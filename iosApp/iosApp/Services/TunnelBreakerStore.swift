import Foundation
import Security

/// Cross-process persistence for `TunnelCircuitBreaker` (P1-ios-redial-loop-blackhole).
///
/// The two processes that must agree are the HOST APP and the PACKET TUNNEL
/// EXTENSION, and during the failure this breaker exists for, the host app is
/// usually suspended — the extension is the only thing running. So the failure
/// counter cannot live in the view model.
///
/// The channel is the SHARED KEYCHAIN, reused deliberately: it is already
/// entitled on both sides and already carries `last_tunnel_error` between them
/// (see `PacketTunnelProvider.recordFailure`). An App Group would be a nicer
/// store, but it means a new entitlement and regenerating BOTH provisioning
/// profiles — a materially larger change than the fix.
///
/// This file compiles into the app AND the extension (declared for both targets
/// in project.yml), so the group/service literals are stated once here instead
/// of a third time per side.
///
/// Accessibility is `AfterFirstUnlockThisDeviceOnly`, matching every other
/// shared item: the extension writes these while the device is locked.
final class TunnelBreakerStore: @unchecked Sendable {
    static let shared = TunnelBreakerStore()

    /// MUST match `VPNManager.sharedKeychainAccessGroup` and
    /// `PacketTunnelProvider.sharedAccessGroup`. On a physical device the
    /// team-prefixed form is mandatory — the bare group fails with
    /// errSecMissingEntitlement (-34018).
    private static let accessGroup = "KPUFGR98A5.app.birdo.vpn"
    private static let service = "app.birdo.vpn.shared"
    private static let recordAccount = "breaker_record"
    private static let onDemandAccount = "on_demand_armed"

    /// Serialises the read-modify-write in `recordFailure`. The extension calls
    /// it from the heartbeat Task; the host reads from the main actor.
    private let lock = NSLock()

    private init() {}

    // MARK: - Breaker record

    /// The persisted record, or nil.
    ///
    /// FAIL OPEN: any keychain miss, any decode failure, reads as nil — which
    /// `TunnelCircuitBreaker.isTripped` treats as "not tripped". A corrupted
    /// record must never be able to hold the VPN down.
    var record: TunnelBreakerRecord? {
        guard let data = read(account: Self.recordAccount) else { return nil }
        return try? JSONDecoder().decode(TunnelBreakerRecord.self, from: data)
    }

    /// Fold one failure in and persist the result. Returns the new record so
    /// the caller can act on it without a second read.
    @discardableResult
    func recordFailure(_ kind: TunnelFailureKind, nodeId: String, now: Date = Date()) -> TunnelBreakerRecord {
        lock.lock()
        defer { lock.unlock() }
        let previous = record
        let updated = TunnelCircuitBreaker.recordFailure(kind, nodeId: nodeId, now: now, into: previous)
        if let data = try? JSONEncoder().encode(updated) {
            write(account: Self.recordAccount, data: data)
        }
        return updated
    }

    /// Clear the breaker. THE reset primitive — see the call sites in
    /// `VpnViewModel` for the full list of reset conditions.
    func clear() {
        lock.lock()
        defer { lock.unlock() }
        delete(account: Self.recordAccount)
    }

    // MARK: - On-demand arming mirror

    /// Whether the host app currently has an on-demand rule armed.
    ///
    /// The extension cannot read `NETunnelProviderManager.isOnDemandEnabled`
    /// (host-app API), yet that flag decides whether an extension-side teardown
    /// is a RECOVERY or a RE-DIAL. So the host mirrors it here on every
    /// arm/disarm and the extension reads the mirror.
    ///
    /// FAIL OPEN: absent/unreadable reads as `false`, i.e. "cancelling is safe",
    /// which preserves the pre-existing behaviour rather than inventing a new
    /// hold state on a keychain glitch.
    var onDemandArmed: Bool {
        guard let data = read(account: Self.onDemandAccount),
              let value = String(data: data, encoding: .utf8) else { return false }
        return value == "1"
    }

    func setOnDemandArmed(_ armed: Bool) {
        lock.lock()
        defer { lock.unlock() }
        write(account: Self.onDemandAccount, data: Data((armed ? "1" : "0").utf8))
    }

    // MARK: - Keychain primitives

    private func read(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: Self.accessGroup,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    private func write(account: String, data: Data) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: Self.accessGroup,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        add[kSecAttrSynchronizable as String] = kCFBooleanFalse as Any
        SecItemAdd(add as CFDictionary, nil)
    }

    private func delete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: Self.accessGroup,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
