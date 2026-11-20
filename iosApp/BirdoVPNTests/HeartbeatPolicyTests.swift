import XCTest

/// MR-2343: what the host does with each heartbeat reason, outcome by outcome:
/// `HeartbeatPolicy`, `QuotaGrace`, `HeartbeatEnding` and
/// `HeartbeatRebuildBudget` (HeartbeatWire.swift), the decision
/// `VpnViewModel.sendHeartbeat` carries out.
///
/// ConnectContractTests runs the same decision over every body the contract's
/// server writes. This file pins each outcome on its own, the edges those
/// bodies do not reach, and, as source pins (VpnViewModel and HomeView cannot
/// be compiled into this bundle), that the app acts on this decision rather
/// than on a copy of it.
///
/// Android twin: HeartbeatPolicy, QuotaPolicy and SessionCopy
/// (app/src/main/java/app/birdo/vpn/service/SessionPolicy.kt), carried out by
/// VpnManager.onHeartbeatVerdict.
final class HeartbeatPolicyTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_793_000_000)

    // birdo-web VpnService.heartbeat's own sentences.
    private let notFound = "Connection not found. Please reconnect."
    private let offline = "Your current VPN server is offline. Please reconnect to a different server."

    // The client's sentences (Android SessionCopy), word for word.
    private let revokedCopy = "Connection has been revoked. Please reconnect."
    private let evictedCopy = "Another device on your account connected, so this one was disconnected. "
        + "Tap Connect to take it back."
    private let quotaCopy = "You've used this month's free data allowance. Upgrade to keep using BirdoVPN."
    private let routeGoneCopy = "A server on your Multi-Hop route went offline. Choose another entry or exit."
    private let serverGoneCopy = "This server went offline. Choose another location."

    private var revokedEnd: HeartbeatOutcome { .end(HeartbeatEnding(message: revokedCopy, offersPlans: false)) }
    private var quotaEnd: HeartbeatOutcome { .end(HeartbeatEnding(message: quotaCopy, offersPlans: true)) }

    private func reply(valid: Bool = true, serverOnline: Bool? = true, message: String? = nil,
                       reason: HeartbeatReason? = nil, quotaExceeded: Bool = false,
                       endsAt: String? = nil, secondsLeft: TimeInterval? = nil) -> HeartbeatResult {
        HeartbeatResult(valid: valid, serverOnline: serverOnline, message: message, reason: reason,
                        quotaExceeded: quotaExceeded, quotaGraceEndsAt: endsAt,
                        quotaGraceSecondsRemaining: secondsLeft)
    }

    private func outcome(_ reply: HeartbeatResult, sinceLastOk: TimeInterval = 0,
                         multiHop: Bool = false) -> HeartbeatOutcome {
        HeartbeatPolicy.outcome(of: reply, sinceLastOk: sinceLastOk, multiHop: multiHop, now: now)
    }

    private func iso(_ date: Date, fractional: Bool) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = fractional ? [.withInternetDateTime, .withFractionalSeconds] : [.withInternetDateTime]
        return formatter.string(from: date)
    }

    // MARK: - ok inside the Free grace window: a notice, the session kept

    func testTheFreeGraceWindowShowsANoticeAndKeepsTheSession() {
        let inGrace = reply(reason: .ok, quotaExceeded: true,
                            endsAt: "2026-10-31T23:50:00.000Z", secondsLeft: 540)
        XCTAssertEqual(outcome(inGrace), .keep(nodeOffline: false, grace: QuotaGrace(minutesLeft: 9)))
        // On a node that is not ONLINE: still kept, still announced.
        let offlineInGrace = reply(serverOnline: false, reason: .serverOffline, quotaExceeded: true, secondsLeft: 60)
        XCTAssertEqual(outcome(offlineInGrace), .keep(nodeOffline: true, grace: QuotaGrace(minutesLeft: 1)))
        // Not over the allowance: no notice.
        XCTAssertEqual(outcome(reply(reason: .ok)), .keep(nodeOffline: false, grace: nil))
    }

    func testTheGraceNoticeSaysWhenInWholeMinutes() {
        XCTAssertEqual(QuotaGrace(minutesLeft: 9).message, "Free data allowance used — your connection ends in 9 min.")
        XCTAssertEqual(QuotaGrace(minutesLeft: nil).message, "Free data allowance used — your connection ends soon.")
    }

    /// Android QuotaPolicy.grace: the server's seconds win (its clock, not
    /// ours), the end instant is the fallback, minutes round UP so "ends in 0
    /// min" is never said, and with neither the notice says "soon".
    func testTheGraceMinutesRoundUpAndFallBackToTheEndInstant() {
        func grace(_ seconds: TimeInterval?, endsAt: String? = nil) -> QuotaGrace? {
            QuotaGrace.from(reply(reason: .ok, quotaExceeded: true, endsAt: endsAt, secondsLeft: seconds), now: now)
        }
        XCTAssertEqual(grace(540), QuotaGrace(minutesLeft: 9))
        XCTAssertEqual(grace(541), QuotaGrace(minutesLeft: 10))
        XCTAssertEqual(grace(60), QuotaGrace(minutesLeft: 1))
        XCTAssertEqual(grace(61), QuotaGrace(minutesLeft: 2))
        XCTAssertEqual(grace(0), QuotaGrace(minutesLeft: 1))
        XCTAssertEqual(grace(-30), QuotaGrace(minutesLeft: 1))
        XCTAssertEqual(grace(120, endsAt: iso(now.addingTimeInterval(3600), fractional: true)),
                       QuotaGrace(minutesLeft: 2), "the server's seconds win over the end instant")
        XCTAssertEqual(grace(nil, endsAt: iso(now.addingTimeInterval(150), fractional: true)), QuotaGrace(minutesLeft: 3))
        XCTAssertEqual(grace(nil, endsAt: iso(now.addingTimeInterval(150), fractional: false)), QuotaGrace(minutesLeft: 3))
        XCTAssertEqual(grace(nil, endsAt: iso(now.addingTimeInterval(-90), fractional: true)), QuotaGrace(minutesLeft: 1))
        XCTAssertEqual(grace(nil), QuotaGrace(minutesLeft: nil))
        XCTAssertEqual(grace(nil, endsAt: "after lunch"), QuotaGrace(minutesLeft: nil))
        // No grace window is a day long; a number past Int's range must not trap Int(_:).
        XCTAssertEqual(grace(1e300), QuotaGrace(minutesLeft: 1440))
    }

    func testNoGraceNoticeWithoutTheFlagOrOnceTheSessionIsOver() {
        XCTAssertNil(QuotaGrace.from(reply(reason: .ok, secondsLeft: 60), now: now))
        XCTAssertNil(QuotaGrace.from(reply(valid: false, reason: .quotaExceeded, quotaExceeded: true, secondsLeft: 60),
                                     now: now))
    }

    // MARK: - evicted: another device took over

    func testEvictedSaysAnotherDeviceTookOverAndNeverReconnects() {
        let evicted = reply(valid: false, serverOnline: false, message: notFound, reason: .evicted)
        let said = HeartbeatOutcome.end(HeartbeatEnding(message: evictedCopy, offersPlans: false))
        XCTAssertEqual(outcome(evicted), said)
        XCTAssertEqual(outcome(evicted, sinceLastOk: 3600), said, "never a quiet rebuild")
        XCTAssertEqual(outcome(evicted, multiHop: true), said)
    }

    // MARK: - reaped: one quiet rebuild

    func testReapedIsAQuietRebuildOfTheSameRoute() {
        let reaped = reply(valid: false, serverOnline: false, message: notFound, reason: .reaped)
        XCTAssertEqual(outcome(reaped), .rebuild)
        XCTAssertEqual(outcome(reaped, multiHop: true), .rebuild, "the same pair, never a single-hop downgrade")
    }

    /// One quiet rebuild per 10 minutes (Android ReconnectPolicy: one fresh
    /// connect for REAPED in its failure window; Desktop: one per episode). A
    /// second reap inside the window ends the session like a revoke.
    func testOnlyOneQuietRebuildPerWindowThenTheSessionEnds() {
        XCTAssertEqual(HeartbeatRebuildBudget.window, 600)
        var budget = HeartbeatRebuildBudget()
        XCTAssertEqual(budget.admit(.rebuild, now: now), .rebuild)
        XCTAssertEqual(budget.admit(.rebuild, now: now.addingTimeInterval(9 * 60)), revokedEnd)
        XCTAssertEqual(budget.admit(.rebuild, now: now.addingTimeInterval(10 * 60)), .rebuild)
        XCTAssertEqual(budget.admit(.rebuild, now: now.addingTimeInterval(15 * 60)), revokedEnd,
                       "the window runs from the last rebuild")
        // Every other outcome passes through and spends nothing.
        var fresh = HeartbeatRebuildBudget()
        let others: [HeartbeatOutcome] = [.moveServer, .keep(nodeOffline: false, grace: nil), revokedEnd]
        for other in others {
            XCTAssertEqual(fresh.admit(other, now: now), other)
        }
        XCTAssertEqual(fresh.admit(.rebuild, now: now), .rebuild)
    }

    // MARK: - server_offline, key removed: move to another server

    func testADrainedServerMovesToAnotherServer() {
        let drained = reply(valid: false, serverOnline: false, message: notFound, reason: .serverOffline)
        XCTAssertEqual(outcome(drained), .moveServer)
        XCTAssertEqual(outcome(drained, sinceLastOk: 3600), .moveServer)
        // A Multi-Hop pair is the user's to choose again.
        XCTAssertEqual(outcome(drained, multiHop: true),
                       .end(HeartbeatEnding(message: routeGoneCopy, offersPlans: false)))
        // A live key on a node that is not ONLINE stays where it is.
        XCTAssertEqual(outcome(reply(serverOnline: false, message: offline, reason: .serverOffline)),
                       .keep(nodeOffline: true, grace: nil))
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

    /// The move follows MR-2212's owner rule (`RelayRetry.next`): a location
    /// the user picked stays in its country, a server the client chose may go
    /// anywhere. The drained node may be listed offline in the fresh list or
    /// be gone from it; its location is then read from the list it was in.
    func testTheMoveFollowsTheRetryRule() {
        let cached = [Node(id: "de-1"),
                      Node(id: "de-2", city: "Berlin", load: 50),
                      Node(id: "nl-1", countryCode: "NL", city: "Amsterdam", load: 1)]
        var listed = cached
        listed[0].isOnline = false
        XCTAssertEqual(HeartbeatPolicy.moveTarget(from: "de-1", in: listed, cached: cached, origin: .user)?.server.id,
                       "de-2")
        XCTAssertEqual(HeartbeatPolicy.moveTarget(from: "de-1", in: listed, cached: cached,
                                                  origin: .automatic)?.server.id, "nl-1")
        let gone = Array(listed.dropFirst())
        XCTAssertEqual(HeartbeatPolicy.moveTarget(from: "de-1", in: gone, cached: cached, origin: .user)?.server.id,
                       "de-2", "gone from the fresh list: its country comes from the cached one")
        // Nothing the rule allows: no move.
        XCTAssertNil(HeartbeatPolicy.moveTarget(from: "de-1", in: [listed[0], listed[2]], cached: cached,
                                                origin: .user))
        XCTAssertEqual(HeartbeatEnding.serverOffline, HeartbeatEnding(message: serverGoneCopy, offersPlans: false))
    }

    /// Round 2: the OWNER's rule for a server that has gone (owner decision,
    /// relayed by the lead at 2026-11-19 21:29Z, superseding the 21:16Z "no
    /// move"), carried out by `RelayRetry.next` (MR-2535):
    ///   * a DELETED server (no longer in the fresh list): an AUTOMATIC pick
    ///     moves to the best server anywhere, QuickSelect's best of all the
    ///     others, with no need to know where the gone server was;
    ///   * a USER pick moves within its own country, read from the list it was
    ///     dialled from when the fresh one no longer carries it (the same
    ///     city first). When nothing there qualifies, nothing moves and the
    ///     session ends ("This server went offline. Choose another location.").
    /// A DRAINED server (still listed, offline) goes to its same-city twin
    /// first for either pick: `testADrainedServerMovesToItsCityFirstForEitherPick`.
    func testAGoneServerFollowsTheOwnersRule() {
        let dialledFrom = [Node(id: "de-1"),
                           Node(id: "de-1b", load: 60),
                           Node(id: "de-2", city: "Berlin", load: 50),
                           Node(id: "nl-1", countryCode: "NL", city: "Amsterdam", load: 1)]
        let deleted = Array(dialledFrom.dropFirst())
        var drained = dialledFrom
        drained[0].isOnline = false
        // AUTOMATIC, deleted: the best anywhere, past the busier same-city twin.
        let automatic = HeartbeatPolicy.moveTarget(from: "de-1", in: deleted, cached: dialledFrom, origin: .automatic)
        XCTAssertEqual(automatic?.server.id, "nl-1")
        XCTAssertEqual(automatic?.reach, .otherCountry)
        for (label, list) in [("deleted", deleted), ("drained", drained)] {
            // USER: its own country, the same city first.
            let user = HeartbeatPolicy.moveTarget(from: "de-1", in: list, cached: dialledFrom, origin: .user)
            XCTAssertEqual(user?.server.id, "de-1b", label)
            XCTAssertEqual(user?.reach, .sameCity, label)
        }
        // USER with nothing left in its country: no move. AUTOMATIC still moves.
        let germanyGone = [dialledFrom[3]]
        XCTAssertNil(HeartbeatPolicy.moveTarget(from: "de-1", in: germanyGone, cached: dialledFrom, origin: .user))
        XCTAssertEqual(HeartbeatPolicy.moveTarget(from: "de-1", in: germanyGone, cached: dialledFrom,
                                                  origin: .automatic)?.server.id, "nl-1")
        // Neither list knows where it was. A user pick cannot keep its country,
        // so nothing moves; an automatic pick needs no location.
        XCTAssertNil(HeartbeatPolicy.moveTarget(from: "zz-9", in: deleted, cached: deleted, origin: .user))
        XCTAssertEqual(HeartbeatPolicy.moveTarget(from: "zz-9", in: deleted, cached: deleted,
                                                  origin: .automatic)?.server.id, "nl-1")
        // Only servers listed online and open to this plan qualify, for either pick.
        var closed = deleted
        closed[2].isOnline = false
        closed[1].accessible = false
        XCTAssertEqual(HeartbeatPolicy.moveTarget(from: "de-1", in: closed, cached: dialledFrom,
                                                  origin: .automatic)?.server.id, "de-1b")
        XCTAssertNil(HeartbeatPolicy.moveTarget(from: "de-1", in: [closed[1], closed[2]], cached: dialledFrom,
                                                origin: .automatic))
    }

    /// MR-2535 (review L1 of #651 round 2): a DRAINED server, still listed
    /// (offline), goes to another relay in its own city first, whoever picked
    /// it, as the connect retry (#655), Android and Desktop do. Before, an
    /// automatic pick went straight to the least loaded server anywhere and
    /// skipped the same-city twin. With no twin left, an automatic pick still
    /// goes anywhere and a user pick stays in its country.
    func testADrainedServerMovesToItsCityFirstForEitherPick() {
        let dialledFrom = [Node(id: "de-1"),
                           Node(id: "de-1b", load: 60),
                           Node(id: "de-2", city: "Berlin", load: 50),
                           Node(id: "nl-1", countryCode: "NL", city: "Amsterdam", load: 1)]
        var drained = dialledFrom
        drained[0].isOnline = false
        for origin in [SelectionOrigin.automatic, .user] {
            let choice = HeartbeatPolicy.moveTarget(from: "de-1", in: drained, cached: dialledFrom, origin: origin)
            XCTAssertEqual(choice?.server.id, "de-1b", "\(origin): the same city first, past a less loaded abroad")
            XCTAssertEqual(choice?.reach, .sameCity, "\(origin)")
        }
        // No twin left (offline too): an automatic pick goes anywhere, a user
        // pick stays in its country.
        var noTwin = drained
        noTwin[1].isOnline = false
        let automatic = HeartbeatPolicy.moveTarget(from: "de-1", in: noTwin, cached: dialledFrom, origin: .automatic)
        XCTAssertEqual(automatic?.server.id, "nl-1")
        XCTAssertEqual(automatic?.reach, .otherCountry)
        let user = HeartbeatPolicy.moveTarget(from: "de-1", in: noTwin, cached: dialledFrom, origin: .user)
        XCTAssertEqual(user?.server.id, "de-2")
        XCTAssertEqual(user?.reach, .sameCountry)
    }

    /// MR-2535: one implementation. The heartbeat's move IS the connect
    /// retry's `RelayRetry.next` with the cached list, for every gone server
    /// (listed, deleted, unknown to both lists), both picks, and many mixes of
    /// online, open-to-this-plan and load.
    func testTheMoveIsTheRetryRuleForEveryListShape() {
        let places: [(String, String)] = [("DE", "Frankfurt"), ("DE", "Berlin"), ("NL", "Amsterdam")]
        var compared = 0
        for mask in 0..<(1 << 9) {
            // Six relays, two per place; the mask's bits pick online and open.
            var nodes: [Node] = []
            for i in 0..<6 {
                let place = places[i / 2]
                var node = Node(id: "n\(i)", countryCode: place.0, city: place.1,
                                load: Int32((i * 37 + mask) % 90))
                node.isOnline = mask & (1 << (i % 9)) != 0
                node.accessible = mask & (1 << ((i + 3) % 9)) != 0
                nodes.append(node)
            }
            let shapes: [[Node]] = [nodes, Array(nodes.dropFirst()), nodes.filter { $0.id != "n2" }]
            for list in shapes {
                for gone in ["n0", "n2", "zz"] {
                    for origin in [SelectionOrigin.automatic, .user] {
                        let move = HeartbeatPolicy.moveTarget(from: gone, in: list, cached: nodes, origin: origin)
                        let retry = RelayRetry.next(after: gone, in: list, cached: nodes, origin: origin)
                        XCTAssertEqual(move?.server.id, retry?.server.id, "mask \(mask) gone \(gone) \(origin)")
                        XCTAssertEqual(move?.reach, retry?.reach, "mask \(mask) gone \(gone) \(origin)")
                        compared += 1
                    }
                }
            }
        }
        XCTAssertEqual(compared, 512 * 3 * 3 * 2)
    }

    /// Round 2: when a USER pick cannot move (nothing qualifies in its
    /// country), the stop "This server went offline. Choose another location."
    /// carries #648's one tap (MR-2493, `RelayOffer`). The tap offers the best
    /// server from the fresh list, which by then can only be outside that
    /// country, and never the gone one. An automatic pick stops only when
    /// nothing anywhere qualifies, so it is never offered anything.
    func testAUserPickThatCannotMoveIsOfferedOneTapElsewhere() {
        let dialledFrom = [Node(id: "de-1"),
                           Node(id: "nl-1", countryCode: "NL", city: "Amsterdam", load: 40),
                           Node(id: "se-1", countryCode: "SE", city: "Stockholm", load: 30)]
        let fresh = Array(dialledFrom.dropFirst())
        XCTAssertNil(HeartbeatPolicy.moveTarget(from: "de-1", in: fresh, cached: dialledFrom, origin: .user),
                     "the premise: nothing left in Germany")
        XCTAssertEqual(HeartbeatPolicy.offer(from: "de-1", in: fresh, origin: .user)?.id, "se-1")
        XCTAssertNil(HeartbeatPolicy.offer(from: "de-1", in: fresh, origin: .automatic))
        // Never the gone server, even listed online and idle in a stale answer.
        let stale = [Node(id: "de-1", load: 0)] + fresh
        XCTAssertEqual(HeartbeatPolicy.offer(from: "de-1", in: stale, origin: .user)?.id, "se-1")
        // Only what is listed online and open to this plan; nothing, no offer.
        var closed = fresh
        closed[1].isOnline = false
        XCTAssertEqual(HeartbeatPolicy.offer(from: "de-1", in: closed, origin: .user)?.id, "nl-1")
        closed[0].accessible = false
        XCTAssertNil(HeartbeatPolicy.offer(from: "de-1", in: closed, origin: .user))
    }

    // MARK: - quota_exceeded: never "not found"

    func testTheAllowancesEndIsNeverShownAsNotFound() {
        // The removal record a later beat finds: the not-found sentence, the flag kept.
        XCTAssertEqual(outcome(reply(valid: false, serverOnline: false, message: notFound,
                                     reason: .quotaExceeded, quotaExceeded: true)), quotaEnd)
        // Either field is enough (Android HeartbeatPolicy.verdict), whatever the reason says.
        XCTAssertEqual(outcome(reply(valid: false, serverOnline: false, message: notFound, reason: .quotaExceeded)),
                       quotaEnd)
        XCTAssertEqual(outcome(reply(valid: false, serverOnline: false, message: notFound, quotaExceeded: true)),
                       quotaEnd)
        XCTAssertEqual(outcome(reply(valid: false, serverOnline: false, message: notFound,
                                     reason: .notFound, quotaExceeded: true), sinceLastOk: 3600), quotaEnd)
        // quotaEndedResult(): the check-in that ends it, in the server's long sentence.
        XCTAssertEqual(outcome(reply(valid: false, serverOnline: true, message: "Free-tier data limit reached.",
                                     reason: .quotaExceeded, quotaExceeded: true)), quotaEnd)
    }

    // MARK: - revoked: never the offline sentence

    func testARevokedKeyNeverShowsTheOfflineOrTheNotFoundSentence() {
        let messages: [String?] = [offline, notFound, revokedCopy, nil]
        for message in messages {
            for serverOnline in [true, false] {
                XCTAssertEqual(outcome(reply(valid: false, serverOnline: serverOnline, message: message,
                                             reason: .revoked)), revokedEnd, "\(String(describing: message))")
            }
        }
    }

    // MARK: - not_found, and a reply with no reason: Android's inference

    func testNotFoundIsInferredFromTheGapSinceTheLastGoodBeat() {
        XCTAssertEqual(HeartbeatPolicy.reapWindow, 300)
        let gone = reply(valid: false, serverOnline: false, message: notFound, reason: .notFound)
        XCTAssertEqual(outcome(gone), revokedEnd)
        XCTAssertEqual(outcome(gone, sinceLastOk: 299), revokedEnd)
        XCTAssertEqual(outcome(gone, sinceLastOk: 300), .rebuild)
        XCTAssertEqual(outcome(gone, sinceLastOk: 3600, multiHop: true), .rebuild)
    }

    /// A backend before birdo-web #599 sends no reason: live is live, gone is
    /// inferred like not_found, and the reply's sentence is never shown.
    func testAReplyWithNoReasonIsJudgedAsAndroidJudgesIt() {
        XCTAssertEqual(outcome(reply(serverOnline: true)), .keep(nodeOffline: false, grace: nil))
        XCTAssertEqual(outcome(reply(serverOnline: false)), .keep(nodeOffline: true, grace: nil))
        XCTAssertEqual(outcome(reply(serverOnline: nil)), .keep(nodeOffline: false, grace: nil),
                       "an absent serverOnline is not offline")
        XCTAssertEqual(outcome(reply(valid: false, serverOnline: false, message: notFound)), revokedEnd)
        XCTAssertEqual(outcome(reply(valid: false, serverOnline: false, message: notFound), sinceLastOk: 600), .rebuild)
    }

    // MARK: - The endings

    func testEachEndingSaysItsOwnSentenceAndOnlyTheAllowanceOffersPlans() {
        XCTAssertEqual(HeartbeatEnding.revoked, HeartbeatEnding(message: revokedCopy, offersPlans: false))
        XCTAssertEqual(HeartbeatEnding.evicted, HeartbeatEnding(message: evictedCopy, offersPlans: false))
        XCTAssertEqual(HeartbeatEnding.quotaExceeded, HeartbeatEnding(message: quotaCopy, offersPlans: true))
        XCTAssertEqual(HeartbeatEnding.multiHopRouteOffline, HeartbeatEnding(message: routeGoneCopy, offersPlans: false))
        XCTAssertEqual(HeartbeatEnding.serverOffline, HeartbeatEnding(message: serverGoneCopy, offersPlans: false))
    }

    // MARK: - Where it is wired (source pins: these files cannot compile here)

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/HeartbeatPolicyTests.swift -> .../iosApp
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let url = root.appendingPathComponent(path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            XCTFail("\(path) not found under \(root.path): this pin would be vacuous")
            return ""
        }
        return try String(contentsOf: url, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
    }

    /// Text from `start` up to the next `end`, or "" (failing) when absent.
    private func slice(_ text: String, from start: String, to end: String,
                       file: StaticString = #filePath, line: UInt = #line) -> String {
        guard let a = text.range(of: start),
              let b = text.range(of: end, range: a.upperBound..<text.endIndex) else {
            XCTFail("not found: \(start) … \(end)", file: file, line: line)
            return ""
        }
        return String(text[a.lowerBound..<b.upperBound])
    }

    /// The host carries out THIS decision, for the session the beat was sent
    /// for, and never shows the reply's own sentence.
    func testTheHostBeatCarriesOutThisDecision() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let beat = slice(vm, from: "private func sendHeartbeat() async {", to: "\n    }\n")
        XCTAssertTrue(beat.contains("HeartbeatPolicy.outcome(of: result,"), "the beat decides by itself again")
        XCTAssertTrue(beat.contains("heartbeatRebuildBudget.admit("), "a reap is rebuilt without the budget")
        XCTAssertTrue(beat.contains("guard isConnected, activeKeyId == keyId"),
                      "a reply about a key the session no longer rides must not act")
        XCTAssertFalse(beat.contains("result.message"), "the reply's sentence is shown again")
        for handled in ["case .keep(let nodeOffline, let grace):", "case .rebuild:", "case .moveServer:",
                        "case .end(let ending):"] {
            XCTAssertTrue(beat.contains(handled), "sendHeartbeat does not handle \(handled)")
        }
    }

    /// MR-2525: the quiet rebuild and the move go IN PLACE first. Nothing in
    /// either stops the session before the attempt's directive: the flag
    /// that holds every other beat, dial and rebuild off is raised in the
    /// same main-actor step as the outcome, and only `.disconnectFirst`
    /// reaches the old stop-wait-dial path.
    func testTheRebuildAndTheMoveGoInPlaceFirst() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let rebuild = slice(vm, from: "private func rebuildAfterReap() {", to: "\n    }\n")
        let move = slice(vm, from: "private func moveOffDrainedServer() {", to: "\n    }\n")
        for (name, path) in [("rebuild", rebuild), ("move", move)] {
            let raised = try XCTUnwrap(path.range(of: "isRebuildingLive = true"), name)
            let task = try XCTUnwrap(path.range(of: "Task { [weak self] in"), name)
            XCTAssertLessThan(raised.lowerBound, task.lowerBound, "\(name): the flag goes up after a suspension")
            let attempt = try XCTUnwrap(path.range(of: "await self.redialInPlace("), name)
            let gate = try XCTUnwrap(path.range(of: "guard case .disconnectFirst = directive else { return }"), name)
            XCTAssertLessThan(attempt.lowerBound, gate.lowerBound, name)
            XCTAssertFalse(path.contains("vpnManager.disconnect()"), "\(name): stops the session before the attempt")
            XCTAssertFalse(path.contains("isTearingDownForRedial = true"), name)
            XCTAssertFalse(path.contains("releaseServerSlot()"), "\(name): the key is already gone: nothing to DELETE")
        }
        XCTAssertTrue(rebuild.contains("relayRecovery = .reconnecting"))
        XCTAssertTrue(rebuild.contains("self.rebuildDisconnectFirst(pair: pair, server: server, origin: origin, generation: generation)"))
        XCTAssertTrue(move.contains("relayRecovery = .tryingAnotherServer"))
        XCTAssertTrue(move.contains("HeartbeatPolicy.moveTarget(from: goneId, in: self.servers, cached: cached,"))
        XCTAssertTrue(move.contains("await self.redialInPlace(.single(next.server), generation: generation)"))
        XCTAssertTrue(move.contains("self.moveDisconnectFirst(to: next.server, origin: origin, generation: generation)"))
        // Nothing qualifies: the session ends as any server stop does, and says so.
        XCTAssertTrue(move.contains("self.endSessionForServer(.serverOffline)"))
        // Round 2: the stop carries #648's one tap for a user pick, chosen
        // only from a fresh list (never the list already held).
        XCTAssertTrue(move.contains("fresh.flatMap { HeartbeatPolicy.offer(from: goneId, in: $0, origin: origin) }"),
                      "the stop offers nothing")
        XCTAssertTrue(move.contains(
            "self.relayOffer = self.makeRelayOffer(offer, beside: HeartbeatEnding.serverOffline.message, fits: true)"))
        let retryWindow = slice(vm, from: "private func retryOnAnotherRelay() -> Bool {", to: "private func legacyRebuild(")
        XCTAssertFalse(retryWindow.contains("rebuildAfterReap") || retryWindow.contains("moveOffDrainedServer"))
        // The grace notice and the reap clock belong to one session.
        let reset = slice(vm, from: "private func resetSessionState() {", to: "\n    }\n")
        XCTAssertTrue(reset.contains("quotaGrace = nil"))
        XCTAssertTrue(reset.contains("lastHeartbeatOkAt = nil"))
    }

    /// The fallback, only after the in-place attempt kept no new peer: the
    /// pre-MR-2525 path, disconnect-first behind the teardown gate, never the
    /// user's own connect (so neither refills the MR-2212 retry nor clears the
    /// breaker on a single-hop session). A user who acted meanwhile wins.
    func testTheFallbackIsTheOldDisconnectFirstPath() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let rebuild = slice(vm, from: "private func rebuildDisconnectFirst(", to: "\n    }\n")
        let move = slice(vm, from: "private func moveDisconnectFirst(", to: "\n    }\n")
        for (name, path) in [("rebuild", rebuild), ("move", move)] {
            let fence = try XCTUnwrap(path.range(of: "guard generation == relayRetryGeneration else { return }"), name)
            let stop = try XCTUnwrap(path.range(of: "vpnManager.disconnect()"), name)
            XCTAssertLessThan(fence.lowerBound, stop.lowerBound, "\(name): a user who acted is overridden")
            XCTAssertTrue(path.contains("isTearingDownForRedial = true"), name)
            XCTAssertTrue(path.contains("await self?.awaitTeardown()"), name)
            XCTAssertTrue(path.contains("guard generation == self.relayRetryGeneration else { return }"), name)
            XCTAssertTrue(path.contains("self.connect(userInitiated: false)"), name)
            XCTAssertFalse(path.contains("releaseServerSlot()"), "\(name): the key is already gone: nothing to DELETE")
        }
        XCTAssertTrue(rebuild.contains("relayRecovery = .reconnecting"))
        XCTAssertTrue(rebuild.contains("self.connectMultiHop(entry: pair.entry, exit: pair.exit)"))
        XCTAssertTrue(move.contains("relayRecovery = .tryingAnotherServer"))
    }

    /// MR-2525: the attempt itself. A plain mint (the server has removed the
    /// key, so there is nothing to defer), capped; the new peer onto the
    /// RUNNING session; never a stop, never a change to the kill switch;
    /// the directive from the one table; the flag lowered and an unkept key
    /// released on every exit; the attempt's PQ pair ended.
    func testTheInPlaceAttemptNeverStopsTheSession() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let attempt = slice(vm, from: "private func attemptRedialInPlace(", to: "\n    }\n")
        let redial = slice(vm, from: "private func redialInPlace(", to: "\n    }\n")
        let commit = slice(vm, from: "private func commitRedial(", to: "\n    }\n")
        for (name, body) in [("attempt", attempt), ("redial", redial), ("commit", commit)] {
            for forbidden in ["vpnManager.disconnect()", "applyKillSwitchFlag", "failOpenAndStop", "rebuildOf",
                              "releaseServerSlot()", "disconnect()"] {
                XCTAssertFalse(body.contains(forbidden), "\(name) contains \(forbidden)")
            }
        }
        XCTAssertTrue(attempt.contains("guard isConnected, !isConnecting else { return RedialAttempt(step: .sessionNotUp) }"))
        XCTAssertTrue(attempt.contains("try await vpnManager.swapConfig(config: config)"))
        XCTAssertTrue(attempt.contains("MultiHopRouteCheck.validate("))
        XCTAssertTrue(attempt.contains("await awaitNewPeerHandshake()"))
        XCTAssertTrue(attempt.contains("api.getConnectConfig(serverId: ids.first)"))
        XCTAssertTrue(attempt.contains("api.getMultiHopConfig(entryId: ids.first, exitId: exitId)"))
        XCTAssertTrue(attempt.contains("try? await Task.sleep(for: HeartbeatRedial.mintCap)"))
        XCTAssertTrue(attempt.contains("work.cancel()"))
        XCTAssertTrue(attempt.contains("defer { BirdoPQManager.shared.discardHandOff(publicKey: config.pqUploadedKey) }"))
        // An answered request is recorded as every dial records it, and only then
        // is it the outcome; an unanswered one goes to the disconnect-first path.
        let answered = try XCTUnwrap(attempt.range(of: "HeartbeatRedial.serverAnswered(error)"))
        let hold = try XCTUnwrap(attempt.range(of: "holdAutomaticDials(after: error)"))
        let refused = try XCTUnwrap(attempt.range(of: "return RedialAttempt(step: .mintRefused)"))
        XCTAssertLessThan(answered.lowerBound, hold.lowerBound)
        XCTAssertLessThan(hold.lowerBound, refused.lowerBound)
        XCTAssertTrue(attempt.contains("return RedialAttempt(step: .mintFailed)"))
        XCTAssertTrue(redial.contains("let directive = HeartbeatRedial.directive(after: attempt.step)"))
        XCTAssertTrue(redial.contains("isRebuildingLive = false"))
        XCTAssertTrue(redial.contains("release(keyId: newId)"))
        // The one stop: a refused request, through its own helper, nowhere else.
        XCTAssertTrue(redial.contains("case .stopAfterRefusal:\n            stopAfterRefusedRedial()"))
        XCTAssertEqual(vm.components(separatedBy: "stopAfterRefusedRedial()").count - 1, 2,
                       "its declaration and its one call")
        let stop = slice(vm, from: "private func stopAfterRefusedRedial() {", to: "\n    }\n")
        XCTAssertTrue(stop.contains("setActiveKeyId(nil)"), "the gone key's handle is dropped, never DELETEd")
        XCTAssertFalse(stop.contains("releaseServerSlot()"))
        XCTAssertTrue(commit.contains("clearCircuitBreaker()"))
        XCTAssertTrue(commit.contains("lastHeartbeatOkAt = Date()"))
    }

    /// Review of #662 (F2): a sign-out and an account deletion stop an attempt
    /// that is out, as a user disconnect does. Both used to leave the
    /// generation alone until `resetForLogout()`'s `disconnect()` ran after the
    /// DELETE, so for up to ~31 s the attempt could still install its key in
    /// the shared keychain over the teardown, or dial again. And the attempt
    /// installs nothing once the session is gone.
    func testASignOutOrADeletionStopsTheInPlaceAttempt() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        for start in ["func disconnectForSignOut() async {", "func tearDownForAccountDeletion() -> Bool {"] {
            let body = slice(vm, from: start, to: "\n    }\n")
            let bump = try XCTUnwrap(body.range(of: "relayRetryGeneration &+= 1"), start)
            let stop = try XCTUnwrap(body.range(of: "vpnManager.disconnect()"), start)
            XCTAssertLessThan(bump.lowerBound, stop.lowerBound, start)
        }
        let attempt = slice(vm, from: "private func attemptRedialInPlace(", to: "\n    }\n")
        let guarded = try XCTUnwrap(attempt.range(of:
            "guard isConnected else { return ended(.sessionGoneBeforeSwap, route: route) }"))
        let install = try XCTUnwrap(attempt.range(of: "setActiveKeyId(config.keyId)"))
        XCTAssertLessThan(guarded.lowerBound, install.lowerBound)
    }

    // MARK: - MR-2525: the in-place table (HeartbeatRedial)

    /// Only a new peer that delivered bytes is kept.
    func testOnlyAProvenNewPeerIsKept() {
        for step in HeartbeatRedialStep.allCases {
            XCTAssertEqual(HeartbeatRedial.directive(after: step) == .commit, step == .newPeerHandshaked, "\(step)")
        }
    }

    /// A key minted for an attempt that is not kept is always released, so it
    /// never holds the account's slot; with nothing minted there is nothing to
    /// release. Every attempt not kept and not overtaken by the user ends on
    /// the disconnect-first path: never worse than before MR-2525.
    func testAnUnkeptAttemptReleasesItsKeyAndFallsBack() {
        let afterTheMint: [HeartbeatRedialStep] = [.routeNotConfirmed, .sessionGoneBeforeSwap, .swapFailed, .newPeerSilent]
        for step in afterTheMint {
            XCTAssertEqual(HeartbeatRedial.directive(after: step), .disconnectFirst(releaseNewKey: true), "\(step)")
        }
        for step in [HeartbeatRedialStep.sessionNotUp, .mintFailed] {
            XCTAssertEqual(HeartbeatRedial.directive(after: step), .disconnectFirst(releaseNewKey: false), "\(step)")
        }
        XCTAssertEqual(Set(HeartbeatRedialStep.allCases.map { "\($0)" }),
                       Set((afterTheMint + [.sessionNotUp, .mintFailed, .mintRefused, .userActed, .newPeerHandshaked])
                           .map { "\($0)" }),
                       "a new step must be placed in this table on purpose")
    }

    /// A server that ANSWERED the in-place request with a refusal or an error
    /// has given the outcome, as it would to the disconnect-first path's own
    /// dial: the session ends with its words and nothing is asked again. Only
    /// an unanswered request (a transport failure, the cap) earns that second
    /// dial, outside the old path.
    func testAnAnsweredRefusalEndsTheSessionAndOnlyNoAnswerDialsAgain() {
        XCTAssertEqual(HeartbeatRedial.directive(after: .mintRefused), .stopAfterRefusal)
        for step in HeartbeatRedialStep.allCases where step != .mintRefused {
            XCTAssertNotEqual(HeartbeatRedial.directive(after: step), .stopAfterRefusal, "\(step)")
        }
        struct Refused: Error {}
        XCTAssertTrue(HeartbeatRedial.serverAnswered(Refused()))
        XCTAssertTrue(HeartbeatRedial.serverAnswered(NSError(domain: "HTTP", code: 503)))
        XCTAssertFalse(HeartbeatRedial.serverAnswered(URLError(.timedOut)))
        XCTAssertFalse(HeartbeatRedial.serverAnswered(URLError(.networkConnectionLost)))
        XCTAssertFalse(HeartbeatRedial.serverAnswered(URLError(.cancelled)), "a failed TLS pin check is no answer")
        XCTAssertFalse(HeartbeatRedial.serverAnswered(CancellationError()), "the cap is no answer")
    }

    /// The user acting meanwhile (a disconnect, a sign-out) wins: the attempt
    /// releases what it minted and changes nothing else; nothing is dialled.
    func testTheUserActingMeanwhileWins() {
        XCTAssertEqual(HeartbeatRedial.directive(after: .userActed), .standDown(releaseNewKey: true))
        for step in HeartbeatRedialStep.allCases where step != .userActed {
            if case .standDown = HeartbeatRedial.directive(after: step) { XCTFail("\(step) stands down") }
        }
    }

    /// The blocked wait is bounded: the capped mint plus the new peer's probe
    /// window stays inside 30 s before the fallback runs.
    func testTheInPlaceAttemptIsBounded() {
        XCTAssertEqual(HeartbeatRedial.mintCap, .seconds(8))
        XCTAssertLessThanOrEqual(HeartbeatRedial.mintCap + .milliseconds(LiveRebuildProbe.windowMs), .seconds(30))
    }

    func testTheQuietRebuildSaysReconnecting() {
        XCTAssertEqual(RelayRecovery.reconnecting.label, "Reconnecting…")
        XCTAssertEqual(RelayRecovery.reconnecting.label, ConnectionPhase.reconnectingLabel)
    }

    /// The Free grace notice reaches Home, with the way out.
    func testHomeShowsTheGraceNotice() throws {
        let home = try source("iosApp/Views/HomeView.swift")
        let notice = slice(home, from: "if vpnVM.isConnected, let grace = vpnVM.quotaGrace {",
                           to: ".accessibilityIdentifier(\"home_quota_grace\")")
        XCTAssertTrue(notice.contains("Text(grace.message)"))
        XCTAssertTrue(notice.contains("showPlans = true"), "the notice offers the plans, as on Android")
    }
}
