import Foundation
import SwiftUI

/// Manages VPN and app settings with persistence and Android-parity
/// apply-on-change semantics (mobile #207, spec §0.6):
///
/// 1. **Kill switch → runtime flag push, NO tunnel rebuild.** Enabling is
///    immediate (persist + `VPNManager.applyKillSwitchFlag`). Disabling never
///    mutates on the gesture: `setKillSwitch(false)` only raises
///    `showKillSwitchDisableConfirm`, and the value changes solely via
///    `confirmDisableKillSwitch()`. Bind the Toggle to
///    `killSwitchToggleBinding` — it echoes "off" while the confirm is
///    pending so the switch neither bounces nor lies (T2 fix).
/// 2. **Tunnel-shape toggles** (quantum protection, local network sharing,
///    BirdoShield, custom DNS on/off) → 1200 ms debounced `onSettingsReapplyNeeded` so a
///    burst of toggles produces ONE reconnect "blip". The app root wires the
///    callback to `VpnViewModel.reapplySettings()` (no-op unless connected).
/// 3. **Text fields** (DNS addresses, MTU) → persist
///    immediately (valid values only) but only flag a pending reapply; the
///    VPN Settings screen calls `commitPendingReapply()` on dismiss so one
///    blip fires with the FINAL value, never per keystroke.
///
/// Removed vs the pre-rebuild model:
/// - `stealthModeEnabled` — iOS has no Xray transport; a dead security toggle
///   must not ship (platform-constraints spec §3.7). Stored key is cleaned up.
/// - `notificationsEnabled` — no notification code exists on iOS; ditto.
/// - `wireGuardPort` — the relays accept WireGuard on 51820 only, so the "53"
///   preset and a custom port never connected and "51820" was Automatic
///   (LIVE-PORT53). The port is not a setting; a saved one is retired at init
///   (`WireGuardPort.retireStoredChoice`) and never read when dialling.
@MainActor
final class SettingsViewModel: ObservableObject {
    // MARK: - Kill Switch (default ON)

    /// Persisted truth. Mutated ONLY through `setKillSwitch` /
    /// `confirmDisableKillSwitch` so the confirm-before-disable contract can't
    /// be bypassed by a stray binding.
    @Published private(set) var killSwitchEnabled: Bool
    /// Drives the "Disable kill switch?" confirmation dialog.
    @Published var showKillSwitchDisableConfirm = false

    // MARK: - Connection
    @Published var autoConnect: Bool {
        didSet { persist("auto_connect", autoConnect) }
    }

    // MARK: - Security
    @Published var biometricLockEnabled: Bool {
        didSet { persist("biometric_lock", biometricLockEnabled) }
    }

    // MARK: - VPN Protocol
    @Published var quantumProtectionEnabled: Bool {
        didSet {
            guard quantumProtectionEnabled != oldValue else { return }
            persist("quantum_protection", quantumProtectionEnabled)
            requestSettingsReapply()
        }
    }
    @Published var localNetworkSharing: Bool {
        didSet {
            guard localNetworkSharing != oldValue else { return }
            persist("local_network_sharing", localNetworkSharing)
            requestSettingsReapply()
        }
    }
    /// BirdoShield (D18): per-device DNS filtering (ads, trackers, malware
    /// domains blocked at the node's resolver). OFF by default — no
    /// `register(defaults:)` entry, so an absent key reads `false`. The flag
    /// only travels on the connect body (`APIClient` reads the same key at
    /// dial time), which is why it is a tunnel-shape toggle: a flip while
    /// connected takes the same debounced reconnect as Local Network Sharing.
    @Published var dnsFilteringEnabled: Bool {
        didSet {
            guard dnsFilteringEnabled != oldValue else { return }
            persist(ConnectWire.dnsFilteringDefaultsKey, dnsFilteringEnabled)
            requestSettingsReapply()
        }
    }

    /// BirdoShield FLEET GATE, from `GET /api/client-config`
    /// (`dnsFilteringAvailable` = the backend's `DNS_FILTERING_ENABLED`).
    ///
    /// Distinct from `dnsFilteringEnabled` above: that is what the user asked
    /// for, this is whether the server will honour it. `nil` means NOT KNOWN
    /// YET, which reads as AVAILABLE — see `BirdoShieldGate`.
    ///
    /// Deliberately NOT persisted: it is a property of the fleet, not of this
    /// install, and a stale `false` restored at launch would hide the feature
    /// before the fetch could correct it. Every cold start therefore begins at
    /// "available" and only an explicit server `false` moves it.
    @Published private(set) var dnsFilteringAvailable: Bool?

    /// The last decoded `/api/client-config`, for the per-plan Custom DNS flag
    /// (owner item 40). Nil until a fetch succeeds — read as AVAILABLE.
    @Published private(set) var clientConfig: ClientConfigResponse?

    /// Custom DNS on this plan? Every plan, unless the server's client config
    /// says otherwise for it (`CustomDnsGate`, owner item 40).
    func isCustomDnsAvailable(plan: String) -> Bool {
        CustomDnsGate.isAvailable(plan: plan, config: clientConfig)
    }

