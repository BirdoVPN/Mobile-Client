import Foundation
import CommonCrypto
import CryptoKit
#if canImport(UIKit)
import UIKit
#endif

/// On-device WireGuard (Curve25519) keypair as WireGuard-format Base64 strings.
///
/// Generating the keypair locally keeps the tunnel PRIVATE key on the device:
/// we send only the public key, the backend registers it as this connection's
/// peer key and omits `privateKey` from the response. This is parity with
/// Android's FIX-1-1 (`BirdoRepository.connectVpn` → `com.wireguard.crypto.KeyPair`).
/// Previously iOS sent no client public key, so the operator's control plane
/// minted and transmitted every iOS user's WireGuard private key over the wire.
enum WireGuardKeypair {
    static func generate() -> (privateKey: String, publicKey: String) {
        let priv = Curve25519.KeyAgreement.PrivateKey()
        return (
            priv.rawRepresentation.base64EncodedString(),
            priv.publicKey.rawRepresentation.base64EncodedString()
        )
    }
}

/// Client version reported in the User-Agent and every device payload. Read
/// from the bundle so a release bump propagates automatically — backend
/// telemetry keys off this value, and any future iOS version floor enforces
/// against it.
///
/// The fallback is deliberately NOT a plausible version. It used to be "1.0.0",
/// which was also the value both Info.plists hardcoded, so a genuine release and
/// a completely unversioned build were indistinguishable on the wire — and were,
/// for months. "0.0.0-unknown" can never be mistaken for a shipped version, so
/// if it ever appears in telemetry it names its own bug.
private let kBirdoClientVersion: String = {
    guard let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
          !v.isEmpty
    else { return "0.0.0-unknown" }
    return v
}()

