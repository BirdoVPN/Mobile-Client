import Foundation

/// A-L24 / MR-2241 / MA-IOS-010: writing a freshly minted session to the
/// keychain can fail. For an anonymous account that is the worst moment: the
/// server has already created the account, and the 24-digit number is its only
/// credential. Foundation-only by design so SessionPersistenceTests can pin it
/// with a failing store; AuthViewModel (its caller) cannot be compiled into
/// the unit-test bundle.
enum SessionPersistence {
    /// One write plus ONE retry. A keychain failure right after launch or
    /// unlock is usually transient (errSecInteractionNotAllowed and friends);
    /// a second failure is treated as real. `attempt` returns true on success.
    static func saveWithRetry(_ attempt: () -> Bool) -> Bool {
        if attempt() { return true }
        return attempt()
    }
}
