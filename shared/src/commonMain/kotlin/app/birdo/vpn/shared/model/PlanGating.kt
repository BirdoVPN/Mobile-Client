package app.birdo.vpn.shared.model

/**
 * MR-1517: the features a plan gates, by the names `GET /api/client-config`
 * gives them under `features.<PLAN>`, and the lowest plan that has each one.
 *
 * [minPlan] is the table the owner decided on 2026-10-07 (Stealth from
 * OPERATIVE; Multi-Hop and Port Forwarding on SOVEREIGN), the same rules the
 * backend's `planAllows` enforces. The SERVER's table wins wherever it gave a
 * flag; this is what a plan or feature it said nothing about falls back to (a
 * cold process, an older deploy, no network, a failed fetch). Desktop's twin
 * is `FEATURE_MIN_PLAN` in src/lib/plan.ts, iOS's is `PlanFeature` in
 * BirdoShieldGate.swift.
 */
enum class PlanFeature(val key: String, val minPlan: String) {
    STEALTH_MODE("stealthMode", "OPERATIVE"),
    MULTI_HOP("multiHop", "SOVEREIGN"),
    PORT_FORWARDING("portForwarding", "SOVEREIGN"),
}

/**
 * The server's plan-gating flags, keyed by uppercased plan id, holding only the
 * booleans it actually gave ([ClientConfigResponse.planGates]).
 */
typealias PlanGates = Map<String, Map<PlanFeature, Boolean>>

private fun planRank(plan: String): Int = when (plan) {
    "SOVEREIGN" -> 2
    "OPERATIVE" -> 1
    else -> 0
}

/**
 * Whether [plan] has [feature]: the server's flag for that plan when it gave
 * one, else [PlanFeature.minPlan]. `null` while the plan is not known, so a
 * caller decides for itself what "not loaded yet" means rather than guessing.
 * A plan slug this build does not know reads only its own server flag, never
 * another plan's, and otherwise ranks as the free tier.
 *
 * The ONE gate for Stealth, Multi-Hop and Port Forwarding on Android: every
 * lock, upsell and headless dial asks this, so a change to the server's table
 * reaches all of them.
 */
fun planHasFeature(plan: String?, gates: PlanGates?, feature: PlanFeature): Boolean? {
    val id = plan?.trim()?.uppercase() ?: return null
    gates?.get(id)?.get(feature)?.let { return it }
    return planRank(id) >= planRank(feature.minPlan)
}
