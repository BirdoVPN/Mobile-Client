import Foundation
import NetworkExtension
import Security

/// Wraps NETunnelProviderManager for starting/stopping the WireGuard VPN tunnel.
///
/// Security posture:
/// - The WireGuard private key & PSK are written to the **shared keychain
///   access group** (see `writeSharedSecret`) and the tunnel extension reads
///   them by ID. They never appear in
///   `NETunnelProviderProtocol.providerConfiguration` (which is stored in
///   plaintext in NEVPN system preferences).
/// - `includeAllNetworks = true` enables the iOS kill switch: traffic is
///   blocked when the tunnel is down, even on cellular.
/// - `excludeLocalNetworks` tracks the user's Local Network Sharing setting
///   (default OFF, matching Android): ON keeps AirPlay / printers reachable by
///   routing LAN traffic around the tunnel; OFF carries the LAN in the tunnel.
/// - `disconnectOnSleep = false` keeps the tunnel up across screen-off so
///   background fetches stay protected.
final class VPNManager: @unchecked Sendable {
    static let shared = VPNManager()

    /// AUDIT-C1: fully-qualified keychain access group shared with the
    /// PacketTunnel extension. The entitlement grants
    /// `$(AppIdentifierPrefix)app.birdo.vpn`, and on a PHYSICAL device an
    /// explicit `kSecAttrAccessGroup` must name the team-prefixed group
    /// exactly — the bare `"app.birdo.vpn"` literal fails with
    /// errSecMissingEntitlement (-34018), so every real-device connect threw
    /// `keychainUnavailable`. The simulator does not enforce this, which is
    /// how the unprefixed value ever appeared to work.
    /// MUST stay in lockstep with `PacketTunnelProvider.sharedAccessGroup`
    /// on the extension side — the two targets do not share source files, so
    /// the constant is declared once per side (team id pinned in project.yml).
    static let sharedKeychainAccessGroup = "KPUFGR98A5.app.birdo.vpn"

    /// Keychain service the tunnel secrets live under. Same literal
    /// `KeychainService.sharedService` uses, so the auth layer's blanket
    /// shared-secret wipe on logout still covers these accounts.
    private static let sharedKeychainService = "app.birdo.vpn.shared"

    /// Fired on every NEVPNStatus transition AND replayed with the settled
    /// status whenever a manager finishes loading (finding #4/#7). The `didSet`
    /// covers the race where `loadManager()`'s async completion assigns
    /// `manager` (and calls this) BEFORE `VpnViewModel.init` gets to set the
    /// callback: on assignment we immediately replay the already-loaded
    /// manager's current status so a relaunch under a live tunnel doesn't show
    /// "Not Connected" forever. `handleStatusChange` is idempotent for a
    /// repeated `.connected`, so the belt-and-braces double fire is safe.
    var onStatusChange: ((NEVPNStatus) -> Void)? {
        didSet {
            guard let mgr = manager, let cb = onStatusChange else { return }
            let status = mgr.connection.status
            DispatchQueue.main.async { cb(status) }
        }
    }

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
        // Written directly against the fully-qualified access group (AUDIT-C1)
        // so a real device never trips the unprefixed-group entitlement check.
        guard writeSharedSecret(account: "wg_private_key", value: config.privateKey) else {
            throw VPNManagerError.keychainUnavailable
        }
        if let psk = effectivePsk, !psk.isEmpty {
            guard writeSharedSecret(account: "wg_preshared_key", value: psk) else {
                // SEC: if the PSK write fails the extension would read a
                // missing secret and the tunnel would fail silently. Wipe the
                // already-written private key so we don't leave half-state.
                deleteSharedSecret(account: "wg_private_key")
                throw VPNManagerError.keychainUnavailable
            }
        } else {
            deleteSharedSecret(account: "wg_preshared_key")
        }

