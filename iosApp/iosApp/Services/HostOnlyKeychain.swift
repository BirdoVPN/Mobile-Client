import Foundation

/// MR-1729 — the host app's own keychain items live in an access group the
/// PacketTunnel extension is not entitled to.
///
/// Before: `KeychainService` and `BirdoPQManager` named no access group, so
/// every item meant for the app alone (the access and refresh tokens, the
/// account email, `device_id`, `anonymous_id` — the anonymous account's only
/// recovery credential — the sign-in method and the BirdoPQ ML-KEM keypair)
/// landed in the app's DEFAULT group: the first `keychain-access-groups` entry,
/// `<team>.app.birdo.vpn`. The PacketTunnel extension is entitled to exactly
/// that group, so the network-facing process that runs wireguard-go could read
/// the refresh token, whatever the "host app only" comments said.
///
/// Now the app is also entitled to `<team>.app.birdo.vpn.host`, which
/// PacketTunnel.entitlements does not list, and these items are read and
/// written there by name. The shared group keeps only what the extension needs
/// (the WireGuard keys, the heartbeat token, the breaker record). It stays FIRST
/// in the app's list: it is the default group every older build's group-less
/// write used, and the one `KeychainService.discoverDefaultAccessGroup`
/// recognises.
///
/// An install that upgrades must stay signed in, so:
///   - `migrate` (every launch, before anything reads the session) copies an
///     item from the shared group into the host group, reads the copy back and
///     only then deletes the shared one. Any failure leaves the old copy where
///     it is, to be retried on the next launch. A host copy that already exists
///     wins: only a write by this build can have put it there.
///   - `read` falls back to the old copy while one is left, so an item whose
///     move failed or has not run yet still reads.
///   - `delete` names no group, so it removes both copies: a sign-out cannot
///     leave an old copy behind for the fallback to bring back.
///   - a build that cannot use the host group (`.unresolved`: signed without
///     the entitlement) runs exactly as before rather than failing every
///     sign-in.
///
/// Foundation-only on purpose: the keychain is behind `KeychainGroupStore`, so
/// HostOnlyKeychainTests runs every step against an in-memory keychain that
/// enforces entitlements the way the system does, the extension's view of it
/// included. `KeychainService` (Security) is the live store.
enum HostOnlyKeychain {
    /// The host group is the shared group plus this suffix:
    /// `$(AppIdentifierPrefix)app.birdo.vpn.host` in the app's entitlements
    /// (iOS and macOS), and in neither PacketTunnel entitlements file.
    static let hostGroupSuffix = ".host"

    static func hostGroup(forShared shared: String) -> String {
        shared + hostGroupSuffix
    }

    static func read(_ item: HostKeychainItem,
                     placement: HostKeychainPlacement,
                     store: any KeychainGroupStore) -> Data? {
        guard case .hostOnly(let host, _) = placement else {
            return store.lookup(service: item.service, account: item.account, group: nil).data
        }
        switch store.lookup(service: item.service, account: item.account, group: host) {
        case .found(let data):
            return data
        case .failed:
            return nil
        case .notFound:
            // Not moved yet: the copy an older build wrote, wherever its
            // default group put it. No group, because the host group was just
            // found empty.
            return store.lookup(service: item.service, account: item.account, group: nil).data
        }
    }

    /// Replace the item. False when the write failed. A copy an older build
    /// left in the shared group is removed only after the write succeeded, so
    /// a failed write leaves it readable.
    @discardableResult
    static func write(_ data: Data,
                      _ item: HostKeychainItem,
                      placement: HostKeychainPlacement,
                      store: any KeychainGroupStore) -> Bool {
        guard case .hostOnly(let host, let shared) = placement else {
            return store.store(data, service: item.service, account: item.account, group: nil)
        }
        guard store.store(data, service: item.service, account: item.account, group: host) else {
            return false
        }
        // Superseded: the copy the PacketTunnel extension could read.
        store.remove(service: item.service, account: item.account, group: shared)
        return true
    }

    /// Remove every copy, wherever it is. No group, so a copy not moved yet
    /// goes too and `read` cannot fall back to it after a sign-out.
    static func delete(_ item: HostKeychainItem, store: any KeychainGroupStore) {
        store.remove(service: item.service, account: item.account, group: nil)
    }

