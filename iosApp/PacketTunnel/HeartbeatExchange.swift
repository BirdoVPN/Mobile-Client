import Foundation

/// MR-953 F1 — the parts of the heartbeat's through-tunnel exchange that need
/// no NetworkExtension: how long it may take, how it ends, and why it failed.
///
/// TunnelHeartbeatTransport drives a connection (NWConnection on iOS 18 /
/// macOS 15, NWTCPConnection below) and ends it through
/// `HeartbeatExchangeCompletion`, which resumes the caller exactly once. That
/// class and the deadline rule live here, Foundation-only, so BirdoVPNTests
/// compiles this file directly and runs them (HeartbeatExchangeTests): the
/// races between a response, the timeout and task cancellation are behaviour,
/// not something a source-text check can see.

/// Why a beat did not come back. `TunnelHeartbeatTransport.TransportError`
/// is this type under its old name.
enum HeartbeatTransportError: Error, Equatable {
    /// No way into the tunnel right now. The beat was not sent anywhere.
    case tunnelUnavailable
    case invalidRequest
    case connectionFailed
    /// The certificate chain failed validation or matched no pin.
    case untrusted
    case timedOut
    case badResponse
    case cancelled
}

/// How long one exchange may take, connect to last byte.
enum HeartbeatExchangeTimeout {
    /// The longest any exchange may take: 20 s, the old URLSession's
    /// per-request timeout.
    static let cap: TimeInterval = 20

    /// The request's own `timeoutInterval` when it asks for less than `cap`,
    /// otherwise `cap`.
    ///
    /// Review of #621: the transport always waited 20 s. The dead-tunnel probe
    /// of #605 sets 5 s on its request, because it runs while the tunnel may be
    /// holding every packet (`includeAllNetworks`), and a probe that ignored it
    /// would hold the device offline four times longer than intended.
    /// URLRequest's default (60 s) leaves the 30 s beat at the cap. A value
    /// that is not a positive finite number is not a deadline: the cap again.
    static func seconds(for request: URLRequest, cap: TimeInterval = cap) -> TimeInterval {
        let asked = request.timeoutInterval
        guard asked.isFinite, asked > 0 else { return cap }
        return min(cap, asked)
    }
}

/// Resumes the caller exactly once, with whichever comes first: the response,
/// a failure, the timeout or task cancellation; then stops the connection.
final class HeartbeatExchangeCompletion: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<HeartbeatHTTP.Response, Error>?
    private var finished = false
    private var stop: (@Sendable () -> Void)?

    /// The exchange has its outcome. A connection that has not started by now
    /// must not start.
    var isFinished: Bool {
        lock.lock()
        defer { lock.unlock() }
        return finished
    }

    /// Runs one exchange: `start` (on `queue`) opens the connection, which
    /// ends it with `finish`; `stop` closes it, once, whatever ended it.
    func run(timeout: TimeInterval, queue: DispatchQueue,
             start: @escaping @Sendable () -> Void,
             stop: @escaping @Sendable () -> Void) async throws -> HeartbeatHTTP.Response {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<HeartbeatHTTP.Response, Error>) in
                lock.lock()
                if finished {
                    lock.unlock()
                    cont.resume(throwing: HeartbeatTransportError.cancelled)
                    return
                }
                continuation = cont
                self.stop = stop
                lock.unlock()
                queue.asyncAfter(deadline: .now() + timeout) { [self] in
                    finish(.failure(HeartbeatTransportError.timedOut))
                }
                // Review of #621: an exchange that ended before this block ran
                // (cancelled, or timed out behind a busy queue) was stopped
                // with nothing yet to stop. Starting it now would open a
                // connection that nothing ever cancels.
                queue.async { [self] in
                    guard !isFinished else { return }
                    start()
                }
            }
        } onCancel: {
            finish(.failure(HeartbeatTransportError.cancelled))
        }
    }

    /// The outcome. Only the first call counts; it stops the connection and
    /// resumes the caller.
    func finish(_ result: Result<HeartbeatHTTP.Response, Error>) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        let cont = continuation
        let stop = self.stop
        continuation = nil
        self.stop = nil
        lock.unlock()
        stop?()
        cont?.resume(with: result)
    }
}

/// One exchange's connection, from the moment it is opened to its terminal
/// state (review of #621).
///
/// The exchange is started on its queue and may be stopped from any thread
/// (the timeout, task cancellation, a failure in a callback). Three rules:
///   - a slot that was stopped never opens: a connection made after the stop
///     is one that nothing would ever cancel;
///   - a stop after the open hands back the connection, to be cancelled;
///   - the owner is kept alive from the open until the connection reports its
///     terminal state (`ended`). On the iOS 17 path the owner is the
///     NWTCPConnection's authentication delegate, so no late delegate callback
///     can reach a freed object.
final class HeartbeatConnectionSlot<Connection: AnyObject>: @unchecked Sendable {
    private let lock = NSLock()
    private var stopped = false
    private var connection: Connection?
    private var owner: AnyObject?

    /// Opens with `make`, called at most once and under the slot's lock,
    /// unless the slot was already stopped (or opened). Returns the connection
    /// it opened, nil otherwise; `owner` is held until `ended()`.
    func open(retaining owner: AnyObject, _ make: () -> Connection?) -> Connection? {
        lock.lock()
        defer { lock.unlock() }
        guard !stopped, connection == nil, let made = make() else { return nil }
        connection = made
        self.owner = owner
        return made
    }

    /// The connection, once opened.
    var current: Connection? {
        lock.lock()
        defer { lock.unlock() }
        return connection
    }

    /// No open from now on. Returns the connection to cancel, if one was opened.
    func stop() -> Connection? {
        lock.lock()
        defer { lock.unlock() }
        stopped = true
        return connection
    }

    /// The connection reached its terminal state: the owner may go.
    func ended() {
        lock.lock()
        let released = owner
        owner = nil
        lock.unlock()
        withExtendedLifetime(released) {}   // the last release happens outside the lock
    }
}

/// How an exchange ends when the server closes the connection.
enum HeartbeatExchangeOutcome {
    /// The server closed (the iOS 17 path's NWTCPConnection reported
    /// `.disconnected`). The request asks for `Connection: close`, so a close
    /// right after a complete answer is the normal end, and the state change
    /// can be reported before the read that saw the end: what has arrived is
    /// read as at EOF, and only an incomplete or unreadable answer fails.
    static func atClose(_ received: Data) -> Result<HeartbeatHTTP.Response, Error> {
        if let response = try? HeartbeatHTTP.parse(received, atEOF: true) {
            return .success(response)
        }
        return .failure(HeartbeatTransportError.connectionFailed)
    }
}
