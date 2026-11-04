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

    /**
     * The Android paywall's copy: its feature lines live in strings.xml since
     * A2-031 (`subscription_feature_*`), plus whatever literal is left in the
     * screen. Reading only the screen would now pass on an empty list.
     */
    private fun androidPaywallCopy(): List<String> {
        val strings = source("app/src/main/res/values/strings.xml")
        val features = Regex(
            """<(string|plurals) name="subscription_feature_[^"]+"[^>]*>(.*?)</\1>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(strings).map { it.groupValues[2] }.toList()
        assertTrue("found only ${features.size} paywall feature strings", features.size >= 15)
        return features + literals(source("app/src/main/java/app/birdo/vpn/ui/screen/SubscriptionScreen.kt"))
    }

    @Test
    fun `the Play paywall sells no speed test and states no location count`() {
        val android = androidPaywallCopy()
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

    /**
     * MR-1980: both Recon cards said "10 GB monthly bandwidth" from a number in
     * the build; the owner raised the free cap to 50 GB. The cards now state the
     * server's value (FreePlanCap / freePlanCapGb), so no paywall may carry a
     * literal GB figure for the free plan again.
     */
    @Test
    fun `no paywall hard-codes the free plan's data allowance`() {
        val ios = literals(source("iosApp/iosApp/Views/SubscriptionView.swift"))
        val gbLine = Regex("""\b\d+(\.\d+)?\s*GB monthly""", RegexOption.IGNORE_CASE)
        assertEquals("a GB figure is back on the iOS paywall", emptyList<String>(), ios.filter { gbLine.containsMatchIn(it) })
        assertTrue(source("iosApp/iosApp/Views/SubscriptionView.swift").contains("FreePlanCap.featureLine(gb: freeCapGb)"))
        val android = source("app/src/main/java/app/birdo/vpn/ui/screen/SubscriptionScreen.kt")
        assertFalse(
            "the Android Recon card counts a literal number of GB again",
            Regex("""subscription_feature_monthly_data,\s*\d""").containsMatchIn(android),
        )
        assertTrue(android.contains("PlanFeature.FreeMonthlyData,"))
    }

    /**
     * MR-1981 (repo half): the store texts the owner pastes into Play Console
     * and App Store Connect state the 50 GB free cap, not the old 10 GB.
     * Nothing publishes them automatically (play-listing.yml and
     * asc-metadata.yml are workflow_dispatch only).
     */
    @Test
    fun `the store texts state the 50 GB free cap`() {
        val listing = source("store-assets/listing/en-US/full-description.txt")
        assertTrue(listing.contains("• Recon (free): 1 device, 50 GB of data a month"))
        val asc = source(".github/scripts/asc_metadata.py")
        assertTrue(asc.contains("to enforce the free plan's 50 GB"))
        val tenGb = Regex("""\b10\s?GB\b""")
        assertFalse(tenGb.containsMatchIn(listing))
        assertFalse(tenGb.containsMatchIn(asc))
    }

    @Test
    fun `the StoreKit test catalogue describes no stealth mode`() {
        val storekit = source("iosApp/BirdoVPN.storekit")
        assertFalse(storekit.contains("stealth", ignoreCase = true))
        assertFalse(storekit.contains("split tunnel", ignoreCase = true))
    }

    /**
     * Paid limits change on 5 December 2026: Operative 5 to 3, Sovereign 10 to 5
     * (birdo-web lib/plans.ts). A card states what a new buyer gets, so it has no
     * date logic. A subscriber's own limit is the server's (`maxConnections`) and
     * can be the old one until then.
     */
    @Test
    fun `each paywall card sells the connection limit a new buyer gets`() {
        val android = source("app/src/main/java/app/birdo/vpn/ui/screen/SubscriptionScreen.kt")
        val ios = source("iosApp/iosApp/Views/SubscriptionView.swift")
        val counted = Regex("""subscription_feature_devices,\s*(\d+)""")
        mapOf("RECON" to 1, "OPERATIVE" to 3, "SOVEREIGN" to 5).forEach { (plan, limit) ->
            val androidCard = android.substringAfter("id = \"$plan\",").substringBefore("PlanInfo(")
            assertEquals("Android $plan card", "$limit", counted.find(androidCard)?.groupValues?.get(1))
            val iosCard = ios.substringAfter("slug: \"$plan\",").substringBefore("PlanCardModel(")
            val line = if (limit == 1) "\"1 device connection\"" else "\"$limit device connections\""
            assertTrue("iOS $plan card does not say $line", line in iosCard)
        }
    }

    @Test
    fun `the store texts state the connection limit a new buyer gets`() {
        val listing = source("store-assets/listing/en-US/full-description.txt")
        assertTrue(listing.contains("• Operative: 3 devices,"))
        assertTrue(listing.contains("• Sovereign: 5 devices,"))
        val storekit = source("iosApp/BirdoVPN.storekit").lines().filter { "device connections" in it }
        assertEquals("the StoreKit paid products that state a limit", 4, storekit.size)
        storekit.forEach { line ->
            val limit = if ("Everything in Operative" in line) 5 else 3
            assertTrue(line, "and $limit device connections" in line)
        }
    }
}
