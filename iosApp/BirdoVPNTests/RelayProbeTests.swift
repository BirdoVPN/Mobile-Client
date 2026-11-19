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

    /// A TCP port on 127.0.0.1 that is bound but never listening, held for
    /// the test: a connect to it is refused, every time. (A listener that was
    /// just cancelled is not: `NWListener.cancel()` is asynchronous, and the
    /// first run of this test connected to one still closing.)
    private final class RefusingPort {
        let fd: Int32
        private(set) var port: UInt16 = 0

        init() throws {
            fd = socket(AF_INET, SOCK_STREAM, 0)
            guard fd >= 0 else { throw POSIXError(.EBADF) }
            var addr = sockaddr_in()
            addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = 0
            addr.sin_addr.s_addr = inet_addr("127.0.0.1")
            var length = socklen_t(MemoryLayout<sockaddr_in>.size)
            let bound = withUnsafeMutablePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, length) }
            }
            let named = withUnsafeMutablePointer(to: &addr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &length) }
            }
            guard bound == 0, named == 0 else {
                close(fd)
                throw POSIXError(.EADDRNOTAVAIL)
            }
            port = UInt16(bigEndian: addr.sin_port)
        }

        deinit { close(fd) }
    }

    func testARelayThatDoesNotAcceptTcpIsUnreachable() async throws {
        let closed = try RefusingPort()
        XCTAssertNotEqual(closed.port, 0)

        let started = ContinuousClock.now
        let verdict = await RelayProbe.verdict(host: "127.0.0.1", port: closed.port, timeout: .seconds(1))
        let elapsed = started.duration(to: ContinuousClock.now)
        withExtendedLifetime(closed) {}

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
