import Foundation
import Network
@preconcurrency import NetworkExtension
import Security

/// MR-953 F1 — the extension's heartbeat goes THROUGH its own tunnel, or not
/// at all.
///
/// NetworkExtension routes the packet-tunnel provider's own connections
/// OUTSIDE the tunnel it provides; that is what keeps wireguard-go's UDP
/// socket in this same process from looping into the tunnel it carries. The
/// heartbeat used URLSession on the assumption that it "rides the live
/// tunnel", so every 30 s for the tunnel's whole life the bearer token, the key
/// id and a resolver lookup of api.birdo.app left from the user's real IP:
/// the iOS/macOS twin of the defect Android fixed in D-6.
///
/// Apple's way to reach a host through the provider's own tunnel:
///   - iOS 18 / macOS 15 and later: a Network.framework connection that
///     REQUIRES `NEPacketTunnelProvider.virtualInterface` ("To create a
///     connection through the tunnel, pass this interface to
///     nw_parameters_require_interface", NEPacketTunnelProvider.h). The name
///     is resolved on that interface too, i.e. by the tunnel's DNS.
///   - iOS 17 / macOS 14 (the deployment floors): the same provider's
///     `createTCPConnectionThroughTunnel(to:enableTLS:...)`, which Apple
///     deprecated in 18 / 15 in favour of the above.
///
/// FAIL CLOSED: when neither way is available (no virtual interface yet, the
/// provider gone), the beat is SKIPPED with `.tunnelUnavailable`. Nothing ever
/// falls back to a connection outside the tunnel. A skipped beat is a missed
/// beat: the server keeps a key whose check-ins stop for 5 minutes only while
/// its peer keeps handshaking, and never past 12 minutes after the last
/// check-in (birdo-web CleanupService STALE_CONNECTION_MINUTES /
/// HANDSHAKE_KEEP_CEILING_MINUTES); the host app's own heartbeat, which rides
/// the tunnel like any app's traffic, still checks in while it runs.
///
/// TLS is pinned exactly as the URLSession delegate was (`HeartbeatPins`,
/// the one pin set the cert-pins check reads): standard validation for the
/// host first, then at least one certificate in the chain must match a pin.
/// The HTTP/1.1 bytes are HeartbeatHTTP's.
final class TunnelHeartbeatTransport: @unchecked Sendable {
    /// HeartbeatExchange.swift, Foundation-only so the unit tests can see it.
    typealias TransportError = HeartbeatTransportError

    private weak var provider: NEPacketTunnelProvider?
    private let timeout: TimeInterval
    private let queue = DispatchQueue(label: "app.birdo.vpn.tunnel.heartbeat-transport")

    /// - Parameter timeout: the longest any exchange may take, connect to last
    ///   byte. 20 s, the old URLSession's per-request timeout.
    init(provider: NEPacketTunnelProvider, timeout: TimeInterval = HeartbeatExchangeTimeout.cap) {
        self.provider = provider
        self.timeout = timeout
    }

    /// One request through the tunnel. Returns the body and an HTTPURLResponse
    /// carrying the status code, the shape `URLSession.data(for:)` returned.
    ///
    /// The request's own `timeoutInterval` is honoured when it is shorter than
    /// the transport's (`HeartbeatExchangeTimeout.seconds`): the beat leaves it
    /// at URLRequest's 60 s default and gets 20 s, a dead-tunnel probe asks
    /// for 5 s and gets 5 s.
    func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
        guard let url = request.url?.absoluteURL, let host = url.host, !host.isEmpty,
              let port = UInt16(exactly: url.port ?? 443),
              let bytes = HeartbeatHTTP.encode(request) else {
            throw TransportError.invalidRequest
        }
        guard let provider else { throw TransportError.tunnelUnavailable }

