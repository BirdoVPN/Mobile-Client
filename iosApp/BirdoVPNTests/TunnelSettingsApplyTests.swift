import XCTest

/// MR-953 F4 — pins `TunnelSettingsApply`, WireGuardKit local patch 5.
///
/// Before: `WireGuardAdapter.setNetworkSettings` waited 5 s and, with no answer
/// from `setTunnelNetworkSettings`, logged "proceeding anyway" and started
/// WireGuard on settings the system may never have applied. Now one re-ask,
/// then the start fails. These run the real waiting code with short timeouts.
final class TunnelSettingsApplyTests: XCTestCase {

    private struct Refused: Error, Equatable {}

    /// Lets a test hand the completion to another thread under Swift 6.
    private final class Handoff: @unchecked Sendable {
        let deliver: (Error?) -> Void
        init(_ deliver: @escaping (Error?) -> Void) { self.deliver = deliver }
    }

    func testNoAnswerAtAllFailsAfterTheRetryInsteadOfProceeding() {
        var requests = 0
        var logged: [String] = []
        let failure = TunnelSettingsApply.run(attempts: 2, timeout: 0.05,
                                              log: { logged.append($0) },
                                              issue: { _ in requests += 1 })
        XCTAssertEqual(requests, 2, "one request, then exactly one re-ask")
        XCTAssertEqual(failure as? TunnelSettingsApplyTimedOut,
                       TunnelSettingsApplyTimedOut(attempts: 2, timeout: 0.05))
        XCTAssertEqual(logged.count, 2)
        XCTAssertFalse(logged.joined().contains("proceeding anyway"))
    }

    func testAnAnswerToTheFirstRequestApplies() {
        var requests = 0
        let failure = TunnelSettingsApply.run(attempts: 2, timeout: 1, log: { _ in },
                                              issue: { done in requests += 1; done(nil) })
        XCTAssertNil(failure)
        XCTAssertEqual(requests, 1, "an answered request is not repeated")
    }

    func testTheSystemsErrorIsReturnedNotRetried() {
        var requests = 0
        let failure = TunnelSettingsApply.run(attempts: 2, timeout: 1, log: { _ in },
                                              issue: { done in requests += 1; done(Refused()) })
        XCTAssertEqual(failure as? Refused, Refused())
        XCTAssertEqual(requests, 1)
    }

    /// The lost completion upstream's comment describes: the first request is
    /// never answered, the re-ask is.
    func testTheReAskRecoversALostCompletion() {
        var requests = 0
        var logged: [String] = []
        let failure = TunnelSettingsApply.run(attempts: 2, timeout: 0.05,
                                              log: { logged.append($0) },
                                              issue: { done in
                                                  requests += 1
                                                  if requests == 2 { done(nil) }
                                              })
        XCTAssertNil(failure)
        XCTAssertEqual(requests, 2)
        XCTAssertEqual(logged.count, 1, "the first timeout is logged once")
    }

    /// A slow answer to the FIRST request still counts while the re-ask waits.
    func testALateAnswerToTheFirstRequestCountsDuringTheSecondWait() {
        var requests = 0
        let failure = TunnelSettingsApply.run(attempts: 2, timeout: 0.3, log: { _ in },
                                              issue: { done in
                                                  requests += 1
                                                  guard requests == 1 else { return }
                                                  let handoff = Handoff(done)
                                                  DispatchQueue.global().asyncAfter(deadline: .now() + 0.4) {
                                                      handoff.deliver(nil)
                                                  }
                                              })
        XCTAssertNil(failure)
        XCTAssertEqual(requests, 2)
    }

    // MARK: - Which answer is final (review of #621)

    /// The first request times out and is asked again; its completion then
    /// arrives late WITH AN ERROR (the system cancelling the superseded
    /// request, say), and the re-ask succeeds. The settings are applied: the
    /// stale error must not fail the start. Before, the first answer of any
    /// request won, so this returned the error.
    func testALateErrorFromTheFirstRequestDoesNotOverrideTheReAsksSuccess() {
        var handoffs: [Handoff] = []
        let failure = TunnelSettingsApply.run(attempts: 2, timeout: 1, log: { _ in },
                                              issue: { done in
                                                  handoffs.append(Handoff(done))
                                                  guard handoffs.count == 2 else { return }
                                                  let first = handoffs[0], second = handoffs[1]
                                                  DispatchQueue.global().async {
                                                      first.deliver(Refused())
                                                      second.deliver(nil)
                                                  }
                                              })
        XCTAssertNil(failure, "an earlier request's late error overrode the latest request's success")
        XCTAssertEqual(handoffs.count, 2)
    }

