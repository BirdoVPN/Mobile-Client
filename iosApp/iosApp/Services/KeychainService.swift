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
/// - Host-only access group (MR-1729) — everything the app keeps to itself
///   (`HostKeychainItem`: tokens, email, device_id, anonymous_id, sign-in
///   method, the BirdoPQ keypair) is read and written in
///   `<team>.app.birdo.vpn.host`, which the PacketTunnel entitlements do not
///   list. An older build's copies are moved there on launch without ever
///   losing one (`migrateHostOnlyItems`, `HostOnlyKeychain`).
final class KeychainService: @unchecked Sendable {
    static let shared = KeychainService()

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

    /// MR-1729: where the host-only items live (`hostPlacement`). Kept once
    /// resolved; guarded by `placementLock`, because BirdoPQManager reads from
    /// its own queue.
    private let placementLock = NSLock()
    private var resolvedPlacement: HostKeychainPlacement?

    // MARK: - Public Accessors (host app only)

    var accessToken: String? { read(.accessToken) }
    var refreshToken: String? { read(.refreshToken) }
    var userEmail: String? { read(.userEmail) }
    /// SSOT device identity ("ios_" + a random v4 UUID, minted by APIClient).
    /// PER INSTALL (MR-1726, Android parity): it survives `clear()`, so
    /// signing out and back in keeps it — it keys the server-side
    /// trusted-device 2FA skip and connection-slot reclamation — but it does
    /// NOT survive a reinstall. Keychain items outlive the app bundle, so
    /// `InstallIdentity.checkOnLaunch` replaces a value a previous install left
    /// behind on the first launch of a fresh install.
    ///
    /// It was a hash of the IDFV until that was found to be recomputable by
    /// anyone holding the IDFV, and impossible to rotate away from. Erasure now
    /// rotates it (`APIClient.resetDeviceIdentity`); a value already stored by
    /// an older build of THIS install is read back unchanged so nobody burns a
    /// device slot on upgrade.
    var deviceId: String? { read(.deviceId) }
    /// The 24-digit anonymous account number — the SOLE recovery credential for
    /// an anonymous account — kept for the signed-in session it belongs to.
    /// Wiped by `clearAnonymousId()` on sign-out (`LocalSignOut.wipe`, as
    /// Android drops its pending id), account deletion and switching to an
    /// email account, and on the first launch of a fresh install
    /// (`InstallIdentity`, MR-1726).
    var anonymousId: String? { read(.anonymousId) }
    /// Owner item 97: this device's CURRENT session was signed in with Sign in
    /// with Apple. `/auth/me` does not say whether an account is Apple-linked,
    /// so this is what decides whether account deletion re-authenticates with
    /// Apple first. Session-scoped: `clear()` drops it with the tokens.
    var signedInWithApple: Bool { read(.signInMethod) == "apple" }

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
        let priorAccess = read(.accessToken)
        guard write(.accessToken, value: accessToken) else { return false }
        guard write(.refreshToken, value: refreshToken) else {
            // Refresh write failed — undo the access write so no half-session
            // survives. Restore the prior access token if there was one.
            if let priorAccess {
                write(.accessToken, value: priorAccess)
            } else {
                delete(.accessToken)
            }
            return false
        }
        if let email {
            write(.userEmail, value: email)
        } else {
            delete(.userEmail)
        }
        return true
    }

    @discardableResult
    func saveDeviceId(_ id: String) -> Bool {
        write(.deviceId, value: id)
    }

    @discardableResult
    func saveAnonymousId(_ id: String) -> Bool {
        write(.anonymousId, value: id)
    }

    func clearAnonymousId() {
        delete(.anonymousId)
    }

    /// Record how the session now being stored was signed in (owner item 97).
    func setSignedInWithApple(_ value: Bool) {
        if value {
            write(.signInMethod, value: "apple")
        } else {
            delete(.signInMethod)
        }
    }

    /// Wipe the session. `device_id` survives a sign-out — see its accessor
    /// doc; a sign-out drops `anonymous_id` separately (`LocalSignOut.wipe`)
    /// and a reinstall is handled by `InstallIdentity`.
    func clear() {
        delete(.accessToken)
        delete(.refreshToken)
        delete(.userEmail)
        delete(.signInMethod)
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
        // `hb_access_token`: the tunnel extension's heartbeat bearer token
        // (VPNManager.heartbeatTokenAccount, audit D-13) — a session
        // credential, so it dies with the session.
        ["wg_private_key", "wg_preshared_key", "hb_access_token"].forEach { deleteShared(key: $0) }
    }

    // MARK: - Host-Only Keychain Operations (MR-1729)

    /// A host-only item's bytes, from the host-only group, or from where an
    /// older build put it while it has not been moved yet.
    func hostData(_ item: HostKeychainItem) -> Data? {
        HostOnlyKeychain.read(item, placement: hostPlacement, store: self)
    }

    /// Write a host-only keychain item. Returns `false` if `SecItemAdd` failed
    /// (disk full, permissions, etc.), matching the status-checking semantics
    /// of `setSharedSecret`.
    @discardableResult
    func setHostData(_ data: Data, for item: HostKeychainItem) -> Bool {
        HostOnlyKeychain.write(data, item, placement: hostPlacement, store: self)
    }

    /// Delete a host-only item, every copy of it.
    func deleteHostData(_ item: HostKeychainItem) {
        HostOnlyKeychain.delete(item, store: self)
    }

    /// MR-1729: move what an older build stored where the PacketTunnel
    /// extension can read it into the host-only group. Called on every launch
    /// before anything reads the session (AuthViewModel.init). Lossless and
    /// idempotent: an item is deleted from the shared group only after its copy
    /// in the host group has read back, and whatever cannot be moved now (the
    /// keychain still locked from boot, a failed write) stays readable where
    /// it is until a later launch moves it.
    @discardableResult
    func migrateHostOnlyItems() -> [HostKeychainItem: HostKeychainMigration] {
        let outcomes = HostOnlyKeychain.migrateAll(placement: hostPlacement, store: self)
        let moved = outcomes.values.filter { $0 == .moved }.count
        let deferred = outcomes.values.filter { $0 == .deferred }.count
        if moved > 0 || deferred > 0 {
            debugLog("[KeychainService] host-only items: %ld moved, %ld deferred", moved, deferred)
        }
        return outcomes
    }

    @discardableResult
    private func write(_ item: HostKeychainItem, value: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }
        return setHostData(data, for: item)
    }

    private func read(_ item: HostKeychainItem) -> String? {
        hostData(item).flatMap { String(data: $0, encoding: .utf8) }
    }

    private func delete(_ item: HostKeychainItem) {
        deleteHostData(item)
    }

    /// MR-1729: where the host-only items live. The host group is the resolved
    /// shared group plus `.host`, so a re-signed build with another team
    /// follows the shared group's discovery. Kept once found. An unresolved
    /// answer is NOT kept: the probe also fails while the keychain is locked
    /// from boot, so it is asked again next time. Until then the items are read
    /// and written as every older build did (`HostKeychainPlacement.unresolved`).
    private var hostPlacement: HostKeychainPlacement {
        placementLock.lock()
        defer { placementLock.unlock() }
        if let placement = resolvedPlacement { return placement }
        guard let shared = sharedAccessGroup else { return .unresolved }
        let host = HostOnlyKeychain.hostGroup(forShared: shared)
        guard probeHostGroup(host) else {
            debugLog("[KeychainService] host-only keychain group unavailable — items stay where older builds kept them")
            return .unresolved
        }
        let placement = HostKeychainPlacement.hostOnly(host: host, shared: shared)
        resolvedPlacement = placement
        return placement
    }

    /// True when this process can write to the host-only group, i.e. the build
    /// is signed with it. Same posture as the items it stands in for
    /// (AfterFirstUnlockThisDeviceOnly), so it answers as they would.
    private func probeHostGroup(_ group: String) -> Bool {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: HostKeychainItem.accessToken.service,
            kSecAttrAccount as String: "_birdo_probe_host",
            kSecAttrAccessGroup as String: group,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        var add = base
        add[kSecValueData as String] = Data([0])
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        add[kSecAttrSynchronizable as String] = kCFBooleanFalse as Any
        SecItemDelete(base as CFDictionary)
        let status = SecItemAdd(add as CFDictionary, nil)
        SecItemDelete(base as CFDictionary)
        return status == errSecSuccess
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

/// MR-1726: the store `InstallIdentity.checkOnLaunch` and `LocalSignOut.wipe`
/// read and replace.
extension KeychainService: InstallIdentityStore {
    var hasSession: Bool { accessToken != nil || refreshToken != nil }

    /// The session half of a sign-out: tokens, email, sign-in method and the
    /// tunnel's shared secrets.
    func clearSession() { clear() }
}

/// MR-1729: the live keychain behind `HostOnlyKeychain`. `group == nil` sends no
/// `kSecAttrAccessGroup`, exactly as every host-only query here did before; the
/// attributes are the ones those queries always used.
extension KeychainService: KeychainGroupStore {
    func lookup(service: String, account: String, group: String?) -> KeychainLookup {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        if let group { query[kSecAttrAccessGroup as String] = group }
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data else { return .failed(status) }
            return .found(data)
        case errSecItemNotFound:
            return .notFound
        default:
            return .failed(status)
        }
    }

    func store(_ data: Data, service: String, account: String, group: String?) -> Bool {
        remove(service: service, account: account, group: group)
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecAttrSynchronizable as String: kCFBooleanFalse as Any,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        if let group { query[kSecAttrAccessGroup as String] = group }
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    func remove(service: String, account: String, group: String?) {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: kCFBooleanTrue as Any,
        ]
        if let group { query[kSecAttrAccessGroup as String] = group }
        SecItemDelete(query as CFDictionary)
    }
}