        let completion = HeartbeatExchangeCompletion()
        let deadline = HeartbeatExchangeTimeout.seconds(for: request, cap: timeout)
        let start: @Sendable () -> Void
        let stop: @Sendable () -> Void
        if #available(iOS 18.0, macOS 15.0, *) {
            guard let interface = provider.virtualInterface else {
                throw TransportError.tunnelUnavailable
            }
            let exchange = InterfaceExchange(host: host, port: port, interface: interface,
                                             request: bytes, queue: queue, completion: completion)
            start = { exchange.start() }
            stop = { exchange.stop() }
        } else {
            let exchange = LegacyTunnelExchange(provider: provider, host: host, port: port,
                                                request: bytes, queue: queue, completion: completion)
            start = { exchange.start() }
            stop = { exchange.stop() }
        }
        let response = try await completion.run(timeout: deadline, queue: queue, start: start, stop: stop)
        guard let http = HTTPURLResponse(url: url, statusCode: response.statusCode,
                                         httpVersion: "HTTP/1.1", headerFields: nil) else {
            throw TransportError.badResponse
        }
        return (response.body, http)
    }
}

// One exchange is completed exactly once by HeartbeatExchangeCompletion
// (HeartbeatExchange.swift).

// MARK: - iOS 18 / macOS 15: Network.framework on the tunnel's interface

@available(iOS 18.0, macOS 15.0, *)
private final class InterfaceExchange: @unchecked Sendable {
    private let connection: NWConnection
    private let request: Data
    private let queue: DispatchQueue
    private let completion: HeartbeatExchangeCompletion
    private var reader = HeartbeatHTTP.ResponseReader()   // touched only on `queue`
    private let lock = NSLock()
    /// Set by `stop`; a stopped exchange never starts its connection.
    private var stopped = false

    init(host: String, port: UInt16, interface: NWInterface, request: Data,
         queue: DispatchQueue, completion: HeartbeatExchangeCompletion) {
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        sec_protocol_options_set_tls_server_name(options, host)
        sec_protocol_options_add_tls_application_protocol(options, "http/1.1")
        sec_protocol_options_set_min_tls_protocol_version(options, .TLSv12)
        // Review of #621 hardening: App Transport Security's cipher suites
        // only (forward-secret suites on TLS 1.2), and no session resumption,
        // so every connection is a full handshake whose chain the verify block
        // below sees. Both exist since iOS 13 / macOS 10.15 at the latest,
        // well under this path's iOS 18 / macOS 15 floor.
        sec_protocol_options_append_tls_ciphersuite_group(options, .ats)
        sec_protocol_options_set_tls_resumption_enabled(options, false)
        // A verify block REPLACES the default evaluation; HeartbeatPins.isTrusted
        // runs the standard one for `host` itself before checking the pins.
        sec_protocol_options_set_verify_block(options, { _, secTrust, complete in
            let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
            complete(HeartbeatPins.isTrusted(trust, host: host))
        }, queue)
        let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        // THE fix: this connection may use the tunnel's interface and no other.
        parameters.requiredInterface = interface
        parameters.preferNoProxies = true
        self.connection = NWConnection(host: Network.NWEndpoint.Host(host),
                                       port: Network.NWEndpoint.Port(rawValue: port) ?? .https,
                                       using: parameters)
        self.request = request
        self.queue = queue
        self.completion = completion
    }

    func start() {
        // Held across the start, so a stop on another thread lands either
        // before it (nothing starts) or after it (the cancel reaches a started
        // connection). The handler runs on `queue` and never takes this lock.
        lock.lock()
        defer { lock.unlock() }
        guard !stopped else { return }
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.sendRequest()
            case .failed(let error):
                self.completion.finish(.failure(Self.classify(error)))
            case .waiting(let error):
                // No path on the required interface (the tunnel is going down,
                // or never came up). Waiting for one would only hold the beat;
                // it is skipped instead.
                self.completion.finish(.failure(Self.classify(error)))
            case .cancelled:
                self.completion.finish(.failure(TunnelHeartbeatTransport.TransportError.cancelled))
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    func stop() {
        lock.lock()
        stopped = true
        lock.unlock()
        connection.cancel()
    }

    private func sendRequest() {
        connection.send(content: request, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            if error != nil {
                self.completion.finish(.failure(TunnelHeartbeatTransport.TransportError.connectionFailed))
            } else {
                self.receive()
            }
        })
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data { self.reader.append(data) }
            let atEOF = isComplete || error != nil
            do {
                if let response = try self.reader.response(atEOF: atEOF) {
                    self.completion.finish(.success(response))
                    return
                }
            } catch {
                self.completion.finish(.failure(TunnelHeartbeatTransport.TransportError.badResponse))
                return
            }
            if atEOF {
                self.completion.finish(.failure(TunnelHeartbeatTransport.TransportError.badResponse))
            } else {
                self.receive()
            }
        }
    }

    private static func classify(_ error: NWError) -> TunnelHeartbeatTransport.TransportError {
        if case .tls = error { return .untrusted }
        return .connectionFailed
    }
}