/// HTTP client for the Birdo VPN API.
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

    /// Monotonic session generation. `invalidateSession()` (called by
    /// AuthViewModel.completeLocalLogout) bumps it; an in-flight refresh
    /// captures it before the network call and refuses to persist rotated
    /// tokens if it changed — fencing ALL post-logout writes so a refresh that
    /// lands AFTER sign-out can never resurrect the wiped session (finding #1).
    private let sessionGeneration = SessionGeneration()

    /// Memoised device identity — see `deviceContext()`. Idempotent, so a
    /// concurrent double-COMPUTE simply recomputes the same value — but the
    /// read/write themselves cross concurrency domains, so they go through
    /// `deviceContextLock` (an unsynchronised var in an `@unchecked Sendable`
    /// class is a data race, and a hard error under Swift 6 strict checking).
    private let deviceContextLock = NSLock()
    private var _cachedDeviceContext: DeviceIdentity?
    private var cachedDeviceContext: DeviceIdentity? {
        get { deviceContextLock.lock(); defer { deviceContextLock.unlock() }; return _cachedDeviceContext }
        set { deviceContextLock.lock(); defer { deviceContextLock.unlock() }; _cachedDeviceContext = newValue }
    }

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

    func login(email: String, password: String) async throws -> LoginResult {
        // AUDIT-M-DRIFT: `/auth/login` is the WEB route — it answers `{ ok: true }`
        // and sets httpOnly cookies, returning no tokens at all, so a native client
        // could never obtain credentials from it. `/auth/login/desktop` is the
        // JSON-token route for bundled clients (gated on X-Desktop-Client, which
        // this session sets globally). Mirrors Android `BirdoApi.login`.
        let device = await deviceContext()
        let body = try encoder.encode(LoginBody(
            email: email,
            password: password,
            deviceId: device.id,
            deviceName: device.name,
            platformVersion: device.osVersion,
            appVersion: kBirdoClientVersion
        ))
        let data = try await post(path: "/auth/login/desktop", body: body, authenticated: false)
        return try parseAuthResponse(data)
    }

    /// The backend identifies the pending login by the challenge token issued
    /// alongside `{ requiresTwoFactor: true }` — it never re-accepts
    /// email/password. The token lives in the ViewModel between the two calls
    /// (NOT the keychain) and SURVIVES a failed code: the backend consumes it
    /// only after a successful verify (two-factor.controller.ts, rate-limited
    /// 10/min), so retrying a mistyped code with the same token is expected.
    func verifyTwoFactor(challengeToken: String, code: String) async throws -> TokenPairData {
        let body = try encoder.encode(TwoFactorBody(challengeToken: challengeToken, token: code))
        let data = try await post(path: "/auth/2fa/verify", body: body, authenticated: false)
        let parsed = try decoder.decode(AuthTokensResponse.self, from: data)
        guard let tokens = parsed.tokens, !tokens.accessToken.isEmpty else {
            throw APIError.invalidResponse
        }
        return TokenPairData(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken)
    }

    /// Log in to an EXISTING anonymous account. `anonymousId` is the 24-digit
    /// account number; `password` is sent only when non-nil (the backend
    /// deliberately rejects a password supplied to a password-less account, so
    /// callers must pass nil — not "" — when the field was left blank).
    /// Anonymous accounts CAN have 2FA (set via the website), hence the
    /// `LoginResult` return.
    func loginAnonymous(anonymousId: String, password: String?) async throws -> LoginResult {
        let device = await deviceContext()
        let body = try encoder.encode(AnonymousLoginBody(
            anonymousId: anonymousId,
            password: password,
            deviceId: device.id,
            deviceName: device.name,
            platformVersion: device.osVersion,
            appVersion: kBirdoClientVersion
        ))
        let data = try await post(path: "/auth/login/anonymous", body: body, authenticated: false)
        return try parseAuthResponse(data)
    }

    /// Create a NEW anonymous account — `POST /auth/register/anonymous`
    /// (answers HTTP 201; body is device context only, the server mints the
    /// 24-digit ID). The returned `anonymousId` is surfaced ONCE and is the
    /// account's sole recovery credential — callers MUST persist/display it.
    /// Server rate limit: 3 creations per IP per hour.
    func registerAnonymous() async throws -> AnonymousRegistration {
        let device = await deviceContext()
        let body = try encoder.encode(DeviceInfoBody(
            deviceId: device.id,
            deviceName: device.name,
            platformVersion: device.osVersion,
            appVersion: kBirdoClientVersion
        ))
        let data = try await post(path: "/auth/register/anonymous", body: body, authenticated: false)
        let parsed = try decoder.decode(AuthTokensResponse.self, from: data)
        guard parsed.ok != false, let tokens = parsed.tokens, !tokens.accessToken.isEmpty else {
            // Client-minted: the response WAS 2xx, we just could not use it.
            throw APIError.serverMessage("Could not create an anonymous account. Please try again.", status: nil)
        }
        return AnonymousRegistration(
            anonymousId: parsed.anonymousId,
            tokens: TokenPairData(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken)
        )
    }

    /// SSO handoff exchange — `POST /auth/native/exchange`. The `code` is the
    /// single-use, ~60s-lived, PKCE-bound token the broker redirected back
    /// with (`birdo://auth?code=…`); fire this EXACTLY once per code (a replay
    /// gets 401 "This sign-in code has already been used"). Response shape is
    /// identical to password login — SSO does NOT bypass a user's 2FA.
    func exchangeSsoCode(code: String, codeVerifier: String) async throws -> LoginResult {
        let device = await deviceContext()
        let body = try encoder.encode(SsoExchangeBody(
            code: code,
            codeVerifier: codeVerifier,
            deviceId: device.id,
            deviceName: device.name,
            platformVersion: device.osVersion,
            appVersion: kBirdoClientVersion
        ))
        let data = try await post(path: "/auth/native/exchange", body: body, authenticated: false)
        return try parseAuthResponse(data)
    }

    /// Identity hydration — `GET /auth/me` (Bearer). Login is never blocked on
    /// this call; a failure leaves the session logged-in with `user == nil`.
    /// Moved off the brute-force rate bucket server-side (web #279), but do
    /// not poll it.
    func fetchProfile() async throws -> UserProfile {
        let data = try await get(path: "/auth/me")
        return try decoder.decode(UserProfile.self, from: data)
    }

    /// Best-effort server-side session revocation — `POST /auth/logout`.
    /// Never throws: local sign-out proceeds regardless. Takes the bearer
    /// token explicitly because the caller clears the keychain synchronously
    /// right after firing this (the stored token would already be gone).
    func logout(bearerToken: String?) async {
        guard let token = bearerToken, !token.isEmpty else { return }
        guard let url = URL(string: "/auth/logout", relativeTo: baseURL),
              url.scheme?.lowercased() == "https" else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        _ = try? await session.data(for: request)
    }

    /// Shared response handling for `/auth/login/desktop`,
    /// `/auth/login/anonymous` and `/auth/native/exchange`: either a full
    /// token pair or a pending 2FA challenge. Note the mixed casing is the
    /// wire contract — `tokens.access_token` snake_case, `requiresTwoFactor`
    /// camelCase.
    private func parseAuthResponse(_ data: Data) throws -> LoginResult {
        let parsed = try decoder.decode(AuthTokensResponse.self, from: data)
        if parsed.requiresTwoFactor == true, let challenge = parsed.challengeToken, !challenge.isEmpty {
            return .twoFactorRequired(challengeToken: challenge)
        }
        guard parsed.ok != false,
              let tokens = parsed.tokens,
              !tokens.accessToken.isEmpty, !tokens.refreshToken.isEmpty else {
            // Client-minted sentinel on a 2xx body — NOT a credential rejection.
            throw APIError.serverMessage("Unexpected server response (no tokens)", status: nil)
        }
        return .success(TokenPairData(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken))
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
        // 401s on this route come from TWO distinct layers (gdpr.controller.ts):
        //   1. JwtAuthGuard rejects an EXPIRED access token BEFORE the handler
        //      runs — recoverable, needs a refresh-then-retry.
        //   2. The handler itself throws 401 ("Password confirmation is
        //      required" / "Incorrect password") for a password account — a
        //      BUSINESS refusal.
        // Finding #217: the old `refreshOn401: false` treated BOTH as terminal,
        // so an expired token dead-ended — erasure was impossible for anyone
        // whose access token had aged out (the common case). We now let the
        // generic refresh-then-retry run (`refreshOn401: true`): case 1 refreshes
        // and the retry reaches the real password check; case 2 refreshes once,
        // the retry hits the SAME password 401, and performRequest surfaces it as
        // .unauthorized — which mapDeleteError renders as "Incorrect password",
        // the correct message. The only cost is one wasted rotation on a wrong
        // password (the backend's rotation grace window keeps that safe), which
        // is far better than a permanently un-deletable account.
        let trimmed = password?.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = try encoder.encode(
            DeleteAccountBody(password: (trimmed?.isEmpty ?? true) ? nil : trimmed)
        )
        _ = try await performRequest(
            method: "DELETE",
            path: "/api/v1/gdpr/delete",
            body: body,
            authenticated: true,
            refreshOn401: true
        )
    }

    // MARK: - Servers

    func fetchServers() async throws -> [ServerInfo] {
        // AUDIT-M-DRIFT: VpnController is mounted at `/vpn`, so the listing is
        // `/vpn/servers` — `/servers` 404'd. Mirrors Android `BirdoApi.getServers`.
        let data = try await get(path: "/vpn/servers")
        return try decoder.decode([ServerInfo].self, from: data)
    }

    /// Public, UNAUTHENTICATED location list — `GET /vpn/locations`
    /// (backend `PublicVpnController`, no guard, 60 s server-side cache).
    ///
    /// This is what makes the guest shell honest: `GET /vpn/servers` is
    /// per-account (it is behind `JwtAuthGuard` and its `accessible` flag is
    /// computed from THIS user's plan), so it cannot be shown signed out. The
    /// public endpoint carries no per-user data at all — city, country, node
    /// count, ONLINE/MAINTENANCE — which is exactly the browsable location
    /// list a signed-out user is entitled to see (5.1.1(v)).
    func fetchPublicLocations() async throws -> [PublicLocation] {
        let data = try await performRequest(method: "GET",
                                            path: "/vpn/locations",
                                            body: nil,
                                            authenticated: false,
                                            refreshOn401: false)
        return try decoder.decode(PublicLocationsEnvelope.self, from: data).locations
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
        // Distinguish "user turned PQ off" from "PQ is on but we could not produce
        // a key". Both used to yield nil and therefore silently STOP requesting
        // post-quantum protection, so a keypair failure downgraded the session
        // while the UI still showed Quantum Protection enabled. Android hard-errors
        // here; iOS was the outlier.
        if quantumEnabled && pqPk == nil {
            throw APIError.quantumKeyUnavailable
        }
        // Generate the WireGuard keypair on-device; send only the public key.
        let wg = WireGuardKeypair.generate()
        // AUDIT-M-DRIFT: the field is `serverNodeId` (ConnectDto), not `serverId`.
        // The global ValidationPipe is configured `forbidNonWhitelisted: true`
        // (backend/src/main.ts:167), so an unknown `serverId` did NOT get
        // silently stripped — it was REJECTED with a 400 ("property serverId
        // should not exist"), i.e. every connect failed outright.
        let body = try encoder.encode(
            ConnectBody(
                serverNodeId: serverId,
                deviceId: await deviceContext().id,
                clientPublicKey: wg.publicKey,
                quantumProtection: pqPk == nil ? nil : true,
                pqClientPublicKey: pqPk,
                pqClientCanDecapsulate: pqPk == nil ? nil : true
            )
        )
        let data = try await post(path: "/vpn/connect", body: body, authenticated: true)
        var config = try decodeConnectResult(data)
        // The private key never came from the server — use the on-device one.
        config.privateKey = wg.privateKey
        return config
    }

    func getMultiHopConfig(entryId: String, exitId: String) async throws -> VPNConnectionConfig {
        // AUDIT-M-DRIFT: route is `@Post('multi-hop/connect')` and the zod schema
        // (`multiHopConnectSchema`) is `.strict()` with `entryNodeId`/`exitNodeId`.
        // The old `/vpn/multi-hop` + `entryId`/`exitId` 404'd, and would have 400'd
        // on the unknown keys even at the right path.
        let quantumEnabled = UserDefaults.standard.object(forKey: "quantum_protection") as? Bool ?? true
        let pqPk = quantumEnabled ? BirdoPQManager.shared.clientPublicKeyBase64() : nil
        // Distinguish "user turned PQ off" from "PQ is on but we could not produce
        // a key". Both used to yield nil and therefore silently STOP requesting
        // post-quantum protection, so a keypair failure downgraded the session
        // while the UI still showed Quantum Protection enabled. Android hard-errors
        // here; iOS was the outlier.
        if quantumEnabled && pqPk == nil {
            throw APIError.quantumKeyUnavailable
        }
        // Generate the WireGuard keypair on-device; send only the public key.
        let wg = WireGuardKeypair.generate()
        let body = try encoder.encode(
            MultiHopBody(
                entryNodeId: entryId,
                exitNodeId: exitId,
                deviceId: await deviceContext().id,
                clientPublicKey: wg.publicKey,
                quantumProtection: pqPk == nil ? nil : true,
                pqClientPublicKey: pqPk,
                pqClientCanDecapsulate: pqPk == nil ? nil : true
            )
        )
        let data = try await post(path: "/vpn/multi-hop/connect", body: body, authenticated: true)
        var config = try decodeConnectResult(data)
        // The private key never came from the server — use the on-device one.
        config.privateKey = wg.privateKey
        return config
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
            // Carried on a 2xx, so there is no error status to attribute.
            throw APIError.serverMessage(envelope.message ?? "Could not connect. Please try again.", status: nil)
        }
        return try decoder.decode(VPNConnectionConfig.self, from: data)
    }

    // MARK: - Subscription Stats & Heartbeat

    /// `GET /vpn/stats` — the CANONICAL subscription/plan source (there is no
    /// `/users/subscription` route). Powers plan gating, the Limit tab's usage
    /// meter and the Profile/Subscription current-plan cards. Callers cache
    /// ~30 s client-side (VpnViewModel does, mirroring Android's repository);
    /// do not poll it tighter.
    func fetchVpnStats() async throws -> VpnStats {
        let data = try await get(path: "/vpn/stats")
        return try decoder.decode(VpnStats.self, from: data)
    }

    /// `POST /vpn/heartbeat/{keyId}` — 30 s keepalive while connected, mirroring
    /// Android's `VpnManager.startHeartbeat`. The backend answers
    /// `{ valid, serverOnline, message? }`; `valid: false` means the connection
    /// was revoked server-side (free-tier eviction is silent by design — this
    /// heartbeat is the ONLY way a replaced client ever learns about it).
    func heartbeat(keyId: String) async throws -> HeartbeatResult {
        let data = try await performRequest(
            method: "POST",
            path: "/vpn/heartbeat/\(keyId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? keyId)",
            body: nil,
            authenticated: true
        )
        return try decoder.decode(HeartbeatResult.self, from: data)
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

    // MARK: - Session Invalidation

    /// Fence every in-flight/late token write against a sign-out that already
    /// happened. AuthViewModel.completeLocalLogout() calls this AFTER
    /// keychain.clear(): it bumps the session generation so a refresh whose
    /// network call was already in flight drops its rotated pair instead of
    /// re-persisting valid tokens over the wiped keychain (finding #1).
    func invalidateSession() async {
        await sessionGeneration.bump()
    }

    // MARK: - Token Refresh

    private func refreshTokens() async throws {
        guard let refresh = keychain.refreshToken else {
            throw APIError.unauthorized
        }
        // Capture the session generation BEFORE the network call so we can tell
        // whether a logout landed while the refresh was in flight.
        let generationAtStart = await sessionGeneration.current
        // AUDIT-M-DRIFT: the backend reads `refresh_token` (snake_case) from the
        // body for X-Desktop-Client callers and answers
        // `{ ok, access_token, refresh_token, expires_in }` — not the camelCase
        // pair this used to send and expect. Mirrors Android `RefreshRequest`.
        let body = try encoder.encode(RefreshBody(refresh_token: refresh))
        // Perform the refresh WITHOUT the generic error mapping so we can read
        // the raw HTTP status. Classification is load-bearing (finding #1b):
        // only a definitive 401/403 from /auth/refresh may become .unauthorized
        // (→ sign-out, keychain wipe); URLError / 5xx / pin-cancel are TRANSIENT
        // and must propagate unchanged so a valid 30-day refresh token is KEPT
        // and retried on the next cold start. (The backend's rotation grace
        // window `refresh:rotated:{jti}` makes a retry after a lost response
        // safe.)
        let (data, http) = try await rawRefreshRequest(body: body)
        guard (200...299).contains(http.statusCode) else {
            if http.statusCode == 401 || http.statusCode == 403 {
                throw APIError.unauthorized
            }
            // 5xx / other non-2xx during a deploy window etc. — transient.
            throw APIError.httpError(http.statusCode)
        }
        let tokens = try decoder.decode(RefreshTokensResponse.self, from: data)

        // Compare-and-swap fence (finding #1): only persist if the session the
        // refresh belongs to still exists. Two things must hold —
        // (1) no logout invalidated the session while we were in flight
        //     (generation unchanged), and
        // (2) the refresh token still on file is the SAME one we presented
        //     (a concurrent clear() nils it; a concurrent successful refresh
        //     would have rotated it).
        // If either fails, drop the rotated pair silently — the caller's retry
        // will use whatever token is now current (or fail as .unauthorized).
        let generationNow = await sessionGeneration.current
        guard generationNow == generationAtStart,
              keychain.refreshToken == refresh else {
            return
        }
        // The rotated refresh token replaces the old one; if the server omits
        // it, the presented token remains current. A failed keychain write is a
        // HARD failure (findings #433/#60): the server has already rotated the
        // token, so proceeding on a half-written/unwritten pair would strand the
        // client on a token it can never replay. Surface it so the retry does
        // NOT run against a stale/blank Authorization header.
        guard keychain.save(accessToken: tokens.accessToken,
                            refreshToken: tokens.refreshToken ?? refresh,
                            email: keychain.userEmail) else {
            throw APIError.invalidResponse
        }
    }

    /// Fire `POST /auth/refresh` and return the raw body + response WITHOUT the
    /// generic status→APIError mapping, so `refreshTokens()` can classify the
    /// HTTP status precisely (definitive 401/403 vs transient 5xx). No auth
    /// header (the refresh token travels in the body) and no 401-refresh
    /// recursion. A thrown error here is a transport failure (URLError,
    /// pin-cancel) and propagates unchanged — a TRANSIENT signal.
    private func rawRefreshRequest(body: Data) async throws -> (Data, HTTPURLResponse) {
        guard let url = URL(string: "/auth/refresh", relativeTo: baseURL),
              url.scheme?.lowercased() == "https" else {
            throw APIError.invalidURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }
        return (data, http)
    }

    // MARK: - Device Identity

    /// SSOT device identity, mirroring Android's derivation
    /// (`"android_" + first32hex(SHA-256(SSAID + "|birdo-vpn-device-ssot-v1"))`):
    /// iOS uses `"ios_" + first32hex(SHA-256(IDFV + salt))`, PERSISTED IN THE
    /// KEYCHAIN so it survives reinstall (bare `identifierForVendor` does not —
    /// it resets when the last vendor app is removed). The stored value is
    /// load-bearing: it keys the server-side trusted-device 2FA skip and
    /// connection-slot reclamation (unstable IDs read as "device limit
    /// reached" after every reinstall). Cached after the first resolution;
    /// the mint is idempotent while IDFV is available.
    private func deviceContext() async -> DeviceIdentity {
        if let cached = cachedDeviceContext { return cached }
        let (idfv, osVersion) = await MainActor.run {
            (PlatformDevice.vendorId, PlatformDevice.systemVersion)
        }
        let deviceId: String
        if let stored = keychain.deviceId {
            deviceId = stored
        } else {
            let seed = (idfv ?? UUID().uuidString) + "|birdo-vpn-device-ssot-v1"
            let derived = "ios_" + String(Self.sha256Hex(seed).prefix(32))
            // Re-check before persisting: a concurrent first-call may have
            // minted (only divergent when IDFV was nil — random fallback).
            if let raced = keychain.deviceId {
                deviceId = raced
            } else {
                keychain.saveDeviceId(derived)
                deviceId = derived
            }
        }
        let context = DeviceIdentity(id: deviceId, name: Self.deviceModelName(), osVersion: osVersion)
        cachedDeviceContext = context
        return context
    }

    /// Hardware model identifier, e.g. "Apple iPhone15,3" (Android parity is
    /// "Samsung SM-S926B" style). Fallback `iOS device`.
    private static func deviceModelName() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let machine = withUnsafeBytes(of: &systemInfo.machine) { rawBuffer in
            String(decoding: rawBuffer.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
        return machine.isEmpty ? "iOS device" : "Apple \(machine)"
    }

    private static func sha256Hex(_ input: String) -> String {
        let data = Data(input.utf8)
        var hash = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes {
            _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &hash)
        }
        return hash.map { String(format: "%02x", $0) }.joined()
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
                // Rethrow instead of collapsing every failure to "session
                // expired" (finding #1b, spec §0: network errors / 5xx /
                // timeouts KEEP the user logged in). refreshTokens() already
                // maps the DEFINITIVE cases to .unauthorized (no stored token,
                // or /auth/refresh returned 401/403); everything else (URLError,
                // 5xx via .httpError, pin-cancel, CancellationError) stays its
                // original TRANSIENT type so logoutIfUnauthorized ignores it and
                // keeps the still-valid 30-day refresh token.
                throw error
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
        // 426 Upgrade Required = the backend's minimum-supported-version floor.
        // Surface a fixed, actionable string rather than the raw body (Review #219).
        if status == 426 {
            return .serverMessage("This app version is no longer supported. Update Birdo VPN to reconnect.",
                                  status: status)
        }
        if let parsed = try? JSONDecoder().decode(APIErrorBody.self, from: body),
           let message = parsed.message?.text, !message.isEmpty {
            // The status travels WITH the message. Without it, a 401 "Invalid
            // credentials" and a 500 "Internal server error" were indistinguishable
            // to callers, which is how a server outage came to look like a bad
            // password (see AuthViewModel.isCredentialRejection).
            return .serverMessage(message, status: status)
        }
        return .httpError(status)
    }
}

