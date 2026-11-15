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
}
