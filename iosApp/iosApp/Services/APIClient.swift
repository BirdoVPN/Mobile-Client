import Foundation
import CommonCrypto
import UIKit
import BirdoShared

/// Client version pinned to the Android value so backend rate-limiters and
/// telemetry treat both platforms uniformly. Update with each release.
private let kBirdoClientVersion = "1.2.0"

/// HTTP client for the Birdo VPN API. Uses shared KMP model types.
final class APIClient: @unchecked Sendable {
    static let shared = APIClient()

    private let baseURL: URL
    private let session: URLSession
    private let keychain: KeychainService
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder

    /// Single in-flight refresh task. Concurrent 401s await the same task
    /// instead of racing each other or retrying with the stale token.
    private let refreshActor = RefreshCoordinator()

    /// AUDIT-M-DRIFT: the backend's 2FA login gate is challenge-token based —
    /// `/auth/login/desktop` hands back `{ requiresTwoFactor, challengeToken }`
    /// and `/auth/2fa/verify` consumes that token (see
    /// backend/src/auth/two-factor.controller.ts `VerifyCodeSchema`). It does
    /// NOT re-accept email/password. Park the token here between the two calls
    /// so the ViewModel's existing signatures stay unchanged.
    private let challengeStore = ChallengeStore()

    /// Memoised IDFV — see `deviceId()`. Idempotent, so a concurrent double-read
    /// simply recomputes the same value.
    private var cachedDeviceId: String?

    init(
        baseURL: URL = URL(string: "https://api.birdo.app")!,
        keychain: KeychainService = .shared
    ) {
        self.baseURL = baseURL
        self.keychain = keychain
        self.decoder = JSONDecoder()
        self.encoder = JSONEncoder()

        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        config.httpAdditionalHeaders = [
            "User-Agent": "Birdo-iOS/\(kBirdoClientVersion) (iOS)",
            "X-Desktop-Client": "birdo-ios",
        ]
        // SEC: Disable HTTP cookies + URL cache so auth headers and JSON
        // bodies aren't persisted to disk between launches.
        config.httpCookieStorage = nil
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData

        let delegate = PinningDelegate()
        self.session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    }

    // MARK: - Auth

    func login(email: String, password: String) async throws -> LoginResultType {
        // AUDIT-M-DRIFT: `/auth/login` is the WEB route — it answers `{ ok: true }`
        // and sets httpOnly cookies, returning no tokens at all, so a native client
        // could never obtain credentials from it. `/auth/login/desktop` is the
        // JSON-token route for bundled clients (gated on X-Desktop-Client, which
        // this session sets globally). Mirrors Android `BirdoApi.login`.
        let body = try encoder.encode(LoginBody(email: email, password: password, deviceId: await deviceId()))
        let data = try await post(path: "/auth/login/desktop", body: body, authenticated: false)
        let parsed = try decoder.decode(AuthTokensResponse.self, from: data)

        // 2FA gate: the backend withholds tokens and hands back a challenge token.
        if parsed.requiresTwoFactor == true, let challenge = parsed.challengeToken {
            await challengeStore.set(challenge)
            return .twoFactorRequired
        }

        guard let tokens = parsed.tokens else {
            throw APIError.invalidResponse
        }
        return .success(TokenPairData(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken))
    }

    /// `email`/`password` are unused on the wire — the backend identifies the
    /// pending login by the challenge token issued at `login()`, not by
    /// re-presented credentials. Kept in the signature so callers are unchanged.
    func verifyTwoFactor(email: String, password: String, code: String) async throws -> TokenPairData {
        // PEEK, don't take: the backend calls `consumeChallengeToken` ONLY after
        // the code verifies (two-factor.controller.ts:267, after the !success
        // throw at :261) and rate-limits the route at 10 attempts/min, so a
        // wrong code leaves the challenge valid and a retry is expected.
        // Destroying it on the first attempt stranded the user on the 2FA
        // screen — AuthViewModel keeps `requiresTwoFactor` true on error, so
        // every retry threw .unauthorized with no path forward. Matches
        // Android, which re-reads `uiState.challengeToken` on each attempt.
        guard let challenge = await challengeStore.peek() else {
            throw APIError.unauthorized
        }
        let body = try encoder.encode(TwoFactorBody(challengeToken: challenge, token: code))
        let data = try await post(path: "/auth/2fa/verify", body: body, authenticated: false)
        let parsed = try decoder.decode(AuthTokensResponse.self, from: data)
        guard let tokens = parsed.tokens else {
            throw APIError.invalidResponse
        }
        // Server-side single-use is now spent — drop our copy so it can never
        // be replayed against a later login.
        await challengeStore.clear()
        return TokenPairData(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken)
    }

