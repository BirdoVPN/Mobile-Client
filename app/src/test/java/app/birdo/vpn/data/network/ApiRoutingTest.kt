package app.birdo.vpn.data.network

import app.birdo.vpn.di.NetworkModule
import app.birdo.vpn.service.ApiRoutePolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

/**
 * D-6: the plumbing under ApiRoutePolicy — which client a call goes to, and
 * that a bypass socket really is protected before it connects.
 */
class ApiRoutingTest {

    private val request = Request.Builder().url("https://api.birdo.app/vpn/heartbeat/k").build()

    @After
    fun tearDown() {
        // Never leave a protector installed for other tests.
        BypassSockets.uninstall(installed ?: return)
    }

    private var installed: ((Socket) -> Boolean)? = null

    private fun client(call: Call, pool: ConnectionPool): OkHttpClient = mockk {
        every { newCall(any()) } returns call
        every { connectionPool } returns pool
    }

    @Test
    fun `each call goes to the client its path names`() {
        val tunnelCall = mockk<Call>()
        val bypassCall = mockk<Call>()
        var bypass = true
        val factory = RoutingCallFactory(client(tunnelCall, mockk(relaxed = true)), client(bypassCall, mockk(relaxed = true))) { bypass }

        assertSame(bypassCall, factory.newCall(request))
        bypass = false
        assertSame(tunnelCall, factory.newCall(request))
    }

    @Test
    fun `changing path drops the idle connections of the client no longer used`() {
        val tunnelPool = mockk<ConnectionPool>(relaxed = true)
        val bypassPool = mockk<ConnectionPool>(relaxed = true)
        var bypass = true
        val factory = RoutingCallFactory(client(mockk(), tunnelPool), client(mockk(), bypassPool)) { bypass }

        factory.newCall(request)
        factory.newCall(request)
        verify(exactly = 0) { bypassPool.evictAll() }

        // The tunnel came up: nothing may stay open around it.
        bypass = false
        factory.newCall(request)
        verify(exactly = 1) { bypassPool.evictAll() }

        // The tunnel died: its pooled connections are dead with it.
        bypass = true
        factory.newCall(request)
        verify(exactly = 1) { tunnelPool.evictAll() }
    }

    /**
     * REVIEW-AND2-001/-002: an account deletion and the dead-tunnel probe are
     * answered only off the peer the server removes, so they go around a LIVE
     * tunnel too — without moving the calls after them off it, and without
     * leaving what they opened around it open for long.
     */
    @Test
    fun `a call tagged AroundTunnel goes around a live tunnel, and the next tunnel call drops what it opened`() {
        val tunnelCall = mockk<Call>()
        val bypassCall = mockk<Call>()
        val tunnelPool = mockk<ConnectionPool>(relaxed = true)
        val bypassPool = mockk<ConnectionPool>(relaxed = true)
        val dohPool = mockk<ConnectionPool>(relaxed = true)
        val factory = RoutingCallFactory(
            client(tunnelCall, tunnelPool),
            client(bypassCall, bypassPool),
            alsoAroundTunnel = listOf(dohPool),
        ) { false }
        val aroundTunnel = request.newBuilder().tag(AroundTunnel::class.java, AroundTunnel).build()

        assertSame(tunnelCall, factory.newCall(request))
        assertSame(bypassCall, factory.newCall(aroundTunnel))
        // The live tunnel's own connections are untouched by it.
        verify(exactly = 0) { tunnelPool.evictAll() }

        assertSame(tunnelCall, factory.newCall(request))
        verify(exactly = 1) { bypassPool.evictAll() }
        verify(exactly = 1) { dohPool.evictAll() }
        verify(exactly = 0) { tunnelPool.evictAll() }
    }

    /**
     * F3, live on the emulator (2026-10-01): one idle DoH connection,
     * `wlan0 -> 1.1.1.1:443`, stayed ESTABLISHED outside the tunnel for the
     * whole session. DoH's pool now goes with the bypass client's.
     */
    @Test
    fun `the tunnel coming up drops the DoH connections too, and the tunnel going down leaves them`() {
        val dohPool = mockk<ConnectionPool>(relaxed = true)
        var bypass = true
        val factory = RoutingCallFactory(
            client(mockk(), mockk(relaxed = true)),
            client(mockk(), mockk(relaxed = true)),
            alsoAroundTunnel = listOf(dohPool),
        ) { bypass }

        factory.newCall(request)
        verify(exactly = 0) { dohPool.evictAll() }

        bypass = false
        factory.newCall(request)
        verify(exactly = 1) { dohPool.evictAll() }

        // Back around a dead tunnel: DoH is what resolves now, so it stays.
        bypass = true
        factory.newCall(request)
        verify(exactly = 1) { dohPool.evictAll() }
    }

    /** The same, through the factory the app actually builds (NetworkModule). */
    @Test
    fun `the app's call factory is wired to drop the DoH connections`() {
        val dohPool = mockk<ConnectionPool>(relaxed = true)
        mockkObject(DohResolver, ApiRoutePolicy)
        try {
            every { DohResolver.connectionPool } returns dohPool
            var path = ApiRoutePolicy.Path.BYPASS
            every { ApiRoutePolicy.currentPath() } answers { path }
            val factory = NetworkModule.provideRetrofit(
                client(mockk(), mockk(relaxed = true)),
                client(mockk(), mockk(relaxed = true)),
            ).callFactory()

            factory.newCall(request)
            path = ApiRoutePolicy.Path.TUNNEL
            factory.newCall(request)

            verify(exactly = 1) { dohPool.evictAll() }
        } finally {
            unmockkObject(DohResolver, ApiRoutePolicy)
        }
    }

    @Test
    fun `a bypass socket is protected before anything connects it`() {
        val protectedSockets = mutableListOf<Socket>()
        val socket = ProtectingSocketFactory { protectedSockets += it; true }.createSocket()
        assertSame(socket, protectedSockets.single())
        assertFalse("handed out unconnected, for OkHttp to connect", socket.isConnected)
        socket.close()
    }

    @Test
    fun `with no service there is nothing to go around, and a refused protect is reported as false`() {
        Socket().use { assertTrue("no tunnel of ours: the default route is already physical", BypassSockets.protect(it)) }

        val refuse: (Socket) -> Boolean = { false }
        installed = refuse
        BypassSockets.install(refuse)
        Socket().use { assertFalse(BypassSockets.protect(it)) }

        BypassSockets.uninstall(refuse)
        Socket().use { assertTrue(BypassSockets.protect(it)) }
    }

    @Test
    fun `a stale service cannot remove its successor's protector`() {
        val old: (Socket) -> Boolean = { true }
        val current: (Socket) -> Boolean = { false }
        BypassSockets.install(old)
        BypassSockets.install(current)
        installed = current
        BypassSockets.uninstall(old)
        Socket().use { assertFalse("the current service's protect is still the one used", BypassSockets.protect(it)) }
    }
}