// MARK: - Refresh Coordinator

/// Serialises concurrent token refreshes. The first 401 starts the refresh;
/// every subsequent caller awaits the same in-flight task and then retries
/// with the freshly-stored token.
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

// MARK: - Session Generation

/// Monotonic counter that fences post-logout token writes. `refreshTokens()`
/// reads `current` before and after its network call; `invalidateSession()`
/// (sign-out) bumps it, so a refresh that finishes AFTER logout sees a changed
/// generation and drops its rotated pair instead of resurrecting the wiped
/// session (finding #1).
private actor SessionGeneration {
    private var value: UInt64 = 0

    var current: UInt64 { value }

    func bump() {
        value &+= 1
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
    ///
    /// `status` is the HTTP status the message arrived on, or nil when the
    /// message is CLIENT-minted (a 2xx `{success:false}` envelope, or a
    /// locally-authored fallback). It exists because "the server explained
    /// itself" and "the server rejected your credentials" are different facts,
    /// and callers were previously unable to tell them apart: a 401 with a
    /// message body and a 500 with a message body both arrived here as a bare
    /// string. Only classify on this field — never by matching the message text,
    /// which is backend copy and changes without notice.
    case serverMessage(String, status: Int?)
    /// Post-quantum protection is enabled but the ML-KEM keypair could not be
    /// produced. Surfaced rather than silently connecting without PQ.
    case quantumKeyUnavailable

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid URL"
        case .invalidResponse: return "Invalid server response"
        case .unauthorized: return "Session expired. Please log in again."
        case .httpError(let code): return "Server error (\(code))"
        case .serverMessage(let message, _): return message
        case .quantumKeyUnavailable:
            return "Could not prepare quantum-protected encryption. Not connecting, because "
                + "continuing would use weaker encryption. Try again, or turn off Quantum "
                + "Protection in Settings."
        }
    }
}