    /// Refresh the fleet gate and the Custom DNS flag. Called by Settings and
    /// the VPN Settings screen on appear.
    ///
    /// Failure is silent BY DESIGN: the value is left untouched, so an
    /// unreachable web app leaves the toggle usable instead of greying out a
    /// feature that works. Only a decoded, explicit boolean changes anything —
    /// an older web deploy omits the key, which is unknown, not off.
    func refreshClientConfig() async {
        // Owner item 41: no request before the privacy disclosure is accepted.
        // Settings is open to a user who chose "Not now"; the values simply
        // stay UNKNOWN for them, and unknown reads as available here.
        guard ConsentRecord.hasAcceptedCurrent(in: .standard) else { return }
        // Any failure — offline, 5xx, pin cancel, an undecodable body — keeps
        // the current value rather than becoming `false`.
        guard let config = try? await APIClient.shared.fetchClientConfig() else { return }
        clientConfig = config
        // A web deploy older than #465 omits the key. That is "the server did
        // not say", not "off", so it must not overwrite anything either.
        guard let available = config.dnsFilteringAvailable else { return }
        if dnsFilteringAvailable != available { dnsFilteringAvailable = available }
    }

    // MARK: - DNS
    @Published var customDnsEnabled: Bool {
        didSet {
            guard customDnsEnabled != oldValue else { return }
            persist("custom_dns", customDnsEnabled)
            requestSettingsReapply()
        }
    }
    /// The property holds whatever the user typed (so the field never fights
    /// them); UserDefaults only ever holds the last valid-or-empty value —
    /// Android parity: invalid text is NOT persisted, and VPNManager
    /// re-validates at build time regardless.
    @Published var customDnsPrimary: String {
        didSet {
            guard customDnsPrimary != oldValue else { return }
            persistDns(customDnsPrimary, key: "custom_dns_primary")
        }
    }
    @Published var customDnsSecondary: String {
        didSet {
            guard customDnsSecondary != oldValue else { return }
            persistDns(customDnsSecondary, key: "custom_dns_secondary")
        }
    }

    // MARK: - WireGuard

    @Published var wireGuardMtu: Int32 {
        didSet {
            guard wireGuardMtu != oldValue else { return }
            // Persist clamped (0 = the "Auto MTU" sentinel); the property may
            // briefly hold mid-typing values the field is still editing.
            let clamped: Int32 = wireGuardMtu == 0 ? 0 : min(max(wireGuardMtu, 1280), 1500)
            UserDefaults.standard.set(Int(clamped), forKey: "wg_mtu")
            pendingReapplyOnExit = true
        }
    }

    // MARK: - Hooks

    /// Fired (debounced / on screen exit) when a persisted setting needs a
    /// tunnel rebuild to take effect. Wire to `VpnViewModel.reapplySettings()`
    /// at the app root. Deliberately a callback: SettingsViewModel must not
    /// own connection state.
    var onSettingsReapplyNeeded: (() -> Void)?

    // MARK: - Private

    private let vpnManager: VPNManager
    /// `nonisolated(unsafe)` so the nonisolated `deinit` can cancel it (same
    /// pattern as VpnViewModel's timers). All other access is main-actor.
    nonisolated(unsafe) private var reapplyDebounceTask: Task<Void, Never>?
    private var pendingReapplyOnExit = false
    /// Android's debounce window: a burst of toggle flips = one blip.
    private static let reapplyDebounceMs = 1200

    // MARK: - Init

    init(vpnManager: VPNManager = .shared) {
        self.vpnManager = vpnManager
        let d = UserDefaults.standard

        // One-time migration: the kill-switch and quantum-protection toggles were
        // previously cosmetic (they persisted a value but nothing read it), so any
        // stored value is unreliable. Clear those two keys once so every user
        // starts from the real, security-forward defaults registered below. An
        // explicit value the user sets AFTER this migration is honoured normally.
        if d.bool(forKey: "settings_migrated_v2") == false {
            d.removeObject(forKey: "kill_switch")
            d.removeObject(forKey: "quantum_protection")
            d.set(true, forKey: "settings_migrated_v2")
        }

        // Dead-setting cleanup (idempotent): stealth has no iOS transport and
        // the notifications toggle had no notification code behind it — the
        // settings were removed from this model, so drop their stored values
        // rather than leave stale "enabled" flags a future reader could trust.
        d.removeObject(forKey: "stealth_mode")
        d.removeObject(forKey: "notifications")

        // Security-forward defaults: an ABSENT key reads `true`. register() does
        // NOT persist, so an explicitly stored `false` (user turned it off) still
        // wins over these. Must run before the reads below.
        d.register(defaults: [
            "kill_switch": true,
            "quantum_protection": true,
        ])

        killSwitchEnabled = d.bool(forKey: "kill_switch")
        autoConnect = d.bool(forKey: "auto_connect")
        biometricLockEnabled = d.bool(forKey: "biometric_lock")
        quantumProtectionEnabled = d.bool(forKey: "quantum_protection")
        localNetworkSharing = d.bool(forKey: "local_network_sharing")
        dnsFilteringEnabled = d.bool(forKey: ConnectWire.dnsFilteringDefaultsKey)
        customDnsEnabled = d.bool(forKey: "custom_dns")
        customDnsPrimary = d.string(forKey: "custom_dns_primary") ?? ""
        customDnsSecondary = d.string(forKey: "custom_dns_secondary") ?? ""

        // LIVE-PORT53: the WireGuard port is not a setting any more. A "53",
        // custom port or "51820" preset an older build saved becomes "auto",
        // once (the next launch finds nothing to do). VPNManager no longer
        // reads the key at all; this keeps the stored state honest. MTU and
        // every other setting are left as they are.
        WireGuardPort.retireStoredChoice(in: d)

        // `integer(forKey:)` is an `Int` read straight from a user-writable
        // plist: an out-of-Int32-range value would TRAP the conversion and crash
        // on launch. 0 is the "Auto MTU" sentinel the settings UI keys off, so
        // preserve it exactly; clamp anything else into the WireGuard-legal
        // range rather than surfacing a value the tunnel would reject.
        let storedMtu = d.integer(forKey: "wg_mtu")
        wireGuardMtu = storedMtu == 0 ? 0 : Int32(min(max(storedMtu, 1280), 1500))
    }

