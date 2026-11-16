import XCTest

/// MR-953 F1/F3/F4 — the call sites this bundle cannot compile.
///
/// The PacketTunnel extension and the vendored WireGuardKit (NetworkExtension,
/// libwg-go) are not linked into this un-hosted bundle, so their helpers are
/// tested directly (HeartbeatHTTPTests, TunnelIPv6BlackholeTests,
/// TunnelSettingsApplyTests) and this file reads the sources to check the
/// helpers are actually WIRED: a re-vendor of WireGuardKit or a refactor of the
/// provider that drops a call site fails here instead of shipping quietly.
final class TunnelRouteContractGuardTests: XCTestCase {

    /// iosApp/, from this file's own path (iosApp/BirdoVPNTests/...).
    private let iosRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()

    private func source(_ relative: String) throws -> String {
        let url = iosRoot.appendingPathComponent(relative)
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// Code only: line comments dropped, so prose about the old behaviour
    /// cannot satisfy or trip a check.
    private func code(_ relative: String) throws -> String {
        try source(relative)
            .components(separatedBy: .newlines)
            .map { line -> String in
                guard let range = line.range(of: "//") else { return line }
                return String(line[..<range.lowerBound])
            }
            .joined(separator: "\n")
    }

    // MARK: F4, local patch 5

    func testTheAdapterNoLongerProceedsAfterASettingsTimeout() throws {
        let adapter = try code("Vendor/wireguard-apple/Sources/WireGuardKit/WireGuardAdapter.swift")
        XCTAssertFalse(adapter.contains("proceeding anyway"))
        XCTAssertTrue(adapter.contains("TunnelSettingsApply.run("),
                      "setNetworkSettings must wait through TunnelSettingsApply")
        XCTAssertTrue(adapter.contains("throw WireGuardAdapterError.setNetworkSettings(failure)"),
                      "an unanswered or refused apply must throw")
        XCTAssertFalse(adapter.contains("condition.wait(until:"),
                       "the upstream wait (lost-wakeup prone, proceeds on timeout) is back")
    }

    /// A resume that fails is retried on a timer and then REPORTED, instead of
    /// waiting for a path event that may never come while the system says
    /// connected (TunnelResumeWatchdogTests runs the timer itself).
    func testAFailedResumeIsRetriedAndThenRoutedIntoTheBreaker() throws {
        let adapter = try code("Vendor/wireguard-apple/Sources/WireGuardKit/WireGuardAdapter.swift")
        XCTAssertTrue(adapter.contains("self.resumeWatchdog.pathResumeFailed()"),
                      "a failed resume must start the retry timer")
        XCTAssertTrue(adapter.contains("if self.resumeBackend(with: settingsGenerator) {"))
        // The timer retries the same resume, under TunnelResumeWatchdog.retry's guard
        // (paused AND a satisfied path), which TunnelResumeWatchdogTests runs.
        let retry = try XCTUnwrap(adapter.components(separatedBy: "private func retryResume()").last)
        XCTAssertTrue(retry.contains("return TunnelResumeWatchdog.retry(paused: paused, pathSatisfied: satisfied) {"))
        XCTAssertTrue(retry.contains("if case .temporaryShutdown(let settingsGenerator) = self.state {"))
        XCTAssertTrue(retry.contains("let satisfied = self.networkMonitor?.currentPath.status.isSatisfiable == true"))
        XCTAssertTrue(retry.contains("return self.resumeBackend(with: settingsGenerator)"),
                      "the timer must retry the same resume")
        // stop() resets the watchdog: no retry may outlive the tunnel.
        let stop = try XCTUnwrap(adapter.components(separatedBy: "public func stop(completionHandler:").last)
        let stopBody = try XCTUnwrap(stop.components(separatedBy: "public func update(").first)
        XCTAssertTrue(stopBody.contains("self.resumeWatchdog.reset()"))
        XCTAssertTrue(adapter.contains("self?.onResumeExhausted?()"), "exhaustion must reach the provider")
        XCTAssertEqual(adapter.components(separatedBy: "self.resumeWatchdog.reset()").count - 1, 3,
                       "reset on stop, on going offline, and on a resume that worked")
        // Fail closed: the backend is only ever started after the settings applied.
        let resume = try XCTUnwrap(adapter.components(separatedBy: "private func resumeBackend(").last)
        let apply = try XCTUnwrap(resume.range(of: "try self.setNetworkSettings("))
        let start = try XCTUnwrap(resume.range(of: "try self.startWireGuardBackend("))
        XCTAssertLessThan(apply.lowerBound, start.lowerBound)

        let provider = try code("PacketTunnel/PacketTunnelProvider.swift")
        XCTAssertTrue(provider.contains("adapter.onResumeExhausted = { [weak self] in self?.handleResumeExhausted() }"))
        let handler = try XCTUnwrap(provider.components(separatedBy: "private func handleResumeExhausted() {").last)
        XCTAssertTrue(handler.contains("self?.handleDataPlaneFailure(.diedAfterHandshake"),
                      "a tunnel that cannot resume must go through the circuit breaker")
    }

    // MARK: F3, local patch 6

    func testTheGeneratorGivesAV4OnlyTunnelThePlaceholder() throws {
        let generator = try code("Vendor/wireguard-apple/Sources/WireGuardKit/PacketTunnelSettingsGenerator.swift")
        XCTAssertTrue(generator.contains("TunnelIPv6Blackhole.addressesToAdd(tunnelHasIPv6Address: !ipv6Addresses.isEmpty"),
                      "the placeholder decision must see the interface's own IPv6 addresses")
        XCTAssertTrue(generator.contains("ipv6RoutedIntoTunnel: !ipv6IncludedRoutes.isEmpty"))
        XCTAssertTrue(generator.contains("ipv6Addresses.append(NEIPv6Route(destinationAddress: placeholder.address"))
        XCTAssertTrue(generator.contains("NEIPv6Settings(addresses: ipv6Addresses.map"),
                      "the NEIPv6Settings must be built from the list the placeholder was added to")
    }

    // MARK: F1

    /// The extension's heartbeat goes through `heartbeatTransport`; nothing in
    /// the provider opens a URLSession, which would leave outside the tunnel.
    func testTheProviderSendsTheHeartbeatThroughTheTunnelTransport() throws {
        let provider = try code("PacketTunnel/PacketTunnelProvider.swift")
        XCTAssertTrue(provider.contains("try await heartbeatTransport.send(request)"))
        XCTAssertTrue(provider.contains("TunnelHeartbeatTransport(provider: self)"))
        XCTAssertFalse(provider.contains("URLSession("), "a URLSession in the provider leaves outside the tunnel")
        XCTAssertFalse(provider.contains(".data(for:"), "a URLSession request in the provider leaves outside the tunnel")
    }

    /// Both ways into the tunnel are Apple's, there is no third, and the beat
    /// is skipped (never sent around the tunnel) when neither is available.
    func testTheTransportOnlyEverConnectsThroughTheTunnel() throws {
        let transport = try code("PacketTunnel/TunnelHeartbeatTransport.swift")
        XCTAssertTrue(transport.contains("parameters.requiredInterface = interface"))
        XCTAssertTrue(transport.contains("provider.virtualInterface"))
        XCTAssertTrue(transport.contains("createTCPConnectionThroughTunnel(to: endpoint, enableTLS: true"))
        XCTAssertTrue(transport.contains("throw TransportError.tunnelUnavailable"))
        XCTAssertFalse(transport.contains("URLSession"))
        XCTAssertFalse(transport.contains("createTCPConnection(to:"),
                       "NEProvider.createTCPConnection goes OUTSIDE the tunnel")
        XCTAssertFalse(transport.contains("createUDPSession(to:"),
                       "NEProvider.createUDPSession goes OUTSIDE the tunnel")
        XCTAssertEqual(transport.components(separatedBy: "NWConnection(").count - 1, 1,
                       "every NWConnection must be the one built on the tunnel's interface")
    }

    /// The pin check runs on both ways in, before a byte is written.
    func testTheTransportIsPinned() throws {
        let transport = try code("PacketTunnel/TunnelHeartbeatTransport.swift")
        XCTAssertEqual(transport.components(separatedBy: "HeartbeatPins.isTrusted(").count - 1, 2)
        XCTAssertTrue(transport.contains("sec_protocol_options_set_verify_block"))
        // iOS 17 path: the write waits for the gate, and only this pin check opens it.
        XCTAssertTrue(transport.contains("switch writeGate.connected() {"))
        XCTAssertEqual(transport.components(separatedBy: "writeGate.approve()").count - 1, 1)
        let approval = try XCTUnwrap(transport.range(of: "writeGate.approve()"))
        let check = try XCTUnwrap(transport.range(of: "if HeartbeatPins.isTrusted(trust, host: host) {"))
        XCTAssertLessThan(check.lowerBound, approval.lowerBound, "approval must follow the pin check")
        XCTAssertEqual(transport.components(separatedBy: "connection.write(").count - 1, 1,
                       "the one write is the one behind the gate")
        // The decision both paths end in is PinnedTrust's, run by HeartbeatTrustTests;
        // the provider only supplies the production pin set.
        let provider = try code("PacketTunnel/PacketTunnelProvider.swift")
        XCTAssertTrue(provider.contains("static func isTrusted(_ trust: SecTrust, host: String) -> Bool"))
        XCTAssertTrue(provider.contains("PinnedTrust.isTrusted(trust, host: host, pins: pins)"))
        let trust = try code("PacketTunnel/HeartbeatTrust.swift")
        let evaluate = try XCTUnwrap(trust.range(of: "SecTrustEvaluateWithError(trust, &trustError)"))
        let match = try XCTUnwrap(trust.range(of: "pins.contains(hash)"))
        XCTAssertLessThan(evaluate.lowerBound, match.lowerBound, "standard validation must run before the pin match")
    }

    /// TLS 1.2 is the floor on BOTH ways in. The iOS 17 / macOS 14 path used
    /// to pass no TLS parameters at all, i.e. the system's default floor.
    func testBothPathsRefuseTLSBelow12() throws {
        let transport = try code("PacketTunnel/TunnelHeartbeatTransport.swift")
        XCTAssertTrue(transport.contains("sec_protocol_options_set_min_tls_protocol_version(options, .TLSv12)"))
        XCTAssertTrue(transport.contains("tls.minimumSSLProtocolVersion = Self.minimumTLSVersion"))
        // kTLSProtocol12 == 8 in Security's SSLProtocol.
        XCTAssertTrue(transport.contains("private static let minimumTLSVersion = 8"))
        XCTAssertTrue(transport.contains("tlsParameters: tls, delegate: self"))
        XCTAssertFalse(transport.contains("tlsParameters: nil"),
                       "nil TLS parameters leave the minimum version to the system default")
    }

    /// The iOS 18 / macOS 15 path: ATS cipher suites only, and no TLS session
    /// resumption, so the verify block (the pin check) runs on every connection.
    func testTheInterfacePathUsesATSCiphersAndNoResumption() throws {
        let transport = try code("PacketTunnel/TunnelHeartbeatTransport.swift")
        let modern = try XCTUnwrap(transport.components(separatedBy: "private final class InterfaceExchange").last)
        XCTAssertTrue(modern.contains("sec_protocol_options_append_tls_ciphersuite_group(options, .ats)"))
        XCTAssertTrue(modern.contains("sec_protocol_options_set_tls_resumption_enabled(options, false)"))
        let verify = try XCTUnwrap(modern.range(of: "sec_protocol_options_set_verify_block"))
        let noResume = try XCTUnwrap(modern.range(of: "sec_protocol_options_set_tls_resumption_enabled"))
        XCTAssertLessThan(noResume.lowerBound, verify.lowerBound, "set on the same options, before the connection")
    }

    /// The request's own timeout reaches the exchange (HeartbeatExchangeTests
    /// runs the rule and the completion): a 5 s dead-tunnel probe must not
    /// wait the beat's 20 s while the tunnel holds every packet.
    func testTheTransportHonoursTheRequestsTimeout() throws {
        let transport = try code("PacketTunnel/TunnelHeartbeatTransport.swift")
        XCTAssertTrue(transport.contains("let deadline = HeartbeatExchangeTimeout.seconds(for: request, cap: timeout)"))
        XCTAssertTrue(transport.contains("try await completion.run(timeout: deadline,"))
        XCTAssertFalse(transport.contains("completion.run(timeout: timeout,"), "the fixed 20 s is back")
        XCTAssertTrue(transport.contains("timeout: TimeInterval = HeartbeatExchangeTimeout.cap"))
    }

    /// No connection is opened after the exchange was stopped, on either path;
    /// the iOS 17 delegate outlives its connection; and a close right after a
    /// complete answer is the answer. HeartbeatExchangeTests runs the slot, the
    /// start guard and the close rule; this pins that the exchanges use them.
    func testTheExchangesNeverOpenAfterAStop() throws {
        let exchange = try code("PacketTunnel/HeartbeatExchange.swift")
        XCTAssertTrue(exchange.contains("guard !isFinished else { return }"))
        let transport = try code("PacketTunnel/TunnelHeartbeatTransport.swift")
        let legacy = try XCTUnwrap(transport.components(separatedBy: "private final class LegacyTunnelExchange").last)
        XCTAssertTrue(legacy.contains("guard let connection = slot.open(retaining: self, {"))
        // A connection a stop raced while it was being made is cancelled by
        // the open; the slot's release fallback runs on the exchange's queue.
        XCTAssertTrue(legacy.contains("}, cancel: { $0.cancel() }) else { return }"))
        XCTAssertTrue(legacy.contains("self.slot = HeartbeatConnectionSlot(queue: queue)"))
        XCTAssertTrue(legacy.contains("slot.stop()?.cancel()"))
        XCTAssertEqual(legacy.components(separatedBy: "slot.ended()").count - 1, 1)
        let cancelled = try XCTUnwrap(legacy.components(separatedBy: "case .cancelled:").last)
        XCTAssertTrue(cancelled.prefix(200).contains("ended()"), "the delegate is let go only at .cancelled")
        XCTAssertFalse(legacy.contains("__NWTCPConnection?"), "a connection outside the slot is back")
        let disconnected = try XCTUnwrap(legacy.components(separatedBy: "case .disconnected:").last)
        XCTAssertTrue(disconnected.prefix(400).contains("HeartbeatExchangeOutcome.atClose("))
        let modern = try XCTUnwrap(transport.components(separatedBy: "private final class InterfaceExchange").last)
        XCTAssertTrue(modern.contains("guard !stopped else { return }"))
    }
}
