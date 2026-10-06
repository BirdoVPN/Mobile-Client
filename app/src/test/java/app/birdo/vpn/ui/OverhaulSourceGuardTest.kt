package app.birdo.vpn.ui

import app.birdo.vpn.data.auth.SsoLaunch
import app.birdo.vpn.data.auth.ssoLaunchFor
import app.birdo.vpn.testing.KotlinSource
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
        // The label is decided from the whole profile since item 86: the
        // server's own anonymous flag first, the email's shape as a fallback.
        assertTrue(graph.contains("accountLabel = accountLabel(authState.user)"))
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

    // ── LIVE retest 2026-10-02: the revoke sentence ─────────────────────

    @Test
    fun `a revoke shows the canonical sentence, never the server's message`() {
        // Today's backend answers a lost key with "Connection not found";
        // HeartbeatPolicy infers a revoke (the eviction ping-pong's stop) from
        // it, so showing its message named the wrong thing.
        val revoked = source("$main/service/VpnManager.kt")
            .substringAfter("private suspend fun onHeartbeatVerdict(", "")
            .substringAfter("HeartbeatPolicy.Verdict.REVOKED -> {", "")
            .substringBefore("HeartbeatPolicy.Verdict.EVICTED ->")
        assertTrue(revoked.contains("endSessionForServer(SessionCopy.REVOKED, FailureKind.REVOKED)"))
        assertFalse(revoked.contains("serverMessage") || revoked.contains(".message"))
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
        val reset = activity.indexOf("appPreferences.resetProtectedSettingsToSafeDefaults()")
        assertTrue(reset > 0)
        assertTrue(activity.indexOf("appPreferences.settingsResetNoticePending = true", reset) > reset)
    }

    // ── P1-dk-dead-lastserver-verifyintegrity ────────────────────────────

    /**
     * AppPreferences owns "birdo_vpn_prefs". MainActivity re-opened the file
     * by its literal name to run the integrity check itself — once under the
     * WRONG name, which checked an empty file and never saw tampering — while
     * AppPreferences.verifyIntegrity() had no caller at all.
     */
    @Test
    fun `the settings integrity check goes through the injected AppPreferences`() {
        val integrity = source("$main/MainActivity.kt").replace("\r\n", "\n")
            .substringAfter("private fun verifySettingsIntegrity() {", "")
            .substringBefore("\n    }\n")
        assertTrue("verifySettingsIntegrity is gone", integrity.isNotEmpty())
        assertTrue(integrity.contains("appPreferences.verifyIntegrity()"))
        assertTrue(integrity.contains("appPreferences.resetProtectedSettingsToSafeDefaults()"))
        assertFalse(
            "MainActivity opens the settings store itself again; AppPreferences is its one owner",
            integrity.contains("getSharedPreferences("),
        )
        assertFalse(
            "MainActivity runs SettingsHmac on its own copy of the store again",
            integrity.contains("SettingsHmac."),
        )
        // The name lives in exactly one main-source file: the owner's.
        val opened = File(repoRoot, main).walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("\"birdo_vpn_prefs\"") }
            .map { it.name }
            .toList()
        assertEquals(listOf("AppPreferences.kt"), opened)
    }

    // ── A2-024 ───────────────────────────────────────────────────────────

    /** Every screen and every shared component, as (path under [main], source). */
    private fun uiSources(): List<Pair<String, String>> =
        listOf("ui/screen", "ui/components").flatMap { dir ->
            File(repoRoot, "$main/$dir").listFiles { f -> f.extension == "kt" }.orEmpty()
                .sortedBy { it.name }
                .map { "$dir/${it.name}" to it.readText() }
        }.also { assertTrue("found only ${it.size} UI files", it.size > 25) }

    /** Shapes that hold no text and may keep a fixed height. */
    private val fixedHeightAllowed = setOf(
        // Shimmer placeholder bars, drawn while the real rows load.
        "ui/components/BirdoSkeleton.kt",
    )

    @Test
    fun `no screen or component fixes the height of a control, so labels grow with the font`() {
        // Spacers may be fixed; anything that can hold a label may not.
        val fixed = Regex("""\.height\(\s*[\d.]+\.dp\s*\)""")
        val hits = uiSources().associate { (path, src) ->
            path to src.lines().withIndex()
                .filter { (_, line) -> "Spacer" !in line && fixed.containsMatchIn(line) }
                .map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }
        }
        val offenders = hits.filterKeys { it !in fixedHeightAllowed }.values.flatten()
        assertEquals("fixed-height control (A2-024): use heightIn(min = …)", emptyList<String>(), offenders)
        // An exemption that no longer matches anything is a hole for the next
        // fixed height in that file (REVIEW-AND2-006).
        val stale = fixedHeightAllowed.filter { hits[it].isNullOrEmpty() }
        assertEquals("stale fixed-height exemption", emptyList<String>(), stale)
    }

    /**
     * Text in a fixed-size tile is drawn with the user's font scale and
     * outgrows the tile at 200 %, where the tile's clip cuts it (a country
     * flag drawn as an emoji was). Such text must be sized in dp (`.toSp()`),
     * like the icon it stands in for. A file another lane still owns may be
     * listed here with the exact count it has, so a new tile still fails; an
     * entry whose count no longer matches fails too.
     *
     * Empty since the merge: HomeScreen's two flag tiles are dp-sized, and its
     * stale entry of 2 was never checked (files with no hits were dropped
     * before the comparison), so two new sp-sized texts there would have
     * passed (REVIEW-AND2-006).
     */
    private val scaledTextInTilePending = emptyMap<String, Int>()

    @Test
    fun `text inside a fixed-size tile does not scale with the font`() {
        val tile = Regex("""\b(Box|Surface|Row|Column)\s*\(""")
        val fixedSize = Regex("""\.size\(\s*[\d.]+\.dp\s*\)""")
        val text = Regex("""(?<![A-Za-z])Text\s*\(""")
        val found = uiSources().associate { (path, src) ->
            val kt = KotlinSource(src)
            val lines = tile.findAll(kt.code).flatMap { call ->
                val close = kt.closing(call.range.last)
                val block = kt.trailingBlock(close)
                if (block == null || !fixedSize.containsMatchIn(kt.code.substring(call.range.last, close))) {
                    emptySequence()
                } else {
                    text.findAll(kt.code.substring(0, block.last), block.first)
                        .filter { t -> "toSp()" !in kt.code.substring(t.range.last, kt.closing(t.range.last)) }
                        .map { t -> kt.lineOf(t.range.first) }
                }
            }.toSet()
            path to lines
        }
        // Every file with hits, and every listed file whether or not it still
        // has any: a listed count that no longer matches is stale.
        val unexpected = (found.filterValues { it.isNotEmpty() }.keys + scaledTextInTilePending.keys)
            .associateWith { found[it].orEmpty() }
            .filter { (path, lines) -> lines.size != (scaledTextInTilePending[path] ?: 0) }
        assertEquals("sp-sized Text in a fixed-size tile (A2-024)", emptyMap<String, Set<Int>>(), unexpected)
    }

    @Test
    fun `the account number wraps at large font scales instead of being cut short`() {
        val number = source("$main/ui/screen/ProfileScreen.kt")
            .substringAfter("text = if (accountNumberShown) {", "")
            .substringBefore("modifier =")
        assertTrue("the Profile account number is no longer shown grouped", number.contains("formatAnonymousId(accountNumber)"))
        assertTrue(number.contains("maxLines"))
        assertFalse("a credential the user copies by hand is ellipsized to one line", number.contains("maxLines = 1"))
    }

    /** REVIEW-AND2-013: masked until asked, as on Windows and in the account API contract. */
    @Test
    fun `the account number is masked until the user asks to see it`() {
        val profile = source("$main/ui/screen/ProfileScreen.kt")
        assertTrue(profile.contains("var accountNumberShown by rememberSaveable { mutableStateOf(false) }"))
        val number = profile.substringAfter("text = if (accountNumberShown) {", "").substringBefore("modifier =")
        assertTrue("the hidden state no longer masks", number.contains("maskAnonymousId(accountNumber)"))
        assertTrue(profile.contains("R.string.cd_show_account_number"))
    }

    /** REVIEW-AND2-008/-009: the deletion dialog's errors and its 2FA prompt. */
    @Test
    fun `the deletion dialog outlines the field an error is about, and focuses and announces the 2FA prompt`() {
        val dialog = source("$main/ui/screen/ProfileScreen.kt")
            .substringAfter("private fun DeleteAccountDialog(", "")
            .substringBefore("private fun VoucherRedeemDialog(")
        assertTrue(dialog.contains("isError = errorField == DeleteAccountField.PASSWORD"))
        assertTrue(dialog.contains("isError = errorField == DeleteAccountField.CODE"))
        assertFalse("an error is drawn on a field it is not about", dialog.contains("isError = error != null"))
        val prompt = dialog.substringAfter("if (requiresTwoFactor) {", "")
        assertTrue("the code field is no longer focused as it appears", prompt.contains("codeFocus.requestFocus()"))
        assertTrue(prompt.contains(".focusRequester(codeFocus)"))
        assertTrue(
            "the 2FA prompt is no longer announced",
            prompt.substringAfter("R.string.delete_dialog_2fa_required").substringBefore("OutlinedTextField")
                .contains("liveRegion = LiveRegionMode.Polite"),
        )
    }

    // ── D6 (owner decision, 2026-10-01) ──────────────────────────────────

    @Test
    fun `Custom DNS Servers is on every plan, in Settings and on the paywall`() {
        val settings = source("$main/ui/screen/SettingsScreen.kt")
        assertFalse("Custom DNS is plan-gated again", settings.contains("customDnsUnlocked"))
        val row = settings.substringAfter("title = stringResource(R.string.vpn_settings_custom_dns)")
            .substringBefore("item")
        assertFalse("the Custom DNS row is locked again", row.contains("locked"))
        assertFalse(source("$main/ui/navigation/BirdoNavGraph.kt").contains("customDnsUnlocked"))

        val plans = source("$main/ui/screen/SubscriptionScreen.kt").replace("\r\n", "\n")
            .substringAfter("private val plans = listOf(").substringBefore("\n)\n")
        val cards = plans.split("PlanInfo(").drop(1)
        assertEquals("three plan cards", 3, cards.size)
        cards.forEach { card ->
            assertTrue(
                "a plan card no longer lists Custom DNS Servers",
                card.contains("R.string.subscription_feature_custom_dns"),
            )
        }
        val listing = source("store-assets/listing/en-US/full-description.txt")
        assertTrue(listing.contains("Custom DNS servers on every plan"))
        assertFalse(
            "the Play listing sells Custom DNS as a Sovereign feature again",
            Regex("""Sovereign:[^\n]*custom DNS""", RegexOption.IGNORE_CASE).containsMatchIn(listing),
        )
    }

    // ── LIVE-PORT53 ──────────────────────────────────────────────────────

    /**
     * The relays accept WireGuard on 51820 only (fleet check 2026-10-01), so
     * the port is no setting: no preset or custom entry on the screen, and a
     * saved one is retired after the settings are verified (the rewrite
     * re-signs them).
     */
    @Test
    fun `the WireGuard port is not a choice, and a saved one is retired after verification`() {
        val screen = source("$main/ui/screen/VpnSettingsScreen.kt")
        assertFalse("a port preset is offered again", screen.contains("PORT_PRESETS"))
        assertFalse(screen.contains("onWireGuardPortChange"))
        assertFalse("the 53 preset is back", screen.contains("\"53\""))
        assertTrue(screen.contains("R.string.vpn_settings_port_fixed"))

        val integrity = source("$main/MainActivity.kt").replace("\r\n", "\n")
            .substringAfter("private fun verifySettingsIntegrity() {", "")
            .substringBefore("\n    }\n")
        val verified = integrity.indexOf("appPreferences.verifyIntegrity()")
        val retired = integrity.indexOf("appPreferences.retireWireGuardPortChoice()")
        assertTrue("the saved port is no longer retired at start-up", retired > 0)
        assertTrue("the port is retired (and the settings re-signed) before they are verified", retired > verified && verified > 0)
    }

    // ── A2-003 ───────────────────────────────────────────────────────────

    @Test
    fun `sign-out and plan changes reach the per-account server state`() {
        val graph = source("$main/ui/navigation/BirdoNavGraph.kt")
        assertTrue(graph.contains("vpnViewModel.resetForSignOut()"))
        assertTrue(graph.contains("vpnViewModel.onEntitlementChanged()"))
    }
}
