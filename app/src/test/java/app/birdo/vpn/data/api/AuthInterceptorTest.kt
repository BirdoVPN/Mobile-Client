package app.birdo.vpn.data.api

import app.birdo.vpn.BuildConfig
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.repository.BirdoRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Authorization` goes to the API host and NOWHERE else.
 *
 * This client gained a second host when the BirdoShield fleet gate started
 * reading `GET /api/client-config` off the WEB origin (birdo.app): before
 * that every request on the shared OkHttp client was an api.birdo.app request
 * and the interceptor could attach the bearer token unconditionally. It no
 * longer can — a session token must not travel to a host that has no use for
 * it merely because it shares a client.
 *
 * That conditional now runs on EVERY authenticated request in the app, and it
 * fails in a way nothing else is red for: if `API_BASE_URL` ever stops parsing
 * (`toHttpUrlOrNull()` returns null), or an authenticated endpoint moves to a
 * second host, no request carries a token, every call 401s and the app logs
 * itself out. PR CI cannot exercise it — there is no instrumented run and no
 * network — so it is pinned here, at the interceptor, with a fake Chain.
 *
 * Both URLs below are built from the SAME BuildConfig constants production
 * uses, so moving a base URL moves the test with it instead of leaving it
 * asserting a stale hostname.
 */
class AuthInterceptorTest {

    private val tokenManager = mockk<TokenManager>()

    /** Runs the interceptor over `url` and returns the request it forwarded. */
    private fun forwarded(url: String): Request {
        val interceptor = AuthInterceptor(tokenManager)
        val sent = slot<Request>()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns Request.Builder().url(url).build()
        every { chain.proceed(capture(sent)) } answers {
            Response.Builder()
                .request(sent.captured)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }
        interceptor.intercept(chain)
        return sent.captured
    }

    private val apiUrl = BuildConfig.API_BASE_URL + "/auth/me"
    private val webUrl = BuildConfig.WEB_BASE_URL + BirdoRepository.CLIENT_CONFIG_PATH

    @Test
    fun `the API base URL parses to a host, or nothing is ever authenticated`() {
        val parsed = BuildConfig.API_BASE_URL.toHttpUrlOrNull()
        assertNotNull(
            "BuildConfig.API_BASE_URL (${BuildConfig.API_BASE_URL}) does not parse as an " +
                "HTTP URL, so the interceptor's host comparison can never match and NO " +
                "request would carry Authorization — every authenticated call 401s and the " +
                "app signs itself out with nothing else red",
            parsed,
        )
        // Vacuity guard for the pair below: if both constants resolved to the
        // same host, "token here, no token there" would be untestable.
        assertNotNull(BuildConfig.WEB_BASE_URL.toHttpUrlOrNull())
    }

    @Test
    fun `an authenticated API request carries the bearer token`() {
        every { tokenManager.getAccessToken() } returns "test-access-token"
        assertEquals(
            "Bearer test-access-token",
            forwarded(apiUrl).header("Authorization"),
        )
    }

    @Test
    fun `the public client-config request on the web host carries no token`() {
        every { tokenManager.getAccessToken() } returns "test-access-token"
        assertNull(
            "the session token must not travel to the WEB origin — that endpoint is " +
                "unauthenticated and the token has no use there",
            forwarded(webUrl).header("Authorization"),
        )
        // Not merely stripped afterwards: the token is never even read for a
        // non-API host, so a redirect or a logging interceptor cannot see it.
        verify(exactly = 0) { tokenManager.getAccessToken() }
    }

    @Test
    fun `a signed-out API request carries no token`() {
        every { tokenManager.getAccessToken() } returns null
        assertNull(forwarded(apiUrl).header("Authorization"))
    }

    @Test
    fun `client identification headers go to both hosts`() {
        every { tokenManager.getAccessToken() } returns "test-access-token"
        for (url in listOf(apiUrl, webUrl)) {
            val request = forwarded(url)
            assertEquals("$url lost its client header", "birdo-android", request.header("X-Desktop-Client"))
            assertNotNull("$url lost its User-Agent", request.header("User-Agent"))
        }
    }
}