    func loginAnonymous() async throws -> TokenPairData {
        // AUDIT-M-DRIFT: `/auth/login/anonymous` logs in to an EXISTING account and
        // REQUIRES a 24-digit `anonymousId` (AnonymousLoginSchema) — calling it with
        // an empty body 400s. The in-app "create an anonymous account" path the UI
        // actually offers is `@Post('register/anonymous')`, whose body is device
        // context only and which mints the ID server-side. Mirrors Android
        // `BirdoApi.registerAnonymous`.
        let body = try encoder.encode(DeviceInfoBody(deviceId: await deviceId()))
        let data = try await post(path: "/auth/register/anonymous", body: body, authenticated: false)
        let parsed = try decoder.decode(AuthTokensResponse.self, from: data)
        guard let tokens = parsed.tokens else {
            throw APIError.invalidResponse
        }
        return TokenPairData(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken)
    }

    /// - Parameter password: the account password, required by the backend for
    ///   accounts that HAVE one. Pass `nil`/empty for SSO and anonymous accounts
    ///   (no hash on file) — the backend skips the check for those.
    func deleteAccount(password: String?) async throws {
        // AUDIT-M-DRIFT: there is no `/auth/account` route. Erasure lives on the
        // GDPR controller at `@Controller('api/v1/gdpr')` + `@Delete('delete')`.
        // api.birdo.app proxies to Nest verbatim (no `uri strip_prefix /api` in
        // that Caddy site block), so the `api/v1/gdpr` in the controller path is
        // real and must be sent — this is NOT a double prefix.
        //
        // The body carries an optional password. gdpr.controller.ts answers 401
        // ("Password confirmation is required" / "Incorrect password") for a
        // password account, which is a BUSINESS refusal, not an expired token —
        // hence `refreshOn401: false`. Letting the generic handler run would burn
        // a refresh-token rotation and then report "Session expired. Please log
        // in again.", making erasure impossible for every email-signup account.
        let trimmed = password?.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = try encoder.encode(
            DeleteAccountBody(password: (trimmed?.isEmpty ?? true) ? nil : trimmed)
        )
        _ = try await performRequest(
            method: "DELETE",
            path: "/api/v1/gdpr/delete",
            body: body,
            authenticated: true,
            refreshOn401: false
        )
    }

    // MARK: - Servers

    func fetchServers() async throws -> [ServerInfo] {
        // AUDIT-M-DRIFT: VpnController is mounted at `/vpn`, so the listing is
        // `/vpn/servers` — `/servers` 404'd. Mirrors Android `BirdoApi.getServers`.
        let data = try await get(path: "/vpn/servers")
        return try decoder.decode([ServerInfo].self, from: data)
    }

    // MARK: - VPN Config

    func getConnectConfig(serverId: String) async throws -> VPNConnectionConfig {
        // AUDIT-C1: attach the persistent ML-KEM-1024 client public key so the
        // server can encapsulate against it and ship the ciphertext back in
        // `rosenpassPublicKey` for true bilateral PQ PSK derivation.
        // The Swift `BirdoPQManager` lazy-generates + persists the keypair on
        // first call; if the call returns nil we proceed without PQ rather
        // than block the connect.
        // Post-quantum protection is ON by default; user can disable it via the
        // Quantum Protection toggle. Read the raw stored value so an absent key
        // (fresh install) defaults true regardless of view-model init order.
        let quantumEnabled = UserDefaults.standard.object(forKey: "quantum_protection") as? Bool ?? true
        let pqPk = quantumEnabled ? BirdoPQManager.shared.clientPublicKeyBase64() : nil
        // AUDIT-M-DRIFT: the field is `serverNodeId` (ConnectDto), not `serverId`.
        // The global ValidationPipe is configured `forbidNonWhitelisted: true`
        // (backend/src/main.ts:167), so an unknown `serverId` did NOT get
        // silently stripped — it was REJECTED with a 400 ("property serverId
        // should not exist"), i.e. every connect failed outright.
        let body = try encoder.encode(
            ConnectBody(
                serverNodeId: serverId,
                deviceId: await deviceId(),
                quantumProtection: pqPk == nil ? nil : true,
                pqClientPublicKey: pqPk
            )
        )
        let data = try await post(path: "/vpn/connect", body: body, authenticated: true)
        return try decodeConnectResult(data)
    }

