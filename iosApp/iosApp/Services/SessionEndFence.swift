import Foundation

/// MR-2581 (L2 of the #665 review; I5 of the #653 review): a 401 ends only the
/// session it was the answer for.
///
/// `APIClient.refreshTokens` records the refresh token the server answered 401
/// to, while it was still the stored one. `AuthViewModel.logoutIfUnauthorized`
/// wipes the keychain only while that token, or no token, is stored. A pair
/// stored after that 401 belongs to a session the 401 said nothing about: a
/// number rotation commits its new pair while a refresh of the old session can
/// still be on the wire, and that refresh's 401 used to sign the user out of
/// the new session right after a rotation that worked. Android clears inside
/// its fenced 401 branch for the same reason (`BirdoRepository.refreshToken`).
///
/// Only four places throw `.unauthorized`: no refresh token stored (nothing to
/// keep), this 401, a retry's 401 right after a refresh that worked (which
/// `forget`s the record first, so it wipes as before), and the number
/// rotation's own refresh (its sheet never wipes).
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

    /// Wipe unless a refresh token is stored that is not the one that died.
    static func mayEnd(stored: String?, dead: String?) -> Bool {
        guard let stored, let dead else { return true }
        return stored == dead
    }
}
