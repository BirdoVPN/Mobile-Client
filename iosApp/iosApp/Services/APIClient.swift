import Foundation
import CommonCrypto
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
        let body = try encoder.encode(LoginBody(email: email, password: password))
        let data = try await post(path: "/auth/login", body: body, authenticated: false)

        // Check if 2FA required
        if let parsed = try? decoder.decode(TwoFactorRequiredResponse.self, from: data),
           parsed.requiresTwoFactor {
            return .twoFactorRequired
        }

        let tokens = try decoder.decode(TokensResponse.self, from: data)
        return .success(TokenPairData(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken))
    }

    func verifyTwoFactor(email: String, password: String, code: String) async throws -> TokenPairData {
        let body = try encoder.encode(TwoFactorBody(email: email, password: password, code: code))
        let data = try await post(path: "/auth/2fa/verify", body: body, authenticated: false)
        let tokens = try decoder.decode(TokensResponse.self, from: data)
        return TokenPairData(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken)
    }

    func loginAnonymous() async throws -> TokenPairData {
        let data = try await post(path: "/auth/anonymous", body: nil, authenticated: false)
        let tokens = try decoder.decode(TokensResponse.self, from: data)
        return TokenPairData(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken)
    }

    func deleteAccount() async throws {
        _ = try await performRequest(method: "DELETE", path: "/auth/account", body: nil, authenticated: true)
    }

    // MARK: - Servers

    func fetchServers() async throws -> [ServerInfo] {
        let data = try await get(path: "/servers")
        return try decoder.decode([ServerInfo].self, from: data)
    }

    // MARK: - VPN Config

    func getConnectConfig(serverId: String) async throws -> VPNConnectionConfig {
        let body = try encoder.encode(ConnectBody(serverId: serverId))
        let data = try await post(path: "/vpn/connect", body: body, authenticated: true)
        return try decoder.decode(VPNConnectionConfig.self, from: data)
    }

    func getMultiHopConfig(entryId: String, exitId: String) async throws -> VPNConnectionConfig {
        let body = try encoder.encode(MultiHopBody(entryId: entryId, exitId: exitId))
        let data = try await post(path: "/vpn/multi-hop", body: body, authenticated: true)
        return try decoder.decode(VPNConnectionConfig.self, from: data)
    }

    // MARK: - Port Forwarding

    func createPortForward(port: Int, proto: String) async throws -> PortForwardEntry {
        let body = try encoder.encode(PortForwardBody(internalPort: port, proto: proto))
        let data = try await post(path: "/vpn/port-forward", body: body, authenticated: true)
        return try decoder.decode(PortForwardEntry.self, from: data)
    }

    func deletePortForward(id: String) async throws {
        _ = try await performRequest(
            method: "DELETE",
            path: "/vpn/port-forward/\(id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id)",
            body: nil,
            authenticated: true
        )
    }

    // MARK: - Speed Test

    func measureLatency() async throws -> (latencyMs: Int, jitterMs: Int) {
        var latencies: [Int] = []
        for _ in 0..<5 {
            let start = CFAbsoluteTimeGetCurrent()
            _ = try await get(path: "/ping")
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
        let data = try await get(path: "/speedtest/download")
        let elapsed = CFAbsoluteTimeGetCurrent() - start
        let bits = Double(data.count) * 8
        return bits / elapsed / 1_000_000 // Mbps
    }

    func measureUpload() async throws -> Double {
        let payload = Data(repeating: 0, count: 1_000_000) // 1 MB
        let start = CFAbsoluteTimeGetCurrent()
        _ = try await post(path: "/speedtest/upload", body: payload, authenticated: true)
        let elapsed = CFAbsoluteTimeGetCurrent() - start
        let bits = Double(payload.count) * 8
        return bits / elapsed / 1_000_000
    }

    // MARK: - Token Refresh

    private func refreshTokens() async throws {
        guard let refresh = keychain.refreshToken else {
            throw APIError.unauthorized
        }
        let body = try encoder.encode(RefreshBody(refreshToken: refresh))
        let data = try await post(path: "/auth/refresh", body: body, authenticated: false)
        let tokens = try decoder.decode(TokensResponse.self, from: data)
        keychain.save(accessToken: tokens.accessToken,
                      refreshToken: tokens.refreshToken,
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
                throw APIError.httpError(retryHttp.statusCode)
            }
            return retryData
        }

        guard (200...299).contains(http.statusCode) else {
            throw APIError.httpError(http.statusCode)
        }
        return data
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
        switch (type, size) {
        case (kSecAttrKeyTypeRSA as String, 2048):
            return Data([
                0x30, 0x82, 0x01, 0x22, 0x30, 0x0d, 0x06, 0x09,
                0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00, 0x03, 0x82, 0x01, 0x0f, 0x00,
            ])
        case (kSecAttrKeyTypeRSA as String, 4096):
            return Data([
                0x30, 0x82, 0x02, 0x22, 0x30, 0x0d, 0x06, 0x09,
                0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00, 0x03, 0x82, 0x02, 0x0f, 0x00,
            ])
        case (kSecAttrKeyTypeECSECPrimeRandom as String, 256):
            return Data([
                0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86,
                0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
                0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03,
                0x42, 0x00,
            ])
        case (kSecAttrKeyTypeECSECPrimeRandom as String, 384):
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

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid URL"
        case .invalidResponse: return "Invalid server response"
        case .unauthorized: return "Session expired. Please log in again."
        case .httpError(let code): return "Server error (\(code))"
        }
    }
}

private struct LoginBody: Encodable {
    let email: String
    let password: String
}

private struct TwoFactorBody: Encodable {
    let email: String
    let password: String
    let code: String
}

private struct ConnectBody: Encodable {
    let serverId: String
}

private struct MultiHopBody: Encodable {
    let entryId: String
    let exitId: String
}

private struct PortForwardBody: Encodable {
    let internalPort: Int
    let proto: String
}

private struct RefreshBody: Encodable {
    let refreshToken: String
}

private struct TokensResponse: Decodable {
    let accessToken: String
    let refreshToken: String
}

private struct TwoFactorRequiredResponse: Decodable {
    let requiresTwoFactor: Bool
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
