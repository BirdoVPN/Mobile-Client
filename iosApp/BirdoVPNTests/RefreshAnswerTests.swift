import XCTest

/// MR-1771 / MR-2500 — one refresh rule on iOS: only a 401 ends the session.
///
/// ## The gap these pin
///
/// birdo-web answers every rate limit on `POST /auth/refresh` with 429,
/// `Retry-After` and (web #912) `details.retryAfter`; a 503 says the token is
/// intact (a session-store outage; once the server half of MR-2500 lands, also
/// a rotation that failed and restored it). `APIClient.refreshTokens()` turns
/// an answer into
/// `APIError.unauthorized` — the one error `AuthViewModel.logoutIfUnauthorized`
/// signs out on and wipes the keychain for — only when `RefreshAnswer` says
/// `.dead`, so "a 429/403/5xx keeps the stored session" is "only a 401 is
/// `.dead`". Before, a 403 was `.dead` too, a 429 that named no wait in its
/// header waited 30 s whatever its body said, and a 5xx or a timeout held
/// nothing, so every request's 401 sent the same token again at once.
///
/// The waits are one table with Desktop #467 (lead decision L2, 2026-11-19):
/// a backoff of 1 s doubled, capped at 30 s; a named wait clamped to
/// 1 s ... 60 s, a named 0 being 1 s. A sign-out or a sign-in forgets them
/// (L1, `RefreshCoordinator.forgetSession`).
///
/// `RefreshCoordinator` (single flight, and nothing sent while a wait runs)
/// is tested here directly. That `refreshTokens()` applies the answers is
/// pinned by IosRefreshWiringTest on the Android side: APIClient.swift does
/// not compile into this bundle.
final class RefreshAnswerTests: XCTestCase {

    // MARK: - What each status means

    func testOnlyA401IsTheTokensDeath() {
        for status in 100...599 {
            let answer = RefreshAnswer.classify(status: status, retryAfterHeader: "60")
            if status == 401 {
                XCTAssertEqual(answer, .dead, "\(status)")
            } else if (200...299).contains(status) {
                XCTAssertEqual(answer, .rotated, "\(status)")
            } else {
                XCTAssertNotEqual(answer, .dead, "\(status) must keep the session")
            }
        }
    }

    func testA403KeepsTheSession() {
        // MR-1771: it was `.dead` (lead decision 2026-10-23) and signed out.
        XCTAssertEqual(RefreshAnswer.classify(status: 403, retryAfterHeader: nil), .refused)
        XCTAssertEqual(RefreshAnswer.classify(status: 403, retryAfterHeader: "60"), .refused)
    }

