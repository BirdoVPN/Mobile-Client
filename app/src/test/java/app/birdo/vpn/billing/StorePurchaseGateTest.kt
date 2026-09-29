package app.birdo.vpn.billing

import app.birdo.vpn.billing.StorePurchaseGate.SOURCE_APP_STORE
import app.birdo.vpn.billing.StorePurchaseGate.SOURCE_FREE_FLOOR
import app.birdo.vpn.billing.StorePurchaseGate.SOURCE_GOOGLE_PLAY
import app.birdo.vpn.billing.StorePurchaseGate.SOURCE_WEB
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Audit 2026-09-29, A-9 / A-16: the Play paywall offered "Change plan" to an
 * account already paying on the web or the App Store, and Play's replacement
 * flow — which only knows Play purchases — started a SECOND subscription. The
 * rule is now: a paid plan is managed where it was bought; Play sells only to
 * an account that is free here, or changes a plan Play itself sold.
 *
 * The iOS twin (StoreCatalog.swift) is pinned by StoreCatalogTests.swift.
 */
class StorePurchaseGateTest {

    private fun paidElsewhere(
        plan: String?,
        source: String? = null,
        liveSources: List<String>? = null,
        owns: Boolean = false,
    ) = StorePurchaseGate.paidElsewhere(plan, source, liveSources, SOURCE_GOOGLE_PLAY, owns)

    @Test
    fun `a free or unknown account may always buy`() {
        assertFalse(paidElsewhere(plan = "RECON"))
        assertFalse(paidElsewhere(plan = "recon"))
        assertFalse(paidElsewhere(plan = null))
        assertFalse(paidElsewhere(plan = ""))
        // Even with a stale source on a free plan.
        assertFalse(paidElsewhere(plan = "RECON", source = SOURCE_WEB))
    }

    @Test
    fun `server says web or App Store - no Play purchase`() {
        assertTrue(paidElsewhere(plan = "SOVEREIGN", source = SOURCE_WEB))
        assertTrue(paidElsewhere(plan = "OPERATIVE", source = SOURCE_APP_STORE))
        // Even if this Google account happens to own a Birdo product: the
        // server's answer about THIS Birdo account wins.
        assertTrue(paidElsewhere(plan = "OPERATIVE", source = SOURCE_WEB, owns = true))
    }

    @Test
    fun `server says Play - Play may change the plan`() {
        assertFalse(paidElsewhere(plan = "OPERATIVE", source = SOURCE_GOOGLE_PLAY))
        assertFalse(paidElsewhere(plan = "OPERATIVE", source = "google_play"))
    }

    @Test
    fun `any live rail other than Play blocks, even alongside Play`() {
        assertTrue(paidElsewhere(plan = "SOVEREIGN", liveSources = listOf(SOURCE_GOOGLE_PLAY, SOURCE_WEB)))
        assertFalse(paidElsewhere(plan = "SOVEREIGN", liveSources = listOf(SOURCE_GOOGLE_PLAY, SOURCE_FREE_FLOOR)))
        // liveSources outranks a single winning source.
        assertTrue(
            paidElsewhere(plan = "SOVEREIGN", source = SOURCE_GOOGLE_PLAY, liveSources = listOf(SOURCE_APP_STORE)),
        )
    }

    @Test
    fun `without server sources, a paid plan Play does not own was bought elsewhere`() {
        // Today's /vpn/stats: no source fields. A voucher, web or App Store
        // plan is invisible to Play, so it must not be sold again here.
        assertTrue(paidElsewhere(plan = "OPERATIVE", owns = false))
        // This Google account owns the subscription: a real Play cross-grade.
        assertFalse(paidElsewhere(plan = "OPERATIVE", owns = true))
    }

    @Test
    fun `the free floor is not a rail`() {
        assertTrue(paidElsewhere(plan = "OPERATIVE", source = SOURCE_FREE_FLOOR, owns = false))
        assertFalse(paidElsewhere(plan = "OPERATIVE", source = SOURCE_FREE_FLOOR, owns = true))
    }

    @Test
    fun `the Play paywall and the purchase entry point both use the gate`() {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile!!
        val graph = File(dir, "app/src/main/java/app/birdo/vpn/ui/navigation/BirdoNavGraph.kt").readText()
        assertTrue(
            "the gate must be checked where a Play purchase starts, not only where the button is drawn",
            Regex("""if \(offer != null && activity != null && !paidElsewhere\)""").containsMatchIn(graph),
        )
        assertTrue(graph.contains("purchaseManagedElsewhere = purchaseManagedElsewhere"))
        val screen = File(dir, "app/src/main/java/app/birdo/vpn/ui/screen/SubscriptionScreen.kt").readText()
        assertTrue(screen.contains("!purchaseManagedElsewhere"))
    }
}
