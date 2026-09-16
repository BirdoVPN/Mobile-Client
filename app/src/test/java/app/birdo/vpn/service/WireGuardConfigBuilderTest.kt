package app.birdo.vpn.service

import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.preferences.AppPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress

/**
 * BirdoShield (D18) — the RESPONSE side of the toggle.
 *
 * PR #403 sent `dnsFiltering: true` and the backend answered with
 * `dns: ["10.13.13.1"]`, the node's own tunnel-gateway resolver (blocky). But
 * `WireGuardConfigBuilder.isValidDnsAddress` rejects every RFC1918 address
 * (`InetAddress.isSiteLocalAddress` is true for 10.13.13.1), so
 * `resolveDnsServers` fell back to 1.1.1.1 and a device with BirdoShield ON
 * resolved exactly like one with it OFF — on every dial path, with no error.
 * The adversarial review of #403 found it; nothing here existed to catch it.
 *
 * These pin the narrow exception that fixes it: a SERVER-provided IPv4 entry
 * in the same /24 as the assigned tunnel address survives, and every other
 * private address (a LAN 10.0.0.53, 192.168.1.1) still does not.
 */
class WireGuardConfigBuilderTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        prefs = mockk(relaxed = true)
        every { prefs.customDnsEnabled } returns false
        every { prefs.customDnsPrimary } returns ""
        every { prefs.customDnsSecondary } returns ""
        every { prefs.wireGuardPort } returns "auto"
        every { prefs.wireGuardMtu } returns 0
        every { prefs.localNetworkSharing } returns false
    }

    @After
    fun tearDown() = unmockkAll()

    private fun response(
        dns: List<String>?,
        assignedIp: String = "10.13.13.7",
    ) = ConnectResponse(
        success = true,
        keyId = "key-1",
        privateKey = null,
        serverPublicKey = null,
        endpoint = "lon-01.birdo.app:51820",
        assignedIp = assignedIp,
        dns = dns,
        allowedIps = listOf("0.0.0.0/0", "::/0"),
    )

    // ── resolveDnsServers: the BirdoShield resolver survives ──────────────

    @Test
    fun `the tunnel-gateway resolver the server hands out survives`() {
        // The exact response the backend's FILTERED_DNS_SERVERS produces.
        val dns = WireGuardConfigBuilder.resolveDnsServers(response(listOf("10.13.13.1")), prefs)
        assertEquals(listOf("10.13.13.1"), dns)
    }

    @Test
    fun `a LAN resolver in 10-8 is still rejected and the fallback applies`() {
        // Not in the client's /24: a home-router resolver, a captive portal's,
        // anything a forged response might point at on the LAN.
        val dns = WireGuardConfigBuilder.resolveDnsServers(response(listOf("10.0.0.53")), prefs)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), dns)
    }

    @Test
    fun `the other RFC1918 and link-local ranges are still rejected`() {
        for (addr in listOf("192.168.1.1", "172.16.0.1", "169.254.169.254", "127.0.0.1", "0.0.0.0", "fd00::1", "fe80::1")) {
            val dns = WireGuardConfigBuilder.resolveDnsServers(response(listOf(addr)), prefs)
            assertEquals("$addr must not be usable", listOf("1.1.1.1", "1.0.0.1"), dns)
        }
    }

    @Test
    fun `the gateway resolver is admitted only for the client's own subnet`() {
        // Same literal, different tunnel: a client on 10.100.0.2 has no
        // business sending DNS to 10.13.13.1 — it would blackhole resolution.
        val dns = WireGuardConfigBuilder.resolveDnsServers(
            response(listOf("10.13.13.1"), assignedIp = "10.100.0.2"), prefs)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), dns)
    }

    @Test
    fun `a public resolver beside the gateway is kept too and order is preserved`() {
        val dns = WireGuardConfigBuilder.resolveDnsServers(
            response(listOf("10.13.13.1", "1.1.1.1", "10.0.0.53")), prefs)
        assertEquals(listOf("10.13.13.1", "1.1.1.1"), dns)
    }

    @Test
    fun `the OFF case is unchanged — Cloudflare from the server is passed through`() {
        val dns = WireGuardConfigBuilder.resolveDnsServers(response(listOf("1.1.1.1", "1.0.0.1")), prefs)
        assertEquals(listOf("1.1.1.1", "1.0.0.1"), dns)
    }

    @Test
    fun `custom DNS never gets the gateway exception`() {
        // The exception is for what the SERVER hands out. A user typing the
        // gateway address into custom DNS gets the same rejection as any
        // private address — and custom DNS also overrides the server list.
        every { prefs.customDnsEnabled } returns true
        every { prefs.customDnsPrimary } returns "10.13.13.1"
        every { prefs.customDnsSecondary } returns "9.9.9.9"
        val dns = WireGuardConfigBuilder.resolveDnsServers(response(listOf("10.13.13.1")), prefs)
        assertEquals(listOf("9.9.9.9"), dns)
    }

    // ── isTunnelGatewayResolver: the rule itself ──────────────────────────

    @Test
    fun `the rule is IPv4 literal, same slash-24, a real host, not self`() {
        val assigned = "10.13.13.7"
        assertTrue(WireGuardConfigBuilder.isTunnelGatewayResolver("10.13.13.1", assigned))
        assertTrue(WireGuardConfigBuilder.isTunnelGatewayResolver("10.13.13.254", assigned))
        // Network and broadcast hosts are not resolvers.
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("10.13.13.0", assigned))
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("10.13.13.255", assigned))
        // The client's own address is not a resolver.
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("10.13.13.7", assigned))
        // Adjacent /24s are someone else's network.
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("10.13.12.1", assigned))
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("10.13.14.1", assigned))
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("10.0.0.53", assigned))
        // Never a hostname (no lookup), never IPv6, never garbage.
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("dns.birdo.app", assigned))
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("fd00:b1d0::1", assigned))
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("10.13.13", assigned))
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("10.13.13.1.1", assigned))
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("10.13.13.256", assigned))
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("", assigned))
        // No assigned address, nothing to be the gateway of.
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("10.13.13.1", null))
        assertFalse(WireGuardConfigBuilder.isTunnelGatewayResolver("10.13.13.1", "not-an-ip"))
    }

    // ── pinnedResolverRoutes: the local-network-sharing leak guard ────────

    @Test
    fun `the gateway resolver gets a slash-32 route and public resolvers get none`() {
        assertEquals(
            listOf("10.13.13.1/32"),
            WireGuardConfigBuilder.pinnedResolverRoutes(listOf("10.13.13.1"), "10.13.13.7"),
        )
        assertEquals(
            emptyList<String>(),
            WireGuardConfigBuilder.pinnedResolverRoutes(listOf("1.1.1.1", "1.0.0.1"), "10.13.13.7"),
        )
        assertEquals(
            listOf("10.13.13.1/32"),
            WireGuardConfigBuilder.pinnedResolverRoutes(listOf("10.13.13.1", "1.1.1.1"), "10.13.13.7"),
        )
        // A rejected LAN address never made it into the resolved list, but if
        // it did it must not be routed into the tunnel either.
        assertEquals(
            emptyList<String>(),
            WireGuardConfigBuilder.pinnedResolverRoutes(listOf("10.0.0.53"), "10.13.13.7"),
        )
    }

    // ── build(): the wg-go config carries the resolver ─────────────────────

    private fun stubAndroidBase64() {
        // android.util.Base64 is a stub in unit tests (returns null); back it
        // with java.util.Base64 so the key validation runs for real.
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
    }

    private fun keyedResponse(dns: List<String>): ConnectResponse {
        val client = com.wireguard.crypto.KeyPair()
        val server = com.wireguard.crypto.KeyPair()
        return response(dns).copy(
            privateKey = client.privateKey.toBase64(),
            serverPublicKey = server.publicKey.toBase64(),
        )
    }

    @Test
    fun `the wg-go interface carries the gateway resolver as its DNS`() {
        stubAndroidBase64()

        val config = WireGuardConfigBuilder.build(keyedResponse(listOf("10.13.13.1")), prefs)

        assertEquals(setOf(InetAddress.getByName("10.13.13.1")), config.getInterface().dnsServers)
    }

    @Test
    fun `the wg-go interface falls back to Cloudflare for a LAN resolver`() {
        stubAndroidBase64()

        val config = WireGuardConfigBuilder.build(keyedResponse(listOf("10.0.0.53")), prefs)

        assertEquals(
            setOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1")),
            config.getInterface().dnsServers,
        )
    }

    // ── BirdoVpnService: the OS-resolver path and the route pin ───────────

    private val serviceSource: String by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        File(dir, "app/src/main/java/app/birdo/vpn/service/BirdoVpnService.kt").readText()
    }

    /**
     * `buildVpnInterface` is a private method on an Android Service, so the
     * route set it builds cannot be instantiated here. What CAN be pinned is
     * the shape the leak needs: the local-network-sharing branch deliberately
     * skips 10.0.0.0/8, so the resolver admitted above would be routed to the
     * LAN unless that same branch pins it with a /32 — from the SAME resolved
     * list the OS resolver is configured with.
     */
    @Test
    fun `the local-network-sharing route set pins the gateway resolver back into the tunnel`() {
        val lnsStart = serviceSource.indexOf("if (appPrefs.localNetworkSharing) {")
        assertTrue("the LNS branch must exist", lnsStart > 0)
        val lnsEnd = serviceSource.indexOf("Local network sharing enabled", lnsStart)
        assertTrue("the LNS branch must end with its log line", lnsEnd > lnsStart)
        val lnsBranch = serviceSource.substring(lnsStart, lnsEnd)

        assertTrue("the LNS branch still leaves 10.0.0.0/8 to the LAN", lnsBranch.contains("Skip 10.0.0.0/8"))
        assertTrue(
            "the LNS branch must pin the gateway resolver with a /32 via pinnedResolverRoutes",
            lnsBranch.contains("WireGuardConfigBuilder.pinnedResolverRoutes(tunnelDns, config.assignedIp)"),
        )
        // The pin must be computed from the SAME list the OS resolver gets.
        val dnsResolved = serviceSource.indexOf("val tunnelDns = WireGuardConfigBuilder.resolveDnsServers(config, appPrefs)")
        assertTrue("the OS-resolver DNS list must be captured as tunnelDns", dnsResolved in 1 until lnsStart)
        assertTrue(
            "the OS resolver must be fed from tunnelDns",
            serviceSource.substring(dnsResolved, lnsStart).contains("for (dns in tunnelDns)"),
        )
    }
}