    func getMultiHopConfig(entryId: String, exitId: String) async throws -> VPNConnectionConfig {
        // AUDIT-M-DRIFT: route is `@Post('multi-hop/connect')` and the zod schema
        // (`multiHopConnectSchema`) is `.strict()` with `entryNodeId`/`exitNodeId`.
        // The old `/vpn/multi-hop` + `entryId`/`exitId` 404'd, and would have 400'd
        // on the unknown keys even at the right path.
        let quantumEnabled = UserDefaults.standard.object(forKey: "quantum_protection") as? Bool ?? true
        let pqPk = quantumEnabled ? BirdoPQManager.shared.clientPublicKeyBase64() : nil
        let body = try encoder.encode(
            MultiHopBody(
                entryNodeId: entryId,
                exitNodeId: exitId,
                deviceId: await deviceId(),
                quantumProtection: pqPk == nil ? nil : true,
                pqClientPublicKey: pqPk
            )
        )
        let data = try await post(path: "/vpn/multi-hop/connect", body: body, authenticated: true)
        return try decodeConnectResult(data)
    }

    /// Release the server-side WireGuard peer for a connection. The backend keeps
    /// the peer (and the user's connection slot) alive until this is called —
    /// tearing down only the local tunnel leaks the slot until it is evicted.
    func disconnect(keyId: String) async throws {
        _ = try await performRequest(
            method: "DELETE",
            path: "/vpn/connections/\(keyId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? keyId)",
            body: nil,
            authenticated: true
        )
    }

    /// Both connect routes answer HTTP 2xx with `{ success: false, message }` for
    /// user-actionable refusals (device cap, out-of-plan node, node offline). Pull
    /// the message out instead of surfacing an opaque JSON decoding failure.
    private func decodeConnectResult(_ data: Data) throws -> VPNConnectionConfig {
        if let envelope = try? decoder.decode(ConnectEnvelope.self, from: data),
           envelope.success == false {
            throw APIError.serverMessage(envelope.message ?? "Could not connect. Please try again.")
        }
        return try decoder.decode(VPNConnectionConfig.self, from: data)
    }

    // MARK: - Port Forwarding

    /// AUDIT-M-DRIFT: the routes are `port-forwards` (plural) — the singular paths
    /// 404'd — and `createPortForwardSchema` is `.strict()` with a `protocol` key
    /// whose enum is lowercase `['tcp','udp']`. The old body sent `proto` with an
    /// upper-cased value, which failed validation twice over.
    func fetchPortForwards() async throws -> [PortForwardEntry] {
        let data = try await get(path: "/vpn/port-forwards")
        return try decoder.decode([PortForwardEntry].self, from: data)
    }

    func createPortForward(port: Int, proto: String) async throws -> PortForwardEntry {
        let body = try encoder.encode(
            PortForwardBody(internalPort: port, proto: proto.lowercased())
        )
        let data = try await post(path: "/vpn/port-forwards", body: body, authenticated: true)
        return try decoder.decode(PortForwardEntry.self, from: data)
    }

    func deletePortForward(id: String) async throws {
        _ = try await performRequest(
            method: "DELETE",
            path: "/vpn/port-forwards/\(id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id)",
            body: nil,
            authenticated: true
        )
    }

    // MARK: - Speed Test

    // AUDIT-M-DRIFT: the speed-test trio lives under `/vpn/speed-test/*` on
    // VpnController (`speed-test/ping`, `speed-test/download`, `speed-test/upload`),
    // matching the desktop client's SPEED_TEST_URL. The old `/ping`,
    // `/speedtest/download` and `/speedtest/upload` paths all 404'd.

    func measureLatency() async throws -> (latencyMs: Int, jitterMs: Int) {
        var latencies: [Int] = []
        for _ in 0..<5 {
            let start = CFAbsoluteTimeGetCurrent()
            _ = try await get(path: "/vpn/speed-test/ping")
            let ms = Int((CFAbsoluteTimeGetCurrent() - start) * 1000)
            latencies.append(ms)
        }
        let avg = latencies.reduce(0, +) / max(latencies.count, 1)
        let jitter = latencies.count > 1
            ? latencies.map { abs($0 - avg) }.reduce(0, +) / (latencies.count - 1)
            : 0
        return (avg, jitter)
    }

