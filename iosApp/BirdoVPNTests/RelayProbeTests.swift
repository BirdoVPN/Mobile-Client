import Network
import XCTest

/// MR-2212 (design §3.3): the TCP probe after a dial whose handshake never
/// came, end to end on loopback: a relay that accepts TCP means this network
/// blocks VPN traffic; one that does not means the server is not reachable.
/// Each case runs the real `RelayProbe` (Network.framework) and checks the
/// words `VpnViewModel` puts on the banner (`RelayProbeVerdict.message`).
final class RelayProbeTests: XCTestCase {

    /// A TCP listener on 127.0.0.1 that accepts and closes; stands in for a
    /// relay whose Stealth port is up.
    private final class LoopbackListener: @unchecked Sendable {
        let listener: NWListener
        private(set) var port: UInt16 = 0

        init() throws {
            let parameters = NWParameters.tcp
            parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
            listener = try NWListener(using: parameters)
        }

        func start(_ test: XCTestCase) async {
            let ready = test.expectation(description: "listener ready")
            let queue = DispatchQueue(label: "relay-probe-tests.listener")
            listener.newConnectionHandler = { connection in
                connection.start(queue: queue)
                connection.cancel()
            }
            listener.stateUpdateHandler = { [self] state in
                if case .ready = state, let port = self.listener.port?.rawValue {
                    self.port = port
                    ready.fulfill()
                }
            }
            listener.start(queue: queue)
            await test.fulfillment(of: [ready], timeout: 5)
        }

        func stop() { listener.cancel() }
    }

    func testARelayThatAcceptsTcpMeansTheNetworkBlocksVpn() async throws {
        let relay = try LoopbackListener()
        await relay.start(self)
        defer { relay.stop() }
        XCTAssertNotEqual(relay.port, 0)

        let verdict = await RelayProbe.verdict(host: "127.0.0.1", port: relay.port, timeout: .seconds(3))

        XCTAssertEqual(verdict, .networkBlocksVpn)
        XCTAssertEqual(verdict.message,
                       "This network appears to block VPN connections. Try mobile data or another Wi-Fi.")
    }

    func testARelayThatDoesNotAcceptTcpIsUnreachable() async throws {
        // A port that was just listening and is now closed: refused.
        let relay = try LoopbackListener()
        await relay.start(self)
        let closedPort = relay.port
        relay.stop()
        XCTAssertNotEqual(closedPort, 0)

        let started = ContinuousClock.now
        let verdict = await RelayProbe.verdict(host: "127.0.0.1", port: closedPort, timeout: .seconds(1))
        let elapsed = started.duration(to: ContinuousClock.now)

        XCTAssertEqual(verdict, .serverUnreachable)
        XCTAssertEqual(verdict.message, "This server isn't reachable right now.")
        // Bounded by the cap, never left to the system's own connect timeout.
        XCTAssertLessThan(elapsed, .seconds(4))
    }

    func testNoAddressIsUnreachableWithoutTrying() async {
        let started = ContinuousClock.now
        let verdict = await RelayProbe.verdict(host: "", port: RelayProbeVerdict.port, timeout: .seconds(3))
        XCTAssertEqual(verdict, .serverUnreachable)
        XCTAssertLessThan(started.duration(to: ContinuousClock.now), .seconds(1))
    }
}
