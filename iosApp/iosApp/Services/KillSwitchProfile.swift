import Foundation

/// MR-1728 — the tunnel-protocol flags the kill switch and Local Network
/// Sharing set, decided in one place for the three sites that write them
/// (`VPNManager.connect`, `applyKillSwitchFlag` and `failOpenAndStop`).
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
/// then reads the same as one this app never touched.
///
/// MR-953 F2 — `enforceRoutes` is ON whatever the kill switch says, and
/// `excludeLocalNetworks` follows the user's Local Network Sharing setting.
/// `enforceRoutes` used to follow the kill switch. With the switch off, a
/// more specific route on the local network then won over the tunnel's
/// 0.0.0.0/0: the connected subnet, or a route a hostile DHCP server pushed
/// with option 121 (TunnelVision, CVE-2024-3661). That traffic left outside
/// the tunnel while the app showed Protected. Apple's contract
/// (`NEVPNProtocol`, iOS 14.2 / macOS 11; the deployment targets are 17 / 14):
///   - `enforceRoutes`: the tunnel's route rules take precedence over any
///     locally defined route. With `includeAllNetworks` false, the included
///     routes are scoped to the VPN. It routes; it does not block, so with
///     the tunnel down traffic flows normally (that is `includeAllNetworks`).
///   - `excludeLocalNetworks` takes effect ONLY when `includeAllNetworks` or
///     `enforceRoutes` is also true. Then connections to hosts on the local
///     network stay outside the tunnel.
/// So Local Network Sharing ON keeps `enforceRoutes` on as well: the LAN is
/// left out by `excludeLocalNetworks`, which is the only way that setting is
/// delivered at all with the kill switch off, while a non-local route pushed
/// by DHCP still loses to the tunnel. Local Network Sharing OFF carries the
/// LAN in the tunnel, with or without the kill switch.
///
/// Foundation-only on purpose: KillSwitchProfileTests pins the decision, and
/// VPNManager (NetworkExtension) cannot be compiled into BirdoVPNTests.
struct KillSwitchProfileFlags: Equatable, Sendable {
    var includeAllNetworks: Bool
    var enforceRoutes: Bool
    var excludeLocalNetworks: Bool
    var excludeAPNs: Bool
    var excludeDeviceCommunication: Bool
    var excludeCellularServices: Bool

    /// - Parameters:
    ///   - killSwitch: the user's kill switch.
    ///   - localNetworkSharing: the user's Local Network Sharing setting (at
    ///     connect), or the value the live profile already carries (a kill
    ///     switch push must not change it: iOS applies it only at tunnel start,
    ///     see `VPNManager.liveProfileNeedsRestart`).
    static func forProfile(killSwitch: Bool, localNetworkSharing: Bool) -> KillSwitchProfileFlags {
        KillSwitchProfileFlags(
            includeAllNetworks: killSwitch,
            enforceRoutes: true,
            excludeLocalNetworks: localNetworkSharing,
            excludeAPNs: !killSwitch,
            excludeDeviceCommunication: !killSwitch,
            excludeCellularServices: true
        )
    }
}
