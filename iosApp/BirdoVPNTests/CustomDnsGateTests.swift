import XCTest

/// Owner item 40 — pins `CustomDnsGate`: Custom DNS is available on EVERY
/// plan; the server's client-config `customDns` flag for the plan is honoured
/// when present, and anything else (no config, no flag, an unexpected shape)
/// reads as available.
final class CustomDnsGateTests: XCTestCase {

    private func decode(_ json: String) throws -> ClientConfigResponse {
        try JSONDecoder().decode(ClientConfigResponse.self, from: Data(json.utf8))
    }

    func testNoConfigYetMeansAvailableOnEveryPlan() {
        for plan in ["RECON", "OPERATIVE", "SOVEREIGN", "recon"] {
            XCTAssertTrue(CustomDnsGate.isAvailable(plan: plan, config: nil), plan)
        }
    }

    /// The shape birdo-web serves after item 40: `true` for every plan.
    func testServerTrueForEveryPlan() throws {
        let cfg = try decode("""
        { "features": {
            "RECON": { "customDns": true, "multiHop": false },
            "OPERATIVE": { "customDns": true },
            "SOVEREIGN": { "customDns": true } } }
        """)
        XCTAssertTrue(CustomDnsGate.isAvailable(plan: "RECON", config: cfg))
        XCTAssertTrue(CustomDnsGate.isAvailable(plan: "operative", config: cfg))
        XCTAssertTrue(CustomDnsGate.isAvailable(plan: "SOVEREIGN", config: cfg))
    }

    /// An explicit server `false` for a plan is honoured (the pre-item-40
    /// payload said this for RECON and OPERATIVE).
    func testExplicitFalseForAPlanIsHonoured() throws {
        let cfg = try decode("""
        { "features": {
            "RECON": { "customDns": false },
            "SOVEREIGN": { "customDns": true } } }
        """)
        XCTAssertFalse(CustomDnsGate.isAvailable(plan: "RECON", config: cfg))
        XCTAssertTrue(CustomDnsGate.isAvailable(plan: "SOVEREIGN", config: cfg))
        XCTAssertTrue(CustomDnsGate.isAvailable(plan: "OPERATIVE", config: cfg),
                      "a plan the map does not mention defaults to available")
    }

    func testFlatFlagAppliesToEveryPlan() throws {
        let cfg = try decode(#"{ "features": { "customDns": false } }"#)
        XCTAssertFalse(CustomDnsGate.isAvailable(plan: "SOVEREIGN", config: cfg))
        let on = try decode(#"{ "features": { "customDns": true, "RECON": { "customDns": false } } }"#)
        XCTAssertTrue(CustomDnsGate.isAvailable(plan: "RECON", config: on),
                      "the flat flag wins over the per-plan map")
    }

    func testNoFeaturesKeyMeansAvailable() throws {
        let cfg = try decode(#"{ "dnsFilteringAvailable": true }"#)
        XCTAssertTrue(CustomDnsGate.isAvailable(plan: "RECON", config: cfg))
    }

    /// A `features` shape this build does not expect must cost only the
    /// custom-DNS flag — never the BirdoShield gate decoded beside it.
    func testUnexpectedFeaturesShapeKeepsTheBirdoShieldGate() throws {
        let cfg = try decode(#"{ "dnsFilteringAvailable": false, "features": ["RECON"] }"#)
        XCTAssertEqual(cfg.dnsFilteringAvailable, false)
        XCTAssertTrue(CustomDnsGate.isAvailable(plan: "RECON", config: cfg))

        let odd = try decode(#"{ "dnsFilteringAvailable": true, "features": { "RECON": { "customDns": "yes" } } }"#)
        XCTAssertEqual(odd.dnsFilteringAvailable, true)
        XCTAssertTrue(CustomDnsGate.isAvailable(plan: "RECON", config: odd))
    }
}