    func measureDownload() async throws -> Double {
        let start = CFAbsoluteTimeGetCurrent()
        let data = try await get(path: "/vpn/speed-test/download?size=10485760")
        // Floor elapsed at 1 microsecond: a sub-millisecond (or clock-skewed
        // non-positive) measurement would otherwise yield .infinity/NaN Mbps,
        // which renders as "inf Mbps" in the UI.
        let elapsed = max(CFAbsoluteTimeGetCurrent() - start, 0.000001)
        let bits = Double(data.count) * 8
        return bits / elapsed / 1_000_000 // Mbps
    }

    func measureUpload() async throws -> Double {
        let payload = Data(repeating: 0, count: 1_000_000) // 1 MB
        let start = CFAbsoluteTimeGetCurrent()
        // Must NOT be sent as application/json: the global express.json() parser
        // caps bodies at BODY_LIMIT (10kb) and would 413 this payload before the
        // handler — which reads the raw request stream — ever ran.
        _ = try await performRequest(
            method: "POST",
            path: "/vpn/speed-test/upload",
            body: payload,
            authenticated: true,
            contentType: "application/octet-stream"
        )
        // Floor elapsed at 1 microsecond (see measureDownload) to avoid
        // .infinity/NaN Mbps on a sub-millisecond or non-positive interval.
        let elapsed = max(CFAbsoluteTimeGetCurrent() - start, 0.000001)
        let bits = Double(payload.count) * 8
        return bits / elapsed / 1_000_000
    }

    // MARK: - Token Refresh

    private func refreshTokens() async throws {
        guard let refresh = keychain.refreshToken else {
            throw APIError.unauthorized
        }
        // AUDIT-M-DRIFT: the backend reads `refresh_token` (snake_case) from the
        // body for X-Desktop-Client callers and answers
        // `{ ok, access_token, refresh_token, expires_in }` — not the camelCase
        // pair this used to send and expect. Mirrors Android `RefreshRequest`.
        let body = try encoder.encode(RefreshBody(refresh_token: refresh))
        let data = try await post(path: "/auth/refresh", body: body, authenticated: false)
        let tokens = try decoder.decode(RefreshTokensResponse.self, from: data)
        keychain.save(accessToken: tokens.accessToken,
                      // The rotated refresh token replaces the old one; if the
                      // server omits it, the presented token remains current.
                      refreshToken: tokens.refreshToken ?? refresh,
                      email: keychain.userEmail)
    }

    // MARK: - Device Identity

    /// SSOT device identity (see `ConnectDto.deviceId`: "Android SSAID hash /
    /// iOS IDFV / desktop host hash"). Sent on login and connect so a returning
    /// install reclaims its OWN connection slot instead of burning a new one.
    /// Cached after the first main-actor hop; `identifierForVendor` is stable for
    /// the lifetime of the vendor's apps on the device.
    private func deviceId() async -> String? {
        if let cached = cachedDeviceId { return cached }
        let value = await MainActor.run { UIDevice.current.identifierForVendor?.uuidString }
        cachedDeviceId = value
        return value
    }

    // MARK: - Core HTTP

    private func get(path: String) async throws -> Data {
        try await performRequest(method: "GET", path: path, body: nil, authenticated: true)
    }

    private func post(path: String, body: Data?, authenticated: Bool) async throws -> Data {
        try await performRequest(method: "POST", path: path, body: body, authenticated: authenticated)
    }

    private func performRequest(
        method: String,
        path: String,
        body: Data?,
        authenticated: Bool,
        contentType: String = "application/json",
        /// Set false for endpoints where 401 is a BUSINESS refusal rather than an
        /// expired access token (e.g. GDPR erasure's password re-confirmation).
        /// Refresh-and-retry there wastes a token rotation and replaces the
        /// backend's own explanation with a bogus "Session expired".
        refreshOn401: Bool = true
    ) async throws -> Data {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw APIError.invalidURL
        }
        // SEC: refuse to ever issue a non-HTTPS request, even if a future
        // override slips an http:// base URL into config.
        guard url.scheme?.lowercased() == "https" else {
            throw APIError.invalidURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = body

        if authenticated, let token = keychain.accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        let (data, response) = try await session.data(for: request)

        guard let http = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }

