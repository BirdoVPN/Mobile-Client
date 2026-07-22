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
///    custom DNS on/off) → 1200 ms debounced `onSettingsReapplyNeeded` so a
///    burst of toggles produces ONE reconnect "blip". The app root wires the
///    callback to `VpnViewModel.reapplySettings()` (no-op unless connected).
/// 3. **Text fields** (DNS addresses, WireGuard port, MTU) → persist
///    immediately (valid values only) but only flag a pending reapply; the
///    VPN Settings screen calls `commitPendingReapply()` on dismiss so one
///    blip fires with the FINAL value, never per keystroke.
///
/// Removed vs the pre-rebuild model:
/// - `stealthModeEnabled` — iOS has no Xray transport; a dead security toggle
///   must not ship (platform-constraints spec §3.7). Stored key is cleaned up.
/// - `notificationsEnabled` — no notification code exists on iOS; ditto.
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

    /// Persisted port preference: "auto" or a numeric string 1–65535. The
    /// legacy UI stored the literal "custom" (which VPNManager treats as
    /// auto); init normalises that away. Mutate via `setWireGuardPortPreset`
    /// / `applyCustomWireGuardPort` only.
    @Published private(set) var wireGuardPort: String
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
        customDnsEnabled = d.bool(forKey: "custom_dns")
        customDnsPrimary = d.string(forKey: "custom_dns_primary") ?? ""
        customDnsSecondary = d.string(forKey: "custom_dns_secondary") ?? ""

        // T3 normalisation: the old picker persisted the literal "custom",
        // which `VPNManager.effectivePort` (correctly) treats as auto — so a
        // "custom" port never took effect. Anything that isn't "auto" or a
        // valid port number collapses to "auto"; the Custom radio itself is
        // local UI state on the screen, never derived from this value.
        let storedPort = d.string(forKey: "wg_port") ?? "auto"
        if storedPort == "auto" || Self.isValidPortText(storedPort) {
            wireGuardPort = storedPort
        } else {
            wireGuardPort = "auto"
            d.set("auto", forKey: "wg_port")
        }

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

    // MARK: - WireGuard Port API (T3)

    /// Persist a preset ("auto" | "51820" | "53") immediately. The reapply
    /// fires on screen exit like the other field-style settings.
    func setWireGuardPortPreset(_ value: String) {
        guard value == "auto" || Self.isValidPortText(value) else { return }
        guard value != wireGuardPort else { return }
        wireGuardPort = value
        UserDefaults.standard.set(value, forKey: "wg_port")
        pendingReapplyOnExit = true
    }

    /// T3 fix: the custom port is persisted HERE the moment it parses to
    /// 1–65535 — the old screen kept the text in a local @State and never
    /// wrote it back, so "Custom" silently behaved as Auto forever. Returns
    /// whether the text is currently a valid port (drives the field's error
    /// state); invalid/partial text persists nothing.
    @discardableResult
    func applyCustomWireGuardPort(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard Self.isValidPortText(trimmed) else { return false }
        if trimmed != wireGuardPort {
            wireGuardPort = trimmed
            UserDefaults.standard.set(trimmed, forKey: "wg_port")
            pendingReapplyOnExit = true
        }
        return true
    }

    static func isValidPortText(_ text: String) -> Bool {
        guard let port = Int(text) else { return false }
        return (1...65535).contains(port)
    }

    /// Same predicate `VPNManager` applies when it builds the tunnel config,
    /// exposed for the DNS fields' inline error state — UI feedback and the
    /// build-time gate can never drift.
    static func isValidDnsAddress(_ text: String) -> Bool {
        VPNManager.isUsableDnsAddress(text)
    }

    // MARK: - Reapply Plumbing (§0.6 paths 2 & 3)

    /// The VPN Settings screen MUST call this on dismiss (`.onDisappear`): it
    /// fires the single pending blip for any field-style edits (DNS, port,
    /// MTU) made while the screen was open. No-op when nothing changed.
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