/// Complete token pair from a successful authentication.
struct TokenPairData: Sendable {
    let accessToken: String
    let refreshToken: String
}

/// Outcome of a credential/SSO login round: either a full token pair, or a
/// pending 2FA challenge. The challenge token belongs in ViewModel state
/// (never the keychain) and is consumed server-side only on a SUCCESSFUL
/// verify — a wrong code leaves it valid for retries.
enum LoginResult: Sendable {
    case success(TokenPairData)
    case twoFactorRequired(challengeToken: String)
}

/// Result of `POST /auth/register/anonymous`. The minted 24-digit ID is
/// returned ONCE and is the account's sole recovery credential — persist it
/// and surface it to the user before proceeding.
struct AnonymousRegistration: Sendable {
    let anonymousId: String?
    let tokens: TokenPairData
}

/// Identity model from `GET /auth/me`. Unknown wire keys are ignored and
/// every field is defaulted so a new backend field can never break decoding.
struct UserProfile: Sendable, Equatable {
    let id: String
    let email: String?
    let name: String?
    let emailVerified: Bool
    /// Drives the delete-account dialog (password confirmation). DEFAULTS
    /// TRUE when absent — fail-safe: ask for a password rather than let a
    /// password account delete without one.
    let hasPassword: Bool
    let isSSO: Bool

