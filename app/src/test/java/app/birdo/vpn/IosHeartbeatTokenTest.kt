package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Audit 2026-09-29, D-13: the iOS/macOS app put the bearer access token into
 * the tunnel profile's `providerConfiguration` — stored by NetworkExtension in
 * the system VPN preferences — and nothing removed it, or the connection key
 * id beside it, on sign-out or account deletion.
 *
 * Now the token is parked in the shared keychain group the PacketTunnel
 * extension already reads the WireGuard keys from, and passed by reference;
 * the persisted profile is scrubbed on sign-out/deletion. The heartbeat still
 * works, including for a profile written by an older host build.
 *
 * Reads Swift for the usual reason: iOS never builds in PR CI.
 */
class IosHeartbeatTokenTest {

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
        return file.readText()
    }

    private val manager by lazy { source("iosApp/iosApp/Services/VPNManager.swift") }
    private val tunnel by lazy { source("iosApp/PacketTunnel/PacketTunnelProvider.swift") }

    @Test
    fun `the host never writes the token itself into the profile`() {
        val writes = Regex("""\[\s*"hb-access-token"\s*]\s*=""").findAll(manager).count()
        assertEquals(
            "VPNManager assigns [\"hb-access-token\"] again — the bearer token would be persisted " +
                "in the system VPN preferences (D-13). Pass hb-access-token-ref instead.",
            0,
            writes,
        )
        assertTrue(manager.contains("\"hb-access-token-ref\": Self.heartbeatTokenAccount"))
        assertTrue(
            "connect / swapConfig / restoreProfile must all park the token by reference",
            Regex("""parkHeartbeatToken\(\)""").findAll(manager).count() >= 3,
        )
    }

    @Test
    fun `the parked token dies with the session`() {
        val disconnect = manager.substringAfter("func disconnect() {").substringBefore("guard let mgr = manager")
        assertTrue(
            "disconnect() must delete the parked heartbeat token with the WireGuard keys",
            disconnect.contains("deleteSharedSecret(account: Self.heartbeatTokenAccount)"),
        )
        val keychain = source("iosApp/iosApp/Services/KeychainService.swift")
        assertTrue(
            "KeychainService.clear() (sign-out) must wipe the parked token too",
            Regex("""clearAllSharedSecrets\(\)\s*\{[^}]*"hb_access_token"""").containsMatchIn(keychain),
        )
        assertTrue(manager.contains("static let heartbeatTokenAccount = \"hb_access_token\""))
    }

    @Test
    fun `sign-out and deletion scrub the persisted profile`() {
        val auth = source("iosApp/iosApp/ViewModels/AuthViewModel.swift")
        val local = auth.substringAfter("private func completeLocalLogout() {").substringBefore("isLoggedIn = false")
        assertTrue(local.contains("VPNManager.shared.scrubHeartbeatCredentials()"))
        assertTrue(
            manager.contains(
                "private static let heartbeatProfileKeys = [\"hb-access-token\", \"hb-access-token-ref\", \"hb-key-id\"]",
            ),
        )
    }

    @Test
    fun `the extension reads the token by reference and still honours an old profile`() {
        assertTrue(tunnel.contains("ref: proto.providerConfiguration?[\"hb-access-token-ref\"] as? String"))
        assertTrue(tunnel.contains("legacy: proto.providerConfiguration?[\"hb-access-token\"] as? String"))
        assertTrue(tunnel.contains("ref: message[\"hb-access-token-ref\"] as? String"))
        assertTrue(tunnel.contains("readSharedKeychain(account: name)"))
        assertFalse(
            "the extension must not log the token",
            Regex("""os_log\([^)]*token""", RegexOption.IGNORE_CASE).containsMatchIn(
                tunnel.substringAfter("private func resolveHeartbeatToken").substringBefore("private func readSharedKeychain"),
            ),
        )
    }
}
