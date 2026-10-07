package app.birdo.vpn.service

import app.birdo.vpn.service.ApiRoutePolicy.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D-6 (A1-016): the rules that decide which of the device's traffic rides the
 * tunnel. Pure, so each is a row here rather than a device session.
 */
class TunnelRoutingTest {

    private val own = "app.birdo.vpn"

    // ── Who is excluded from the tunnel ─────────────────────────────────

    @Test
    fun `the app is inside its own tunnel except while Stealth runs Xray`() {
        assertEquals(emptyList<String>(), TunnelAppRules.disallowedPackages(own, false, false, emptySet()))
        assertEquals(listOf(own), TunnelAppRules.disallowedPackages(own, true, false, emptySet()))
    }

    @Test
    fun `split-tunnel apps stay excluded, and a persisted list can never exclude the app itself`() {
        val apps = setOf("com.bank", own, "com.bank", "")
        assertEquals(listOf("com.bank"), TunnelAppRules.disallowedPackages(own, false, true, apps))
        assertEquals(listOf(own, "com.bank"), TunnelAppRules.disallowedPackages(own, true, true, apps))
        // Split tunnelling off: the list is ignored entirely.
        assertEquals(emptyList<String>(), TunnelAppRules.disallowedPackages(own, false, false, apps))
    }

    @Test
    fun `the split-tunnel picker never offers BirdoVPN itself`() {
        assertFalse(TunnelAppRules.selectableForSplitTunnel(own, own))
        assertTrue(TunnelAppRules.selectableForSplitTunnel("com.bank", own))
    }

    // ── Xray's server carved out of the routes ──────────────────────────

    @Test
    fun `API 33 and later exclude the route, earlier levels split the table`() {
        assertEquals(XrayCarveOut.Method.EXCLUDE_ROUTE, XrayCarveOut.method(33))
        assertEquals(XrayCarveOut.Method.EXCLUDE_ROUTE, XrayCarveOut.method(36))
        assertEquals(XrayCarveOut.Method.ROUTE_TABLE, XrayCarveOut.method(32))
        assertEquals(XrayCarveOut.Method.ROUTE_TABLE, XrayCarveOut.method(29))
    }

    @Test
    fun `only an IPv4 literal is carved, never a name that would need a lookup`() {
        assertEquals("203.0.113.7", XrayCarveOut.serverIpv4("203.0.113.7:8443"))
        assertEquals(null, XrayCarveOut.serverIpv4("node.birdo.app:8443"))
        assertEquals(null, XrayCarveOut.serverIpv4("[2001:db8::1]:8443"))
        assertEquals(null, XrayCarveOut.serverIpv4("203.0.113.7"))
        assertEquals(null, XrayCarveOut.serverIpv4(null))
    }

    @Test
    fun `the default route minus the server covers every other address and not the server`() {
        val routes = XrayCarveOut.split("0.0.0.0/0", "203.0.113.7")
        assertEquals(32, routes.size)
        assertFalse("the server stays outside", routes.any { contains(it, "203.0.113.7") })
        listOf("203.0.113.6", "203.0.113.8", "1.1.1.1", "10.13.13.1", "255.255.255.255", "0.0.0.0", "203.0.112.7")
            .forEach { address ->
                assertEquals("$address is covered exactly once", 1, routes.count { contains(it, address) })
            }
    }

    @Test
    fun `a route that does not contain the server, or an IPv6 route, is left alone`() {
        assertEquals(listOf("10.0.0.0/8"), XrayCarveOut.split("10.0.0.0/8", "203.0.113.7"))
        assertEquals(listOf("::/0"), XrayCarveOut.split("::/0", "203.0.113.7"))
        assertEquals(emptyList<String>(), XrayCarveOut.split("203.0.113.7/32", "203.0.113.7"))
        // A route from the Local Network Sharing table that holds the server.
        val carved = XrayCarveOut.split("192.0.0.0/9", "192.1.2.3")
        assertEquals(23, carved.size)
        assertFalse(carved.any { contains(it, "192.1.2.3") })
        assertTrue(carved.any { contains(it, "192.1.2.4") })
    }

    private fun contains(cidr: String, address: String): Boolean {
        fun ip(s: String) = s.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
        val prefix = cidr.substringAfter('/').toInt()
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (ip(cidr.substringBefore('/')) and mask) == (ip(address) and mask)
    }

    // ── Which client the app's API calls use ────────────────────────────

    @Test
    fun `the API rides the tunnel only while a verified tunnel is up`() {
        assertEquals(Path.TUNNEL, ApiRoutePolicy.pathFor(VpnState.Connected, blockActive = false, stealthActive = false))
    }

    @Test
    fun `every other state goes around it`() {
        val around = listOf(
            // Bootstrap: no tunnel yet (sign-in, the server list, the first /connect).
            VpnState.Disconnected,
            // The probe window: established, not yet carrying traffic.
            VpnState.Connecting,
            VpnState.StealthConnecting,
            // VpnManager tearing it down (a switch, a reap) before the service has.
            VpnState.Disconnecting,
            // The reconnect path, and behind the block.
            VpnState.Reconnecting(2),
            VpnState.Reconnecting(1, waitingForNetwork = true),
            VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE),
            VpnState.KillSwitchActive,
        )
        around.forEach { state ->
            assertEquals("$state", Path.BYPASS, ApiRoutePolicy.pathFor(state, blockActive = false, stealthActive = false))
        }
        // Connected, but the block is up, or Stealth keeps the app outside the
        // tunnel (the bypass client keeps its lookups on DoH).
        assertEquals(Path.BYPASS, ApiRoutePolicy.pathFor(VpnState.Connected, blockActive = true, stealthActive = false))
        assertEquals(Path.BYPASS, ApiRoutePolicy.pathFor(VpnState.Connected, blockActive = false, stealthActive = true))
    }

    // ── wg-go's own sockets ─────────────────────────────────────────────

    @Test
    fun `the tunnel starts only when every socket wg-go opened is protected`() {
        assertTrue(TunnelSocketProtection.complete(v4Fd = 41, v6Fd = 42, v4Protected = true, v6Protected = true))
        assertTrue(TunnelSocketProtection.complete(v4Fd = 41, v6Fd = -1, v4Protected = true, v6Protected = false))
        assertFalse("no socket at all", TunnelSocketProtection.complete(-1, -1, false, false))
        assertFalse("v4 open, unprotected", TunnelSocketProtection.complete(41, 42, false, true))
        assertFalse("v6 open, unprotected", TunnelSocketProtection.complete(41, 42, true, false))
    }
}
