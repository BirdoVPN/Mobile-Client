// SPDX-License-Identifier: MIT
// BIRDO LOCAL PATCH 5 (MR-953 F4). Not upstream; see iosApp/project.yml.

import Foundation

/// What happens after a RESUME fails.
///
/// On iOS `WireGuardAdapter` turns WireGuard off when the device goes offline
/// and brings it back on the next satisfied path: it re-applies the network
/// settings and restarts the backend. Since patch 5 that re-apply FAILS when
/// the system does not answer (TunnelSettingsApply), and upstream's only retry
/// was the next `NWPathMonitor` event, which may never come: the adapter then
/// sat paused on a perfectly good network, `NEVPNStatus` said connected, the
/// provider's liveness check could not see it (a paused adapter has no runtime
/// configuration to read) and the device had no connectivity at all.
///
/// This is the watchdog for that state. After a failed resume it retries on a
/// timer (`delays`: the wait before each retry), and when the retries are used
/// up it tells its owner ONCE (until `reset()`), so the provider can treat the tunnel as dead
/// (PacketTunnelProvider routes it into its circuit breaker, like any other
/// dead data plane). It never starts WireGuard itself and never reports a
/// tunnel up: the paused tunnel keeps holding traffic until a resume really
/// succeeds or the provider tears it down.
///
/// Not thread-safe by itself: every method, and the timer, run on `queue`
/// (the adapter's work queue). That confinement is why it is
/// `@unchecked Sendable`: the timer's work item (a `@Sendable` block under
/// Swift 6, which compiles this file in BirdoVPNTests) only ever runs on
/// that same queue.
///
/// Foundation-only, so BirdoVPNTests compiles this file directly
/// (TunnelResumeWatchdogTests).
final class TunnelResumeWatchdog: @unchecked Sendable {
    /// What one retry found.
    enum Attempt {
        /// The backend is running again.
        case resumed
        /// The resume failed again.
        case failed
        /// Nothing to resume: the adapter is no longer paused, or the path is
        /// not satisfied (the next path event owns the resume then).
        case notNeeded
    }

    /// Two timed retries, 5 s and 10 s after the failure before them: with the
    /// attempt the path event made, three in all. Each attempt can itself take
    /// two 5 s settings waits, so the owner hears about a tunnel that cannot
    /// resume within about 45 s.
    static let defaultDelays: [TimeInterval] = [5, 10]

    private let queue: DispatchQueue
    private let delays: [TimeInterval]
    private let attempt: () -> Attempt
    private let onExhausted: () -> Void

    private var failures = 0
    private var exhausted = false
    private var pending: DispatchWorkItem?

    /// - Parameters:
    ///   - queue: the serial queue everything here runs on.
    ///   - delays: the wait before each timed retry; its count is the number
    ///     of timed retries.
    ///   - attempt: tries the resume once.
    ///   - onExhausted: called once when a resume has failed `delays.count + 1`
    ///     times in a row.
    init(queue: DispatchQueue,
         delays: [TimeInterval] = TunnelResumeWatchdog.defaultDelays,
         attempt: @escaping () -> Attempt,
         onExhausted: @escaping () -> Void) {
        self.queue = queue
        self.delays = delays
        self.attempt = attempt
        self.onExhausted = onExhausted
    }

    /// A resume the PATH MONITOR attempted has failed. Starts the retry timer
    /// unless one is already running: path events can arrive in bursts, and a
    /// burst of failures is one failure for this purpose, not three.
    func pathResumeFailed() {
        guard pending == nil else { return }
        resumeFailed()
    }

    /// A resume has just failed. Schedules the next retry, or reports
    /// exhaustion. After exhaustion nothing more is scheduled or reported
    /// until `reset()`.
    private func resumeFailed() {
        guard !exhausted else { return }
        pending?.cancel()
        pending = nil
        failures += 1
        guard failures <= delays.count else {
            exhausted = true
            onExhausted()
            return
        }
        let item = DispatchWorkItem { [weak self] in self?.fire() }
        pending = item
        queue.asyncAfter(deadline: .now() + delays[failures - 1], execute: item)
    }

    /// One timed retry, decided. It resumes only while the adapter is still
    /// paused (`paused` is the paused tunnel's settings, nil when it is not
    /// paused) AND the path is satisfied; otherwise the next path event owns
    /// the resume and nothing is counted against the tunnel. `resume` runs at
    /// most once and says whether the backend is running again.
    ///
    /// The adapter's `retryResume` only supplies its state to this, so the
    /// rule runs in TunnelResumeWatchdogTests without WireGuardKit.
    static func retry<Settings>(paused: Settings?, pathSatisfied: Bool,
                                resume: (Settings) -> Bool) -> Attempt {
        guard let paused, pathSatisfied else { return .notNeeded }
        return resume(paused) ? .resumed : .failed
    }

    /// The tunnel resumed, was stopped, or went offline: forget the failures
    /// and drop any pending retry.
    func reset() {
        pending?.cancel()
        pending = nil
        failures = 0
        exhausted = false
    }

    private func fire() {
        pending = nil
        switch attempt() {
        case .resumed, .notNeeded:
            reset()
        case .failed:
            resumeFailed()
        }
    }
}
