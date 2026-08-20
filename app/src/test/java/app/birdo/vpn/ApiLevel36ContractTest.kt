package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural pins for the Android 16 (API 36) target.
 *
 * Google Play requires an app's target API to stay within a year of the latest
 * Android release: from 31 Aug 2026 an app targeting below API 36 CANNOT BE
 * UPDATED. The bump itself is two lines in app/build.gradle.kts, which makes it
 * exactly the kind of change a careless merge-conflict resolution or a revert
 * can silently undo — the app still builds, still installs, still runs, and the
 * only symptom is a rejected upload months later.
 *
 * These tests read the REAL build script and the REAL source manifest at test
 * runtime — never a copy — following the pattern in PrivacyBoundaryTest, so the
 * edit that breaks a guarantee breaks the build.
 *
 * The manifest invariants pinned here are the ones whose failure mode at API 36
 * is SILENT to the developer but severe for the user: an always-on lockdown with
 * no headless reconnect, an intent-matching opt-in that has never been exercised
 * against the service's custom actions, and an edge-to-edge opt-out that stopped
 * working in Android 16.
 */
class ApiLevel36ContractTest {

    /** Repo root, found by walking up from the test working dir. */
    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above " + File("").absolutePath)
        }
        dir
    }

    private fun readText(relativePath: String, sentinel: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue(relativePath + " does not exist at " + file.absolutePath, file.isFile)
        val text = file.readText()
        // Vacuity guard: if the file were empty, renamed, or read from the wrong
        // place, every "absent" assertion below would pass for free.
        assertTrue(
            relativePath + " does not contain the sentinel [" + sentinel + "] — the " +
                "file was found but is not the one these assertions think they are " +
                "reading, so the scans below would be vacuous",
            text.contains(sentinel),
        )
        return text
    }

    private val buildScript: String
        get() = readText("app/build.gradle.kts", "android {")

    private val manifest: String
        get() = readText("app/src/main/AndroidManifest.xml", ".service.BirdoVpnService")

    // ── The Play deadline itself ─────────────────────────────────────────

    @Test
    fun `app targets at least API 36`() {
        val target = Regex("""^\s*targetSdk\s*=\s*(\d+)""", RegexOption.MULTILINE)
            .find(buildScript)
            ?.groupValues?.get(1)?.toInt()
        assertTrue(
            "targetSdk was not found in app/build.gradle.kts — the declaration moved " +
                "or changed shape and this guard stopped guarding anything",
            target != null,
        )
        assertTrue(
            "targetSdk is " + target + ". Google Play requires target API >= 36 " +
                "(Android 16): from 31 Aug 2026 an app below that floor cannot be " +
                "updated at all. Do not lower this to work around a UI bug — fix the " +
                "bug, or use the documented per-symptom escape hatch (for predictive " +
                "back, android:enableOnBackInvokedCallback=\"false\").",
            target!! >= 36,
        )
    }

    @Test
    fun `app compiles against at least API 36`() {
        val compile = Regex("""^\s*compileSdk\s*=\s*(\d+)""", RegexOption.MULTILINE)
            .find(buildScript)
            ?.groupValues?.get(1)?.toInt()
        assertTrue(
            "compileSdk was not found in app/build.gradle.kts — the declaration moved " +
                "or changed shape and this guard stopped guarding anything",
            compile != null,
        )
        assertTrue(
            "compileSdk is " + compile + " but targetSdk is 36. Targeting an API you " +
                "do not compile against means the new platform's constants and opt-out " +
                "attributes are not even resolvable, while the runtime behaviour " +
                "changes apply to you anyway.",
            compile!! >= 36,
        )
    }

    // ── Manifest invariants that break users, not builds ─────────────────

    @Test
    fun `always-on VPN stays disabled`() {
        // BirdoVpnService cannot self-establish a tunnel from a system-initiated
        // (null-action) start or after reboot: the WireGuard config is fetched per
        // user-initiated connect and held in memory, and there is no headless
        // re-auth path. If SUPPORTS_ALWAYS_ON is flipped to true, a user can enable
        // Always-on VPN + "Block connections without VPN" (lockdown) and then lose
        // ALL connectivity after every reboot until they manually reconnect — which
        // they cannot do, because there is no working network to log in over.
        val declaration = Regex(
            "android:name=\"android\\.net\\.VpnService\\.SUPPORTS_ALWAYS_ON\"" +
                "\\s*\\n\\s*android:value=\"(\\w+)\"",
        ).find(manifest)
        assertTrue(
            "The SUPPORTS_ALWAYS_ON meta-data declaration was not found in " +
                "AndroidManifest.xml. Deleting the element is NOT a safe no-op: " +
                "without it the system offers the Always-on toggle, which is the " +
                "exact state this guard exists to prevent.",
            declaration != null,
        )
        assertEquals(
            "android.net.VpnService.SUPPORTS_ALWAYS_ON must remain \"false\" until " +
                "BirdoVpnService can re-authenticate and re-fetch a config headlessly " +
                "in onStartCommand. See the comment above the element in the manifest.",
            "false",
            declaration!!.groupValues[1],
        )
    }

    @Test
    fun `safer intents enforcement is not opted into`() {
        // Android 16 ships "Safer Intents", but enforcement is OPT-IN via
        // android:intentMatchingFlags="enforceIntentFilter". Every internal start of
        // the VPN service is a component-explicit Intent carrying a CUSTOM action
        // (VpnManager -> BirdoVpnService.ACTION_START / ACTION_STOP /
        // ACTION_SWITCH_TEARDOWN / ACTION_UPDATE_SETTINGS), while the service
        // declares exactly one intent-filter, for android.net.VpnService. None of
        // those custom actions matches a declared filter.
        //
        // Opting in is therefore not a hardening freebie here; it changes how
        // intents are allowed to reach components on the connect path. It is not
        // required at API 36 and must not be enabled as a side effect of a target
        // bump — only deliberately, and only after each ACTION_* path has been
        // exercised on a real device.
        assertFalse(
            "AndroidManifest.xml now declares android:intentMatchingFlags. This is an " +
                "opt-in behaviour change affecting how intents reach components, and " +
                "the VPN connect path relies on explicit intents whose custom actions " +
                "match no declared intent-filter. Verify every BirdoVpnService " +
                "ACTION_* path on a real device before enabling it.",
            manifest.contains("intentMatchingFlags"),
        )
    }

    @Test
    fun `edge-to-edge enforcement is not opted out of`() {
        // Android 15 introduced windowOptOutEdgeToEdgeEnforcement as a temporary
        // escape hatch; in Android 16 it is REMOVED, so declaring it is a silent
        // no-op. Anyone adding it to fix an inset bug at API 36 would ship a "fix"
        // that does nothing. The real handling must stay: the app draws edge-to-edge
        // deliberately and pads with the window insets.
        val resourceXml = File(repoRoot, "app/src/main/res")
            .walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .toList()
        assertTrue(
            "found only " + resourceXml.size + " resource XML files — the walker is " +
                "broken and this scan would be vacuous",
            resourceXml.size >= 10,
        )
        val offenders = resourceXml
            .filter { it.readText().contains("windowOptOutEdgeToEdgeEnforcement") }
            .map { it.name }
        assertTrue(
            "windowOptOutEdgeToEdgeEnforcement is declared in " + offenders + " — the " +
                "attribute is REMOVED in Android 16 and does nothing at targetSdk 36. " +
                "Handle the insets instead.",
            offenders.isEmpty(),
        )

        val theme = readText(
            "app/src/main/java/app/birdo/vpn/ui/theme/Theme.kt",
            "MaterialTheme(",
        )
        assertTrue(
            "Theme.kt no longer calls WindowCompat.setDecorFitsSystemWindows(window, " +
                "false). At targetSdk 36 the app is drawn edge-to-edge with no opt-out " +
                "available, so this call and the matching insets padding are the only " +
                "thing keeping content out from under the system bars.",
            theme.contains("setDecorFitsSystemWindows(window, false)"),
        )
    }
}
