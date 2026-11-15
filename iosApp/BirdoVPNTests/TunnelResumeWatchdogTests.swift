import XCTest

/// MR-953 F4 (review of #621) — pins `TunnelResumeWatchdog`, the retry timer
/// behind a failed resume in the vendored WireGuardAdapter (local patch 5).
///
/// Before: when the network settings could not be re-applied after a path
/// change, the adapter logged it and waited for the NEXT path event. If none
/// came it stayed paused on a good network: the system said connected, the
/// provider's liveness check saw nothing (a paused adapter has no runtime
/// configuration) and the device had no connectivity. These run the real
/// watchdog on a real serial queue with short delays.
final class TunnelResumeWatchdogTests: XCTestCase {

    /// The watchdog and what it was asked to do, all confined to `queue`
    /// exactly as the adapter confines them to its work queue.
    private final class Rig: @unchecked Sendable {
        let queue = DispatchQueue(label: "TunnelResumeWatchdogTests")
        private(set) var attempts = 0
        private(set) var exhausted = 0
        /// What the next timed retries find, in order; `.failed` once it runs out.
        var script: [TunnelResumeWatchdog.Attempt] = []
        private(set) var watchdog: TunnelResumeWatchdog!

        init(delays: [TimeInterval]) {
            watchdog = TunnelResumeWatchdog(
                queue: queue,
                delays: delays,
                attempt: { [unowned self] in
                    self.attempts += 1
                    return self.script.isEmpty ? .failed : self.script.removeFirst()
                },
                onExhausted: { [unowned self] in self.exhausted += 1 }
            )
        }

        func onQueue(_ body: (TunnelResumeWatchdog) -> Void) {
            queue.sync { body(watchdog) }
        }

        /// (attempts, exhausted) after the queue has been idle for `seconds`.
        func settled(after seconds: TimeInterval) -> (attempts: Int, exhausted: Int) {
            Thread.sleep(forTimeInterval: seconds)
            return queue.sync { (attempts, exhausted) }
        }
    }

    /// The case the review found: one failed resume, then NO further path
    /// event. The retries run on their own, and when they are used up the
    /// owner is told exactly once.
    func testAFailedResumeIsRetriedOnATimerAndThenReportedOnce() {
        let rig = Rig(delays: [0.05, 0.05])
        rig.onQueue { $0.pathResumeFailed() }
        let result = rig.settled(after: 0.5)
        XCTAssertEqual(result.attempts, 2, "one timed retry per delay")
        XCTAssertEqual(result.exhausted, 1, "the owner hears about it once")
        // Nothing more happens afterwards: no timer, no second report.
        XCTAssertEqual(rig.settled(after: 0.2).attempts, 2)
        rig.onQueue { $0.pathResumeFailed() }
        let later = rig.settled(after: 0.2)
        XCTAssertEqual(later.attempts, 2)
        XCTAssertEqual(later.exhausted, 1)
    }

    func testARetryThatResumesStopsTheWatchdog() {
        let rig = Rig(delays: [0.05, 0.05])
        rig.script = [.resumed]
        rig.onQueue { $0.pathResumeFailed() }
        let result = rig.settled(after: 0.4)
        XCTAssertEqual(result.attempts, 1)
        XCTAssertEqual(result.exhausted, 0)
    }

    /// Paused but offline, or no longer paused: the next path event owns the
    /// resume, and nothing is counted against the tunnel.
    func testARetryWithNothingToResumeStandsDown() {
        let rig = Rig(delays: [0.05, 0.05])
        rig.script = [.notNeeded]
        rig.onQueue { $0.pathResumeFailed() }
        let result = rig.settled(after: 0.4)
        XCTAssertEqual(result.attempts, 1)
        XCTAssertEqual(result.exhausted, 0)
    }

    /// Stop, a successful resume by a path event, or going offline: the
    /// pending retry must not fire afterwards.
    func testResetCancelsThePendingRetry() {
        let rig = Rig(delays: [0.1, 0.1])
        rig.onQueue { $0.pathResumeFailed() }
        rig.onQueue { $0.reset() }
        let result = rig.settled(after: 0.4)
        XCTAssertEqual(result.attempts, 0)
        XCTAssertEqual(result.exhausted, 0)
    }

    /// Path events arrive in bursts. A burst of failed resumes is one failure:
    /// it must not use up the retries in a single second.
    func testABurstOfPathFailuresDoesNotExhaustTheRetries() {
        let rig = Rig(delays: [0.15, 0.15])
        rig.onQueue { watchdog in
            for _ in 0..<5 { watchdog.pathResumeFailed() }
        }
        XCTAssertEqual(rig.settled(after: 0.05).exhausted, 0, "exhausted by the burst itself")
        let result = rig.settled(after: 0.6)
        XCTAssertEqual(result.attempts, 2)
        XCTAssertEqual(result.exhausted, 1)
    }