    /// Anonymous accounts carry the synthetic email
    /// `anon_<24digits>@anonymous.local`. Never render it — it "reads as a
    /// bug"; show `Anonymous account` + the account-number card instead.
    var isAnonymous: Bool {
        guard let email else { return false }
        return email.hasPrefix("anon_") && email.hasSuffix("@anonymous.local")
    }

    /// The 24-digit account number extracted from the synthetic email;
    /// nil for non-anonymous accounts.
    var anonymousAccountNumber: String? {
        guard isAnonymous, let email,
              let atIndex = email.firstIndex(of: "@") else { return nil }
        let start = email.index(email.startIndex, offsetBy: "anon_".count)
        guard start < atIndex else { return nil }
        let digits = String(email[start..<atIndex])
        guard !digits.isEmpty, digits.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return digits
    }
}

extension UserProfile: Decodable {
    private enum CodingKeys: String, CodingKey {
        case id, email, name, emailVerified, hasPassword, isSSO
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        email = try c.decodeIfPresent(String.self, forKey: .email)
        name = try c.decodeIfPresent(String.self, forKey: .name)
        emailVerified = try c.decodeIfPresent(Bool.self, forKey: .emailVerified) ?? false
        hasPassword = try c.decodeIfPresent(Bool.self, forKey: .hasPassword) ?? true
        isSSO = try c.decodeIfPresent(Bool.self, forKey: .isSSO) ?? false
    }
}

