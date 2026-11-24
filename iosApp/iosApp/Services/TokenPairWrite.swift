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

    /// Store the new pair, refresh token first. True when both tokens are
    /// stored. False when they are not, and then the keychain holds the prior
    /// pair; or the prior access token alone (a sign-in whose prior refresh
    /// token could not be put back); or, after a refresh rotation only, the
    /// prior access token with the new refresh token.
    @discardableResult
    static func save(accessToken: Data,
                     refreshToken: Data,
                     replacing: Replaced,
                     in store: any TokenPairStore) -> Bool {
        // Read BEFORE anything is written: what a failed sign-in puts back.
        let prior: KeychainLookup? = replacing == .anotherSession ? store.hostLookup(.refreshToken) : nil
        guard store.replaceHostData(refreshToken, for: .refreshToken) else {
            return false
        }
        guard store.replaceHostData(accessToken, for: .accessToken) else {
            if let prior { putBack(prior, in: store) }
            return false
        }
        return true
    }

    /// Undo a failed sign-in's refresh write. What cannot be put back (it could
    /// not be read, or the write back failed) is removed: the prior access
    /// token then ends in a sign-out at its next 401, never in the session that
    /// was reported as not stored.
    private static func putBack(_ prior: KeychainLookup, in store: any TokenPairStore) {
        if case .found(let data) = prior, store.replaceHostData(data, for: .refreshToken) {
            return
        }
        store.deleteHostData(.refreshToken)
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
