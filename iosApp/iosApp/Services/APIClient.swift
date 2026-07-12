import Foundation
import CommonCrypto
#if canImport(UIKit)
import UIKit
#endif

/// Client version read from the bundle, which CI stamps from
/// version.properties — the same single source that versions Android. A
/// hardcoded copy here is exactly how the last one rotted ("1.2.0" while
/// Android shipped 1.3.41).
private let kBirdoClientVersion =
    Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"

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

    func login(email: String, password: String) async throws -> LoginResponse {
        let body = try encoder.encode(LoginRequest(
            email: email,
            password: password,
            deviceId: nil,
            deviceName: await Self.deviceName(),
            deviceType: "phone",
            platform: "ios",
            platformVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            appVersion: kBirdoClientVersion
        ))
        let data = try await post(path: "/auth/login/desktop", body: body, authenticated: false)
        return try decoder.decode(LoginResponse.self, from: data)
    }

    func verifyTwoFactor(challengeToken: String, code: String) async throws -> TwoFactorVerifyResponse {
        let body = try encoder.encode(TwoFactorVerifyRequest(challengeToken: challengeToken, token: code))
        let data = try await post(path: "/auth/2fa/verify", body: body, authenticated: false)
        return try decoder.decode(TwoFactorVerifyResponse.self, from: data)
    }

    func loginAnonymous(anonymousId: String) async throws -> AnonymousLoginResponse {
        let body = try encoder.encode(AnonymousLoginRequest(
            anonymousId: anonymousId,
            password: nil,
            deviceId: nil,
            deviceName: await Self.deviceName(),
            deviceType: "phone",
            platform: "ios",
            platformVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            appVersion: kBirdoClientVersion
        ))
        let data = try await post(path: "/auth/login/anonymous", body: body, authenticated: false)
        return try decoder.decode(AnonymousLoginResponse.self, from: data)
    }

    func logout() async {
        // Best-effort server-side session invalidation; local state is wiped
        // by the caller regardless of the outcome.
        _ = try? await post(path: "/auth/logout", body: nil, authenticated: true)
    }

    /// GDPR account deletion. The backend requires the password IN THE BODY
    /// of the DELETE — collecting it in the UI and not sending it (the old
    /// behaviour) deleted nothing.
    func deleteAccount(password: String) async throws {
        let body = try encoder.encode(DeleteAccountRequest(password: password))
        _ = try await performRequest(method: "DELETE", path: "/v1/gdpr/delete", body: body, authenticated: true)
    }

    // MARK: - Subscription

    func fetchSubscription() async throws -> SubscriptionStatus {
        let data = try await get(path: "/vpn/stats")
        return try decoder.decode(SubscriptionStatus.self, from: data)
    }

    // MARK: - Servers

    func fetchServers() async throws -> [VpnServer] {
        let data = try await get(path: "/vpn/servers")
        return try decoder.decode([VpnServer].self, from: data)
    }

    // MARK: - Connect / lifecycle

    func connect(_ request: ConnectRequest) async throws -> ConnectResponse {
        let body = try encoder.encode(request)
        let data = try await post(path: "/vpn/connect", body: body, authenticated: true)
        return try decoder.decode(ConnectResponse.self, from: data)
    }

    func connectMultiHop(_ request: MultiHopConnectRequest) async throws -> MultiHopConnectResponse {
        let body = try encoder.encode(request)
        let data = try await post(path: "/vpn/multi-hop/connect", body: body, authenticated: true)
        return try decoder.decode(MultiHopConnectResponse.self, from: data)
    }

    /// Server-side teardown of a connection slot. Best-effort by design: the
    /// local tunnel MUST come down even if this call fails, so callers fire
    /// it without gating the disconnect on the result.
    func disconnectConnection(keyId: String) async {
        let encoded = keyId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? keyId
        _ = try? await performRequest(method: "DELETE", path: "/vpn/connections/\(encoded)", body: nil, authenticated: true)
    }

    func heartbeat(keyId: String) async throws -> HeartbeatResponse {
        let encoded = keyId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? keyId
        let data = try await post(path: "/vpn/heartbeat/\(encoded)", body: nil, authenticated: true)
        return try decoder.decode(HeartbeatResponse.self, from: data)
    }

    // MARK: - Port Forwarding

    func listPortForwards() async throws -> [PortForward] {
        let data = try await get(path: "/vpn/port-forwards")
        return try decoder.decode([PortForward].self, from: data)
    }

    func createPortForward(internalPort: Int, proto: String) async throws -> PortForward {
        let body = try encoder.encode(CreatePortForwardRequest(internalPort: internalPort, protocol: proto))
        let data = try await post(path: "/vpn/port-forwards", body: body, authenticated: true)
        let response = try decoder.decode(CreatePortForwardResponse.self, from: data)
        guard response.success, let forward = response.portForward else {
            throw APIError.server(response.message ?? "Port forward could not be created.")
        }
        return forward
    }

    func deletePortForward(id: String) async throws {
        _ = try await performRequest(
            method: "DELETE",
            path: "/vpn/port-forwards/\(id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id)",
            body: nil,
            authenticated: true
        )
    }

    // MARK: - Helpers

    private static func deviceName() async -> String {
        #if canImport(UIKit)
        return await MainActor.run { UIDevice.current.name }
        #else
        return "iPhone"
        #endif
    }

    // MARK: - Token Refresh

    private func refreshTokens() async throws {
        guard let refresh = keychain.refreshToken else {
            throw APIError.unauthorized
        }
        let body = try encoder.encode(RefreshRequest(refreshToken: refresh))
        let data = try await post(path: "/auth/refresh", body: body, authenticated: false)
        let tokens = try decoder.decode(RefreshResponse.self, from: data)
        // The backend only rotates the refresh token sometimes; keep the old
        // one when no replacement is issued or the session dies on refresh.
        keychain.save(accessToken: tokens.accessToken,
                      refreshToken: tokens.refreshToken ?? refresh,
                      email: keychain.userEmail)
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
        authenticated: Bool
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
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
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
        if http.statusCode == 401 && authenticated {
            do {
                try await refreshActor.refresh { [weak self] in
                    try await self?.refreshTokens()
                }
            } catch {
                throw APIError.unauthorized
            }
            var retry = URLRequest(url: url)
            retry.httpMethod = method
            retry.setValue("application/json", forHTTPHeaderField: "Content-Type")
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
                throw Self.errorFor(status: retryHttp.statusCode, data: retryData)
            }
            return retryData
        }

        guard (200...299).contains(http.statusCode) else {
            throw Self.errorFor(status: http.statusCode, data: data)
        }
        return data
    }

    /// Prefer the backend's structured error body over a bare status code —
    /// "Device limit reached - remove a device to connect" beats "Server
    /// error (403)".
    private static func errorFor(status: Int, data: Data) -> APIError {
        if let body = try? JSONDecoder().decode(ApiErrorBody.self, from: data),
           let message = body.message, !message.isEmpty {
            return .server(message)
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
        let count = SecTrustGetCertificateCount(trust)
        for i in 0..<count {
            guard let cert = SecTrustGetCertificateAtIndex(trust, i),
                  let pubKey = SecCertificateCopyKey(cert),
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
        // Hoist the CFString->String casts OUT of the patterns. Inside a
        // `case`, `x as String` is a type-CASTING pattern, not a cast
        // expression, so `case (kSecAttrKeyTypeRSA as String, 2048)` does not
        // compare against `type` at all — it fails to type-check against a
        // String tuple element.
        let rsa = kSecAttrKeyTypeRSA as String
        let ecPrime = kSecAttrKeyTypeECSECPrimeRandom as String
        switch (type, size) {
        case (rsa, 2048):
            return Data([
                0x30, 0x82, 0x01, 0x22, 0x30, 0x0d, 0x06, 0x09,
                0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00, 0x03, 0x82, 0x01, 0x0f, 0x00,
            ])
        case (rsa, 4096):
            return Data([
                0x30, 0x82, 0x02, 0x22, 0x30, 0x0d, 0x06, 0x09,
                0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00, 0x03, 0x82, 0x02, 0x0f, 0x00,
            ])
        case (ecPrime, 256):
            return Data([
                0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86,
                0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
                0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03,
                0x42, 0x00,
            ])
        case (ecPrime, 384):
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
    /// The backend's own human-readable error message (ApiErrorBody.message).
    case server(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid URL"
        case .invalidResponse: return "Invalid server response"
        case .unauthorized: return "Session expired. Please log in again."
        case .httpError(let code): return "Server error (\(code))"
        case .server(let message): return message
        }
    }
}

/// VPN connection configuration returned by server.
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
    let persistentKeepalive: Int?

    /// AUDIT-C1 (BirdoPQ v1, optional): set when the server received a
    /// `pqClientPublicKey` and produced a per-connect ML-KEM ciphertext for
    /// the client to decapsulate. The Swift `BirdoPQManager` derives a
    /// 32-byte WireGuard PSK from these fields client-side.
    let quantumEnabled: Bool?
    let rosenpassPublicKey: String?
    let rosenpassEndpoint: String?

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

extension VPNConnectionConfig {
    /// Build a tunnel config from a ConnectResponse and the CLIENT-generated
    /// private key (contract rule 1: the server must never know it; a
    /// server-sent privateKey is only honoured when we did not send a
    /// clientPublicKey).
    ///
    /// IPv6 leak rule (contract rule 6): even when the node gives us no IPv6
    /// address, ::/0 must be claimed in AllowedIPs so v6 traffic blackholes
    /// inside the tunnel instead of leaking around it.
    init(response r: ConnectResponse, clientPrivateKey: String?) throws {
        guard r.success else {
            throw APIError.server(r.message ?? "Connection was refused by the server.")
        }
        guard let endpoint = r.endpoint, let serverKey = r.serverPublicKey else {
            throw VPNConfigValidationError.invalidServerAddress
        }
        // endpoint is "host:port" ("[v6]:port" for IPv6 hosts) — split on the
        // LAST colon so v6 literals survive.
        guard let idx = endpoint.lastIndex(of: ":"),
              let port = Int(endpoint[endpoint.index(after: idx)...]) else {
            throw VPNConfigValidationError.invalidPort
        }
        var host = String(endpoint[..<idx])
        if host.hasPrefix("["), host.hasSuffix("]") {
            host = String(host.dropFirst().dropLast())
        }

        guard let privateKey = clientPrivateKey ?? r.privateKey else {
            throw VPNConfigValidationError.invalidPrivateKey
        }

        var addresses: [String] = []
        if let v4 = r.assignedIp, !v4.isEmpty { addresses.append(v4) }
        if let v6 = r.clientIpv6, !v6.isEmpty { addresses.append(v6) }

        var allowed = r.allowedIps ?? ["0.0.0.0/0", "::/0"]
        if !allowed.contains("::/0") { allowed.append("::/0") }

        self.init(
            serverAddress: host,
            serverPort: port,
            privateKey: privateKey,
            publicKey: serverKey,
            presharedKey: r.presharedKey,
            addresses: addresses,
            dns: r.dns ?? [],
            allowedIPs: allowed,
            mtu: r.mtu,
            persistentKeepalive: r.persistentKeepalive,
            quantumEnabled: r.quantumEnabled,
            rosenpassPublicKey: r.rosenpassPublicKey,
            rosenpassEndpoint: r.rosenpassEndpoint
        )
    }
}
