import Foundation

// MR-1771 / MR-2500: what one `POST /auth/refresh` answer means for the stored
// session, how long the next refresh waits, and the single-flight coordinator
// that applies both. Android twin: `BirdoRepository.lockedRefresh` with its
// `refreshHold`, and `RefreshRetry.kt`.
//
// birdo-web's rule (backend/contract/README.md, "Token refresh"): a 401 is the
// refresh token's death, and the ONLY answer that ends the session. A 429 is a
// rate limit, answered by every limiter on the route BEFORE the token is read
// or rotated: the session is intact, and the same token is presented again
// after `Retry-After` (else the body's `details.retryAfter`, web #912, else
// 60 s). A 5xx, or no answer at all, keeps the session too, and the same token
// is presented again after a capped exponential backoff with jitter: a 503
// says the token is intact (a session-store outage, or a rotation that failed
// and restored it, MR-2500).
//
// A 403 keeps the session as well (MR-1771). It signed out until now, by the
// lead decision of 2026-10-23, but an infrastructure block answers it too: the
// error is surfaced, the keychain is not touched, and the refresh backs off.
//
// Foundation-only by design so RefreshAnswerTests can pin it: APIClient.swift
// (its caller) cannot be compiled into the test bundle.

/// How `APIClient.refreshTokens()` treats one `/auth/refresh` answer.
enum RefreshAnswer: Equatable, Sendable {
    /// 2xx: the pair was rotated.
    case rotated
    /// 401: the refresh token is dead. The ONLY answer that becomes
    /// `APIError.unauthorized`, which is what signs the user out and wipes
    /// the keychain (`AuthViewModel.logoutIfUnauthorized`).
    case dead
    /// 403: refused, but not a verdict on the token. The session is kept, the
    /// error is surfaced, and the refresh backs off.
    case refused
    /// 429: the session is kept, and no refresh is sent for `retryAfter`
    /// seconds; then the same token is presented again.
    case rateLimited(retryAfter: TimeInterval)
    /// Any other status (a 5xx, an unexpected one): the session is kept and
    /// the refresh backs off, never for less than `retryAfter` when the
    /// server named one.
    case unavailable(retryAfter: TimeInterval?)

