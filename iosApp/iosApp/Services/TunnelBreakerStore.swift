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
    private static let heartbeatHandOffAccount = "heartbeat_handoff"
    private static let heartbeatLastOkAccount = "heartbeat_last_ok"

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

    // MARK: - Heartbeat hand-off (MR-2524)

    /// What the extension's heartbeat decided and left to the host (a rebuild,
    /// a move), or stopped and wants said (an ending): see `HeartbeatHandOff`.
    /// The latest decision wins; the host acts on it for its own session only.
    ///
    /// Absent or unreadable reads as nil: nothing handed over, which is how the
    /// host behaved before the hand-off existed.
    var heartbeatHandOff: HeartbeatHandOff? {
        guard let data = read(account: Self.heartbeatHandOffAccount) else { return nil }
        return try? JSONDecoder().decode(HeartbeatHandOff.self, from: data)
    }

    /// Review F4: written in ONE step (`replace`), so a reader never finds the
    /// slot empty between a delete and an add, and labelled with the decision
    /// so the host's clear can name exactly the one it dealt with.
    func setHeartbeatHandOff(_ handOff: HeartbeatHandOff) {
        lock.lock()
        defer { lock.unlock() }
        if let data = try? JSONEncoder().encode(handOff) {
            replace(account: Self.heartbeatHandOffAccount, data: data, label: Self.label(of: handOff))
        }
    }

    /// Delete `handOff` once the host has dealt with it, unless the extension
    /// has written a newer one since it was read: that one is still owed. The
    /// keychain compares the label itself (review F4), so a newer decision the
    /// extension writes between this read and this delete is never deleted,
    /// even though the two processes share no lock.
    func clearHeartbeatHandOff(_ handOff: HeartbeatHandOff) {
        lock.lock()
        defer { lock.unlock() }
        delete(account: Self.heartbeatHandOffAccount, label: Self.label(of: handOff))
    }

    private static func label(of handOff: HeartbeatHandOff) -> String {
        "\(handOff.verdict.rawValue)@\(handOff.decidedAt)"
    }

    /// The last good beat of `keyId` (review F2): written by the extension at
    /// most every `TunnelHeartbeatState.shareEvery`, read by every extension
    /// instance and by a host started cold, so neither restarts the reap clock.
    /// A record about another key reads as nil.
    func heartbeatLastOk(for keyId: String) -> Date? {
        guard let data = read(account: Self.heartbeatLastOkAccount),
              let record = try? JSONDecoder().decode(HeartbeatLastOk.self, from: data),
              record.keyId == keyId else { return nil }
        return Date(timeIntervalSince1970: record.at)
    }

    func setHeartbeatLastOk(_ at: Date, keyId: String) {
        lock.lock()
        defer { lock.unlock() }
        if let data = try? JSONEncoder().encode(HeartbeatLastOk(keyId: keyId, at: at.timeIntervalSince1970)) {
            replace(account: Self.heartbeatLastOkAccount, data: data, label: "last_ok")
        }
    }

    /// Review F3: both heartbeat records name this session's key (D-13 scrubs
    /// it from the profile at sign-out too), so sign-out and account deletion
    /// remove them (`VpnViewModel.resetForLogout`).
    func clearHeartbeatRecords() {
        lock.lock()
        defer { lock.unlock() }
        delete(account: Self.heartbeatHandOffAccount)
        delete(account: Self.heartbeatLastOkAccount)
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

    /// One-step write: update the item in place, and add it only when there is
    /// none yet. Unlike `write` (delete, then add), no reader in either process
    /// can find the slot empty, and no delete of the old value can land on the
    /// new one.
    private func replace(account: String, data: Data, label: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: Self.accessGroup,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        let changes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrLabel as String: label,
        ]
        guard SecItemUpdate(query as CFDictionary, changes as CFDictionary) == errSecItemNotFound else { return }
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrLabel as String] = label
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        add[kSecAttrSynchronizable as String] = kCFBooleanFalse as Any
        SecItemAdd(add as CFDictionary, nil)
    }

    /// Delete the item only while it still carries `label`: the keychain does
    /// the comparison, across both processes.
    private func delete(account: String, label: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: Self.accessGroup,
            kSecAttrLabel as String: label,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