    /// Move one item an older build stored in the shared group. Idempotent:
    /// read the old copy, write the new one, read it back, and only then delete
    /// the old one. Anything short of that leaves the old copy where it was.
    @discardableResult
    static func migrate(_ item: HostKeychainItem,
                        placement: HostKeychainPlacement,
                        store: any KeychainGroupStore) -> HostKeychainMigration {
        guard case .hostOnly(let host, let shared) = placement else { return .deferred }
        let service = item.service
        let account = item.account

        switch store.lookup(service: service, account: account, group: host) {
        case .found:
            // Already moved. A shared copy can only be one an interrupted move
            // or write left behind, and the host copy is this build's own
            // write, so it wins.
            store.remove(service: service, account: account, group: shared)
            return .alreadyMoved
        case .failed:
            return .deferred
        case .notFound:
            break
        }

        let old: Data
        switch store.lookup(service: service, account: account, group: shared) {
        case .found(let data):
            old = data
        case .notFound:
            return .nothingStored
        case .failed:
            return .deferred
        }

        // Until the copy reads back, the shared copy is the only one there is.
        guard store.store(old, service: service, account: account, group: host),
              store.lookup(service: service, account: account, group: host) == .found(old) else {
            return .deferred
        }
        store.remove(service: service, account: account, group: shared)
        return .moved
    }

    /// `migrate` for every host-only item.
    @discardableResult
    static func migrateAll(placement: HostKeychainPlacement,
                           store: any KeychainGroupStore) -> [HostKeychainItem: HostKeychainMigration] {
        var outcomes: [HostKeychainItem: HostKeychainMigration] = [:]
        for item in HostKeychainItem.allCases {
            outcomes[item] = migrate(item, placement: placement, store: store)
        }
        return outcomes
    }
}

/// Every keychain item the host app keeps to itself. `KeychainService` and
/// `BirdoPQManager` address their items through this enum and the launch
/// migration walks `allCases`, so an item cannot be added to one and not the
/// other.
enum HostKeychainItem: String, CaseIterable, Sendable {
    case accessToken = "access_token"
    case refreshToken = "refresh_token"
    case userEmail = "user_email"
    case deviceId = "device_id"
    case anonymousId = "anonymous_id"
    case signInMethod = "sign_in_method"
    /// BirdoPQ's static ML-KEM-1024 keypair, stored as `pk || sk`.
    case pqKeypair = "birdo_pq_v1_keypair"

    /// The keychain service. The PQ keypair keeps its own, so a PQ-only purge
    /// cannot touch the session (BirdoPQManager).
    var service: String {
        switch self {
        case .accessToken, .refreshToken, .userEmail, .deviceId, .anonymousId, .signInMethod:
            return "app.birdo.vpn"
        case .pqKeypair:
            return "app.birdo.vpn.pq"
        }
    }

    var account: String { rawValue }
}

/// Where the host-only items live.
enum HostKeychainPlacement: Equatable, Sendable {
    /// In `host`. `shared` is the group an older build's items landed in.
    case hostOnly(host: String, shared: String)
    /// This build cannot use the host group: the pre-MR-1729 queries, with no
    /// access group, so nothing that worked before stops working.
    case unresolved
}

/// What `HostOnlyKeychain.migrate` did with one item.
enum HostKeychainMigration: Equatable, Sendable {
    /// Neither copy exists.
    case nothingStored
    /// Already in the host group (any shared copy left by an interrupted move
    /// is removed).
    case alreadyMoved
    /// Copied, read back and the shared copy removed.
    case moved
    /// Not now (keychain locked, a failed or unreadable write, no host group).
    /// The item stays readable where it is and the next launch tries again.
    case deferred
}

/// One keychain lookup. Only `.notFound` means absent: a locked keychain or
/// any other error is `.failed`, and the migration moves and deletes nothing
/// on it.
enum KeychainLookup: Equatable, Sendable {
    case found(Data)
    case notFound
    /// The `OSStatus`.
    case failed(Int32)

    var data: Data? {
        if case .found(let data) = self { return data }
        return nil
    }
}

/// The keychain, one access group at a time. `group == nil` is a query with no
/// `kSecAttrAccessGroup`, as every pre-MR-1729 query was: an add lands in the
/// default (first) group, and a lookup or a delete covers every group the
/// process is entitled to.
protocol KeychainGroupStore: AnyObject {
    func lookup(service: String, account: String, group: String?) -> KeychainLookup
    /// Replace the item in `group`: delete it there, then add it. False when
    /// the add failed.
    func store(_ data: Data, service: String, account: String, group: String?) -> Bool
    func remove(service: String, account: String, group: String?)
}
