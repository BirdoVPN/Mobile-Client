package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Audit 2026-09-29: the store listing, F-Droid metadata, App Review notes and
 * in-app copy carried claims the system contradicts — "RAM-only volatile
 * infrastructure", "we cannot see ... when you connect", "no connection
 * timestamps ... Period.", "your real IP address never leaks", "recorded
 * traffic stays private even against future quantum computers", "maximum
 * anonymity", "Open-source clients", a retired warrant canary, "Rosenpass",
 * and account data "in the United Kingdom".
 *
 * Every surface this repo publishes now follows
 * AUDIT-2026-09-29/REMEDIATION-DECISIONS.md. This scans those surfaces — the
 * user-visible text only, not code comments that quote a retired claim to
 * explain why it went — so a retired claim cannot quietly come back.
 */
class PublicClaimsGuardTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun file(path: String): File =
        File(repoRoot, path).also { assertTrue("scan target is missing: $path", it.isFile) }

    private fun text(path: String): String = file(path).readText()

    /** Retired or forbidden public claims (case-insensitive substrings). */
    private val forbidden = listOf(
        "RAM-only", "RAM only", "volatile infrastructure", "diskless",
        "zero-log", "zero log", "zero-activity-log",
        "warrant canary",
        "open-source clients", "open source clients", "fully open source",
        "maximum anonymity", "extra anonymity",
        "never leaks", "traffic stops instead of leaking", "nothing leaves your device",
        "quantum-safe", "quantum-resistant", "quantum-ready", "future quantum computer",
        "cannot see which websites", "when you connect", "connection timestamps",
        "assigned-IP records", "IP addresses are logged",
        "Rosenpass",
        "United Kingdom",
        "Always-On VPN keeps", "system-guaranteed block",
        "No personal data", "non-reversible",
        "publicly documented server architecture", "github.com/BirdoVPN/Architecture",
        "no tracking libraries", "no third-party analytics",
        "DNS-over-HTTPS", "live latency",
        // Second-pass #2: the daily physical backup (7 days) can hold the live
        // record and usage totals (REMEDIATION-DECISIONS §1.2a).
        "never included in backups", "never in backups", "not in any backup",
    )

    /** Every publishable text surface in this repo, as (label, text). */
    private fun surfaces(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        // Play listing source (scripts/play_listing.py publishes these).
        File(repoRoot, "store-assets/listing").walkTopDown().filter { it.isFile && it.extension == "txt" }
            .forEach { out += repoRelative(it) to it.readText() }
        out += "store-assets/alt-text.txt" to text("store-assets/alt-text.txt")
        // Play "What's new".
        File(repoRoot, "distribution/whatsnew").walkTopDown().filter { it.isFile }
            .forEach { out += repoRelative(it) to it.readText() }
        // F-Droid repository and listing (YAML comments stripped: they explain history).
        out += "fdroid/metadata/app.birdo.vpn.yml" to stripHashComments(text("fdroid/metadata/app.birdo.vpn.yml"))
        out += "fdroid/config.yml" to stripHashComments(text("fdroid/config.yml"))
        out += "fdroid/site-index.html" to text("fdroid/site-index.html")
        File(repoRoot, "fdroid/metadata/app.birdo.vpn").walkTopDown().filter { it.isFile && it.extension == "txt" }
            .forEach { out += repoRelative(it) to it.readText() }
        // App Review notes: the VPN answers block only (the script also lists
        // the retired sentences so it can flag them in App Store Connect).
        val asc = text(".github/scripts/asc_metadata.py")
        val answers = Regex("VPN_ANSWERS = \"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL).find(asc)
        assertTrue("VPN_ANSWERS block not found in asc_metadata.py", answers != null)
        out += "asc_metadata.py VPN_ANSWERS" to answers!!.groupValues[1]
        // Android string resources (values only).
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text("app/src/main/res/values/strings.xml"))
            .forEach { out += "strings.xml:${it.groupValues[1]}" to it.groupValues[2] }
        // Hard-coded Android UI text and iOS/macOS view text (literals on non-comment lines).
        val uiRoots = listOf("app/src/main/java/app/birdo/vpn/ui", "iosApp/iosApp/Views")
        uiRoots.forEach { root ->
            File(repoRoot, root).walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "swift") }
                .forEach { f ->
                    literals(f.readText()).forEach { out += repoRelative(f) to it }
                }
        }
        return out
    }

    private fun repoRelative(f: File) = f.relativeTo(repoRoot).invariantSeparatorsPath

    private fun stripHashComments(yaml: String) =
        yaml.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")

    private fun literals(source: String): List<String> =
        source.lines()
            .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
            .flatMap { line -> Regex(""""((?:[^"\\]|\\.)*)"""").findAll(line).map { it.groupValues[1] } }

    @Test
    fun `no retired claim appears on any public surface`() {
        val all = surfaces()
        assertTrue("surface scan found only ${all.size} texts — the walker is broken", all.size > 200)
        val hits = all.flatMap { (label, body) ->
            forbidden.filter { body.contains(it, ignoreCase = true) }.map { "$label: \"$it\"" }
        }
        assertEquals("retired public claims found:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    @Test
    fun `the Play listing fits Play's limits and states what we are`() {
        val full = text("store-assets/listing/en-US/full-description.txt").trim()
        val short = text("store-assets/listing/en-US/short-description.txt").trim()
        val whatsNew = text("distribution/whatsnew/whatsnew-en-US").trim()
        assertTrue("full description is ${full.length} chars (Play limit 4000)", full.length <= 4000)
        assertTrue("short description is ${short.length} chars (Play limit 80)", short.length <= 80)
        assertTrue("what's new is ${whatsNew.length} chars (Play limit 500)", whatsNew.length <= 500)
        listOf(
            "Source-available apps (CC BY-NC 4.0)",
            "Not yet independently audited",
            "No advertising or analytics SDKs. Optional crash reporting (off unless you turn it on).",
            "If the tunnel drops unexpectedly, the app blocks traffic until it reconnects.",
            // Second-pass #5: the corrected REMEDIATION-DECISIONS §2 Android caveat.
            "Protection applies while BirdoVPN's VPN service is running.",
            "It is not onion routing",
            "TLS that is not yet post-quantum",
            "It is deleted when you disconnect and is left out of our nightly backups.",
            "Our daily encrypted copy of the database files (kept 7 days, used for " +
                "point-in-time recovery) can contain it as it stood at that moment.",
        ).forEach { assertTrue("full description lost: $it", full.contains(it, ignoreCase = true)) }
    }

    /**
     * Second-pass #5: each platform's kill-switch copy carries its caveat.
     * Android's block is the app's own and holds while its VpnService runs
     * (Always-on is not offered). iOS releases the block when the re-dial
     * breaker trips, and tells the user only in-app the next time it runs, so
     * the copy says "stops blocking" and never "tells you".
     */
    @Test
    fun `kill switch copy carries each platform caveat`() {
        // Raw resource text: aapt2 needs the apostrophe escaped as \'.
        val androidCaveat = """Protection applies while BirdoVPN\'s VPN service is running."""
        val strings = text("app/src/main/res/values/strings.xml")
        assertTrue(
            "settings_kill_switch_desc lost the Android caveat",
            Regex("""<string name="settings_kill_switch_desc">[^<]*""").find(strings)?.value.orEmpty()
                .contains(androidCaveat),
        )
        val fdroid = text("fdroid/metadata/app.birdo.vpn.yml").replace(Regex("""\s+"""), " ")
        assertTrue(fdroid.contains("Protection applies while BirdoVPN's VPN service is running"))

        val ios = text("iosApp/iosApp/Views/SettingsView.swift")
        assertTrue(ios.contains("\"If reconnecting keeps failing, the app stops blocking.\""))
        assertFalse(
            "the iOS app shows no notification when the breaker releases the block",
            literals(ios).any { it.contains("tells you", ignoreCase = true) },
        )
    }

    @Test
    fun `F-Droid declares the crash reporter and the Play libraries`() {
        val yml = text("fdroid/metadata/app.birdo.vpn.yml")
        val anti = Regex("""AntiFeatures:\s*\n((?:\s+-\s+\w+\s*\n)+)""").find(yml)?.groupValues?.get(1).orEmpty()
        listOf("NonFreeNet", "NonFreeDep", "Tracking").forEach {
            assertTrue("F-Droid AntiFeatures lost $it", anti.contains(it))
        }
    }

    @Test
    fun `App Review notes put the account data in Germany`() {
        val asc = text(".github/scripts/asc_metadata.py")
        val answers = Regex("VPN_ANSWERS = \"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL).find(asc)!!.groupValues[1]
        assertTrue(answers.contains("hosted by Hetzner in Germany (EU)"))
        // Second-pass #6: only the WAL bucket is documented as EU-jurisdiction,
        // and §1.2a fixes the public wording of the vanished-app limit.
        assertTrue(answers.contains("Cloudflare R2 object storage."))
        assertFalse("the nightly-dump bucket's jurisdiction is unconfirmed", answers.contains("R2 in the EU"))
        assertTrue(answers.contains("within 15 minutes of the last check-in"))
    }
}
