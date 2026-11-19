import Foundation
import Network

/// MR-2212 (design §3.3): the TCP probe behind `RelayProbeVerdict`. A plain
/// TCP connect to the relay, nothing sent, cancelled the moment it answers or
/// the cap runs out.
///
/// The caller runs it only once the tunnel is DOWN, so the connection takes
/// the device's own network, never the dead tunnel (the route contract of
/// #621 is not involved). The host is the relay's address from the dial's
/// own `/vpn/connect` answer; it is never logged.
///
/// Network.framework only, so BirdoVPNTests compiles it and runs it against a
/// loopback listener (RelayRetryTests).
enum RelayProbe {
    /// The verdict for `host`, after at most `timeout`.
    static func verdict(host: String,
                        port: UInt16 = RelayProbeVerdict.port,
                        timeout: Duration = RelayProbeVerdict.timeout) async -> RelayProbeVerdict {
        RelayProbeVerdict(tcpConnected: await tcpConnects(host: host, port: port, timeout: timeout))
    }

    /// Whether a TCP connection to `host:port` is established within
    /// `timeout`. `.waiting` (no route yet, or a refusal) is not an answer:
    /// the connection keeps trying until the cap, because the path can still
    /// be settling straight after the tunnel went down.
    static func tcpConnects(host: String, port: UInt16, timeout: Duration) async -> Bool {
        guard !host.isEmpty, let nwPort = NWEndpoint.Port(rawValue: port) else { return false }
        let attempt = ProbeAttempt(NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp))
        return await attempt.run(timeout: timeout)
    }
}

/// One connection attempt, finished exactly once (ready, failed, or the cap),
/// all on its own serial queue.
private final class ProbeAttempt: @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "app.birdo.vpn.relay-probe")
    private var continuation: CheckedContinuation<Bool, Never>?

    init(_ connection: NWConnection) {
        self.connection = connection
    }

    func run(timeout: Duration) async -> Bool {
        let seconds = Double(timeout.components.seconds)
            + Double(timeout.components.attoseconds) / 1_000_000_000_000_000_000
        return await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            queue.async {
                self.continuation = continuation
                self.connection.stateUpdateHandler = { [self] state in
                    switch state {
                    case .ready: self.finish(true)
                    case .failed, .cancelled: self.finish(false)
                    default: break
                    }
                }
                self.connection.start(queue: self.queue)
                self.queue.asyncAfter(deadline: .now() + max(0, seconds)) { self.finish(false) }
            }
        }
    }

    /// On `queue` only. The first call wins; the connection is cancelled
    /// either way, so nothing is left open and nothing is ever sent.
    private func finish(_ connected: Bool) {
        guard let continuation else { return }
        self.continuation = nil
        connection.stateUpdateHandler = nil
        connection.cancel()
        continuation.resume(returning: connected)
    }
}
