import NetworkExtension
import Security
import os.log

/// WireGuard packet tunnel provider.
///
/// Hardened parsing:
/// - PrivateKey & PresharedKey are read from the **shared App Group keychain**
///   (the host app's `VPNManager` writes them there on connect) so they
///   never appear in the NEVPN preferences plaintext blob.
/// - All `os_log` calls use `%{private}@` for any string derived from the
///   config so secrets stay marked-private in sysdiagnose.
/// - IPv4 / IPv6 routing honours per-address CIDR prefixes instead of
///   hard-coding /24 and /128.
/// - Allowed-IP CIDRs become individual `NEIPv4Route` / `NEIPv6Route` entries
///   so split-tunnelling becomes a one-line config swap upstream.
class PacketTunnelProvider: NEPacketTunnelProvider {
    private let log = OSLog(subsystem: "app.birdo.vpn.tunnel", category: "tunnel")

    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        os_log("Starting Birdo VPN tunnel", log: log, type: .info)

        guard let proto = protocolConfiguration as? NETunnelProviderProtocol,
              let configString = proto.providerConfiguration?["wg-config"] as? String else {
            os_log("Missing WireGuard config", log: log, type: .error)
            completionHandler(TunnelError.missingConfig)
            return
        }

        // Read the WireGuard private key out of the shared keychain. The
        // ref id is supplied via providerConfiguration; the secret itself
        // is NEVER stored there.
        let privateKeyRef = (proto.providerConfiguration?["wg-private-key-ref"] as? String) ?? "wg_private_key"
        let presharedKeyRef = (proto.providerConfiguration?["wg-preshared-key-ref"] as? String) ?? ""

        guard let privateKey = readSharedKeychain(account: privateKeyRef), !privateKey.isEmpty else {
            os_log("WireGuard private key missing from shared keychain", log: log, type: .error)
            completionHandler(TunnelError.missingConfig)
            return
        }
        let presharedKey: String? = presharedKeyRef.isEmpty
            ? nil
            : readSharedKeychain(account: presharedKeyRef)