    /// With only that stale error and no answer to the re-ask, the outcome is
    /// the timeout: the error was not about the request in flight.
    func testAnEarlierRequestsLateErrorAloneIsStillATimeout() {
        var handoffs: [Handoff] = []
        let failure = TunnelSettingsApply.run(attempts: 2, timeout: 0.2, log: { _ in },
                                              issue: { done in
                                                  handoffs.append(Handoff(done))
                                                  guard handoffs.count == 2 else { return }
                                                  let first = handoffs[0]
                                                  DispatchQueue.global().async { first.deliver(Refused()) }
                                              })
        XCTAssertEqual(failure as? TunnelSettingsApplyTimedOut,
                       TunnelSettingsApplyTimedOut(attempts: 2, timeout: 0.2))
    }

    /// The LATEST request's error is final.
    func testTheLatestRequestsErrorIsFinal() {
        var requests = 0
        let failure = TunnelSettingsApply.run(attempts: 2, timeout: 0.05, log: { _ in },
                                              issue: { done in
                                                  requests += 1
                                                  if requests == 2 { done(Refused()) }
                                              })
        XCTAssertEqual(failure as? Refused, Refused())
        XCTAssertEqual(requests, 2)
    }

    /// A success is final from ANY request, even when the latest then errors.
    func testAnEarlierRequestsLateSuccessIsFinal() {
        var handoffs: [Handoff] = []
        let failure = TunnelSettingsApply.run(attempts: 2, timeout: 1, log: { _ in },
                                              issue: { done in
                                                  handoffs.append(Handoff(done))
                                                  guard handoffs.count == 2 else { return }
                                                  let first = handoffs[0], second = handoffs[1]
                                                  DispatchQueue.global().async {
                                                      first.deliver(nil)
                                                      second.deliver(Refused())
                                                  }
                                              })
        XCTAssertNil(failure)
    }

    /// Upstream signalled without the lock and waited without a predicate, so
    /// an answer that landed before the wait began was lost and read as a
    /// timeout. Harmless while a timeout meant "proceed", fatal once it fails
    /// the tunnel. Answers delivered at once, from another thread, must apply.
    func testAnAnswerFromAnotherThreadBeforeTheWaitIsNotLost() {
        for _ in 0..<50 {
            let issued = DispatchSemaphore(value: 0)
            let failure = TunnelSettingsApply.run(attempts: 1, timeout: 2, log: { _ in },
                                                  issue: { done in
                                                      let handoff = Handoff(done)
                                                      DispatchQueue.global().async {
                                                          handoff.deliver(nil)
                                                          issued.signal()
                                                      }
                                                      // Hold `issue` until the answer is in, so it
                                                      // always lands BEFORE the wait starts.
                                                      issued.wait()
                                                  })
            XCTAssertNil(failure)
        }
    }

    /// Only the first answer decides; a second one cannot overwrite it.
    func testOnlyTheFirstAnswerCounts() {
        let failure = TunnelSettingsApply.run(attempts: 1, timeout: 1, log: { _ in },
                                              issue: { done in done(nil); done(Refused()) })
        XCTAssertNil(failure)
    }

    func testAtLeastOneRequestIsAlwaysMade() {
        var requests = 0
        _ = TunnelSettingsApply.run(attempts: 0, timeout: 0.01, log: { _ in },
                                    issue: { _ in requests += 1 })
        XCTAssertEqual(requests, 1)
    }

    func testTheProductionBudgetIsTwoFiveSecondWaits() {
        XCTAssertEqual(TunnelSettingsApply.defaultAttempts, 2)
        XCTAssertEqual(TunnelSettingsApply.defaultTimeout, 5)
    }
}
