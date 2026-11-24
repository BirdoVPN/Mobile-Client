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

    /// One step (`KeychainGroupStore.replace`): the value is updated where the
    /// item exists, and the item is added (`newItem`) where it does not. It is
    /// never deleted first, so a failed write leaves it as it was and a kill
    /// cannot catch it gone. False when it was not written.
    static func replace(_ data: Data, service: String, account: String, group: String?,
                        calls: any KeychainCalls = SystemKeychainCalls()) -> Bool {
        let query = item(service: service, account: account, group: group)
        switch calls.update(query, newValue(data)) {
        case errSecSuccess:
            return true
        case errSecItemNotFound:
            switch calls.add(newItem(data, service: service, account: account, group: group)) {
            case errSecSuccess:
                return true
            case errSecDuplicateItem:
                // Another writer added it between the two calls: replace theirs.
                return calls.update(query, newValue(data)) == errSecSuccess
            default:
                return false
            }
        default:
            // Locked, not entitled, an I/O error: nothing was changed.
            return false
        }
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