    static func classify(status: Int, retryAfterHeader: String?, body: Data? = nil) -> RefreshAnswer {
        switch status {
        case 200...299:
            return .rotated
        case 401:
            return .dead
        case 403:
            return .refused
        case 429:
            // Every limiter on the route sends Retry-After and, since web
            // #912, `details.retryAfter`; a 429 that lost both still must not
            // be re-asked at once.
            return .rateLimited(retryAfter: ConnectRetryAfter.clamped(seconds: seconds(retryAfterHeader))
                                ?? ConnectRetryAfter.clamped(seconds: detailsRetryAfter(body))
                                ?? RefreshRetry.rateLimitedDefault)
        default:
            return .unavailable(retryAfter: ConnectRetryAfter.clamped(seconds: seconds(retryAfterHeader)))
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

    /// `details.retryAfter`: the key birdo-web's `refusal()` writes, and the
    /// one every refresh 429 carries since web #912 (`details: {code:
    /// "rate_limited", retryAfter: N}`, N the Retry-After header's seconds).
    /// Connect refusals name theirs `retryAfterSeconds` (ConnectErrorEnvelope).
    static func detailsRetryAfter(_ body: Data?) -> Double? {
        guard let body, let envelope = try? JSONDecoder().decode(RefusalWait.self, from: body) else {
            return nil
        }
        return envelope.details?.retryAfter
    }

    /// The one field of a refusal body the wait needs.
    private struct RefusalWait: Decodable {
        struct Details: Decodable {
            let retryAfter: Double?
        }
        let details: Details?
    }
}

/// MR-1771 / MR-2500: the waits after a refresh that kept the session.
/// Android twin: `RefreshRetry.kt`.
enum RefreshRetry {
    /// The wait after a 429 that named none this client can use.
    static let rateLimitedDefault: TimeInterval = 60
    /// The first backoff's ceiling; each failure in a row doubles it.
    static let backoffBase: TimeInterval = 2
    /// The backoff never grows past this.
    static let backoffCap: TimeInterval = 5 * 60

    /// The wait after the `failures`th refresh in a row that got no verdict:
    /// a ceiling of `backoffBase` doubled per failure, capped at `backoffCap`,
    /// and a random point in the ceiling's upper half (`random` in 0...1).
    /// The jitter keeps clients that failed together from coming back
    /// together; the lower bound keeps any wait from being zero.
    static func backoff(failures: Int, random: Double) -> TimeInterval {
        // 2 s doubled 8 times is past the cap; more doublings only risk an overflow.
        let doublings = min(max(failures, 1) - 1, 16)
        let ceiling = min(backoffBase * Double(1 << doublings), backoffCap)
        let draw = random.isNaN ? 0 : min(max(random, 0), 1)
        return ceiling / 2 + ceiling / 2 * draw
    }
}

/// While a wait runs, no refresh is sent: asking sooner only earns another
/// refusal. A 429's wait is the server's (MR-1771); any other answer that kept
/// the session, or no answer at all, waits the backoff (MR-2500). Every wait
/// is at most `ConnectRetryAfter.maximum` (ten minutes), well inside an access
/// token's hour, so a new sign-in never needs it cleared.
struct RefreshHoldOff: Equatable, Sendable {
    private(set) var since: Date?
    private(set) var wait: TimeInterval = 0
    /// Refreshes in a row that got no verdict (a 5xx, a 403, no answer): the
    /// backoff's exponent. A 2xx or a 401 starts it over (`reset()`).
    private(set) var failures = 0
    /// The status a refresh held by this wait reports: 429 while a rate
    /// limit's wait runs, 503 during a backoff.
    private(set) var heldStatus = 503

    /// Start holding at `now` for a 429's `wait` seconds.
    mutating func hold(for wait: TimeInterval, now: Date) {
        since = now
        self.wait = wait
        heldStatus = 429
    }

    /// A refresh got no verdict: hold at `now` for the next backoff step,
    /// never for less than `serverWait`. Returns the wait.
    @discardableResult
    mutating func backOff(serverWait: TimeInterval?, random: Double, now: Date) -> TimeInterval {
        failures += 1
        let wait = max(serverWait ?? 0, RefreshRetry.backoff(failures: failures, random: random))
        since = now
        self.wait = wait
        heldStatus = 503
        return wait
    }

    /// A 2xx or a 401: the next failure waits the first step again.
    mutating func reset() {
        failures = 0
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

/// Serialises token refreshes (single flight). The first 401 starts the
/// refresh; every caller that comes while it is in flight awaits the SAME task
/// and then retries with the freshly stored token, or shares its error. While
/// a `RefreshHoldOff` wait runs, nothing is sent: the caller gets
/// `heldError(status)` at once and the session is untouched. `APIClient`
/// owns the one instance; `refreshTokens()` sets the waits.
actor RefreshCoordinator {
    private var inFlight: Task<Void, any Error>?
    private var holdOff = RefreshHoldOff()
    /// How many callers joined a refresh already in flight instead of
    /// starting their own. RefreshAnswerTests reads it.
    private(set) var joined = 0
    /// The error a held refresh throws, given the status it reports.
    private let heldError: @Sendable (Int) -> any Error

    init(heldError: @escaping @Sendable (Int) -> any Error) {
        self.heldError = heldError
    }

    func refresh(_ work: @escaping @Sendable () async throws -> Void) async throws {
        if let task = inFlight {
            joined += 1
            try await task.value
            return
        }
        // The last refresh was rate limited or got no verdict, and its wait is
        // not over: the same transient answer, the session untouched, nothing
        // sent.
        if holdOff.isHeld(now: Date()) {
            throw heldError(holdOff.heldStatus)
        }
        let task = Task<Void, any Error> { try await work() }
        inFlight = task
        defer { inFlight = nil }
        try await task.value
    }

    /// A 429: hold every refresh for the server's `wait` seconds from now.
    func hold(for wait: TimeInterval) {
        holdOff.hold(for: wait, now: Date())
    }

    /// A 5xx, a 403 or no answer: hold every refresh for the next backoff
    /// step from now, never for less than `serverWait`.
    func backOff(serverWait: TimeInterval?) {
        holdOff.backOff(serverWait: serverWait, random: Double.random(in: 0...1), now: Date())
    }

    /// A 2xx or a 401: the backoff starts over.
    func reset() {
        holdOff.reset()
    }
}
