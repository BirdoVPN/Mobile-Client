import XCTest

/// P1-ios-redial-loop-blackhole — the re-dial circuit breaker in
/// `TunnelCircuitBreaker.swift`, compiled directly into this un-hosted bundle
/// (no app host, no KMP framework, no WireGuardKit — the store's keychain half
/// is deliberately kept in a separate file so this stays testable on CI).
///
/// These tests carry more weight than usual because the bug needs a revoked
/// peer or a dead node on real hardware to observe: nobody is going to re-run
/// that by hand on every release. So every branch that decides "keep
/// re-dialling" vs "stop" is pinned here, and so is every FAIL-OPEN path — a
/// breaker that trips when it shouldn't, or that never un-trips, is a worse bug
/// than the loop it replaces.
final class TunnelCircuitBreakerTests: XCTestCase {

    private let node = "de1.birdo.app"
    private let otherNode = "nl1.birdo.app"
    private let t0 = Date(timeIntervalSince1970: 1_770_000_000)

    /// Apply `count` failures of one kind, one minute apart (comfortably inside
    /// the rolling window), starting at `t0`.
    private func run(
        _ kind: TunnelFailureKind,
        count: Int,
        nodeId: String? = nil,
        from start: Date? = nil,
        into initial: TunnelBreakerRecord? = nil
    ) -> TunnelBreakerRecord {
        var record = initial
        let base = start ?? t0
        for i in 0..<count {
            record = TunnelCircuitBreaker.recordFailure(
                kind,
                nodeId: nodeId ?? node,
                now: base.addingTimeInterval(Double(i) * 60),
                into: record
            )
        }
        return record!
    }

    // MARK: - Budgets differ by failure kind (requirement 2)

    func testDiedAfterHandshakeGetsTheLargestBudget() {
        // A handshake that existed and went stale is a NAT rebind / node restart
        // / roaming path change. Re-dialling usually WORKS, so the breaker must
        // not cut it off early — that would turn a self-healing blip into a
        // manual reconnect.
        let three = run(.diedAfterHandshake, count: 3)
        XCTAssertEqual(three.consecutiveFailures, 3)
        XCTAssertFalse(TunnelCircuitBreaker.isTripped(three, now: t0.addingTimeInterval(180)),
                       "3 stale-handshake failures must still be under budget")

        let four = run(.diedAfterHandshake, count: 4)
        XCTAssertTrue(TunnelCircuitBreaker.isTripped(four, now: t0.addingTimeInterval(240)),
                      "the 4th consecutive stale-handshake failure must trip")
    }

    func testNeverEstablishedGetsASmallBudget() {
        // The tunnel came up and never handshaked. Re-dialling the SAME endpoint
        // cannot fix a blocked path or a peer that was never installed, so the
        // budget is deliberately tighter than diedAfterHandshake.
        let one = run(.neverEstablished, count: 1)
        XCTAssertFalse(TunnelCircuitBreaker.isTripped(one, now: t0))

        let two = run(.neverEstablished, count: 2)
        XCTAssertTrue(TunnelCircuitBreaker.isTripped(two, now: t0.addingTimeInterval(60)),
                      "the 2nd never-established failure must trip")
    }

    func testRevokedTripsOnTheVeryFirstFailure() {
        // The server deleted the peer. There is nothing to hand shake with, so
        // every automatic re-dial is guaranteed to fail — one is already one too
        // many. Recovery is a fresh /vpn/connect, which is host-app work.
        let record = run(.revoked, count: 1)
        XCTAssertEqual(record.consecutiveFailures, 1)
        XCTAssertNotNil(record.trippedAt)
        XCTAssertTrue(TunnelCircuitBreaker.isTripped(record, now: t0))
    }

    func testABudgetIsSpentByTheKindOfTheLatestFailure() {
        // A run that degrades into a revocation must trip AT ONCE rather than
        // riding out the larger stale-handshake budget it started on.
        let died = run(.diedAfterHandshake, count: 1)
        XCTAssertFalse(TunnelCircuitBreaker.isTripped(died, now: t0))

        let revoked = TunnelCircuitBreaker.recordFailure(
            .revoked, nodeId: node, now: t0.addingTimeInterval(60), into: died
        )
        XCTAssertEqual(revoked.kind, .revoked)
        XCTAssertEqual(revoked.consecutiveFailures, 2)
        XCTAssertTrue(TunnelCircuitBreaker.isTripped(revoked, now: t0.addingTimeInterval(60)))
    }

