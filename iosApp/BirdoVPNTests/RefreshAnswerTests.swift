import XCTest

/// MR-1771 — a refresh 429 keeps the session on iOS.
///
/// ## The gap these pin
///
/// birdo-web #739 (live since web 32ab2b23) answers every rate limit on
/// `POST /auth/refresh` with 429 and `Retry-After`; it used to be a 401, which
/// every client read as "the refresh token is dead" and signed the user out
/// over. `APIClient.refreshTokens()` turns an answer into
/// `APIError.unauthorized` — the one error `AuthViewModel.logoutIfUnauthorized`
/// signs out on and wipes the keychain for — only when `RefreshAnswer` says
/// `.dead`, so "a 429 keeps the stored session" is "a 429 is never `.dead`".
/// Before, a 429 was already transient, but its `Retry-After` was thrown away
/// and the next 401 asked again at once (MR-2063 overlap): `RefreshHoldOff`
/// is the wait `RefreshCoordinator` now keeps.
///
/// A 403 still signs out (lead decision 2026-10-23), as before.
///
/// That `refreshTokens()` and `RefreshCoordinator` apply these cannot be
/// asserted from this bundle: APIClient.swift does not compile into it.
final class RefreshAnswerTests: XCTestCase {

    // MARK: - What each status means

    func testOnlyA401OrA403IsTheTokensDeath() {
        for status in 100...599 {
            let answer = RefreshAnswer.classify(status: status, retryAfterHeader: "60")
            if status == 401 || status == 403 {
                XCTAssertEqual(answer, .dead, "\(status)")
            } else if (200...299).contains(status) {
                XCTAssertEqual(answer, .rotated, "\(status)")
            } else if case .keepSession(_) = answer {
                // kept
            } else {
                XCTFail("\(status) must keep the session, got \(answer)")
            }
        }
    }

    func testA429KeepsTheSessionAndWaitsTheServersRetryAfter() {
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: "60"), .keepSession(retryAfter: 60))
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: " 15 "), .keepSession(retryAfter: 15))
    }

    func testA429WithNoUsableWaitStillWaitsAndAHugeOneIsClamped() {
        let fallback = RefreshAnswer.keepSession(retryAfter: ConnectRetryAfter.retryableDefault)
        // No header, an HTTP date (the device clock is not the server's), zero, junk.
        for header in [nil, "Wed, 21 Oct 2026 07:28:00 GMT", "0", "-5", "soon"] as [String?] {
            XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: header), fallback,
                           "Retry-After: \(String(describing: header))")
        }
        XCTAssertEqual(RefreshAnswer.classify(status: 429, retryAfterHeader: "86400"),
                       .keepSession(retryAfter: ConnectRetryAfter.maximum))
    }

    func testA5xxKeepsTheSessionAndWaitsOnlyWhenTold() {
        XCTAssertEqual(RefreshAnswer.classify(status: 503, retryAfterHeader: "20"), .keepSession(retryAfter: 20))
        XCTAssertEqual(RefreshAnswer.classify(status: 503, retryAfterHeader: nil), .keepSession(retryAfter: nil))
        XCTAssertEqual(RefreshAnswer.classify(status: 500, retryAfterHeader: nil), .keepSession(retryAfter: nil))
    }

    func testA401AndA403StillSignOut() {
        XCTAssertEqual(RefreshAnswer.classify(status: 401, retryAfterHeader: nil), .dead)
        // Lead decision 2026-10-23: unchanged by MR-1771.
        XCTAssertEqual(RefreshAnswer.classify(status: 403, retryAfterHeader: nil), .dead)
        XCTAssertEqual(RefreshAnswer.classify(status: 200, retryAfterHeader: nil), .rotated)
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
}
