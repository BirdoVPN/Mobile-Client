import Foundation
import Security

/// Secure storage for tokens, credentials, and the WireGuard private key
/// using the iOS Keychain.
///
/// Security posture:
/// - `kSecUseDataProtectionKeychain = true` — opt in to the modern
///   data-protection keychain, isolating items from legacy file-based ones.
/// - `kSecAttrSynchronizable = false` — never sync any of these items to
///   iCloud Keychain, even if the user has it enabled.
/// - `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` — required so the
///   PacketTunnel extension can read shared items after first unlock when
///   the device is locked again (necessary for VPN auto-reconnect).
/// - Shared access group — grants the PacketTunnel extension read access to
///   the WG private key without ever serialising it into
///   `NETunnelProviderProtocol.providerConfiguration` (which is stored in
///   plaintext system preferences).
final class KeychainService: @unchecked Sendable {
    static let shared = KeychainService()

    /// Service identifier for app-only items (tokens, email).
    private let service = "app.birdo.vpn"
    /// Service identifier for items shared with the PacketTunnel extension.
    private let sharedService = "app.birdo.vpn.shared"
    /// Resolved keychain access group shared between the host app and the
    /// PacketTunnel extension. Looked up dynamically so we don't need to
    /// hard-code the team-id prefix.
    private lazy var sharedAccessGroup: String? = resolveSharedAccessGroup()

    // MARK: - Public Accessors (host app only)

    var accessToken: String? { read(key: "access_token") }
    var refreshToken: String? { read(key: "refresh_token") }
    var userEmail: String? { read(key: "user_email") }

    // MARK: - Save / Clear (host app only)

    func save(accessToken: String, refreshToken: String, email: String?) {
        write(key: "access_token", value: accessToken)
        write(key: "refresh_token", value: refreshToken)
        if let email {
            write(key: "user_email", value: email)
        } else {
            delete(key: "user_email")
        }
    }

    func clear() {
        delete(key: "access_token")
        delete(key: "refresh_token")
        delete(key: "user_email")
        // Also wipe any tunnel secrets that may have been left behind.
        clearAllSharedSecrets()
    }

    // MARK: - Shared Secrets (host app ↔ PacketTunnel extension)

    /// Store a secret readable by both the host app and the PacketTunnel
    /// extension. Used for the WireGuard private key & PSK so they never
    /// touch `providerConfiguration`. Returns `false` if the access group
    /// could not be resolved.
    @discardableResult
    func setSharedSecret(key: String, value: String) -> Bool {
        guard let group = sharedAccessGroup,
              let data = value.data(using: .utf8) else { return false }
        deleteShared(key: key)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: sharedService,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessGroup as String: group,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    func readSharedSecret(key: String) -> String? {
        guard let group = sharedAccessGroup else { return nil }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: sharedService,
            kSecAttrAccount as String: key,
            kSecAttrAccessGroup as String: group,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func deleteShared(key: String) {
        guard let group = sharedAccessGroup else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: sharedService,
            kSecAttrAccount as String: key,
            kSecAttrAccessGroup as String: group,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemDelete(query as CFDictionary)
    }

    private func clearAllSharedSecrets() {
        ["wg_private_key", "wg_preshared_key"].forEach { deleteShared(key: $0) }
    }

    // MARK: - App-Only Keychain Operations

    private func write(key: String, value: String) {
        guard let data = value.data(using: .utf8) else { return }
        delete(key: key)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    private func read(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func delete(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - Access-Group Resolution

    /// Probe the keychain to discover the actual fully-qualified access group
    /// the system applies. Returns the literal we should pass to subsequent
    /// queries, or `nil` when the entitlement is missing (in which case the
    /// shared API refuses to store secrets rather than silently leaking
    /// them to the host-only keychain).
    private func resolveSharedAccessGroup() -> String? {
        let probeAccount = "_birdo_probe"
        let candidate = "app.birdo.vpn"
        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: sharedService,
            kSecAttrAccount as String: probeAccount,
            kSecValueData as String: Data([0]),
            kSecAttrAccessGroup as String: candidate,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemDelete(addQuery as CFDictionary)
        let status = SecItemAdd(addQuery as CFDictionary, nil)
        if status == errSecSuccess {
            SecItemDelete(addQuery as CFDictionary)
            return candidate
        }
        return nil
    }
}
