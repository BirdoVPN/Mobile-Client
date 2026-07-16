import Foundation
import NetworkExtension

/// Wraps NETunnelProviderManager for starting/stopping the WireGuard VPN tunnel.
///
/// Security posture:
/// - The WireGuard private key & PSK are written to the **shared App Group
///   keychain** (see `KeychainService.setSharedSecret`) and the tunnel
///   extension reads them by ID. They never appear in
///   `NETunnelProviderProtocol.providerConfiguration` (which is stored in
///   plaintext in NEVPN system preferences).
/// - `includeAllNetworks = true` enables the iOS kill switch: traffic is
///   blocked when the tunnel is down, even on cellular.
/// - `excludeLocalNetworks = true` keeps AirPlay / printers reachable.
/// - `disconnectOnSleep = false` keeps the tunnel up across screen-off so
///   background fetches stay protected.
final class VPNManager: @unchecked Sendable {
    static let shared = VPNManager()

    var onStatusChange: ((NEVPNStatus) -> Void)?

    private var manager: NETunnelProviderManager?
    private var statusObserver: NSObjectProtocol?

    init() {
        loadManager()
    }

    deinit {
        if let observer = statusObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    // MARK: - Public

    func connect(config: VPNConnectionConfig) async throws {
        // SEC: validate the server response BEFORE any keychain write or
        // VPN preferences mutation. A malformed response must never reach
        // the system VPN configuration store.
        try config.validate()

        let mgr = try await ensureManager()

        // AUDIT-C1: prefer a true bilateral PQ PSK over the server-provided
        // classical one. `tryDecapsulate` returns nil when the server didn't
        // ship a ciphertext, in which case we fall back to `presharedKey`.
        let pqPsk = BirdoPQManager.shared.tryDecapsulate(
            quantumEnabled: config.quantumEnabled ?? false,
            rosenpassPublicKeyBase64: config.rosenpassPublicKey,
            rosenpassEndpointBase64: config.rosenpassEndpoint
        )
        let effectivePsk: String?
        if let pqPsk {
            effectivePsk = pqPsk
        } else if let serverPsk = config.presharedKey, !serverPsk.isEmpty {
            BirdoPQManager.shared.recordServerProvided()
            effectivePsk = serverPsk
        } else {
            BirdoPQManager.shared.recordDisabled()
            effectivePsk = nil
        }

        // Park secrets in the shared keychain — the extension reads them by
        // ID instead of receiving them through plaintext provider config.
        let keychain = KeychainService.shared
        guard keychain.setSharedSecret(key: "wg_private_key", value: config.privateKey) else {
            throw VPNManagerError.keychainUnavailable
        }
        if let psk = effectivePsk, !psk.isEmpty {
            guard keychain.setSharedSecret(key: "wg_preshared_key", value: psk) else {
                // SEC: if the PSK write fails the extension would read a
                // missing secret and the tunnel would fail silently. Wipe the
                // already-written private key so we don't leave half-state.
                keychain.deleteShared(key: "wg_private_key")
                throw VPNManagerError.keychainUnavailable
            }
        } else {
            keychain.deleteShared(key: "wg_preshared_key")
        }

        // Build a redacted WG config string (no PrivateKey / PresharedKey).
        let wgConfig = buildRedactedWireGuardConfig(config)

        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = "app.birdo.vpn.tunnel"
        proto.serverAddress = config.serverAddress
        proto.providerConfiguration = [
            "wg-config": wgConfig,
            "wg-private-key-ref": "wg_private_key",
            "wg-preshared-key-ref": (effectivePsk?.isEmpty == false) ? "wg_preshared_key" : "",
        ]
        // SEC: kill switch — when ON, block all traffic while the tunnel is not
        // up. User-toggleable, default ON: read the raw stored value so a fresh
        // install (absent key) defaults true even before the settings view model
        // has registered its defaults.
        let killSwitch = UserDefaults.standard.object(forKey: "kill_switch") as? Bool ?? true
        if #available(iOS 14.0, *) {
            proto.includeAllNetworks = killSwitch
            proto.excludeLocalNetworks = true
            proto.enforceRoutes = killSwitch
        }
        proto.disconnectOnSleep = false

        mgr.protocolConfiguration = proto
        mgr.isEnabled = true
        mgr.localizedDescription = "Birdo VPN"

        // On-demand: with the kill switch ON, keep the tunnel up automatically
        // across reachability changes. With it OFF, disable on-demand so a tunnel
        // drop lets traffic fall back to the open network instead of reconnecting.
        if killSwitch {
            let connectRule = NEOnDemandRuleConnect()
            connectRule.interfaceTypeMatch = .any
            mgr.onDemandRules = [connectRule]
            mgr.isOnDemandEnabled = true
        } else {
            mgr.onDemandRules = nil
            mgr.isOnDemandEnabled = false
        }

        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            mgr.saveToPreferences { error in
                if let error { cont.resume(throwing: error) }
                else { cont.resume() }
            }
        }
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            mgr.loadFromPreferences { error in
                if let error { cont.resume(throwing: error) }
                else { cont.resume() }
            }
        }

        try mgr.connection.startVPNTunnel()
    }

    func disconnect() {
        manager?.connection.stopVPNTunnel()
        // Also disable on-demand so the tunnel stays down when the user asked.
        manager?.isOnDemandEnabled = false
        manager?.saveToPreferences { error in
            if let error {
                // Persisting the on-demand-disabled state failed; without it
                // the tunnel may auto-reconnect against the user's explicit
                // disconnect. Surface it instead of swallowing silently.
                NSLog("[VPNManager] disconnect saveToPreferences failed: %@", error.localizedDescription)
            }
        }
        // SEC: wipe shared secrets from keychain — the kernel tunnel has
        // already consumed them; nothing else needs them after disconnect.
        let keychain = KeychainService.shared
        keychain.deleteShared(key: "wg_private_key")
        keychain.deleteShared(key: "wg_preshared_key")
    }

    /// Query the PacketTunnel extension for live transfer stats via the
    /// `NETunnelProviderSession.sendProviderMessage("stats")` IPC channel.
    /// Returns `(0, 0)` if the tunnel is down or the IPC call fails.
    func currentStats() async -> (rx: Int64, tx: Int64) {
        guard let session = manager?.connection as? NETunnelProviderSession,
              session.status == .connected else {
            return (0, 0)
        }
        return await withCheckedContinuation { (cont: CheckedContinuation<(Int64, Int64), Never>) in
            do {
                try session.sendProviderMessage(Data("stats".utf8)) { responseData in
                    guard let data = responseData,
                          let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                        cont.resume(returning: (0, 0))
                        return
                    }
                    let rx = (json["rx"] as? NSNumber)?.int64Value ?? 0
                    let tx = (json["tx"] as? NSNumber)?.int64Value ?? 0
                    cont.resume(returning: (rx, tx))
                }
            } catch {
                // IPC to the tunnel extension failed even though the session
                // reported .connected. Log so a transient tunnel/permission
                // failure isn't silently indistinguishable from zero traffic.
                NSLog("[VPNManager] currentStats sendProviderMessage failed: %@", error.localizedDescription)
                cont.resume(returning: (0, 0))
            }
        }
    }

    // MARK: - Private

    private func loadManager() {
        NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, _ in
            guard let self else { return }
            if let existing = managers?.first {
                self.manager = existing
                self.observeStatus(existing)
            }
        }
    }

    private func ensureManager() async throws -> NETunnelProviderManager {
        if let mgr = manager { return mgr }

        return try await withCheckedThrowingContinuation { cont in
            NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, error in
                if let error {
                    cont.resume(throwing: error)
                    return
                }
                let mgr = managers?.first ?? NETunnelProviderManager()
                self?.manager = mgr
                self?.observeStatus(mgr)
                cont.resume(returning: mgr)
            }
        }
    }

    private func observeStatus(_ manager: NETunnelProviderManager) {
        if let existing = statusObserver {
            NotificationCenter.default.removeObserver(existing)
        }
        statusObserver = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: manager.connection,
            queue: .main
        ) { [weak self] note in
            // Read status from the notification's object (the connection we
            // registered for) rather than capturing `manager` strongly, which
            // would keep the NETunnelProviderManager alive via self.statusObserver.
            guard let connection = note.object as? NEVPNConnection else { return }
            self?.onStatusChange?(connection.status)
        }
    }

    /// Build a WireGuard config string with `PrivateKey` and `PresharedKey`
    /// **omitted** — the tunnel extension fetches those from the shared
    /// keychain so they never get persisted to NEVPN preferences.
    private func buildRedactedWireGuardConfig(_ config: VPNConnectionConfig) -> String {
        var lines: [String] = []
        lines.append("[Interface]")
        for addr in config.addresses {
            lines.append("Address = \(addr)")
        }
        if !config.dns.isEmpty {
            lines.append("DNS = \(config.dns.joined(separator: ", "))")
        }
        if let mtu = config.mtu {
            lines.append("MTU = \(mtu)")
        }

        lines.append("")
        lines.append("[Peer]")
        lines.append("PublicKey = \(config.publicKey)")
        lines.append("Endpoint = \(config.serverAddress):\(config.serverPort)")
        for ip in config.allowedIPs {
            lines.append("AllowedIPs = \(ip)")
        }
        lines.append("PersistentKeepalive = 25")

        return lines.joined(separator: "\n")
    }
}

enum VPNManagerError: Error, LocalizedError {
    case keychainUnavailable

    var errorDescription: String? {
        switch self {
        case .keychainUnavailable:
            return "Secure storage unavailable. Restart the app and try again."
        }
    }
}
