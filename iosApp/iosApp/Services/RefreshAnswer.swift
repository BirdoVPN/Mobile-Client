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
// says the token is intact (a session-store outage). Once the server half of
// MR-2500 lands (not on birdo-web main yet; it follows web #929), a rotation
// that fails after consuming the token will restore it and answer 503 too;
// until then that failure is a 401 from the server.
//
// A 426, a 2xx whose pair cannot be read and a TLS-pin failure keep the
// session and hold the same way (lead decision L2, 2026-11-19): the error
// surfaces, and nothing retries until the wait is over.
//
// One timing table with Desktop #467 (`refresh_policy.rs`, `error.rs`
// `RETRY_AFTER_MAX_SECS`; lead decision L2): the backoff starts at 1 s,
// doubles, is capped at 30 s and jitters in the step's upper half; a wait the
// server names is clamped to 1 s ... 60 s, and a named 0 is 1 s.
//
// A 403 keeps the session as well (MR-1771; the owner's rule supersedes the
// lead decision of 2026-10-23 that it signed out): an infrastructure block
// answers it too. The keychain is not touched, the refresh backs off, and the
// error surfaces as a transient 503, never as a 403: an auth failure is not a
// server refusing a relay (lead, 2026-11-19; Android does the same).
//
// No timer retries a held refresh: the next request does, or the app coming
// to the foreground (`RefreshCoordinator.retryIsDue`, ContentView).
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
    /// error surfaces as a transient 503, and the refresh backs off.
    case refused
    /// 429: the session is kept, and no refresh is sent for `retryAfter`
    /// seconds; then the same token is presented again.
    case rateLimited(retryAfter: TimeInterval)
    /// Any other status (a 5xx, a 426, an unexpected one): the session is
    /// kept and the refresh backs off, never for less than `retryAfter` when
    /// the server named one (clamped by `RefreshRetry.serverWait`).
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
            return .rateLimited(retryAfter: RefreshRetry.serverWait(seconds: seconds(retryAfterHeader))
                                ?? RefreshRetry.serverWait(seconds: detailsRetryAfter(body))
                                ?? RefreshRetry.rateLimitedDefault)
        default:
            return .unavailable(retryAfter: RefreshRetry.serverWait(seconds: seconds(retryAfterHeader)))
        }
    }

    /// A delta-seconds `Retry-After`, as sent. An HTTP date is ignored: the
    /// device clock is not the server's (as on Android,
    /// `RetryAfter.headerSeconds`).
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
/// Android twin: `RefreshRetry.kt`. One table with Desktop #467
/// (`refresh_policy.rs`, `error.rs`; lead decision L2, 2026-11-19).
enum RefreshRetry {
    /// The wait after a 429 that named none this client can use.
    static let rateLimitedDefault: TimeInterval = 60
    /// The first backoff's ceiling; each failure in a row doubles it.
    /// Desktop `BACKOFF_BASE`.
    static let backoffBase: TimeInterval = 1
    /// The backoff never grows past this, jitter included. Desktop
    /// `BACKOFF_CAP`.
    static let backoffCap: TimeInterval = 30
    /// The shortest wait the server may name for a refresh: a named 0 is
    /// this.
    static let serverWaitMinimum: TimeInterval = 1
    /// The longest wait the server may name for a refresh. Desktop
    /// `RETRY_AFTER_MAX_SECS`.
    static let serverWaitMaximum: TimeInterval = 60

    /// A wait the server named for a refresh (`Retry-After`, or the body's
    /// `details.retryAfter`), clamped to `serverWaitMinimum` ...
    /// `serverWaitMaximum`: a 0 is 1 s (as on Desktop) and a day is a
    /// minute. Nil when there is none to use: absent, negative or not
    /// finite, which the caller replaces with its own wait. Not
    /// `ConnectRetryAfter.clamped`: a connect refusal's wait keeps its own
    /// rule (a 0 is no wait there, and ten minutes the most).
    static func serverWait(seconds: Double?) -> TimeInterval? {
        guard let seconds, seconds.isFinite, seconds >= 0 else { return nil }
        return min(max(seconds, serverWaitMinimum), serverWaitMaximum)
    }

