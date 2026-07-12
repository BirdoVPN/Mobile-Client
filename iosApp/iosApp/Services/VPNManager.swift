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
/// The user's tunnel-affecting settings, snapshotted at connect time.
/// Reads the exact keys SettingsViewModel persists. Kill switch and quantum
/// protection default ON for fresh installs — same lockdown-by-default
/// posture as Android; `UserDefaults.bool` alone would silently default them
/// OFF because the key is absent.
struct TunnelPreferences {
    var killSwitch: Bool = true
    var localNetworkSharing: Bool = false
    var autoConnect: Bool = false
    var quantumProtection: Bool = true
    var customDnsEnabled: Bool = false
    var customDnsPrimary: String = ""
    var customDnsSecondary: String = ""
    var wireGuardPort: String = "auto"
    var mtuOverride: Int? = nil

    static func load(from defaults: UserDefaults = .standard) -> TunnelPreferences {
        var p = TunnelPreferences()
        if defaults.object(forKey: "kill_switch") != nil {
            p.killSwitch = defaults.bool(forKey: "kill_switch")
        }
        if defaults.object(forKey: "quantum_protection") != nil {
            p.quantumProtection = defaults.bool(forKey: "quantum_protection")
        }
        p.localNetworkSharing = defaults.bool(forKey: "local_network_sharing")
        p.autoConnect = defaults.bool(forKey: "auto_connect")
        p.customDnsEnabled = defaults.bool(forKey: "custom_dns")
        p.customDnsPrimary = defaults.string(forKey: "custom_dns_primary") ?? ""
        p.customDnsSecondary = defaults.string(forKey: "custom_dns_secondary") ?? ""
        p.wireGuardPort = defaults.string(forKey: "wg_port") ?? "auto"
        let mtu = defaults.integer(forKey: "wg_mtu")
        p.mtuOverride = (1280...1500).contains(mtu) ? mtu : nil
        return p
    }
}

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

    func connect(config: VPNConnectionConfig, preferences: TunnelPreferences = .load()) async throws {
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
        // SEC: kill switch — block all traffic when the tunnel is not up.
        // Driven by the user's toggle (default ON via TunnelPreferences);
        // hardcoding `true` here made the Settings switch a lie.
        if #available(iOS 14.0, *) {
            proto.includeAllNetworks = preferences.killSwitch
            proto.excludeLocalNetworks = preferences.localNetworkSharing
            proto.enforceRoutes = true
        }
        proto.disconnectOnSleep = false

        mgr.protocolConfiguration = proto
        mgr.isEnabled = true
        mgr.localizedDescription = "Birdo VPN"

        // On-demand: reconnect automatically across reachability changes.
        // Tied to the user's Auto-Connect toggle; the kill switch does not
        // depend on this (includeAllNetworks blocks regardless).
        let connectRule = NEOnDemandRuleConnect()
        connectRule.interfaceTypeMatch = .any
        mgr.onDemandRules = [connectRule]
        mgr.isOnDemandEnabled = preferences.autoConnect

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

        // NETunnelProviderManager is not Sendable, so Swift 6 refuses to let it
        // cross the continuation boundary. NetworkExtension delivers these
        // completions on its own serial queue and the value is only read by the
        // caller awaiting here, so the hop is safe — but say so EXPLICITLY with
        // a narrow box rather than disabling concurrency checking for the file.
        let boxed: UncheckedSendableBox<NETunnelProviderManager> =
            try await withCheckedThrowingContinuation { cont in
                NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, error in
                    if let error {
                        cont.resume(throwing: error)
                        return
                    }
                    let mgr = managers?.first ?? NETunnelProviderManager()
                    self?.manager = mgr
                    self?.observeStatus(mgr)
                    cont.resume(returning: UncheckedSendableBox(value: mgr))
                }
            }
        return boxed.value
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
        lines.append("PersistentKeepalive = \(config.persistentKeepalive ?? 25)")

        return lines.joined(separator: "\n")
    }
}

/// Carries a non-Sendable value across ONE known-safe concurrency hop.
/// Kept private and minimal on purpose: an `@unchecked Sendable` escape hatch
/// should be as small as the hop it exists for.
private struct UncheckedSendableBox<T>: @unchecked Sendable {
    let value: T
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
