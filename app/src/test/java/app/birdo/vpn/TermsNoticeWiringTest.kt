package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-2199: that the terms notices reach Home, on both platforms.
 *
 * TermsNoticesTest (Android) and TermsNoticeTests.swift (iOS and macOS) pin the
 * pure rules. The composable and `HomeView.swift` cannot run in either test
 * bundle, so what they do with those rules is read from the source here, as
 * [IosConnectRefusalWiringTest] does. Runs on every pull request with the
 * Android unit tests.
 *
 * Messages report paths only, never source text.
 */
class TermsNoticeWiringTest {

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
        val text = file.readText().replace("\r\n", "\n")
        assertTrue("scan target $path is empty", text.length > 200)
        return text
    }

    private val homeScreenPath = "app/src/main/java/app/birdo/vpn/ui/screen/HomeScreen.kt"
    private val navGraphPath = "app/src/main/java/app/birdo/vpn/ui/navigation/BirdoNavGraph.kt"
    private val noticesPath = "app/src/main/java/app/birdo/vpn/ui/screen/TermsNotices.kt"
    private val stringsPath = "app/src/main/res/values/strings.xml"
    private val homeViewPath = "iosApp/iosApp/Views/HomeView.swift"
    private val authWirePath = "iosApp/iosApp/Services/AuthWire.swift"
    private val authViewModelPath = "iosApp/iosApp/ViewModels/AuthViewModel.swift"
    private val projectPath = "iosApp/project.yml"

    /** From the declaration to the next top-level declaration, or the end of the file. */
    private fun block(text: String, path: String, start: String, end: String?): String {
        val from = text.indexOf(start)
        assertTrue("$path: the notice block is gone — this test would be vacuous", from >= 0)
        val to = end?.let { text.indexOf(it, from + start.length) } ?: -1
        return if (to < 0) text.substring(from) else text.substring(from, to)
    }

    private val composable by lazy {
        block(
            source(homeScreenPath), homeScreenPath,
            "private fun ColumnScope.TermsNoticeBanners(", "\n// ── Update banner",
        )
    }
    private val rows by lazy { block(source(homeViewPath), homeViewPath, "private struct TermsNoticeRows: View {", null) }

    // ── Android ──────────────────────────────────────────────────────────

    @Test
    fun `Android, the notices reach Home from the signed-in profile`() {
        assertTrue(
            "$navGraphPath: HomeScreen no longer gets AuthViewModel's user's notices",
            source(navGraphPath).contains("termsNotices = authState.user?.termsNotices.orEmpty()"),
        )
        assertTrue(
            "$homeScreenPath: the action panel no longer shows them",
            source(homeScreenPath).contains("TermsNoticeBanners(termsNotices, onOpenUrl)"),
        )
    }

    @Test
    fun `Android, each is a calm banner with the server's text, and cannot be dismissed`() {
        assertTrue("$homeScreenPath: not HomeBanner", composable.contains("HomeBanner("))
        assertTrue("$homeScreenPath: not the info tone", composable.contains("tone = BannerTone.Info"))
        assertTrue("$homeScreenPath: not the text as sent", composable.contains("message = notice.text,"))
        assertFalse("$homeScreenPath: a notice must not be dismissible", composable.contains("onDismiss"))
        assertFalse("$homeScreenPath: a notice is never an error", composable.contains("BannerTone.Danger"))
    }

    @Test
    fun `Android, the link and the clock come from the pure rules`() {
        assertTrue("$homeScreenPath: the link skips the rule", composable.contains("TermsNotices.link(notice)"))
        assertTrue(
            "$homeScreenPath: a screen left open must drop a notice at its date",
            composable.contains("TermsNotices.inForceOver(notices)"),
        )
        assertTrue(
            "$homeScreenPath: stopped with the app, read again on return",
            composable.contains("collectAsStateWithLifecycle"),
        )
    }

    @Test
    fun `Android, nothing is recorded or sent`() {
        val rules = source(noticesPath)
        for (word in listOf("repository", "prefs", "Preferences", "Sentry", "FaultReporter", "Retrofit", "api.")) {
            assertFalse("$noticesPath: no storage, no reporting, no call", rules.contains(word))
            assertFalse("$homeScreenPath: no storage, no reporting, no call", composable.contains(word))
        }
    }

    // ── iOS and macOS ────────────────────────────────────────────────────

    @Test
    fun `Apple, the notices are read leniently, entry by entry`() {
        val wire = source(authWirePath)
        assertTrue(
            "$authWirePath: a wrong shape must not fail the profile",
            wire.contains("(try? c.decodeIfPresent([TermsNoticeEntry].self, forKey: .termsNotices))"),
        )
        assertTrue(
            "$authWirePath: a bad entry must be dropped on its own",
            wire.contains("notice = try? TermsNotice(from: decoder)"),
        )
    }

    @Test
    fun `Apple, Home shows what applyProfile set, and nothing keeps it`() {
        val viewModel = source(authViewModelPath)
        val apply = block(viewModel, authViewModelPath, "private func applyProfile(", "\n    private func ")
        assertTrue("$authViewModelPath: applyProfile no longer sets the profile", apply.contains("user = profile"))
        assertFalse(
            "$authViewModelPath: held in the profile only, which goes on sign-out",
            viewModel.contains("termsNotices"),
        )
        val home = source(homeViewPath)
        assertTrue(
            "$homeViewPath: Home no longer reads the profile's notices",
            home.contains("if let notices = authVM.user?.termsNotices, !notices.isEmpty {"),
        )
        assertTrue("$homeViewPath: the rows are gone", home.contains("TermsNoticeRows(notices: notices)"))
    }

    @Test
    fun `Apple, each row is the server's text, calm, and cannot be dismissed`() {
        assertTrue("$homeViewPath: not the text as sent", rows.contains("Text(verbatim: notice.text)"))
        assertTrue("$homeViewPath: not the calm tone", rows.contains("BirdoTheme.accentSoft"))
        assertFalse("$homeViewPath: a notice is never an error", rows.contains("ErrorBanner"))
        assertFalse("$homeViewPath: a notice is never red", rows.contains("BirdoTheme.red"))
        assertFalse("$homeViewPath: a notice must not be dismissible", rows.contains("Button("))
        assertFalse("$homeViewPath: nothing is recorded", rows.contains("UserDefaults"))
    }

    @Test
    fun `Apple, the link and the clock come from the pure rules`() {
        assertTrue("$homeViewPath: the link skips SitePath", rows.contains("if let url = notice.link {"))
        assertTrue(
            "$authWirePath: the link skips SitePath",
            source(authWirePath).contains("var link: URL? { SitePath.url(path) }"),
        )
        assertTrue("$homeViewPath: rows skip the date rule", rows.contains("TermsNotice.live(notices, at: now)"))
        assertTrue(
            "$homeViewPath: a screen left open must drop a notice at its date",
            rows.contains("TermsNotice.nextCheck(notices, at: now)") && rows.contains("Task.sleep(for: .seconds(wait))"),
        )
    }

    @Test
    fun `the unit bundle compiles the rules and what they call`() {
        val project = source(projectPath)
        for (file in listOf("iosApp/Services/AuthWire.swift", "iosApp/Services/ConnectRefusal.swift")) {
            assertTrue("$projectPath: $file is not in the unit bundle", project.contains("- path: $file\n"))
        }
    }

    // ── The two platforms agree ──────────────────────────────────────────

    @Test
    fun `the link's words and the tick are the same on both platforms`() {
        val label = "See every change"
        assertTrue("$stringsPath: the link's words", source(stringsPath).contains(">$label</string>"))
        assertTrue("$homeViewPath: the link's words", rows.contains("Link(\"$label\""))
        assertEquals("$noticesPath: the tick", 1, Regex("""TICK_MS = 60_000L""").findAll(source(noticesPath)).count())
        assertEquals("$authWirePath: the tick", 1, Regex("""tick: TimeInterval = 60\n""").findAll(source(authWirePath)).count())
    }
}