        // Handle 401 — refresh once, then retry. Concurrent 401s coalesce
        // through `refreshActor` so we never refresh twice in parallel.
        if http.statusCode == 401 && authenticated && refreshOn401 {
            do {
                try await refreshActor.refresh { [weak self] in
                    try await self?.refreshTokens()
                }
            } catch {
                throw APIError.unauthorized
            }
            var retry = URLRequest(url: url)
            retry.httpMethod = method
            retry.setValue(contentType, forHTTPHeaderField: "Content-Type")
            retry.httpBody = body
            if let newToken = keychain.accessToken {
                retry.setValue("Bearer \(newToken)", forHTTPHeaderField: "Authorization")
            }
            let (retryData, retryResponse) = try await session.data(for: retry)
            guard let retryHttp = retryResponse as? HTTPURLResponse else {
                throw APIError.invalidResponse
            }
            guard (200...299).contains(retryHttp.statusCode) else {
                if retryHttp.statusCode == 401 { throw APIError.unauthorized }
                throw Self.error(status: retryHttp.statusCode, body: retryData)
            }
            return retryData
        }

        guard (200...299).contains(http.statusCode) else {
            throw Self.error(status: http.statusCode, body: data)
        }
        return data
    }

    /// Surface the backend's own refusal text ("Port forwarding requires a
    /// Sovereign subscription", "Device limit reached") instead of a bare status
    /// code. Nest error bodies carry `message` as either a string or an array of
    /// validation strings.
    private static func error(status: Int, body: Data) -> APIError {
        if let parsed = try? JSONDecoder().decode(APIErrorBody.self, from: body),
           let message = parsed.message?.text, !message.isEmpty {
            return .serverMessage(message)
        }
        return .httpError(status)
    }
}

// MARK: - Refresh Coordinator

/// Serialises concurrent token refreshes. The first 401 starts the refresh;
/// every subsequent caller awaits the same in-flight task and then retries
/// with the freshly-stored token.
/// Holds the short-lived 2FA challenge token between `login()` and
/// `verifyTwoFactor()`. The token survives a FAILED verification (the backend
/// only consumes it on success) so the user can retry a mistyped code;
/// `clear()` drops it once it has actually been spent, so a stale token can
/// never be replayed against a later login attempt. `set()` on a new login
/// overwrites any leftover token.
private actor ChallengeStore {
    private var token: String?

    func set(_ value: String) { token = value }

    func peek() -> String? { token }

    func clear() { token = nil }
}

private actor RefreshCoordinator {
    private var inFlight: Task<Void, Error>?

    func refresh(_ work: @escaping @Sendable () async throws -> Void) async throws {
        if let task = inFlight {
            try await task.value
            return
        }
        let task = Task<Void, Error> { try await work() }
        inFlight = task
        defer { inFlight = nil }
        try await task.value
    }
}

// MARK: - Certificate Pinning

/// SPKI-pinning URLSession delegate. Pins are kept in sync with the Android
/// `NetworkModule.kt` set and the `network_security_config.xml` file.
/// Pin-set expiration: 2027-06-01.
private final class PinningDelegate: NSObject, URLSessionDelegate {
    /// Base64-encoded SHA-256 hashes of the SubjectPublicKeyInfo (SPKI) of
    /// every certificate we accept. We require a match against any cert in
    /// the chain (intermediate or root), giving us CA-migration headroom.
    private static let pins: Set<String> = [
        // WE1 — Google Trust Services intermediate (verified 2026-02-22)
        "kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
        // GlobalSign ECC Root CA - R4 (verified 2026-02-22)
        "CLOmM1/OXvSPjw5UOYbAf9GKOxImEp9hhku9W90fHMk=",
        // ISRG Root X1 — Let's Encrypt root (cross-CA diversity backup)
        "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
    ]

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        // Standard CA validation must succeed first.
        var trustError: CFError?
        guard SecTrustEvaluateWithError(trust, &trustError) else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        // Then SPKI pinning: at least one cert in the chain must match.
        // SecTrustCopyCertificateChain (iOS 15+) replaces the deprecated
        // SecTrustGetCertificateAtIndex/SecTrustGetCertificateCount pair.
        // If the chain cannot be read we FAIL CLOSED — never fall through to
        // .useCredential, which would silently disable pinning.
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate] else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        for cert in chain {
            guard let pubKey = SecCertificateCopyKey(cert),
                  let pubKeyData = SecKeyCopyExternalRepresentation(pubKey, nil) as Data? else {
                continue
            }
            let spkiHeader = Self.spkiHeader(for: pubKey)
            var hashable = spkiHeader
            hashable.append(pubKeyData)
            let hash = Self.sha256(hashable).base64EncodedString()
            if Self.pins.contains(hash) {
                completionHandler(.useCredential, URLCredential(trust: trust))
                return
            }
        }
        completionHandler(.cancelAuthenticationChallenge, nil)
    }

    /// ASN.1 SubjectPublicKeyInfo header bytes that prefix the raw key data
    /// produced by `SecKeyCopyExternalRepresentation`. We only need the RSA
    /// 2048 + EC P-256 / P-384 prefixes to cover every CA in our pin set.
    private static func spkiHeader(for key: SecKey) -> Data {
        let attrs = SecKeyCopyAttributes(key) as? [CFString: Any] ?? [:]
        let type = (attrs[kSecAttrKeyType] as? String) ?? ""
        let size = (attrs[kSecAttrKeySizeInBits] as? Int) ?? 0
        // Bridge the CFString constants to String up front: inside a `case`
        // pattern `x as String` parses as a cast pattern, not an expression.
        let rsaType = kSecAttrKeyTypeRSA as String
        let ecType = kSecAttrKeyTypeECSECPrimeRandom as String
        switch (type, size) {
        case (rsaType, 2048):
            return Data([
                0x30, 0x82, 0x01, 0x22, 0x30, 0x0d, 0x06, 0x09,
                0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00, 0x03, 0x82, 0x01, 0x0f, 0x00,
            ])
        case (rsaType, 4096):
            return Data([
                0x30, 0x82, 0x02, 0x22, 0x30, 0x0d, 0x06, 0x09,
                0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00, 0x03, 0x82, 0x02, 0x0f, 0x00,
            ])
        case (ecType, 256):
            return Data([
                0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86,
                0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
                0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03,
                0x42, 0x00,
            ])
        case (ecType, 384):
            return Data([
                0x30, 0x76, 0x30, 0x10, 0x06, 0x07, 0x2a, 0x86,
                0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x05, 0x2b,
                0x81, 0x04, 0x00, 0x22, 0x03, 0x62, 0x00,
            ])
        default:
            return Data() // Unknown key type — hash will not match any pin.
        }
    }

    private static func sha256(_ data: Data) -> Data {
        var hash = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes {
            _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &hash)
        }
        return Data(hash)
    }
}