    deinit {
        reapplyDebounceTask?.cancel()
    }

    // MARK: - Kill Switch API (T2)

    /// Entry point for the UI gesture. Enabling commits immediately; the
    /// "off" gesture only raises the confirmation — state changes there via
    /// `confirmDisableKillSwitch()` or reverts via `cancelDisableKillSwitch()`.
    func setKillSwitch(_ enabled: Bool) {
        if enabled {
            showKillSwitchDisableConfirm = false
            commitKillSwitch(true)
        } else if killSwitchEnabled {
            showKillSwitchDisableConfirm = true
        }
    }

    func confirmDisableKillSwitch() {
        showKillSwitchDisableConfirm = false
        commitKillSwitch(false)
    }

    func cancelDisableKillSwitch() {
        showKillSwitchDisableConfirm = false
    }

    /// Bind the Settings kill-switch Toggle to THIS, not to
    /// `killSwitchEnabled`: while the disable confirmation is pending the
    /// binding reads `false` (the switch sits visually off under the alert)
    /// but the persisted value is untouched — "Cancel" animates it back on
    /// with no bounce, "Turn off anyway" commits without a snap.
    var killSwitchToggleBinding: Binding<Bool> {
        Binding(
            get: { [weak self] in
                guard let self else { return true }
                return self.showKillSwitchDisableConfirm ? false : self.killSwitchEnabled
            },
            set: { [weak self] newValue in
                self?.setKillSwitch(newValue)
            }
        )
    }

    private func commitKillSwitch(_ enabled: Bool) {
        guard enabled != killSwitchEnabled else { return }
        killSwitchEnabled = enabled
        persist("kill_switch", enabled)
        // Apply-on-change path 1: flag push into the live VPN configuration —
        // never a tunnel rebuild (the on-demand drop behaviour updates
        // immediately; protocol flags persist for the next tunnel start).
        vpnManager.applyKillSwitchFlag(enabled)
    }

    /// Same predicate `VPNManager` applies when it builds the tunnel config,
    /// exposed for the DNS fields' inline error state — UI feedback and the
    /// build-time gate can never drift.
    static func isValidDnsAddress(_ text: String) -> Bool {
        TunnelDns.isUsableDnsAddress(text)
    }

    // MARK: - Reapply Plumbing (§0.6 paths 2 & 3)

    /// The VPN Settings screen MUST call this on dismiss (`.onDisappear`): it
    /// fires the single pending blip for any field-style edits (DNS, MTU)
    /// made while the screen was open. No-op when nothing changed.
    func commitPendingReapply() {
        guard pendingReapplyOnExit else { return }
        reapplyDebounceTask?.cancel()
        fireReapply()
    }

    private func requestSettingsReapply() {
        reapplyDebounceTask?.cancel()
        reapplyDebounceTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(Self.reapplyDebounceMs))
            guard !Task.isCancelled else { return }
            self?.fireReapply()
        }
    }

    private func fireReapply() {
        // One blip covers everything: a live rebuild picks up field edits too,
        // so a pending on-exit reapply is satisfied by it.
        pendingReapplyOnExit = false
        onSettingsReapplyNeeded?()
    }

    // MARK: - Helpers

    private func persist(_ key: String, _ value: Bool) {
        UserDefaults.standard.set(value, forKey: key)
    }

    private func persistDns(_ text: String, key: String) {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        // Android parity: invalid text is NEVER persisted — the tunnel only
        // ever sees the last valid-or-empty value.
        guard trimmed.isEmpty || Self.isValidDnsAddress(trimmed) else { return }
        UserDefaults.standard.set(trimmed, forKey: key)
        pendingReapplyOnExit = true
    }
}
