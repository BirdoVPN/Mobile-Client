import Foundation

// Canonical wire-protocol models. The contract is docs/IOS-PARITY-CONTRACT.md;
// the reference implementation is Android's shared Models.kt. These shapes are
// pinned by BirdoVPNTests/APIContractTests — if a test fails after editing
// here, the edit is wrong unless the backend contract itself changed.
//
// Everything is camelCase EXCEPT the keys inside token objects
// (access_token / refresh_token) — the backend really is mixed like that.

// MARK: - Auth

struct LoginRequest: Encodable {
    let email: String
    let password: String
    var deviceId: String? = nil
    var deviceName: String? = nil
    var deviceType: String? = nil
    var platform: String? = nil
    var platformVersion: String? = nil
    var appVersion: String? = nil
}

struct TokenPair: Codable {
    let accessToken: String
    let refreshToken: String

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
    }
}

struct LoginResponse: Decodable {
    var ok: Bool? = nil
    var tokens: TokenPair? = nil
    var requiresTwoFactor: Bool? = nil
    var challengeToken: String? = nil
}

struct TwoFactorVerifyRequest: Encodable {
    let challengeToken: String
    let token: String
}

struct TwoFactorVerifyResponse: Decodable {
    var ok: Bool = false
    var tokens: TokenPair? = nil
    var backupCodeUsed: Bool = false

    enum CodingKeys: String, CodingKey { case ok, tokens, backupCodeUsed }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ok = try c.decodeIfPresent(Bool.self, forKey: .ok) ?? false
        tokens = try c.decodeIfPresent(TokenPair.self, forKey: .tokens)
        backupCodeUsed = try c.decodeIfPresent(Bool.self, forKey: .backupCodeUsed) ?? false
    }
}

struct RefreshRequest: Encodable {
    let refreshToken: String
    enum CodingKeys: String, CodingKey { case refreshToken = "refresh_token" }
}

struct RefreshResponse: Decodable {
    let accessToken: String
    var refreshToken: String? = nil
    var expiresIn: Int64 = 3600

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresIn = "expires_in"
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        accessToken = try c.decode(String.self, forKey: .accessToken)
        refreshToken = try c.decodeIfPresent(String.self, forKey: .refreshToken)
        expiresIn = try c.decodeIfPresent(Int64.self, forKey: .expiresIn) ?? 3600
    }
}

struct AnonymousLoginRequest: Encodable {
    let anonymousId: String
    var password: String? = nil
    var deviceId: String? = nil
    var deviceName: String? = nil
    var deviceType: String? = nil
    var platform: String? = nil
    var platformVersion: String? = nil
    var appVersion: String? = nil
}

struct AnonymousLoginResponse: Decodable {
    var ok: Bool? = nil
    var anonymousId: String? = nil
    var tokens: TokenPair? = nil
    var requiresTwoFactor: Bool? = nil
    var challengeToken: String? = nil
}

struct UserProfile: Decodable {
    let id: String
    let email: String
    var name: String? = nil
    var emailVerified: Bool? = nil
    var createdAt: String? = nil
}

// MARK: - Account / GDPR

struct DeleteAccountRequest: Encodable {
    let password: String
}

struct DeleteAccountResponse: Decodable {
    var success: Bool? = nil
    var message: String? = nil
    var deletedItems: Int? = nil
    var anonymizedItems: Int? = nil
}

struct RedeemVoucherRequest: Encodable {
    let code: String
}

struct RedeemVoucherResponse: Decodable {
    var ok: Bool? = nil
    var plan: String? = nil
    var durationDays: Int? = nil
    var newPeriodEnd: String? = nil
    var extended: Bool? = nil
    var error: String? = nil
}

// MARK: - Subscription

struct SubscriptionStatus: Decodable {
    var plan: String = "RECON"
    var status: String = "INACTIVE"
    var activeConnections: Int = 0
    var maxConnections: Int = 1
    var bandwidthLimitGb: Int64 = 0
    var hasPremiumServers: Bool = false
    var subscriptionEndsAt: String? = nil