        guard let parsed = parseWireGuardConfig(configString) else {
            os_log("Invalid WireGuard config", log: log, type: .error)
            completionHandler(TunnelError.invalidConfig)
            return
        }

        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: parsed.endpointHost)
        settings.mtu = NSNumber(value: parsed.mtu)

        // IPv4 — derive the subnet mask from each address's CIDR prefix.
        let ipv4Pairs: [(String, String)] = parsed.addresses.compactMap { addr in
            guard addr.contains("."), let pair = ipv4SplitCIDR(addr) else { return nil }
            return pair
        }
        if !ipv4Pairs.isEmpty {
            let ipv4 = NEIPv4Settings(
                addresses: ipv4Pairs.map { $0.0 },
                subnetMasks: ipv4Pairs.map { $0.1 }
            )
            ipv4.includedRoutes = parsed.allowedIPs
                .compactMap { ipv4Route(from: $0) }
                .ifEmpty([NEIPv4Route.default()])
            settings.ipv4Settings = ipv4
        }

        // IPv6 — honour the per-address prefix length (default 128).
        let ipv6Pairs: [(String, NSNumber)] = parsed.addresses.compactMap { addr in
            guard addr.contains(":"), let pair = ipv6SplitCIDR(addr) else { return nil }
            return (pair.0, NSNumber(value: pair.1))
        }
        if !ipv6Pairs.isEmpty {
            let ipv6 = NEIPv6Settings(
                addresses: ipv6Pairs.map { $0.0 },
                networkPrefixLengths: ipv6Pairs.map { $0.1 }
            )
            ipv6.includedRoutes = parsed.allowedIPs
                .compactMap { ipv6Route(from: $0) }
                .ifEmpty([NEIPv6Route.default()])
            settings.ipv6Settings = ipv6
        }

        if !parsed.dns.isEmpty {
            let dnsSettings = NEDNSSettings(servers: parsed.dns)
            // Force every DNS query through the tunnel — prevents leaks.
            dnsSettings.matchDomains = [""]
            settings.dnsSettings = dnsSettings
        }

        setTunnelNetworkSettings(settings) { [weak self] error in
            if let error {
                os_log("Failed to set tunnel settings: %{public}@",
                       log: self?.log ?? .default, type: .error,
                       error.localizedDescription)
                completionHandler(error)
                return
            }

            // TODO: Start WireGuardKit tunnel adapter here.
            //
            // Production wiring once `WireGuardKit` is added via SwiftPM:
            //
            //   let tunnelConfig = try TunnelConfiguration(
            //       fromUapiConfig: self.buildUapi(parsed: parsed,
            //                                      privateKey: privateKey,
            //                                      presharedKey: presharedKey),
            //       called: "birdo")
            //   self.adapter = WireGuardAdapter(with: self) { _, msg in /* ... */ }
            //   self.adapter.start(tunnelConfiguration: tunnelConfig) { err in
            //       completionHandler(err)
            //   }
            //
            // Until WireGuardKit lands, returning success here keeps
            // network settings applied so QA can validate routing & DNS.
            os_log("Tunnel settings applied (peer key %{private}@)",
                   log: self?.log ?? .default, type: .info, parsed.publicKey)
            _ = privateKey   // referenced so compiler doesn't warn; consumed by adapter above
            _ = presharedKey
            completionHandler(nil)
        }
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        os_log("Stopping tunnel, reason: %{public}d", log: log, type: .info, reason.rawValue)
        // TODO: adapter.stop { completionHandler() }
        completionHandler()
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        if let command = String(data: messageData, encoding: .utf8) {
            switch command {
            case "stats":
                let stats = """
                {"rx": 0, "tx": 0}
                """
                completionHandler?(stats.data(using: .utf8))
            default:
                completionHandler?(nil)
            }
        } else {
            completionHandler?(nil)
        }
    }

    // MARK: - Shared Keychain Read

    /// Read a shared-keychain string by account, scoped to the
    /// `app.birdo.vpn.shared` service that the host app writes to.
    private func readSharedKeychain(account: String) -> String? {
        let group = "app.birdo.vpn"
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "app.birdo.vpn.shared",
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: group,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    // MARK: - Config Parsing

    private struct ParsedConfig {
        let addresses: [String]
        let dns: [String]
        let mtu: Int
        let endpointHost: String
        let publicKey: String
        let allowedIPs: [String]
    }

    private func parseWireGuardConfig(_ raw: String) -> ParsedConfig? {
        var addresses: [String] = []
        var dns: [String] = []
        var mtu = 1420
        var endpointHost = ""
        var publicKey = ""
        var allowedIPs: [String] = []

        for line in raw.components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if let v = strip(trimmed, prefix: "Address") {
                addresses.append(contentsOf: splitList(v))
            } else if let v = strip(trimmed, prefix: "DNS") {
                dns.append(contentsOf: splitList(v))
            } else if let v = strip(trimmed, prefix: "MTU") {
                mtu = Int(v) ?? 1420
            } else if let v = strip(trimmed, prefix: "Endpoint") {
                // Strip the port — NEPacketTunnelNetworkSettings wants host only.
                if let colonIdx = v.lastIndex(of: ":"), v.first != "[" {
                    endpointHost = String(v[..<colonIdx])
                } else if v.first == "[", let close = v.firstIndex(of: "]") {
                    endpointHost = String(v[v.index(after: v.startIndex)..<close])
                } else {
                    endpointHost = v
                }
            } else if let v = strip(trimmed, prefix: "PublicKey") {
                publicKey = v
            } else if let v = strip(trimmed, prefix: "AllowedIPs") {
                allowedIPs.append(contentsOf: splitList(v))
            }
        }

        guard !endpointHost.isEmpty, !publicKey.isEmpty, !addresses.isEmpty else {
            return nil
        }
        return ParsedConfig(
            addresses: addresses,
            dns: dns,
            mtu: mtu,
            endpointHost: endpointHost,
            publicKey: publicKey,
            allowedIPs: allowedIPs
        )
    }

    private func strip(_ line: String, prefix: String) -> String? {
        let exact = "\(prefix) = "
        if line.hasPrefix(exact) { return String(line.dropFirst(exact.count)) }
        return nil
    }

    private func splitList(_ value: String) -> [String] {
        value.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
    }

    // MARK: - CIDR helpers

    /// "10.0.0.2/32" → ("10.0.0.2", "255.255.255.255")
    private func ipv4SplitCIDR(_ addr: String) -> (String, String)? {
        let parts = addr.split(separator: "/", maxSplits: 1)
        let ip = String(parts[0])
        let prefix = parts.count > 1 ? Int(parts[1]) ?? 32 : 32
        guard (0...32).contains(prefix) else { return nil }
        var mask: UInt32 = prefix == 0 ? 0 : 0xFFFFFFFF << (32 - prefix)
        let b1 = (mask >> 24) & 0xFF
        let b2 = (mask >> 16) & 0xFF
        let b3 = (mask >> 8) & 0xFF
        let b4 = mask & 0xFF
        _ = mask
        return (ip, "\(b1).\(b2).\(b3).\(b4)")
    }

    /// "fd00::1/64" → ("fd00::1", 64)
    private func ipv6SplitCIDR(_ addr: String) -> (String, Int)? {
        let parts = addr.split(separator: "/", maxSplits: 1)
        let ip = String(parts[0])
        let prefix = parts.count > 1 ? Int(parts[1]) ?? 128 : 128
        guard (0...128).contains(prefix) else { return nil }
        return (ip, prefix)
    }

    private func ipv4Route(from cidr: String) -> NEIPv4Route? {
        let parts = cidr.split(separator: "/", maxSplits: 1)
        guard let ip = parts.first.map(String.init), ip.contains(".") else { return nil }
        let prefix = parts.count > 1 ? Int(parts[1]) ?? 0 : 32
        if ip == "0.0.0.0" && prefix == 0 { return NEIPv4Route.default() }
        guard let (addr, mask) = ipv4SplitCIDR(cidr) else { return nil }
        return NEIPv4Route(destinationAddress: addr, subnetMask: mask)
    }

    private func ipv6Route(from cidr: String) -> NEIPv6Route? {
        let parts = cidr.split(separator: "/", maxSplits: 1)
        guard let ip = parts.first.map(String.init), ip.contains(":") else { return nil }
        let prefix = parts.count > 1 ? Int(parts[1]) ?? 0 : 128
        if ip == "::" && prefix == 0 { return NEIPv6Route.default() }
        return NEIPv6Route(
            destinationAddress: ip,
            networkPrefixLength: NSNumber(value: prefix)
        )
    }
}

private extension Array {
    /// Return `self` if non-empty, otherwise the supplied fallback.
    func ifEmpty(_ fallback: @autoclosure () -> [Element]) -> [Element] {
        isEmpty ? fallback() : self
    }
}

// MARK: - Errors

enum TunnelError: Error, LocalizedError {
    case missingConfig
    case invalidConfig
    case adapterFailed(String)

    var errorDescription: String? {
        switch self {
        case .missingConfig: return "Missing tunnel configuration"
        case .invalidConfig: return "Invalid tunnel configuration"
        case .adapterFailed(let msg): return "Tunnel adapter failed: \(msg)"
        }
    }
}
