import XCTest

/// BirdoShield (D18) FLEET GATE — pins `BirdoShieldGate`, the rule behind the
/// BirdoShield row in `VpnSettingsView`.
///
/// `DNS_FILTERING_ENABLED` is a fleet-wide rollout switch. While it is off,
/// birdo-web's `WireguardService.resolveDnsServers` IGNORES the per-device
/// `dnsFiltering` connect flag and hands the tunnel the normal resolver — so a
/// row the user can switch ON claims something the server will not do. That is
/// the estate's "never render reassurance from missing data" rule, and it is
/// what birdo-web#465's `dnsFilteringAvailable` exists to let clients avoid.
///
/// Same cases as Android's `BirdoShieldAvailabilityTest` and the desktop
/// `BirdoShieldToggle.test.tsx`, so the three clients agree on what
/// "unavailable" means — most importantly on what UNKNOWN means.
final class BirdoShieldGateTests: XCTestCase {

    // MARK: - The row

    func testGateOffHidesTheRowAndReadsOff() {
        // Preference is ON — the user turned BirdoShield on while the gate was
        // up. It must still read OFF now.
        let row = BirdoShieldGate.row(preference: true, available: false)

        XCTAssertFalse(row.isOn, "the row must not read ON while the server ignores the flag")
        XCTAssertFalse(row.isEnabled, "the row must not be switchable")
        XCTAssertFalse(row.isVisible, "the row is hidden while the fleet gate is off (owner 2026-11-13)")
    }

    /// The gate never clears the user's choice: `row` is pure and cannot write,
    /// so the same preference comes straight back when the gate does.
    func testGateOffDoesNotClearThePreference() {
        let stored = true
        _ = BirdoShieldGate.row(preference: stored, available: false)

        let restored = BirdoShieldGate.row(preference: stored, available: true)
        XCTAssertTrue(restored.isOn, "the user's own choice returns with the gate")
        XCTAssertTrue(restored.isEnabled)
        XCTAssertTrue(restored.isVisible, "the row comes back with the gate")
    }

    func testGateOnFollowsThePreference() {
        XCTAssertEqual(
            BirdoShieldGate.row(preference: true, available: true),
            BirdoShieldGate.Row(isOn: true, isEnabled: true, isVisible: true)
        )
        XCTAssertEqual(
            BirdoShieldGate.row(preference: false, available: true),
            BirdoShieldGate.Row(isOn: false, isEnabled: true, isVisible: true)
        )
    }

    /// `nil` is a cold start before the fetch lands, a failed fetch, or a web
    /// deploy older than #465. Owner 2026-11-13: unknown HIDES the row and it
    /// reads off, like an explicit off; it appears when the fetch confirms the
    /// gate.
    func testUnknownGateHidesTheRowAndReadsOff() {
        let row = BirdoShieldGate.row(preference: true, available: nil)
        XCTAssertFalse(row.isOn, "unknown must not read as on")
        XCTAssertFalse(row.isEnabled)
        XCTAssertFalse(row.isVisible, "the row is not shown until the gate is known to be on")
    }

    // MARK: - The wire shape