// MARK: - API Models

enum APIError: Error, LocalizedError {
    case invalidURL
    case invalidResponse
    case unauthorized
    case httpError(Int)
    /// A refusal the backend explained in its own words — show it verbatim.
    case serverMessage(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid URL"
        case .invalidResponse: return "Invalid server response"
        case .unauthorized: return "Session expired. Please log in again."
        case .httpError(let code): return "Server error (\(code))"
        case .serverMessage(let message): return message
        }
    }
}

/// Nest error envelope. `message` is a string for thrown HttpExceptions and an
/// array of strings for class-validator failures, so decode both.
private struct APIErrorBody: Decodable {
    enum Message: Decodable {
        case single(String)
        case many([String])

        var text: String {
            switch self {
            case .single(let s): return s
            case .many(let list): return list.joined(separator: " ")
            }
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if let s = try? container.decode(String.self) {
                self = .single(s)
            } else {
                self = .many(try container.decode([String].self))
            }
        }
    }

    let message: Message?
}

private struct LoginBody: Encodable {
    let email: String
    let password: String
    /// Device attribution — also lets a trusted device skip the 2FA challenge.
    let deviceId: String?
    let deviceType = "MOBILE"
    let platform = "IOS"
}

/// Body for `POST /auth/register/anonymous` — device context only; the server
/// mints the 24-digit anonymous ID.
private struct DeviceInfoBody: Encodable {
    let deviceId: String?
    let deviceType = "MOBILE"
    let platform = "IOS"
}

private struct TwoFactorBody: Encodable {
    let challengeToken: String
    let token: String
}

private struct DeleteAccountBody: Encodable {
    let password: String?
}

private struct ConnectBody: Encodable {
    let serverNodeId: String
    /// SSOT stable device identity — reclaims this device's own slot.
    let deviceId: String?
    /// AUDIT-C1: opt the user into bilateral PQ when we have a client pk to
    /// send. Server interprets this together with `pqClientPublicKey`.
    let quantumProtection: Bool?
    /// AUDIT-C1: BirdoPQ v1 ML-KEM-1024 client public key (Base64).
    let pqClientPublicKey: String?
}

private struct MultiHopBody: Encodable {
    let entryNodeId: String
    let exitNodeId: String
    let deviceId: String?
    let quantumProtection: Bool?
    let pqClientPublicKey: String?
}

private struct PortForwardBody: Encodable {
    let internalPort: Int
    let proto: String

