package app.birdo.vpn.service

import app.birdo.vpn.data.model.ConnectResponse
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
}
