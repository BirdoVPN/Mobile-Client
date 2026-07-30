package app.birdo.vpn.data.repository

import app.birdo.vpn.data.api.BirdoApi
import app.birdo.vpn.data.auth.ClientDeviceInfo
import app.birdo.vpn.data.auth.DeviceInfoProvider
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.*
import app.birdo.vpn.shared.model.LoginResult
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

class BirdoRepositoryTest {

    private lateinit var api: BirdoApi
    private lateinit var tokenManager: TokenManager
    private lateinit var deviceInfoProvider: DeviceInfoProvider
    private lateinit var repository: BirdoRepository

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        deviceInfoProvider = mockk(relaxed = true)
        every { deviceInfoProvider.current() } returns ClientDeviceInfo(
            deviceId = "android_test_device",
            deviceName = "Test Android",
            platformVersion = "15",
            appVersion = "1.0.0",
        )
        repository = BirdoRepository(api, tokenManager, deviceInfoProvider)
    }

    // ── Login ────────────────────────────────────────────────────

    @Test
    fun `login success stores tokens and returns result`() = runTest {
        val loginResponse = LoginResponse(
            ok = true,
            tokens = TokenPair("access_tok", "refresh_tok"),
        )
        coEvery { api.login(any()) } returns Response.success(loginResponse)

        val result = repository.login("user@test.com", "pass123")

        assertTrue(result is ApiResult.Success)
        val loginResult = (result as ApiResult.Success).data
        assertTrue(loginResult is LoginResult.Success)
        assertEquals(true, (loginResult as LoginResult.Success).ok)
        verify { tokenManager.setTokens("access_tok", "refresh_tok") }
    }

    @Test
    fun `login failure returns error with sanitized message`() = runTest {
        val errorBody = "Invalid credentials".toResponseBody("text/plain".toMediaType())
        coEvery { api.login(any()) } returns Response.error(401, errorBody)

        val result = repository.login("user@test.com", "wrong")

        assertTrue(result is ApiResult.Error)
        assertEquals(401, (result as ApiResult.Error).code)
        verify(exactly = 0) { tokenManager.setTokens(any(), any()) }
    }

    /**
     * The half of the lockout fix that AuthViewModelTest cannot see.
     *
     * parseLoginError distinguishes a lockout from a wrong password by matching
     * the SERVER'S SENTENCE inside the error body. Those ViewModel tests feed
     * the sentence in by hand (they mock `repository.login`), so they prove the
     * mapping and nothing about whether the sentence ever arrives.
     *
     * It arrives through [InputValidator.sanitizeErrorMessage], which is a
     * FILTER, not a passthrough: it discards the body wholesale — substituting
     * the bland "Login failed" fallback — when it exceeds 200 chars or contains
     * "<html", "Exception", "at " or "stackTrace". Any of those and every
     * lockout, ban and suspension collapses back into the generic bucket,
     * parseLoginError's careful arms never fire, and the locked-out user is
     * told to retype their password again — with the whole suite still green,
     * because nothing else exercises this seam.
     *
     * So pin the real Nest bodies verbatim (auth.controller validateLoginAttempt,
     * lockout.service isLockedOut, auth.service validateUser) and assert the
     * distinguishing sentence survives to the string parseLoginError is handed.
     */
    @Test
    fun `real server lockout and ban bodies survive sanitization intact`() = runTest {
        val bodies = mapOf(
            // lockout.service: Redis + DB lockout reason
            """{"message":"Too many failed login attempts","error":"Unauthorized","statusCode":401}"""
                to "Too many failed login attempts",
            // auth.controller: the fallback when isLockedOut supplies no reason
            """{"message":"Account locked due to multiple failed login attempts","error":"Unauthorized","statusCode":401}"""
                to "Account locked",
            // auth.service.validateUser: the timed variant, thrown as 403
            """{"message":"Account locked. Try again in 12 minutes.","error":"Forbidden","statusCode":403}"""
                to "Account locked",
            // auth.service.validateUser: untimed fallback
            """{"message":"Account is locked","error":"Forbidden","statusCode":403}"""
                to "Account is locked",
            // lockout.service: banned AND suspended share one uniform sentence
            """{"message":"Unable to sign in. Please contact support.","error":"Unauthorized","statusCode":401}"""
                to "Unable to sign in",
        )

        for ((body, sentence) in bodies) {
            clearMocks(api, answers = false)
            coEvery { api.login(any()) } returns Response.error(
                401, body.toResponseBody("application/json".toMediaType()),
            )

            val result = repository.login("user@birdo.app", "password")

            val message = (result as ApiResult.Error).message
            assertTrue(
                "sanitizeErrorMessage swallowed the body parseLoginError needs — " +
                    "expected \"$sentence\" to survive, got: $message",
                message.contains(sentence),
            )
        }
    }

    @Test
    fun `login network exception returns error`() = runTest {
        coEvery { api.login(any()) } throws java.net.SocketTimeoutException("Connection timed out")

        val result = repository.login("user@test.com", "pass")

        assertTrue(result is ApiResult.Error)
        assertEquals("Connection timed out", (result as ApiResult.Error).message)
    }

    // ── Refresh Token ───────────────────────────────────────────

    @Test
    fun `refreshToken success stores new token`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "old_refresh"
        coEvery { api.refreshToken(any()) } returns Response.success(
            RefreshResponse(accessToken = "new_access", expiresIn = 3600)
        )

        val result = repository.refreshToken()

        assertEquals(RefreshOutcome.SUCCESS, result)
        verify { tokenManager.setAccessToken("new_access") }
    }

    @Test
    fun `refreshToken with no refresh token is unauthorized`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns null

        val result = repository.refreshToken()

        assertEquals(RefreshOutcome.UNAUTHORIZED, result)
    }

    @Test
    fun `refreshToken 401 is a definitive unauthorized`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "old_refresh"
        val errorBody = "Invalid token".toResponseBody("text/plain".toMediaType())
        coEvery { api.refreshToken(any()) } returns Response.error(401, errorBody)

        val result = repository.refreshToken()

        assertEquals(RefreshOutcome.UNAUTHORIZED, result)
    }

    @Test
    fun `refreshToken 5xx is transient (keeps the session)`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "old_refresh"
        val errorBody = "boom".toResponseBody("text/plain".toMediaType())
        coEvery { api.refreshToken(any()) } returns Response.error(503, errorBody)

        val result = repository.refreshToken()

        // Not UNAUTHORIZED — a definitive 401/403 would force re-login, a 5xx
        // must not (finding #7). The stored tokens are left untouched.
        assertEquals(RefreshOutcome.TRANSIENT, result)
        verify(exactly = 0) { tokenManager.setAccessToken(any()) }
    }

    // ── Logout ──────────────────────────────────────────────────

    @Test
    fun `logout clears all tokens`() = runTest {
        coEvery { api.logout() } returns Response.success(Unit)

        repository.logout()

        verify { tokenManager.clearAll() }
    }

    @Test
    fun `logout still clears tokens even if API call fails`() = runTest {
        coEvery { api.logout() } throws Exception("Network error")

        repository.logout()

        verify { tokenManager.clearAll() }
    }

    /**
     * The one that mattered: logout ran OUTSIDE withAutoRefresh, so an expired
     * access token (the normal case — access tokens live 1h, refresh tokens 30
     * days) made /auth/logout answer 401 with nothing retrying. Local tokens
     * were still wiped, so the user saw a clean sign-out while the refresh
     * lineage stayed alive server-side for up to another 30 days.
     *
     * Asserting TWO calls to api.logout is the whole point: one call means the
     * 401 was accepted and the session outlived the logout.
     */
    @Test
    fun `logout with an expired access token refreshes and retries so the session dies server-side`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "live_refresh"
        coEvery { api.refreshToken(any()) } returns Response.success(
            RefreshResponse(accessToken = "new_access", expiresIn = 3600)
        )
        val unauthorized = "Unauthorized".toResponseBody("text/plain".toMediaType())
        coEvery { api.logout() } returnsMany listOf(
            Response.error(401, unauthorized),
            Response.success(Unit),
        )

        repository.logout()

        coVerify(exactly = 1) { api.refreshToken(any()) }
        coVerify(exactly = 2) { api.logout() }
        verify { tokenManager.clearAll() }
    }

    /**
     * A logout that succeeds first time must NOT spend a refresh — rotating the
     * refresh token for no reason is exactly the kind of extra replay that made
     * reuse detection fire in production.
     */
    @Test
    fun `logout on a live access token does not burn a refresh`() = runTest {
        coEvery { api.logout() } returns Response.success(Unit)

        repository.logout()

        coVerify(exactly = 1) { api.logout() }
        coVerify(exactly = 0) { api.refreshToken(any()) }
        verify { tokenManager.clearAll() }
    }

    /**
     * If the refresh token is dead too, there is nothing left to revoke. What
     * this pins is that logout does NOT then replay the dead token: exactly one
     * refresh, and no retry of api.logout afterwards.
     *
     * That restraint is the whole of finding #243. A rejected refresh token
     * replayed against the server re-runs reuse detection, and every replay
     * revoked the account's sessions AND every WireGuard peer — four
     * revocations in one minute in production on 2026-07-28. Routing logout
     * through withAutoRefresh added a NEW caller of refreshToken(), so it needs
     * its own guard against becoming another replay source.
     *
     * NOTE: this test deliberately does NOT assert `clearAll`, even though
     * clearAll does happen here. On a 401 refresh, refreshToken() clears the
     * tokens ITSELF, so a `verify { clearAll() }` here passes whether or not
     * logout's own unconditional clear exists — verified by deleting that line
     * and watching this test still go green. The unconditional clear is pinned
     * by `logout still signs out locally when the device is offline` below,
     * where refreshToken() provably never clears.
     */
    @Test
    fun `logout does not replay a dead refresh token`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "dead_refresh"
        val unauthorized = "Unauthorized".toResponseBody("text/plain".toMediaType())
        coEvery { api.logout() } returns Response.error(401, unauthorized)
        coEvery { api.refreshToken(any()) } returns Response.error(401, unauthorized)

        repository.logout()

        coVerify(exactly = 1) { api.logout() }
        coVerify(exactly = 1) { api.refreshToken(any()) }
    }

    /**
     * The unconditional local sign-out, pinned where nothing else can supply it.
     *
     * Offline is the case that matters: "Log out" must never leave the user
     * signed in on the handset because the network happened to be down. Both
     * calls throw the way OkHttp throws with no route to the host, so
     * refreshToken() returns TRANSIENT — the one refresh outcome that
     * deliberately leaves the stored tokens alone (a 5xx or a dead Wi-Fi must
     * not destroy a valid session). Nothing in the refresh path can call
     * clearAll here, so the only possible source is logout's own trailing
     * clear. Delete that line and this test fails; that is the point of it.
     */
    @Test
    fun `logout still signs out locally when the device is offline`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "live_refresh"
        coEvery { api.logout() } throws IOException("Unable to resolve host api.birdo.app")
        coEvery { api.refreshToken(any()) } throws IOException("Unable to resolve host api.birdo.app")

        repository.logout()

        verify(exactly = 1) { tokenManager.clearAll() }
    }

    /**
     * ...and it must not HANG on the way, which is the other half of "logout
     * must never fail because the network is down".
     *
     * A server that accepts the connection and then never answers does not
     * throw — it just sits there. NetworkModule sets no OkHttp `callTimeout`,
     * so before [BirdoRepository.LOGOUT_SERVER_CALL_TIMEOUT_MS] nothing bounded
     * this at all, and routing logout through withAutoRefresh made it worse by
     * turning one stalled round trip into up to three.
     *
     * runTest's virtual clock makes the wait free but still real to the code
     * under test, so asserting `currentTime` pins the actual budget rather than
     * just "it eventually returned". Remove the withTimeout and this test hangs
     * until the suite times out instead of passing.
     */
    @Test
    fun `logout gives up on a server that never answers and still signs out`() = runTest {
        coEvery { api.logout() } coAnswers {
            delay(Long.MAX_VALUE)
            Response.success(Unit)
        }

        val startedAt = currentTime
        repository.logout()

        assertEquals(
            "logout must abandon the server call at the declared budget",
            BirdoRepository.LOGOUT_SERVER_CALL_TIMEOUT_MS,
            currentTime - startedAt,
        )
        verify(exactly = 1) { tokenManager.clearAll() }
    }

    // ── Get Profile ─────────────────────────────────────────────

    @Test
    fun `getProfile success returns user`() = runTest {
        val profile = UserProfile(id = "1", email = "user@test.com")
        coEvery { api.getProfile() } returns Response.success(profile)

        val result = repository.getProfile()

        assertTrue(result is ApiResult.Success)
        assertEquals("user@test.com", (result as ApiResult.Success).data.email)
    }

    @Test
    fun `getProfile 401 triggers refresh and retries`() = runTest {
        val profile = UserProfile(id = "1", email = "user@test.com")
        val errorBody = "Unauthorized".toResponseBody("text/plain".toMediaType())
        // First call returns 401, retry succeeds
        coEvery { api.getProfile() } returnsMany listOf(
            Response.error(401, errorBody),
            Response.success(profile),
        )
        coEvery { tokenManager.getRefreshToken() } returns "refresh"
        coEvery { api.refreshToken(any()) } returns Response.success(
            RefreshResponse(accessToken = "new_access", expiresIn = 3600)
        )

        val result = repository.getProfile()

        assertTrue(result is ApiResult.Success)
        assertEquals("user@test.com", (result as ApiResult.Success).data.email)
    }

    // ── Get Servers ─────────────────────────────────────────────

    @Test
    fun `getServers returns server list`() = runTest {
        val servers = listOf(
            VpnServer(id = "1", name = "Utah 1", country = "US", countryCode = "US"),
            VpnServer(id = "2", name = "London 1", country = "UK", countryCode = "GB"),
        )
        coEvery { api.getServers() } returns Response.success(servers)

        val result = repository.getServers()

        assertTrue(result is ApiResult.Success)
        assertEquals(2, (result as ApiResult.Success).data.size)
    }

    // ── Connect VPN ─────────────────────────────────────────────

    @Test
    fun `connectVpn success stores key and server info`() = runTest {
        val response = ConnectResponse(
            success = true,
            keyId = "key123",
            config = "wireguard_config",
        )
        coEvery { api.connect(any()) } returns Response.success(response)

        val result = repository.connectVpn("server_1")

        assertTrue(result is ApiResult.Success)
        verify { tokenManager.setLastKeyId("key123") }
        // Private key is generated locally (not from the server), so verify it's stored but don't
        // check the exact value — it's a random X25519 key from wireguard-android.
        verify { tokenManager.setWireGuardPrivateKey(any()) }
        verify { tokenManager.setLastServer("server_1") }
    }

    @Test
    fun `connectVpn forwards stealth quantum and PQ public key`() = runTest {
        val request = slot<ConnectRequest>()
        coEvery { api.connect(capture(request)) } returns Response.success(
            ConnectResponse(success = true, keyId = "key123")
        )

        val result = repository.connectVpn(
            serverNodeId = "server_1",
            deviceName = "Pixel 9",
            stealthMode = true,
            quantumProtection = true,
            pqClientPublicKey = "pq-public-key",
        )

        assertTrue(result is ApiResult.Success)
        assertEquals("server_1", request.captured.serverNodeId)
        assertEquals("Pixel 9", request.captured.deviceName)
        assertNotNull(request.captured.clientPublicKey)
        assertEquals(true, request.captured.stealthMode)
        assertEquals(true, request.captured.quantumProtection)
        assertEquals("pq-public-key", request.captured.pqClientPublicKey)
    }

    // ── Disconnect VPN ──────────────────────────────────────────

    @Test
    fun `disconnectVpn calls API with stored key ID`() = runTest {
        coEvery { tokenManager.getLastKeyId() } returns "key123"
        coEvery { api.disconnect("key123") } returns Response.success(Unit)

        val result = repository.disconnectVpn()

        assertTrue(result is ApiResult.Success)
        coVerify { api.disconnect("key123") }
    }

    @Test
    fun `disconnectVpn without key ID skips API call`() = runTest {
        coEvery { tokenManager.getLastKeyId() } returns null

        val result = repository.disconnectVpn()

        assertTrue(result is ApiResult.Success)
        coVerify(exactly = 0) { api.disconnect(any()) }
    }

    // ── Anonymous Login ─────────────────────────────────────────

    @Test
    fun `loginAnonymous success stores tokens`() = runTest {
        val response = AnonymousLoginResponse(
            ok = true,
            anonymousId = "anon_123",
            tokens = TokenPair("access_anon", "refresh_anon"),
        )
        coEvery { api.loginAnonymous(any()) } returns Response.success(response)

        val result = repository.loginAnonymous("device_abc")

        assertTrue(result is ApiResult.Success)
        assertTrue((result as ApiResult.Success).data.ok)
        verify { tokenManager.setTokens("access_anon", "refresh_anon") }
    }

    @Test
    fun `loginAnonymous failure returns error`() = runTest {
        val errorBody = "Rate limited".toResponseBody("text/plain".toMediaType())
        coEvery { api.loginAnonymous(any()) } returns Response.error(429, errorBody)

        val result = repository.loginAnonymous("device_abc")

        assertTrue(result is ApiResult.Error)
        assertEquals(429, (result as ApiResult.Error).code)
    }

    // ── Multi-Hop ───────────────────────────────────────────────

    @Test
    fun `getMultiHopRoutes returns routes`() = runTest {
        val routes = listOf(
            MultiHopRoute(entryNodeId = "de-1", exitNodeId = "us-1", entryCountry = "DE", exitCountry = "US"),
        )
        coEvery { api.getMultiHopRoutes() } returns Response.success(routes)

        val result = repository.getMultiHopRoutes()

        assertTrue(result is ApiResult.Success)
        assertEquals(1, (result as ApiResult.Success).data.size)
    }

    @Test
    fun `connectMultiHop forwards stealth quantum and PQ public key`() = runTest {
        val request = slot<MultiHopConnectRequest>()
        coEvery { api.connectMultiHop(capture(request)) } returns Response.success(
            MultiHopConnectResponse(success = true, keyId = "mh-key")
        )

        val result = repository.connectMultiHop(
            entryNodeId = "de-1",
            exitNodeId = "nl-1",
            deviceName = "Pixel 9",
            stealthMode = true,
            quantumProtection = true,
            pqClientPublicKey = "pq-public-key",
        )

        assertTrue(result is ApiResult.Success)
        assertEquals("de-1", request.captured.entryNodeId)
        assertEquals("nl-1", request.captured.exitNodeId)
        assertEquals("Pixel 9", request.captured.deviceName)
        assertNotNull(request.captured.clientPublicKey)
        assertEquals(true, request.captured.stealthMode)
        assertEquals(true, request.captured.quantumProtection)
        assertEquals("pq-public-key", request.captured.pqClientPublicKey)
    }

    // ── Port Forwarding ─────────────────────────────────────────

    @Test
    fun `getPortForwards returns list`() = runTest {
        val forwards = listOf(
            PortForward(id = "pf-1", externalPort = 8080, internalPort = 8080, protocol = "tcp"),
        )
        coEvery { api.getPortForwards() } returns Response.success(forwards)

        val result = repository.getPortForwards()

        assertTrue(result is ApiResult.Success)
        assertEquals(1, (result as ApiResult.Success).data.size)
    }

    @Test
    fun `deletePortForward calls API`() = runTest {
        coEvery { api.deletePortForward("pf-1") } returns Response.success(Unit)

        val result = repository.deletePortForward("pf-1")

        assertTrue(result is ApiResult.Success)
        coVerify { api.deletePortForward("pf-1") }
    }
}