    enum CodingKeys: String, CodingKey {
        case plan, status, activeConnections, maxConnections
        case bandwidthLimitGb, hasPremiumServers, subscriptionEndsAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        plan = try c.decodeIfPresent(String.self, forKey: .plan) ?? "RECON"
        status = try c.decodeIfPresent(String.self, forKey: .status) ?? "INACTIVE"
        activeConnections = try c.decodeIfPresent(Int.self, forKey: .activeConnections) ?? 0
        maxConnections = try c.decodeIfPresent(Int.self, forKey: .maxConnections) ?? 1
        bandwidthLimitGb = try c.decodeIfPresent(Int64.self, forKey: .bandwidthLimitGb) ?? 0
        hasPremiumServers = try c.decodeIfPresent(Bool.self, forKey: .hasPremiumServers) ?? false
        subscriptionEndsAt = try c.decodeIfPresent(String.self, forKey: .subscriptionEndsAt)
    }
}

// MARK: - Servers

struct VpnServer: Decodable, Identifiable, Hashable {
    let id: String
    let name: String
    var country: String = ""
    var countryCode: String = ""
    var city: String = ""
    var hostname: String = ""
    var ipAddress: String = ""
    var port: Int = 51820
    var load: Int = 0
    var isPremium: Bool = false
    var isStreaming: Bool = false
    var isP2p: Bool = false
    var isOnline: Bool = true

    enum CodingKeys: String, CodingKey {
        case id, name, country, countryCode, city, hostname, ipAddress
        case port, load, isPremium, isStreaming, isP2p, isOnline
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        country = try c.decodeIfPresent(String.self, forKey: .country) ?? ""
        countryCode = try c.decodeIfPresent(String.self, forKey: .countryCode) ?? ""
        city = try c.decodeIfPresent(String.self, forKey: .city) ?? ""
        hostname = try c.decodeIfPresent(String.self, forKey: .hostname) ?? ""
        ipAddress = try c.decodeIfPresent(String.self, forKey: .ipAddress) ?? ""
        port = try c.decodeIfPresent(Int.self, forKey: .port) ?? 51820
        load = try c.decodeIfPresent(Int.self, forKey: .load) ?? 0
        isPremium = try c.decodeIfPresent(Bool.self, forKey: .isPremium) ?? false
        isStreaming = try c.decodeIfPresent(Bool.self, forKey: .isStreaming) ?? false
        isP2p = try c.decodeIfPresent(Bool.self, forKey: .isP2p) ?? false
        isOnline = try c.decodeIfPresent(Bool.self, forKey: .isOnline) ?? true
    }

    init(id: String, name: String, country: String = "", countryCode: String = "",
         city: String = "", hostname: String = "", ipAddress: String = "",
         port: Int = 51820, load: Int = 0, isPremium: Bool = false,
         isStreaming: Bool = false, isP2p: Bool = false, isOnline: Bool = true) {
        self.id = id; self.name = name; self.country = country
        self.countryCode = countryCode; self.city = city; self.hostname = hostname
        self.ipAddress = ipAddress; self.port = port; self.load = load
        self.isPremium = isPremium; self.isStreaming = isStreaming
        self.isP2p = isP2p; self.isOnline = isOnline
    }

    /// Flag emoji derived from the ISO country code via Unicode regional
    /// indicators — same transform as Android's FlagUtils.
    var flag: String {
        let cc = countryCode.uppercased()
        guard cc.count == 2, cc.allSatisfy({ $0.isLetter && $0.isASCII }) else { return "🏳️" }
        var s = ""
        for u in cc.unicodeScalars {
            guard let scalar = Unicode.Scalar(0x1F1E6 + u.value - Unicode.Scalar("A").value) else {
                return "🏳️"
            }
            s.unicodeScalars.append(scalar)
        }
        return s
    }
}

