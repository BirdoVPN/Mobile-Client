package app.birdo.vpn.data.model

import app.birdo.vpn.di.NetworkModule
import app.birdo.vpn.shared.model.PlanFeature
import app.birdo.vpn.shared.model.PlanFeature.MULTI_HOP
import app.birdo.vpn.shared.model.PlanFeature.PORT_FORWARDING
import app.birdo.vpn.shared.model.PlanFeature.STEALTH_MODE
import app.birdo.vpn.shared.model.planHasFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MR-1517: Android gates Stealth, Multi-Hop and Port Forwarding on the
 * server's plan table, `GET /api/client-config` → `features.<PLAN>`, and on
 * the owner's 2026-10-07 table wherever the server said nothing. Decoded by
 * THE wire serializer, so what is pinned here is what the app reads.
 */
class PlanGatingTest {

    private fun config(body: String): ClientConfigResponse = NetworkModule.json.decodeFromString(body)

    /** The per-plan map as birdo-web app/api/client-config/route.ts serves it (trimmed). */
    private val served = """
        {
          "dnsFilteringAvailable": true,
          "features": {
            "RECON": { "maxDevices": 1, "bandwidth": "50GB", "splitTunneling": true, "stealthMode": false,
                       "multiHop": false, "portForwarding": false, "customDns": true },
            "OPERATIVE": { "maxDevices": 5, "bandwidth": "unlimited", "splitTunneling": true, "stealthMode": true,
                           "multiHop": false, "portForwarding": false, "customDns": true },
            "SOVEREIGN": { "maxDevices": 10, "bandwidth": "unlimited", "splitTunneling": true, "stealthMode": true,
                           "multiHop": true, "portForwarding": true, "customDns": true }
          },
          "plans": { "RECON": { "bandwidthLimitGb": 50 } }
        }
    """.trimIndent()

    private val decided = mapOf(
        "RECON" to mapOf(STEALTH_MODE to false, MULTI_HOP to false, PORT_FORWARDING to false),
        "OPERATIVE" to mapOf(STEALTH_MODE to true, MULTI_HOP to false, PORT_FORWARDING to false),
        "SOVEREIGN" to mapOf(STEALTH_MODE to true, MULTI_HOP to true, PORT_FORWARDING to true),
    )

    @Test
    fun `the served table decodes to the decided one, beside the fields already read`() {
        val cfg = config(served)
        assertEquals(decided, cfg.planGates)
        assertEquals(true, cfg.dnsFilteringAvailable)
        assertEquals(50L, cfg.freePlanCapGb)
    }

    @Test
    fun `with no table the built-in one is the decided one`() {
        decided.forEach { (plan, features) ->
            features.forEach { (feature, expected) ->
                assertEquals("$plan $feature", expected, planHasFeature(plan, null, feature))
                assertEquals("${plan.lowercase()} $feature", expected, planHasFeature(plan.lowercase(), null, feature))
                assertEquals("$plan $feature, served", expected, planHasFeature(plan, config(served).planGates, feature))
            }
        }
        PlanFeature.entries.forEach { feature ->
            assertEquals(decided.entries.first { it.value.getValue(feature) }.key, feature.minPlan)
        }
    }

    @Test
    fun `the server's flag wins wherever it gave one`() {
        val gates = config(
            """{"features":{"operative":{"multiHop":true,"portForwarding":true},"SOVEREIGN":{"multiHop":false},
               "RECON":{"stealthMode":true}}}""",
        ).planGates
        assertEquals(true, planHasFeature("OPERATIVE", gates, MULTI_HOP))
        assertEquals(true, planHasFeature("Operative", gates, PORT_FORWARDING))
        assertEquals(false, planHasFeature("SOVEREIGN", gates, MULTI_HOP))
        assertEquals(true, planHasFeature("RECON", gates, STEALTH_MODE))
        // What it left out keeps the built-in table.
        assertEquals(true, planHasFeature("SOVEREIGN", gates, PORT_FORWARDING))
        assertEquals(false, planHasFeature("RECON", gates, MULTI_HOP))
        assertEquals(true, planHasFeature("OPERATIVE", gates, STEALTH_MODE))
    }

    @Test
    fun `an unknown plan has no answer, and an unknown slug reads only its own flag`() {
        val gates = config("""{"features":{"ELITE":{"multiHop":true},"RECON":{"portForwarding":true}}}""").planGates
        PlanFeature.entries.forEach { assertNull(planHasFeature(null, gates, it)) }
        assertEquals(true, planHasFeature("ELITE", gates, MULTI_HOP))
        assertEquals(false, planHasFeature("ELITE", gates, PORT_FORWARDING))
        // Never folded into RECON and handed RECON's flag.
        assertEquals(false, planHasFeature("TRIAL", gates, PORT_FORWARDING))
    }

    @Test
    fun `an odd flag costs only that flag, never the rest of the config`() {
        val cfg = config(
            """{"dnsFilteringAvailable":false,"plans":{"RECON":{"bandwidthLimitGb":40}},"features":{
               "RECON":{"multiHop":"true","stealthMode":null,"portForwarding":1,"customDns":false},
               "OPERATIVE":["multiHop"],
               "SOVEREIGN":{"multiHop":false,"portForwarding":"no"},
               "customDns":true}}""",
        )
        assertEquals(false, cfg.dnsFilteringAvailable)
        assertEquals(40L, cfg.freePlanCapGb)
        assertEquals(mapOf("SOVEREIGN" to mapOf(MULTI_HOP to false)), cfg.planGates)
        assertEquals(false, planHasFeature("RECON", cfg.planGates, MULTI_HOP))
        assertEquals(false, planHasFeature("RECON", cfg.planGates, PORT_FORWARDING))
        assertEquals(true, planHasFeature("OPERATIVE", cfg.planGates, STEALTH_MODE))
        assertEquals(true, planHasFeature("SOVEREIGN", cfg.planGates, PORT_FORWARDING))
    }

    @Test
    fun `no features object means no table, so a caller keeps the one it had`() {
        listOf("""{"dnsFilteringAvailable":true}""", """{"features":null}""", """{"features":["SOVEREIGN"]}""", """{"features":7}""")
            .forEach { assertNull(it, config(it).planGates) }
        // An object with no gating flag at all is an empty table, not none.
        assertEquals(emptyMap<String, Any>(), config("""{"features":{"RECON":{"customDns":true}}}""").planGates)
    }
}
