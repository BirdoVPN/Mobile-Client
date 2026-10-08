package app.birdo.vpn.ui

import app.birdo.vpn.R
import app.birdo.vpn.ui.screen.stealthBannerText
import app.birdo.vpn.ui.viewmodel.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner decisions 2026-10-07 (client UX batch): IP and location are OFF in
 * notifications by default (MR-1603), notification titles carry no
 * "BirdoVPN — " prefix (MR-1514), and Home says the network blocks VPN traffic
 * only when Stealth was an automatic fallback, never when the user turned it on
 * (device test of 1.4.33). The prefs default lives behind SharedPreferences, so
 * it is pinned in the source, in the style of OverhaulSourceGuardTest.
 */
class NotificationPrivacyCopyTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun source(path: String): String =
        File(repoRoot, path).also { assertTrue("missing: $path", it.isFile) }.readText()

    @Test
    fun `IP and location are off in notifications unless the user turns them on`() {
        val prefs = source("app/src/main/java/app/birdo/vpn/data/preferences/AppPreferences.kt")
        assertTrue(prefs.contains("prefs.getBoolean(KEY_NOTIF_SHOW_IP, false)"))
        assertTrue(prefs.contains("prefs.getBoolean(KEY_NOTIF_SHOW_LOCATION, false)"))
        val ui = SettingsUiState()
        assertFalse(ui.showIpInNotification)
        assertFalse(ui.showLocationInNotification)
    }

    @Test
    fun `notification titles carry no BirdoVPN prefix`() {
        val strings = source("app/src/main/res/values/strings.xml")
        val titles = Regex("""<string name="notif_title_[a-z_]+">([^<]*)</string>""")
            .findAll(strings).map { it.groupValues[1] }.toList()
        assertTrue("no notif_title_ strings found", titles.isNotEmpty())
        titles.forEach { assertFalse("prefixed title: $it", it.startsWith("BirdoVPN")) }
    }

    @Test
    fun `only an automatic Stealth fallback says the network blocks VPN traffic`() {
        assertEquals(R.string.stealth_fallback_active, stealthBannerText(stealthChosen = false))
        assertEquals(R.string.stealth_chosen_active, stealthBannerText(stealthChosen = true))
    }
}