/// The old iOS code (and several views) used the name `ServerInfo` for this
/// model. The canonical name matches Android; the alias keeps views readable.
typealias ServerInfo = VpnServer

// MARK: - Connect

struct AttestationNonceResponse: Decodable {
    let nonce: String
}

struct ConnectRequest: Encodable {
    var serverNodeId: String? = nil
    var deviceName: String? = nil
    var preferredRegion: String? = nil
    var clientPublicKey: String? = nil
    var stealthMode: Bool = false
    var quantumProtection: Bool = false
    var pqClientPublicKey: String? = nil
    /// Play Integrity on Android. iOS has no equivalent wired yet (App Attest
    /// is future work) — always send nil; the backend's attestation policy is
    /// default-off and accepts absent tokens. Do NOT invent a value here.
    var integrityToken: String? = nil
}

struct ServerNodeInfo: Decodable {
    let id: String
    let name: String
    var region: String = ""
    var country: String = ""
    var hostname: String = ""

    enum CodingKeys: String, CodingKey { case id, name, region, country, hostname }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        region = try c.decodeIfPresent(String.self, forKey: .region) ?? ""
        country = try c.decodeIfPresent(String.self, forKey: .country) ?? ""
        hostname = try c.decodeIfPresent(String.self, forKey: .hostname) ?? ""
    }
}

struct ConnectResponse: Decodable {
    var success: Bool = false
    var message: String? = nil
    var config: String? = nil
    var keyId: String? = nil
    /// Only present when the server generated the keypair (we didn't send
    /// clientPublicKey). The client-keygen flow leaves this nil by design.
    var privateKey: String? = nil
    var publicKey: String? = nil
    var presharedKey: String? = nil
    var assignedIp: String? = nil
    var clientIpv6: String? = nil
    var serverPublicKey: String? = nil
    var endpoint: String? = nil
    var dns: [String]? = nil
    var allowedIps: [String]? = nil
    var mtu: Int? = nil
    var persistentKeepalive: Int? = nil
    var serverNode: ServerNodeInfo? = nil
    var stealthEnabled: Bool? = nil
    var quantumEnabled: Bool? = nil
    var rosenpassPublicKey: String? = nil
    var rosenpassEndpoint: String? = nil

    enum CodingKeys: String, CodingKey {
        case success, message, config, keyId, privateKey, publicKey, presharedKey
        case assignedIp, clientIpv6, serverPublicKey, endpoint, dns, allowedIps
        case mtu, persistentKeepalive, serverNode, stealthEnabled
        case quantumEnabled, rosenpassPublicKey, rosenpassEndpoint
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = try c.decodeIfPresent(Bool.self, forKey: .success) ?? false
        message = try c.decodeIfPresent(String.self, forKey: .message)
        config = try c.decodeIfPresent(String.self, forKey: .config)
        keyId = try c.decodeIfPresent(String.self, forKey: .keyId)
        privateKey = try c.decodeIfPresent(String.self, forKey: .privateKey)
        publicKey = try c.decodeIfPresent(String.self, forKey: .publicKey)
        presharedKey = try c.decodeIfPresent(String.self, forKey: .presharedKey)
        assignedIp = try c.decodeIfPresent(String.self, forKey: .assignedIp)
        clientIpv6 = try c.decodeIfPresent(String.self, forKey: .clientIpv6)
        serverPublicKey = try c.decodeIfPresent(String.self, forKey: .serverPublicKey)
        endpoint = try c.decodeIfPresent(String.self, forKey: .endpoint)
        dns = try c.decodeIfPresent([String].self, forKey: .dns)
        allowedIps = try c.decodeIfPresent([String].self, forKey: .allowedIps)
        mtu = try c.decodeIfPresent(Int.self, forKey: .mtu)
        persistentKeepalive = try c.decodeIfPresent(Int.self, forKey: .persistentKeepalive)
        serverNode = try c.decodeIfPresent(ServerNodeInfo.self, forKey: .serverNode)
        stealthEnabled = try c.decodeIfPresent(Bool.self, forKey: .stealthEnabled)
        quantumEnabled = try c.decodeIfPresent(Bool.self, forKey: .quantumEnabled)
        rosenpassPublicKey = try c.decodeIfPresent(String.self, forKey: .rosenpassPublicKey)
        rosenpassEndpoint = try c.decodeIfPresent(String.self, forKey: .rosenpassEndpoint)
    }
}

