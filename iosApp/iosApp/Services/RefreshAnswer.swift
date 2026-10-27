import Foundation

// MR-1771: what one `POST /auth/refresh` answer means for the stored session,
// and how long a rate-limited refresh is not asked again. Android twin:
// `BirdoRepository.lockedRefresh` and its `refreshHold`.
//
// birdo-web's rule (backend/contract/README.md, "Token refresh"; live since
// web 32ab2b23, #739): a 401 is the refresh token's death. A 429 is a rate
// limit, answered by every limiter on the route BEFORE the token is read or
// rotated, so the session is intact and the client asks again after
// `Retry-After`. A 5xx, or no answer at all, keeps the session too. Until #739
// the controller answered its own rate limit with 401, and every client signed
// the user out over it.
//
// A 403 still signs out (lead decision 2026-10-23: on this route it means the
// refresh token is refused), exactly as before MR-1771.
//
// Foundation-only by design so RefreshAnswerTests can pin it: APIClient.swift
// (its caller) cannot be compiled into the test bundle.

/// How `APIClient.refreshTokens()` treats one `/auth/refresh` answer.
enum RefreshAnswer: Equatable, Sendable {
    /// 2xx: the pair was rotated.
    case rotated
    /// 401 / 403: the refresh token is dead. The ONLY answer that becomes
    /// `APIError.unauthorized`, which is what signs the user out and wipes
    /// the keychain (`AuthViewModel.logoutIfUnauthorized`).
    case dead
    /// Anything else (429, 5xx, an unexpected status): keep the session.
    /// `retryAfter` is how long no refresh is sent again (clamped like a
    /// connect refusal's wait, `ConnectRetryAfter`), or nil when the server
    /// named none and nothing calls for one.
    case keepSession(retryAfter: TimeInterval?)

    static func classify(status: Int, retryAfterHeader: String?) -> RefreshAnswer {
        switch status {
        case 200...299:
            return .rotated
        case 401, 403:
            return .dead
        case 429:
            // Every limiter on the route sends Retry-After; a 429 that lost it
            // still must not be re-asked at once.
            return .keepSession(retryAfter: ConnectRetryAfter.clamped(seconds: seconds(retryAfterHeader))
                                ?? ConnectRetryAfter.retryableDefault)
        default:
            return .keepSession(retryAfter: ConnectRetryAfter.clamped(seconds: seconds(retryAfterHeader)))
        }
    }

    /// A delta-seconds `Retry-After`. An HTTP date is ignored: the device
    /// clock is not the server's (as on Android, `RetryAfter.fromHeader`).
    static func seconds(_ header: String?) -> Double? {
        guard let text = header?.trimmingCharacters(in: .whitespaces), let value = Int(text) else {
            return nil
        }
        return Double(value)
    }
}

/// MR-1771: while the server's wait after a rate-limited refresh runs, no
/// refresh is sent: asking sooner only earns another 429. The wait is at most
/// `ConnectRetryAfter.maximum` (ten minutes), well inside an access token's
/// hour, so a new sign-in never needs it cleared.
struct RefreshHoldOff: Equatable, Sendable {
    private(set) var since: Date?
    private(set) var wait: TimeInterval = 0

    /// Start holding at `now` for `wait` seconds.
    mutating func hold(for wait: TimeInterval, now: Date) {
        since = now
        self.wait = wait
    }

    /// Whether a refresh must still wait at `now`. A clock set back before
    /// the hold began ends it: one early refresh costs at worst another 429,
    /// where trusting the wrong clock could hold for hours.
    func isHeld(now: Date) -> Bool {
        guard let since else { return false }
        let elapsed = now.timeIntervalSince(since)
        return elapsed >= 0 && elapsed < wait
    }
}