    // The API field is `protocol`, which is a Swift keyword — map it to `proto`,
    // matching the decoding side in `PortForwardEntry`.
    private enum CodingKeys: String, CodingKey {
        case internalPort
        case proto = "protocol"
    }
}

private struct RefreshBody: Encodable {
    // swiftlint:disable:next identifier_name — snake_case is the wire contract.
    let refresh_token: String
}

/// Token pair as the backend emits it (`AuthService.login` → snake_case).
private struct TokenPairDTO: Decodable {
    let accessToken: String
    let refreshToken: String

    private enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
    }
}

/// Shared response shape of `/auth/login/desktop`, `/auth/2fa/verify` and
/// `/auth/register/anonymous`: tokens on success, or a 2FA challenge.
private struct AuthTokensResponse: Decodable {
    let tokens: TokenPairDTO?
    let requiresTwoFactor: Bool?
    let challengeToken: String?
    /// Returned ONLY by `/auth/register/anonymous`, and only once. It is the
    /// sole credential for an anonymous account — `/auth/me` never replays it
    /// and `/auth/login/anonymous` demands it — so dropping it here would make
    /// the account permanently unrecoverable after a logout or reinstall.
    let anonymousId: String?
}

/// `/auth/refresh` returns the rotated pair at the top level, not nested.
private struct RefreshTokensResponse: Decodable {
    let accessToken: String
    let refreshToken: String?

    private enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
    }
}

/// The `{ success, message }` envelope both connect routes use for
/// user-actionable refusals returned with a 2xx status.
private struct ConnectEnvelope: Decodable {
    let success: Bool?
    let message: String?
}

/// VPN connection configuration returned by server.
///
/// AUDIT-M-DRIFT: the backend does NOT emit these field names. `/vpn/connect`
/// and `/vpn/multi-hop/connect` return (see `VpnService.connect`'s
/// `ConnectionResult`): `endpoint` as a single "host:port" string, `assignedIp`
/// as a bare IPv4 with no prefix, the optional `clientIpv6` likewise bare,
/// `serverPublicKey` for the PEER key (`publicKey` is the CLIENT's own key —
/// using it as the peer key would have made every handshake fail), and
/// `allowedIps` with a lowercase "ps". The decoder below translates the wire
/// shape into the property names the rest of the app already uses.
struct VPNConnectionConfig: Decodable {
    let serverAddress: String
    let serverPort: Int
    let privateKey: String
    let publicKey: String
    let presharedKey: String?
    let addresses: [String]
    let dns: [String]
    let allowedIPs: [String]
    let mtu: Int?

    /// Server-side handle for this connection. Required to release the peer via
    /// `DELETE /vpn/connections/{keyId}` on disconnect.
    let keyId: String?

    /// AUDIT-C1 (BirdoPQ v1, optional): set when the server received a
    /// `pqClientPublicKey` and produced a per-connect ML-KEM ciphertext for
    /// the client to decapsulate. The Swift `BirdoPQManager` derives a
    /// 32-byte WireGuard PSK from these fields client-side.
    let quantumEnabled: Bool?
    let rosenpassPublicKey: String?
    let rosenpassEndpoint: String?

    private enum CodingKeys: String, CodingKey {
        case endpoint, privateKey, serverPublicKey, presharedKey
        case assignedIp, clientIpv6, dns, allowedIps, mtu, keyId
        case quantumEnabled, rosenpassPublicKey, rosenpassEndpoint
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)

        // "1.2.3.4:51820" — split on the LAST colon so a future IPv6 literal
        // endpoint doesn't get shredded.
        let endpoint = try c.decode(String.self, forKey: .endpoint)
        guard let separator = endpoint.lastIndex(of: ":"),
              let port = Int(endpoint[endpoint.index(after: separator)...]) else {
            throw VPNConfigValidationError.invalidServerAddress
        }
        serverAddress = String(endpoint[..<separator])
        serverPort = port

        privateKey = try c.decode(String.self, forKey: .privateKey)
        publicKey = try c.decode(String.self, forKey: .serverPublicKey)
        presharedKey = try c.decodeIfPresent(String.self, forKey: .presharedKey)

        // The tunnel addresses arrive as bare IPs; WireGuard needs prefixes.
        // Host-scoped, matching the .conf the backend writes (`/32`, `/128`).
        let assignedIp = try c.decode(String.self, forKey: .assignedIp)
        var tunnelAddresses = [assignedIp + "/32"]
        if let v6 = try c.decodeIfPresent(String.self, forKey: .clientIpv6), !v6.isEmpty {
            tunnelAddresses.append(v6 + "/128")
        }
        addresses = tunnelAddresses

