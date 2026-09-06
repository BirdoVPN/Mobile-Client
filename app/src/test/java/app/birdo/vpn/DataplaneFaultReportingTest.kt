package app.birdo.vpn

import app.birdo.vpn.utils.FaultReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Regression pins for issue "every failure on the connect, kill-switch and
 * native-integrity paths is reported to nobody".
 *
 * The defect was not a wrong line of code — it was an ABSENCE. `Log.e` is
 * stripped from release builds by `-assumenosideeffects` in
 * app/proguard-rules.pro, and `Sentry` was called from exactly one place in the
 * whole app (TokenManager), so on the shipped artifact a wg-go failure or a
 * kill switch that refused to arm produced nothing at all: no crash, no log
 * line, no event. The operator's first signal was a user writing in.
 *
 * An absence cannot be caught by a behavioural test on the code that is
 * missing, so these are structural scans of the REAL shipped sources — the same
 * technique PrivacyBoundaryTest uses. They fail the build when a future change
 * puts a data-plane failure back on a channel that R8 deletes.
 */
class DataplaneFaultReportingTest {

    /** Repo root, found by walking up from the test working dir. */
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
        assertTrue("scan target $path is empty", text.length > 200)
        return text
    }

    /**
     * Kotlin source with comments removed, so a scan cannot be fooled — in
     * either direction — by prose that quotes the pattern it is looking for.
     */
    private fun strippedSource(path: String): String =
        source(path)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** Every shipped Kotlin file under app/src/main. */
    private fun shippedSources(): List<File> {
        val files = File(repoRoot, "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        // Vacuity guard: an empty walk would make the scans below pass for free.
        assertTrue(
            "source walk found only ${files.size} files — the walker is broken",
            files.size >= 50,
        )
        return files
    }

    @Before
    fun clearThrottle() = FaultReporter.resetThrottleForTest()

    // ── The three paths the finding named ────────────────────────────────

    /**
     * WgNative is the JNI bridge. Every function in it turns a failure into a
     * sentinel return so the service degrades instead of crashing — which is
     * exactly why a swallowed exception there is invisible unless it is
     * reported. In THIS file an ignored exception is a blind spot by
     * construction, so anonymous catches are banned outright.
     */
    @Test
    fun `WgNative reports every exception it swallows`() {
        val path = "app/src/main/java/app/birdo/vpn/service/WgNative.kt"
        val text = strippedSource(path)

        assertFalse(
            "$path has an anonymous catch (`catch (_:`). Every failure in the " +
                "JNI bridge is swallowed into a sentinel return, so an " +
                "unreported one reaches nobody: Log.e is stripped from release " +
                "builds. Bind the exception and call FaultReporter.report.",
            Regex("""catch\s*\(\s*_\s*:""").containsMatchIn(text),
        )

        // Every catch block must report before it returns its sentinel.
        val catches = Regex("""catch\s*\(\s*\w+\s*:""").findAll(text).map { it.range.first }.toList()
        assertTrue("$path has no catch blocks — the scan is vacuous", catches.size >= 5)
        catches.forEach { start ->
            val window = text.substring(start, minOf(text.length, start + 900))
            assertTrue(
                "$path: the catch block at offset $start does not call " +
                    "FaultReporter.report. A swallowed exception here is silent " +
                    "in the release artifact.\n---\n${window.take(300)}\n---",
                window.contains("FaultReporter.report("),
            )
        }
    }

    /**
     * A kill switch that fails to arm leaves traffic in the clear while the UI
     * and the notification both say "protected". It was `Log.e` only, i.e.
     * nothing. Both the root (activateKillSwitch) and the two callers that
     * publish the user-facing error must report.
     */
    @Test
    fun `kill switch arming failures are reported, not only logged`() {
        val path = "app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt"
        val text = strippedSource(path)

        listOf(
            // activateKillSwitch: establish() returned null — no throwable at all.
            "kill_switch_establish_refused",
            // activateKillSwitch: Builder/establish threw.
            "kill_switch_activate_threw",
            // Re-arm after a system restart (START_STICKY relaunch).
            "kill_switch_rearm_failed_restart",
            // Re-arm for a session the backend invalidated.
            "kill_switch_rearm_failed_invalidated",
        ).forEach { code ->
            assertTrue(
                "$path no longer reports fault code '$code'. If the branch was " +
                    "removed, remove this pin with it; if it was merely " +
                    "reworded, keep reporting it — a kill switch that fails " +
                    "silently is the worst failure this client has.",
                text.contains("\"$code\""),
            )
        }

        // The specific regression: this phrase used to appear ONLY inside a
        // Log.e, which R8 deletes.
        val loggedOnly = text.lines().filter {
            it.contains("traffic is NOT blocked") && Regex("""Log\.[a-z]+\(""").containsMatchIn(it)
        }
        assertEquals(
            "$path routes a 'traffic is NOT blocked' message through android.util.Log, " +
                "which is stripped from release builds. Route it through " +
                "FaultReporter.report instead. Offending lines: $loggedOnly",
            emptyList<String>(),
            loggedOnly,
        )
    }

    /** Native-integrity verdicts only ever happen on builds we did not ship. */
    @Test
    fun `native integrity failures are reported`() {
        val path = "app/src/main/java/app/birdo/vpn/utils/NativeLibraryVerifier.kt"
        val text = source(path)

        listOf(
            "native_lib_missing",
            "integrity_no_hash_no_signature",
            "integrity_repackaged_apk",
            "integrity_hash_mismatch_untrusted",
            // Accepted, but only because the signature saved it: this is how we
            // learn our own build pipeline injected a stale NATIVE_HASH_*.
            "integrity_hash_stale_accepted_by_signature",
            "integrity_verify_threw",
        ).forEach { code ->
            assertTrue("$path no longer reports fault code '$code'", text.contains("\"$code\""))
        }
    }

    /**
     * The catch-all. ~19 sites publish a VpnState.Error and more will be added;
     * requiring each new one to remember a report call is the exact shape of
     * bug this ticket was. updateState is the single funnel they all pass
     * through, so a breadcrumb there covers every present and future branch.
     */
    @Test
    fun `every VpnState Error leaves a breadcrumb via updateState`() {
        val text = source("app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt")
        val body = text.substringAfter("private fun updateState(newState: VpnState) {")
            .substringBefore("\n        }")
        assertTrue(
            "BirdoVpnService.updateState no longer trails VpnState.Error. It is " +
                "the only funnel every error branch passes through; without it a " +
                "new error branch is invisible again.",
            body.contains("FaultReporter.trail(") && body.contains("VpnState.Error"),
        )
    }

    // ── Invariants that keep the channel working ─────────────────────────

    /**
     * A duplicated code means two failures share one throttle bucket, so the
     * second one is muted for five minutes by the first — silently.
     */
    @Test
    fun `fault codes are unique across the app`() {
        val codeRegex = Regex("FaultReporter\\.report\\(\\s*FaultReporter\\.PATH_\\w+,\\s*\"([^\"]+)\"")
        val codes = shippedSources().flatMap { file ->
            codeRegex.findAll(file.readText()).map { it.groupValues[1] }
        }.toList()
        assertTrue("found only ${codes.size} report call sites — the scan is vacuous", codes.size >= 10)
        val duplicates = codes.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(
            "duplicate fault codes share a throttle bucket and mute each other: $duplicates",
            emptySet<String>(),
            duplicates,
        )
    }

    // The invariant that keeps this whole channel alive in a release build —
    // `-assumenosideeffects` must name android.util.Log and NOTHING else, since
    // adding io.sentry there would delete the reporter silently (R8 does not
    // report what it removed) — is already pinned by
    // PrivacyBoundaryTest.`sentry is never added to the proguard strip list`
    // (P6-CLI-SENTRY-01). Not duplicated here on purpose: two copies of one pin
    // is how the pair drifts apart. If that test moves, this comment is the
    // pointer to follow.

    // ── Throttle behaviour ───────────────────────────────────────────────

    @Test
    fun `the first occurrence of a fault code always sends`() {
        assertTrue(FaultReporter.shouldSend("test_code_first"))
    }

    @Test
    fun `a repeat inside the window is throttled, and codes do not share a bucket`() {
        assertTrue(FaultReporter.shouldSend("test_code_a"))
        // The polling loops (socket protect, stall detection) call into the
        // reporter every couple of seconds while a fault lasts; without this
        // the quota is gone in minutes.
        assertFalse(FaultReporter.shouldSend("test_code_a"))
        assertFalse(FaultReporter.shouldSend("test_code_a"))
        // A DIFFERENT failure must not be muted by an unrelated one — that
        // would turn the throttle into a single-event-per-process reporter.
        assertTrue(FaultReporter.shouldSend("test_code_b"))
        assertFalse(FaultReporter.shouldSend("test_code_b"))
        assertFalse(FaultReporter.shouldSend("test_code_a"))
    }

    /** The reporter runs inside catch blocks; it may never throw. */
    @Test
    fun `report and trail are safe when Sentry was never initialised`() {
        // Unit tests never call SentryAndroid.init, which is the same state a
        // debug build is in (initSentry returns early) — so this is the real
        // uninitialised path, not a mock of it.
        FaultReporter.report(FaultReporter.PATH_CONNECT, "test_code_no_sentry", "message")
        FaultReporter.report(
            FaultReporter.PATH_KILL_SWITCH,
            "test_code_no_sentry_throwable",
            "message",
            IllegalStateException("boom"),
        )
        FaultReporter.trail(FaultReporter.PATH_TUNNEL, "message")
    }
}