/// Canonical subscription/bandwidth truth from `GET /vpn/stats` (spec §3.4).
/// Every field is defaulted so a shape change can never blank the plan.
/// One browsable location from the public (unauthenticated) list. Carries no
/// per-user data — there is no `accessible` flag here, because access is a
/// property of an ACCOUNT and a signed-out viewer has none.
struct PublicLocation: Identifiable, Decodable, Equatable, Sendable {
    let city: String
    let countryCode: String
    let countryName: String
    /// "ONLINE" | "MAINTENANCE" (the backend lists nothing else publicly).
    let status: String
    /// How many nodes Birdo runs in this city.
    let count: Int

    /// City names are unique in the public list (the backend groups by city).
    var id: String { "\(countryCode)-\(city)" }

    var isOnline: Bool { status.uppercased() == "ONLINE" }

    /// Regional-indicator flag for `countryCode`, falling back to the globe.
    var flag: String { flagEmoji(countryCode) }

    /// Explicit memberwise init: declaring `init(from:)` below suppresses the
    /// synthesized one, and tests/previews need to build these.
    init(city: String, countryCode: String, countryName: String, status: String, count: Int) {
        self.city = city
        self.countryCode = countryCode
        self.countryName = countryName
        self.status = status
        self.count = count
    }

    /// Hand-rolled for the same reason as `ServerInfo`: a missing key degrades
    /// to a safe default instead of throwing and blanking the WHOLE list.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        city = try c.decodeIfPresent(String.self, forKey: .city) ?? ""
        countryCode = try c.decodeIfPresent(String.self, forKey: .countryCode) ?? ""
        countryName = try c.decodeIfPresent(String.self, forKey: .countryName) ?? ""
        status = try c.decodeIfPresent(String.self, forKey: .status) ?? "ONLINE"
        count = try c.decodeIfPresent(Int.self, forKey: .count) ?? 1
    }

    private enum CodingKeys: String, CodingKey {
        // `location` (lat/lng, for the web globe) is deliberately not decoded —
        // nothing on iOS plots the public list.
        case city, countryCode, countryName, status, count
    }
}

