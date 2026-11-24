import XCTest

/// MR-2524: what the tunnel extension's own heartbeat does with each reply
/// (`TunnelHeartbeatPolicy`, `TunnelHeartbeatState` and `HeartbeatHandOff` in
/// TunnelHeartbeatPolicy.swift). It decides with the host's own policy
/// (`HeartbeatPolicy`, HeartbeatWire.swift, compiled into both), so these tests
/// hold it to the host's outcome reply by reply, and pin what the extension can
/// do with each one: keep, leave it to the host, or stop.
///
/// TunnelHeartbeatWiringTests pins where the provider and the host use this.
///
/// Android twin: `VpnManager.onHeartbeatVerdict`, where one process does both
/// halves. REAPED and a single-hop SERVER_GONE are rebuilt or moved behind the
/// block; REVOKED, EVICTED and QUOTA_EXCEEDED end the session, never re-dialled.
final class TunnelHeartbeatPolicyTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    private func reply(valid: Bool = true, serverOnline: Bool? = true, reason: HeartbeatReason? = nil,
                       quotaExceeded: Bool = false, secondsLeft: TimeInterval? = nil) -> HeartbeatResult {
        HeartbeatResult(valid: valid, serverOnline: serverOnline, message: nil, reason: reason,
                        quotaExceeded: quotaExceeded, quotaGraceEndsAt: nil,
                        quotaGraceSecondsRemaining: secondsLeft)
    }

    /// The extension's action for one reply about a fresh key whose beats
    /// began `sinceStart` ago (no good beat heard yet).
    private func action(_ reply: HeartbeatResult, sinceStart: TimeInterval = 60,
                        multiHop: Bool = false) -> TunnelHeartbeatAction? {
        var state = TunnelHeartbeatState()
        return state.decide(reply, keyId: "key-a", multiHop: multiHop,
                            startedAt: now.addingTimeInterval(-sinceStart), now: now)
    }

    private struct Node: RelayRetryCandidate {
        let id: String
        var name: String { id }
        var countryCode = "DE"
        var city = "Frankfurt"
        var load: Int32 = 10
        var isOnline = true
        var accessible = true
    }

    // MARK: - The host's decision, reply by reply

    /// Every reply the decision reads, on either kind of session, before and
    /// after a reap window without a good beat: the extension's action is the
    /// host's outcome mapped once (`TunnelHeartbeatPolicy.action`), and the
    /// host, carrying out what was handed over, reaches exactly the outcome its
    /// own beat would have reached on the same reply.
    func testTheExtensionTakesTheHostsDecisionForEveryReply() {
        let reasons: [HeartbeatReason?] = HeartbeatReason.allCases.map { Optional($0) } + [nil]
        let onlines: [Bool?] = [true, false, nil]
        let gaps: [TimeInterval] = [0, 60, 299, 300, 3600]
        var judged = 0
        var redials = 0
        for reason in reasons {
            for valid in [true, false] {
                for quota in [false, true] {
                    for online in onlines {
                        for gap in gaps {
                            for multiHop in [false, true] {
                                let sent = reply(valid: valid, serverOnline: online, reason: reason,
                                                 quotaExceeded: quota, secondsLeft: quota ? 540 : nil)
                                let label = "\(sent) after \(gap) s, multiHop \(multiHop)"
                                let host = HeartbeatPolicy.outcome(of: sent, sinceLastOk: gap, multiHop: multiHop,
                                                                   now: now)
                                let taken = action(sent, sinceStart: gap, multiHop: multiHop)
                                XCTAssertEqual(taken, TunnelHeartbeatPolicy.action(for: host), label)
                                switch taken {
                                case .leaveToHost(let verdict)?, .stop(let verdict)?:
                                    XCTAssertEqual(verdict.hostOutcome(multiHop: multiHop), host, label)
                                case .keep?:
                                    if case .keep = host {} else { XCTFail("kept what the host acts on: \(label)") }
                                case nil:
                                    XCTFail("a fresh key was not judged: \(label)")
                                }
                                // No teardown where the policy redials.
                                if host == .rebuild || host == .moveServer {
                                    redials += 1
                                    if case .leaveToHost? = taken {} else {
                                        XCTFail("the extension tears down what the host redials: \(label)")
                                    }
                                }
                                judged += 1
                            }
                        }
                    }
                }
            }
        }
        XCTAssertEqual(judged, 8 * 2 * 2 * 3 * 5 * 2)
        XCTAssertGreaterThan(redials, 0, "the grid never reaches a rebuild or a move")
    }

    /// The table, outcome by outcome.
    func testEachOutcomeOfTheTable() {
        // Live, and live inside the Free grace window: kept, the tunnel untouched
        // (the host shows the grace notice from its own beat).
        XCTAssertEqual(action(reply(reason: .ok)), .keep)
        XCTAssertEqual(action(reply(reason: .ok, quotaExceeded: true, secondsLeft: 540)), .keep)
        XCTAssertEqual(action(reply(serverOnline: false, reason: .serverOffline)), .keep,
                       "a node that is not ONLINE is logged by the host, never left")
        XCTAssertEqual(action(reply(serverOnline: false)), .keep, "a reply with no reason, still valid")

        // reaped: one quiet rebuild, the host's (Android REAPED, behind the block).
        let reaped = reply(valid: false, serverOnline: false, reason: .reaped)
        XCTAssertEqual(action(reaped), .leaveToHost(.rebuild))
        XCTAssertEqual(action(reaped, sinceStart: 3600), .leaveToHost(.rebuild))
        XCTAssertEqual(action(reaped, multiHop: true), .leaveToHost(.rebuild), "the same pair, never a downgrade")

        // server_offline with the key removed: drained or deleted, the wire is
        // the same (where to go is the host's `moveTarget`). A single hop
        // moves; a Multi-Hop pair ends, the pair being the user's to choose.
        let gone = reply(valid: false, serverOnline: false, reason: .serverOffline)
        XCTAssertEqual(action(gone), .leaveToHost(.moveServer))
        XCTAssertEqual(action(gone, multiHop: true), .stop(.multiHopRouteOffline))

        // Ended on purpose: stop, never re-dialled (Android REVOKED, EVICTED,
        // QUOTA_EXCEEDED), whatever the node reports.
        for online in [true, false] {
            XCTAssertEqual(action(reply(valid: false, serverOnline: online, reason: .revoked)), .stop(.revoked))
            XCTAssertEqual(action(reply(valid: false, serverOnline: online, reason: .evicted), sinceStart: 3600),
                           .stop(.evicted), "never a quiet rebuild")
        }
        XCTAssertEqual(action(reply(valid: false, serverOnline: false, reason: .quotaExceeded, quotaExceeded: true)),
                       .stop(.quotaExceeded))
        XCTAssertEqual(action(reply(valid: false, serverOnline: false, quotaExceeded: true)), .stop(.quotaExceeded),
                       "either field ends it as the allowance")
        XCTAssertEqual(action(reply(valid: false, serverOnline: false, reason: .notFound, quotaExceeded: true),
                              sinceStart: 3600), .stop(.quotaExceeded), "never inferred as a reap")

        // not_found, or no reason: Android's inference from the gap since the
        // last good beat, on the extension's own clock.
        let notFound = reply(valid: false, serverOnline: false, reason: .notFound)
        XCTAssertEqual(action(notFound, sinceStart: 299), .stop(.revoked))
        XCTAssertEqual(action(notFound, sinceStart: 300), .leaveToHost(.rebuild))
        XCTAssertEqual(action(reply(valid: false, serverOnline: false)), .stop(.revoked))
        XCTAssertEqual(action(reply(valid: false, serverOnline: false), sinceStart: 600), .leaveToHost(.rebuild))
    }

    /// A move is the same verdict for either pick. WHERE it goes is the host's
    /// `moveTarget`, which the hand-off reaches exactly as the host's own beat
    /// does (`hostOutcome` gives `.moveServer`), under the owner's rule
    /// (2026-11-19): a DELETED server's automatic pick moves to the best server
    /// anywhere; a user pick stays in its own country, read from the cached
    /// list, and with nothing there the session stops ("Choose a server").
    func testAMoveFollowsTheOwnersRuleForEitherPick() {
        let gone = reply(valid: false, serverOnline: false, reason: .serverOffline)
        XCTAssertEqual(action(gone), .leaveToHost(.moveServer))
        XCTAssertEqual(HeartbeatHandOff.Verdict.moveServer.hostOutcome(multiHop: false), .moveServer)

        let dialledFrom = [Node(id: "de-1"),
                           Node(id: "de-1b", load: 60),
                           Node(id: "de-2", city: "Berlin", load: 50),
                           Node(id: "nl-1", countryCode: "NL", city: "Amsterdam", load: 1)]
        let deleted = Array(dialledFrom.dropFirst())
        XCTAssertEqual(HeartbeatPolicy.moveTarget(from: "de-1", in: deleted, cached: dialledFrom,
                                                  origin: .automatic)?.server.id, "nl-1", "automatic: anywhere")
        XCTAssertEqual(HeartbeatPolicy.moveTarget(from: "de-1", in: deleted, cached: dialledFrom,
                                                  origin: .user)?.server.id, "de-1b", "user: its own country")
        XCTAssertNil(HeartbeatPolicy.moveTarget(from: "de-1", in: [dialledFrom[3]], cached: dialledFrom, origin: .user),
                     "user, nothing left in its country: no move, the session stops")
        XCTAssertEqual(HeartbeatPolicy.moveTarget(from: "de-1", in: [dialledFrom[3]], cached: dialledFrom,
                                                  origin: .automatic)?.server.id, "nl-1")
        // A drained server (still listed, offline): a user pick stays in its country too.
        var drained = dialledFrom
        drained[0].isOnline = false
        XCTAssertEqual(HeartbeatPolicy.moveTarget(from: "de-1", in: drained, cached: dialledFrom,
                                                  origin: .user)?.server.id, "de-1b")
    }

    // MARK: - The extension's memory

    /// One quiet rebuild per window, as on the host: a session the host rebuilt
    /// onto a new key that is reaped again inside the window ends. Fed the same
    /// reaps, the host's own budget answers the same.
    func testOneQuietRebuildPerWindowAsOnTheHost() {
        var state = TunnelHeartbeatState()
        let reaped = reply(valid: false, serverOnline: false, reason: .reaped)
        XCTAssertEqual(state.decide(reaped, keyId: "key-a", multiHop: false,
                                    startedAt: now.addingTimeInterval(-60), now: now), .leaveToHost(.rebuild))
        XCTAssertEqual(state.decide(reaped, keyId: "key-b", multiHop: false,
                                    startedAt: now, now: now.addingTimeInterval(9 * 60)), .stop(.revoked))
        XCTAssertEqual(state.decide(reaped, keyId: "key-c", multiHop: false,
                                    startedAt: now, now: now.addingTimeInterval(20 * 60)), .leaveToHost(.rebuild))

        var host = HeartbeatRebuildBudget()
        XCTAssertEqual(host.admit(.rebuild, now: now), .rebuild)
        XCTAssertEqual(host.admit(.rebuild, now: now.addingTimeInterval(9 * 60)), .end(.revoked))
        XCTAssertEqual(host.admit(.rebuild, now: now.addingTimeInterval(20 * 60)), .rebuild)
    }

    /// A key the server removed never comes back: it is judged once. The beats
    /// that follow about it hand nothing over again, charge nothing to the
    /// breaker and spend nothing from the budget, so a host that is rebuilding
    /// in place is never cut short by the extension's next beat (#662 F3). The
    /// key the host swaps in is judged afresh.
    func testASettledKeyIsJudgedOnce() {
        var state = TunnelHeartbeatState()
        let reaped = reply(valid: false, serverOnline: false, reason: .reaped)
        let start = now.addingTimeInterval(-60)
        XCTAssertEqual(state.decide(reaped, keyId: "key-a", multiHop: false, startedAt: start, now: now),
                       .leaveToHost(.rebuild))
        XCTAssertTrue(state.settled)
        for later in [30.0, 60, 90] {
            XCTAssertNil(state.decide(reaped, keyId: "key-a", multiHop: false, startedAt: start,
                                      now: now.addingTimeInterval(later)))
            XCTAssertNil(state.decide(reply(valid: false, serverOnline: false, reason: .evicted), keyId: "key-a",
                                      multiHop: false, startedAt: start, now: now.addingTimeInterval(later)))
        }
        XCTAssertEqual(state.decide(reply(reason: .ok), keyId: "key-b", multiHop: false, startedAt: now,
                                    now: now.addingTimeInterval(120)), .keep)
        XCTAssertFalse(state.settled)
        XCTAssertEqual(state.keyId, "key-b")
        // The budget was spent once, by key-a: key-b's reap inside the window ends.
        XCTAssertEqual(state.decide(reaped, keyId: "key-b", multiHop: false, startedAt: now,
                                    now: now.addingTimeInterval(150)), .stop(.revoked))
    }

    /// The reap inference's clock is the last good beat of THIS key (the host
    /// keeps `lastHeartbeatOkAt` the same way), or when its beats began.
    func testTheReapClockRunsFromTheLastGoodBeatOfThisKey() {
        var state = TunnelHeartbeatState()
        let start = now.addingTimeInterval(-1000)
        let lastOk = now.addingTimeInterval(-100)
        XCTAssertEqual(state.decide(reply(reason: .ok), keyId: "key-a", multiHop: false, startedAt: start,
                                    now: lastOk), .keep)
        XCTAssertEqual(state.lastOkAt, lastOk)
        let notFound = reply(valid: false, serverOnline: false, reason: .notFound)

        var copy = state
        XCTAssertEqual(copy.decide(notFound, keyId: "key-a", multiHop: false, startedAt: start,
                                   now: lastOk.addingTimeInterval(299)), .stop(.revoked), "beats were flowing")
        copy = state
        XCTAssertEqual(copy.decide(notFound, keyId: "key-a", multiHop: false, startedAt: start,
                                   now: lastOk.addingTimeInterval(300)), .leaveToHost(.rebuild), "a reap window")
        // A valid reply about a node that is not ONLINE is a good beat too (the
        // host's rule: `if result.valid { lastHeartbeatOkAt = now }`).
        copy = state
        XCTAssertEqual(copy.decide(reply(serverOnline: false, reason: .serverOffline), keyId: "key-a",
                                   multiHop: false, startedAt: start, now: lastOk.addingTimeInterval(200)), .keep)
        XCTAssertEqual(copy.lastOkAt, lastOk.addingTimeInterval(200), "a valid reply on an offline node is a good beat")

        // A new key starts from its own start, not the old key's last good beat.
        copy = state
        XCTAssertEqual(copy.decide(notFound, keyId: "key-b", multiHop: false,
                                   startedAt: now.addingTimeInterval(-299), now: now), .stop(.revoked))
        copy = state
        XCTAssertEqual(copy.decide(notFound, keyId: "key-b", multiHop: false,
                                   startedAt: now.addingTimeInterval(-300), now: now), .leaveToHost(.rebuild))
    }

    // MARK: - The hand-off

    /// The verdict crosses the process boundary in the shared keychain, by
    /// stable names. A name this build does not know reads as nothing to do.
    func testTheHandOffCrossesTheProcessBoundary() throws {
        XCTAssertEqual(HeartbeatHandOff.Verdict.allCases.map(\.rawValue),
                       ["rebuild", "move_server", "revoked", "evicted", "quota_exceeded",
                        "multi_hop_route_offline", "server_offline"])
        for verdict in HeartbeatHandOff.Verdict.allCases {
            let handOff = HeartbeatHandOff(keyId: "key-a", verdict: verdict, decidedAt: 1_793_000_000)
            let data = try JSONEncoder().encode(handOff)
            XCTAssertEqual(try JSONDecoder().decode(HeartbeatHandOff.self, from: data), handOff)
        }
        let unknown = Data(#"{"keyId":"key-a","verdict":"teleport","decidedAt":1}"#.utf8)
        XCTAssertNil(try? JSONDecoder().decode(HeartbeatHandOff.self, from: unknown))
        // Only the rebuild and the move are the host's, the tunnel untouched.
        XCTAssertEqual(HeartbeatHandOff.Verdict.allCases.filter(\.isLeftToHost), [.rebuild, .moveServer])
    }

    /// Each ending is handed over by name, and the host says that ending's own
    /// sentence (HeartbeatPolicyTests pins the sentences; only the allowance
    /// offers the plans), never the breaker's generic one.
    func testEachEndingIsHandedOverByName() {
        let endings: [(HeartbeatEnding, HeartbeatHandOff.Verdict)] = [
            (.revoked, .revoked), (.evicted, .evicted), (.quotaExceeded, .quotaExceeded),
            (.multiHopRouteOffline, .multiHopRouteOffline), (.serverOffline, .serverOffline),
        ]
        for (ending, verdict) in endings {
            XCTAssertEqual(HeartbeatHandOff.Verdict(ending: ending), verdict)
            XCTAssertEqual(TunnelHeartbeatPolicy.action(for: .end(ending)), .stop(verdict))
            XCTAssertEqual(verdict.hostOutcome(multiHop: false), .end(ending))
            XCTAssertEqual(verdict.hostOutcome(multiHop: true), .end(ending))
            XCTAssertFalse(verdict.isLeftToHost)
        }
        XCTAssertEqual(HeartbeatHandOff.Verdict(ending: HeartbeatEnding(message: "Something new.", offersPlans: false)),
                       .revoked, "an ending this build does not name is said as a revoke")
    }

    /// The one fact a profile an older build saved cannot give the extension is
    /// whether the session is a Multi-Hop pair. The host judges a move again in
    /// its own context: a pair is never moved, it ends. A rebuild keeps the pair.
    func testTheHostJudgesAPairsMoveAgain() {
        XCTAssertEqual(HeartbeatHandOff.Verdict.moveServer.hostOutcome(multiHop: false), .moveServer)
        XCTAssertEqual(HeartbeatHandOff.Verdict.moveServer.hostOutcome(multiHop: true), .end(.multiHopRouteOffline))
        XCTAssertEqual(HeartbeatHandOff.Verdict.rebuild.hostOutcome(multiHop: true), .rebuild)
        XCTAssertEqual(TunnelHeartbeatPolicy.multiHopKey, "hb-multi-hop")
        XCTAssertTrue(TunnelHeartbeatPolicy.multiHop("1"))
        let notPairs: [Any?] = ["0", "", "true", "yes", 1, true, nil]
        for value in notPairs {
            XCTAssertFalse(TunnelHeartbeatPolicy.multiHop(value), "\(String(describing: value))")
        }
    }
}