    /// The wait after the `failures`th refresh in a row that got no verdict:
    /// a ceiling of `backoffBase` doubled per failure, capped at `backoffCap`,
    /// and a random point in the ceiling's upper half (`random` in 0...1):
    /// Desktop's "equal jitter". The jitter keeps clients that failed
    /// together from coming back together; the lower bound keeps any wait
    /// from being zero.
    static func backoff(failures: Int, random: Double) -> TimeInterval {
        // 1 s doubled 5 times is past the cap; more doublings only risk an overflow.
        let doublings = min(max(failures, 1) - 1, 16)
        let ceiling = min(backoffBase * Double(1 << doublings), backoffCap)
        let draw = random.isNaN ? 0 : min(max(random, 0), 1)
        return ceiling / 2 + ceiling / 2 * draw
    }
}

/// While a wait runs, no refresh is sent: asking sooner only earns another
/// refusal. A 429's wait is the server's (MR-1771); any other answer that kept
/// the session, or no answer at all, waits the backoff (MR-2500). Every wait
/// is at most a minute (`RefreshRetry.serverWaitMaximum`). The wait, the
/// backoff count and the retry owed are the session's: a sign-out or a
/// sign-in replaces them with a fresh `RefreshHoldOff`
/// (`RefreshCoordinator.forgetSession`, L1).
struct RefreshHoldOff: Equatable, Sendable {
    private(set) var since: Date?
    private(set) var wait: TimeInterval = 0
    /// Refreshes in a row that got no verdict (a 5xx, a 403, no answer): the
    /// backoff's exponent. A 2xx or a 401 starts it over (`reset()`).
    private(set) var failures = 0
    /// The status a refresh held by this wait reports: 429 while a rate
    /// limit's wait runs, 503 during a backoff.
    private(set) var heldStatus = 503
    /// Whether the last refresh got no verdict (a 403, a 429, a 5xx, no
    /// answer) and nothing has rotated or ended the session since: a retry is
    /// owed, which the app pays when it comes to the foreground.
    private(set) var retryOwed = false

    /// Start holding at `now` for a 429's `wait` seconds.
    mutating func hold(for wait: TimeInterval, now: Date) {
        since = now
        self.wait = wait
        heldStatus = 429
        retryOwed = true
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
        retryOwed = true
        return wait
    }

    /// A 2xx or a 401: the next failure waits the first step again, and no
    /// retry is owed.
    mutating func reset() {
        failures = 0
        retryOwed = false
    }

    /// Whether the app coming to the foreground should retry the refresh at
    /// `now`: one is owed and its wait is over. Never sooner, and nothing
    /// retries on a timer.
    func retryIsDue(now: Date) -> Bool {
        retryOwed && !isHeld(now: now)
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
    /// The current wait, backoff count and retry owed. RefreshAnswerTests
    /// reads it.
    private(set) var holdOff = RefreshHoldOff()
    /// Which signed-in session the waits belong to: `forgetSession()` (a
    /// sign-out or a sign-in) moves it on.
    private var session: UInt64 = 0
    /// The `session` the refresh in flight was sent in.
    private var inFlightSession: UInt64 = 0
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
        inFlightSession = session
        let task = Task<Void, any Error> { try await work() }
        inFlight = task
        defer { inFlight = nil }
        try await task.value
    }

    /// A 429: hold every refresh for the server's `wait` seconds from now.
    func hold(for wait: TimeInterval) {
        guard waitIsThisSessions else { return }
        holdOff.hold(for: wait, now: Date())
    }

    /// A 5xx, a 403, a 426, an unreadable 2xx or no answer: hold every
    /// refresh for the next backoff step from now, never for less than
    /// `serverWait`. The jitter is uniform in 0..<1, as on Desktop.
    func backOff(serverWait: TimeInterval?) {
        guard waitIsThisSessions else { return }
        holdOff.backOff(serverWait: serverWait, random: Double.random(in: 0..<1), now: Date())
    }

    /// L1 (review of #653), as Desktop's `RetryGate::forget_session`: the
    /// session ended (a sign-out) or a new one began (a sign-in, with or
    /// without a sign-out first). Its wait, backoff count and retry owed go
    /// with it, so the next session's first refresh is sent, its first
    /// failure waits the first step, and its first foreground owes nothing.
    /// A refresh still in flight from before answers for the old session:
    /// the wait it records is dropped (`waitIsThisSessions`).
    func forgetSession() {
        session &+= 1
        holdOff = RefreshHoldOff()
    }

    /// Whether a wait recorded now is this session's: no refresh is in flight
    /// (a direct call), or the one in flight was sent in this session. A
    /// refresh sent before `forgetSession()` must not hold the next session.
    private var waitIsThisSessions: Bool {
        inFlight == nil || inFlightSession == session
    }

    /// A 2xx or a 401: the backoff starts over.
    func reset() {
        holdOff.reset()
    }

