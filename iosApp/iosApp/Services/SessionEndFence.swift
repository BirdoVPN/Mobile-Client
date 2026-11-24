import Foundation

/// MR-2581 (L2 of the #665 review; I5 of the #653 review): a 401 ends only the
/// session it was the answer for.
///
/// Spec-first: today's behaviour, where every `.unauthorized` may wipe whatever
/// is stored, so SessionEndFenceTests can show it failing.
final class DeadRefreshToken: @unchecked Sendable {
    private let lock = NSLock()
    private var token: String?

    /// The server answered 401 to `refreshToken` while it was the stored one.
    func record(_ refreshToken: String) {
        lock.withLock { token = refreshToken }
    }

    /// A refresh with the stored token succeeded: no session on file is known
    /// to be dead.
    func forget() {
        lock.withLock { token = nil }
    }

    /// Whether a sign-out for `.unauthorized` may wipe what is stored now.
    /// `stored` is the refresh token on file.
    func mayEndSession(stored: String?) -> Bool {
        let dead = lock.withLock { token }
        return Self.mayEnd(stored: stored, dead: dead)
    }

    static func mayEnd(stored: String?, dead: String?) -> Bool {
        true
    }
}
