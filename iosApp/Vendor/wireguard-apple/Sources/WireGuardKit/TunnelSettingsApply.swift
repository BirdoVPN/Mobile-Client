// SPDX-License-Identifier: MIT
// BIRDO LOCAL PATCH 5 (MR-953 F4). Not upstream; see iosApp/project.yml.

import Foundation

/// How `WireGuardAdapter.setNetworkSettings` waits for the system to apply the
/// tunnel's network settings.
///
/// Upstream waited 5 s for `setTunnelNetworkSettings`' completion and, when it
/// never came, logged "proceeding anyway" and brought WireGuard up. The routes,
/// addresses and DNS may then never have been applied, and the tunnel still
/// reported itself up: with the kill switch off, traffic left outside it while
/// the app showed Protected.
///
/// Now the settings are asked for at most `attempts` times, each waiting
/// `timeout` seconds; no answer at all fails the start (or update, or resume)
/// with `TunnelSettingsApplyTimedOut`.
///
/// Which answer decides:
///   - a SUCCESS from any request is final: a late success to the first
///     request still counts during the second wait;
///   - an ERROR is final only from the LATEST request. An error that arrives
///     late from an earlier, timed-out request (the system cancelling it
///     because the re-ask superseded it, say) is not the answer to the request
///     now in flight, and must not fail a start that request then completes.
///
/// Also fixed on the way: upstream signalled its NSCondition without holding
/// the lock and waited without a predicate, so an answer that arrived before
/// the wait began was lost and read as a timeout. Harmless while a timeout
/// meant "proceed"; fatal once it means "fail". `issue` is called with the
/// lock NOT held, so an answer delivered synchronously cannot deadlock.
///
/// Foundation-only, so BirdoVPNTests compiles this file directly
/// (TunnelSettingsApplyTests).
enum TunnelSettingsApply {
    /// Two requests: a lost completion (the case upstream's comment
    /// describes) gets one fresh one before the tunnel is failed.
    static let defaultAttempts = 2
    /// Upstream's per-request wait, unchanged.
    static let defaultTimeout: TimeInterval = 5

    /// - Parameters:
    ///   - attempts: how many times to ask (at least 1).
    ///   - timeout: seconds to wait after each request.
    ///   - log: told about each timeout.
    ///   - issue: makes ONE request and calls its argument with the system's
    ///     answer (nil = applied), or never.
    /// - Returns: nil when applied; otherwise the system's error, or
    ///   `TunnelSettingsApplyTimedOut` when no request was answered in time.
    static func run(attempts: Int = defaultAttempts,
                    timeout: TimeInterval = defaultTimeout,
                    log: (String) -> Void,
                    issue: (@escaping (Error?) -> Void) -> Void) -> Error? {
        let answer = Answer()
        let total = max(1, attempts)
        for attempt in 1...total {
            // An error that landed between the last wait and here came from
            // what was then the latest request: it is final, nothing is re-asked.
            if let outcome = answer.begin(attempt) {
                return outcome
            }
            issue { error in answer.deliver(error, from: attempt) }
            if let outcome = answer.wait(until: Date().addingTimeInterval(timeout)) {
                return outcome
            }
            if attempt < total {
                log("setTunnelNetworkSettings timed out after \(Int(timeout)) seconds; asking again")
            } else {
                log("setTunnelNetworkSettings timed out after \(Int(timeout)) seconds, \(total) times; failing")
            }
        }
        return TunnelSettingsApplyTimedOut(attempts: total, timeout: timeout)
    }

    /// The deciding answer (see the type's comment), guarded by its own
    /// condition.
    private final class Answer {
        private let condition = NSCondition()
        private var latest = 0
        private var decided = false
        private var error: Error?

        /// Request `attempt` is about to be made. Returns the outcome when one
        /// is already final.
        func begin(_ attempt: Int) -> Error?? {
            condition.lock()
            defer { condition.unlock() }
            if decided { return .some(error) }
            latest = attempt
            return nil
        }

        func deliver(_ error: Error?, from attempt: Int) {
            condition.lock()
            defer { condition.unlock() }
            guard !decided else { return }
            // An earlier request's error is not final; its success is.
            if error != nil && attempt != latest { return }
            decided = true
            self.error = error
            condition.broadcast()
        }

        /// `.some(nil)` = applied, `.some(error)` = refused, `nil` = still
        /// undecided at `deadline`.
        func wait(until deadline: Date) -> Error?? {
            condition.lock()
            defer { condition.unlock() }
            while !decided {
                if !condition.wait(until: deadline) { break }
            }
            return decided ? .some(error) : nil
        }
    }
}

/// No `setTunnelNetworkSettings` request was answered in time. Carried by
/// `WireGuardAdapterError.setNetworkSettings`.
struct TunnelSettingsApplyTimedOut: Error, Equatable, CustomStringConvertible {
    let attempts: Int
    let timeout: TimeInterval

    var description: String {
        "setTunnelNetworkSettings was not answered in \(Int(timeout)) seconds, \(attempts) times"
    }
}
