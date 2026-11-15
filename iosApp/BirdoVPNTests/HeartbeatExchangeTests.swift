import XCTest

/// MR-953 F1 (review of #621) — the heartbeat exchange's deadline and its
/// exactly-once ending, RUN rather than read (HeartbeatExchange.swift).
///
/// TunnelHeartbeatTransport hands every connection to
/// `HeartbeatExchangeCompletion`, which must resume its caller once and stop
/// the connection once, whichever comes first: the response, a failure, the
/// timeout or the task being cancelled. These drive it with a fake connection
/// (the `start` / `stop` closures) on a real queue.
///
/// What stays device-only: the real NWConnection / NWTCPConnection callbacks
/// that call `finish`.
final class HeartbeatExchangeTests: XCTestCase {

    private static let ok = HeartbeatHTTP.Response(statusCode: 200, body: Data(#"{"valid":true}"#.utf8))

    /// What the fake connection was asked to do, from any thread.
    private final class Calls: @unchecked Sendable {
        private let lock = NSLock()
        private var counts: [String: Int] = [:]
        func hit(_ name: String) {
            lock.lock()
            counts[name, default: 0] += 1
            lock.unlock()
        }
        func count(_ name: String) -> Int {
            lock.lock()
            defer { lock.unlock() }
            return counts[name] ?? 0
        }
    }

    private func request(timeout: TimeInterval?) -> URLRequest {
        var request = URLRequest(url: URL(string: "https://api.birdo.app/vpn/heartbeat/key-123")!)
        if let timeout { request.timeoutInterval = timeout }
        return request
    }

    // MARK: - The deadline (review item: the transport ignored timeoutInterval)

    /// The 30 s beat leaves URLRequest's default (60 s): it keeps the 20 s cap.
    func testTheBeatWithURLRequestsDefaultTimeoutKeepsTheCap() {
        XCTAssertEqual(request(timeout: nil).timeoutInterval, 60, "URLRequest's default")
        XCTAssertEqual(HeartbeatExchangeTimeout.seconds(for: request(timeout: nil)), 20)
        XCTAssertEqual(HeartbeatExchangeTimeout.cap, 20)
    }

    /// #605's dead-tunnel probe asks for 5 s, and must get 5 s, not 20.
    func testAShorterRequestTimeoutIsHonoured() {
        XCTAssertEqual(HeartbeatExchangeTimeout.seconds(for: request(timeout: 5)), 5)
        XCTAssertEqual(HeartbeatExchangeTimeout.seconds(for: request(timeout: 0.25)), 0.25)
        XCTAssertEqual(HeartbeatExchangeTimeout.seconds(for: request(timeout: 5), cap: 3), 3,
                       "never longer than the transport's own cap")
    }

    func testALongerOrMeaninglessRequestTimeoutIsTheCap() {
        for asked in [30, 20.5, 0, -1] as [TimeInterval] {
            XCTAssertEqual(HeartbeatExchangeTimeout.seconds(for: request(timeout: asked)), 20, "\(asked)")
        }
    }

    /// End to end through the completion: a request asking for 0.2 s, with a
    /// connection that never answers, ends as timedOut in about 0.2 s, not 20.
    func testAnExchangeEndsAtTheRequestsShorterDeadline() async {
        let completion = HeartbeatExchangeCompletion()
        let calls = Calls()
        let deadline = HeartbeatExchangeTimeout.seconds(for: request(timeout: 0.2))
        let began = Date()
        do {
            _ = try await completion.run(timeout: deadline, queue: DispatchQueue(label: "exchange-test"),
                                         start: { calls.hit("start") }, stop: { calls.hit("stop") })
            XCTFail("a connection that never answers cannot succeed")
        } catch {
            XCTAssertEqual(error as? HeartbeatTransportError, .timedOut)
        }
        XCTAssertLessThan(Date().timeIntervalSince(began), 5, "waited for the cap instead of the request's 0.2 s")
        XCTAssertEqual(calls.count("start"), 1)
        XCTAssertEqual(calls.count("stop"), 1)
    }

    // MARK: - Exactly once

    func testAResponseEndsTheExchangeAndStopsTheConnectionOnce() async throws {
        let completion = HeartbeatExchangeCompletion()
        let calls = Calls()
        let ok = Self.ok
        let response = try await completion.run(timeout: 5, queue: DispatchQueue(label: "exchange-test"),
                                                 start: { calls.hit("start"); completion.finish(.success(ok)) },
                                                 stop: { calls.hit("stop") })
        XCTAssertEqual(response, ok)
        // Late outcomes after the first are nothing: no second resume (which
        // would trap), no second stop.
        completion.finish(.failure(HeartbeatTransportError.timedOut))
        completion.finish(.success(ok))
        XCTAssertEqual(calls.count("start"), 1)
        XCTAssertEqual(calls.count("stop"), 1)
    }

    func testAFailureFromTheConnectionIsTheAnswer() async {
        let completion = HeartbeatExchangeCompletion()
        let calls = Calls()
        do {
            _ = try await completion.run(timeout: 5, queue: DispatchQueue(label: "exchange-test"),
                                         start: { completion.finish(.failure(HeartbeatTransportError.untrusted)) },
                                         stop: { calls.hit("stop") })
            XCTFail("expected the connection's failure")
        } catch {
            XCTAssertEqual(error as? HeartbeatTransportError, .untrusted)
        }
        XCTAssertEqual(calls.count("stop"), 1)
    }

    /// The timeout fires first; the answer that arrives after it changes nothing.
    func testAnAnswerAfterTheTimeoutIsIgnored() async {
        let completion = HeartbeatExchangeCompletion()
        let calls = Calls()
        do {
            _ = try await completion.run(timeout: 0.05, queue: DispatchQueue(label: "exchange-test"),
                                         start: { calls.hit("start") }, stop: { calls.hit("stop") })
            XCTFail("expected the timeout")
        } catch {
            XCTAssertEqual(error as? HeartbeatTransportError, .timedOut)
        }
        completion.finish(.success(Self.ok))
        XCTAssertEqual(calls.count("stop"), 1)
    }

    func testCancellingTheTaskEndsTheExchangeAsCancelled() async throws {
        let completion = HeartbeatExchangeCompletion()
        let calls = Calls()
        let task = Task {
            try await completion.run(timeout: 30, queue: DispatchQueue(label: "exchange-test"),
                                     start: { calls.hit("start") }, stop: { calls.hit("stop") })
        }
        try await Task.sleep(nanoseconds: 100_000_000)
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("expected cancellation")
        } catch {
            XCTAssertEqual(error as? HeartbeatTransportError, .cancelled)
        }
        XCTAssertEqual(calls.count("start"), 1)
        XCTAssertEqual(calls.count("stop"), 1)
    }

    /// A task cancelled before the exchange begins never opens a connection.
    func testAnAlreadyCancelledTaskNeverStartsTheConnection() async throws {
        let completion = HeartbeatExchangeCompletion()
        let calls = Calls()
        let queue = DispatchQueue(label: "exchange-test")
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await completion.run(timeout: 30, queue: queue,
                                            start: { calls.hit("start") }, stop: { calls.hit("stop") })
        }
        do {
            _ = try await task.value
            XCTFail("expected cancellation")
        } catch {
            XCTAssertEqual(error as? HeartbeatTransportError, .cancelled)
        }
        queue.sync {}   // anything scheduled on the queue has run by now
        XCTAssertEqual(calls.count("start"), 0)
    }

