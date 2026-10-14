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
/// Foundation-only on purpose: compiled into the BirdoVPNTests bundle and
/// pinned by FreePlanCapTests.
enum FreePlanCap {
    /// The owner's 2026-10-13 cap. Shown only when no server value exists.
    static let fallbackGb: Double = 50

    static func gb(configGb: Double? = nil, plan: String?, bandwidthLimitGb: Double?) -> Double {
        if let configGb, configGb.isFinite, configGb > 0 { return configGb }
        if let plan, plan.caseInsensitiveCompare("RECON") == .orderedSame,
           let limit = bandwidthLimitGb, limit.isFinite, limit > 0 {
            return limit
        }
        return fallbackGb
    }

    /// The Recon card's feature line.
    static func featureLine(gb: Double) -> String {
        "\(format(gb)) GB monthly bandwidth"
    }

    /// "50" for whole numbers, one decimal otherwise — never "50.0".
    static func format(_ gb: Double) -> String {
        gb.truncatingRemainder(dividingBy: 1) == 0
            ? String(Int(gb))
            : String(format: "%.1f", gb)
    }
}
