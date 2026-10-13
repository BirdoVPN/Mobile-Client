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
    /** VPNConnectionConfig moved here from APIClient.swift (MR-1949). */
    private val connectConfig by lazy { source("iosApp/iosApp/Services/ConnectWire.swift") }
    private val viewModel by lazy { source("iosApp/iosApp/ViewModels/VpnViewModel.swift") }

    @Test
    fun `the connect response model decodes clientUpdate`() {
        assertTrue(
            "VPNConnectionConfig does not declare clientUpdate — the backend sends it on every " +
                "below-floor iOS connect and it would be dropped on the floor, which IS the H3 defect",
            connectConfig.contains("let clientUpdate: ClientUpdateAdvisory?"),
        )
        assertTrue(
            "clientUpdate is declared but never decoded in init(from:)",
            Regex("""clientUpdate\s*=\s*try\??\s*c\.decodeIfPresent""").containsMatchIn(connectConfig),
        )
    }

    @Test
    fun `a malformed advisory cannot fail the connect decode`() {
        // `try?`, not `try`. Without it a backend that changes a field's TYPE
        // turns "you should update" into "you cannot connect" — strictly worse
        // than the silence H3 fixes.
        assertTrue(
            "clientUpdate must be decoded with `try?` so advice can never reject a good tunnel config",
            connectConfig.contains("clientUpdate = try? c.decodeIfPresent"),
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

    // ── MR-1661: the hard floor's 426 ───────────────────────────────────────

    @Test
    fun `both connect routes keep the 426 details instead of a fixed sentence`() {
        // The generic mapping turns every 426 into one sentence and drops
        // `details`; only the refusal hook sees the body first. Same shape as
        // IosConnectRefusalWiringTest: once in each route's request.
        for (route in listOf("/vpn/connect", "/vpn/multi-hop/connect")) {
            val pathAt = apiClient.indexOf("path: \"$route\",")
            assertTrue("no request carries $route", pathAt >= 0)
            val window = apiClient.substring(pathAt, apiClient.indexOf("decodeConnectResult(", pathAt))
            assertEquals(
                "the $route refusal hook must decode the 426 into UpdateRequiredRefusal",
                1,
                Regex("""UpdateRequiredRefusal\.classify\(""").findAll(window).count(),
            )
        }
    }

    @Test
    fun `every failed connect path raises the required-update banner`() {
        // The same twin-drift shape as the advisory: three failure paths
        // (single-hop, multi-hop, live rebuild), each must look for the 426.
        val failurePaths = Regex("""holdAutomaticDials\(after: error\)""").findAll(viewModel).count()
        val raised = Regex("""showUpdateRequired\(after: error\)""").findAll(viewModel).count()
        assertTrue("found no connect failure paths; the marker changed", failurePaths >= 3)
        assertEquals("every connect failure path must raise the required-update banner", failurePaths, raised)
    }

    @Test
    fun `a required update cannot be dismissed and links to the App Store`() {
        assertTrue(
            "dismissUpdateAdvisory must refuse a required advisory, as Android's UpdateViewModel does",
            "if updateAdvisory?.required == true { return }" in viewModel,
        )
        val home = source("iosApp/iosApp/Views/HomeView.swift")
        assertTrue("the banner hides Dismiss for a required update", "if !advisory.required {" in home)
        assertTrue("the Update button uses the App Store link the advisory resolves", "advisory.storeURL" in home)
        val advisory = source("iosApp/iosApp/Services/ClientUpdateAdvisory.swift")
        assertTrue(
            "storeURL may only open an https apps.apple.com link",
            "host == \"apps.apple.com\"" in advisory && "url.scheme?.lowercased() == \"https\"" in advisory,
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
