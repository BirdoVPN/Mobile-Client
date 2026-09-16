import Foundation

// Tunnel-time DNS selection — the pure half of `VPNManager.resolveDnsServers`.
//
// Foundation-only by design: BirdoVPNTests is an un-hosted bundle that cannot
// compile VPNManager.swift (NetworkExtension, the KMP framework), so the
// predicates that decide WHICH resolver the wg-quick config carries live here
// and are compiled into the test target directly. The bug they guard was found
// by the adversarial review of Mobile-Client #403: BirdoShield ON asked the
// server for its filtering resolver, the server answered `10.13.13.1`, and
// `isUsableDnsAddress` threw it away as RFC1918 — so ON resolved through
// 1.1.1.1 exactly like OFF, on every dial path, with no error and no test.
//
// Mirrors Android's `WireGuardConfigBuilder` (resolveDnsServers /
// isValidDnsAddress / isTunnelGatewayResolver) — keep the two in step.

enum TunnelDns {

    /// Cloudflare, used whenever nothing the server or the user supplied is
    /// usable — a tunnel with no DNS line leaves the device on its current
    /// (ISP / captive Wi-Fi) resolver while tunnelled, a DNS leak.
    static let fallback = ["1.1.1.1", "1.0.0.1"]

    /// The DNS line for the tunnel config: the user's custom entries when the
    /// custom-DNS switch is on, else the server's list, else `fallback`.
    ///
    /// `tunnelAddresses` are the client's assigned tunnel addresses (CIDR, as
    /// `VPNConnectionConfig.addresses` carries them) — needed to recognise the
    /// node's own gateway resolver, see `isTunnelGatewayResolver`.
    static func resolve(
        serverDns: [String],
        tunnelAddresses: [String],
        customDnsEnabled: Bool,
        customPrimary: String,
        customSecondary: String
    ) -> [String] {
        if customDnsEnabled {
            // User-typed entries get the plain predicate only: the gateway
            // exception is for what the SERVER hands out, never for a value
            // the user (or a settings rewrite) typed.
            let custom = [customPrimary, customSecondary]
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { Self.isUsableDnsAddress($0) }
            return custom.isEmpty ? fallback : custom
        }
        let usable = serverDns.filter {
            Self.isUsableDnsAddress($0) || Self.isTunnelGatewayResolver($0, tunnelAddresses: tunnelAddresses)
        }
        return usable.isEmpty ? fallback : usable
    }

    /// A DNS entry must be an IP literal (a hostname would have to be resolved
    /// by the resolver we are configuring) and must not be loopback or
    /// unspecified. This also stops user-typed text in the custom-DNS fields
    /// from injecting extra lines into the generated wg-quick config.
    /// `SettingsViewModel` runs the SAME check on the custom-DNS fields so the
    /// UI's validity feedback and the persisted-value gate can never drift
    /// from what the tunnel builder actually accepts.
    ///
    /// The ONE private address that IS reachable through the tunnel — the
    /// node's own gateway resolver (BirdoShield) — is admitted separately by
    /// `isTunnelGatewayResolver`, for server-provided entries only.
    static func isUsableDnsAddress(_ s: String) -> Bool {
        var v4 = in_addr()
        var v6 = in6_addr()
        if s.withCString({ inet_pton(AF_INET, $0, &v4) }) == 1 {
            let host = UInt32(bigEndian: v4.s_addr)
            if host == 0 || (host >> 24) == 127 { return false }   // 0.0.0.0, 127/8
            // Tunnel-reachability (Android WireGuardConfigBuilder parity): a
            // private or link-scoped resolver either leaks DNS onto the LAN
            // (excludeLocalNetworks on) or blackholes all resolution inside a
            // public-egress tunnel (off). Never usable either way.
            if (host >> 24) == 10 { return false }                 // 10/8
            if (host >> 20) == 0xAC1 { return false }              // 172.16/12
            if (host >> 16) == 0xC0A8 { return false }             // 192.168/16
            if (host >> 16) == 0xA9FE { return false }             // 169.254/16
            return true
        }
        if s.withCString({ inet_pton(AF_INET6, $0, &v6) }) == 1 {
            let bytes = withUnsafeBytes(of: &v6) { Array($0) }
            if bytes.allSatisfy({ $0 == 0 }) { return false }                        // ::
            if bytes.dropLast().allSatisfy({ $0 == 0 }) && bytes.last == 1 { return false } // ::1
            if (bytes[0] & 0xFE) == 0xFC { return false }          // ULA fc00::/7
            if bytes[0] == 0xFE && (bytes[1] & 0xC0) == 0x80 { return false } // fe80::/10
            if bytes[0] == 0xFF { return false }                   // multicast ff00::/8
            return true
        }
        return false
    }

    /// BirdoShield (D18): is `s` the tunnel-gateway resolver for a client whose
    /// tunnel addresses are `tunnelAddresses`?
    ///
    /// Every node allocates client addresses from one /24 (the fleet's
    /// BIRDO_AGENT_WG_SUBNET, 10.13.13.0/24) and runs the blocky filtering
    /// resolver on its own wg0 address in that /24 (10.13.13.1). That address
    /// is RFC1918, so `isUsableDnsAddress` rejects it — correctly for a LAN
    /// resolver, wrongly for this one: it is reachable ONLY through the tunnel,
    /// by construction, because it shares the subnet the tunnel interface owns
    /// (WireGuardKit routes AllowedIPs 0.0.0.0/0 into the tunnel, and
    /// `excludeLocalNetworks` carves out only the physical interface's own
    /// subnets — so no extra route is needed here, unlike Android's CIDR-based
    /// LAN exclusion).
    ///
    /// The rule is narrow: IPv4 literals only (no lookups), the same /24 as
    /// an assigned IPv4 tunnel address, not that address itself and not the
    /// network/broadcast host. A LAN resolver (10.0.0.53, 192.168.1.1) never
    /// satisfies it, so the leak/blackhole reasoning above still holds for
    /// everything else.
    static func isTunnelGatewayResolver(_ s: String, tunnelAddresses: [String]) -> Bool {
        guard let resolver = ipv4Literal(s) else { return false }
        let host = resolver & 0xFF
        if host == 0 || host == 0xFF { return false }
        for cidr in tunnelAddresses {
            let addrText = cidr.split(separator: "/", maxSplits: 1).first.map(String.init) ?? cidr
            guard let assigned = ipv4Literal(addrText) else { continue }
            if resolver == assigned { continue }
            if (resolver >> 8) == (assigned >> 8) { return true }
        }
        return false
    }

    /// Dotted-quad only — never a hostname, so no resolver is consulted.
    private static func ipv4Literal(_ s: String) -> UInt32? {
        var v4 = in_addr()
        let text = s.trimmingCharacters(in: .whitespaces)
        guard text.withCString({ inet_pton(AF_INET, $0, &v4) }) == 1 else { return nil }
        return UInt32(bigEndian: v4.s_addr)
    }
}
