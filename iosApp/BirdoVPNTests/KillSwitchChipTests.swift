import XCTest

/// MR-975 (iOS half) — pins `KillSwitchChip.resolve`, the Home kill-switch
/// chip decided from the configuration the system holds, not the toggle.
///
/// Before: "blocked" was `armed && toggle && !connected`, so a block still in
/// force after the toggle went off was hidden, and nothing at all was said
/// while connected. The device check (does the chip match what the phone
/// actually does?) comes later; this pins the mapping.
final class KillSwitchChipTests: XCTestCase {

    private let armed = KillSwitchSystemState(onDemandEnabled: true, hasConnectRule: true,
                                              includeAllNetworks: true)
    /// The steady disconnected state: connect() saves with on-demand off and
    /// disconnect() disarms first; includeAllNetworks stays as last built.
    private let disarmed = KillSwitchSystemState(onDemandEnabled: false, hasConnectRule: false,
                                                 includeAllNetworks: true)

    private func chip(_ system: KillSwitchSystemState?, up: Bool, toggle: Bool) -> KillSwitchChip {
        KillSwitchChip.resolve(system: system, tunnelUp: up, preferenceOn: toggle)
    }

    func testArmedWithTheTunnelDownIsBlockingWhateverTheToggleSays() {
        XCTAssertEqual(chip(armed, up: false, toggle: true), .blocking)
        // The toggle was just switched off but the system still holds the
        // rule (the save has not landed, or failed): traffic IS blocked, so
        // the chip must say so. The old chip hid it.
        XCTAssertEqual(chip(armed, up: false, toggle: false), .blocking)
    }

    func testArmedWithTheTunnelUpSaysTheKillSwitchIsOn() {
        XCTAssertEqual(chip(armed, up: true, toggle: true), .active)
        XCTAssertEqual(chip(armed, up: true, toggle: false), .active)
    }

    func testConnectedButNotArmedClaimsNothing() {
        // Before the handshake arms it, or when the arm failed: the toggle
        // being on must not produce an "on" chip.
        XCTAssertEqual(chip(disarmed, up: true, toggle: true), .hidden)
        XCTAssertEqual(chip(nil, up: true, toggle: true), .hidden)
    }

    func testEveryPartOfTheArmIsRequired() {
        let parts: [KillSwitchSystemState] = [
            KillSwitchSystemState(onDemandEnabled: false, hasConnectRule: true, includeAllNetworks: true),
            KillSwitchSystemState(onDemandEnabled: true, hasConnectRule: false, includeAllNetworks: true),
            KillSwitchSystemState(onDemandEnabled: true, hasConnectRule: true, includeAllNetworks: false),
        ]
        for state in parts {
            XCTAssertFalse(state.isArmed, "\(state)")
            XCTAssertNotEqual(chip(state, up: false, toggle: true), .blocking, "\(state)")
            XCTAssertNotEqual(chip(state, up: true, toggle: true), .active, "\(state)")
        }
        XCTAssertTrue(armed.isArmed)
    }

    func testDisconnectedAndDisarmedIsOnlyAHintAboutTheNextSession() {
        XCTAssertEqual(chip(disarmed, up: false, toggle: true), .armsOnConnect)
        XCTAssertEqual(chip(disarmed, up: false, toggle: false), .hidden)
        // No VPN configuration yet (fresh install): nothing can be blocked.
        XCTAssertEqual(chip(nil, up: false, toggle: true), .armsOnConnect)
        XCTAssertEqual(chip(nil, up: false, toggle: false), .hidden)
    }
}
