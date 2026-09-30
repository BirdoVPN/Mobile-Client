package app.birdo.vpn.ui

import app.birdo.vpn.data.auth.SsoLaunch
import app.birdo.vpn.data.auth.ssoLaunchFor
import app.birdo.vpn.utils.anonymousAccountNumber
import app.birdo.vpn.utils.isAnonymousAccountEmail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * Pins for the client-overhaul fixes that live in wiring rather than in a
 * pure function: where a fix is "this call moved", "this modifier is here",
 * the test reads the source, in the style of ReleaseLogGuardTest.
 */
class OverhaulSourceGuardTest {

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

    // ── A2-013 ───────────────────────────────────────────────────────────

    @Test
    fun `the synthetic anonymous email is recognised and never rendered on Home`() {
        val synthetic = "anon_838568571611234567890123@anonymous.local"
        assertTrue(isAnonymousAccountEmail(synthetic))
        assertEquals("838568571611234567890123", anonymousAccountNumber(synthetic))
        assertFalse(isAnonymousAccountEmail("anon_1@example.com"))
        assertNull(anonymousAccountNumber("user@birdo.app"))
        assertNull(anonymousAccountNumber(null))

        val graph = source("$main/ui/navigation/BirdoNavGraph.kt")
        assertFalse(
            "Home is handed the raw email again: an anonymous account's synthetic address " +
                "carries half its only credential",
            Regex("""HomeScreen\([\s\S]*?userEmail\s*=""").containsMatchIn(graph),
        )
        assertTrue(graph.contains("accountLabel = accountLabel(authState.user?.email)"))
    }

    // ── A2-010 ───────────────────────────────────────────────────────────

    @Test
    fun `the root Scaffold does not pad the status bar a second time`() {
        val graph = source("$main/ui/navigation/BirdoNavGraph.kt")
        assertTrue(
            "the root Scaffold must leave insets to the screens (A2-010)",
            graph.contains("contentWindowInsets = WindowInsets(0, 0, 0, 0)"),
        )
        assertTrue(
            "the bottom bar's padding must be consumed, or tab Scaffolds add the nav-bar inset again",
            graph.contains(".consumeWindowInsets(scaffoldPadding)"),
        )
        // Screens with no top bar inset themselves.
        for (screen in listOf("ProfileScreen.kt", "LimitScreen.kt")) {
            assertTrue(screen, source("$main/ui/screen/$screen").contains("statusBarsPadding()"))
        }
        for (screen in listOf("LoginScreen.kt", "ConsentScreen.kt")) {
            assertTrue(screen, source("$main/ui/screen/$screen").contains("WindowInsets.safeDrawing"))
        }
    }

    // ── A2-009 ───────────────────────────────────────────────────────────

    @Test
    fun `the app cover is drawn over the nav graph, not instead of it`() {
        val activity = source("$main/MainActivity.kt")
        assertFalse(
            "the nav graph is composed only when unlocked again: every unlock would build a new " +
                "NavController and drop the user on Connect",
            Regex("""if \(isLocked\.value\)\s*\{[^}]*\}\s*else\s*\{[\s\S]{0,400}BirdoNavGraph""").containsMatchIn(activity),
        )
        val graphCall = activity.indexOf("BirdoNavGraph(")
        val cover = activity.indexOf("AppCoverScreen(onUnlock")
        assertTrue(graphCall in 0 until cover)
        assertTrue(activity.contains("LocalAppObscured provides covered"))
    }

    // ── A2-019 ───────────────────────────────────────────────────────────

    @Test
    fun `the pixel background is not drawn under the globe`() {
        assertTrue(
            source("$main/ui/navigation/BirdoNavGraph.kt").contains("visible = currentRoute != Screen.Home.route"),
        )
    }

    // ── A2-016 ───────────────────────────────────────────────────────────

