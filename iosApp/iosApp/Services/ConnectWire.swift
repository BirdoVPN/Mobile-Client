import Foundation

// K5 connect contract — the two request bodies this client puts on the wire
// for POST /vpn/connect and POST /vpn/multi-hop/connect, plus the encoder that
// serialises them.
//
// Foundation-only by design: BirdoVPNTests is an un-hosted bundle that cannot
// compile APIClient.swift (keychain, KMP framework, URLSession plumbing), so the
// bodies live here and are compiled into the test target directly. The test
// encodes them with `ConnectWire.makeEncoder()` — the SAME factory APIClient
// uses — and checks the resulting keys against contract/vpn-protocol.schema.json,
// the backend's generated schema. Both backend routes refuse unknown keys
// (ValidationPipe forbidNonWhitelisted; zod .strict()), so a renamed field is
// not ignored, it is a 400 on every connect — that already happened once here
// (`serverId` for `serverNodeId`, see the AUDIT-M-DRIFT note in APIClient).
//
// A field added to either struct MUST exist in the vendored schema, and the
// schema MUST be refreshed from birdo-web when the backend changes (see
// contract/README.md).

/// Builds the JSONEncoder every APIClient request body goes through.
///
/// Deliberately the stock configuration: no key strategy (the backend's DTOs
/// are camelCase and so are these structs), no date strategy (no dates on the
/// connect bodies). `nil` optionals are OMITTED — the multi-hop zod schema's
/// `.optional()` accepts an absent key but rejects an explicit `null`.
enum ConnectWire {
    static func makeEncoder() -> JSONEncoder {
        JSONEncoder()
    }

    /// BirdoShield (D18): the ONE UserDefaults key the toggle is persisted
    /// under. `SettingsViewModel` writes it, `APIClient` reads it at dial time
    /// to decide whether the connect bodies carry `dnsFiltering: true`. It is
    /// a constant so a rename can never split the writer from the reader —
    /// which would silently turn every installed user's toggle OFF — and it
    /// lives here (not in either of them) because this file is the only one
    /// of the three the test bundle can compile.
    static let dnsFilteringDefaultsKey = "dns_filtering"
}

/// POST /vpn/connect — twin of birdo-web `ConnectDto`.
struct ConnectBody: Encodable {
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
    /// the response: it never crosses the app-to-API link under classical TLS.
    /// (The server still sends it on to the VPN server over TLS that is not
    /// yet post-quantum, MR-1444; see BirdoPQManager's threat model.) Only ever
    /// true alongside `pqClientPublicKey`; VPNManager fails closed if
    /// decapsulation then fails, so this can never silently downgrade.
    let pqClientCanDecapsulate: Bool?
    /// REBUILD (Mobile-Client #159): this request rides the live tunnel it is
    /// replacing — see `APIClient.getConnectConfig(serverId:rebuildOf:)`.
    /// Twin of birdo-web `ConnectDto.rebuild` / `.currentKeyId`.
    let rebuild: Bool?
    /// The server-side key the live tunnel is riding; the ONE key the server
    /// defers. Sent only together with `rebuild`.
    let currentKeyId: String?
    /// BirdoShield (D18): per-DEVICE opt-in to the node's filtering DNS
    /// resolver (ads, trackers, malware domains). `true` when the toggle is
    /// on, `nil` — ABSENT — when it is off, so an untouched device sends the
    /// pre-D18 body, the only one a backend that predates the field accepts
    /// (forbidNonWhitelisted). Twin of birdo-web `ConnectDto.dnsFiltering`.
    let dnsFiltering: Bool?
}

/// POST /vpn/multi-hop/connect — twin of birdo-web `multiHopConnectSchema`
/// (`.strict()`; `entryNodeId` and `exitNodeId` are the only required keys).
struct MultiHopBody: Encodable {
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
    /// REBUILD (#159) — declared on BOTH bodies (the wire-model twin); the
    /// backend's `multiHopConnectSchema` is `.strict()`.
    let rebuild: Bool?
    let currentKeyId: String?
    /// BirdoShield (D18) — see ConnectBody. Declared on BOTH bodies (the
    /// wire-model twin): `multiHopConnectSchema` carries the same optional
    /// boolean, so a double-hop user gets the filtering resolver too.
    let dnsFiltering: Bool?
}

// MARK: - Response

// MR-1949: `VPNConnectionConfig` (it carries the tunnel's private key and
// pre-shared key) moved here from APIClient.swift VERBATIM, with its
// validation error, so CredentialDescriptionTests can check the real struct's
// printed form. The response half of the same connect contract.

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

    /// REBUILD (Mobile-Client #159): echo of the request's `currentKeyId` when
    /// the server deferred that key's eviction (it stays on its node until this
    /// new peer handshakes). Absent on a plain connect, and on a backend that
    /// accepted the flag but did not defer — the client treats both as
    /// "not honoured" (`RebuildDeferral`).
    let deferredKeyId: String?

    /// AUDIT-C1 (BirdoPQ v1, optional): set when the server received a
    /// `pqClientPublicKey` and produced a per-connect ML-KEM ciphertext for
    /// the client to decapsulate. The Swift `BirdoPQManager` derives a
    /// 32-byte WireGuard PSK from these fields client-side.
    let quantumEnabled: Bool?
    let rosenpassPublicKey: String?
    let rosenpassEndpoint: String?

    /// The warn-only version floor advisory, when this build is below it.
    /// See [ClientUpdateAdvisory] — OPEN-WORK H3.
    let clientUpdate: ClientUpdateAdvisory?

    /// `/vpn/multi-hop/connect` only: the route the backend says it ACTUALLY
    /// installed. `VpnViewModel.connectMultiHop` refuses to bring the tunnel
    /// up unless this names the requested pair (`MultiHopRouteCheck`) — see
    /// MultiHopRoute.swift. Absent on single-hop responses.
    let multiHop: MultiHopRouteInfo?

    private enum CodingKeys: String, CodingKey {
        case endpoint, privateKey, serverPublicKey, presharedKey
        case assignedIp, clientIpv6, dns, allowedIps, mtu, keyId, deferredKeyId
        case quantumEnabled, rosenpassPublicKey, rosenpassEndpoint
        case multiHop, clientUpdate
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
        deferredKeyId = try c.decodeIfPresent(String.self, forKey: .deferredKeyId)

        quantumEnabled = try c.decodeIfPresent(Bool.self, forKey: .quantumEnabled)
        rosenpassPublicKey = try c.decodeIfPresent(String.self, forKey: .rosenpassPublicKey)
        rosenpassEndpoint = try c.decodeIfPresent(String.self, forKey: .rosenpassEndpoint)

        multiHop = try c.decodeIfPresent(MultiHopRouteInfo.self, forKey: .multiHop)

        // Advice, never a reason to reject a good tunnel config: a malformed
        // advisory decodes to nil and the connect proceeds untouched.
        clientUpdate = try? c.decodeIfPresent(ClientUpdateAdvisory.self, forKey: .clientUpdate)
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
