import NetworkExtension
import Security
import WireGuardKit
import os.log

/// WireGuard packet tunnel provider.
///
/// Production wiring:
/// - Uses `WireGuardAdapter` from WireGuardKit to drive the Go tunnel runtime.
/// - PrivateKey & PresharedKey are read from the **shared App Group keychain**
///   (the host app's `VPNManager` writes them there on connect) so they
///   never appear in the NEVPN preferences plaintext blob.
/// - All `os_log` calls use `%{private}@` for any string derived from the
///   config so secrets stay marked-private in sysdiagnose.
/// - Forwards `NEPacketTunnelProvider` → adapter network-settings calls,
///   honouring per-address CIDR for both IPv4 and IPv6.
/// - Implements live transfer-stats IPC for the host app (`stats` message).
class PacketTunnelProvider: NEPacketTunnelProvider {
    private let log = OSLog(subsystem: "app.birdo.vpn.tunnel", category: "tunnel")

    private lazy var adapter: WireGuardAdapter = {
        WireGuardAdapter(with: self) { [weak self] level, message in
            guard let self else { return }
            // Map WireGuardKit log levels onto os_log; NEVER include the
            // raw message at .info/.error in production builds because it
            // can include endpoints. Mark private.
            let type: OSLogType
            switch level {
            case .verbose: type = .debug
            case .error:   type = .error
            }
            os_log("wg: %{private}@", log: self.log, type: type, message)
        }
    }()

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

        // Read secrets out of the shared keychain. The IDs are passed via
        // providerConfiguration; the actual material never appears there.
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

        // Reconstruct a complete `wg-quick` config (with the keys) and parse
        // it into a WireGuardKit `TunnelConfiguration`. The reconstructed
        // string is NEVER persisted — it lives only in this stack frame
        // until WireGuardAdapter copies it into the Go runtime.
        //
        // SEC NOTE: Swift `String` is immutable + value-typed, so we cannot
        // truly zero its backing buffer. Mitigation: keep the variable in
        // the tightest possible scope (an immediately-invoked closure) so
        // ARC drops the only strong reference the moment the parser is done.
        let tunnelConfiguration: TunnelConfiguration
        do {
            tunnelConfiguration = try {
                let fullConfigString = injectSecrets(
                    into: configString,
                    privateKey: privateKey,
                    presharedKey: presharedKey
                )
                return try TunnelConfiguration(
                    fromWgQuickConfig: fullConfigString,
                    called: "birdo"
                )
            }()
        } catch {
            os_log("Failed to parse tunnel config: %{public}@",
                   log: log, type: .error, error.localizedDescription)
            completionHandler(TunnelError.invalidConfig)
            return
        }

        adapter.start(tunnelConfiguration: tunnelConfiguration) { [weak self] adapterError in
            guard let self else { return }
            if let adapterError {
                os_log("Adapter start failed: %{public}@",
                       log: self.log, type: .error,
                       String(describing: adapterError))
                completionHandler(TunnelError.adapterFailed(String(describing: adapterError)))
                return
            }
            os_log("Tunnel up", log: self.log, type: .info)
            completionHandler(nil)
        }
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        os_log("Stopping tunnel, reason: %{public}d", log: log, type: .info, reason.rawValue)
        adapter.stop { [weak self] error in
            if let error {
                os_log("Adapter stop failed: %{public}@",
                       log: self?.log ?? .default, type: .error,
                       String(describing: error))
            }
            completionHandler()
        }
    }

    /// Called by NetworkExtension when the device wakes from sleep.
    /// WireGuardKit's adapter has its own internal `NWPathMonitor` so it
    /// will already have noticed the path change — we just nudge it to
    /// re-resolve the endpoint DNS in case the upstream IP rotated while
    /// we were asleep (e.g. roaming Wi-Fi → 5G across CGNAT boundaries).
    override func wake() {
        os_log("wake() — prompting adapter to re-validate path",
               log: log, type: .info)
        adapter.getRuntimeConfiguration { [weak self] _ in
            // The act of pulling runtime config triggers wg-go's path
            // re-evaluation; nothing else needed.
            _ = self
        }
    }

    override func sleep(completionHandler: @escaping () -> Void) {
        // Keep the tunnel alive while the device is idle; wg-go is
        // configured with PersistentKeepalive=25 to keep the NAT pinhole
        // open. We just acknowledge the call.
        completionHandler()
    }

    /// IPC from the host app. Supported commands:
    ///   "stats" → JSON `{ "rx": <bytes>, "tx": <bytes> }`
    ///   "ping"  → JSON `{ "ok": true }`
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        guard let command = String(data: messageData, encoding: .utf8) else {
            completionHandler?(nil)
            return
        }
        switch command {
        case "stats":
            adapter.getRuntimeConfiguration { [weak self] config in
                guard let config else {
                    completionHandler?(self?.encodeStats(rx: 0, tx: 0))
                    return
                }
                let (rx, tx) = self?.parseTransferStats(uapiConfig: config) ?? (0, 0)
                completionHandler?(self?.encodeStats(rx: rx, tx: tx))
            }
        case "ping":
            completionHandler?(#"{"ok":true}"#.data(using: .utf8))
        default:
            completionHandler?(nil)
        }
    }

    // MARK: - Helpers

    private func encodeStats(rx: Int64, tx: Int64) -> Data {
        let json = #"{"rx":\#(rx),"tx":\#(tx)}"#
        return json.data(using: .utf8) ?? Data()
    }

    /// Sum `rx_bytes=` / `tx_bytes=` lines from the wg-go UAPI dump.
    private func parseTransferStats(uapiConfig: String) -> (rx: Int64, tx: Int64) {
        var rx: Int64 = 0
        var tx: Int64 = 0
        for line in uapiConfig.split(separator: "\n") {
            if line.hasPrefix("rx_bytes=") {
                rx &+= Int64(line.dropFirst("rx_bytes=".count)) ?? 0
            } else if line.hasPrefix("tx_bytes=") {
                tx &+= Int64(line.dropFirst("tx_bytes=".count)) ?? 0
            }
        }
        return (rx, tx)
    }

    /// Build a wg-quick string by re-inserting the secrets pulled from the
    /// shared keychain. Idempotent: if the input already contains a
    /// `PrivateKey =` line we do not duplicate it.
    private func injectSecrets(into config: String, privateKey: String, presharedKey: String?) -> String {
        var lines = config.components(separatedBy: "\n")
        // Insert PrivateKey after the [Interface] header if not present.
        if !lines.contains(where: { $0.trimmingCharacters(in: .whitespaces).hasPrefix("PrivateKey") }) {
            if let idx = lines.firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "[Interface]" }) {
                lines.insert("PrivateKey = \(privateKey)", at: idx + 1)
            }
        }
        // Insert PresharedKey after the [Peer] header if we have one and it isn't already present.
        if let psk = presharedKey, !psk.isEmpty,
           !lines.contains(where: { $0.trimmingCharacters(in: .whitespaces).hasPrefix("PresharedKey") }) {
            if let idx = lines.firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "[Peer]" }) {
                lines.insert("PresharedKey = \(psk)", at: idx + 1)
            }
        }
        return lines.joined(separator: "\n")
    }

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