    func testA429WaitsTheHeaderThenDetailsRetryAfterThenSixtySeconds() {
        let body = Data(#"{"statusCode":429,"error":"Too Many Requests","details":{"code":"rate_limited","retryAfter":42}}"#.utf8)
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: "15", body: body), .rateLimited(retryAfter: 15))
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: " 15 ", body: nil), .rateLimited(retryAfter: 15))
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: nil, body: body), .rateLimited(retryAfter: 42))
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: nil, body: nil), .rateLimited(retryAfter: 60))
        XCTAssertEqual(RefreshRetry.rateLimitedDefault, 60)
    }

    func testA429WithNoUsableWaitWaitsSixtySeconds() {
        let fallback = RefreshAnswer.rateLimited(retryAfter: RefreshRetry.rateLimitedDefault)
        // No body, not JSON, no details, a negative, a string, null, and the
        // connect refusals' `retryAfterSeconds` (not this route's key).
        let bodies: [Data?] = [
            nil,
            Data(),
            Data("<html>429</html>".utf8),
            Data(#"{"statusCode":429,"message":"Too many requests"}"#.utf8),
            Data(#"{"details":{"retryAfter":-5}}"#.utf8),
            Data(#"{"details":{"retryAfter":"soon"}}"#.utf8),
            Data(#"{"details":{"retryAfter":null}}"#.utf8),
            Data(#"{"details":{"retryAfterSeconds":42}}"#.utf8),
        ]
        // No header, an HTTP date (the device clock is not the server's), a
        // negative, junk, a fraction, NaN and an Int overflow.
        let headers: [String?] = [nil, "Wed, 21 Oct 2026 07:28:00 GMT", "-5", "soon", "1.5", "NaN",
                                  "99999999999999999999999"]
        for header in headers {
            for (i, body) in bodies.enumerated() {
                XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: header, body: body), fallback,
                               "Retry-After: \(String(describing: header)), body #\(i)")
            }
        }
    }

    /// L2 (one table with Desktop #467): a named 0 is 1 s, in the header or
    /// the body. It was read as no wait at all, so a 429 waited 60 s.
    func testA429ThatNamesZeroWaitsOneSecond() {
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: "0"), .rateLimited(retryAfter: 1))
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: nil,
                                              body: Data(#"{"details":{"retryAfter":0}}"#.utf8)),
                       .rateLimited(retryAfter: 1))
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: "0",
                                              body: Data(#"{"details":{"retryAfter":42}}"#.utf8)),
                       .rateLimited(retryAfter: 1), "the header wins")
        XCTAssertEqual(RefreshAnswer.classify(status: 503, retryAfterHeader: "0"), .unavailable(retryAfter: 1))
    }

    /// L2: a named wait is a minute at most (it was ten), in the header, the
    /// body, and on a 5xx.
    func testANamedWaitIsClampedAtSixtySeconds() {
        XCTAssertEqual(RefreshRetry.serverWaitMaximum, 60)
        XCTAssertEqual(RefreshRetry.serverWaitMinimum, 1)
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: "60"), .rateLimited(retryAfter: 60))
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: "61"), .rateLimited(retryAfter: 60))
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: "86400"), .rateLimited(retryAfter: 60))
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: nil,
                                              body: Data(#"{"details":{"retryAfter":86400}}"#.utf8)),
                       .rateLimited(retryAfter: 60))
        XCTAssertEqual(RefreshAnswer.classify(status: 503, retryAfterHeader: "86400"), .unavailable(retryAfter: 60))
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: "59"), .rateLimited(retryAfter: 59))

        XCTAssertNil(RefreshRetry.serverWait(seconds: nil))
        XCTAssertNil(RefreshRetry.serverWait(seconds: -0.5))
        XCTAssertNil(RefreshRetry.serverWait(seconds: .nan))
        XCTAssertNil(RefreshRetry.serverWait(seconds: .infinity))
        XCTAssertEqual(RefreshRetry.serverWait(seconds: 0), 1)
        XCTAssertEqual(RefreshRetry.serverWait(seconds: 0.5), 1)
        XCTAssertEqual(RefreshRetry.serverWait(seconds: 30), 30)
        XCTAssertEqual(RefreshRetry.serverWait(seconds: .greatestFiniteMagnitude), 60)
        // The connect refusals keep their own rule: ten minutes, and a 0 is no wait.
        XCTAssertEqual(ConnectRetryAfter.maximum, 600)
        XCTAssertNil(ConnectRetryAfter.clamped(seconds: 0))
    }

    func testA5xxOrAnUnexpectedStatusKeepsTheSessionAndCarriesTheServersWait() {
        XCTAssertEqual(RefreshAnswer.classify(status: 503, retryAfterHeader: "10"), .unavailable(retryAfter: 10))
        XCTAssertEqual(RefreshAnswer.classify(status: 503, retryAfterHeader: nil), .unavailable(retryAfter: nil))
        XCTAssertEqual(RefreshAnswer.classify(status: 500, retryAfterHeader: nil), .unavailable(retryAfter: nil))
        XCTAssertEqual(RefreshAnswer.classify(status: 404, retryAfterHeader: nil), .unavailable(retryAfter: nil))
        XCTAssertEqual(RefreshAnswer.classify(status: 200, retryAfterHeader: nil), .rotated)
    }

    /// Lead decision L2 (2026-11-19): a 426 keeps the session and backs off
    /// like any unexpected status, and `refreshTokens()` surfaces it as its
    /// own 426 (IosRefreshWiringTest pins that `.unavailable` backs off, that
    /// a pin failure, which is no answer, backs off, and that a 2xx whose pair
    /// cannot be read backs off too).
    func testA426KeepsTheSessionAndBacksOff() {
        XCTAssertEqual(RefreshAnswer.classify(status: 426, retryAfterHeader: nil), .unavailable(retryAfter: nil))
        XCTAssertEqual(RefreshAnswer.classify(status: 426, retryAfterHeader: "5"), .unavailable(retryAfter: 5))
    }

    // MARK: - The backoff (MR-2500)

    func testTheBackoffDoublesFromOneSecondIsCappedAtThirtySecondsAndJittersInTheUpperHalf() {
        XCTAssertEqual(RefreshRetry.backoffBase, 1)
        XCTAssertEqual(RefreshRetry.backoffCap, 30)
        // Desktop's own pin, word for word: at a draw of 0, 0.5 1 2 4 8 15 15 s.
        XCTAssertEqual((1...7).map { RefreshRetry.backoff(failures: $0, random: 0) }, [0.5, 1, 2, 4, 8, 15, 15])
        let ceilings: [TimeInterval] = [1, 2, 4, 8, 16, 30, 30, 30]
        for (i, ceiling) in ceilings.enumerated() {
            let failures = i + 1
            XCTAssertEqual(RefreshRetry.backoff(failures: failures, random: 0), ceiling / 2, "failures=\(failures)")
            XCTAssertEqual(RefreshRetry.backoff(failures: failures, random: 1), ceiling, "failures=\(failures)")
            let mid = RefreshRetry.backoff(failures: failures, random: 0.5)
            XCTAssertTrue((ceiling / 2...ceiling).contains(mid), "failures=\(failures): \(mid)")
        }
    }

    func testTheBackoffIsNeverZeroAndNeverPastTheCap() {
        let draws: [Double] = [-1, 0, 0.999, 1, 7, .nan]
        for failures in [Int.min, -1, 0, 1, 50, 1_000, Int.max] {
            for draw in draws {
                let wait = RefreshRetry.backoff(failures: failures, random: draw)
                XCTAssertTrue((RefreshRetry.backoffBase / 2...RefreshRetry.backoffCap).contains(wait),
                              "failures=\(failures) draw=\(draw): \(wait)")
            }
        }
    }

    func testABackoffHoldsNeverLessThanTheServersWaitAndAResetStartsItOver() {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        var hold = RefreshHoldOff()
        let first = hold.backOff(serverWait: 10, random: 0, now: start)
        XCTAssertEqual(first, 10, "the server's 10 s outweighs the first step (0.5 s)")
        XCTAssertTrue(hold.isHeld(now: start.addingTimeInterval(9.9)))
        XCTAssertFalse(hold.isHeld(now: start.addingTimeInterval(10)))
        XCTAssertEqual(hold.heldStatus, 503)
        var steps: [TimeInterval] = []
        for _ in 0..<3 {
            steps.append(hold.backOff(serverWait: nil, random: 0, now: start))
        }
        XCTAssertEqual(steps, [1, 2, 4], "the 2nd, 3rd and 4th failure in a row")
        XCTAssertEqual(hold.failures, 4)

        hold.reset()
        XCTAssertEqual(hold.failures, 0)
        let afterReset = hold.backOff(serverWait: nil, random: 0, now: start)
        XCTAssertEqual(afterReset, 0.5, "a 2xx or a 401 starts it over")

        // A 429's wait is the server's, and a refresh held by it reports 429.
        hold.hold(for: 60, now: start)
        XCTAssertEqual(hold.heldStatus, 429)
        XCTAssertTrue(hold.isHeld(now: start.addingTimeInterval(59)))
    }

    // MARK: - The foreground retry (lead, 2026-11-19: no timer)

    func testARetryIsOwedAfterAnyWaitAndDueOnlyOnceTheWaitIsOver() {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        var hold = RefreshHoldOff()
        XCTAssertFalse(hold.retryIsDue(now: start), "nothing owed before a refresh failed")

        hold.backOff(serverWait: 10, random: 0, now: start)
        XCTAssertTrue(hold.retryOwed)
        XCTAssertFalse(hold.retryIsDue(now: start.addingTimeInterval(9.9)), "never before the wait is over")
        XCTAssertTrue(hold.retryIsDue(now: start.addingTimeInterval(10)))

        hold.reset()
        XCTAssertFalse(hold.retryIsDue(now: start.addingTimeInterval(10)), "a 2xx or a 401 owes nothing")

        hold.hold(for: 60, now: start)
        XCTAssertFalse(hold.retryIsDue(now: start.addingTimeInterval(59)))
        XCTAssertTrue(hold.retryIsDue(now: start.addingTimeInterval(60)))
    }

    func testTheForegroundRetryIsDueOnlyWhenOwedAndNoLongerHeld() async throws {
        let coordinator = RefreshCoordinator(heldError: { HeldRefresh(status: $0) })
        let nothingOwed = await coordinator.retryIsDue()
        XCTAssertFalse(nothingOwed, "nothing owed: the foreground sends nothing")

        await coordinator.backOff(serverWait: 10)
        let stillHeld = await coordinator.retryIsDue()
        XCTAssertFalse(stillHeld, "owed, but the wait still runs")

        await coordinator.hold(for: 0) // a wait that is already over
        let due = await coordinator.retryIsDue()
        XCTAssertTrue(due)

        // The retry rotates: what refreshTokens() does on a 2xx.
        try await coordinator.refresh { await coordinator.reset() }
        let paid = await coordinator.retryIsDue()
        XCTAssertFalse(paid, "rotated: nothing owed any more")
    }

    // MARK: - The hold

    func testNoRefreshIsSentBeforeTheWaitIsOver() {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        var hold = RefreshHoldOff()
        XCTAssertFalse(hold.isHeld(now: start), "nothing held before a 429")

        hold.hold(for: 60, now: start)
        XCTAssertTrue(hold.isHeld(now: start))
        XCTAssertTrue(hold.isHeld(now: start.addingTimeInterval(59)))
        XCTAssertFalse(hold.isHeld(now: start.addingTimeInterval(60)), "the wait is over: refresh again")
    }

    func testAClockSetBackEndsTheHoldRatherThanStretchingIt() {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        var hold = RefreshHoldOff()
        hold.hold(for: RefreshRetry.serverWaitMaximum, now: start)
        XCTAssertFalse(hold.isHeld(now: start.addingTimeInterval(-1)))
        XCTAssertFalse(hold.isHeld(now: start.addingTimeInterval(RefreshRetry.serverWaitMaximum)))
    }

    func testANewWaitReplacesTheOldOne() {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        var hold = RefreshHoldOff()
        hold.hold(for: 600, now: start)
        hold.hold(for: 10, now: start.addingTimeInterval(5))
        XCTAssertTrue(hold.isHeld(now: start.addingTimeInterval(14)))
        XCTAssertFalse(hold.isHeld(now: start.addingTimeInterval(15)))
    }

    // MARK: - The coordinator: one refresh in flight, nothing sent while held

    func testConcurrentCallersShareTheOneRefreshInFlight() async throws {
        let coordinator = RefreshCoordinator(heldError: { HeldRefresh(status: $0) })
        let sent = RefreshSentCount()
        let release = RefreshRelease()
        let work: @Sendable () async throws -> Void = {
            await sent.bump()
            while !(await release.isSet) {
                try await Task.sleep(nanoseconds: 1_000_000)
            }
        }

        let first = Task { try await coordinator.refresh(work) }
        try await eventually("the first refresh to be on the wire") { await sent.value == 1 }
        let others = (0..<3).map { _ in Task { try await coordinator.refresh(work) } }
        try await eventually("three callers to join it") { await coordinator.joined == 3 }
        await release.set()
        try await first.value
        for task in others {
            try await task.value
        }

        let count = await sent.value
        XCTAssertEqual(count, 1, "one refresh on the wire, however many callers")
    }

    /// MR-2500: a refresh that got a 503 keeps the session for everyone who
    /// was waiting on it, and the next caller sends nothing until the backoff
    /// is over.
    func testCallersShareAFailedRefreshsErrorAndTheNextOneIsHeld() async throws {
        let coordinator = RefreshCoordinator(heldError: { HeldRefresh(status: $0) })
        let sent = RefreshSentCount()
        let release = RefreshRelease()
        let work: @Sendable () async throws -> Void = {
            await sent.bump()
            while !(await release.isSet) {
                try await Task.sleep(nanoseconds: 1_000_000)
            }
            // What refreshTokens() does with a 503 and Retry-After: 10.
            await coordinator.backOff(serverWait: 10)
            throw RefreshUnavailable()
        }

        let first = Task { try await coordinator.refresh(work) }
        try await eventually("the first refresh to be on the wire") { await sent.value == 1 }
        let others = (0..<3).map { _ in Task { try await coordinator.refresh(work) } }
        try await eventually("three callers to join it") { await coordinator.joined == 3 }
        await release.set()
        for task in [first] + others {
            do {
                try await task.value
                XCTFail("a failed refresh must fail every caller that shared it")
            } catch is RefreshUnavailable {
                // shared
            }
        }

        do {
            try await coordinator.refresh(work)
            XCTFail("a refresh that is backing off must not be sent")
        } catch let held as HeldRefresh {
            XCTAssertEqual(held.status, 503)
        }
        let count = await sent.value
        XCTAssertEqual(count, 1, "nothing re-sent while the backoff runs")
    }

    func testNothingIsSentWhileA429sWaitRunsAndTheCallerIsToldWhy() async throws {
        let coordinator = RefreshCoordinator(heldError: { HeldRefresh(status: $0) })
        let sent = RefreshSentCount()
        let work: @Sendable () async throws -> Void = { await sent.bump() }

        await coordinator.hold(for: 60)
        do {
            try await coordinator.refresh(work)
            XCTFail("a held refresh must not be sent")
        } catch let held as HeldRefresh {
            XCTAssertEqual(held.status, 429)
        }
        let held = await sent.value
        XCTAssertEqual(held, 0)

        // Nothing held: the refresh is sent.
        let free = RefreshCoordinator(heldError: { HeldRefresh(status: $0) })
        try await free.refresh(work)
        let ran = await sent.value
        XCTAssertEqual(ran, 1)
    }

    // MARK: - L1: the waits are the session's

    /// Builds a held refresh on `coordinator`: three failures in a row (the
    /// backoff three steps deep), then a 429 that holds a minute with a retry
    /// owed. What `refreshTokens()` records for those answers.
    private func holdTheRefresh(_ coordinator: RefreshCoordinator) async throws {
        for _ in 0..<3 {
            await coordinator.backOff(serverWait: nil)
        }
        await coordinator.hold(for: 60)
        let holdOff = await coordinator.holdOff
        XCTAssertEqual(holdOff.failures, 3)
        XCTAssertTrue(holdOff.retryOwed)
        let sent = RefreshSentCount()
        do {
            try await coordinator.refresh { await sent.bump() }
            XCTFail("the old session's refresh is not held")
        } catch let held as HeldRefresh {
            XCTAssertEqual(held.status, 429)
        }
        let count = await sent.value
        XCTAssertEqual(count, 0, "a held refresh was sent")
    }

    /// The next session, still inside the old minute: its foreground owes
    /// nothing, its first refresh is sent, and that refresh's failure is the
    /// backoff's first step (failures 1), not the old session's fourth.
    private func assertTheNewSessionIsNotHeld(_ coordinator: RefreshCoordinator) async throws {
        let owed = await coordinator.retryIsDue()
        XCTAssertFalse(owed, "the old session's retry is still owed")
        let forgotten = await coordinator.holdOff
        XCTAssertEqual(forgotten, RefreshHoldOff())
        let sent = RefreshSentCount()
        do {
            try await coordinator.refresh {
                await sent.bump()
                await coordinator.backOff(serverWait: nil) // a 503
                throw RefreshUnavailable()
            }
            XCTFail("the refresh's 503 was not passed on")
        } catch is RefreshUnavailable {
            // sent, and failed
        }
        let count = await sent.value
        XCTAssertEqual(count, 1, "the new session's first refresh was held by the old session's wait")
        let holdOff = await coordinator.holdOff
        XCTAssertEqual(holdOff.failures, 1, "the new session's first failure is the first step")
        XCTAssertLessThanOrEqual(holdOff.wait, RefreshRetry.backoffBase)
    }

    /// L1 (review of #653), as Desktop's `forget_session`: a hold, then a
    /// sign-out (`APIClient.invalidateSession`), then a sign-in
    /// (`APIClient.beginSession`): the first refresh is not held. Before, the
    /// coordinator kept the old session's wait, backoff and retry owed.
    func testAHoldDoesNotSurviveASignOutAndTheNextSignIn() async throws {
        let coordinator = RefreshCoordinator(heldError: { HeldRefresh(status: $0) })
        try await holdTheRefresh(coordinator)

        await coordinator.forgetSession() // sign-out
        await coordinator.forgetSession() // sign-in

        try await assertTheNewSessionIsNotHeld(coordinator)
    }

    /// L1: the same for a sign-in with no sign-out before it.
    func testAHoldDoesNotSurviveASignInWithoutASignOut() async throws {
        let coordinator = RefreshCoordinator(heldError: { HeldRefresh(status: $0) })
        try await holdTheRefresh(coordinator)

        await coordinator.forgetSession() // sign-in

        try await assertTheNewSessionIsNotHeld(coordinator)
    }

    /// L1: a refresh sent before a sign-out and a sign-in answers for the old
    /// session. Its 503 must not hold the new session nor leave a retry owed.
    func testARefreshInFlightAcrossASignOutLeavesNoHoldOnTheNextSession() async throws {
        let coordinator = RefreshCoordinator(heldError: { HeldRefresh(status: $0) })
        let sent = RefreshSentCount()
        let release = RefreshRelease()
        let stale = Task {
            try await coordinator.refresh {
                await sent.bump()
                while !(await release.isSet) {
                    try await Task.sleep(nanoseconds: 1_000_000)
                }
                await coordinator.backOff(serverWait: 30) // the old session's 503
                throw RefreshUnavailable()
            }
        }
        try await eventually("the old session's refresh to be on the wire") { await sent.value == 1 }
        await coordinator.forgetSession() // sign-out
        await coordinator.forgetSession() // sign-in
        await release.set()
        do {
            try await stale.value
            XCTFail("the old refresh's 503 was not passed on")
        } catch is RefreshUnavailable {
            // its caller is still told
        }

        let after = await coordinator.holdOff
        XCTAssertEqual(after, RefreshHoldOff(), "the old session's 503 held the new one")
        let owed = await coordinator.retryIsDue()
        XCTAssertFalse(owed)
        try await coordinator.refresh { await sent.bump() }
        let count = await sent.value
        XCTAssertEqual(count, 2, "the new session's refresh was held")
    }

    /// Polls `condition` for up to five seconds.
    private func eventually(_ what: String, _ condition: () async -> Bool) async throws {
        for _ in 0..<5_000 {
            if await condition() { return }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("timed out waiting for \(what)")
    }
}

/// The error a held refresh throws in these tests (APIClient's is `APIError.httpError`).
private struct HeldRefresh: Error, Equatable {
    let status: Int
}

/// A refresh that got no verdict, as `refreshTokens()` throws it.
private struct RefreshUnavailable: Error {}

private actor RefreshSentCount {
    private(set) var value = 0

    func bump() {
        value += 1
    }
}

private actor RefreshRelease {
    private(set) var isSet = false

    func set() {
        isSet = true
    }
}
