import Foundation

/// BirdoShield (D18) FLEET GATE — the PURE half of "may the toggle claim to work".
///
/// This file deliberately imports nothing beyond Foundation: the BirdoVPNTests
/// unit bundle compiles it directly (un-hosted, no app host, no SwiftUI), so
/// the rule is pinned by tests rather than by a device run nobody repeats.
/// `VpnSettingsView.swift` and `SettingsViewModel.swift` cannot be compiled
/// into that bundle — they pull in the whole app — which is why the decision
/// does not live there. Same shape as QuickSelect.swift and LiveRebuild.swift.
///
/// WHAT IT GUARDS. `DNS_FILTERING_ENABLED` is a fleet-wide rollout switch.
/// While it is off, birdo-web's `WireguardService.resolveDnsServers` IGNORES
/// the per-device `dnsFiltering` connect flag and hands the tunnel the normal
/// resolver — so a BirdoShield row the user can switch ON is a claim about
/// what the server will do that the server will not honour. birdo-web#465
/// publishes the switch as `dnsFilteringAvailable` on `GET /api/client-config`
/// so every client can ask. Android's twin is `birdoShieldRowState` in
/// `VpnSettingsScreen.kt`; the desktop twin is `fleetGateOff` in
/// `src/screens/VpnSettings.tsx`.

/// `GET /api/client-config` — only the fields this client acts on.
///
/// Not a full mirror of the payload: it also serves cert pins (vendored into
/// `third_party/` and enforced from there), per-plan feature entitlements
/// (which arrive with the subscription) and consent copy. Modelling them here
/// would create a second source of truth for each. `Decodable` ignores unknown
/// keys by default, so web-side additions never break a shipped client.
struct ClientConfigResponse: Decodable {
    /// Is DNS filtering switched on for the fleet this account dials?
    ///
    /// `nil` (key absent — a web deploy older than #465) means UNKNOWN, which
    /// is NOT `false`. See `BirdoShieldGate.row(preference:available:)`.
    let dnsFilteringAvailable: Bool?
}

enum BirdoShieldGate {
    /// Everything the BirdoShield row renders, derived in ONE place.
    ///
    /// The switch position, whether the row responds to a tap, and which
    /// subtitle it shows all have to agree about whether the feature can do
    /// anything. Deriving them separately at three call sites is how a row ends
    /// up reading ON while greyed out.
    struct Row: Equatable {
        let isOn: Bool
        let isEnabled: Bool
        /// True when the row should show the "not available yet" reason instead
        /// of the normal description.
        let unavailable: Bool
    }

    /// - Parameters:
    ///   - preference: the user's persisted per-device opt-in
    ///     (`UserDefaults` key `ConnectWire.dnsFilteringDefaultsKey`).
    ///   - available: the fleet gate — `true` on, `false` off, `nil` NOT KNOWN
    ///     YET (cold start before the fetch lands, an unreachable web app, or a
    ///     deploy older than birdo-web#465).
    ///
    /// `available == false` rather than `available != true` is the whole point:
    /// UNKNOWN COUNTS AS AVAILABLE. The two failure modes are not symmetric —
    /// a wrongly-off gate hides a feature that works and silently drops the
    /// filtering the user asked for, while a wrongly-available one costs a
    /// greyed-out row appearing a moment late, and the backend refuses the flag
    /// anyway while the gate is down. So only an explicit server "no" disables
    /// the row; a network error never does.
    ///
    /// Note what this does NOT do: it never writes. An unavailable gate makes
    /// the row read OFF, but `preference` is left untouched in UserDefaults, so
    /// the user's own choice comes back by itself when the gate does.
    static func row(preference: Bool, available: Bool?) -> Row {
        let unavailable = available == false
        return Row(
            isOn: preference && !unavailable,
            isEnabled: !unavailable,
            unavailable: unavailable
        )
    }

    /// The subtitle shown while the gate is down.
    ///
    /// The user cannot act on this, so the copy does not ask them to; it says
    /// the preference survives. Kept here beside the rule so the three clients'
    /// wording stays comparable in review (Android:
    /// `R.string.vpn_settings_birdoshield_unavailable`).
    static let unavailableReason =
        "Not available on your account's server fleet yet. "
        + "Your preference is kept and applies as soon as it is."
}
