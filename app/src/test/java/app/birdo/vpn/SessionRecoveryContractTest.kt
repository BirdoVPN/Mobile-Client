package app.birdo.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What happens when the app's process dies, and what may run before consent,
 * pinned on the REAL sources (the ApiLevel36ContractTest pattern): these are
 * wiring decisions in Application/Activity/Service code that no unit test can
 * instantiate.
 *
 * Live on API 35 (2026-09-30): after `am crash` or `kill -9` with the tunnel
 * up, Android never restarted the START_STICKY service, its foreground
 * notification kept saying "BirdoVPN — Protected" over an unprotected device,
 * and reopening the app showed "Not connected" with the user's intent
 * forgotten.
 */
class SessionRecoveryContractTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above " + File("").absolutePath)
        }
        dir
    }

    private fun read(relativePath: String, sentinel: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue("$relativePath does not exist", file.isFile)
        val text = file.readText().replace("\r\n", "\n")
        assertTrue("$relativePath lacks [$sentinel]; these scans would be vacuous", text.contains(sentinel))
        return text
    }

    private val app get() = read("app/src/main/java/app/birdo/vpn/BirdoApp.kt", "class BirdoApp")
    private val activity get() = read("app/src/main/java/app/birdo/vpn/MainActivity.kt", "class MainActivity")
    private val service get() =
        read("app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt", "class BirdoVpnService : VpnService()")

    @Test
    fun `every new process resumes a session the user wanted`() {
        val onCreate = app.substringAfter("override fun onCreate() {").substringBefore("\n    }\n")
        assertTrue("BirdoApp.onCreate no longer resumes a dead session", onCreate.contains("resumeSessionAfterProcessDeath()"))
        val resume = app.substringAfter("private fun resumeSessionAfterProcessDeath() {").substringBefore("\n    }\n")
        assertTrue(resume.contains("SystemStartPolicy.resumeOnProcessStart("))
        assertTrue(resume.contains("BirdoVpnService.ACTION_RESUME_SESSION"))
        // A refused start never leaves the dead service's "Protected" up unanswered.
        assertTrue(resume.contains("retractStaleStatus()"))
        assertTrue(resume.contains("stoppedUnexpectedlyAlert()"))
        assertTrue(
            "the service no longer classifies the resume as a system start",
            service.contains("ACTION_RESUME_SESSION -> SystemStartKind.PROCESS_RESTART"),
        )
    }

    @Test
    fun `the service is not sticky`() {
        assertTrue(service.contains("private const val RESTART_POLICY = START_NOT_STICKY"))
        assertFalse(
            "a command returns START_STICKY again: on API 35 it bought no restart and kept a stale " +
                "\"Protected\" notification over a dead tunnel",
            Regex("""return\s+START_STICKY""").containsMatchIn(service),
        )
    }

    @Test
    fun `nothing reconciles Play purchases before the current consent`() {
        val warmUp = app.substringAfter("Thread({").substringBefore("}, \"birdo-startup\")")
        assertTrue(
            "BirdoApp starts the Play rail without the consent check (A2-028's residual)",
            warmUp.contains("BuildConfig.IS_PLAY_BUILD && appPreferences.hasAcceptedCurrentConsent"),
        )
        val onResume = activity.substringAfter("override fun onResume() {").substringBefore("\n    }\n")
        assertTrue(
            "MainActivity reconciles Play on resume before consent (REVIEW-AND-010)",
            onResume.contains("BuildConfig.IS_PLAY_BUILD && appPreferences.hasAcceptedCurrentConsent"),
        )
        assertTrue(activity.contains("startPlayRailOnceConsented()"))
    }

    @Test
    fun `the tile and the widget pass the consent to the one-tap decision`() {
        val tile = read("app/src/main/java/app/birdo/vpn/service/BirdoTileService.kt", "class BirdoTileService")
        val widget = read("app/src/main/java/app/birdo/vpn/widget/BirdoWidget.kt", "class WidgetToggleAction")
        listOf(tile, widget).forEach { source ->
            assertTrue(source.contains("consentAccepted = ") && source.contains("hasAcceptedCurrentConsent"))
        }
    }
}
