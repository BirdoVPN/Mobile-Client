package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The consent screens are the pre-use data declaration (Apple 5.4, Play's
 * prominent disclosure) and the point where every in-app account accepts the
 * Terms. Audit 2026-09-29 (P0-1 / B-1 / D-1 / B-3 / B-13) found them telling
 * every new user the VPN ran on "RAM-only volatile infrastructure" with no
 * connection timestamps or IP addresses logged, calling a reversible IP hash
 * "non-reversible", promising iOS crash reports that do not exist, and asking
 * for the privacy policy only.
 *
 * The approved wording is REMEDIATION-DECISIONS §1.5. These pin it on Android
 * (strings.xml) and iOS/macOS (ConsentView.swift). Why a JVM test reads Swift:
 * iOS never builds in PR CI — same reason as IosPurchaseResumeTest.
 */
class ConsentCopyTest {

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
        return file.readText()
    }

    /** name -> user-visible value, unescaped the way aapt2 would. */
    private val androidStrings: Map<String, String> by lazy {
        val xml = source("app/src/main/res/values/strings.xml")
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { m ->
                m.groupValues[1] to m.groupValues[2]
                    .replace("\\'", "'")
                    .replace("\\\"", "\"")
                    .replace("&amp;", "&")
            }
            .also { assertTrue("strings.xml parse found only ${it.size} strings", it.size > 100) }
    }

    /** Every string literal on a non-comment line of a Swift file. */
    private fun swiftLiterals(path: String): List<String> =
        source(path).lines()
            .filterNot { it.trimStart().startsWith("//") }
            .flatMap { line -> Regex(""""((?:[^"\\]|\\.)*)"""").findAll(line).map { it.groupValues[1].replace("\\\"", "\"") } }

    private val consentView = "iosApp/iosApp/Views/ConsentView.swift"

    private val noActivityLogs =
        "Our VPN servers don't record the sites you visit, your DNS queries or your traffic. " +
            "While you're connected, our account system keeps a live record of your session " +
            "(server, device, connect time). It is deleted when you disconnect and is left out " +
            "of our nightly backups. We also count your data use per billing period."

    private val accountHolds =
        "Your email (or anonymous account number), plan, the devices you add, and your usage " +
            "totals. Full list: birdo.app/privacy."

    // Second-pass #7 (option A): FaultReporter also sends non-fatal events when
    // a feature fails, so the disclosure names error reports and never says
    // "crash details" or "only".
    private val crashReports =
        "Off by default. If you turn this on, the app sends crash and error reports to Sentry " +
            "so we can fix bugs: crashes, and errors when an app feature such as connecting or " +
            "the kill switch fails, with the app and OS version and device model. No account " +
            "details, IP address or browsing data. Change it any time in Settings."

    @Test
    fun `android consent items use the approved wording`() {
        assertEquals(noActivityLogs, androidStrings["consent_no_logs_desc"])
        assertEquals(accountHolds, androidStrings["consent_minimal_data_desc"])
        assertEquals(crashReports, androidStrings["consent_crash_reports_desc"])
    }

    @Test
    fun `the Settings crash-report row discloses error reports too`() {
        val settings = androidStrings["settings_crash_reports_desc"].orEmpty()
        assertTrue(settings.contains("Crash and error reports"))
        assertTrue(settings.contains("such as connecting or the kill switch fails"))
        assertFalse("never \"only\": the reports are not crash details alone", settings.contains("only", ignoreCase = true))
    }

    @Test
    fun `ios consent items use the approved wording and offer no crash reports`() {
        val literals = swiftLiterals(consentView)
        assertTrue("iOS 'No Activity Logs' text drifted", noActivityLogs in literals)
        assertTrue("iOS 'What Your Account Holds' text drifted", accountHolds in literals)
        assertFalse(
            "the iOS/macOS app has no crash-reporting SDK; its consent screen must not " +
                "describe crash reports",
            literals.any { it.contains("crash", ignoreCase = true) },
        )
    }

    @Test
    fun `both consent screens link the Terms and the Privacy Policy and state the age`() {
        val android = source("app/src/main/java/app/birdo/vpn/ui/screen/ConsentScreen.kt")
        assertTrue(android.contains("https://birdo.app/terms"))
        assertTrue(android.contains("https://birdo.app/privacy"))
        assertEquals("You must be 18 or over to use BirdoVPN.", androidStrings["consent_age_notice"])
        assertTrue(
            androidStrings["consent_terms_notice"].orEmpty()
                .contains("accept the Terms of Service and the Privacy Policy"),
        )

        val ios = swiftLiterals(consentView)
        assertTrue(ios.contains("https://birdo.app/terms"))
        assertTrue(ios.contains("https://birdo.app/privacy"))
        assertTrue(ios.contains("You must be 18 or over to use BirdoVPN."))
        assertTrue(ios.any { it.contains("accept the Terms of Service and the Privacy Policy") })
    }

    @Test
    fun `no retired claim survives in any user-visible consent text`() {
        val forbidden = listOf(
            "RAM-only", "RAM only", "volatile", "diskless", "zero-log", "zero log",
            "non-reversible", "No personal data", "IP addresses are logged",
            "connection timestamps", "never included in backups", "not in any backup",
        )
        val android = androidStrings.filterKeys { it.startsWith("consent_") }.values
        val ios = swiftLiterals(consentView)
        val hits = (android + ios).flatMap { text ->
            forbidden.filter { text.contains(it, ignoreCase = true) }.map { "'$it' in: $text" }
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun `accepting an older consent screen does not count as accepting this one`() {
        val prefs = source("app/src/main/java/app/birdo/vpn/data/preferences/AppPreferences.kt")
        val version = Regex("""const val CURRENT_CONSENT_VERSION = (\d+)""").find(prefs)
        assertTrue("CURRENT_CONSENT_VERSION is gone", version != null)
        assertTrue(
            "the 2026-09-29 rewrite must be shown again to users who accepted version 1",
            version!!.groupValues[1].toInt() >= 2,
        )
        val graph = source("app/src/main/java/app/birdo/vpn/ui/navigation/BirdoNavGraph.kt")
        assertTrue(graph.contains("mutableStateOf(appPreferences.hasAcceptedCurrentConsent)"))
        assertTrue(graph.contains("appPreferences.acceptedConsentVersion = AppPreferences.CURRENT_CONSENT_VERSION"))
    }
}
