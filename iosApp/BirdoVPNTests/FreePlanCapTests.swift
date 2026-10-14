import XCTest

/// MR-1980 — pins `FreePlanCap`, the number the App Store paywall's Recon card
/// states. It was a "10 GB monthly bandwidth" literal; the owner raised the
/// free cap to 50 GB (2026-10-13), and the card now follows the server.
final class FreePlanCapTests: XCTestCase {

    func testAReconAccountsOwnLimitIsTheFreeCap() {
        XCTAssertEqual(FreePlanCap.gb(plan: "RECON", bandwidthLimitGb: 50), 50)
        // Whatever the server says, not the build: a later change needs no release.
        XCTAssertEqual(FreePlanCap.gb(plan: "recon", bandwidthLimitGb: 75), 75)
    }

    func testAServerWideValueWinsOverTheAccountsOwn() {
        XCTAssertEqual(FreePlanCap.gb(configGb: 60, plan: "RECON", bandwidthLimitGb: 50), 60)
        XCTAssertEqual(FreePlanCap.gb(configGb: 60, plan: "SOVEREIGN", bandwidthLimitGb: 0), 60)
    }

    func testAPaidAccountsLimitSaysNothingAboutTheFreePlan() {
        // 0 = unlimited on a paid plan; and a paid plan's number is not Recon's.
        XCTAssertEqual(FreePlanCap.gb(plan: "OPERATIVE", bandwidthLimitGb: 0), FreePlanCap.fallbackGb)
        XCTAssertEqual(FreePlanCap.gb(plan: "SOVEREIGN", bandwidthLimitGb: 500), FreePlanCap.fallbackGb)
    }

    func testTheFallbackIsTheOwnersFiftyNotTheOldTen() {
        XCTAssertEqual(FreePlanCap.fallbackGb, 50)
        // A guest: no stats at all.
        XCTAssertEqual(FreePlanCap.gb(plan: nil, bandwidthLimitGb: nil), 50)
        // Nonsense from the server never reaches the card.
        XCTAssertEqual(FreePlanCap.gb(configGb: 0, plan: "RECON", bandwidthLimitGb: -1), 50)
        XCTAssertEqual(FreePlanCap.gb(configGb: .nan, plan: "RECON", bandwidthLimitGb: .infinity), 50)
    }

    func testTheCardLine() {
        XCTAssertEqual(FreePlanCap.featureLine(gb: 50), "50 GB monthly bandwidth")
        XCTAssertEqual(FreePlanCap.featureLine(gb: 12.5), "12.5 GB monthly bandwidth")
        XCTAssertEqual(FreePlanCap.featureLine(gb: FreePlanCap.gb(plan: nil, bandwidthLimitGb: nil)),
                       "50 GB monthly bandwidth")
    }

    // MARK: - The server-wide value: /api/client-config `plans` (birdo-web#708)

    private func config(_ json: String) throws -> ClientConfigResponse {
        try JSONDecoder().decode(ClientConfigResponse.self, from: Data(json.utf8))
    }

