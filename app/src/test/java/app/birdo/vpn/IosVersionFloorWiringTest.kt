package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OPEN-WORK H3 — the iOS version-floor advisory must be read on EVERY connect
 * path, not just the first one.
 *
 * Why a JVM test reads Swift, again: iOS is never built in PR CI (ios.yml runs
 * on tags and workflow_dispatch only), and the two files that matter —
 * `APIClient.swift` and `VpnViewModel.swift` — cannot compile into the iOS unit
 * test bundle, so `VersionFloorAdvisoryTests` can pin the decoding but not the
 * wiring. This runs on every PR with the Android unit tests, exactly like
 * [QuickSelectGuardTest].
 *
 * The defect it guards is one I made while fixing H3: the advisory was wired
 * into single-hop and rebuild and MISSED on multi-hop, because that call site
 * is indented differently. That is the twin-drift shape (Mobile-Client#336) —
 * a guard on some of N parallel paths — and it compiles perfectly.
 */
class IosVersionFloorWiringTest {

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

    private val apiClient by lazy { source("iosApp/iosApp/Services/APIClient.swift") }
    private val viewModel by lazy { source("iosApp/iosApp/ViewModels/VpnViewModel.swift") }

    @Test
    fun `the connect response model decodes clientUpdate`() {
        assertTrue(
            "VPNConnectionConfig does not declare clientUpdate — the backend sends it on every " +
                "below-floor iOS connect and it would be dropped on the floor, which IS the H3 defect",
            apiClient.contains("let clientUpdate: ClientUpdateAdvisory?"),
        )
        assertTrue(
            "clientUpdate is declared but never decoded in init(from:)",
            Regex("""clientUpdate\s*=\s*try\??\s*c\.decodeIfPresent""").containsMatchIn(apiClient),
        )
    }

    @Test
    fun `a malformed advisory cannot fail the connect decode`() {
        // `try?`, not `try`. Without it a backend that changes a field's TYPE
        // turns "you should update" into "you cannot connect" — strictly worse
        // than the silence H3 fixes.
        assertTrue(
            "clientUpdate must be decoded with `try?` so advice can never reject a good tunnel config",
            apiClient.contains("clientUpdate = try? c.decodeIfPresent"),
        )
    }

    @Test
    fun `every connect path applies the advisory`() {
        // Each successful connect obtains a config and lights the quantum
        // badge. That line is the reliable marker for "a connect succeeded
        // here", so every one of them must also apply the advisory.
        val quantumSites = Regex("""quantumActive = BirdoPQManager\.shared\.currentMode""")
            .findAll(viewModel).count()
        assertTrue(
            "found no connect-success sites in VpnViewModel.swift — the marker this test " +
                "keys on has changed, so it would pass vacuously",
            quantumSites > 0,
        )
        // Matches the CALL, not one spelling of its argument: the three paths
        // reach their config differently (`config`, `ctx.newConfig?`), and a
        // guard that pinned one spelling would have gone green on a connect
        // path that never called this at all.
        val applySites = Regex("""(?<!func )applyUpdateAdvisory\(""")
            .findAll(viewModel).count()
        assertEquals(
            "every successful connect must apply the version-floor advisory; " +
                "$applySites of $quantumSites paths do. A missed path is silent — " +
                "that user simply never learns their build is unsupported.",
            quantumSites, applySites,
        )
    }

    @Test
    fun `dismissal is scoped to one floor, not to the banner forever`() {
        // A raised floor is NEW information. If dismissal were a single bool,
        // a user who dismissed 1.4.30 would never be told about 1.5.0 either.
        assertTrue(
            "dismissUpdateAdvisory must record the specific minVersion, not a bare flag",
            viewModel.contains("updateAdvisory?.minVersion"),
        )
        assertTrue(
            "the dismissed set must be persisted, or the banner returns on every launch",
            viewModel.contains("@AppStorage(\"dismissedUpdateFloors\")"),
        )
    }

    @Test
    fun `the advisory is rendered somewhere`() {
        // The entire point of H3: the backend was already sending this and no
        // UI existed to show it.
        val home = source("iosApp/iosApp/Views/HomeView.swift")
        assertTrue(
            "HomeView never reads vpnVM.updateAdvisory — the floor would still warn nobody",
            home.contains("vpnVM.updateAdvisory"),
        )
        assertTrue(
            "the advisory must be gated on isRenderable somewhere, or an empty " +
                "clientUpdate paints a blank banner",
            viewModel.contains("advisory.isRenderable"),
        )
    }
}
