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
                .contains("extension KeychainService: InstallIdentityStore {"),
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

    // ── MR-1726 follow-ups (owner, 2026-10-14) ───────────────────────────────

    @Test
    fun `a fresh install starts signed out, before the session is read`() {
        val identity = source("$ios/Services/InstallIdentity.swift")
        val clear = identity.indexOf("store.clearSession()")
        val rotate = identity.indexOf("store.saveDeviceId(mintDeviceId())")
        assertTrue("the fresh-install path no longer signs out", clear >= 0)
        assertTrue("the session must be cleared before the device id is replaced", clear < rotate)
        assertTrue(
            source("$ios/Services/KeychainService.swift").contains("func clearSession() { clear() }"),
        )
        val auth = source("$ios/ViewModels/AuthViewModel.swift")
        assertTrue(auth.contains("if install == .discardedPreviousInstall {"))
        // The rest of a sign-out, as completeLocalLogout does it.
        val rest = auth.substringAfter("if install == .discardedPreviousInstall {").substringBefore("}")
        assertTrue(rest.contains("BirdoPQManager.shared.resetPersistedKeypair()"))
        assertTrue(rest.contains("VPNManager.shared.scrubHeartbeatCredentials()"))
    }

    @Test
    fun `signing out forgets the anonymous account number, as on Android`() {
        val auth = source("$ios/ViewModels/AuthViewModel.swift")
        val logout = auth.substringAfter("private func completeLocalLogout() {").substringBefore("\n    }\n")
        assertTrue("sign-out keeps the account number on the device", logout.contains("LocalSignOut.wipe(keychain)"))
        assertTrue(
            source("$ios/Services/InstallIdentity.swift")
                .contains("static func wipe(_ store: any InstallIdentityStore) {\n        store.clearSession()\n        store.clearAnonymousId()"),
        )
        // The new account's number comes from the server, never from storage
        // that could hold a previous account's.
        val minted = auth.substringAfter("let minted = registration.anonymousId").substringBefore("if let minted")
        assertFalse("anonymous create falls back to a stored number", minted.contains("keychain.anonymousId"))
        assertTrue(
            "the sign-out confirmation no longer says to keep the account number",
            home.contains("Make sure you have saved your account number. "),
        )
    }

    @Test
    fun `the apps say account number, never account ID`() {
        // Owner, 2026-10-14: "Use 'Account number' everywhere", as Android and
        // the website do. Apple ID / Face ID / Touch ID are Apple's names.
        val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
        val id = Regex("""(?<!Apple |Face |Touch )\bID\b""")
        val hits = listOf("$ios", "iosApp/PacketTunnel").flatMap { dir ->
            File(repoRoot, dir).walkTopDown().filter { it.isFile && it.extension == "swift" }.flatMap { file ->
                file.readLines()
                    .filterNot { it.trimStart().startsWith("//") }
                    .flatMap { line -> literal.findAll(line).map { it.groupValues[1] } }
                    .filter { it != "ID" && id.containsMatchIn(it) }
                    .map { "${file.name}: $it" }
            }.toList()
        }
        assertTrue("user-visible text still says ID: $hits", hits.isEmpty())
        val login = source("$ios/Views/LoginView.swift")
        listOf(
            "BirdoTextField(\"Account number\",",
            "\"Save your account number\"",
            ".accessibilityLabel(\"Copy account number\")",
            "Announcement(\"Account number copied\")",
        ).forEach { assertTrue("LoginView lost: $it", login.contains(it)) }
        // The UI-test identifier stays.
        assertTrue(login.contains(".accessibilityIdentifier(\"login_anonymous_id_field\")"))
    }

    // ── MR-975: the iOS kill-switch chip shows the real state ────────────────

    @Test
    fun `the Home kill-switch chip comes from the system state, not the toggle`() {
        assertTrue(
            home.contains(
                "KillSwitchChip.resolve(system: vpnVM.killSwitchSystem,\n" +
                    "                               tunnelUp: vpnVM.isConnected,",
            ),
        )
        // The old chip ANDed the toggle into "blocked".
        assertFalse(home.contains("vpnVM.killSwitchArmed && settingsVM.killSwitchEnabled"))
        val manager = source("$ios/Services/VPNManager.swift")
        assertTrue(
            "the armed state no longer requires a Connect rule",
            manager.contains("hasConnectRule: mgr.onDemandRules?.contains { \$0 is NEOnDemandRuleConnect } ?? false"),
        )
        // A Settings toggle changes no tunnel status: the save must report back.
        assertTrue(
            manager.substringAfter("func applyKillSwitchFlag(").substringBefore("\n    }\n")
                .contains("self?.refreshKillSwitchState()"),
        )
        assertTrue(vpnVm.contains("vpnManager.onKillSwitchStateChange = { [weak self] state in"))
        assertTrue(unitTestTarget.contains("- path: iosApp/Services/KillSwitchChip.swift"))
    }

    @Test
    fun `the kill-switch chip reads the system's configuration fresh, isEnabled included`() {
        // vpn-d3 review: the VPN switched off in iOS Settings, or another VPN
        // chosen there, never reached the in-memory manager.
        assertTrue(
            source("$ios/Services/KillSwitchChip.swift")
                .contains("configurationEnabled && onDemandEnabled && hasConnectRule && includeAllNetworks"),
        )
        val manager = source("$ios/Services/VPNManager.swift")
        assertTrue(manager.contains("configurationEnabled: mgr.isEnabled,"))
        assertTrue(manager.contains("forName: .NEVPNConfigurationChange,"))
        val statusHandler = manager.substringAfter("forName: .NEVPNStatusDidChange,").substringBefore("\n    }\n")
        assertTrue("a status change no longer re-reads the configuration", statusHandler.contains("self?.refreshKillSwitchState()"))
        val refresh = manager.substringAfter("func refreshKillSwitchState() {").substringBefore("\n    }\n")
        assertTrue(refresh.contains("NETunnelProviderManager.loadAllFromPreferences"))
        // The fresh copy is read and dropped: assigning it would let a reload
        // overwrite edits this app is about to save.
        assertFalse(refresh.contains("manager ="))
    }

    // ── vpn-d3 review M1: an unfinished fresh-install clean-up ───────────────

    @Test
    fun `an unfinished fresh-install clean-up is never mistaken for an upgrade`() {
        val identity = source("$ios/Services/InstallIdentity.swift")
        val mark = identity.indexOf("defaults.set(true, forKey: pendingKey)")
        val firstStep = identity.indexOf("store.clearSession()")
        assertTrue("the clean-up is not marked pending before it starts", mark in 0 until firstStep)
        assertTrue(
            "the consent record still overrides a pending clean-up",
            identity.contains("if !pending, earlierLaunchKeys.contains("),
        )
        assertTrue(
            "AuthViewModel runs signed in on a session a failed clean-up left behind",
            source("$ios/ViewModels/AuthViewModel.swift").contains("let sessionLive = !install.storedSessionUnusable"),
        )
    }
}
