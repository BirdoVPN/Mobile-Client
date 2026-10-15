import Foundation

/// MR-1728 — the tunnel-protocol flags the kill switch sets, decided in one
/// place for the three sites that write them (`VPNManager.connect`,
/// `applyKillSwitchFlag` and `failOpenAndStop`).
///
/// `includeAllNetworks` sends everything through the tunnel and drops what
/// cannot go through it, EXCEPT the traffic classes Apple exempts by default.
/// Each exemption below defaults to true, none was ever set, so with the kill
/// switch "on" that traffic still left outside the tunnel:
///   - `excludeAPNs` (iOS 16.4 / macOS 13.3): Apple Push Notification service.
///     FALSE with the kill switch on: push rides the tunnel like everything
///     else, and is held with everything else while the tunnel is down.
///   - `excludeDeviceCommunication` (iOS 17.4 / macOS 14.4): traffic with
///     devices this one communicates with directly. FALSE with the kill switch
///     on, as the register asks. Features that talk to such a device can stop
///     working while the switch is on; that is the cost of "nothing leaves
///     outside the tunnel", and the device check covers it.
///   - `excludeCellularServices` (iOS 16.4 / macOS 13.3): the carrier's own
///     services (Wi-Fi Calling, VoLTE, SMS/MMS over IMS, visual voicemail).
///     Deliberately left TRUE. That traffic goes to the user's own carrier,
///     which already knows who and where the subscriber is, and sent through a
///     VPN exit it breaks Wi-Fi Calling, emergency calls placed over Wi-Fi
///     included.
///
/// With the kill switch off `includeAllNetworks` is false and the system ignores
/// all three, so they are written back to Apple's defaults: the saved profile
/// then reads the same as one this app never touched. `excludeLocalNetworks`
/// is not here: it follows the user's Local Network Sharing setting, kill
/// switch or not (`VPNManager.connect`).
///
/// Foundation-only on purpose: KillSwitchProfileTests pins the decision, and
/// VPNManager (NetworkExtension) cannot be compiled into BirdoVPNTests.
struct KillSwitchProfileFlags: Equatable, Sendable {
    var includeAllNetworks: Bool
    var enforceRoutes: Bool
    var excludeAPNs: Bool
    var excludeDeviceCommunication: Bool
    var excludeCellularServices: Bool

    static func forKillSwitch(_ on: Bool) -> KillSwitchProfileFlags {
        KillSwitchProfileFlags(
            includeAllNetworks: on,
            enforceRoutes: on,
            excludeAPNs: !on,
            excludeDeviceCommunication: !on,
            excludeCellularServices: true
        )
    }
}
