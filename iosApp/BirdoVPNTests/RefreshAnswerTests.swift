import XCTest

/// MR-1771 / MR-2500 — one refresh rule on iOS: only a 401 ends the session.
///
/// ## The gap these pin
///
/// birdo-web answers every rate limit on `POST /auth/refresh` with 429,
/// `Retry-After` and (web #912) `details.retryAfter`; a 503 says the token is
/// intact (a session-store outage, or a rotation that failed and restored it,
/// MR-2500). `APIClient.refreshTokens()` turns an answer into
/// `APIError.unauthorized` — the one error `AuthViewModel.logoutIfUnauthorized`
/// signs out on and wipes the keychain for — only when `RefreshAnswer` says
/// `.dead`, so "a 429/403/5xx keeps the stored session" is "only a 401 is
/// `.dead`". Before, a 403 was `.dead` too, a 429 that named no wait in its
/// header waited 30 s whatever its body said, and a 5xx or a timeout held
/// nothing, so every request's 401 sent the same token again at once.
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

    func testA429WithNoUsableWaitWaitsSixtySecondsAndAHugeOneIsClamped() {
        let fallback = RefreshAnswer.rateLimited(retryAfter: RefreshRetry.rateLimitedDefault)
        // No body, not JSON, no details, a zero, a string, and the connect
        // refusals' `retryAfterSeconds` (not this route's key).
        let bodies: [Data?] = [
            nil,
            Data(),
            Data("<html>429</html>".utf8),
            Data(#"{"statusCode":429,"message":"Too many requests"}"#.utf8),
            Data(#"{"details":{"retryAfter":0}}"#.utf8),
            Data(#"{"details":{"retryAfter":"soon"}}"#.utf8),
            Data(#"{"details":{"retryAfterSeconds":42}}"#.utf8),
        ]
        // No header, an HTTP date (the device clock is not the server's), zero, junk.
        let headers: [String?] = [nil, "Wed, 21 Oct 2026 07:28:00 GMT", "0", "-5", "soon"]
        for header in headers {
            for (i, body) in bodies.enumerated() {
                XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: header, body: body), fallback,
                               "Retry-After: \(String(describing: header)), body #\(i)")
            }
        }
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: "86400"),
                       .rateLimited(retryAfter: ConnectRetryAfter.maximum))
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: nil,
                                              body: Data(#"{"details":{"retryAfter":86400}}"#.utf8)),
                       .rateLimited(retryAfter: ConnectRetryAfter.maximum))
    }

    func testA5xxOrAnUnexpectedStatusKeepsTheSessionAndCarriesTheServersWait() {
        XCTAssertEqual(RefreshAnswer.classify(status: 503, retryAfterHeader: "10"), .unavailable(retryAfter: 10))
        XCTAssertEqual(RefreshAnswer.classify(status: 503, retryAfterHeader: nil), .unavailable(retryAfter: nil))
        XCTAssertEqual(RefreshAnswer.classify(status: 500, retryAfterHeader: nil), .unavailable(retryAfter: nil))
        XCTAssertEqual(RefreshAnswer.classify(status: 404, retryAfterHeader: nil), .unavailable(retryAfter: nil))
        XCTAssertEqual(RefreshAnswer.classify(status: 200, retryAfterHeader: nil), .rotated)
    }

    // MARK: - The backoff (MR-2500)

    func testTheBackoffDoublesFromTwoSecondsIsCappedAtFiveMinutesAndJittersInTheUpperHalf() {
        let ceilings: [TimeInterval] = [2, 4, 8, 16, 32, 64, 128, 256, 300, 300]
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
        XCTAssertEqual(first, 10, "the server's 10 s outweighs the first step (1 s)")
        XCTAssertTrue(hold.isHeld(now: start.addingTimeInterval(9.9)))
        XCTAssertFalse(hold.isHeld(now: start.addingTimeInterval(10)))
        XCTAssertEqual(hold.heldStatus, 503)
        var steps: [TimeInterval] = []
        for _ in 0..<3 {
            steps.append(hold.backOff(serverWait: nil, random: 0, now: start))
        }
        XCTAssertEqual(steps, [2, 4, 8], "the 2nd, 3rd and 4th failure in a row")
        XCTAssertEqual(hold.failures, 4)

        hold.reset()
        XCTAssertEqual(hold.failures, 0)
        let afterReset = hold.backOff(serverWait: nil, random: 0, now: start)
        XCTAssertEqual(afterReset, 1, "a 2xx or a 401 starts it over")

        // A 429's wait is the server's, and a refresh held by it reports 429.
        hold.hold(for: 60, now: start)
        XCTAssertEqual(hold.heldStatus, 429)
        XCTAssertTrue(hold.isHeld(now: start.addingTimeInterval(59)))
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
        hold.hold(for: ConnectRetryAfter.maximum, now: start)
        XCTAssertFalse(hold.isHeld(now: start.addingTimeInterval(-1)))
        XCTAssertFalse(hold.isHeld(now: start.addingTimeInterval(ConnectRetryAfter.maximum)))
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
