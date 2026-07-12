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
        // Kill switch and quantum protection default ON (lockdown-by-default,
        // matching Android). `bool(forKey:)` alone returns false for an
        // absent key, which would silently disarm both on a fresh install.
        killSwitchEnabled = d.object(forKey: "kill_switch") == nil
            ? true : d.bool(forKey: "kill_switch")
        autoConnect = d.bool(forKey: "auto_connect")
        biometricLockEnabled = d.bool(forKey: "biometric_lock")
        stealthModeEnabled = d.bool(forKey: "stealth_mode")
        quantumProtectionEnabled = d.object(forKey: "quantum_protection") == nil
            ? true : d.bool(forKey: "quantum_protection")
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