// MARK: - Multi-hop

struct MultiHopRoute: Decodable {
    let entryNodeId: String
    let exitNodeId: String
    var entryCountry: String = ""
    var exitCountry: String = ""

    enum CodingKeys: String, CodingKey { case entryNodeId, exitNodeId, entryCountry, exitCountry }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        entryNodeId = try c.decode(String.self, forKey: .entryNodeId)
        exitNodeId = try c.decode(String.self, forKey: .exitNodeId)
        entryCountry = try c.decodeIfPresent(String.self, forKey: .entryCountry) ?? ""
        exitCountry = try c.decodeIfPresent(String.self, forKey: .exitCountry) ?? ""
    }
}

struct MultiHopConnectRequest: Encodable {
    let entryNodeId: String
    let exitNodeId: String
    var deviceName: String? = nil
    var clientPublicKey: String? = nil
    var stealthMode: Bool = false
    var quantumProtection: Bool = false
    var pqClientPublicKey: String? = nil
    /// Same rule as ConnectRequest — nil on iOS. Omitting the FIELD (rather
    /// than sending nil) is fine; omitting attestation on just one of the two
    /// connect paths was Android's bypass bug, so keep the field in the model.
    var integrityToken: String? = nil
}

struct MultiHopNodeInfo: Decodable {
    let id: String
    let name: String
    var country: String = ""
    var region: String = ""

    enum CodingKeys: String, CodingKey { case id, name, country, region }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        country = try c.decodeIfPresent(String.self, forKey: .country) ?? ""
        region = try c.decodeIfPresent(String.self, forKey: .region) ?? ""
    }
}

struct MultiHopInfo: Decodable {
    let entryNode: MultiHopNodeInfo
    let exitNode: MultiHopNodeInfo
    var route: String = ""

    enum CodingKeys: String, CodingKey { case entryNode, exitNode, route }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        entryNode = try c.decode(MultiHopNodeInfo.self, forKey: .entryNode)
        exitNode = try c.decode(MultiHopNodeInfo.self, forKey: .exitNode)
        route = try c.decodeIfPresent(String.self, forKey: .route) ?? ""
    }
}

struct MultiHopConnectResponse: Decodable {
    var success: Bool = false
    var message: String? = nil
    var config: String? = nil
    var keyId: String? = nil
    var privateKey: String? = nil
    var publicKey: String? = nil
    var presharedKey: String? = nil
    var assignedIp: String? = nil
    var clientIpv6: String? = nil
    var serverPublicKey: String? = nil
    var endpoint: String? = nil
    var dns: [String]? = nil
    var allowedIps: [String]? = nil
    var mtu: Int? = nil
    var persistentKeepalive: Int? = nil
    var multiHop: MultiHopInfo? = nil
    var stealthEnabled: Bool? = nil
    var quantumEnabled: Bool? = nil
    var rosenpassPublicKey: String? = nil
    var rosenpassEndpoint: String? = nil