    /// After a reset the watchdog starts from nothing: a fresh failure gets
    /// the full set of retries again, and can be reported again.
    func testAfterAResetANewFailureGetsItsOwnRetriesAndReport() {
        let rig = Rig(delays: [0.05])
        rig.onQueue { $0.pathResumeFailed() }
        XCTAssertEqual(rig.settled(after: 0.3).exhausted, 1)
        rig.onQueue { $0.reset() }
        rig.onQueue { $0.pathResumeFailed() }
        let result = rig.settled(after: 0.3)
        XCTAssertEqual(result.attempts, 2)
        XCTAssertEqual(result.exhausted, 2)
    }

    /// With no timed retries configured the first failure is reported at once.
    func testNoDelaysMeansTheFirstFailureIsReported() {
        let rig = Rig(delays: [])
        rig.onQueue { $0.pathResumeFailed() }
        let result = rig.settled(after: 0.1)
        XCTAssertEqual(result.attempts, 0)
        XCTAssertEqual(result.exhausted, 1)
    }

    /// Two timed retries, 5 s and 10 s: with the path event's own attempt and
    /// up to two 5 s settings waits per attempt, about 45 s to the report.
    func testTheProductionScheduleIsTwoRetries() {
        XCTAssertEqual(TunnelResumeWatchdog.defaultDelays, [5, 10])
    }

    // MARK: - The retry's guard (the adapter's retryResume, re-review of #621)

    /// Paused on a satisfied path: the same paused settings are resumed, once,
    /// and the answer is what the resume says.
    func testARetryWhilePausedOnASatisfiedPathResumesThePausedTunnel() {
        var resumed: [String] = []
        XCTAssertEqual(TunnelResumeWatchdog.retry(paused: "settings-1", pathSatisfied: true) { settings in
            resumed.append(settings)
            return true
        }, .resumed)
        XCTAssertEqual(TunnelResumeWatchdog.retry(paused: "settings-2", pathSatisfied: true) { settings in
            resumed.append(settings)
            return false
        }, .failed)
        XCTAssertEqual(resumed, ["settings-1", "settings-2"])
    }

    /// No longer paused (resumed by a path event, or stopped): nothing to do,
    /// and nothing is counted against the tunnel.
    func testARetryWhenNotPausedTouchesNothing() {
        var calls = 0
        let attempt = TunnelResumeWatchdog.retry(paused: String?.none, pathSatisfied: true) { _ in
            calls += 1
            return true
        }
        XCTAssertEqual(attempt, .notNeeded)
        XCTAssertEqual(calls, 0)
    }

    /// Paused but offline: the next satisfied path event owns the resume; a
    /// retry now would only fail and use up the budget.
    func testARetryOnAnUnsatisfiedPathTouchesNothing() {
        var calls = 0
        let attempt = TunnelResumeWatchdog.retry(paused: "settings", pathSatisfied: false) { _ in
            calls += 1
            return true
        }
        XCTAssertEqual(attempt, .notNeeded)
        XCTAssertEqual(calls, 0)
    }

    /// The guard and the timer together: a retry that finds the tunnel no
    /// longer paused stands the watchdog down for good, with no report.
    func testTheGuardStandsTheWatchdogDownWhenThePauseIsOver() {
        /// The adapter's side, confined to `queue` like the adapter's state.
        final class Adapter: @unchecked Sendable {
            let queue = DispatchQueue(label: "TunnelResumeWatchdogTests.guard")
            var paused: String? = "settings"
            var attempts = 0
            var resumes = 0
            var exhausted = 0
            private(set) var watchdog: TunnelResumeWatchdog!

            init() {
                watchdog = TunnelResumeWatchdog(
                    queue: queue, delays: [0.05, 0.05],
                    attempt: { [unowned self] in
                        self.attempts += 1
                        return TunnelResumeWatchdog.retry(paused: self.paused, pathSatisfied: true) { _ in
                            self.resumes += 1
                            // This resume fails, and a path event then resumes
                            // the tunnel before the next timed retry.
                            self.paused = nil
                            return false
                        }
                    },
                    onExhausted: { [unowned self] in self.exhausted += 1 })
            }
        }
        let adapter = Adapter()
        adapter.queue.sync { adapter.watchdog.pathResumeFailed() }
        Thread.sleep(forTimeInterval: 0.5)   // both timed retries are due by 0.1 s
        adapter.queue.sync {
            XCTAssertEqual(adapter.attempts, 2, "the second timed retry ran")
            XCTAssertEqual(adapter.resumes, 1, "no resume once the pause was over")
            XCTAssertEqual(adapter.exhausted, 0, "a tunnel that resumed is not reported")
        }
    }
}