/// `{ locations: [...], cities: N, nodes: N }` — only `locations` is used.
private struct PublicLocationsEnvelope: Decodable {
    let locations: [PublicLocation]
}

struct VpnStats: Sendable, Equatable {
    /// "RECON" | "OPERATIVE" | "SOVEREIGN" — compare case-insensitively.
    let plan: String
    let status: String
    let activeConnections: Int
    let maxConnections: Int
    /// Wire `null` is coerced to 0 and **0 means unlimited** — the deliberate
    /// Android-parity convention (`hasCap = limitGb > 0`). Never render
    /// "0 GB / month".
    let bandwidthLimitGb: Double
    let bandwidthUsedGb: Double
    let bandwidthPeriodEnd: String?
    let bandwidthLastSyncAt: String?
    /// true = synced < 3 min ago; nil = never synced yet.
    let bandwidthIsFresh: Bool?
    let hasPremiumServers: Bool
    let subscriptionEndsAt: String?

    var hasBandwidthCap: Bool { bandwidthLimitGb > 0 }
    var isSovereign: Bool { plan.caseInsensitiveCompare("SOVEREIGN") == .orderedSame }
    var isOperativeOrHigher: Bool {
        isSovereign || plan.caseInsensitiveCompare("OPERATIVE") == .orderedSame
    }
}

/// Compatibility alias — some call sites name the /vpn/stats model this way.
typealias SubscriptionStatus = VpnStats

extension VpnStats: Decodable {
    private enum CodingKeys: String, CodingKey {
        case plan, status, activeConnections, maxConnections
        case bandwidthLimitGb, bandwidthUsedGb, bandwidthPeriodEnd
        case bandwidthLastSyncAt, bandwidthIsFresh, hasPremiumServers
        case subscriptionEndsAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        plan = try c.decodeIfPresent(String.self, forKey: .plan) ?? "RECON"
        status = try c.decodeIfPresent(String.self, forKey: .status) ?? "active"
        activeConnections = try c.decodeIfPresent(Int.self, forKey: .activeConnections) ?? 0
        maxConnections = try c.decodeIfPresent(Int.self, forKey: .maxConnections) ?? 1
        bandwidthLimitGb = try c.decodeIfPresent(Double.self, forKey: .bandwidthLimitGb) ?? 0
        bandwidthUsedGb = try c.decodeIfPresent(Double.self, forKey: .bandwidthUsedGb) ?? 0
        bandwidthPeriodEnd = try c.decodeIfPresent(String.self, forKey: .bandwidthPeriodEnd)
        bandwidthLastSyncAt = try c.decodeIfPresent(String.self, forKey: .bandwidthLastSyncAt)
        bandwidthIsFresh = try c.decodeIfPresent(Bool.self, forKey: .bandwidthIsFresh)
        hasPremiumServers = try c.decodeIfPresent(Bool.self, forKey: .hasPremiumServers) ?? false
        subscriptionEndsAt = try c.decodeIfPresent(String.self, forKey: .subscriptionEndsAt)
    }
}

/// Response of `POST /vpn/heartbeat/{keyId}`. `valid: false` means the
/// connection was revoked server-side (silent free-tier eviction) — the
/// client must disconnect and surface `message`. Fail-open on decode
/// surprises: an absent `valid` must never kill a live tunnel.
struct HeartbeatResult: Sendable {
    let valid: Bool
    let serverOnline: Bool?
    let message: String?
}

extension HeartbeatResult: Decodable {
    private enum CodingKeys: String, CodingKey {
        case valid, serverOnline, message
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        valid = try c.decodeIfPresent(Bool.self, forKey: .valid) ?? true
        serverOnline = try c.decodeIfPresent(Bool.self, forKey: .serverOnline)
        message = try c.decodeIfPresent(String.self, forKey: .message)
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

/// Resolved device identity attached to every auth call. The full six-field
/// payload (deviceId/deviceName/deviceType/platform/platformVersion/appVersion)
/// is flattened into each request body below.
private struct DeviceIdentity: Sendable {
    let id: String
    let name: String
    let osVersion: String
}

private struct LoginBody: Encodable {
    let email: String
    let password: String
    /// Device attribution — also lets a trusted device skip the 2FA challenge.
    let deviceId: String
    let deviceName: String
    let deviceType = "MOBILE"
    let platform = "IOS"
    let platformVersion: String
    let appVersion: String
}

/// Body for `POST /auth/login/anonymous` — the 24-digit account ID plus
/// optional password. A nil password is OMITTED from the JSON (synthesized
/// Encodable uses encodeIfPresent): the backend deliberately answers 401
/// "Invalid credentials" when a password is supplied to a password-less
/// account, so an empty string must never be sent.
private struct AnonymousLoginBody: Encodable {
    let anonymousId: String
    let password: String?
    let deviceId: String
    let deviceName: String
    let deviceType = "MOBILE"
    let platform = "IOS"
    let platformVersion: String
    let appVersion: String
}

/// Body for `POST /auth/register/anonymous` — device context only; the server
/// mints the 24-digit anonymous ID.
private struct DeviceInfoBody: Encodable {
    let deviceId: String
    let deviceName: String
    let deviceType = "MOBILE"
    let platform = "IOS"
    let platformVersion: String
    let appVersion: String
}

private struct TwoFactorBody: Encodable {
    let challengeToken: String
    let token: String
}

/// Body for `POST /auth/native/exchange`. Wire casing is EXACT:
/// `code_verifier` is snake_case (RFC 7636 vocabulary), device fields camelCase.
private struct SsoExchangeBody: Encodable {
    let code: String
    let codeVerifier: String
    let deviceId: String
    let deviceName: String
    let deviceType = "MOBILE"
    let platform = "IOS"
    let platformVersion: String
    let appVersion: String

