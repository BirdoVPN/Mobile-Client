package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Audit 2026-09-29, P1-6 / C-3 / D-12 — crash reporting is OPT-IN.
 *
 * Until 1.4.31 `BirdoApp.onCreate` called `initSentry()` unconditionally, with
 * release-health sessions on, so every release install reported to Sentry from
 * its first frame — before the consent screen was drawn and with no way to turn
 * it off. The fix is a handful of wiring decisions spread across five files,
 * any one of which can quietly undo the others; `CrashReportingTest` covers
 * the decisions themselves, this pins the wiring.
 */
class CrashReportingOptInWiringTest {

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
        val text = file.readText()
        assertTrue("scan target $path is suspiciously small", text.length > 200)
        return text
    }

    private val app by lazy { source("app/src/main/java/app/birdo/vpn/BirdoApp.kt") }

    /** Body of the first `fun <name>(` in [text], braces excluded. */
    private fun functionBody(text: String, name: String): String {
        val at = text.indexOf("fun $name(")
        assertTrue("fun $name( not found", at >= 0)
        val open = text.indexOf('{', at)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open + 1, i)
            }
        }
        error("unbalanced braces in $name")
    }

    @Test
    fun `onCreate never starts Sentry directly`() {
        val onCreate = functionBody(app, "onCreate")
        assertFalse(
            "BirdoApp.onCreate calls initSentry() directly again — that is the pre-consent " +
                "reporting the audit found. Go through applyCrashReportingConsent().",
            onCreate.contains("initSentry("),
        )
        assertTrue(
            "BirdoApp.onCreate no longer applies the stored crash-report choice",
            onCreate.contains("applyCrashReportingConsent()"),
        )
    }

    @Test
    fun `the SDK is started only behind the opt-in gate`() {
        val gate = functionBody(app, "applyCrashReportingConsent")
        assertTrue(
            "applyCrashReportingConsent must decide through CrashReporting.shouldStart " +
                "with the user's stored choice",
            gate.contains("CrashReporting.shouldStart(") && gate.contains("crashReportsEnabled"),
        )
        assertTrue("the only initSentry call must be inside the gate", gate.contains("initSentry("))
        assertEquals(
            "initSentry is called from somewhere other than the consent gate",
            1,
            Regex("""\binitSentry\(appPreferences""").findAll(app).count(),
        )
        assertTrue(
            "turning crash reports off must close the SDK, not merely stop starting it",
            gate.contains("Sentry.close()"),
        )
        assertTrue(
            "turning crash reports off must discard the unsent queue",
            gate.contains("CrashReporting.discardUnsentReports("),
        )
    }

    /**
     * Second-pass #18: Sentry.close() flushes and can block up to the SDK's
     * shutdown timeout. From the Settings toggle that ran on the main thread.
     */
    @Test
    fun `the SDK is closed off the calling thread`() {
        val gate = functionBody(app, "applyCrashReportingConsent")
        val onWorker = gate.substringAfter("crashReportingWorker.submit(", missingDelimiterValue = "")
        assertTrue(
            "Sentry.close() must run on crashReportingWorker, not the caller's (main) thread",
            onWorker.contains("Sentry.close()"),
        )
        assertFalse(gate.substringBefore("crashReportingWorker.submit(").contains("Sentry.close()"))
        assertTrue(
            "an init must wait for a close still in flight, or a quick off-on flip ends with the SDK off",
            gate.contains("awaitPendingCrashReportingShutdown()"),
        )
    }

    @Test
    fun `release-health sessions stay off`() {
        assertFalse(
            "isEnableAutoSessionTracking = true is back — a session envelope is a per-install " +
                "id on every app start, which the consent screen does not describe",
            app.contains("isEnableAutoSessionTracking = true"),
        )
    }

    @Test
    fun `the auto-init providers stay removed from the manifest`() {
        // SentryInitProvider would initialise the SDK from manifest meta-data
        // BEFORE Application.onCreate — i.e. before any consent check could run.
        val manifest = source("app/src/main/AndroidManifest.xml")
        listOf("SentryInitProvider", "SentryPerformanceProvider").forEach { provider ->
            val decl = Regex(
                """android:name="io\.sentry\.android\.core\.$provider"[^>]*tools:node="remove"""",
            )
            assertTrue("$provider is no longer removed from the merged manifest", decl.containsMatchIn(manifest))
        }
    }

    @Test
    fun `the consent screen offers the choice unticked and passes it on`() {
        val consent = source("app/src/main/java/app/birdo/vpn/ui/screen/ConsentScreen.kt")
        assertTrue(
            "the consent screen's crash-report switch must start OFF",
            Regex("""var crashReports by rememberSaveable \{ mutableStateOf\(false\) \}""")
                .containsMatchIn(consent),
        )
        assertTrue("accepting must hand the crash-report choice to the caller", consent.contains("onAccept(crashReports)"))

        val graph = source("app/src/main/java/app/birdo/vpn/ui/navigation/BirdoNavGraph.kt")
        assertTrue(
            "the nav graph must persist and apply the consent-screen choice",
            graph.contains("settingsViewModel.setCrashReports(crashReportsEnabled)"),
        )
        assertTrue(
            "the Settings toggle is not wired",
            graph.contains("onCrashReportsChange = { settingsViewModel.setCrashReports(it) }"),
        )
    }

    @Test
    fun `the Settings toggle applies the change immediately`() {
        val vm = source("app/src/main/java/app/birdo/vpn/ui/viewmodel/SettingsViewModel.kt")
        val setter = functionBody(vm, "setCrashReports")
        assertTrue(setter.contains("prefs.crashReportsEnabled = enabled"))
        assertTrue(
            "flipping the Settings toggle must start/close the SDK now, not at the next launch",
            setter.contains("applyCrashReportingConsent()"),
        )
    }
}