// MARK: - iOS 17 / macOS 14: the provider's through-tunnel TCP connection

// The `__` names are Apple's: in Swift 6 language mode the SDK's API notes mark
// NWTCPConnection, NWHostEndpoint, their delegate protocol and
// `createTCPConnectionThroughTunnel` SwiftPrivate, which keeps them callable
// under a `__` prefix (Swift 5 mode sees the plain names). It is the same
// Objective-C API, deprecated only from iOS 18 / macOS 15, which is exactly
// where this path stops being used.

private final class LegacyTunnelExchange: NSObject, __NWTCPConnectionAuthenticationDelegate, @unchecked Sendable {
    /// `kTLSProtocol12` of Security's `SSLProtocol` (SecProtocolTypes.h), the
    /// unit `NWTLSParameters.minimumSSLProtocolVersion` is written in. Spelled
    /// as its value: the enum itself has been deprecated since iOS 13, and
    /// naming it would add a deprecation warning to every build.
    private static let minimumTLSVersion = 8

    private weak var provider: NEPacketTunnelProvider?
    private let host: String
    private let port: UInt16
    private let request: Data
    private let queue: DispatchQueue
    private let completion: HeartbeatExchangeCompletion
    private let lock = NSLock()
    /// The connection, from its open to its end (HeartbeatExchange.swift):
    /// never opened after a stop, and this exchange (the connection's
    /// authentication delegate) kept alive until `.cancelled`, or at most
    /// `HeartbeatExchangeTimeout.releaseAfterStop` after the stop.
    private let slot: HeartbeatConnectionSlot<__NWTCPConnection>
    private var observation: NSKeyValueObservation?
    /// Approved by `evaluateTrust` and required before a byte is written: the
    /// connection must have been through THIS pin check, whatever the
    /// system's own evaluation of the trust handed back says.
    private let writeGate = PinnedWriteGate()
    private var reader = HeartbeatHTTP.ResponseReader()   // touched only on `queue`

    init(provider: NEPacketTunnelProvider, host: String, port: UInt16, request: Data,
         queue: DispatchQueue, completion: HeartbeatExchangeCompletion) {
        self.provider = provider
        self.host = host
        self.port = port
        self.request = request
        self.queue = queue
        self.completion = completion
        self.slot = HeartbeatConnectionSlot(queue: queue)
    }

    func start() {
        guard let provider else {
            completion.finish(.failure(TunnelHeartbeatTransport.TransportError.tunnelUnavailable))
            return
        }
        let endpoint = __NWHostEndpoint(hostname: host, port: String(port))
        // TLS 1.2 at least, as on the iOS 18 path (`.TLSv12` above). With nil
        // parameters the floor was whatever the system default allowed.
        let tls = __NWTLSParameters()
        tls.minimumSSLProtocolVersion = Self.minimumTLSVersion
        // Review of #621: a stop that landed first (the beat cancelled, timed
        // out) leaves nothing to open; the slot refuses. The connection is
        // made outside the slot's lock, and one a stop raced is cancelled
        // there rather than handed back here.
        guard let connection = slot.open(retaining: self, {
            provider.__createTCPConnectionThroughTunnel(to: endpoint, enableTLS: true,
                                                        tlsParameters: tls, delegate: self)
        }, cancel: { $0.cancel() }) else { return }
        // `.initial` reports the state now, so a cancel that raced this line is
        // still seen as `.cancelled` and releases the slot's hold on `self`.
        let observation = connection.observe(\.state, options: [.initial, .new]) { [weak self] observed, _ in
            guard let self else { return }
            let state = observed.state
            self.queue.async { self.stateChanged(state) }
        }
        lock.lock()
        self.observation = observation
        lock.unlock()
    }

