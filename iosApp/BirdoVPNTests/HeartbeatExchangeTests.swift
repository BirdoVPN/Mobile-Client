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

    /// A slot whose release fallback is out of the way unless a test wants it.
    private func makeSlot(releaseAfter: TimeInterval = 60,
                          queue: DispatchQueue = DispatchQueue(label: "slot-test")) -> HeartbeatConnectionSlot<FakeConnection> {
        HeartbeatConnectionSlot<FakeConnection>(queue: queue, releaseAfter: releaseAfter)
    }

    func testAStoppedSlotNeverOpens() {
        let slot = makeSlot()
        let calls = Calls()
        XCTAssertNil(slot.stop(), "nothing was opened, nothing to cancel")
        XCTAssertNil(slot.open(retaining: Owner(), { calls.hit("make"); return FakeConnection() },
                               cancel: { _ in calls.hit("cancel") }))
        XCTAssertEqual(calls.count("make"), 0, "the connection was made after the stop")
        XCTAssertEqual(calls.count("cancel"), 0)
        XCTAssertNil(slot.current)
    }

    func testAStopAfterTheOpenHandsBackTheConnectionToCancel() throws {
        let slot = makeSlot()
        let calls = Calls()
        let opened = try XCTUnwrap(slot.open(retaining: Owner(), { FakeConnection() },
                                             cancel: { _ in calls.hit("cancel") }))
        XCTAssertTrue(slot.current === opened)
        XCTAssertTrue(slot.stop() === opened)
        XCTAssertEqual(calls.count("cancel"), 0, "the stop's caller cancels it, not the open")
    }

    func testASlotMakesItsConnectionAtMostOnce() {
        let slot = makeSlot()
        let calls = Calls()
        XCTAssertNotNil(slot.open(retaining: Owner(), { calls.hit("make"); return FakeConnection() }, cancel: { _ in }))
        XCTAssertNil(slot.open(retaining: Owner(), { calls.hit("make"); return FakeConnection() }, cancel: { _ in }))
        XCTAssertEqual(calls.count("make"), 1)
    }

    /// The owner is the iOS 17 connection's delegate: it must outlive the
    /// connection, so it is held until the connection reports `.cancelled`,
    /// which comes after the stop, not with it. The end lets go of the
    /// connection too.
    func testTheOwnerAndConnectionAreHeldUntilTheConnectionHasEnded() {
        let slot = makeSlot()
        weak var weakOwner: Owner?
        weak var weakConnection: FakeConnection?
        do {
            let owner = Owner()
            weakOwner = owner
            let made = slot.open(retaining: owner, { FakeConnection() }, cancel: { _ in })
            weakConnection = made
            XCTAssertNotNil(made)
        }
        XCTAssertNotNil(weakOwner, "held while the connection lives")
        _ = slot.stop()
        XCTAssertNotNil(weakOwner, "a stop is not the end: the connection has not reported .cancelled yet")
        slot.ended()
        XCTAssertNil(weakOwner, "released once the connection is over")
        XCTAssertNil(weakConnection, "the slot lets go of the connection too")
        XCTAssertNil(slot.current)
    }

    /// Re-review LOW-1: a connection that never reports `.cancelled` used to
    /// keep its exchange (and the connection, and up to 64 KB of answer)
    /// alive for good, one per beat. The fallback lets go of both a fixed
    /// time after the stop.
    func testTheFallbackReleasesTheOwnerAndConnectionWhenTheEndNeverComes() {
        let queue = DispatchQueue(label: "slot-test")
        let slot = makeSlot(releaseAfter: 0.05, queue: queue)
        weak var weakOwner: Owner?
        weak var weakConnection: FakeConnection?
        do {
            let owner = Owner()
            weakOwner = owner
            let made = slot.open(retaining: owner, { FakeConnection() }, cancel: { _ in })
            weakConnection = made
            XCTAssertNotNil(made)
        }
        _ = slot.stop()
        XCTAssertNotNil(weakOwner, "held for the fallback's delay after the stop")
        Thread.sleep(forTimeInterval: 0.3)
        queue.sync {}
        XCTAssertNil(weakOwner, "never reported .cancelled, and still released")
        XCTAssertNil(weakConnection, "no connection -> delegate -> slot -> connection cycle survives")
        XCTAssertNil(slot.current)
    }

    /// The end, reported after the fallback already let go (or twice), is
    /// harmless; so is the fallback firing after the end.
    func testALateEndAfterTheFallbackIsHarmless() {
        let queue = DispatchQueue(label: "slot-test")
        let slot = makeSlot(releaseAfter: 0.05, queue: queue)
        weak var weakOwner: Owner?
        do {
            let owner = Owner()
            weakOwner = owner
            XCTAssertNotNil(slot.open(retaining: owner, { FakeConnection() }, cancel: { _ in }))
        }
        _ = slot.stop()
        Thread.sleep(forTimeInterval: 0.3)
        queue.sync {}
        XCTAssertNil(weakOwner)
        slot.ended()   // the late .cancelled
        slot.ended()
        XCTAssertNil(slot.current)
        XCTAssertNil(slot.stop(), "nothing left to cancel")

        // And the other order: the end first, the fallback later.
        let other = makeSlot(releaseAfter: 0.05, queue: queue)
        XCTAssertNotNil(other.open(retaining: Owner(), { FakeConnection() }, cancel: { _ in }))
        _ = other.stop()
        other.ended()
        Thread.sleep(forTimeInterval: 0.3)
        queue.sync {}
        XCTAssertNil(other.current)
    }

    func testAnOwnerIsNotHeldWhenNothingOpened() {
        let slot = makeSlot()
        _ = slot.stop()
        weak var weakOwner: Owner?
        do {
            let owner = Owner()
            weakOwner = owner
            XCTAssertNil(slot.open(retaining: owner, { FakeConnection() }, cancel: { _ in }))
        }
        XCTAssertNil(weakOwner)
    }

    /// Re-review LOW-2: the connection is made OUTSIDE the slot's lock, so a
    /// stop that arrives while it is being made (NetworkExtension calling back
    /// into the exchange) neither deadlocks nor is lost: it finds nothing to
    /// cancel, and the open cancels what it made instead of handing it out.
    /// With the old slot (make under the lock) this stop deadlocked.
    func testAStopWhileTheConnectionIsBeingMadeGetsItCancelled() {
        let queue = DispatchQueue(label: "slot-test")
        let slot = makeSlot(releaseAfter: 0.05, queue: queue)
        weak var weakOwner: Owner?
        var stopFound: FakeConnection?
        var made: FakeConnection?
        var cancelled: FakeConnection?
        do {
            let owner = Owner()
            weakOwner = owner
            let opened = slot.open(retaining: owner, {
                stopFound = slot.stop()
                let connection = FakeConnection()
                made = connection
                return connection
            }, cancel: { cancelled = $0 })
            XCTAssertNil(opened, "a connection a stop raced is not handed out")
        }
        XCTAssertNil(stopFound, "the stop came before the connection existed")
        XCTAssertNotNil(made)
        XCTAssertTrue(cancelled === made, "the open cancelled what the stop could not")
        XCTAssertNotNil(weakOwner, "the delegate is held for the fallback all the same")
        Thread.sleep(forTimeInterval: 0.3)
        queue.sync {}
        XCTAssertNil(weakOwner)
    }

    /// The start (open) and the stop race on two threads. Whatever the order,
    /// every connection that was made is cancelled by exactly one side: the
    /// stop hands it back, or the open cancels it itself.
    func testAStopRacingTheOpenNeverLeavesAnUncancelledConnection() {
        final class Outcome: @unchecked Sendable {
            private let lock = NSLock()
            private var made: FakeConnection?
            private var opened: FakeConnection?
            private var cancelledByOpen: FakeConnection?
            private var handedToStop: FakeConnection?
            func record(made: FakeConnection) { lock.lock(); self.made = made; lock.unlock() }
            func record(opened: FakeConnection?) { lock.lock(); self.opened = opened; lock.unlock() }
            func record(cancelledByOpen: FakeConnection) { lock.lock(); self.cancelledByOpen = cancelledByOpen; lock.unlock() }
            func record(handedToStop: FakeConnection?) { lock.lock(); self.handedToStop = handedToStop; lock.unlock() }
            /// Nil when the invariant holds, else what broke it.
            var broken: String? {
                lock.lock()
                defer { lock.unlock() }
                guard let made else {
                    return opened == nil && cancelledByOpen == nil && handedToStop == nil ? nil : "nothing made"
                }
                let byStop = handedToStop === made
                let byOpen = cancelledByOpen === made
                if byStop == byOpen { return byStop ? "cancelled twice" : "never cancelled" }
                if opened != nil && !(opened === made && byStop) { return "handed out but not to the stop" }
                return nil
            }
        }
        let queue = DispatchQueue(label: "slot-test")
        for round in 0..<500 {
            let slot = makeSlot(releaseAfter: 0.01, queue: queue)
            let outcome = Outcome()
            let owner = Owner()
            DispatchQueue.concurrentPerform(iterations: 2) { index in
                if index == 0 {
                    outcome.record(opened: slot.open(retaining: owner, {
                        let connection = FakeConnection()
                        outcome.record(made: connection)
                        return connection
                    }, cancel: { outcome.record(cancelledByOpen: $0) }))
                } else {
                    outcome.record(handedToStop: slot.stop())
                }
            }
            XCTAssertNil(outcome.broken, "round \(round)")
        }
        queue.sync {}
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
