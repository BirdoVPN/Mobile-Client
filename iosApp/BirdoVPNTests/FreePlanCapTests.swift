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
}
