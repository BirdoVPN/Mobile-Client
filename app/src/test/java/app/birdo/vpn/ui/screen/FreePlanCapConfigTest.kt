package app.birdo.vpn.ui.screen

import app.birdo.vpn.data.model.ClientConfigResponse
import app.birdo.vpn.data.model.SubscriptionStatus
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.di.NetworkModule
import app.birdo.vpn.ui.viewmodel.nextDnsFilteringAvailable
import app.birdo.vpn.ui.viewmodel.nextFreePlanCapGb
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-1980 wiring: birdo-web#708 publishes the plans on `GET /api/client-config`
 * as `plans.<RECON|OPERATIVE|SOVEREIGN>` = `{ monthlyPrice, yearlyPrice,
 * currency, bandwidthLimitGb }`. The paywall's Recon card states
 * `plans.RECON.bandwidthLimitGb` first, then the account's own `/vpn/stats`
 * limit, then 50 GB. A server older than #708 has no `plans`, and no shape of
 * it may cost the rest of the config (the BirdoShield gate rides on the same
 * response). The prices are never read: Google Play sells at its own.
 */
class FreePlanCapConfigTest {

    /** THE wire serializer, as in BirdoShieldAvailabilityTest. */
    private val json: Json = NetworkModule.json

    private fun config(body: String): ClientConfigResponse = json.decodeFromString(body)

    private val published = """
        {
          "dnsFilteringAvailable": true,
          "plans": {
            "RECON":     { "monthlyPrice": 0, "yearlyPrice": 0,  "currency": "EUR", "bandwidthLimitGb": 75 },
            "OPERATIVE": { "monthlyPrice": 1, "yearlyPrice": 10, "currency": "EUR", "bandwidthLimitGb": null },
            "SOVEREIGN": { "monthlyPrice": 2, "yearlyPrice": 20, "currency": "EUR", "bandwidthLimitGb": null }
          }
        }
    """.trimIndent()

    @Test
    fun `the published Recon cap is read from plans and comes first`() {
        val cfg = config(published)
        assertEquals(75L, cfg.freePlanCapGb)
        assertEquals(true, cfg.dnsFilteringAvailable)
        // Ahead of a Recon account's own limit, and of the fallback.
        assertEquals(75L, freePlanCapGb(SubscriptionStatus(plan = "RECON", bandwidthLimitGb = 50), cfg.freePlanCapGb))
        assertEquals(75L, freePlanCapGb(null, cfg.freePlanCapGb))
        // The key's case is the server's business.
        assertEquals(60L, config("""{"plans":{"recon":{"bandwidthLimitGb":60}}}""").freePlanCapGb)
    }

    @Test
    fun `an older server without plans leaves the account's limit, then 50 GB`() {
        val older = config("""{"dnsFilteringAvailable":false}""")
        assertNull(older.freePlanCapGb)
        assertEquals(false, older.dnsFilteringAvailable)
        assertEquals(40L, freePlanCapGb(SubscriptionStatus(plan = "RECON", bandwidthLimitGb = 40), older.freePlanCapGb))
        assertEquals(50L, freePlanCapGb(SubscriptionStatus(plan = "SOVEREIGN", bandwidthLimitGb = 0), older.freePlanCapGb))
        assertEquals(50L, freePlanCapGb(null, older.freePlanCapGb))
        assertNull(config("""{"plans":null}""").freePlanCapGb)
        assertNull(config("""{"plans":{"OPERATIVE":{"bandwidthLimitGb":null}}}""").freePlanCapGb)
    }

