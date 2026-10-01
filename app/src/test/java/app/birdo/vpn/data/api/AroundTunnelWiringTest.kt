package app.birdo.vpn.data.api

import app.birdo.vpn.data.auth.DeviceInfoProvider
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.network.RoutingCallFactory
import app.birdo.vpn.data.repository.ApiErrorMapper
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.di.NetworkModule
import app.birdo.vpn.testing.StringsXml
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import java.io.IOException

/**
 * REVIEW-AND2-001/-002, end to end through Retrofit and the real repository,
 * with the session Connected (ApiRoutePolicy says TUNNEL) and the tunnel
 * already dead — what the server's own revoke leaves behind: every call the
 * tunnel client makes fails, as the dropped reply did on a device.
 *
 * The VpnManager and view-model tests mock the repository, and the repository
 * tests mock BirdoApi, so without this a `@Tag` dropped from an interface
 * method, or a repository call that stopped passing it, would pass everything.
 */
class AroundTunnelWiringTest {

    private val tunnelRequests = mutableListOf<Request>()
    private val bypassRequests = mutableListOf<Request>()

    /** What the bypass client's server answers; null fails the call like the dead tunnel. */
    private var bypassAnswer: String? = null

    private fun call(answer: String?): Call = mockk(relaxed = true) {
        every { enqueue(any()) } answers {
            val callback = firstArg<Callback>()
            val call = self as Call
            if (answer == null) {
                callback.onFailure(call, IOException("test: no route"))
            } else {
                callback.onResponse(
                    call,
                    Response.Builder()
                        .request(Request.Builder().url("https://api.birdo.app/").build())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(answer.toResponseBody("application/json".toMediaType()))
                        .build(),
                )
            }
        }
    }

    private fun client(record: MutableList<Request>, answer: () -> String?): OkHttpClient = mockk {
        every { newCall(any()) } answers {
            record += firstArg<Request>()
            call(answer())
        }
        every { connectionPool } returns mockk(relaxed = true)
    }

    private val api: BirdoApi = Retrofit.Builder()
        .baseUrl("https://api.birdo.app/")
        .callFactory(
            RoutingCallFactory(client(tunnelRequests) { null }, client(bypassRequests) { bypassAnswer }) { false },
        )
        .addConverterFactory(NetworkModule.json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BirdoApi::class.java)

    private val tokenManager = mockk<TokenManager>(relaxed = true)
    private val deviceInfo = mockk<DeviceInfoProvider>(relaxed = true)
    private val repository = BirdoRepository(api, tokenManager, deviceInfo, ApiErrorMapper(StringsXml))

    @Test
    fun `the dead-tunnel probe goes around the tunnel and an ordinary beat rides it`() = runTest {
        bypassAnswer = """{"valid":false,"serverOnline":false,"reason":"evicted"}"""

        val beat = repository.sendHeartbeat("key-1")
        val probe = repository.sendHeartbeat("key-1", aroundTunnel = true)

        assertTrue("the live beat died with the tunnel: $beat", beat is ApiResult.Error)
        assertEquals("evicted", (probe as ApiResult.Success).data.reason)
        assertEquals(listOf("/vpn/heartbeat/key-1"), tunnelRequests.map { it.url.encodedPath })
        assertEquals(listOf("/vpn/heartbeat/key-1"), bypassRequests.map { it.url.encodedPath })
    }

    @Test
    fun `an account deletion made while connected gets its success back, and the success path runs`() = runTest {
        bypassAnswer = """{"success":true,"storeSubscriptionsStillBilling":[
            {"store":"GOOGLE_PLAY","productId":"birdo_operative","expiresAt":"2026-11-01T00:00:00.000Z"}]}"""

        val result = repository.deleteAccount("pw")

        // Through the tunnel the server's own revoke dropped this reply.
        assertEquals(emptyList<Request>(), tunnelRequests)
        assertEquals(listOf("DELETE /api/v1/gdpr/delete"), bypassRequests.map { "${it.method} ${it.url.encodedPath}" })
        // The Play "still billing" notice has what it needs...
        val billing = (result as ApiResult.Success).data.storeSubscriptionsStillBilling
        assertTrue(billing.single().isGooglePlay)
        // ...and the erased account leaves nothing that links it to the next one.
        verify(exactly = 1) { tokenManager.clearAll() }
        verify(exactly = 1) { deviceInfo.resetDeviceIdentity() }
        verify(exactly = 1) { deviceInfo.forgetPostQuantumKeypair() }
    }
}