    // MARK: - Reset conditions

    func testADifferentNodeStartsAFreshStreak() {
        // "Pick another location" is a documented recovery, so it MUST reset the
        // budget. If failures against Frankfurt counted toward Amsterdam, the
        // user's only workaround would stop working after a few tries.
        let frankfurt = run(.neverEstablished, count: 5)
        XCTAssertTrue(TunnelCircuitBreaker.isTripped(frankfurt, now: t0.addingTimeInterval(300)))

        let amsterdam = TunnelCircuitBreaker.recordFailure(
            .neverEstablished, nodeId: otherNode,
            now: t0.addingTimeInterval(360), into: frankfurt
        )
        XCTAssertEqual(amsterdam.nodeId, otherNode)
        XCTAssertEqual(amsterdam.consecutiveFailures, 1)
        XCTAssertNil(amsterdam.trippedAt)
        XCTAssertFalse(TunnelCircuitBreaker.isTripped(amsterdam, now: t0.addingTimeInterval(360)))
    }

    func testAFailureOutsideTheRollingWindowStartsAFreshStreak() {
        // Without a window, consecutiveFailures is a LIFETIME counter and a
        // perfectly healthy node that blips once a week eventually trips.
        let streak = run(.diedAfterHandshake, count: 3)
        XCTAssertEqual(streak.consecutiveFailures, 3)

        let muchLater = t0.addingTimeInterval(120 + TunnelCircuitBreaker.failureWindow + 1)
        let restarted = TunnelCircuitBreaker.recordFailure(
            .diedAfterHandshake, nodeId: node, now: muchLater, into: streak
        )
        XCTAssertEqual(restarted.consecutiveFailures, 1)
        XCTAssertEqual(restarted.firstFailureAt, muchLater.timeIntervalSince1970)
        XCTAssertNil(restarted.trippedAt)
    }

    func testAFailureExactlyOnTheWindowBoundaryStillContinuesTheStreak() {
        // Boundary is inclusive by design; pin it so a later `<` vs `<=` edit is
        // a deliberate decision rather than an accident.
        let first = TunnelCircuitBreaker.recordFailure(
            .diedAfterHandshake, nodeId: node, now: t0, into: nil
        )
        let onBoundary = t0.addingTimeInterval(TunnelCircuitBreaker.failureWindow)
        let second = TunnelCircuitBreaker.recordFailure(
            .diedAfterHandshake, nodeId: node, now: onBoundary, into: first
        )
        XCTAssertEqual(second.consecutiveFailures, 2)
    }

    // MARK: - FAIL OPEN (requirement 1)

    func testATripLapsesOnItsOwnAfterTheCooldown() {
        // THE fail-open guarantee. Nobody has to open the app, tap anything or
        // change networks: a trip is bounded in time, so the worst a breaker bug
        // can cost is a delay.
        let tripped = run(.revoked, count: 1)
        XCTAssertNotNil(tripped.trippedAt)

        let justBefore = t0.addingTimeInterval(TunnelCircuitBreaker.tripCooldown - 1)
        XCTAssertTrue(TunnelCircuitBreaker.isTripped(tripped, now: justBefore),
                      "must still hold one second before the cooldown expires")

        let justAfter = t0.addingTimeInterval(TunnelCircuitBreaker.tripCooldown + 1)
        XCTAssertFalse(TunnelCircuitBreaker.isTripped(tripped, now: justAfter),
                       "must lapse on its own — a trip that never expires is a VPN that never works again")
    }

    func testNoRecordIsNeverTripped() {
        // The absent case has to read as "carry on as normal", because it is
        // also the case a keychain read failure or a decode failure produces.
        XCTAssertFalse(TunnelCircuitBreaker.isTripped(nil, now: t0))
        XCTAssertEqual(TunnelCircuitBreaker.remainingRedials(nil, now: t0),
                       TunnelCircuitBreaker.redialBudget(for: .diedAfterHandshake))
    }

