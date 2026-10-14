import Foundation

/// MR-1980 — the free (Recon) plan's monthly data allowance, as the paywall
/// states it.
///
/// The Recon card used to hard-code its allowance; the owner set the free cap
/// to 50 GB (2026-10-13), and a number baked into the build cannot follow the
/// server: it would keep selling an old cap until every user updated. So the
/// card states what the SERVER says, and the 50 GB literal is only a fallback
/// for the moment it knows nothing:
///
///   1. the free cap the server publishes for every client (`configGb`):
///      `/api/client-config` `plans.RECON.bandwidthLimitGb` (birdo-web#708,
///      `ClientConfigResponse.freePlanCapGb`), when there is one;
///   2. the signed-in account's own `/vpn/stats` limit, when that account is
///      on Recon — a Recon account's `bandwidthLimitGb` IS the free cap;
///   3. otherwise `fallbackGb` (a paid account's limit is 0 = unlimited and
///      says nothing about the free plan; a guest has no stats at all).
///
/// A server value counts only when it is `usable`: a whole number of GB from 1
/// to `maxGb`, as on Android (`ClientConfigResponse.freePlanCapGb`,
/// `freePlanCapGb`). Anything else — 12.5, 0, -1, NaN, 1e300 — falls through
/// to the next source. 1e300 used to reach `String(Int(gb))`, which traps.
///
/// Foundation-only on purpose: compiled into the BirdoVPNTests bundle and
/// pinned by FreePlanCapTests.
enum FreePlanCap {
    /// The owner's 2026-10-13 cap. Shown only when no server value exists.
    static let fallbackGb: Double = 50

    /// The largest cap the card will state. Android's twin is
    /// `ClientConfigResponse.MAX_FREE_PLAN_CAP_GB`.
    static let maxGb: Double = 100_000

    /// `gb` when it is a whole number of GB in 1...`maxGb`, else nil.
    static func usable(_ gb: Double?) -> Double? {
        guard let gb, gb.isFinite, gb >= 1, gb <= maxGb, gb.rounded(.towardZero) == gb else { return nil }
        return gb
    }

    static func gb(configGb: Double? = nil, plan: String?, bandwidthLimitGb: Double?) -> Double {
        if let configGb = usable(configGb) { return configGb }
        if let plan, plan.caseInsensitiveCompare("RECON") == .orderedSame,
           let limit = usable(bandwidthLimitGb) {
            return limit
        }
        return fallbackGb
    }

    /// The Recon card's feature line.
    static func featureLine(gb: Double) -> String {
        "\(format(gb)) GB monthly bandwidth"
    }

    /// "50" for whole numbers, one decimal otherwise — never "50.0". Never
    /// traps: `Int(_:)` is only reached for values it can hold.
    static func format(_ gb: Double) -> String {
        guard gb.isFinite, gb.magnitude < 1e15 else { return String(format: "%.0f", gb) }
        return gb.truncatingRemainder(dividingBy: 1) == 0
            ? String(Int(gb))
            : String(format: "%.1f", gb)
    }
}
