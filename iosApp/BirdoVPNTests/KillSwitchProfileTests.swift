import XCTest

/// MR-1728 — pins `KillSwitchProfileFlags`, the protocol flags VPNManager
/// writes in `connect()`, `applyKillSwitchFlag()` and `failOpenAndStop()`.
///
/// Before: the kill switch set `includeAllNetworks` and `enforceRoutes` only,
/// so `excludeAPNs`, `excludeDeviceCommunication` and `excludeCellularServices`
/// stayed at Apple's default (true) and that traffic kept leaving outside the
/// tunnel with the switch on. The device check (APNs traffic inside the tunnel
/// with the kill switch on) comes later; this pins the decision.
///
/// MR-953 F2 — `enforceRoutes` used to follow the kill switch, so with the
/// switch off a more specific local or DHCP option 121 route beat the tunnel
/// (TunnelVision, CVE-2024-3661) and Local Network Sharing OFF was never
/// delivered. Every combination of the two settings is pinned below.
final class KillSwitchProfileTests: XCTestCase {

    private func flags(killSwitch: Bool, sharing: Bool) -> KillSwitchProfileFlags {
        KillSwitchProfileFlags.forProfile(killSwitch: killSwitch, localNetworkSharing: sharing)
    }

    func testKillSwitchOnKeepsPushAndDeviceTrafficInsideTheTunnel() {
        for sharing in [false, true] {
            let on = flags(killSwitch: true, sharing: sharing)
            XCTAssertTrue(on.includeAllNetworks)
            XCTAssertTrue(on.enforceRoutes)
            XCTAssertFalse(on.excludeAPNs, "push would leave outside the tunnel with the kill switch on")
            XCTAssertFalse(on.excludeDeviceCommunication,
                           "device-communication traffic would leave outside the tunnel with the kill switch on")
        }
    }

    /// Decided, not defaulted: carrier services (Wi-Fi Calling, emergency calls
    /// over Wi-Fi) go to the user's own carrier and break through a VPN exit.
    func testCarrierServicesStayExcludedOnPurpose() {
        for killSwitch in [false, true] {
            for sharing in [false, true] {
                XCTAssertTrue(flags(killSwitch: killSwitch, sharing: sharing).excludeCellularServices)
            }
        }
    }

    /// Off blocks nothing, and the exemptions (inert without
    /// includeAllNetworks) go back to Apple's defaults. The routes are still
    /// enforced (F2): enforceRoutes routes, it does not block.
    func testKillSwitchOffBlocksNothingAndRestoresApplesDefaults() {
        XCTAssertEqual(flags(killSwitch: false, sharing: false),
                       KillSwitchProfileFlags(includeAllNetworks: false,
                                              enforceRoutes: true,
                                              excludeLocalNetworks: false,
                                              excludeAPNs: true,
                                              excludeDeviceCommunication: true,
                                              excludeCellularServices: true))
    }

    /// The two exemptions follow the switch; the carrier one does not.
    func testOnlyTheSwitchDecidesTheDifference() {
        let on = flags(killSwitch: true, sharing: false)
        let off = flags(killSwitch: false, sharing: false)
        XCTAssertNotEqual(on.excludeAPNs, off.excludeAPNs)
        XCTAssertNotEqual(on.excludeDeviceCommunication, off.excludeDeviceCommunication)
        XCTAssertEqual(on.excludeCellularServices, off.excludeCellularServices)
    }

    // MARK: - MR-953 F2: every kill switch × Local Network Sharing combination

    /// The whole table, field by field, so no combination is left to a
    /// default. Old code: `enforceRoutes` was false in both kill-switch-off
    /// rows, and the local-network flag was not decided here at all.
    func testEveryKillSwitchAndLocalSharingCombination() {
        let table: [(killSwitch: Bool, sharing: Bool, expected: KillSwitchProfileFlags)] = [
            (false, false, KillSwitchProfileFlags(includeAllNetworks: false, enforceRoutes: true,
                                                   excludeLocalNetworks: false, excludeAPNs: true,
                                                   excludeDeviceCommunication: true,
                                                   excludeCellularServices: true)),
            (false, true, KillSwitchProfileFlags(includeAllNetworks: false, enforceRoutes: true,
                                                  excludeLocalNetworks: true, excludeAPNs: true,
                                                  excludeDeviceCommunication: true,
                                                  excludeCellularServices: true)),
            (true, false, KillSwitchProfileFlags(includeAllNetworks: true, enforceRoutes: true,
                                                  excludeLocalNetworks: false, excludeAPNs: false,
                                                  excludeDeviceCommunication: false,
                                                  excludeCellularServices: true)),
            (true, true, KillSwitchProfileFlags(includeAllNetworks: true, enforceRoutes: true,
                                                 excludeLocalNetworks: true, excludeAPNs: false,
                                                 excludeDeviceCommunication: false,
                                                 excludeCellularServices: true)),
        ]
        for row in table {
            XCTAssertEqual(flags(killSwitch: row.killSwitch, sharing: row.sharing), row.expected,
                           "killSwitch=\(row.killSwitch) localNetworkSharing=\(row.sharing)")
        }
    }

    /// TunnelVision: with the kill switch OFF a more specific route on the
    /// local network (the connected subnet, or one a DHCP server pushed with
    /// option 121) must still lose to the tunnel's routes.
    func testRoutesAreEnforcedWithTheKillSwitchOff() {
        XCTAssertTrue(flags(killSwitch: false, sharing: false).enforceRoutes)
        XCTAssertTrue(flags(killSwitch: false, sharing: true).enforceRoutes)
    }

    /// Apple honours `excludeLocalNetworks` only when `enforceRoutes` or
    /// `includeAllNetworks` is also true. Sharing ON must therefore never come
    /// with both off, or the setting silently does nothing.
    func testLocalSharingIsAlwaysPairedWithAFlagThatMakesItTakeEffect() {
        for killSwitch in [false, true] {
            let f = flags(killSwitch: killSwitch, sharing: true)
            XCTAssertTrue(f.excludeLocalNetworks)
            XCTAssertTrue(f.enforceRoutes || f.includeAllNetworks,
                          "excludeLocalNetworks is inert without enforceRoutes or includeAllNetworks")
        }
    }

    /// Local Network Sharing OFF carries the LAN in the tunnel whatever the
    /// kill switch says; it never changes what the kill switch blocks.
    func testLocalSharingDecidesOnlyTheLocalNetworkFlag() {
        for killSwitch in [false, true] {
            var shared = flags(killSwitch: killSwitch, sharing: true)
            let notShared = flags(killSwitch: killSwitch, sharing: false)
            XCTAssertFalse(notShared.excludeLocalNetworks)
            shared.excludeLocalNetworks = false
            XCTAssertEqual(shared, notShared)
        }
    }
}
