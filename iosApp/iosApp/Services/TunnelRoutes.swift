import Foundation

/// MR-953: the iOS half of the route contract — the AllowedIPs the tunnel
/// profile carries, from which WireGuardKit's PacketTunnelSettingsGenerator
/// builds `NEIPv4Settings.includedRoutes` / `NEIPv6Settings.includedRoutes`
/// (one route per entry). Android's half is `VpnService.protect()` and
/// `Builder.addRoute` (BirdoVpnService.buildVpnInterface,
/// protectTunnelSockets); the review of both is docs/ROUTE-CONTRACT-REVIEW.md.
///
/// The same floors Android enforces, so a partial or odd server list can never
/// leave traffic outside the tunnel while the app says Protected:
///   * no entries at all -> the full tunnel, `0.0.0.0/0` and `::/0`
///     (a peer with no AllowedIPs routes nothing);
///   * `::/0` always: on a node without IPv6 the tunnel has no IPv6 address,
///     so IPv6 routed into it is dropped (leak-safe) instead of leaving on the
///     physical interface;
///   * `0.0.0.0/0` always: iOS has no split tunnelling, so a list of specific
///     prefixes would only ever be a silent split tunnel;
///   * every entry as its NETWORK address: WireGuardKit hands a peer's
///     AllowedIPs to `includedRoutes` unmasked, and `10.0.0.1/8` is the
///     host-bits shape Android's `addRoute` rejects ("Bad address").
///     WireGuard masks AllowedIPs itself, so the tunnel routes the same
///     prefix either way.
///
/// Foundation-only (inet_pton / inet_ntop come with it), pinned by
/// TunnelRoutesTests; VPNManager (NetworkExtension) cannot be compiled into the
/// unit-test bundle.
enum TunnelRoutes {
    static let fullTunnel = ["0.0.0.0/0", "::/0"]

    /// The AllowedIPs lines for the server's list, floors applied, in the
    /// server's order with the added defaults last.
    static func allowedIPs(_ server: [String]) -> [String] {
        var routes = server.isEmpty ? fullTunnel : server.map(networkAddress)
        if !routes.contains(where: isIPv6DefaultRoute) {
            routes.append("::/0")
        }
        if !routes.contains(where: isIPv4DefaultRoute) {
            routes.append("0.0.0.0/0")
        }
        return routes
    }

    /// True if an AllowedIPs entry is the IPv6 default route (`::/0`), tolerating
    /// surrounding whitespace and the `0::/0` spelling.
    static func isIPv6DefaultRoute(_ s: String) -> Bool {
        guard let cidr = parse(s, family: AF_INET6) else { return false }
        return cidr.prefix == 0 && cidr.bytes.allSatisfy { $0 == 0 }
    }

    /// True if an AllowedIPs entry is the IPv4 default route (`0.0.0.0/0`),
    /// tolerating whitespace (`0/0` is not accepted: inet_pton wants a dotted
    /// quad).
    static func isIPv4DefaultRoute(_ s: String) -> Bool {
        guard let cidr = parse(s, family: AF_INET) else { return false }
        return cidr.prefix == 0 && cidr.bytes.allSatisfy { $0 == 0 }
    }

    /// `10.0.0.1/8` -> `10.0.0.0/8`, `fd00::1/64` -> `fd00::/64`. An entry that
    /// is not a CIDR is returned unchanged (ConnectWire's validation has
    /// already refused those before a profile is built).
    static func networkAddress(_ cidr: String) -> String {
        let trimmed = cidr.trimmingCharacters(in: .whitespaces)
        for family in [AF_INET, AF_INET6] {
            guard let parsed = parse(trimmed, family: family) else { continue }
            let prefix = parsed.prefix
            var masked = parsed.bytes
            for i in masked.indices {
                let bitsBefore = i * 8
                if prefix <= bitsBefore {
                    masked[i] = 0
                } else if prefix < bitsBefore + 8 {
                    masked[i] &= UInt8(truncatingIfNeeded: 0xFF << (8 - (prefix - bitsBefore)))
                }
            }
            guard let address = format(masked, family: family) else { return trimmed }
            return "\(address)/\(prefix)"
        }
        return trimmed
    }

    /// The address bytes and prefix of a CIDR of the given family, or nil.
    private static func parse(_ s: String, family: Int32) -> (bytes: [UInt8], prefix: Int)? {
        let trimmed = s.trimmingCharacters(in: .whitespaces)
        guard let slash = trimmed.firstIndex(of: "/"),
              let prefix = Int(trimmed[trimmed.index(after: slash)...]) else { return nil }
        let length = family == AF_INET ? 4 : 16
        guard (0...(length * 8)).contains(prefix) else { return nil }
        var buffer = [UInt8](repeating: 0, count: length)
        let address = String(trimmed[..<slash])
        let ok = buffer.withUnsafeMutableBytes { raw in
            address.withCString { inet_pton(family, $0, raw.baseAddress) }
        }
        return ok == 1 ? (bytes: buffer, prefix: prefix) : nil
    }

    private static func format(_ bytes: [UInt8], family: Int32) -> String? {
        var text = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        let written = bytes.withUnsafeBytes { raw in
            text.withUnsafeMutableBufferPointer { out in
                inet_ntop(family, raw.baseAddress, out.baseAddress, socklen_t(out.count)) != nil
            }
        }
        guard written else { return nil }
        return text.withUnsafeBufferPointer { String(cString: $0.baseAddress!) }
    }
}
