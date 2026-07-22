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
    /// Fully-qualified access group the entitlement actually grants
    /// (`$(AppIdentifierPrefix)app.birdo.vpn`, team pinned in project.yml).
    /// Must stay in lockstep with `PacketTunnelProvider.sharedAccessGroup`.
    private static let pinnedSharedAccessGroup = "KPUFGR98A5.app.birdo.vpn"
    /// Resolved keychain access group shared between the host app and the
    /// PacketTunnel extension. Probed once so a signing change (new team /
    /// enterprise resign) still resolves at runtime.
    private lazy var sharedAccessGroup: String? = resolveSharedAccessGroup()

    // MARK: - Public Accessors (host app only)

    var accessToken: String? { read(key: "access_token") }
    var refreshToken: String? { read(key: "refresh_token") }
    var userEmail: String? { read(key: "user_email") }
    /// SSOT device identity ("ios_" + hash, minted by APIClient). Deliberately
    /// survives `clear()`: it keys the server-side trusted-device 2FA skip and
    /// connection-slot reclamation, so it must be stable across logins AND
    /// reinstalls (keychain items outlive the app bundle).
    var deviceId: String? { read(key: "device_id") }
    /// The 24-digit anonymous account ID — the SOLE recovery credential for an
    /// anonymous account. Deliberately survives `clear()` (logout) so a user
    /// who never wrote it down can still sign back in; wiped only by
    /// `clearAnonymousId()` (account deletion / switching to an email account).
    var anonymousId: String? { read(key: "anonymous_id") }

    // MARK: - Save / Clear (host app only)

    /// Persist the auth credentials atomically. Returns `false` if either
    /// required item (access or refresh token) failed to write, so callers can
    /// surface a real error instead of hitting a silent auth failure on the
    /// next launch.
    ///
    /// A HALF-written pair is the dangerous case (finding #60): if the access
    /// token wrote but the refresh token failed, a later cold start would route
    /// to Home on a session whose refresh token is stale/missing — a token
    /// rotation the client can never complete (finding #433). So on a refresh
    /// write failure we roll the access token back to its PREVIOUS value (or
    /// delete it if there was none) and report failure — the keychain is left
    /// in the same consistent state it had before the call.
    @discardableResult
    func save(accessToken: String, refreshToken: String, email: String?) -> Bool {
        let priorAccess = read(key: "access_token")
        guard write(key: "access_token", value: accessToken) else { return false }
        guard write(key: "refresh_token", value: refreshToken) else {
            // Refresh write failed — undo the access write so no half-session
            // survives. Restore the prior access token if there was one.
            if let priorAccess {
                write(key: "access_token", value: priorAccess)
            } else {
                delete(key: "access_token")
            }
            return false
        }
        if let email {
            write(key: "user_email", value: email)
        } else {
            delete(key: "user_email")
        }
        return true
    }

    @discardableResult
    func saveDeviceId(_ id: String) -> Bool {
        write(key: "device_id", value: id)
    }

    @discardableResult
    func saveAnonymousId(_ id: String) -> Bool {
        write(key: "anonymous_id", value: id)
    }

    func clearAnonymousId() {
        delete(key: "anonymous_id")
    }

    /// Wipe the session. `device_id` and `anonymous_id` intentionally survive —
    /// see their accessor docs.
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

    /// Write a host-only keychain item. Returns `false` if the value could
    /// not be encoded or `SecItemAdd` failed (disk full, permissions, etc.),
    /// matching the status-checking semantics of `setSharedSecret`.
    @discardableResult
    private func write(key: String, value: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }
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
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
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

    /// AUDIT-C1: the entitlement grants `$(AppIdentifierPrefix)app.birdo.vpn`,
    /// and on a REAL DEVICE an explicit `kSecAttrAccessGroup` must name the
    /// fully-qualified, team-prefixed group — the bare `"app.birdo.vpn"`
    /// literal fails with `errSecMissingEntitlement (-34018)`, which made
    /// every VPN connect throw `keychainUnavailable` on hardware. (The
    /// simulator does not enforce access groups, which is why the old
    /// unprefixed probe "verified" there.)
    ///
    /// Strategy: try the pinned team-prefixed literal first (team is fixed in
    /// project.yml and must match `PacketTunnelProvider.sharedAccessGroup`),
    /// then fall back to runtime discovery — write a probe item with NO
    /// explicit group (the system assigns the first entitlement group) and
    /// read the assigned `agrp` attribute back.
    private func resolveSharedAccessGroup() -> String? {
        if probeAccessGroup(Self.pinnedSharedAccessGroup) {
            return Self.pinnedSharedAccessGroup
        }
        return discoverDefaultAccessGroup()
    }

    /// Returns true when the given access group is writable from this process.
    private func probeAccessGroup(_ candidate: String) -> Bool {
        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: sharedService,
            kSecAttrAccount as String: "_birdo_probe",
            kSecValueData as String: Data([0]),
            kSecAttrAccessGroup as String: candidate,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemDelete(addQuery as CFDictionary)
        let status = SecItemAdd(addQuery as CFDictionary, nil)
        if status == errSecSuccess {
            SecItemDelete(addQuery as CFDictionary)
            return true
        }
        return false
    }

    /// Fallback discovery for a changed team prefix: add a probe with no
    /// explicit access group, ask for its attributes back, and read `agrp`.
    /// Only accept a group that ends in our declared suffix so we can never
    /// silently adopt some unrelated group.
    private func discoverDefaultAccessGroup() -> String? {
        let probeAccount = "_birdo_probe_discovery"
        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: sharedService,
            kSecAttrAccount as String: probeAccount,
            kSecValueData as String: Data([0]),
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
            kSecReturnAttributes as String: true,
        ]
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: sharedService,
            kSecAttrAccount as String: probeAccount,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        SecItemDelete(deleteQuery as CFDictionary)
        var result: AnyObject?
        let status = SecItemAdd(addQuery as CFDictionary, &result)
        SecItemDelete(deleteQuery as CFDictionary)
        guard status == errSecSuccess,
              let attrs = result as? [String: Any],
              let group = attrs[kSecAttrAccessGroup as String] as? String,
              group.hasSuffix("app.birdo.vpn") else {
            return nil
        }
        return group
    }
}
