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
        XCTAssertNil(HeartbeatPolicy.moveTarget(from: "zz-9", in: gone, cached: cached, origin: .automatic),
                     "a node neither list knows: no move")
        XCTAssertEqual(HeartbeatEnding.serverOffline, HeartbeatEnding(message: serverGoneCopy, offersPlans: false))
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

    /// The quiet rebuild and the move: disconnect-first behind the teardown
    /// gate, never the user's own connect (so neither refills the MR-2212
    /// retry nor clears the breaker on a single-hop session), and Home says
    /// what is under way. Neither is between `retryOnAnotherRelay` and
    /// `legacyRebuild`, whose log lines RelayRetryTests counts.
    func testTheRebuildAndTheMoveAreDisconnectFirstAndSayWhatTheyDo() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let rebuild = slice(vm, from: "private func rebuildAfterReap() {", to: "\n    }\n")
        let move = slice(vm, from: "private func moveOffDrainedServer() {", to: "\n    }\n")
        for path in [rebuild, move] {
            XCTAssertTrue(path.contains("isTearingDownForRedial = true"))
            XCTAssertTrue(path.contains("await self?.awaitTeardown()"))
            XCTAssertTrue(path.contains("guard generation == self.relayRetryGeneration else { return }"))
            XCTAssertTrue(path.contains("self.connect(userInitiated: false)"))
            XCTAssertFalse(path.contains("releaseServerSlot()"), "the key is already gone: nothing to DELETE")
        }
        XCTAssertTrue(rebuild.contains("relayRecovery = .reconnecting"))
        XCTAssertTrue(rebuild.contains("self.connectMultiHop(entry: pair.entry, exit: pair.exit)"))
        XCTAssertTrue(move.contains("relayRecovery = .tryingAnotherServer"))
        XCTAssertTrue(move.contains("HeartbeatPolicy.moveTarget(from: goneId, in: self.servers, cached: cached,"))
        XCTAssertTrue(move.contains("self.error = HeartbeatEnding.serverOffline.message"))
        let retryWindow = slice(vm, from: "private func retryOnAnotherRelay() -> Bool {", to: "private func legacyRebuild(")
        XCTAssertFalse(retryWindow.contains("rebuildAfterReap") || retryWindow.contains("moveOffDrainedServer"))
        // The grace notice and the reap clock belong to one session.
        let reset = slice(vm, from: "private func resetSessionState() {", to: "\n    }\n")
        XCTAssertTrue(reset.contains("quotaGrace = nil"))
        XCTAssertTrue(reset.contains("lastHeartbeatOkAt = nil"))
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