        // Build a redacted WG config string (no PrivateKey / PresharedKey).
        let wgConfig = buildRedactedWireGuardConfig(config)

        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = "app.birdo.vpn.tunnel"
        proto.serverAddress = config.serverAddress
        // CRITICAL (finding #2): the liveness heartbeat must run in the
        // extension (it outlives the app; the host app is suspended on lock).
        // Pass the connection handle + a FRESH access token so the extension
        // can POST /vpn/heartbeat/{keyId} on its own. The token lives under the
        // app-only keychain service the extension cannot read, so it has to
        // ride providerConfiguration — which is readable ONLY by this app's own
        // appex (same app group), acceptable per the security review. NEVER log
        // it. A short-lived/stale token failing heartbeat must NOT tear the
        // tunnel down; only an explicit {valid:false} does.
        var providerConfig: [String: Any] = [
            "wg-config": wgConfig,
            "wg-private-key-ref": "wg_private_key",
            "wg-preshared-key-ref": (effectivePsk?.isEmpty == false) ? "wg_preshared_key" : "",
        ]
        if let keyId = config.keyId, !keyId.isEmpty {
            providerConfig["hb-key-id"] = keyId
        }
        if let token = KeychainService.shared.accessToken, !token.isEmpty {
            providerConfig["hb-access-token"] = token
        }
        let clientVersion = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "1.0.0"
        providerConfig["hb-client-version"] = clientVersion
        proto.providerConfiguration = providerConfig
        // SEC: kill switch — when ON, block all traffic while the tunnel is not
        // up. User-toggleable, default ON: read the raw stored value so a fresh
        // install (absent key) defaults true even before the settings view model
        // has registered its defaults.
        let killSwitch = UserDefaults.standard.object(forKey: "kill_switch") as? Bool ?? true
        // Local Network Sharing is a user setting (default OFF, matching
        // Android's `AppPreferences.localNetworkSharing`). `excludeLocalNetworks`
        // routes LAN traffic *around* the tunnel, so hard-coding it to `true`
        // exempted every user's whole local subnet from the VPN regardless of
        // what the toggle said.
        let localNetworkSharing = UserDefaults.standard.bool(forKey: "local_network_sharing")
        if #available(iOS 14.0, *) {
            proto.includeAllNetworks = killSwitch
            proto.excludeLocalNetworks = localNetworkSharing
            proto.enforceRoutes = killSwitch
        }
        proto.disconnectOnSleep = false

        mgr.protocolConfiguration = proto
        mgr.isEnabled = true
        mgr.localizedDescription = "Birdo VPN"

        // On-demand: NEVER arm it before the tunnel is actually up (finding #5).
        // Arming persists a NEOnDemandRuleConnect that survives even if the
        // failure path below deletes the server-side peer — iOS would then
        // auto-dial a dead peer, report .connected without a handshake, and the
        // kill switch (includeAllNetworks) would blackhole every packet under a
        // "Protected" UI. So we always save with on-demand DISABLED here; the
        // rule is armed only once the connection reaches .connected, via
        // applyKillSwitchFlag() called from VpnViewModel's .connected handler.
        mgr.onDemandRules = nil
        mgr.isOnDemandEnabled = false

        do {
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
        } catch {
            // Tunnel start failed. On-demand was never armed above, so there is
            // no live rule to disarm — but defensively clear it and wipe the
            // shared secrets so a half-built local state can never outlive the
            // server-side peer VpnViewModel is about to release.
            mgr.onDemandRules = nil
            mgr.isOnDemandEnabled = false
            mgr.saveToPreferences { _ in }
            deleteSharedSecret(account: "wg_private_key")
            deleteSharedSecret(account: "wg_preshared_key")
            throw error
        }
    }

    func disconnect() {
        // SEC: wipe shared secrets from keychain — the kernel tunnel has
        // already consumed them; nothing else needs them after disconnect.
        deleteSharedSecret(account: "wg_private_key")
        deleteSharedSecret(account: "wg_preshared_key")

        guard let mgr = manager else { return }

        // ORDER MATTERS: on-demand must be disabled *and persisted* BEFORE the
        // tunnel is stopped. Stopping first leaves the on-demand rule live, so
        // iOS immediately re-dials the tunnel it was just told to tear down and
        // the user's explicit disconnect bounces straight back to connected.
        mgr.isOnDemandEnabled = false
        mgr.onDemandRules = nil
        // `mgr` is not Sendable, so it is re-read through `self` inside the
        // completion rather than captured — the same pattern `ensureManager()`
        // already uses for this type.
        mgr.saveToPreferences { [weak self] error in
            if let error {
                // Persisting the on-demand-disabled state failed; the rule may
                // still be live and re-dial after the stop below. Surface it
                // instead of swallowing silently.
                NSLog("[VPNManager] disconnect saveToPreferences failed: %@", error.localizedDescription)
            }
            // Stop on BOTH paths — a failed save must never leave the tunnel up.
            self?.manager?.connection.stopVPNTunnel()
        }
    }

    /// Push the kill-switch flag into the persisted VPN configuration WITHOUT
    /// rebuilding the tunnel (Android parity, §0.6 path 1: kill switch is a
    /// runtime flag push, never a reconnect). The part that matters — what
    /// happens when the tunnel DROPS — is the on-demand rule set, and that
    /// applies from the moment the preferences save lands: ON re-dials and
    /// keeps traffic tunnelled; OFF lets a drop fall back to the open network.
    /// `includeAllNetworks`/`enforceRoutes` are persisted here too, but iOS
    /// honours protocol-level changes only from the next tunnel start.
    ///
    /// On-demand is armed only while a session is live: arming it with the
    /// tunnel down would make iOS immediately dial a VPN the user has not
    /// asked for (toggling the kill switch while disconnected must not
    /// connect). Disarming is always safe. No-op when no VPN configuration
    /// exists yet — connect() reads the persisted UserDefaults value anyway.
    func applyKillSwitchFlag(_ enabled: Bool) {
        guard let mgr = manager else { return }
        if let proto = mgr.protocolConfiguration as? NETunnelProviderProtocol {
            proto.includeAllNetworks = enabled
            proto.enforceRoutes = enabled
            mgr.protocolConfiguration = proto
        }
        let status = mgr.connection.status
        let sessionActive = status == .connected || status == .connecting || status == .reasserting
        if enabled && sessionActive {
            let connectRule = NEOnDemandRuleConnect()
            connectRule.interfaceTypeMatch = .any
            mgr.onDemandRules = [connectRule]
            mgr.isOnDemandEnabled = true
        } else if !enabled {
            mgr.onDemandRules = nil
            mgr.isOnDemandEnabled = false
        }
        mgr.saveToPreferences { error in
            if let error {
                NSLog("[VPNManager] applyKillSwitchFlag saveToPreferences failed: %@",
                      error.localizedDescription)
            }
        }
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

    // MARK: - Shared Tunnel Secrets (host ↔ extension keychain)

    /// Write a tunnel secret into the shared access group with the same
    /// attribute posture as `KeychainService` (data-protection keychain,
    /// AfterFirstUnlockThisDeviceOnly so the extension can read it while the
    /// device is locked, never synced to iCloud). Owned here rather than in
    /// `KeychainService` so both sides of the C1 fix pin the SAME
    /// fully-qualified group literal.
    @discardableResult
    private func writeSharedSecret(account: String, value: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }
        deleteSharedSecret(account: account)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.sharedKeychainService,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessGroup as String: Self.sharedKeychainAccessGroup,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    private func deleteSharedSecret(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.sharedKeychainService,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: Self.sharedKeychainAccessGroup,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - Private

    private func loadManager() {
        NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, _ in
            guard let self else { return }
            if let existing = managers?.first {
                self.manager = existing
                self.observeStatus(existing)
                // finding #4/#7: NEVPNStatusDidChange is NOT replayed for an
                // already-settled session, so a relaunch under a live tunnel
                // would otherwise never seed isConnected. Push the current
                // status through the pipeline now. (The `onStatusChange` didSet
                // covers the reverse race where the callback is assigned after
                // this completion already ran.)
                let status = existing.connection.status
                DispatchQueue.main.async { self.onStatusChange?(status) }
            }
        }
    }

    private func ensureManager() async throws -> NETunnelProviderManager {
        if let mgr = manager { return mgr }

        // `NETunnelProviderManager` is not Sendable, so it can't be handed back
        // through the continuation (`resume(returning:)` takes a `sending`
        // value, and `mgr` has already escaped into `self.manager`). Resume
        // with Void and re-read the stored manager instead — the same property
        // the rest of this type already reads.
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, error in
                if let error {
                    cont.resume(throwing: error)
                    return
                }
                let mgr = managers?.first ?? NETunnelProviderManager()
                self?.manager = mgr
                self?.observeStatus(mgr)
                // finding #4/#7: replay the settled status for an existing
                // (already-connected) session the same way loadManager() does.
                // A freshly-constructed manager reports .invalid, which
                // handleStatusChange treats as "not connected" — harmless.
                if let self {
                    let status = mgr.connection.status
                    DispatchQueue.main.async { self.onStatusChange?(status) }
                }
                cont.resume()
            }
        }

        guard let mgr = manager else { throw VPNManagerError.managerUnavailable }
        return mgr
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
        // DNS is never omitted: a tunnel with no DNS line leaves the device on
        // its current (ISP / captive Wi-Fi) resolver while tunnelled — a DNS
        // leak. Android's `resolveDnsServers` falls back the same way.
        let dnsServers = resolveDnsServers(config)
        lines.append("DNS = \(dnsServers.joined(separator: ", "))")
        // MTU: user override wins, else the server value, else WireGuard's 1420
        // default — clamped to the same 1280...1500 window Android enforces.
        // Previously the user's MTU setting was collected but never applied.
        let userMtu = UserDefaults.standard.integer(forKey: "wg_mtu")
        let effectiveMtu = min(max(userMtu > 0 ? userMtu : (config.mtu ?? 1420), 1280), 1500)
        lines.append("MTU = \(effectiveMtu)")

        lines.append("")
        lines.append("[Peer]")
        lines.append("PublicKey = \(config.publicKey)")
        lines.append("Endpoint = \(endpointString(host: config.serverAddress, port: effectivePort(config.serverPort)))")
        // A peer with no AllowedIPs routes NO traffic. The server normally sends
        // the full-tunnel pair; if it sent none, fall back to it rather than
        // building a tunnel that silently carries nothing (matches Android's
        // `?: listOf("0.0.0.0/0", "::/0")` plus its allowed-IP count check).
        let allowedIPs = config.allowedIPs.isEmpty ? ["0.0.0.0/0", "::/0"] : config.allowedIPs
        for ip in allowedIPs {
            lines.append("AllowedIPs = \(ip)")
        }
        lines.append("PersistentKeepalive = 25")

        return lines.joined(separator: "\n")
    }

    /// Resolve DNS servers, preferring the user's custom entries.
    /// Mirrors Android's `WireGuardConfigBuilder.resolveDnsServers`.
    private func resolveDnsServers(_ config: VPNConnectionConfig) -> [String] {
        let fallback = ["1.1.1.1", "1.0.0.1"]
        let defaults = UserDefaults.standard
        if defaults.bool(forKey: "custom_dns") {
            let custom = [
                defaults.string(forKey: "custom_dns_primary") ?? "",
                defaults.string(forKey: "custom_dns_secondary") ?? "",
            ]
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { Self.isUsableDnsAddress($0) }
            return custom.isEmpty ? fallback : custom
        }
        let serverDns = config.dns.filter { Self.isUsableDnsAddress($0) }
        return serverDns.isEmpty ? fallback : serverDns
    }

    /// A DNS entry must be an IP literal (a hostname would have to be resolved
    /// by the resolver we are configuring) and must not be loopback or
    /// unspecified. This also stops user-typed text in the custom-DNS fields
    /// from injecting extra lines into the generated wg-quick config.
    /// Internal (not private): `SettingsViewModel` runs the SAME check on the
    /// custom-DNS fields so the UI's validity feedback and the persisted-value
    /// gate can never drift from what the tunnel builder actually accepts.
    static func isUsableDnsAddress(_ s: String) -> Bool {
        var v4 = in_addr()
        var v6 = in6_addr()
        if s.withCString({ inet_pton(AF_INET, $0, &v4) }) == 1 {
            let host = UInt32(bigEndian: v4.s_addr)
            return host != 0 && (host >> 24) != 127   // reject 0.0.0.0 and 127/8
        }
        if s.withCString({ inet_pton(AF_INET6, $0, &v6) }) == 1 {
            let bytes = withUnsafeBytes(of: &v6) { Array($0) }
            if bytes.allSatisfy({ $0 == 0 }) { return false }                        // ::
            if bytes.dropLast().allSatisfy({ $0 == 0 }) && bytes.last == 1 { return false } // ::1
            return true
        }
        return false
    }

    /// Apply the user's WireGuard port override. "auto", "custom" (the picker's
    /// placeholder tag) and any out-of-range value keep the server-provided
    /// port — same contract as Android's `applyPortOverride`.
    private func effectivePort(_ serverPort: Int) -> Int {
        guard let pref = UserDefaults.standard.string(forKey: "wg_port"),
              let override = Int(pref),
              (1...65535).contains(override) else {
            return serverPort
        }
        return override
    }

    /// `host:port`, bracketing IPv6 literals. `Endpoint = fd00::1:51820` is
    /// ambiguous and WireGuard rejects it — it must be `[fd00::1]:51820`.
    private func endpointString(host: String, port: Int) -> String {
        if host.contains(":") && !host.hasPrefix("[") {
            return "[\(host)]:\(port)"
        }
        return "\(host):\(port)"
    }
}

enum VPNManagerError: Error, LocalizedError {
    case keychainUnavailable
    case managerUnavailable

    var errorDescription: String? {
        switch self {
        case .keychainUnavailable:
            return "Secure storage unavailable. Restart the app and try again."
        case .managerUnavailable:
            return "VPN configuration unavailable. Restart the app and try again."
        }
    }
}