    enum CodingKeys: String, CodingKey {
        case success, message, config, keyId, privateKey, publicKey, presharedKey
        case assignedIp, clientIpv6, serverPublicKey, endpoint, dns, allowedIps
        case mtu, persistentKeepalive, multiHop, stealthEnabled
        case quantumEnabled, rosenpassPublicKey, rosenpassEndpoint
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = try c.decodeIfPresent(Bool.self, forKey: .success) ?? false
        message = try c.decodeIfPresent(String.self, forKey: .message)
        config = try c.decodeIfPresent(String.self, forKey: .config)
        keyId = try c.decodeIfPresent(String.self, forKey: .keyId)
        privateKey = try c.decodeIfPresent(String.self, forKey: .privateKey)
        publicKey = try c.decodeIfPresent(String.self, forKey: .publicKey)
        presharedKey = try c.decodeIfPresent(String.self, forKey: .presharedKey)
        assignedIp = try c.decodeIfPresent(String.self, forKey: .assignedIp)
        clientIpv6 = try c.decodeIfPresent(String.self, forKey: .clientIpv6)
        serverPublicKey = try c.decodeIfPresent(String.self, forKey: .serverPublicKey)
        endpoint = try c.decodeIfPresent(String.self, forKey: .endpoint)
        dns = try c.decodeIfPresent([String].self, forKey: .dns)
        allowedIps = try c.decodeIfPresent([String].self, forKey: .allowedIps)
        mtu = try c.decodeIfPresent(Int.self, forKey: .mtu)
        persistentKeepalive = try c.decodeIfPresent(Int.self, forKey: .persistentKeepalive)
        multiHop = try c.decodeIfPresent(MultiHopInfo.self, forKey: .multiHop)
        stealthEnabled = try c.decodeIfPresent(Bool.self, forKey: .stealthEnabled)
        quantumEnabled = try c.decodeIfPresent(Bool.self, forKey: .quantumEnabled)
        rosenpassPublicKey = try c.decodeIfPresent(String.self, forKey: .rosenpassPublicKey)
        rosenpassEndpoint = try c.decodeIfPresent(String.self, forKey: .rosenpassEndpoint)
    }

    /// Multi-hop and single-hop responses drive the same tunnel-config path.
    var asConnectResponse: ConnectResponse {
        var r = ConnectResponse()
        r.success = success; r.message = message; r.config = config
        r.keyId = keyId; r.privateKey = privateKey; r.publicKey = publicKey
        r.presharedKey = presharedKey; r.assignedIp = assignedIp
        r.clientIpv6 = clientIpv6; r.serverPublicKey = serverPublicKey
        r.endpoint = endpoint; r.dns = dns; r.allowedIps = allowedIps
        r.mtu = mtu; r.persistentKeepalive = persistentKeepalive
        r.stealthEnabled = stealthEnabled; r.quantumEnabled = quantumEnabled
        r.rosenpassPublicKey = rosenpassPublicKey
        r.rosenpassEndpoint = rosenpassEndpoint
        return r
    }
}

extension ConnectResponse {
    /// Memberwise-style init for asConnectResponse (Decodable removes the
    /// synthesized one).
    init() {}
}

// MARK: - Connection lifecycle

struct HeartbeatResponse: Decodable {
    var valid: Bool = true
    var serverOnline: Bool = true
    var message: String? = nil

    enum CodingKeys: String, CodingKey { case valid, serverOnline, message }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        valid = try c.decodeIfPresent(Bool.self, forKey: .valid) ?? true
        serverOnline = try c.decodeIfPresent(Bool.self, forKey: .serverOnline) ?? true
        message = try c.decodeIfPresent(String.self, forKey: .message)
    }
}

struct KeyRotationRequest: Encodable {
    let clientPublicKey: String
}

struct KeyRotationResponse: Decodable {
    var success: Bool = false
    var newKeyId: String = ""
    var serverPublicKey: String = ""
    var presharedKey: String? = nil
    var expiresAt: String = ""

    enum CodingKeys: String, CodingKey { case success, newKeyId, serverPublicKey, presharedKey, expiresAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = try c.decodeIfPresent(Bool.self, forKey: .success) ?? false
        newKeyId = try c.decodeIfPresent(String.self, forKey: .newKeyId) ?? ""
        serverPublicKey = try c.decodeIfPresent(String.self, forKey: .serverPublicKey) ?? ""
        presharedKey = try c.decodeIfPresent(String.self, forKey: .presharedKey)
        expiresAt = try c.decodeIfPresent(String.self, forKey: .expiresAt) ?? ""
    }
}

