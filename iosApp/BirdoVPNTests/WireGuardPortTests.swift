import XCTest

/// LIVE-PORT53 — pins `WireGuardPort`, compiled directly into this un-hosted
/// bundle.
///
/// The relays accept WireGuard on 51820 only (all ten checked 2026-10-01: no
/// listener and no DNAT on 53). Builds up to 1.4.32 offered "53" and a custom
/// port, which gave a dead tunnel, and "51820", which was Automatic. The port
/// is no longer a setting. These pin the two halves of retiring it:
///   1. the saved choice becomes "auto", once (Android's
///      `AppPreferences.retireWireGuardPortChoice`, same three inputs);
///   2. a tunnel profile an older build saved, with the port baked into its
///      Endpoint line, is dialled on 51820 by the extension, while a profile
///      this build wrote keeps the server's own port.
final class WireGuardPortTests: XCTestCase {

    private var suiteName = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "WireGuardPortTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    private var stored: String? { defaults.string(forKey: WireGuardPort.legacyDefaultsKey) }

    // MARK: - The saved choice is retired

    func testASavedFiftyThreeBecomesAutomatic() {
        defaults.set("53", forKey: "wg_port")
        XCTAssertTrue(WireGuardPort.retireStoredChoice(in: defaults))
        XCTAssertEqual(stored, "auto")
    }

    func testASavedCustomPortBecomesAutomatic() {
        defaults.set("4500", forKey: "wg_port")
        XCTAssertTrue(WireGuardPort.retireStoredChoice(in: defaults))
        XCTAssertEqual(stored, "auto")
    }

    /// The "51820" preset was the same as Automatic; Android retires it too,
    /// so neither platform keeps a port choice the screen no longer shows.
    func testTheOldFiftyEighteenTwentyPresetBecomesAutomatic() {
        defaults.set("51820", forKey: "wg_port")
        XCTAssertTrue(WireGuardPort.retireStoredChoice(in: defaults))
        XCTAssertEqual(stored, "auto")
    }

    /// The pre-T3 picker persisted the literal tag "custom".
    func testTheLegacyCustomTagBecomesAutomatic() {
        defaults.set("custom", forKey: "wg_port")
        XCTAssertTrue(WireGuardPort.retireStoredChoice(in: defaults))
        XCTAssertEqual(stored, "auto")
    }

    func testItRunsOnceAndLeavesTheMtuAlone() {
        defaults.set("53", forKey: "wg_port")
        defaults.set(1380, forKey: "wg_mtu")
        XCTAssertTrue(WireGuardPort.retireStoredChoice(in: defaults))
        XCTAssertFalse(WireGuardPort.retireStoredChoice(in: defaults), "the second launch finds nothing to do")
        XCTAssertEqual(stored, "auto")
        XCTAssertEqual(defaults.integer(forKey: "wg_mtu"), 1380)
    }

    func testAutomaticAndAFreshInstallWriteNothing() {
        XCTAssertFalse(WireGuardPort.retireStoredChoice(in: defaults))
        XCTAssertNil(defaults.object(forKey: "wg_port"), "a fresh install gains no key")

        defaults.set("auto", forKey: "wg_port")
        XCTAssertFalse(WireGuardPort.retireStoredChoice(in: defaults))
        XCTAssertEqual(stored, "auto")
    }

    // MARK: - An older build's tunnel profile is dialled on the relays' port

    /// The shape `VPNManager.buildRedactedWireGuardConfig` writes.
    private func profile(endpoint: String) -> String {
        [
            "[Interface]",
            "Address = 10.13.13.7/32",
            "DNS = 1.1.1.1, 1.0.0.1",
            "MTU = 1420",
            "",
            "[Peer]",
            "PublicKey = server-public-key",
            "Endpoint = \(endpoint)",
            "AllowedIPs = 0.0.0.0/0",
            "AllowedIPs = ::/0",
            "PersistentKeepalive = 25",
        ].joined(separator: "\n")
    }

    func testAnOlderProfileOnFiftyThreeIsDialledOnTheRelayPort() {
        XCTAssertEqual(WireGuardPort.dialableConfig(profile(endpoint: "203.0.113.7:53"), providerConfiguration: [:]),
                       profile(endpoint: "203.0.113.7:51820"))
    }

    func testAnOlderProfileOnACustomPortIsDialledOnTheRelayPort() {
        XCTAssertEqual(WireGuardPort.dialableConfig(profile(endpoint: "203.0.113.7:4500"), providerConfiguration: nil),
                       profile(endpoint: "203.0.113.7:51820"))
        XCTAssertEqual(WireGuardPort.dialableConfig(profile(endpoint: "[2001:db8::7]:4500"), providerConfiguration: nil),
                       profile(endpoint: "[2001:db8::7]:51820"))
    }

    func testAnOlderProfileAlreadyOnTheRelayPortIsUnchanged() {
        let config = profile(endpoint: "203.0.113.7:51820")
        XCTAssertEqual(WireGuardPort.dialableConfig(config, providerConfiguration: [:]), config)
    }

    /// A profile this build wrote names the server's own port: the server
    /// stays the authority on where its WireGuard listens.
    func testAProfileThisBuildWroteKeepsTheServersPort() {
        let config = profile(endpoint: "203.0.113.7:4500")
        let marked: [String: Any] = [
            "wg-config": config,
            WireGuardPort.endpointPortSourceKey: WireGuardPort.endpointPortFromServer,
        ]
        XCTAssertEqual(WireGuardPort.dialableConfig(config, providerConfiguration: marked), config)
        XCTAssertEqual(WireGuardPort.dialableConfig(config,
                                                    providerConfiguration: [WireGuardPort.endpointPortSourceKey: "user"]),
                       profile(endpoint: "203.0.113.7:51820"),
                       "only the exact marker counts")
    }

    func testOnlyTheEndpointLineIsTouched() {
        // Same parser rules as the vendored wg-quick reader: key case is
        // ignored, a trailing comment is not part of the value.
        let config = "[Peer]\nendpoint=203.0.113.7:53 # old preset\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25"
        XCTAssertEqual(WireGuardPort.pinEndpointPort(in: config),
                       "[Peer]\nEndpoint = 203.0.113.7:51820\nAllowedIPs = 0.0.0.0/0\nPersistentKeepalive = 25")
    }

    func testAnUnbracketedIPv6EndpointIsNotRewrittenIntoAnotherAddress() {
        // No port can be told apart from the address here, and WireGuard
        // rejects it anyway; turning it into a different address helps nobody.
        let config = profile(endpoint: "2001:db8::7")
        XCTAssertEqual(WireGuardPort.pinEndpointPort(in: config), config)
    }
}
