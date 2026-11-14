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
/// with `TunnelSettingsApplyTimedOut`. The first answer from ANY attempt
/// decides: a late answer to the first request still counts during the second
/// wait.
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
            issue { error in answer.deliver(error) }
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

    /// The first answer, guarded by its own condition.
    private final class Answer {
        private let condition = NSCondition()
        private var answered = false
        private var error: Error?

        func deliver(_ error: Error?) {
            condition.lock()
            defer { condition.unlock() }
            guard !answered else { return }
            answered = true
            self.error = error
            condition.broadcast()
        }

        /// `.some(nil)` = applied, `.some(error)` = refused, `nil` = still no
        /// answer at `deadline`.
        func wait(until deadline: Date) -> Error?? {
            condition.lock()
            defer { condition.unlock() }
            while !answered {
                if !condition.wait(until: deadline) { break }
            }
            return answered ? .some(error) : nil
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
