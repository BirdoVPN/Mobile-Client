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
    enum TransportError: Error, Equatable {
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

    private weak var provider: NEPacketTunnelProvider?
    private let timeout: TimeInterval
    private let queue = DispatchQueue(label: "app.birdo.vpn.tunnel.heartbeat-transport")

    /// - Parameter timeout: the whole exchange, connect to last byte. 20 s, the
    ///   old URLSession's per-request timeout.
    init(provider: NEPacketTunnelProvider, timeout: TimeInterval = 20) {
        self.provider = provider
        self.timeout = timeout
    }

    /// One request through the tunnel. Returns the body and an HTTPURLResponse
    /// carrying the status code, the shape `URLSession.data(for:)` returned.
    func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
        guard let url = request.url?.absoluteURL, let host = url.host, !host.isEmpty,
              let port = UInt16(exactly: url.port ?? 443),
              let bytes = HeartbeatHTTP.encode(request) else {
            throw TransportError.invalidRequest
        }
        guard let provider else { throw TransportError.tunnelUnavailable }

        let completion = ExchangeCompletion()
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
        let response = try await completion.run(timeout: timeout, queue: queue, start: start, stop: stop)
        guard let http = HTTPURLResponse(url: url, statusCode: response.statusCode,
                                         httpVersion: "HTTP/1.1", headerFields: nil) else {
            throw TransportError.badResponse
        }
        return (response.body, http)
    }
}

// MARK: - One exchange, completed exactly once

/// Resumes the caller exactly once, with whichever comes first: the response,
/// a failure, the timeout or task cancellation; then stops the connection.
private final class ExchangeCompletion: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<HeartbeatHTTP.Response, Error>?
    private var finished = false
    private var stop: (@Sendable () -> Void)?

    func run(timeout: TimeInterval, queue: DispatchQueue,
             start: @escaping @Sendable () -> Void,
             stop: @escaping @Sendable () -> Void) async throws -> HeartbeatHTTP.Response {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<HeartbeatHTTP.Response, Error>) in
                lock.lock()
                if finished {
                    lock.unlock()
                    cont.resume(throwing: TunnelHeartbeatTransport.TransportError.cancelled)
                    return
                }
                continuation = cont
                self.stop = stop
                lock.unlock()
                queue.asyncAfter(deadline: .now() + timeout) { [self] in
                    finish(.failure(TunnelHeartbeatTransport.TransportError.timedOut))
                }
                queue.async { start() }
            }
        } onCancel: {
            finish(.failure(TunnelHeartbeatTransport.TransportError.cancelled))
        }
    }

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

// MARK: - iOS 18 / macOS 15: Network.framework on the tunnel's interface

@available(iOS 18.0, macOS 15.0, *)
private final class InterfaceExchange: @unchecked Sendable {
    private let connection: NWConnection
    private let request: Data
    private let queue: DispatchQueue
    private let completion: ExchangeCompletion
    private var buffer = Data()   // touched only on `queue`

    init(host: String, port: UInt16, interface: NWInterface, request: Data,
         queue: DispatchQueue, completion: ExchangeCompletion) {
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        sec_protocol_options_set_tls_server_name(options, host)
        sec_protocol_options_add_tls_application_protocol(options, "http/1.1")
        sec_protocol_options_set_min_tls_protocol_version(options, .TLSv12)
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
            if let data { self.buffer.append(data) }
            let atEOF = isComplete || error != nil
            do {
                if let response = try HeartbeatHTTP.parse(self.buffer, atEOF: atEOF) {
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
    private let completion: ExchangeCompletion
    private let lock = NSLock()
    private var connection: __NWTCPConnection?
    private var observation: NSKeyValueObservation?
    /// Set by `evaluateTrust` and required before a byte is written: the
    /// connection must have been through THIS pin check, whatever the
    /// system's own evaluation of the trust handed back says.
    private var pinnedTrustApproved = false
    private var sent = false
    private var buffer = Data()   // touched only on `queue`

    init(provider: NEPacketTunnelProvider, host: String, port: UInt16, request: Data,
         queue: DispatchQueue, completion: ExchangeCompletion) {
        self.provider = provider
        self.host = host
        self.port = port
        self.request = request
        self.queue = queue
        self.completion = completion
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
        let connection = provider.__createTCPConnectionThroughTunnel(to: endpoint, enableTLS: true,
                                                                   tlsParameters: tls, delegate: self)
        let observation = connection.observe(\.state, options: [.initial, .new]) { [weak self] observed, _ in
            guard let self else { return }
            let state = observed.state
            self.queue.async { self.stateChanged(state) }
        }
        lock.lock()
        self.connection = connection
        self.observation = observation
        lock.unlock()
    }

    func stop() {
        lock.lock()
        let connection = self.connection
        let observation = self.observation
        self.observation = nil
        lock.unlock()
        observation?.invalidate()
        connection?.cancel()
    }

    private func stateChanged(_ state: __NWTCPConnectionState) {
        switch state {
        case .connected:
            lock.lock()
            let approved = pinnedTrustApproved
            let first = !sent
            sent = true
            let connection = self.connection
            lock.unlock()
            guard first, let connection else { return }
            guard approved else {
                completion.finish(.failure(TunnelHeartbeatTransport.TransportError.untrusted))
                return
            }
            connection.write(request) { [weak self] error in
                guard let self else { return }
                if error != nil {
                    self.completion.finish(.failure(TunnelHeartbeatTransport.TransportError.connectionFailed))
                } else {
                    self.queue.async { self.read(connection) }
                }
            }
        case .disconnected:
            completion.finish(.failure(TunnelHeartbeatTransport.TransportError.connectionFailed))
        case .cancelled:
            completion.finish(.failure(TunnelHeartbeatTransport.TransportError.cancelled))
        default:
            break
        }
    }

    private func read(_ connection: __NWTCPConnection) {
        connection.readMinimumLength(1, maximumLength: 16 * 1024) { [weak self] data, error in
            guard let self else { return }
            self.queue.async {
                if let data { self.buffer.append(data) }
                let atEOF = error != nil || (data?.isEmpty ?? true)
                do {
                    if let response = try HeartbeatHTTP.parse(self.buffer, atEOF: atEOF) {
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
        let certificates: [SecCertificate] = peerCertificateChain.compactMap { item in
            let ref = item as CFTypeRef
            guard CFGetTypeID(ref) == SecCertificateGetTypeID() else { return nil }
            return (ref as! SecCertificate)
        }
        let policy = SecPolicyCreateSSL(true, host as CFString)
        var trust: SecTrust?
        guard !certificates.isEmpty,
              SecTrustCreateWithCertificates(certificates as CFArray, policy, &trust) == errSecSuccess,
              let trust else {
            // Nothing to hand back that could pass: end the exchange instead.
            self.completion.finish(.failure(TunnelHeartbeatTransport.TransportError.untrusted))
            return
        }
        if HeartbeatPins.isTrusted(trust, host: host) {
            lock.lock()
            pinnedTrustApproved = true
            lock.unlock()
            handBack(trust)
            return
        }
        // A trust that cannot pass the system's own evaluation: no anchors at
        // all, and only those. The write above is refused regardless.
        var refused: SecTrust?
        if SecTrustCreateWithCertificates(certificates as CFArray, policy, &refused) == errSecSuccess,
           let refused {
            SecTrustSetAnchorCertificates(refused, [] as CFArray)
            SecTrustSetAnchorCertificatesOnly(refused, true)
            handBack(refused)
        }
        self.completion.finish(.failure(TunnelHeartbeatTransport.TransportError.untrusted))
    }
}
