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

    func testGateOffDisablesTheRowAndReadsOff() {
        // Preference is ON — the user turned BirdoShield on while the gate was
        // up. It must still read OFF now.
        let row = BirdoShieldGate.row(preference: true, available: false)

        XCTAssertFalse(row.isOn, "the row must not read ON while the server ignores the flag")
        XCTAssertFalse(row.isEnabled, "the row must not be switchable")
        XCTAssertTrue(row.unavailable, "the row must explain why")
    }

    /// The gate never clears the user's choice: `row` is pure and cannot write,
    /// so the same preference comes straight back when the gate does.
    func testGateOffDoesNotClearThePreference() {
        let stored = true
        _ = BirdoShieldGate.row(preference: stored, available: false)

        let restored = BirdoShieldGate.row(preference: stored, available: true)
        XCTAssertTrue(restored.isOn, "the user's own choice returns with the gate")
        XCTAssertTrue(restored.isEnabled)
        XCTAssertFalse(restored.unavailable)
    }

    func testGateOnFollowsThePreference() {
        XCTAssertEqual(
            BirdoShieldGate.row(preference: true, available: true),
            BirdoShieldGate.Row(isOn: true, isEnabled: true, unavailable: false)
        )
        XCTAssertEqual(
            BirdoShieldGate.row(preference: false, available: true),
            BirdoShieldGate.Row(isOn: false, isEnabled: true, unavailable: false)
        )
    }

    /// The default that matters. `nil` is a cold start before the fetch lands,
    /// a failed fetch, or a web deploy older than #465. Unknown is AVAILABLE:
    /// hiding a feature that works because the device could not reach the web
    /// app is the asymmetric failure — the backend refuses the flag anyway
    /// while the gate is down.
    func testUnknownGateLeavesTheRowEnabled() {
        let row = BirdoShieldGate.row(preference: true, available: nil)
        XCTAssertTrue(row.isOn, "unknown must not read as off")
        XCTAssertTrue(row.isEnabled)
        XCTAssertFalse(row.unavailable, "and must not show the unavailable reason")
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

    // MARK: - The copy

    /// The reason must not ask the user to do anything — there is nothing they
    /// can do — and must say the preference survives, which is the only part
    /// they can act on (by leaving it alone).
    func testTheUnavailableReasonPromisesThePreferenceIsKept() {
        let reason = BirdoShieldGate.unavailableReason
        XCTAssertTrue(reason.contains("Not available"))
        XCTAssertTrue(reason.contains("preference is kept"),
                      "the copy must promise the choice survives: \(reason)")
    }
}
