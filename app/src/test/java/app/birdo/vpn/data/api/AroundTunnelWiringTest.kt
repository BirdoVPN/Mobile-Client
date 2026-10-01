package app.birdo.vpn.data.api

import app.birdo.vpn.data.network.AroundTunnel
import app.birdo.vpn.data.network.RoutingCallFactory
import app.birdo.vpn.di.NetworkModule
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import java.io.IOException

/**
 * REVIEW-AND2-001/-002, end to end through Retrofit: the BirdoApi methods the
 * fixes rely on put the [AroundTunnel] tag on the request, and the app's call
 * factory sends it to the bypass client while the session is Connected (the
 * TUNNEL path). The unit tests below the repository mock BirdoApi, so without
 * this a `@Tag` dropped from an interface method would pass everything.
 */
class AroundTunnelWiringTest {

    private val tunnelRequests = mutableListOf<Request>()
    private val bypassRequests = mutableListOf<Request>()

    /** A call that fails at once, so the suspend function returns instead of waiting on a network. */
    private fun failingCall(): Call = mockk(relaxed = true) {
        every { enqueue(any()) } answers { firstArg<Callback>().onFailure(self as Call, IOException("test: no network")) }
    }

    private fun client(record: MutableList<Request>): OkHttpClient = mockk {
        every { newCall(any()) } answers {
            record += firstArg<Request>()
            failingCall()
        }
        every { connectionPool } returns mockk(relaxed = true)
    }

    /** BirdoApi as the app builds it, with the session Connected: ApiRoutePolicy says TUNNEL. */
    private val api: BirdoApi = Retrofit.Builder()
        .baseUrl("https://api.birdo.app/")
        .callFactory(RoutingCallFactory(client(tunnelRequests), client(bypassRequests)) { false })
        .addConverterFactory(NetworkModule.json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BirdoApi::class.java)

    @Test
    fun `the dead-tunnel probe goes around the tunnel and an ordinary beat rides it`() = runTest {
        runCatching { api.heartbeat("key-1", null) }
        runCatching { api.heartbeat("key-1", AroundTunnel) }

        assertEquals(listOf("/vpn/heartbeat/key-1"), tunnelRequests.map { it.url.encodedPath })
        assertEquals(listOf("/vpn/heartbeat/key-1"), bypassRequests.map { it.url.encodedPath })
    }
}