        dns = try c.decodeIfPresent([String].self, forKey: .dns) ?? []
        allowedIPs = try c.decodeIfPresent([String].self, forKey: .allowedIps) ?? []
        mtu = try c.decodeIfPresent(Int.self, forKey: .mtu)
        keyId = try c.decodeIfPresent(String.self, forKey: .keyId)

        quantumEnabled = try c.decodeIfPresent(Bool.self, forKey: .quantumEnabled)
        rosenpassPublicKey = try c.decodeIfPresent(String.self, forKey: .rosenpassPublicKey)
        rosenpassEndpoint = try c.decodeIfPresent(String.self, forKey: .rosenpassEndpoint)
    }

    /// Hardening: every field is treated as untrusted server input.
    /// Reject malformed configs *before* they touch the system VPN.
    /// Throws `VPNConfigValidationError` describing the first failure.
    func validate() throws {
        guard !serverAddress.isEmpty,
              serverAddress.count <= 255,
              !serverAddress.contains(where: { $0.isNewline || $0.isWhitespace })
        else { throw VPNConfigValidationError.invalidServerAddress }

        guard (1...65535).contains(serverPort) else {
            throw VPNConfigValidationError.invalidPort
        }
        guard Self.isValidWireGuardKey(privateKey) else {
            throw VPNConfigValidationError.invalidPrivateKey
        }
        guard Self.isValidWireGuardKey(publicKey) else {
            throw VPNConfigValidationError.invalidPublicKey
        }
        if let psk = presharedKey, !psk.isEmpty {
            guard Self.isValidWireGuardKey(psk) else {
                throw VPNConfigValidationError.invalidPresharedKey
            }
        }
        guard !addresses.isEmpty, addresses.count <= 16 else {
            throw VPNConfigValidationError.invalidAddresses
        }
        for cidr in addresses where !Self.isValidCIDR(cidr) {
            throw VPNConfigValidationError.invalidAddresses
        }
        guard allowedIPs.count <= 32 else {
            throw VPNConfigValidationError.invalidAllowedIPs
        }
        for cidr in allowedIPs where !Self.isValidCIDR(cidr) {
            throw VPNConfigValidationError.invalidAllowedIPs
        }
        if let mtu, !(1280...1500).contains(mtu) {
            throw VPNConfigValidationError.invalidMTU
        }
        // DNS list may legitimately be empty; if present, validate each entry.
        for d in dns where !Self.isValidIP(d) {
            throw VPNConfigValidationError.invalidDNS
        }
    }

    private static func isValidWireGuardKey(_ b64: String) -> Bool {
        guard let data = Data(base64Encoded: b64) else { return false }
        return data.count == 32
    }

    private static func isValidCIDR(_ cidr: String) -> Bool {
        let parts = cidr.split(separator: "/", maxSplits: 1)
        guard parts.count == 2,
              let prefix = Int(parts[1])
        else { return false }
        let host = String(parts[0])
        guard isValidIP(host) else { return false }
        let maxPrefix = host.contains(":") ? 128 : 32
        return (0...maxPrefix).contains(prefix)
    }

    private static func isValidIP(_ s: String) -> Bool {
        var v4 = in_addr(); var v6 = in6_addr()
        if s.withCString({ inet_pton(AF_INET, $0, &v4) }) == 1 { return true }
        if s.withCString({ inet_pton(AF_INET6, $0, &v6) }) == 1 { return true }
        return false
    }
}

enum VPNConfigValidationError: LocalizedError {
    case invalidServerAddress
    case invalidPort
    case invalidPrivateKey
    case invalidPublicKey
    case invalidPresharedKey
    case invalidAddresses
    case invalidAllowedIPs
    case invalidMTU
    case invalidDNS

    var errorDescription: String? {
        switch self {
        case .invalidServerAddress: return "Server returned an invalid endpoint host."
        case .invalidPort:          return "Server returned an invalid port."
        case .invalidPrivateKey:    return "Server returned an invalid private key."
        case .invalidPublicKey:     return "Server returned an invalid peer public key."
        case .invalidPresharedKey:  return "Server returned an invalid pre-shared key."
        case .invalidAddresses:     return "Server returned invalid tunnel addresses."
        case .invalidAllowedIPs:    return "Server returned invalid AllowedIPs."
        case .invalidMTU:           return "Server returned an out-of-range MTU."
        case .invalidDNS:           return "Server returned an invalid DNS address."
        }
    }
}
