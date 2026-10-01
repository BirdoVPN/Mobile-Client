package app.birdo.vpn.service

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [XrayManager] — exercises companion object state
 * management and validates precondition checking for start/stop lifecycle.
 *
 * Full start() testing requires Android context and native Xray binary,
 * so these tests focus on the state machine and edge cases.
 */
class XrayManagerTest {

    @Before
    fun setup() {
        XrayManager.stop()
    }

    @Test
    fun `initial state - not active, port 0`() {
        assertFalse(XrayManager.isActive())
        assertEquals(0, XrayManager.getLocalPort())
    }

    @Test
    fun `stop when already stopped is no-op`() {
        XrayManager.stop()
        assertFalse(XrayManager.isActive())
    }

    @Test
    fun `getLocalPort returns 0 when not active`() {
        assertEquals(0, XrayManager.getLocalPort())
    }

    @Test
    fun `parseEndpoint extracts host and port from IPv4 endpoint`() {
        // Use reflection to test private parseEndpoint
        val method = XrayManager::class.java.getDeclaredMethod(
            "parseEndpoint", String::class.java
        )
        method.isAccessible = true
        val result = method.invoke(XrayManager, "1.2.3.4:443") as? Pair<*, *>
        assertNotNull(result)
        assertEquals("1.2.3.4", result?.first)
        assertEquals(443, result?.second)
    }

    @Test
    fun `parseEndpoint extracts host and port from IPv6 endpoint`() {
        val method = XrayManager::class.java.getDeclaredMethod(
            "parseEndpoint", String::class.java
        )
        method.isAccessible = true
        val result = method.invoke(XrayManager, "[::1]:8443") as? Pair<*, *>
        assertNotNull(result)
        assertEquals("::1", result?.first)
        assertEquals(8443, result?.second)
    }

    @Test
    fun `parseEndpoint returns null for invalid endpoint`() {
        val method = XrayManager::class.java.getDeclaredMethod(
            "parseEndpoint", String::class.java
        )
        method.isAccessible = true
        val result = method.invoke(XrayManager, "invalid")
        assertNull(result)
    }

    @Test
    fun `findAvailablePort returns port in expected range`() {
        val method = XrayManager::class.java.getDeclaredMethod(
            "findAvailablePort", Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        val port = method.invoke(XrayManager, 51821) as Int
        assertTrue("Port should be > 0", port > 0)
        assertTrue("Port should be <= 65535", port <= 65535)
    }

    // ── LIVE-AND-STEALTH-001: where the server's Xray forwards ──────────

    @Test
    fun `the forward targets the node's own WireGuard endpoint, never loopback`() {
        // Since Xray 26 the server refuses loopback destinations for a VLESS
        // inbound: a hard-coded 127.0.0.1:51820 passed no traffic on any relay.
        assertEquals("203.0.113.7" to 51820, XrayManager.wireGuardTarget("203.0.113.7:8443", "203.0.113.7:51820", "auto"))
        // A node on another WireGuard port.
        assertEquals("203.0.113.7" to 443, XrayManager.wireGuardTarget("203.0.113.7:8443", "203.0.113.7:443", "auto"))
        // The user's custom WireGuard port wins, as on desktop.
        assertEquals("203.0.113.7" to 53, XrayManager.wireGuardTarget("203.0.113.7:8443", "203.0.113.7:51820", "53"))
        // IPv6.
        assertEquals("2001:db8::7" to 51820, XrayManager.wireGuardTarget("[2001:db8::7]:8443", "[2001:db8::7]:51820", "auto"))
        // Nothing to derive from: refuse, never guess 51820.
        assertNull(XrayManager.wireGuardTarget("203.0.113.7:8443", null, "auto"))
        assertNull(XrayManager.wireGuardTarget(null, "203.0.113.7:51820", "auto"))
        // An out-of-range override is ignored, like "auto".
        assertEquals("203.0.113.7" to 51820, XrayManager.wireGuardTarget("203.0.113.7:8443", "203.0.113.7:51820", "70000"))
    }

    @Test
    fun `the generated config forwards to the target it is given`() {
        fun dokodemo(targetHost: String, targetPort: Int): org.json.JSONObject {
            val json = org.json.JSONObject(
                XrayManager.buildXrayConfig(
                    localPort = 51821,
                    serverHost = "203.0.113.7",
                    serverPort = 8443,
                    uuid = "00000000-0000-0000-0000-000000000000",
                    publicKey = "a".repeat(43),
                    shortId = "abcd",
                    sni = "www.example.com",
                    targetHost = targetHost,
                    targetPort = targetPort,
                ),
            )
            return json.getJSONArray("inbounds").getJSONObject(0)
        }
        val inbound = dokodemo("203.0.113.7", 51820)
        assertEquals("dokodemo-door", inbound.getString("protocol"))
        assertEquals("127.0.0.1", inbound.getString("listen"))
        val settings = inbound.getJSONObject("settings")
        assertEquals("203.0.113.7", settings.getString("address"))
        assertEquals(51820, settings.getInt("port"))
        assertEquals("udp", settings.getString("network"))
        val custom = dokodemo("203.0.113.7", 53).getJSONObject("settings")
        assertEquals(53, custom.getInt("port"))
        val v6 = dokodemo("2001:db8::7", 51820).getJSONObject("settings")
        assertEquals("2001:db8::7", v6.getString("address"))
        assertFalse(v6.getString("address").startsWith("127."))
    }
}