    /// `false` and "key absent" must stay distinguishable, and the fields this
    /// client does not model must be ignored rather than rejected: a decode
    /// failure is treated exactly like a network error, which would silently
    /// turn every future web-side addition into "gate unknown".
    func testDecodingDistinguishesFalseFromAbsent() throws {
        func decode(_ json: String) throws -> ClientConfigResponse {
            try JSONDecoder().decode(ClientConfigResponse.self, from: Data(json.utf8))
        }

        XCTAssertEqual(try decode(#"{"dnsFilteringAvailable":true}"#).dnsFilteringAvailable, true)
        XCTAssertEqual(try decode(#"{"dnsFilteringAvailable":false}"#).dnsFilteringAvailable, false)
        XCTAssertNil(
            try decode(#"{"version":1}"#).dnsFilteringAvailable,
            "a pre-#465 payload omits the key entirely — that is unknown, not off"
        )
    }

    func testTheUnmodelledHalfOfTheRealPayloadIsIgnored() throws {
        let payload = """
        {
          "version": 1,
          "certPins": { "hosts": { "birdo.app": { "pins": [] } } },
          "dnsFilteringAvailable": false,
          "features": { "RECON": { "dnsFiltering": true, "stealthMode": false } },
          "consent": { "vpnDisclaimer": "...", "dataCollection": "..." },
          "minimumVersions": { "android": "1.0.0", "windows": "1.0.0" }
        }
        """
        let cfg = try JSONDecoder().decode(ClientConfigResponse.self, from: Data(payload.utf8))
        XCTAssertEqual(cfg.dnsFilteringAvailable, false)
        XCTAssertFalse(
            BirdoShieldGate.row(preference: true, available: cfg.dnsFilteringAvailable).isEnabled,
            "the decoded gate must drive the row"
        )
    }

    // MARK: - MR-2373: a gate flipped while connected (owner 2026-11-20)
    //
    // When the server turns the gate on during a CONNECTED session the app does
    // not reconnect; the row says "Applies when you next connect". Same cases
    // as Android's BirdoShieldAvailabilityTest (MR-2373).

    func testARowReadingOnOverASessionDialledWithTheGateOffSaysItAppliesWhenYouNextConnect() {
        let row = BirdoShieldGate.row(preference: true, available: true, sessionGate: false)
        XCTAssertTrue(row.isVisible)
        XCTAssertTrue(row.isOn, "the switch still shows the user's choice")
        XCTAssertTrue(row.isEnabled, "and can still be switched (a user's flip re-dials, as before)")
        XCTAssertTrue(row.appliesWhenNextConnected)
    }

    func testNoNoteWhenThereIsNothingForTheNextConnectToApply() {
        // Dialled under the gate on: the session already filters.
        XCTAssertFalse(BirdoShieldGate.row(preference: true, available: true, sessionGate: true).appliesWhenNextConnected)
        // No connected session.
        XCTAssertFalse(BirdoShieldGate.row(preference: true, available: true, sessionGate: nil).appliesWhenNextConnected)
        // The preference is off: the gate changes nothing for this session.
        XCTAssertFalse(BirdoShieldGate.row(preference: false, available: true, sessionGate: false).appliesWhenNextConnected)
        // A gate turned OFF mid-session hides the row as before (owner 2026-11-13).
        let off = BirdoShieldGate.row(preference: true, available: false, sessionGate: true)
        XCTAssertFalse(off.isVisible)
        XCTAssertFalse(off.appliesWhenNextConnected)
        // With no session the row is what it always was.
        XCTAssertEqual(
            BirdoShieldGate.row(preference: true, available: true),
            BirdoShieldGate.Row(isOn: true, isEnabled: true, isVisible: true)
        )
    }

    func testTheNoteIsTheOwnersWords() {
        XCTAssertEqual(BirdoShieldGate.nextConnectNotice, "Applies when you next connect")
    }

    /// The gate a dial was built under is the client's own record, injected by
    /// APIClient: a server can never set it.
    func testTheDialledGateIsNeverReadFromTheWire() throws {
        let json = #"{"endpoint":"1.2.3.4:51820","serverPublicKey":"pk","assignedIp":"10.0.0.2","dialedShieldGate":true}"#
        let config = try JSONDecoder().decode(VPNConnectionConfig.self, from: Data(json.utf8))
        XCTAssertNil(config.dialedShieldGate)
    }

    /// Wiring the bundle cannot compile (the view and the view models pull in
    /// the whole app): the dial records its gate, the session carries it to
    /// the row, the row shows the note, and a fetched gate never re-dials.
    func testTheConnectedSessionsGateReachesTheRowAndAFlipNeverReDials() throws {
        func count(_ needle: String, in text: String) -> Int { text.components(separatedBy: needle).count - 1 }
        let api = try source("iosApp/Services/APIClient.swift")
        XCTAssertEqual(count("let dnsFilteringEnabled = Self.dnsFilteringEnabled(gateOn: shieldGateOn)", in: api), 2,
                       "both connect bodies are built under the gate they record")
        XCTAssertEqual(count("config.dialedShieldGate = shieldGateOn", in: api), 2)
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        XCTAssertEqual(count("sessionShieldGate = config.dialedShieldGate", in: vm), 2, "connect and Multi-Hop")
        XCTAssertTrue(vm.contains("sessionShieldGate = ctx.newConfig?.dialedShieldGate"), "a committed live rebuild")
        XCTAssertTrue(vm.contains("sessionShieldGate = nil"), "the session's reset")
        let view = try source("iosApp/Views/VpnSettingsView.swift")
        XCTAssertTrue(view.contains("sessionGate: vpnVM.isConnected ? vpnVM.sessionShieldGate : nil"))
        XCTAssertTrue(view.contains("? BirdoShieldGate.nextConnectNotice : nil"))
        let settings = try source("iosApp/ViewModels/SettingsViewModel.swift")
        let fetch = try XCTUnwrap(
            settings.components(separatedBy: "func refreshClientConfig() async {").dropFirst().first?
                .components(separatedBy: "\n    }\n").first
        )
        XCTAssertTrue(fetch.contains("ConnectWire.dnsFilteringGateDefaultsKey"), "the fetch keeps the gate for the next dial")
        XCTAssertFalse(fetch.contains("requestSettingsReapply"), "a fetched gate never re-dials (owner 2026-11-20)")
    }

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/BirdoShieldGateTests.swift -> .../iosApp
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let url = root.appendingPathComponent(path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            XCTFail("\(path) not found under \(root.path): this pin would be vacuous")
            return ""
        }
        return try String(contentsOf: url, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
    }
}
