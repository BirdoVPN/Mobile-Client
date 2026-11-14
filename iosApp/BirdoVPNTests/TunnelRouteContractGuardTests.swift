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
        XCTAssertTrue(transport.contains("guard approved else"))
        let provider = try code("PacketTunnel/PacketTunnelProvider.swift")
        XCTAssertTrue(provider.contains("static func isTrusted(_ trust: SecTrust, host: String) -> Bool"))
        XCTAssertTrue(provider.contains("SecTrustEvaluateWithError(trust, &trustError)"),
                      "standard validation must run before the pin match")
    }
}