    @Test
    fun `a malformed plans block costs only the cap, never the decode`() {
        listOf(
            """"soon"""",
            """[1, 2]""",
            """{"RECON": 50}""",
            """{"RECON": "50"}""",
            """{"RECON": {"bandwidthLimitGb": "50"}}""",
            """{"RECON": {"bandwidthLimitGb": null}}""",
            """{"RECON": {"bandwidthLimitGb": true}}""",
            """{"RECON": {"bandwidthLimitGb": {"value": 50}}}""",
            """{"RECON": {"bandwidthLimitGb": 0}}""",
            """{"RECON": {"bandwidthLimitGb": -5}}""",
            """{"RECON": {"bandwidthLimitGb": 12.5}}""",
            """{"RECON": {"bandwidthLimitGb": 1e300}}""",
            """{"RECON": {"bandwidthLimitGb": 100001}}""",
            """{"RECON": {"bandwidthLimitGb": -1}}""",
            """{"RECON": {}}""",
        ).forEach { plans ->
            val cfg = config("""{"dnsFilteringAvailable":false,"plans":$plans}""")
            assertNull("plans = $plans must yield no cap", cfg.freePlanCapGb)
            assertEquals("plans = $plans must not cost the BirdoShield gate", false, cfg.dnsFilteringAvailable)
            assertEquals(
                "plans = $plans: the paywall falls back to the account's own limit",
                40L,
                freePlanCapGb(SubscriptionStatus(plan = "RECON", bandwidthLimitGb = 40), cfg.freePlanCapGb),
            )
        }
    }

    /** #487 review: the same bounds as iOS's `FreePlanCap.usable`, on both inputs. */
    @Test
    fun `only a whole number of GB from 1 to 100,000 is stated`() {
        assertEquals(100_000L, ClientConfigResponse.MAX_FREE_PLAN_CAP_GB)
        assertEquals(100_000L, config("""{"plans":{"RECON":{"bandwidthLimitGb":100000}}}""").freePlanCapGb)
        assertEquals(1L, config("""{"plans":{"RECON":{"bandwidthLimitGb":1}}}""").freePlanCapGb)
        listOf(0L, -1L, 100_001L, Long.MAX_VALUE).forEach { bad ->
            assertEquals("config $bad", 40L, freePlanCapGb(SubscriptionStatus(plan = "RECON", bandwidthLimitGb = 40), bad))
            assertEquals("stats $bad", 50L, freePlanCapGb(SubscriptionStatus(plan = "RECON", bandwidthLimitGb = bad)))
        }
    }

    @Test
    fun `a failed fetch keeps the cap, a successful one replaces it`() {
        assertNull(nextFreePlanCapGb(null, ApiResult.Error("Network error")))
        assertEquals(75L, nextFreePlanCapGb(75L, ApiResult.Error("server error", 500)))
        assertEquals(75L, nextFreePlanCapGb(null, ApiResult.Success(config(published))))
        // The latest answer is the server's word: a server without plans
        // hands the card back to the account's limit and the fallback.
        assertNull(nextFreePlanCapGb(75L, ApiResult.Success(config("""{"dnsFilteringAvailable":true}"""))))
        // And the gate the same response carries is unaffected by plans.
        assertEquals(true, nextDnsFilteringAvailable(null, ApiResult.Success(config(published))))
    }

    @Test
    fun `no plan price is modelled`() {
        val fields = ClientConfigResponse::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(
            "the web's card prices must not reach the app: $fields",
            fields.any { "price" in it || "currency" in it },
        )
    }

    // ── The wiring: the published cap reaches the Recon card ────────────

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun source(path: String): String {
        val file = File(repoRoot, path)
        assertTrue("scan target is missing: $path — this test would be vacuous", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    @Test
    fun `the paywall is handed the published cap and states it first`() {
        val screen = source("app/src/main/java/app/birdo/vpn/ui/screen/SubscriptionScreen.kt")
        assertTrue(
            "the Recon card must be resolved with the config cap",
            "val freeCapGb = freePlanCapGb(currentSubscription, configFreeCapGb)" in screen,
        )
        val route = source("app/src/main/java/app/birdo/vpn/ui/navigation/BirdoNavGraph.kt")
            .substringAfter("Screen.Subscription.route,").substringBefore("composable(")
        assertTrue(
            "the Subscription route must pass the fetched cap to the screen",
            "configFreeCapGb = vpnState.configFreePlanCapGb" in route,
        )
        assertTrue(
            "the Subscription route must refresh the config, after consent only",
            "if (appPreferences.hasAcceptedCurrentConsent) vpnViewModel.fetchClientConfig()" in route,
        )
        val vm = source("app/src/main/java/app/birdo/vpn/ui/viewmodel/VpnViewModel.kt")
            .substringAfter("fun fetchClientConfig() {").substringBefore("\n    }\n")
        assertTrue("fetchClientConfig must keep the cap", "nextFreePlanCapGb(" in vm && "configFreePlanCapGb = freeCap" in vm)
    }
}