    private let published = #"""
    {
      "dnsFilteringAvailable": true,
      "plans": {
        "RECON":     { "monthlyPrice": 0, "yearlyPrice": 0,  "currency": "EUR", "bandwidthLimitGb": 75 },
        "OPERATIVE": { "monthlyPrice": 1, "yearlyPrice": 10, "currency": "EUR", "bandwidthLimitGb": null },
        "SOVEREIGN": { "monthlyPrice": 2, "yearlyPrice": 20, "currency": "EUR", "bandwidthLimitGb": null }
      }
    }
    """#

    func testThePublishedReconCapIsReadFromPlansAndComesFirst() throws {
        let cfg = try config(published)
        XCTAssertEqual(cfg.freePlanCapGb, 75)
        XCTAssertEqual(cfg.dnsFilteringAvailable, true)
        // Ahead of a Recon account's own limit, and of the fallback.
        XCTAssertEqual(FreePlanCap.gb(configGb: cfg.freePlanCapGb, plan: "RECON", bandwidthLimitGb: 50), 75)
        XCTAssertEqual(FreePlanCap.featureLine(gb: FreePlanCap.gb(configGb: cfg.freePlanCapGb,
                                                                  plan: nil, bandwidthLimitGb: nil)),
                       "75 GB monthly bandwidth")
        // The key's case is the server's business.
        XCTAssertEqual(try config(#"{"plans":{"recon":{"bandwidthLimitGb":60}}}"#).freePlanCapGb, 60)
    }

    func testAnOlderServerWithoutPlansLeavesTheAccountsLimitThenFifty() throws {
        let older = try config(#"{"dnsFilteringAvailable":false}"#)
        XCTAssertNil(older.freePlanCapGb)
        XCTAssertEqual(older.dnsFilteringAvailable, false)
        XCTAssertEqual(FreePlanCap.gb(configGb: older.freePlanCapGb, plan: "RECON", bandwidthLimitGb: 40), 40)
        XCTAssertEqual(FreePlanCap.gb(configGb: older.freePlanCapGb, plan: "SOVEREIGN", bandwidthLimitGb: 0), 50)
        XCTAssertEqual(FreePlanCap.gb(configGb: older.freePlanCapGb, plan: nil, bandwidthLimitGb: nil), 50)
        XCTAssertNil(try config(#"{"plans":null}"#).freePlanCapGb)
        XCTAssertNil(try config(#"{"plans":{"OPERATIVE":{"bandwidthLimitGb":null}}}"#).freePlanCapGb)
    }

    func testAMalformedPlansBlockCostsOnlyTheCapNeverTheDecode() throws {
        let shapes = [
            #""soon""#,
            #"[1, 2]"#,
            #"{"RECON": 50}"#,
            #"{"RECON": "50"}"#,
            #"{"RECON": {"bandwidthLimitGb": "50"}}"#,
            #"{"RECON": {"bandwidthLimitGb": null}}"#,
            #"{"RECON": {"bandwidthLimitGb": true}}"#,
            #"{"RECON": {"bandwidthLimitGb": {"value": 50}}}"#,
            #"{"RECON": {"bandwidthLimitGb": 0}}"#,
            #"{"RECON": {"bandwidthLimitGb": -5}}"#,
            #"{"RECON": {"bandwidthLimitGb": -1}}"#,
            #"{"RECON": {"bandwidthLimitGb": 12.5}}"#,
            #"{"RECON": {"bandwidthLimitGb": 100001}}"#,
            #"{"RECON": {"bandwidthLimitGb": 1e300}}"#,
            #"{"RECON": {}}"#,
        ]
        for plans in shapes {
            let cfg = try config(#"{"dnsFilteringAvailable":false,"features":{"RECON":{"customDns":false}},"plans":"# + plans + "}")
            XCTAssertNil(cfg.freePlanCapGb, "plans = \(plans) must yield no cap")
            XCTAssertEqual(cfg.dnsFilteringAvailable, false, "plans = \(plans) must not cost the BirdoShield gate")
            XCTAssertEqual(cfg.customDnsByPlan["RECON"], false, "plans = \(plans) must not cost the Custom DNS flag")
            XCTAssertEqual(FreePlanCap.gb(configGb: cfg.freePlanCapGb, plan: "RECON", bandwidthLimitGb: 40), 40,
                           "plans = \(plans): the card falls back to the account's own limit")
        }
    }

    // MARK: - #487 review: only a whole number of GB in 1...100,000 is used

    /// 1e300 used to reach `String(Int(gb))` in `format`, which traps (a crash
    /// on opening the paywall). Android takes only whole numbers; so does iOS.
    func testAnUnusableServerValueFallsBackAndNeverTraps() {
        for bad in [1e300, 12.5, -1, 0, .nan, .infinity, 100_001] as [Double] {
            XCTAssertNil(FreePlanCap.usable(bad), "\(bad) must not be usable")
            // From client-config: falls through to the account's own limit.
            XCTAssertEqual(FreePlanCap.gb(configGb: bad, plan: "RECON", bandwidthLimitGb: 40), 40, "\(bad)")
            // From /vpn/stats: falls through to the fallback.
            XCTAssertEqual(FreePlanCap.gb(plan: "RECON", bandwidthLimitGb: bad), FreePlanCap.fallbackGb, "\(bad)")
            XCTAssertEqual(FreePlanCap.featureLine(gb: FreePlanCap.gb(configGb: bad, plan: "RECON", bandwidthLimitGb: bad)),
                           "50 GB monthly bandwidth", "\(bad)")
        }
        XCTAssertEqual(FreePlanCap.usable(1), 1)
        XCTAssertEqual(FreePlanCap.usable(100_000), 100_000)
        XCTAssertEqual(FreePlanCap.maxGb, 100_000)
    }

    func testFormatNeverTrapsOnWhatItIsGiven() {
        // Not reachable from `gb` any more; `format` must still not crash.
        XCTAssertFalse(FreePlanCap.format(1e300).isEmpty)
        XCTAssertFalse(FreePlanCap.format(.nan).isEmpty)
        XCTAssertFalse(FreePlanCap.format(-.infinity).isEmpty)
        XCTAssertEqual(FreePlanCap.format(100_000), "100000")
    }
}
