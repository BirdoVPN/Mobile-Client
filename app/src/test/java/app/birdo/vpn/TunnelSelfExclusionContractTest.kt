package app.birdo.vpn

import app.birdo.vpn.service.TunnelAppRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D-6 (A1-016), pinned the way ApiLevel36ContractTest pins the manifest: by
 * reading the REAL sources, so the edit that undoes it breaks the build.
 *
 * Until the 2026-09-30 overhaul BirdoVPN excluded its own app from its own
 * tunnel with one unconditional `addDisallowedApplication(packageName)`, and
 * every API call, the heartbeat (bearer token + key id), DoH and crash reports
 * left from the user's real IP. That line is exactly the kind a merge or a
 * "harmless" cleanup puts back, and nothing would look different on screen.
 *
 * The contract: the app is NOT excluded from the tunnel, except while Stealth
 * runs Xray as a child process; it IS excluded from the kill-switch block.
 */
class TunnelSelfExclusionContractTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above " + File("").absolutePath)
        }
        dir
    }

    private fun read(relativePath: String, sentinel: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue("$relativePath does not exist", file.isFile)
        val text = file.readText()
        assertTrue("$relativePath lacks [$sentinel]; these scans would be vacuous", text.contains(sentinel))
        return text
    }

    private val service: String
        get() = read("app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt", "class BirdoVpnService : VpnService()")

    /** The body of `private fun <name>(` up to the next member at the same indent. */
    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is gone", start >= 0)
        val end = source.indexOf("\n    private fun ", start + signature.length).takeIf { it > 0 } ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun `the tunnel excludes the app only through the stealth rule`() {
        val build = body(service, "private fun buildVpnInterface(")
        assertFalse(
            "buildVpnInterface calls addDisallowedApplication(packageName) directly again. That takes " +
                "BirdoVPN's own traffic out of its tunnel for every session (D-6). Exclusions go through " +
                "TunnelAppRules.disallowedPackages, which excludes the app only while Stealth is active.",
            Regex("""addDisallowedApplication\(\s*packageName\s*\)""").containsMatchIn(build),
        )
        assertTrue(
            "buildVpnInterface no longer takes its exclusions from TunnelAppRules.disallowedPackages",
            build.contains("TunnelAppRules.disallowedPackages("),
        )
        // The rule itself: never the app outside Stealth, whatever the split list says.
        assertFalse(
            "app.birdo.vpn" in TunnelAppRules.disallowedPackages(
                ownPackage = "app.birdo.vpn",
                stealthActive = false,
                splitTunnelEnabled = true,
                splitTunnelApps = setOf("app.birdo.vpn", "com.example"),
            ),
        )
        assertTrue(
            "app.birdo.vpn" in TunnelAppRules.disallowedPackages("app.birdo.vpn", true, false, emptySet()),
        )
    }

    @Test
    fun `the kill-switch block still excludes the app`() {
        val block = body(service, "private fun activateKillSwitch(")
        assertTrue(
            "the block no longer excludes BirdoVPN: sign-in, /connect and the reconnect path would be " +
                "blocked by the app's own kill switch",
            block.contains(".addDisallowedApplication(packageName)"),
        )
    }

    @Test
    fun `nothing tracks the default network, which is the VPN itself once the app is inside it`() {
        val monitor = read("app/src/main/java/app/birdo/vpn/data/network/NetworkMonitor.kt", "class NetworkMonitor")
        listOf("BirdoVpnService.kt" to service, "NetworkMonitor.kt" to monitor).forEach { (name, text) ->
            assertFalse(
                "$name registers a default-network callback again. With the app inside its own tunnel " +
                    "that network is the VPN: the airplane-mode offline edge never arrives (seen live, " +
                    "2026-09-30). Track NOT_VPN networks (NetworkMonitor.underlyingNetworkRequest).",
                text.contains("registerDefaultNetworkCallback("),
            )
        }
        assertTrue(service.contains("NetworkMonitor.underlyingNetworkRequest()"))
    }

    @Test
    fun `the split-tunnel picker hides BirdoVPN`() {
        val picker = read(
            "app/src/main/java/app/birdo/vpn/ui/viewmodel/SettingsViewModel.kt",
            "fun loadInstalledApps()",
        )
        assertTrue(picker.contains("TunnelAppRules.selectableForSplitTunnel(app.packageName, ownPackage)"))
    }
}
