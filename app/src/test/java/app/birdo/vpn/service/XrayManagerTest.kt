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

    // ── REVIEW-AND2-014: the call site, not only the helpers ──────────────

    /**
     * wireGuardTarget and buildXrayConfig are pinned above; this pins what
     * start() hands them, by reading the config start() actually launches.
     * Passing anything but the server's own endpoint (the local relay the
     * service substitutes once Xray is up, or loopback) fails here.
     */
    @Test
    fun `start forwards to the node's own WireGuard endpoint from the server's reply`() = kotlinx.coroutines.test.runTest {
        var launched: String? = null
        val pickPort = XrayManager.pickLocalPort
        val launch = XrayManager.launch
        XrayManager.pickLocalPort = { 51821 }
        XrayManager.launch = { _, configJson -> launched = configJson; true }
        try {
            val server = app.birdo.vpn.data.model.ConnectResponse(
                success = true,
                endpoint = "203.0.113.7:51820",
                xrayEndpoint = "203.0.113.7:443",
                xrayUuid = "123e4567-e89b-12d3-a456-426614174000",
                xrayPublicKey = "A".repeat(43),
                xrayShortId = "abcd",
                xraySni = "www.example.com",
            )

            assertTrue(XrayManager.start(io.mockk.mockk(relaxed = true), server, "auto") {})

            val config = org.json.JSONObject(launched!!)
            val forward = config.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
            assertEquals("203.0.113.7", forward.getString("address"))
            assertEquals(51820, forward.getInt("port"))
            val reality = config.getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            assertEquals("203.0.113.7", reality.getString("address"))
            assertEquals(443, reality.getInt("port"))
        } finally {
            XrayManager.pickLocalPort = pickPort
            XrayManager.launch = launch
            XrayManager.stop()
        }
    }

    /** The service hands start() the SERVER's reply, and points WireGuard at the relay only after. */
    @Test
    fun `the service starts Xray from the server's reply before it substitutes the local relay`() {
        var dir = java.io.File("").absoluteFile
        while (!java.io.File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("no repo root above $dir")
        val service = java.io.File(dir, "app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt")
            .readText().replace("\r\n", "\n")
        val start = service.indexOf("XrayManager.start(applicationContext, config, appPrefs.wireGuardPort)")
        val relay = service.indexOf("stealthEndpointOverride = \"127.0.0.1:")
        val activeConfig = service.indexOf("val config = activeConfig")
        assertTrue("the Stealth start no longer passes the server's own reply", start > 0)
        assertTrue("`config` is no longer the server's reply where Xray starts", activeConfig in 1 until start)
        assertTrue("the local relay is substituted before Xray is started", relay > start)
    }
}
