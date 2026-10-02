package app.birdo.vpn.ui

import app.birdo.vpn.billing.RESUME_RECONCILE_INTERVAL_MS
import app.birdo.vpn.billing.resumeReconcileDue
import app.birdo.vpn.ui.components.decorativeMotionAllowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Performance fixes: nothing decorative animates unseen (A2-019, A2-041), cold-start work
 * stays off the main thread (A2-021), and the Play rail runs where it must (A2-034, A2-035).
 */
class PerformanceGuardTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun source(path: String): String =
        File(repoRoot, path).also { assertTrue("missing: $path", it.isFile) }.readText()

    private val main = "app/src/main/java/app/birdo/vpn"

    // ── A2-019 / P1-041 ──────────────────────────────────────────────────

    @Test
    fun `decorative motion runs only while visible, uncovered and with animations on`() {
        assertTrue(decorativeMotionAllowed(started = true, animatorsEnabled = true, obscured = false))
        assertFalse(decorativeMotionAllowed(started = false, animatorsEnabled = true, obscured = false))
        assertFalse(decorativeMotionAllowed(started = true, animatorsEnabled = false, obscured = false))
        assertFalse(decorativeMotionAllowed(started = true, animatorsEnabled = true, obscured = true))
    }

    @Test
    fun `the pixel background and the globe share the motion gate`() {
        assertTrue(source("$main/ui/components/PixelCanvas.kt").contains("rememberDecorativeMotionAllowed()"))
        assertTrue(source("$main/ui/components/WorldGlobe.kt").contains("rememberDecorativeMotionAllowed()"))
    }

    // ── A2-041 ───────────────────────────────────────────────────────────

    @Test
    fun `the globe reads its clock only in the draw phase`() {
        val globe = source("$main/ui/components/WorldGlobe.kt")
        val canvas = globe.indexOf("Canvas(modifier = modifier.fillMaxSize()) {")
        assertTrue(canvas > 0)
        val composition = globe.substring(0, canvas)
        assertFalse(
            "a clock-derived value is computed in composition again: every tick re-runs WorldGlobe",
            composition.contains("cyclePhase(clockMs"),
        )
    }

    // ── A2-021 ───────────────────────────────────────────────────────────

    @Test
    fun `the Play rail and the token store are built off the main thread`() {
        val app = source("$main/BirdoApp.kt")
        val onCreate = app.substringAfter("override fun onCreate()").substringBefore("fun applyCrashReportingConsent()")
        val worker = onCreate.substringAfter("Thread({", missingDelimiterValue = "")
        assertTrue("no startup worker in onCreate", worker.isNotEmpty())
        assertTrue(worker.contains("playBilling.get().start()"))
        assertTrue(worker.contains("tokenManager.get()"))
        assertFalse(
            "playBilling.get() is on the main thread again",
            onCreate.substringBefore("Thread({").contains("playBilling.get()"),
        )
    }

    // ── A2-034 ───────────────────────────────────────────────────────────

    @Test
    fun `the billing flow is launched on the main thread`() {
        val billing = source("$main/billing/PlayBillingManager.kt")
        val launch = billing.indexOf("billingClient.launchBillingFlow(")
        val main = billing.lastIndexOf("withContext(Dispatchers.Main)", launch)
        assertTrue("launchBillingFlow must run inside withContext(Dispatchers.Main)", main in 0 until launch)
    }

    // ── A2-035 ───────────────────────────────────────────────────────────

    @Test
    fun `purchases are reconciled on resume, at most once a minute, never mid-purchase`() {
        assertTrue(resumeReconcileDue(nowMs = 1_000, lastMs = null, purchaseInFlight = false))
        assertFalse(resumeReconcileDue(nowMs = 1_000 + RESUME_RECONCILE_INTERVAL_MS - 1, lastMs = 1_000, purchaseInFlight = false))
        assertTrue(resumeReconcileDue(nowMs = 1_000 + RESUME_RECONCILE_INTERVAL_MS, lastMs = 1_000, purchaseInFlight = false))
        assertFalse(resumeReconcileDue(nowMs = 999_999, lastMs = null, purchaseInFlight = true))
    }
}
