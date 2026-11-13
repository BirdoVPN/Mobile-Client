import XCTest

/// MR-1622 (fast dead-tunnel detection) and MR-1623 (old-key probe).
/// Same rules and thresholds as Android's TunnelMonitor, so the two clients
/// agree on when a tunnel is dead.
final class DeadTunnelDetectorTests: XCTestCase {

    private typealias D = DeadTunnelDetector

    /// Feed a sequence of (rx, tx, handshakeAge) samples 10 s apart.
    private func run(_ samples: [(Int64, Int64, TimeInterval?)],
                     sleepBefore: [Int: TimeInterval] = [:]) -> [D.Verdict] {
        var state = D.Liveness()
        var uptime: TimeInterval = 1000
        var wall: TimeInterval = 1_700_000_000
        var out: [D.Verdict] = []
        for (i, s) in samples.enumerated() {
            uptime += D.checkInterval
            wall += D.checkInterval + (sleepBefore[i] ?? 0)
            let (next, verdict) = D.assess(
                prev: state,
                sample: D.Sample(rx: s.0, tx: s.1, handshakeAge: s.2),
                now: uptime, wall: wall)
            state = next
            out.append(verdict)
        }
        return out
    }

    func testThresholdsMatchAndroid() {
        XCTAssertEqual(D.checkInterval, 10)
        XCTAssertEqual(D.fastDead, 20)
        XCTAssertEqual(D.stallThreshold, 180)
    }

    func testHealthyTunnelStaysAlive() {
        let v = run([(100, 100, 5), (500, 400, 15), (900, 700, 25), (1300, 900, 35)])
        XCTAssertEqual(v, [.alive, .alive, .alive, .alive])
    }

    func testFastRuleFiresAfterTwentySecondsOfUnansweredSends() {
        // First reading is a baseline; tx then grows by 5000 with rx flat.
        let v = run([(100, 100, 30), (100, 5100, 40), (100, 10100, 50), (100, 15100, 60)])
        XCTAssertEqual(v, [.alive, .alive, .alive, .unanswered])
    }

    func testAnswersResetTheFastRule() {
        let v = run([(100, 100, 30), (100, 5100, 40), (900, 10100, 50), (900, 15100, 60), (900, 20100, 70)])
        XCTAssertEqual(v, [.alive, .alive, .alive, .alive, .alive])
    }

    func testOwnKeepalivesAloneNeverFireTheFastRule() {
        // An idle tunnel sends a 32-byte keepalive every 25 s: tx creeps, rx too.
        var samples: [(Int64, Int64, TimeInterval?)] = []
        for i in 0..<12 {
            samples.append((Int64(100 + i * 32), Int64(100 + i * 32), TimeInterval(20 + i * 10)))
        }
        let v = run(samples)
        XCTAssertFalse(v.contains(where: { $0.isDead }))
    }

    func testKeepaliveAllowanceIsSmallButNotZero() {
        XCTAssertEqual(D.keepaliveAllowance, 32 * (1 + 10 / 25))
        XCTAssertGreaterThan(D.keepaliveAllowance, 0)
    }

    func testStaleHandshakeBackstop() {
        let v = run([(100, 100, 170), (100, 110, 181)])
        XCTAssertEqual(v, [.alive, .stalled])
    }

    func testNoHandshakeAtAllIsLeftToTheCaller() {
        var state = D.Liveness()
        let (next, verdict) = D.assess(prev: state,
                                       sample: D.Sample(rx: 0, tx: 0, handshakeAge: nil),
                                       now: 1010, wall: 1_700_000_010)
        state = next
        XCTAssertEqual(verdict, .neverHandshook)
        XCTAssertFalse(verdict.isDead)
        let (_, none) = D.assess(prev: state, sample: nil, now: 1020, wall: 1_700_000_020)
        XCTAssertEqual(none, .neverHandshook)
    }

    func testADeepSleepVoidsTheVerdictAndResetsTheBaseline() {
        // 120 s asleep before the third reading: the old handshake age is
        // meaningless, so no verdict, and the next reading starts fresh.
        let v = run([(100, 100, 30), (100, 5100, 40), (100, 9100, 190), (100, 9200, 20)],
                    sleepBefore: [2: 120])
        XCTAssertEqual(v[2], .skippedAfterSuspend)
        XCTAssertEqual(v[3], .alive)
    }

    func testSuspendedTimeIsWallMinusAwake() {
        var prev = D.Liveness()
        prev.lastWall = 100
        prev.lastUptime = 50
        XCTAssertEqual(D.suspended(prev: prev, wall: 220, uptime: 60), 110)
        XCTAssertEqual(D.suspended(prev: D.Liveness(), wall: 220, uptime: 60), 0)
    }

    func testParseSampleSumsPeersAndTakesNewestHandshake() {
        let uapi = """
        public_key=aa
        rx_bytes=100
        tx_bytes=50
        last_handshake_time_sec=1000
        public_key=bb
        rx_bytes=20
        tx_bytes=5
        last_handshake_time_sec=1900
        """
        let s = D.parseSample(uapi: uapi, nowEpoch: 2000)
        XCTAssertEqual(s.rx, 120)
        XCTAssertEqual(s.tx, 55)
        XCTAssertEqual(s.handshakeAge, 100)
        XCTAssertNil(D.parseSample(uapi: "rx_bytes=1\nlast_handshake_time_sec=0", nowEpoch: 2000).handshakeAge)
    }

    // MARK: - MR-1623 probe

