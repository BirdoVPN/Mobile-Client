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
}
