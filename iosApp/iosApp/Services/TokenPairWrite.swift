import Foundation

/// MR-2580 (L1 of the #665 review): how a new pair of session tokens is stored.
///
/// The access and the refresh token are two keychain items, and the app can be
/// killed between any two keychain calls: it holds no background task, and
/// jetsam does not wait. So the ORDER of the two writes decides what a kill
/// leaves behind.
///   - Refresh token first (this): a kill in between leaves the PRIOR access
///     token with the NEW refresh token. The prior access token's next 401
///     refreshes with the new one, and nothing is lost.
///   - Access token first (every build since #114): a kill in between left the
///     NEW access token with the PRIOR refresh token. After a refresh rotation
///     that refresh token is already spent. It was presented once the new
///     access token expired, about an hour later and far past the server's
///     30 s grace, and the server read that as theft: every session of the
///     account and its WireGuard peers were revoked. After a sign-in or a
///     number rotation it was a sign-out an hour later instead.
///
/// Each token is replaced in ONE step (`TokenPairStore.replaceHostData`: an
/// update where the item exists, an add where it does not), so a write that
/// fails leaves its item as it was, and no token is ever deleted to make room.
/// What a failure leaves:
///   - the refresh write fails: nothing changed, the prior pair is intact;
///   - the access write fails: the stored access token is still the prior one
///     (or none), and the rest depends on what the new pair replaced
///     (`Replaced`).
/// Never the new access token with any refresh token but the new one.
///
/// Android stores its pair in one synchronous commit; Desktop writes the
/// refresh token first (#469, P469-L2).
enum TokenPairWrite {
    /// What the stored pair is to the server once the new one exists.
    enum Replaced: Equatable, Sendable {
        /// A sign-in or a number rotation: the stored pair, if any, is another
        /// session's, and this write did not spend its refresh token. If the
        /// access write fails, it is put back, so the device is left as it was
        /// and the caller's "not stored" is true: a cold start counts a live
        /// refresh token as signed in.
        case anotherSession
        /// A refresh rotation: the server spent the stored refresh token when it
        /// answered with the new pair. It is never put back. If the access write
        /// fails, the new refresh token stays, and the prior access token's next
        /// 401 refreshes with it.
        case consumedRefreshToken
    }

    /// What a write left in the keychain.
    enum Outcome: Equatable, Sendable {
        /// Both tokens.
        case stored
        /// A refresh rotation only: the new refresh token, next to the prior
        /// access token (the access write failed).
        case refreshOnly
        /// Neither new token. A sign-in leaves the prior pair, or what of it
        /// could be put back; a refresh rotation leaves the prior access token,
        /// with the spent refresh token removed where the keychain allowed it.
        case notStored
    }

    /// Store the new pair, refresh token first. True when both tokens are
    /// stored (`write`).
    @discardableResult
    static func save(accessToken: Data,
                     refreshToken: Data,
                     replacing: Replaced,
                     in store: any TokenPairStore) -> Bool {
        write(accessToken: accessToken, refreshToken: refreshToken, replacing: replacing, in: store) == .stored
    }

    /// Store the new pair, refresh token first, and say what was left
    /// (`Outcome`). Called under `SessionTokenVault`'s lock.
    static func write(accessToken: Data,
                      refreshToken: Data,
                      replacing: Replaced,
                      in store: any TokenPairStore) -> Outcome {
        switch replacing {
        case .consumedRefreshToken:
            guard store.replaceHostData(refreshToken, for: .refreshToken) else {
                // #671 review, item 1: the stored refresh token is the one the
                // server just spent. It goes wherever the keychain still allows
                // a delete; `SessionTokenVault` fences it either way.
                store.deleteHostData(.refreshToken)
                return .notStored
            }
            return store.replaceHostData(accessToken, for: .accessToken) ? .stored : .refreshOnly
        case .anotherSession:
            // Read BEFORE anything is written: what a failed sign-in puts back.
            let priorRefresh = store.hostLookup(.refreshToken)
            let priorAccess = store.hostLookup(.accessToken)
            // #671 review, item 4: ONE account at every kill point. The prior
            // access token goes before the new refresh token comes; otherwise a
            // kill between the two writes of an account switch left the prior
            // account's access token with the new account's refresh token, and
            // requests acted on the prior account until its 401, then on the
            // new one under the prior one's name. Only a token that was read
            // can be put back, so only that one is removed, and a keychain that
            // will not let it go gets nothing written next to it.
            if case .found = priorAccess {
                store.deleteHostData(.accessToken)
                guard store.hostLookup(.accessToken) == .notFound else { return .notStored }
            }
            guard store.replaceHostData(refreshToken, for: .refreshToken) else {
                putBackAccess(priorAccess, in: store)
                return .notStored
            }
            guard store.replaceHostData(accessToken, for: .accessToken) else {
                putBackRefresh(priorRefresh, in: store)
                // The prior access token goes back only next to the prior
                // refresh token, never next to the new one a failed put-back
                // (and a failed delete) left.
                if store.hostLookup(.refreshToken) == priorRefresh { putBackAccess(priorAccess, in: store) }
                return .notStored
            }
            return .stored
        }
    }

    /// Undo a failed sign-in's refresh write. What cannot be put back (it could
    /// not be read, or the write back failed) is removed: the prior access
    /// token then ends in a sign-out at its next 401, never in the session that
    /// was reported as not stored.
    private static func putBackRefresh(_ prior: KeychainLookup, in store: any TokenPairStore) {
        if case .found(let data) = prior, store.replaceHostData(data, for: .refreshToken) {
            return
        }
        store.deleteHostData(.refreshToken)
    }

    /// The prior access token, removed before the write (`.found` only). If it
    /// cannot go back, the prior refresh token alone is left: one account, and
    /// its next 401 refreshes.
    private static func putBackAccess(_ prior: KeychainLookup, in store: any TokenPairStore) {
        if case .found(let data) = prior { store.replaceHostData(data, for: .accessToken) }
    }
}

/// The host-only items `TokenPairWrite` reads and replaces. `KeychainService`
/// conforms; TokenPairWriteTests use an in-memory store that can fail any step
/// and records every state the keychain passes through.
protocol TokenPairStore: AnyObject {
    func hostLookup(_ item: HostKeychainItem) -> KeychainLookup
    /// Replace the item in one step (`HostOnlyKeychain.replace`). False when it
    /// was not written, and then it is left as it was.
    @discardableResult func replaceHostData(_ data: Data, for item: HostKeychainItem) -> Bool
    func deleteHostData(_ item: HostKeychainItem)
}