    private enum CodingKeys: String, CodingKey {
        case code
        case codeVerifier = "code_verifier"
        case deviceId, deviceName, deviceType, platform, platformVersion, appVersion
    }
}

private struct DeleteAccountBody: Encodable {
    let password: String?
}

private struct ConnectBody: Encodable {
    let serverNodeId: String
    /// SSOT stable device identity — reclaims this device's own slot.
    let deviceId: String?
    /// On-device WireGuard (Curve25519) public key. Sending it makes the backend
    /// use it as the peer key and omit `privateKey` from the response, so the
    /// tunnel private key never leaves the device (parity with Android).
    let clientPublicKey: String?
    /// AUDIT-C1: opt the user into bilateral PQ when we have a client pk to
    /// send. Server interprets this together with `pqClientPublicKey`.
    let quantumProtection: Bool?
    /// AUDIT-C1: BirdoPQ v1 ML-KEM-1024 client public key (Base64).
    let pqClientPublicKey: String?
    /// BirdoPQ v1 HNDL opt-in: we decapsulate the ciphertext and derive the
    /// PSK on-device (BirdoPQManager), so the server WITHHOLDS the PSK from
    /// the response — it never crosses the wire under classical TLS. Only ever
    /// true alongside `pqClientPublicKey`; VPNManager fails closed if
    /// decapsulation then fails, so this can never silently downgrade.
    let pqClientCanDecapsulate: Bool?
}

private struct MultiHopBody: Encodable {
    let entryNodeId: String
    let exitNodeId: String
    let deviceId: String?
    /// On-device WireGuard (Curve25519) public key — see ConnectBody.
    let clientPublicKey: String?
    let quantumProtection: Bool?
    let pqClientPublicKey: String?
    /// HNDL opt-in — see ConnectBody. Declared on BOTH bodies (the duplicated
    /// wire-model twin) so the double-hop path keeps the PSK off the wire too.
    let pqClientCanDecapsulate: Bool?
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

/// Shared response shape of `/auth/login/desktop`, `/auth/login/anonymous`,
/// `/auth/register/anonymous`, `/auth/native/exchange` and `/auth/2fa/verify`:
/// tokens on success, or a 2FA challenge.
private struct AuthTokensResponse: Decodable {
    let ok: Bool?
    let tokens: TokenPairDTO?
    let requiresTwoFactor: Bool?
    let challengeToken: String?
    /// Returned by `/auth/register/anonymous` (minted) and echoed by
    /// `/auth/login/anonymous`. For a fresh registration it is surfaced only
    /// once and is the sole credential for the account — `/auth/me` never
    /// replays it directly (only via the synthetic email) — so dropping it
    /// would make the account unrecoverable after a logout or reinstall.
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
    /// The WireGuard tunnel private key. `var` because it is now GENERATED
    /// ON-DEVICE and injected by `getConnectConfig`/`getMultiHopConfig` after
    /// decoding — the backend omits it from the response once we send a
    /// `clientPublicKey`. Decoded optionally only to tolerate a transitional
    /// backend that still echoes one; the injected local key always wins.
    var privateKey: String
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

    /// `/vpn/multi-hop/connect` only: the route the backend says it ACTUALLY
    /// installed. `VpnViewModel.connectMultiHop` refuses to bring the tunnel
    /// up unless this names the requested pair (`MultiHopRouteCheck`) — see
    /// MultiHopRoute.swift. Absent on single-hop responses.
    let multiHop: MultiHopRouteInfo?

    private enum CodingKeys: String, CodingKey {
        case endpoint, privateKey, serverPublicKey, presharedKey
        case assignedIp, clientIpv6, dns, allowedIps, mtu, keyId
        case quantumEnabled, rosenpassPublicKey, rosenpassEndpoint
        case multiHop
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

        // Optional: the backend omits privateKey when we send a clientPublicKey.
        // The on-device key is injected by the API layer after decode; this "" is
        // a placeholder that is always overwritten before validate()/connect().
        privateKey = try c.decodeIfPresent(String.self, forKey: .privateKey) ?? ""
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

        multiHop = try c.decodeIfPresent(MultiHopRouteInfo.self, forKey: .multiHop)
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
