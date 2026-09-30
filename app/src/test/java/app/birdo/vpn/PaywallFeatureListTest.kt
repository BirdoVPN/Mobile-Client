package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Audit 2026-09-29, A-10 / D-4 / A-24 / A-34: the paywalls sold features the
 * platform does not have — the App Store paywall listed Split tunneling,
 * Stealth mode and Speed test (none exist on iOS/macOS), Android listed Speed
 * test (no mobile implementation) — stated "2 server locations" where the web
 * deliberately gives no count, and advertised "Save 20%" for both plans though
 * Sovereign saves 17%. Users pay Apple and Google on the strength of these
 * lists (Guidelines 2.3 / 3.1.2; CMA unfair-practices rules).
 *
 * Reads Swift for the usual reason: iOS never builds in PR CI.
 */
class PaywallFeatureListTest {

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

    /** String literals on non-comment lines. */
    private fun literals(text: String): List<String> =
        text.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .flatMap { line -> Regex(""""((?:[^"\\]|\\.)*)"""").findAll(line).map { it.groupValues[1] } }

    private val locationCount = Regex("""\b\d+\s+(server\s+)?locations?\b""", RegexOption.IGNORE_CASE)

    @Test
    fun `the App Store paywall sells only what iOS and macOS have`() {
        val ios = literals(source("iosApp/iosApp/Views/SubscriptionView.swift"))
        val notOnApple = listOf("Split tunneling", "Stealth mode", "Speed test")
        val hits = ios.filter { lit -> notOnApple.any { lit.contains(it, ignoreCase = true) } }
        assertEquals("features iOS/macOS do not have are on the App Store paywall", emptyList<String>(), hits)
        assertFalse("a location count is back on the iOS paywall", ios.any { locationCount.containsMatchIn(it) })
    }

    @Test
    fun `the Play paywall sells no speed test and states no location count`() {
        val android = literals(source("app/src/main/java/app/birdo/vpn/ui/screen/SubscriptionScreen.kt"))
        assertFalse(android.any { it.contains("Speed test", ignoreCase = true) })
        assertFalse("a location count is back on the Android paywall", android.any { locationCount.containsMatchIn(it) })
    }

    @Test
    fun `no paywall promises a flat 20 percent saving`() {
        val strings = source("app/src/main/res/values/strings.xml")
        val yearly = Regex("""<string name="subscription_yearly">(.*?)</string>""").find(strings)?.groupValues?.get(1)
        assertEquals("Yearly · Save up to 20%", yearly)
        val ios = literals(source("iosApp/iosApp/Views/SubscriptionView.swift"))
        assertTrue(ios.contains("Yearly · Save up to 20%"))
        assertFalse(ios.any { it.contains("Save 20%") })
    }

    @Test
    fun `the StoreKit test catalogue describes no stealth mode`() {
        val storekit = source("iosApp/BirdoVPN.storekit")
        assertFalse(storekit.contains("stealth", ignoreCase = true))
        assertFalse(storekit.contains("split tunnel", ignoreCase = true))
    }
}
