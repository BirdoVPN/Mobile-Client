import Foundation

/// MR-2580 (L1 of the #665 review): how a new pair of session tokens is stored.
///
/// Spec-first: today's order (access token first), moved here unchanged so
/// TokenPairWriteTests can run it against an in-memory keychain.
enum TokenPairWrite {
    /// What the stored pair is to the server once the new one exists.
    enum Replaced: Equatable, Sendable {
        /// A sign-in or a number rotation: the stored pair, if any, is another
        /// session's, and this write did not spend its refresh token.
        case anotherSession
        /// A refresh rotation: the server spent the stored refresh token when it
        /// answered with the new pair.
        case consumedRefreshToken
    }

    /// Store the new pair. True when both tokens are stored.
    @discardableResult
    static func save(accessToken: Data,
                     refreshToken: Data,
                     replacing: Replaced,
                     in store: any TokenPairStore) -> Bool {
        let priorAccess = store.hostLookup(.accessToken).data
        guard store.replaceHostData(accessToken, for: .accessToken) else { return false }
        guard store.replaceHostData(refreshToken, for: .refreshToken) else {
            if let priorAccess {
                store.replaceHostData(priorAccess, for: .accessToken)
            } else {
                store.deleteHostData(.accessToken)
            }
            return false
        }
        return true
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
