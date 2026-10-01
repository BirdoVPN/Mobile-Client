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
/// `third_party/` and enforced from there), the rest of the per-plan feature
/// entitlements (which arrive with the subscription) and consent copy.
/// Modelling them here would create a second source of truth for each. Of
/// `features`, only `customDns` is read (owner item 40). Unknown keys are
/// ignored, so web-side additions never break a shipped client.
struct ClientConfigResponse: Decodable, Sendable {
    /// Is DNS filtering switched on for the fleet this account dials?
    ///
    /// `nil` (key absent — a web deploy older than #465) means UNKNOWN, which
    /// is NOT `false`. See `BirdoShieldGate.row(preference:available:)`.
    let dnsFilteringAvailable: Bool?
    /// Owner item 40: `features.<PLAN>.customDns`, keyed by UPPERCASED plan
    /// id. birdo-web serves `features` as one object per plan (RECON /
    /// OPERATIVE / SOVEREIGN). Empty when absent. See `CustomDnsGate`.
    let customDnsByPlan: [String: Bool]
    /// A flat `features.customDns`, should the payload ever carry one. It
    /// applies to every plan and wins over the per-plan map.
    let customDnsForAllPlans: Bool?

    private enum CodingKeys: String, CodingKey {
        case dnsFilteringAvailable, features
    }

    private struct FeatureKey: CodingKey {
        let stringValue: String
        init?(stringValue: String) { self.stringValue = stringValue }
        var intValue: Int? { nil }
        init?(intValue: Int) { return nil }
    }

    private struct PlanFeatures: Decodable {
        let customDns: Bool?
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        dnsFilteringAvailable = try c.decodeIfPresent(Bool.self, forKey: .dnsFilteringAvailable)
        // `features` is read field by field under `try?`: a shape this build
        // does not expect must cost only the custom-DNS flag (which then
        // defaults to available), never the BirdoShield gate above.
        var byPlan: [String: Bool] = [:]
        var flat: Bool?
        if let features = try? c.nestedContainer(keyedBy: FeatureKey.self, forKey: .features) {
            for key in features.allKeys {
                if key.stringValue == "customDns" {
                    flat = try? features.decode(Bool.self, forKey: key)
                } else if let plan = try? features.decode(PlanFeatures.self, forKey: key),
                          let value = plan.customDns {
                    byPlan[key.stringValue.uppercased()] = value
                }
            }
        }
        customDnsByPlan = byPlan
        customDnsForAllPlans = flat
    }
}

/// Custom DNS servers — may this account's plan use the setting?
///
/// Owner item 40 (2026-10-01): Custom DNS is available on EVERY plan. It used
/// to be locked to SOVEREIGN in the app itself (the row rendered as an upsell
/// for everyone else); that client-side gate is gone. What remains is the
/// server's say: if `/api/client-config` carries a `customDns` flag for the
/// plan, it is honoured — the backend now sends `true` for every plan — and
/// when it says nothing (no config yet, an unreachable web app, an older
/// deploy, a guest before consent) the setting is AVAILABLE.
enum CustomDnsGate {
    /// - Parameters:
    ///   - plan: the plan id ("RECON" | "OPERATIVE" | "SOVEREIGN"), any case.
    ///   - config: the last decoded client config, or nil if none yet.
    static func isAvailable(plan: String, config: ClientConfigResponse?) -> Bool {
        guard let config else { return true }
        if let all = config.customDnsForAllPlans { return all }
        return config.customDnsByPlan[plan.uppercased()] ?? true
    }
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