    func testProbeClassifiesTheHeartbeatBody() {
        typealias P = DeadTunnelProbe
        XCTAssertEqual(P.classify(statusCode: 200, json: ["valid": false, "serverOnline": false]), .keyRevoked)
        XCTAssertEqual(P.classify(statusCode: 200, json: ["valid": true, "serverOnline": false]), .serverOffline)
        XCTAssertEqual(P.classify(statusCode: 200, json: ["valid": true, "serverOnline": true]), .keyValid)
    }

    func testProbeFailsOpenOnAnythingUnclear() {
        typealias P = DeadTunnelProbe
        XCTAssertEqual(P.classify(statusCode: nil, json: nil), .unknown)
        XCTAssertEqual(P.classify(statusCode: 401, json: ["valid": false]), .unknown)
        XCTAssertEqual(P.classify(statusCode: 503, json: nil), .unknown)
        XCTAssertEqual(P.classify(statusCode: 200, json: nil), .unknown)
        XCTAssertEqual(P.classify(statusCode: 200, json: [:]), .unknown)
    }

    func testOnlyHardEvidenceChargesTheBreaker() {
        typealias P = DeadTunnelProbe
        XCTAssertEqual(P.decision(verdict: .unanswered, outcome: .keyRevoked), .charge(.revoked))
        XCTAssertEqual(P.decision(verdict: .stalled, outcome: .keyValid), .charge(.diedAfterHandshake))
        XCTAssertEqual(P.decision(verdict: .stalled, outcome: .unknown), .charge(.diedAfterHandshake))
        XCTAssertEqual(P.decision(verdict: .neverHandshook, outcome: .unknown), .charge(.neverEstablished))
        // The FAST rule re-dials but never spends the budget (handover, portal).
        XCTAssertEqual(P.decision(verdict: .unanswered, outcome: .unknown), .redialOnly)
        XCTAssertEqual(P.decision(verdict: .unanswered, outcome: .keyValid), .redialOnly)
        // A node-status flap cannot trip it either.
        XCTAssertEqual(P.decision(verdict: .stalled, outcome: .serverOffline), .redialOnly)
    }

    // MARK: - Path changes and deep sleep

    func testAHandoverNeverCountsAsUnanswered() {
        var state = D.Liveness()
        var up: TimeInterval = 1000
        var wall: TimeInterval = 1_700_000_000
        var verdicts: [D.Verdict] = []
        // wg-go resends a 148-byte handshake every 5 s: tx grows, rx is flat.
        for i in 0..<8 {
            up += D.checkInterval; wall += D.checkInterval
            let (n, v) = D.assess(prev: state,
                                  sample: D.Sample(rx: 100, tx: Int64(100 + i * 296), handshakeAge: 30),
                                  now: up, wall: wall,
                                  pathStableFor: 5) // path changed 5 s ago, every tick
            state = n; verdicts.append(v)
        }
        XCTAssertFalse(verdicts.contains(.unanswered))
    }

    func testAStablePathStillFiresTheFastRule() {
        var state = D.Liveness()
        var up: TimeInterval = 1000
        var wall: TimeInterval = 1_700_000_000
        var last = D.Verdict.alive
        for i in 0..<5 {
            up += D.checkInterval; wall += D.checkInterval
            let (n, v) = D.assess(prev: state,
                                  sample: D.Sample(rx: 100, tx: Int64(100 + i * 5000), handshakeAge: 30),
                                  now: up, wall: wall, pathStableFor: 600)
            state = n; last = v
        }
        XCTAssertEqual(last, .unanswered)
    }

    func testStaleHandshakeRightAfterASleepIsHeldForTwoTicks() {
        // Slept 10 minutes: the handshake is 600 s old only because the CPU was off.
        let v = run([(100, 100, 30), (100, 100, 600), (100, 100, 601), (100, 100, 602), (100, 100, 603)],
                    sleepBefore: [1: 600])
        XCTAssertEqual(v[1], .skippedAfterSuspend)
        XCTAssertEqual(v[2], .alive)       // held
        XCTAssertEqual(v[3], .stalled)     // still stale after the hold: now believed
    }

    func testAFreshHandshakeAfterASleepIsAlive() {
        let v = run([(100, 100, 30), (100, 100, 600), (300, 200, 4), (300, 200, 14)], sleepBefore: [1: 600])
        XCTAssertEqual(v, [.alive, .skippedAfterSuspend, .alive, .alive])
    }

    func testIdleTunnelIsNotBusyAndTrafficIs() {
        var state = D.Liveness()
        (state, _) = D.assess(prev: state, sample: D.Sample(rx: 1, tx: 1, handshakeAge: 5), now: 1010, wall: 10)
        (state, _) = D.assess(prev: state, sample: D.Sample(rx: 1, tx: 33, handshakeAge: 15), now: 1020, wall: 20)
        XCTAssertFalse(state.busy)
        (state, _) = D.assess(prev: state, sample: D.Sample(rx: 900, tx: 800, handshakeAge: 25), now: 1030, wall: 30)
        XCTAssertTrue(state.busy)
    }

    func testProbeReasonsAreShortLabels() {
        typealias P = DeadTunnelProbe
        XCTAssertEqual(P.reason(for: .keyRevoked), "connection revoked")
        XCTAssertEqual(P.reason(for: .serverOffline), "server offline")
        XCTAssertNil(P.reason(for: .keyValid))
        XCTAssertNil(P.reason(for: .unknown))
        XCTAssertLessThanOrEqual(P.timeout, 5)
    }
}
