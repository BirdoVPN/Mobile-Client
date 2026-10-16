package app.birdo.vpn.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner decisions of 2026-10-07 that live in UI wiring, pinned from the source
 * in the style of OverhaulSourceGuardTest.
 */
class OwnerDecisionGuardTest {

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

    // ── MR-1519: the globe stops while connected, as on Windows ──────────

    @Test
    fun `the globe holds still while connected`() {
        assertTrue(
            "HomeScreen no longer stops the globe while connected",
            source("$main/ui/screen/HomeScreen.kt").contains("autoRotate = !isConnected"),
        )
        // Stopping only the spin changes nothing a connected user sees: with a
        // server selected, the focus already overrides it. The clock has to stop.
        assertTrue(
            "WorldGlobe's animation clock no longer stops when autoRotate is false",
            source("$main/ui/components/WorldGlobe.kt")
                .contains("val animating = rememberDecorativeMotionAllowed() && autoRotate"),
        )
    }

    // ── MR-1520: no "Secure Connection" badge on Login ───────────────────

    @Test
    fun `Login has no Secure Connection badge`() {
        val strings = source("app/src/main/res/values/strings.xml")
        assertFalse(strings.contains("name=\"login_status_badge\""))
        assertFalse(strings.contains(">Secure Connection<"))
        assertFalse(strings.contains("sovereign network", ignoreCase = true))
        assertFalse(source("$main/ui/screen/LoginScreen.kt").contains("login_status_badge"))
    }

    // ── MR-1518: no Quantum chip on Home, on any client ──────────────────

    @Test
    fun `Home has no Quantum chip on iOS or Android`() {
        // The chip stays removed everywhere (desktop dropped it in June). The
        // Quantum Protection toggle in Settings is not the chip and stays.
        val home = source("iosApp/iosApp/Views/HomeView.swift")
        assertFalse("iOS Home still shows the Quantum chip", Regex("""StatusBadgePill\(\s*"Quantum"""").containsMatchIn(home))
        assertFalse(home.contains("Quantum protection active"))
        assertFalse("iOS Home still reads quantumActive", home.contains("vpnVM.quantumActive"))
        assertFalse(source("$main/ui/screen/HomeScreen.kt").contains("quantum", ignoreCase = true))
    }

    // ── MR-1094: the Face ID / Touch ID prompt says what the gate does ───

    @Test
    fun `the Face ID and Touch ID purpose strings promise a cover, not protection`() {
        // P1-ios-biometric-gate-is-cosmetic, kept by the owner: the gate covers
        // the screen and protects no data (BiometricGate.swift). iOS shows this
        // string in the system prompt the first time the gate runs.
        val purpose = Regex("""<key>NSFaceIDUsageDescription</key>\s*<string>([^<]*)</string>""")
        for (plist in listOf("iosApp/iosApp/Info.plist", "iosApp/iosApp/Info-macOS.plist")) {
            val text = purpose.find(source(plist))?.groupValues?.get(1)
            assertTrue("$plist has no NSFaceIDUsageDescription", text != null)
            assertFalse("$plist: \"$text\" claims protection", text!!.contains("protect", ignoreCase = true))
            assertFalse("$plist: \"$text\" claims security", text.contains("secur", ignoreCase = true))
            assertTrue("$plist: \"$text\" does not name the setting it belongs to", text.contains("Hide App Contents"))
        }
        // A localised copy would show its own text in the prompt.
        val localized = File(repoRoot, "iosApp").walkTopDown()
            .filter { it.isFile && it.extension == "strings" && it.readText().contains("NSFaceIDUsageDescription") }
            .toList()
        localized.forEach { assertFalse("${it.path} claims protection", it.readText().contains("protect", ignoreCase = true)) }
    }
}
