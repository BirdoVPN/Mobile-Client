package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OPEN-WORK K10 — the "pick a server for me" rule must be the same on every
 * path and every platform, and this is the test that fails when it is not.
 *
 * Why a JVM test reads Swift: the Apple pre-select had TWO call sites in
 * `VpnViewModel.swift` (`loadServers()` and the auto-connect-on-launch task),
 * each spelling `list.first { $0.isOnline && $0.accessible }` by hand — the
 * name-sorted list made that "Amsterdam, for everyone, always". Fixing one
 * and not the other is exactly the twin-drift shape Mobile-Client#336 records,
 * and iOS is never built in PR CI, so a Swift-only guard would run only when
 * someone dispatches ios.yml. This one runs on every PR with the Android unit
 * tests. It reads the sources rather than the compiled classes for the same
 * reason MultiHopPolicyTest does: the defect it guards is a NEW hand-written
 * call site, which compiles perfectly.
 */
class QuickSelectGuardTest {

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
        val text = file.readText()
        assertTrue("scan target $path is empty", text.length > 200)
        return text
    }

    private val appleViewModel = "iosApp/iosApp/ViewModels/VpnViewModel.swift"

    @Test
    fun `no Apple pre-select path takes the first online node any more`() {
        val text = source(appleViewModel)
        val firstOnline = Regex("""\.first\s*\{\s*\$0\.isOnline""")
        val hits = firstOnline.findAll(text).map { m ->
            text.substring(0, m.range.first).count { it == '\n' } + 1
        }.toList()
        assertEquals(
            "VpnViewModel.swift lines $hits still pick the FIRST usable node. The list is " +
                "name-sorted, so that is Amsterdam for every user regardless of load (K10). " +
                "Route the choice through QuickSelect.bestServer(in:) — and do not leave the " +
                "old spelling in a comment either; this guard reads the raw file.",
            emptyList<Int>(),
            hits,
        )
    }

    @Test
    fun `both Apple pre-select paths use QuickSelect`() {
        // Exactly two: loadServers() and the auto-connect-on-launch task. If
        // you added a third path that assigns `selectedServer` without a user
        // choice, route it through QuickSelect and raise this count WITH the
        // path's name here — that is the whole point of counting.
        val text = source(appleViewModel)
        val sites = Regex("""QuickSelect\.bestServer\(in:""").findAll(text).count()
        assertEquals(
            "expected the two pre-select twins (loadServers + auto-connect-on-launch) " +
                "to call QuickSelect.bestServer(in:), found $sites call(s)",
            2,
            sites,
        )
    }

    @Test
    fun `the Swift rule is compiled into the un-hosted test bundle`() {
        // QuickSelectTests.swift asserts the rule, but only if xcodegen compiles
        // QuickSelect.swift into BirdoVPNTests. Dropping the project.yml line
        // would turn those tests into a compile error on the next ios.yml
        // dispatch — which nobody runs on a PR. Catch it here instead.
        val projectYml = source("iosApp/project.yml")
        assertTrue(
            "iosApp/project.yml no longer lists iosApp/Services/QuickSelect.swift under " +
                "BirdoVPNTests.sources — QuickSelectTests cannot compile without it",
            Regex("""^\s*-\s*path:\s*iosApp/Services/QuickSelect\.swift\s*$""", RegexOption.MULTILINE)
                .containsMatchIn(projectYml),
        )
        assertTrue(
            "iosApp/BirdoVPNTests/QuickSelectTests.swift is missing",
            File(repoRoot, "iosApp/BirdoVPNTests/QuickSelectTests.swift").isFile,
        )
    }

    @Test
    fun `Android quick-connect still ranks on load — the rule the Apple twin copies`() {
        // Not a change: VpnManager.quickConnect() has ranked by minByOrNull
        // { it.load } since it shipped, and K10 part A (birdo-web) makes that
        // `load` CPU-aware with no client release. This pins the Android half
        // so the two platforms cannot drift apart in opposite directions.
        val text = source("app/src/main/java/app/birdo/vpn/service/VpnManager.kt")
        val quickConnect = text.indexOf("suspend fun quickConnect()")
        assertTrue("VpnManager.quickConnect() not found", quickConnect >= 0)
        val body = text.substring(quickConnect, minOf(text.length, quickConnect + 2000))
        assertTrue(
            "VpnManager.quickConnect() no longer filters online && accessible and ranks by " +
                "minByOrNull { it.load }",
            Regex("""\.filter\s*\{\s*it\.isOnline\s*&&\s*it\.accessible\s*\}\s*\.minByOrNull\s*\{\s*it\.load\s*\}""")
                .containsMatchIn(body),
        )
    }
}