    func testAnUntrippedRecordIsNeverTripped() {
        let record = run(.diedAfterHandshake, count: 1)
        XCTAssertNil(record.trippedAt)
        XCTAssertFalse(TunnelCircuitBreaker.isTripped(record, now: t0.addingTimeInterval(99_999)))
    }

    func testTrippedAtIsStampedOnceAndDoesNotSlideForward() {
        // If every later failure re-stamped trippedAt, the cooldown would never
        // elapse while the extension kept probing — the breaker would latch and
        // the fail-open above would be dead code.
        let tripped = run(.revoked, count: 1)
        let originalTrip = tripped.trippedAt

        let later = TunnelCircuitBreaker.recordFailure(
            .revoked, nodeId: node, now: t0.addingTimeInterval(120), into: tripped
        )
        XCTAssertEqual(later.trippedAt, originalTrip,
                       "the cooldown must measure from the FIRST trip, not the latest failure")
    }

    // MARK: - Budget accounting

    func testRemainingRedialsCountsDown() {
        XCTAssertEqual(TunnelCircuitBreaker.remainingRedials(run(.diedAfterHandshake, count: 1),
                                                             now: t0), 3)
        XCTAssertEqual(TunnelCircuitBreaker.remainingRedials(run(.diedAfterHandshake, count: 3),
                                                             now: t0.addingTimeInterval(120)), 1)
        XCTAssertEqual(TunnelCircuitBreaker.remainingRedials(run(.diedAfterHandshake, count: 4),
                                                             now: t0.addingTimeInterval(180)), 0)
    }

    func testRemainingRedialsIsRestoredOnceATripLapses() {
        let tripped = run(.neverEstablished, count: 2)
        let afterCooldown = t0.addingTimeInterval(TunnelCircuitBreaker.tripCooldown + 60)
        XCTAssertEqual(TunnelCircuitBreaker.remainingRedials(tripped, now: afterCooldown),
                       TunnelCircuitBreaker.redialBudget(for: .neverEstablished))
    }

    // MARK: - Persistence shape

    func testTheRecordSurvivesAJSONRoundTrip() throws {
        // TunnelBreakerStore persists this as JSON in the SHARED KEYCHAIN, and
        // it is the only channel between the tunnel extension (which counts) and
        // the host app (which performs the fail-open). A decode break would fail
        // open silently — no crash, no log, just a breaker that is never there.
        let original = run(.neverEstablished, count: 2)
        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(TunnelBreakerRecord.self, from: data)
        XCTAssertEqual(decoded, original)
        XCTAssertEqual(decoded.kind, .neverEstablished)
        XCTAssertNotNil(decoded.trippedAt)
    }

    func testFailureKindWireValuesAreStable() {
        // These strings are written to the keychain by one build and read back
        // by another after an app update. Renaming a case silently changes the
        // stored value, so pin them.
        XCTAssertEqual(TunnelFailureKind.neverEstablished.rawValue, "neverEstablished")
        XCTAssertEqual(TunnelFailureKind.diedAfterHandshake.rawValue, "diedAfterHandshake")
        XCTAssertEqual(TunnelFailureKind.revoked.rawValue, "revoked")
    }

    // MARK: - What the user is told

    func testTheUserMessageSaysTrafficIsNoLongerBlocked() {
        // The symptom the user just lived through is a dead network under a
        // "Protected" UI. The banner has to close that loop explicitly, or the
        // fail-open is invisible and they go to iOS Settings to delete the VPN
        // profile — the exact outcome this finding is about.
        for kind in [TunnelFailureKind.revoked, .neverEstablished, .diedAfterHandshake] {
            let record = TunnelCircuitBreaker.recordFailure(kind, nodeId: node, now: t0, into: nil)
            let message = TunnelCircuitBreaker.userMessage(for: record)
            XCTAssertTrue(message.contains("no longer being blocked"),
                          "\(kind.rawValue) message must say traffic is flowing again: \(message)")
            XCTAssertTrue(message.contains("Birdo stopped reconnecting"),
                          "\(kind.rawValue) message must say the re-dialling stopped: \(message)")
        }
    }

    func testTheMessageCountsAttemptsForTheRetryableKinds() {
        let record = run(.diedAfterHandshake, count: 4)
        XCTAssertTrue(TunnelCircuitBreaker.userMessage(for: record).contains("4 attempts"))
    }
}
