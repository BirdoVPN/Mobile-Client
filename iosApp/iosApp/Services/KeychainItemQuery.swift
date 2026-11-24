import Foundation
import Security

/// MR-2580: the keychain queries behind every host-only item (`KeychainService`
/// is the live `KeychainGroupStore`) and the two ways an item's value is
/// written. The Security calls are behind `KeychainCalls`, so
/// TokenPairWriteTests check each query and each step without a keychain (the
/// test bundle has no host app, so no keychain entitlement).
enum KeychainItemQuery {
    /// One item, by name. `group == nil` names no access group: an add lands
    /// in the default (first) group, and an update, a lookup or a delete covers
    /// every group the process is entitled to.
    static func item(service: String, account: String, group: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        if let group { query[kSecAttrAccessGroup as String] = group }
        return query
    }

    /// A new item, with the posture in KeychainService's header, which every
    /// build has added these items with: after first unlock, this device only,
    /// never synced to iCloud Keychain.
    static func newItem(_ data: Data, service: String, account: String, group: String?) -> [String: Any] {
        var query = item(service: service, account: account, group: group)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        query[kSecAttrSynchronizable as String] = kCFBooleanFalse as Any
        return query
    }

    /// What replacing an item's value changes: the value, and nothing else. Its
    /// accessibility, access group and synchronizable flag stay the ones it was
    /// added with (`newItem`).
    static func newValue(_ data: Data) -> [String: Any] {
        [kSecValueData as String: data]
    }

    /// Delete, then add. A failed add leaves NO item, not the old one
    /// (`KeychainGroupStore.store`).
    static func store(_ data: Data, service: String, account: String, group: String?,
                      calls: any KeychainCalls = SystemKeychainCalls()) -> Bool {
        remove(service: service, account: account, group: group, calls: calls)
        return calls.add(newItem(data, service: service, account: account, group: group)) == errSecSuccess
    }

    /// One step (`KeychainGroupStore.replace`). Spec-first (MR-2580): still
    /// today's delete-then-add, so TokenPairWriteTests can show it failing.
    static func replace(_ data: Data, service: String, account: String, group: String?,
                        calls: any KeychainCalls = SystemKeychainCalls()) -> Bool {
        store(data, service: service, account: account, group: group, calls: calls)
    }

    static func remove(service: String, account: String, group: String?,
                       calls: any KeychainCalls = SystemKeychainCalls()) {
        _ = calls.delete(item(service: service, account: account, group: group))
    }
}

/// The Security calls `KeychainItemQuery` makes.
protocol KeychainCalls {
    func add(_ attributes: [String: Any]) -> OSStatus
    func update(_ query: [String: Any], _ changes: [String: Any]) -> OSStatus
    func delete(_ query: [String: Any]) -> OSStatus
}

/// The live keychain.
struct SystemKeychainCalls: KeychainCalls {
    func add(_ attributes: [String: Any]) -> OSStatus {
        SecItemAdd(attributes as CFDictionary, nil)
    }

    func update(_ query: [String: Any], _ changes: [String: Any]) -> OSStatus {
        SecItemUpdate(query as CFDictionary, changes as CFDictionary)
    }

    func delete(_ query: [String: Any]) -> OSStatus {
        SecItemDelete(query as CFDictionary)
    }
}