    @Test
    fun `SSO opens in a Custom Tab whenever a provider exists`() {
        assertEquals(SsoLaunch.CUSTOM_TAB, ssoLaunchFor("com.android.chrome"))
        assertEquals(SsoLaunch.BROWSER, ssoLaunchFor(null))
        val manifest = source("app/src/main/AndroidManifest.xml")
        assertTrue(
            "without this <queries> entry getPackageName is always null on API 30+",
            manifest.contains("android.support.customtabs.action.CustomTabsService"),
        )
        assertFalse(
            source("$main/ui/viewmodel/AuthViewModel.kt").contains("FLAG_ACTIVITY_NEW_TASK"),
        )
    }

    // ── A2-035 ───────────────────────────────────────────────────────────

    @Test
    fun `the activity asks the Play rail to reconcile on resume`() {
        assertTrue(source("$main/MainActivity.kt").contains("playBilling.get().onAppResumed()"))
    }

    // ── A2-043 ───────────────────────────────────────────────────────────

    @Test
    fun `sign up no longer opens the web sign-in page`() {
        assertFalse(source("$main/ui/navigation/BirdoNavGraph.kt").contains("https://birdo.app/login"))
        assertTrue(source("$main/ui/screen/LoginScreen.kt").contains("activeTab = AuthTab.Anonymous"))
    }

    // ── A2-045 ───────────────────────────────────────────────────────────

    @Test
    fun `the version can never compute a colliding versionCode`() {
        val props = Properties().apply { File(repoRoot, "version.properties").inputStream().use(::load) }
        assertTrue(props.getProperty("VERSION_MINOR").toInt() in 0..99)
        assertTrue(props.getProperty("VERSION_PATCH").toInt() in 0..99)
        assertTrue(
            "the build no longer refuses MINOR/PATCH >= 100",
            source("app/build.gradle.kts").contains("require(vMinor in 0..99 && vPatch in 0..99)"),
        )
    }

    // ── A2-039 ───────────────────────────────────────────────────────────

    @Test
    fun `no inert AppCompat night-mode call remains`() {
        assertFalse(source("$main/MainActivity.kt").contains("AppCompatDelegate"))
    }

    // ── A2-042 ───────────────────────────────────────────────────────────

    @Test
    fun `the root warning is a Compose dialog shown after consent`() {
        val activity = source("$main/MainActivity.kt")
        assertFalse(activity.contains("android.app.AlertDialog"))
        assertTrue(activity.contains("hasAcceptedCurrentConsentFlow"))
    }

    // ── A2-047 ───────────────────────────────────────────────────────────

    @Test
    fun `a settings-integrity reset leaves a notice for Settings`() {
        val activity = source("$main/MainActivity.kt")
        val reset = activity.indexOf("SettingsHmac.resetToSafeDefaults(prefs)")
        assertTrue(reset > 0)
        assertTrue(activity.indexOf("appPreferences.settingsResetNoticePending = true", reset) > reset)
    }

    // ── A2-024 ───────────────────────────────────────────────────────────

    @Test
    fun `sign-in and consent buttons grow with the font instead of clipping`() {
        for (screen in listOf("LoginScreen.kt", "ConsentScreen.kt")) {
            // Spacers may be fixed; controls may not.
            val controls = source("$main/ui/screen/$screen").lines().filterNot { "Spacer" in it }
            assertFalse(
                "$screen has a fixed-height control again (A2-024)",
                controls.any { Regex("""\.height\((40|48|52)\.dp\)""").containsMatchIn(it) },
            )
        }
    }

    // ── A2-003 ───────────────────────────────────────────────────────────

    @Test
    fun `sign-out and plan changes reach the per-account server state`() {
        val graph = source("$main/ui/navigation/BirdoNavGraph.kt")
        assertTrue(graph.contains("vpnViewModel.resetForSignOut()"))
        assertTrue(graph.contains("vpnViewModel.onEntitlementChanged()"))
    }
}
