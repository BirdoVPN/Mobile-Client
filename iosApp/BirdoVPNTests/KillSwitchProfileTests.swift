import XCTest

/// MR-1728 — pins `KillSwitchProfileFlags`, the protocol flags VPNManager
/// writes in `connect()`, `applyKillSwitchFlag()` and `failOpenAndStop()`.
///
/// Before: the kill switch set `includeAllNetworks` and `enforceRoutes` only,
/// so `excludeAPNs`, `excludeDeviceCommunication` and `excludeCellularServices`
/// stayed at Apple's default (true) and that traffic kept leaving outside the
/// tunnel with the switch on. The device check (APNs traffic inside the tunnel
/// with the kill switch on) comes later; this pins the decision.
final class KillSwitchProfileTests: XCTestCase {

    func testKillSwitchOnKeepsPushAndDeviceTrafficInsideTheTunnel() {
        let on = KillSwitchProfileFlags.forKillSwitch(true)
        XCTAssertTrue(on.includeAllNetworks)
        XCTAssertTrue(on.enforceRoutes)
        XCTAssertFalse(on.excludeAPNs, "push would leave outside the tunnel with the kill switch on")
        XCTAssertFalse(on.excludeDeviceCommunication,
                       "device-communication traffic would leave outside the tunnel with the kill switch on")
    }

    /// Decided, not defaulted: carrier services (Wi-Fi Calling, emergency calls
    /// over Wi-Fi) go to the user's own carrier and break through a VPN exit.
    func testCarrierServicesStayExcludedOnPurpose() {
        XCTAssertTrue(KillSwitchProfileFlags.forKillSwitch(true).excludeCellularServices)
        XCTAssertTrue(KillSwitchProfileFlags.forKillSwitch(false).excludeCellularServices)
    }

    /// Off blocks nothing, and the exemptions (inert without
    /// includeAllNetworks) go back to Apple's defaults.
    func testKillSwitchOffBlocksNothingAndRestoresApplesDefaults() {
        XCTAssertEqual(KillSwitchProfileFlags.forKillSwitch(false),
                       KillSwitchProfileFlags(includeAllNetworks: false,
                                              enforceRoutes: false,
                                              excludeAPNs: true,
                                              excludeDeviceCommunication: true,
                                              excludeCellularServices: true))
    }

    /// The two exemptions follow the switch; the carrier one does not.
    func testOnlyTheSwitchDecidesTheDifference() {
        let on = KillSwitchProfileFlags.forKillSwitch(true)
        let off = KillSwitchProfileFlags.forKillSwitch(false)
        XCTAssertNotEqual(on.excludeAPNs, off.excludeAPNs)
        XCTAssertNotEqual(on.excludeDeviceCommunication, off.excludeDeviceCommunication)
        XCTAssertEqual(on.excludeCellularServices, off.excludeCellularServices)
    }
}