    /// The response, a failure and the timeout all land at once, from many
    /// threads: exactly one decides, and the connection is stopped once. A
    /// second resume of the continuation would trap and end the test run.
    func testRacingOutcomesResolveExactlyOnce() async {
        let ok = Self.ok
        for round in 0..<100 {
            let completion = HeartbeatExchangeCompletion()
            let calls = Calls()
            do {
                let response = try await completion.run(
                    timeout: 0.001, queue: DispatchQueue(label: "exchange-test"),
                    start: {
                        DispatchQueue.concurrentPerform(iterations: 8) { index in
                            completion.finish(index % 2 == 0
                                              ? .success(ok)
                                              : .failure(HeartbeatTransportError.badResponse))
                        }
                    },
                    stop: { calls.hit("stop") })
                XCTAssertEqual(response, ok, "round \(round)")
            } catch {
                let reason = error as? HeartbeatTransportError
                XCTAssertTrue(reason == .badResponse || reason == .timedOut, "round \(round): \(error)")
            }
            XCTAssertEqual(calls.count("stop"), 1, "round \(round)")
        }
    }

    // MARK: - No connection after the stop (review: iOS 17 path)

    /// The stop lands while the exchange's start is still queued behind other
    /// work: when the start block finally runs, it must not open anything,
    /// because the stop that would have cancelled it has already happened.
    func testAnExchangeStoppedBeforeItsStartRanNeverStarts() async throws {
        let completion = HeartbeatExchangeCompletion()
        let calls = Calls()
        let queue = DispatchQueue(label: "exchange-test")
        queue.suspend()   // the start block waits in the queue
        let task = Task {
            try await completion.run(timeout: 30, queue: queue,
                                     start: { calls.hit("start") }, stop: { calls.hit("stop") })
        }
        try await Task.sleep(nanoseconds: 100_000_000)   // the start block is queued by now
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("expected cancellation")
        } catch {
            XCTAssertEqual(error as? HeartbeatTransportError, .cancelled)
        }
        queue.resume()
        queue.sync {}     // the queued start block has run
        XCTAssertEqual(calls.count("start"), 0, "a connection was opened after the exchange was stopped")
        XCTAssertEqual(calls.count("stop"), 1)
    }

    private final class FakeConnection: Sendable {}
    private final class Owner: Sendable {}

    func testAStoppedSlotNeverOpens() {
        let slot = HeartbeatConnectionSlot<FakeConnection>()
        let calls = Calls()
        XCTAssertNil(slot.stop(), "nothing was opened, nothing to cancel")
        XCTAssertNil(slot.open(retaining: Owner()) { calls.hit("make"); return FakeConnection() })
        XCTAssertEqual(calls.count("make"), 0, "the connection was made after the stop")
        XCTAssertNil(slot.current)
    }

    func testAStopAfterTheOpenHandsBackTheConnectionToCancel() throws {
        let slot = HeartbeatConnectionSlot<FakeConnection>()
        let opened = try XCTUnwrap(slot.open(retaining: Owner()) { FakeConnection() })
        XCTAssertTrue(slot.current === opened)
        XCTAssertTrue(slot.stop() === opened)
        XCTAssertNil(slot.open(retaining: Owner()) { FakeConnection() }, "a slot opens once")
    }

    /// The owner is the iOS 17 connection's delegate: it must outlive the
    /// connection, so it is held until the connection reports `.cancelled`,
    /// which comes after the stop, not with it.
    func testTheOwnerIsHeldUntilTheConnectionHasEnded() {
        let slot = HeartbeatConnectionSlot<FakeConnection>()
        weak var weakOwner: Owner?
        do {
            let owner = Owner()
            weakOwner = owner
            XCTAssertNotNil(slot.open(retaining: owner) { FakeConnection() })
        }
        XCTAssertNotNil(weakOwner, "held while the connection lives")
        _ = slot.stop()
        XCTAssertNotNil(weakOwner, "a stop is not the end: the connection has not reported .cancelled yet")
        slot.ended()
        XCTAssertNil(weakOwner, "released once the connection is over")
    }

    func testAnOwnerIsNotHeldWhenNothingOpened() {
        let slot = HeartbeatConnectionSlot<FakeConnection>()
        _ = slot.stop()
        weak var weakOwner: Owner?
        do {
            let owner = Owner()
            weakOwner = owner
            XCTAssertNil(slot.open(retaining: owner) { FakeConnection() })
        }
        XCTAssertNil(weakOwner)
    }

    /// The start (open) and the stop race on two threads. Whatever the order,
    /// a connection that was opened is the one the stop hands back to cancel.
    func testAStopRacingTheOpenNeverLeavesAnUncancelledConnection() {
        final class Outcome: @unchecked Sendable {
            private let lock = NSLock()
            private var opened: FakeConnection?
            private var toCancel: FakeConnection?
            func record(opened: FakeConnection?) { lock.lock(); self.opened = opened; lock.unlock() }
            func record(toCancel: FakeConnection?) { lock.lock(); self.toCancel = toCancel; lock.unlock() }
            var leaked: Bool {
                lock.lock()
                defer { lock.unlock() }
                guard let opened else { return false }
                return toCancel !== opened
            }
        }
        for round in 0..<500 {
            let slot = HeartbeatConnectionSlot<FakeConnection>()
            let outcome = Outcome()
            let owner = Owner()
            DispatchQueue.concurrentPerform(iterations: 2) { index in
                if index == 0 {
                    outcome.record(opened: slot.open(retaining: owner) { FakeConnection() })
                } else {
                    outcome.record(toCancel: slot.stop())
                }
            }
            XCTAssertFalse(outcome.leaked, "round \(round): a connection opened that the stop never saw")
        }
    }

    // MARK: - A close right after the answer (review: .disconnected)

    func testACompleteAnswerFollowedByTheCloseIsTheAnswer() throws {
        let body = #"{"valid":false}"#
        let raw = Data("HTTP/1.1 200 OK\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)".utf8)
        XCTAssertEqual(try HeartbeatExchangeOutcome.atClose(raw).get(),
                       HeartbeatHTTP.Response(statusCode: 200, body: Data(body.utf8)))
        // No length at all: the close is what ends the body.
        let open = Data("HTTP/1.1 401 Unauthorized\r\n\r\nnope".utf8)
        XCTAssertEqual(try HeartbeatExchangeOutcome.atClose(open).get().statusCode, 401)
    }

    func testAnIncompleteAnswerAtTheCloseIsAFailedConnection() {
        let cut = Data("HTTP/1.1 200 OK\r\nContent-Length: 40\r\n\r\n{\"valid\":".utf8)
        for received in [cut, Data(), Data("HTTP/1.1 200".utf8), Data("garbage\r\n\r\n".utf8)] {
            switch HeartbeatExchangeOutcome.atClose(received) {
            case .success(let response):
                XCTFail("read \(response) out of \(received.count) bytes")
            case .failure(let error):
                XCTAssertEqual(error as? HeartbeatTransportError, .connectionFailed)
            }
        }
    }
}