    /// Cancels the connection, if one was opened, and makes sure none opens
    /// later. The state observation stays until `.cancelled` arrives; if that
    /// never comes, the slot lets go of this exchange and the connection
    /// `releaseAfterStop` later, and the observation goes with the exchange.
    func stop() {
        slot.stop()?.cancel()
    }

    /// `.cancelled` is the connection's last state: stop observing it and let
    /// the slot release this exchange.
    private func ended() {
        lock.lock()
        let observation = self.observation
        self.observation = nil
        lock.unlock()
        observation?.invalidate()
        slot.ended()
    }

    private func stateChanged(_ state: __NWTCPConnectionState) {
        switch state {
        case .connected:
            switch writeGate.connected() {
            case .ignore:
                return
            case .refuse:
                completion.finish(.failure(TunnelHeartbeatTransport.TransportError.untrusted))
                return
            case .write:
                break
            }
            guard let connection = slot.current else { return }
            connection.write(request) { [weak self] error in
                guard let self else { return }
                if error != nil {
                    self.completion.finish(.failure(TunnelHeartbeatTransport.TransportError.connectionFailed))
                } else {
                    self.queue.async { self.read(connection) }
                }
            }
        case .disconnected:
            // Review of #621: `Connection: close` means the server closes right
            // after its answer, and this state can arrive before the read that
            // saw the end. A complete answer already received is the answer.
            completion.finish(HeartbeatExchangeOutcome.atClose(reader.received))
        case .cancelled:
            completion.finish(.failure(TunnelHeartbeatTransport.TransportError.cancelled))
            ended()
        default:
            break
        }
    }

    private func read(_ connection: __NWTCPConnection) {
        connection.readMinimumLength(1, maximumLength: 16 * 1024) { [weak self] data, error in
            guard let self else { return }
            self.queue.async {
                if let data { self.reader.append(data) }
                let atEOF = error != nil || (data?.isEmpty ?? true)
                do {
                    if let response = try self.reader.response(atEOF: atEOF) {
                        self.completion.finish(.success(response))
                        return
                    }
                } catch {
                    self.completion.finish(.failure(TunnelHeartbeatTransport.TransportError.badResponse))
                    return
                }
                if atEOF {
                    self.completion.finish(.failure(TunnelHeartbeatTransport.TransportError.badResponse))
                } else {
                    self.read(connection)
                }
            }
        }
    }

    // MARK: __NWTCPConnectionAuthenticationDelegate

    func shouldEvaluateTrust(for connection: __NWTCPConnection) -> Bool {
        true
    }

    func evaluateTrust(for connection: __NWTCPConnection, peerCertificateChain: [Any],
                       completionHandler handBack: @escaping (SecTrust) -> Void) {
        guard let trust = PinnedTrust.trust(forPeerChain: peerCertificateChain, host: host) else {
            // Nothing to hand back that could pass: end the exchange instead.
            self.completion.finish(.failure(TunnelHeartbeatTransport.TransportError.untrusted))
            return
        }
        if HeartbeatPins.isTrusted(trust, host: host) {
            writeGate.approve()
            handBack(trust)
            return
        }
        // Refused: hand back a trust that cannot pass the system's own
        // evaluation either. The write is refused regardless (writeGate).
        if let refused = PinnedTrust.unpassableTrust(forPeerChain: peerCertificateChain, host: host) {
            handBack(refused)
        }
        self.completion.finish(.failure(TunnelHeartbeatTransport.TransportError.untrusted))
    }
}