    /// MR-1771 / MR-2500: whether the app coming to the foreground should
    /// retry: a refresh is owed, none is in flight, and the wait is over.
    func retryIsDue() -> Bool {
        inFlight == nil && holdOff.retryIsDue(now: Date())
    }
}

// MARK: - MR-2532: a sign-out never refreshes

/// The tokens a sign-out ends on the server, read before the keychain wipe.
struct SignOutTokens: Sendable {
    let accessToken: String?
    let refreshToken: String?
}

/// MR-2532 [SIGNOUT-DURING-REFRESH-HOLD], owner decision 2026-11-20 (option
/// a): POST /auth/logout also takes the REFRESH token, in the body as
/// /auth/refresh takes it, and the server revokes exactly that session,
/// issuing nothing (birdo-web). A sign-out NEVER refreshes: a refresh held by
/// a 429 or a backoff cannot run, and one sent only to log out risks the
/// reuse response (a token a racing refresh already presented revokes every
/// session of the account past the server's 30 s rotation grace).
///
/// So the sign-out sends the bearer only while the access token is usable,
/// and the refresh token otherwise, or when the bearer is answered 401. A
/// refresh hold changes nothing here: with a usable access token the bearer
/// ends the session, and without one the refresh token does. Android:
/// `BirdoRepository.endServerSession`. The requests are APIClient's
/// (`signOut`); this decides them, Foundation-only so RefreshAnswerTests and
/// SignOutRevocationTests can pin it.
enum SignOutRevocation {
    /// An access token this close to its `exp` is not presented: it may
    /// expire on the way. Android `TokenManager.ACCESS_TOKEN_SKEW_SECONDS`.
    static let accessTokenSkew: TimeInterval = 30

    enum Plan: Equatable, Sendable {
        /// The bearer first; the refresh token follows only if it is answered 401.
        case bearerThenRefreshTokenOn401
        /// The refresh token at once: the access token cannot be presented.
        case refreshToken
        /// Nothing the server could act on.
        case nothing
    }

    static func plan(for tokens: SignOutTokens, now: Date) -> Plan {
        if let access = tokens.accessToken, accessTokenUsable(access, now: now) {
            return .bearerThenRefreshTokenOn401
        }
        return (tokens.refreshToken ?? "").isEmpty ? .nothing : .refreshToken
    }

    /// After the bearer's answer: only a 401 says it could not end the
    /// session. Any other answer, or none, ends the sign-out's server half.
    static func refreshTokenAfterBearer(status: Int?, hasRefreshToken: Bool) -> Bool {
        status == 401 && hasRefreshToken
    }

    /// The JWT's `exp` is more than `accessTokenSkew` after `now`. A token
    /// whose payload or `exp` cannot be read is not usable.
    static func accessTokenUsable(_ jwt: String, now: Date) -> Bool {
        let segments = jwt.split(separator: ".", omittingEmptySubsequences: false)
        guard segments.count == 3 else { return false }
        var payload = String(segments[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while payload.count % 4 != 0 { payload += "=" }
        guard let data = Data(base64Encoded: payload),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let exp = (object["exp"] as? NSNumber)?.doubleValue else {
            return false
        }
        return exp - now.timeIntervalSince1970 > accessTokenSkew
    }

    /// A refresh whose rotated pair the fence did not store (a sign-out, a
    /// sign-in or another refresh landed while it was on the wire). Its
    /// session is revoked with the refresh token it was handed only when NO
    /// session is stored now, i.e. the user signed out: the sign-out itself
    /// could only present the consumed token. With a session stored (a
    /// sign-in since, or a rotation) nothing is revoked: for a device-scoped
    /// session the server ends that device's whole lineage, a new session
    /// included (review of #666, L3). Read from the keychain, not from the
    /// session generation, whose bump runs after the wipe (L4).
    static func revokesLateRotation(sessionStored: Bool) -> Bool {
        !sessionStored
    }

    /// MR-2532, owner decision 2026-11-24: the sign-out's peer release is
    /// sent only with an access token that is still usable, never through a
    /// refresh. Otherwise the server's stale-connection sweep frees the slot.
    static func releasesPeer(bearer: String?, now: Date) -> Bool {
        guard let bearer else { return false }
        return accessTokenUsable(bearer, now: now)
    }

    /// The sign-out's peer release never holds the sign-out longer than this
    /// (review of #666, M3; Android `SIGN_OUT_RELEASE_TIMEOUT_MS`).
    static let peerReleaseTimeout: TimeInterval = 10
}
