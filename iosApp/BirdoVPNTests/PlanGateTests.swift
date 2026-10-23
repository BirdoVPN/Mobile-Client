import XCTest

/// MR-1517 — pins `PlanGate`: Multi-Hop and Port Forwarding (and Stealth,
/// decoded for parity although iOS has no Stealth) follow the server's
/// `features.<PLAN>` table from `/api/client-config` wherever it gave a flag,
/// and the owner's 2026-10-07 table everywhere else. The views that ask it
/// (HomeView, MultiHopView, SettingsView, PortForwardView) cannot be compiled
/// into this bundle; MobileMiscRowsGuardTest pins that they do.
final class PlanGateTests: XCTestCase {

    private func decode(_ json: String) throws -> ClientConfigResponse {
        try JSONDecoder().decode(ClientConfigResponse.self, from: Data(json.utf8))
    }

    /// The decided table, as birdo-web app/api/client-config/route.ts serves it.
    private let decided: [String: [PlanFeature: Bool]] = [
        "RECON": [.stealthMode: false, .multiHop: false, .portForwarding: false],
        "OPERATIVE": [.stealthMode: true, .multiHop: false, .portForwarding: false],
        "SOVEREIGN": [.stealthMode: true, .multiHop: true, .portForwarding: true],
    ]

    func testWithNoConfigTheBuiltInTableIsTheDecidedOne() {
        for (plan, features) in decided {
            for (feature, expected) in features {
                XCTAssertEqual(PlanGate.has(plan: plan, config: nil, feature: feature), expected, "\(plan) \(feature)")
                XCTAssertEqual(PlanGate.has(plan: plan.lowercased(), config: nil, feature: feature), expected)
            }
        }
        XCTAssertEqual(PlanGate.lowestPlan(with: .multiHop, config: nil), "SOVEREIGN")
        XCTAssertEqual(PlanGate.lowestPlan(with: .portForwarding, config: nil), "SOVEREIGN")
        XCTAssertEqual(PlanGate.lowestPlan(with: .stealthMode, config: nil), "OPERATIVE")
    }

    /// The subscription has not loaded: no answer, so no lock is guessed.
    func testAnUnknownPlanHasNoAnswer() throws {
        let cfg = try decode(#"{ "features": { "RECON": { "multiHop": true } } }"#)
        for feature in PlanFeature.allCases {
            XCTAssertNil(PlanGate.has(plan: nil, config: nil, feature: feature))
            XCTAssertNil(PlanGate.has(plan: nil, config: cfg, feature: feature))
        }
    }

    /// The payload birdo-web serves today decodes to the decided table.
    func testTheServedTableDecodes() throws {
        let cfg = try decode("""
        { "dnsFilteringAvailable": true, "features": {
            "RECON": { "splitTunneling": true, "stealthMode": false, "multiHop": false,
                       "portForwarding": false, "customDns": true },
            "OPERATIVE": { "splitTunneling": true, "stealthMode": true, "multiHop": false,
                           "portForwarding": false, "customDns": true },
            "SOVEREIGN": { "splitTunneling": true, "stealthMode": true, "multiHop": true,
                           "portForwarding": true, "customDns": true } } }
        """)
        XCTAssertEqual(cfg.planGates, decided)
        XCTAssertEqual(cfg.dnsFilteringAvailable, true)
    }

    /// The server's flag decides wherever it gave one; anything it left out
    /// keeps the built-in table.
    func testTheServerFlagWins() throws {
        let cfg = try decode("""
        { "features": {
            "operative": { "multiHop": true, "portForwarding": true },
            "SOVEREIGN": { "multiHop": false } } }
        """)
        XCTAssertEqual(PlanGate.has(plan: "OPERATIVE", config: cfg, feature: .multiHop), true)
        XCTAssertEqual(PlanGate.has(plan: "Operative", config: cfg, feature: .portForwarding), true)
        XCTAssertEqual(PlanGate.has(plan: "SOVEREIGN", config: cfg, feature: .multiHop), false)
        XCTAssertEqual(PlanGate.has(plan: "SOVEREIGN", config: cfg, feature: .portForwarding), true)
        XCTAssertEqual(PlanGate.has(plan: "RECON", config: cfg, feature: .multiHop), false)
        // The upsell names the lowest plan the table gives the feature.
        XCTAssertEqual(PlanGate.lowestPlan(with: .multiHop, config: cfg), "OPERATIVE")
        XCTAssertEqual(PlanGate.lowestPlan(with: .portForwarding, config: cfg), "OPERATIVE")
    }

    /// A plan this build does not know reads only its own flag, never another
    /// plan's, and otherwise ranks as the free tier.
    func testAnUnknownPlanSlugReadsOnlyItsOwnFlag() throws {
        let cfg = try decode(#"{ "features": { "ELITE": { "multiHop": true } } }"#)
        XCTAssertEqual(PlanGate.has(plan: "ELITE", config: cfg, feature: .multiHop), true)
        XCTAssertEqual(PlanGate.has(plan: "ELITE", config: cfg, feature: .portForwarding), false)
        XCTAssertEqual(PlanGate.has(plan: "TRIAL", config: cfg, feature: .multiHop), false)
    }

    /// A flag of an unexpected type costs only that flag, never the plan's
    /// other flags, the custom-DNS flag or the BirdoShield gate beside them.
    func testAnOddFlagFallsBackOnItsOwn() throws {
        let cfg = try decode("""
        { "dnsFilteringAvailable": false, "features": {
            "RECON": { "multiHop": "yes", "stealthMode": null, "portForwarding": false, "customDns": false },
            "OPERATIVE": ["multiHop"],
            "SOVEREIGN": { "multiHop": false, "portForwarding": "no" } } }
        """)
        XCTAssertEqual(cfg.dnsFilteringAvailable, false)
        XCTAssertFalse(CustomDnsGate.isAvailable(plan: "RECON", config: cfg))
        XCTAssertEqual(cfg.planGates["RECON"], [.portForwarding: false])
        XCTAssertNil(cfg.planGates["OPERATIVE"])
        XCTAssertEqual(PlanGate.has(plan: "RECON", config: cfg, feature: .multiHop), false)
        XCTAssertEqual(PlanGate.has(plan: "OPERATIVE", config: cfg, feature: .stealthMode), true)
        XCTAssertEqual(PlanGate.has(plan: "SOVEREIGN", config: cfg, feature: .multiHop), false)
        XCTAssertEqual(PlanGate.has(plan: "SOVEREIGN", config: cfg, feature: .portForwarding), true)
    }

    /// An older deploy without `features`, or an unexpected shape of it,
    /// leaves the built-in table.
    func testNoFeaturesMapMeansTheBuiltInTable() throws {
        for json in [#"{ "dnsFilteringAvailable": true }"#, #"{ "features": ["SOVEREIGN"] }"#, #"{ "features": null }"#] {
            let cfg = try decode(json)
            XCTAssertTrue(cfg.planGates.isEmpty, json)
            XCTAssertEqual(PlanGate.has(plan: "SOVEREIGN", config: cfg, feature: .multiHop), true, json)
            XCTAssertEqual(PlanGate.has(plan: "OPERATIVE", config: cfg, feature: .portForwarding), false, json)
        }
    }
}
