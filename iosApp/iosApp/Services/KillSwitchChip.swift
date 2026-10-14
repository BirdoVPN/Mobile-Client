import Foundation

/// MR-975 (iOS half) — the Home kill-switch chip, decided from what the SYSTEM
/// holds rather than from the Settings toggle.
///
/// What holds traffic while the tunnel is down is the VPN configuration iOS and
/// macOS keep for this app, and two parts of it together:
///   - an enabled on-demand CONNECT rule — a dropped tunnel is dialled again
///     instead of the device falling back to the open network;
///   - `includeAllNetworks` on the tunnel protocol — while the tunnel is not
///     up, traffic is dropped rather than sent around it;
/// and only while that configuration is the one the system uses (`isEnabled`),
/// which the user can switch off in iOS Settings at any time. VPNManager reads
/// all of it fresh from the system on every configuration and status change.
/// The rule is armed only after a real handshake
/// (`VpnViewModel.armKillSwitchAfterHandshake`) and is disarmed by connect()
/// before dialling and by disconnect() before stopping. So the toggle being on
/// says nothing about NOW: it is what the NEXT session will arm.
///
/// The old chip ANDed the system state with the toggle, so a block that was
/// really in force (the toggle just switched off, its save not landed or
/// failed) was hidden; and while connected it said nothing at all, so a session
/// whose kill switch had not armed looked the same as one whose had.
///
/// Foundation-only on purpose: VPNManager reads the NETunnelProviderManager into
/// `KillSwitchSystemState`, and the BirdoVPNTests bundle pins the mapping
/// (KillSwitchChipTests) without NetworkExtension.
struct KillSwitchSystemState: Equatable, Sendable {
    /// `NEVPNManager.isEnabled`: this app's configuration is the one the system
    /// will use. Turning the VPN off in iOS Settings, or choosing another
    /// app's VPN there, clears it, and then none of the flags below does
    /// anything (vpn-d3 review).
    var configurationEnabled: Bool
    /// `NEVPNManager.isOnDemandEnabled`.
    var onDemandEnabled: Bool
    /// The on-demand rules include an `NEOnDemandRuleConnect`.
    var hasConnectRule: Bool
    /// `NEVPNProtocol.includeAllNetworks` on the saved configuration.
    var includeAllNetworks: Bool

    /// The kill switch is in force: a drop is held and dialled again.
    var isArmed: Bool {
        configurationEnabled && onDemandEnabled && hasConnectRule && includeAllNetworks
    }
}

/// What the Home chip says.
enum KillSwitchChip: Equatable, Sendable {
    /// Nothing to say.
    case hidden
    /// Armed and the tunnel is down: every packet is being dropped.
    case blocking
    /// Armed and the tunnel is up: a drop will be held and dialled again.
    case active
    /// Not armed, tunnel down, toggle on: nothing is blocked now; the next
    /// session arms it. The only case the toggle decides — connect() builds
    /// the next session's flags from it.
    case armsOnConnect

    /// - Parameters:
    ///   - system: the configuration as the system holds it; nil when this app
    ///     has no VPN configuration yet (nothing can be armed).
    ///   - tunnelUp: the tunnel is connected.
    ///   - preferenceOn: the Settings toggle.
    static func resolve(system: KillSwitchSystemState?, tunnelUp: Bool, preferenceOn: Bool) -> KillSwitchChip {
        if system?.isArmed == true {
            return tunnelUp ? .active : .blocking
        }
        // Connected but not armed (the handshake has not landed yet, or the
        // arm failed): claim nothing. Showing `.active` here is the lie this
        // mapping exists to prevent.
        if tunnelUp { return .hidden }
        return preferenceOn ? .armsOnConnect : .hidden
    }
}
