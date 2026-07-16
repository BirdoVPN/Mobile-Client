import Foundation
import SwiftUI

/// Manages VPN and app settings with persistence.
@MainActor
final class SettingsViewModel: ObservableObject {
    // MARK: - Connection
    @Published var killSwitchEnabled: Bool {
        didSet { persist("kill_switch", killSwitchEnabled) }
    }
    @Published var autoConnect: Bool {
        didSet { persist("auto_connect", autoConnect) }
    }
    @Published var notificationsEnabled: Bool {
        didSet { persist("notifications", notificationsEnabled) }
    }

    // MARK: - Security
    @Published var biometricLockEnabled: Bool {
        didSet { persist("biometric_lock", biometricLockEnabled) }
    }

    // MARK: - VPN Protocol
    @Published var stealthModeEnabled: Bool {
        didSet { persist("stealth_mode", stealthModeEnabled) }
    }
    @Published var quantumProtectionEnabled: Bool {
        didSet { persist("quantum_protection", quantumProtectionEnabled) }
    }
    @Published var localNetworkSharing: Bool {
        didSet { persist("local_network_sharing", localNetworkSharing) }
    }

    // MARK: - DNS
    @Published var customDnsEnabled: Bool {
        didSet { persist("custom_dns", customDnsEnabled) }
    }
    @Published var customDnsPrimary: String {
        didSet { UserDefaults.standard.set(customDnsPrimary, forKey: "custom_dns_primary") }
    }
    @Published var customDnsSecondary: String {
        didSet { UserDefaults.standard.set(customDnsSecondary, forKey: "custom_dns_secondary") }
    }

    // MARK: - WireGuard
    @Published var wireGuardPort: String {
        didSet { UserDefaults.standard.set(wireGuardPort, forKey: "wg_port") }
    }
    @Published var wireGuardMtu: Int32 {
        didSet { UserDefaults.standard.set(wireGuardMtu, forKey: "wg_mtu") }
    }

    // MARK: - Init

    init() {
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

        // Security-forward defaults: an ABSENT key reads `true`. register() does
        // NOT persist, so an explicitly stored `false` (user turned it off) still
        // wins over these. Must run before the reads below.
        d.register(defaults: [
            "kill_switch": true,
            "quantum_protection": true,
        ])

        killSwitchEnabled = d.bool(forKey: "kill_switch")
        autoConnect = d.bool(forKey: "auto_connect")
        notificationsEnabled = d.bool(forKey: "notifications")
        biometricLockEnabled = d.bool(forKey: "biometric_lock")
        stealthModeEnabled = d.bool(forKey: "stealth_mode")
        quantumProtectionEnabled = d.bool(forKey: "quantum_protection")
        localNetworkSharing = d.bool(forKey: "local_network_sharing")
        customDnsEnabled = d.bool(forKey: "custom_dns")
        customDnsPrimary = d.string(forKey: "custom_dns_primary") ?? ""
        customDnsSecondary = d.string(forKey: "custom_dns_secondary") ?? ""
        wireGuardPort = d.string(forKey: "wg_port") ?? "auto"
        wireGuardMtu = Int32(d.integer(forKey: "wg_mtu"))
    }

    // MARK: - Helpers

    private func persist(_ key: String, _ value: Bool) {
        UserDefaults.standard.set(value, forKey: key)
    }
}