struct QualityReport: Encodable {
    let keyId: String
    let latencyMs: Double
    let jitterMs: Double
    let packetLossPercent: Double
    let bytesIn: Int64
    let bytesOut: Int64
    let handshakeAgeSeconds: Int64
    let connectionState: String
    let platform: String
}

// MARK: - Port forwarding

struct PortForward: Decodable, Identifiable {
    let id: String
    let externalPort: Int
    let internalPort: Int
    var `protocol`: String = "tcp"
    var enabled: Bool = true

    enum CodingKeys: String, CodingKey { case id, externalPort, internalPort, `protocol`, enabled }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        externalPort = try c.decode(Int.self, forKey: .externalPort)
        internalPort = try c.decode(Int.self, forKey: .internalPort)
        `protocol` = try c.decodeIfPresent(String.self, forKey: .`protocol`) ?? "tcp"
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? true
    }

    /// The views were written against the old local struct's `proto` name.
    var proto: String { `protocol` }
}

/// Old iOS name for this model, kept so views read naturally.
typealias PortForwardEntry = PortForward

struct CreatePortForwardRequest: Encodable {
    let internalPort: Int
    var `protocol`: String = "tcp"
}

struct CreatePortForwardResponse: Decodable {
    var success: Bool = false
    var portForward: PortForward? = nil
    var message: String? = nil

    enum CodingKeys: String, CodingKey { case success, portForward, message }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = try c.decodeIfPresent(Bool.self, forKey: .success) ?? false
        portForward = try c.decodeIfPresent(PortForward.self, forKey: .portForward)
        message = try c.decodeIfPresent(String.self, forKey: .message)
    }
}

// MARK: - Errors

/// Backend protocol error codes. Mirrors Android's ProtocolErrorCode enum.
/// Unknown wire values decode to nil rather than throwing — the backend may
/// add codes before the app updates.
enum ProtocolErrorCode: String, Decodable {
    case authRequired = "AUTH_REQUIRED"
    case authExpired = "AUTH_EXPIRED"
    case subscriptionRequired = "SUBSCRIPTION_REQUIRED"
    case subscriptionExpired = "SUBSCRIPTION_EXPIRED"
    case deviceLimitReached = "DEVICE_LIMIT_REACHED"
    case rateLimited = "RATE_LIMITED"
    case serverOffline = "SERVER_OFFLINE"
    case serverFull = "SERVER_FULL"
    case noServersAvailable = "NO_SERVERS_AVAILABLE"
    case tunnelCreationFailed = "TUNNEL_CREATION_FAILED"
    case tunnelStartFailed = "TUNNEL_START_FAILED"
    case dnsConfigurationFailed = "DNS_CONFIGURATION_FAILED"
    case routeConfigurationFailed = "ROUTE_CONFIGURATION_FAILED"
    case killSwitchFailed = "KILL_SWITCH_FAILED"
    case ipv6BlockFailed = "IPV6_BLOCK_FAILED"
    case stealthTunnelFailed = "STEALTH_TUNNEL_FAILED"
    case quantumHandshakeFailed = "QUANTUM_HANDSHAKE_FAILED"
}

struct ApiErrorBody: Decodable {
    let errorCode: ProtocolErrorCode?
    let message: String?

    enum CodingKeys: String, CodingKey { case errorCode, message }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // Tolerate unknown error-code strings: map to nil, never throw.
        let raw = try c.decodeIfPresent(String.self, forKey: .errorCode)
        errorCode = raw.flatMap(ProtocolErrorCode.init(rawValue:))
        message = try c.decodeIfPresent(String.self, forKey: .message)
    }
}
