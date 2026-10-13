package app.birdo.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The iOS/macOS wiring of a second batch of register rows, pinned from the
 * Android unit tests in the style of IosRequiredRowsGuardTest: the Swift files
 * involved (VpnViewModel, HomeView, AuthViewModel, VPNManager) cannot compile
 * into the un-hosted iOS test bundle. The pure logic is XCTested there
 * (GlobeRouteTests, InstallIdentityTests, KillSwitchChipTests, FreePlanCapTests);
 * this pins that the app actually calls it.
 */
class IosParityRowsGuardTest {

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
        return file.readText().replace("\r\n", "\n")
    }

    private val ios = "iosApp/iosApp"
    private val home by lazy { source("$ios/Views/HomeView.swift") }
    private val vpnVm by lazy { source("$ios/ViewModels/VpnViewModel.swift") }
    private val projectYml by lazy { source("iosApp/project.yml") }

    /** The block of project.yml that lists the unit-test bundle's sources. */
    private val unitTestTarget by lazy {
        val start = projectYml.indexOf("\n  BirdoVPNTests:")
        assertTrue("project.yml has no BirdoVPNTests target", start >= 0)
        val end = projectYml.indexOf("\n  BirdoVPNUITests:", start)
        projectYml.substring(start, if (end > start) end else projectYml.length)
    }

    // ── MR-1100: a Multi-Hop session is shown as the pair it is ──────────────

    @Test
    fun `the connected node of a Multi-Hop session is the entry node`() {
        assertTrue(vpnVm.contains("connectedServerId = activeMultiHop?.entry.id ?? selectedServer?.id"))
    }

    @Test
    fun `a settings rebuild redials the confirmed pair and a server switch refuses`() {
        assertTrue(vpnVm.contains("target = .multiHop(entry: mh.entry, exit: mh.exit)"))
        assertTrue(
            Regex("""if activeMultiHop != nil \{\s*error = "Switching servers would replace your Multi-Hop route""")
                .containsMatchIn(vpnVm),
        )
    }

    @Test
    fun `Home says Multi-Hop and names entry and exit`() {
        assertTrue(home.contains("""vpnVM.activeMultiHop != nil ? "Protected · Multi-Hop" : "Protected""""))
        assertTrue(home.contains("""Text("\(mh.entry.flag) \(mh.entry.name) → \(mh.exit.flag) \(mh.exit.name)")"""))
    }

    @Test
    fun `the globe draws the two-hop route`() {
        assertTrue(
            "HomeView no longer hands the globe the Multi-Hop exit",
            home.contains("exitServerId: vpnVM.activeMultiHop?.exit.id"),
        )
        val globe = source("$ios/Views/Components/WorldGlobeView.swift")
        assertTrue(globe.contains("GlobeRoute.legs(user: user, entry: sel, exit: exitCoord)"))
        assertTrue(globe.contains("GlobeRoute.focus(user: user, entry: selectedCoord, exit: exitCoord)"))
        assertTrue(unitTestTarget.contains("- path: iosApp/Views/Components/GlobeRoute.swift"))
        assertTrue(File(repoRoot, "iosApp/BirdoVPNTests/GlobeRouteTests.swift").isFile)
        // One unit-test bundle: the old branch added its own, this repo has one.
        assertFalse(Regex("""(?m)^  BirdoVPNUnitTests:""").containsMatchIn(projectYml))
    }

    // ── MR-1726: the Apple device identity is per install ────────────────────

    @Test
    fun `the first-run check runs before anything reads the keychain`() {
        val auth = source("$ios/ViewModels/AuthViewModel.swift")
        val check = auth.indexOf("InstallIdentity.checkOnLaunch(")
        val firstTokenRead = auth.indexOf("keychain.accessToken")
        assertTrue("AuthViewModel no longer runs the first-run identity check", check >= 0)
        assertTrue("the check runs after the session is read from the keychain", check < firstTokenRead)
        assertTrue(
            source("$ios/Services/KeychainService.swift")
                .contains("extension KeychainService: InstallIdentityStore {}"),
        )
        assertTrue(unitTestTarget.contains("- path: iosApp/Services/InstallIdentity.swift"))
    }

    @Test
    fun `the keychain no longer promises an identity that survives a reinstall`() {
        val keychain = source("$ios/Services/KeychainService.swift")
        assertFalse(keychain.contains("stable across\n    /// logins AND reinstalls"))
        assertFalse(source("$ios/Services/APIClient.swift").contains("so it survives reinstall"))
    }

    @Test
    fun `signing out keeps the device id, as on Android`() {
        // Android: BirdoRepository.logout clears tokens and the PQ keypair and
        // keeps DeviceInfoProvider's id; only deletion rotates it.
        val auth = source("$ios/ViewModels/AuthViewModel.swift")
        val logout = auth.substringAfter("private func completeLocalLogout() {").substringBefore("\n    }\n")
        assertFalse("sign-out rotates the device id", logout.contains("resetDeviceIdentity"))
        assertFalse("sign-out runs the fresh-install reset", logout.contains("InstallIdentity"))
    }
}
