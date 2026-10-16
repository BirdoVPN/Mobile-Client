package app.birdo.vpn.data.network

import app.birdo.vpn.data.api.AuthInterceptor
import app.birdo.vpn.di.NetworkModule
import io.mockk.every
import io.mockk.mockk
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * MR-949: inside a tunnel to a node without IPv6, IPv6 is blackholed, so the
 * tunnel client must not sit out its 10 s connect timeout on the first
 * address it tries when that address goes nowhere. Fast fallback (Happy
 * Eyeballs) races the next address 250 ms later instead.
 */
class TunnelClientFallbackTest {

    /** Passes every request straight through, as AuthInterceptor does once it has added its headers. */
    private val passThrough = mockk<AuthInterceptor> {
        every { intercept(any()) } answers {
            val chain = firstArg<Interceptor.Chain>()
            chain.proceed(chain.request())
        }
    }

    private fun tunnelClient(): OkHttpClient = NetworkModule.provideTunnelClient(passThrough)

    @Test
    fun `the tunnel client races addresses instead of trying them one by one`() {
        val client = tunnelClient()
        assertTrue("the tunnel client lost fast fallback (Happy Eyeballs)", client.fastFallback)
        assertEquals("the stall this guards against is one connect timeout", 10_000, client.connectTimeoutMillis)
    }

    /**
     * The first address is a black hole (TEST-NET-1 drops or refuses), the
     * second answers. One connect timeout would be 10 s; the call must take a
     * fraction of that.
     */
    @Test
    fun `a call whose first address is a black hole still completes promptly`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serving = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (input.readLine()?.isNotEmpty() == true) { /* request head */ }
                        socket.getOutputStream().write(
                            "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                        )
                        socket.getOutputStream().flush()
                    }
                }
            }
            val blackHoleFirst = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(
                    InetAddress.getByAddress(hostname, byteArrayOf(192.toByte(), 0, 2, 1)),
                    InetAddress.getByAddress(hostname, byteArrayOf(127, 0, 0, 1)),
                )
            }
            // Only the resolver is swapped; fast fallback and the timeouts are the app's.
            val client = tunnelClient().newBuilder().dns(blackHoleFirst).build()
            val started = System.nanoTime()
            client.newCall(Request.Builder().url("http://api.birdo.app:${server.localPort}/ping").build())
                .execute().use { response -> assertEquals(204, response.code) }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("the call took $elapsedMs ms: it waited on the black hole", elapsedMs < 5_000)
            serving.join(5_000)
        }
    }
}
