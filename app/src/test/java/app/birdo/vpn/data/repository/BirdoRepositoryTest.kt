package app.birdo.vpn.data.repository

import app.birdo.vpn.data.api.BirdoApi
import app.birdo.vpn.data.auth.ClientDeviceInfo
import app.birdo.vpn.data.auth.DeviceInfoProvider
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.*
import app.birdo.vpn.shared.model.LoginResult
import app.birdo.vpn.testing.StringsXml
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
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
        repository = BirdoRepository(api, tokenManager, deviceInfoProvider, ApiErrorMapper(StringsXml))
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
     * The seam end to end: the real Nest bodies (auth.controller
     * validateLoginAttempt, lockout.service isLockedOut, auth.service
     * validateUser), read off the wire by login(), come out as the right
     * reason and the right sentence. ApiErrorMapperTest pins the mapping
     * itself; this pins that login() actually hands it the body.
     */
    @Test
    fun `real server lockout and ban bodies reach the user as the right sentence`() = runTest {
        val bodies = mapOf(
            """{"message":"Too many failed login attempts","error":"Unauthorized","statusCode":401}"""
                to FailureReason.ACCOUNT_LOCKED,
            """{"message":"Account locked due to multiple failed login attempts","error":"Unauthorized","statusCode":401}"""
                to FailureReason.ACCOUNT_LOCKED,
            """{"message":"Account locked. Try again in 12 minutes.","error":"Forbidden","statusCode":403}"""
                to FailureReason.ACCOUNT_LOCKED,
            """{"message":"Account is locked","error":"Forbidden","statusCode":403}"""
                to FailureReason.ACCOUNT_LOCKED,
            """{"message":"Unable to sign in. Please contact support.","error":"Unauthorized","statusCode":401}"""
                to FailureReason.ACCOUNT_BLOCKED,
        )

        for ((body, reason) in bodies) {
            clearMocks(api, answers = false)
            coEvery { api.login(any()) } returns Response.error(
                401, body.toResponseBody("application/json".toMediaType()),
            )

            val error = repository.login("user@birdo.app", "password") as ApiResult.Error

            assertEquals(body, reason, error.reason)
            assertFalse("raw body reached the user: ${error.message}", "statusCode" in error.message)
        }
    }

    @Test
    fun `login network exception is mapped, never shown as exception text`() = runTest {
        coEvery { api.login(any()) } throws java.net.SocketTimeoutException("Connection timed out")

        val result = repository.login("user@test.com", "pass")

        val error = result as ApiResult.Error
        assertEquals(FailureReason.UNREACHABLE, error.reason)
        assertEquals(StringsXml.text("error_unreachable"), error.message)
    }

    @Test
    fun `login with no network says so instead of the resolver's text`() = runTest {
        coEvery { api.login(any()) } throws java.net.UnknownHostException("Unable to resolve host \"api.birdo.app\"")

        val error = repository.login("user@test.com", "pass") as ApiResult.Error

        assertEquals(FailureReason.OFFLINE, error.reason)
        assertEquals(StringsXml.text("error_offline"), error.message)
    }

    @Test
    fun `a 5xx JSON body never reaches the user as JSON`() = runTest {
        coEvery { api.getServers() } returns Response.error(
            500, """{"statusCode":500,"message":"Internal server error"}""".toResponseBody("application/json".toMediaType()),
        )

        val error = repository.getServers(forceRefresh = true) as ApiResult.Error

        assertEquals(500, error.code)
        assertFalse(error.message.startsWith("{"))
        assertEquals(StringsXml.text("error_server_unavailable"), error.message)
    }

    @Test
    fun `a cancelled call is rethrown, not reported as a failure`() = runTest {
        coEvery { api.getProfile() } throws kotlinx.coroutines.CancellationException("left the screen")

        val thrown = runCatching { repository.getProfile() }.exceptionOrNull()

        assertTrue("got $thrown", thrown is kotlinx.coroutines.CancellationException)
    }

    // ── Single-flight refresh (A2-007) ──────────────────────────

    /**
     * Four requests fired together with an expired access token — the cold
     * start after the one-hour lifetime: profile, plan, servers, update check.
     * The server 401s every request carrying the old token. Exactly ONE
     * rotation may happen; the other three callers must find the token already
     * replaced and retry on it.
     */
    @Test
    fun `concurrent 401s share one refresh`() = runTest {
        var access = "old_access"
        var refresh = "old_refresh"
        every { tokenManager.getAccessToken() } answers { access }
        every { tokenManager.getRefreshToken() } answers { refresh }
        every { tokenManager.setTokens(any(), any()) } answers {
            access = firstArg()
            refresh = secondArg()
        }
        fun <T> unauthorized(): Response<T> =
            Response.error(401, "Unauthorized".toResponseBody("text/plain".toMediaType()))
        coEvery { api.getProfile() } coAnswers {
            if (access == "old_access") unauthorized() else Response.success(UserProfile(id = "1", email = "a@b.c"))
        }
        coEvery { api.getSubscription() } coAnswers {
            if (access == "old_access") unauthorized() else Response.success(SubscriptionStatus())
        }
        coEvery { api.getServers() } coAnswers {
            if (access == "old_access") unauthorized() else Response.success(emptyList())
        }
        coEvery { api.checkAppUpdate(any()) } coAnswers {
            if (access == "old_access") unauthorized() else Response.success(AppUpdateInfo())
        }
        coEvery { api.refreshToken(any()) } coAnswers {
            delay(100) // the others pile up on the lock meanwhile
            Response.success(RefreshResponse(accessToken = "new_access", refreshToken = "new_refresh", expiresIn = 3600))
        }

        val results = listOf(
            async { repository.getProfile() },
            async { repository.getSubscription(forceRefresh = true) },
            async { repository.getServers(forceRefresh = true) },
            async { repository.checkAppUpdate() },
        ).awaitAll()

        coVerify(exactly = 1) { api.refreshToken(any()) }
        assertTrue("every caller must succeed on the retry: $results", results.all { it is ApiResult.Success })
    }

    @Test
    fun `a refresh for a token that is still current does rotate`() = runTest {
        coEvery { tokenManager.getAccessToken() } returns "stale"
        coEvery { tokenManager.getRefreshToken() } returns "live_refresh"
        coEvery { api.refreshToken(any()) } returns Response.success(
            RefreshResponse(accessToken = "new_access", expiresIn = 3600)
        )

        assertEquals(RefreshOutcome.SUCCESS, repository.refreshToken(staleAccessToken = "stale"))
        coVerify(exactly = 1) { api.refreshToken(any()) }
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
     * throw — it just sits there. NetworkModule's `callTimeout` bounds each
     * round trip at 45 s, and routing logout through the refresh path turns
     * one stalled round trip into up to three, so
     * [BirdoRepository.LOGOUT_SERVER_CALL_TIMEOUT_MS] is what bounds the whole.
     *
     * runTest's virtual clock makes the wait free but still real to the code
     * under test, so asserting `currentTime` pins the actual budget rather than
     * just "it eventually returned". Remove the withTimeout and this test hangs
     * until the suite times out instead of passing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
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

    // ── Delete Account ──────────────────────────────────────────

    @Test
    fun `deleteAccount success resets the device identity`() = runTest {
        coEvery { api.deleteAccount(any()) } returns Response.success(
            DeleteAccountResponse(success = true)
        )

        val result = repository.deleteAccount("pass123")

        assertTrue(result is ApiResult.Success)
        verify { tokenManager.clearAll() }
        // The SSAID-derived deviceId must not outlive the account it
        // identified — kept, it joins the erased account to the next one
        // registered on this handset.
        verify(exactly = 1) { deviceInfoProvider.resetDeviceIdentity() }
    }

    /** ACCOUNT-API-2026-10-01, item 85: the code rides the same body, and only when there is one. */
    @Test
    fun `deleteAccount sends the 2FA code with the password`() = runTest {
        coEvery { api.deleteAccount(any()) } returns Response.success(DeleteAccountResponse(success = true))

        repository.deleteAccount("pass123", "123456")
        repository.deleteAccount("pass123")

        coVerify(exactly = 1) { api.deleteAccount(DeleteAccountRequest("pass123", "123456")) }
        coVerify(exactly = 1) { api.deleteAccount(DeleteAccountRequest("pass123", null)) }
    }

    @Test
    fun `failed deleteAccount keeps the device identity`() = runTest {
        val err = "nope".toResponseBody("text/plain".toMediaType())
        coEvery { api.deleteAccount(any()) } returns Response.error(400, err)

        repository.deleteAccount("wrong-pass")

        // The account still exists — its slot reclamation depends on the id
        // staying stable, so a refused deletion must not rotate it.
        verify(exactly = 0) { deviceInfoProvider.resetDeviceIdentity() }
        verify(exactly = 0) { deviceInfoProvider.forgetPostQuantumKeypair() }
    }

    // ── ML-KEM keypair rotation (audit 2026-09-29, D-15) ────────

    @Test
    fun `deleteAccount success forgets the ML-KEM keypair`() = runTest {
        coEvery { api.deleteAccount(any()) } returns Response.success(
            DeleteAccountResponse(success = true)
        )

        repository.deleteAccount("pass123")

        // The per-install PQ public key rides every /connect body; kept, it
        // joins the erased account to the next one on this handset.
        verify(exactly = 1) { deviceInfoProvider.forgetPostQuantumKeypair() }
    }

    @Test
    fun `logout forgets the ML-KEM keypair`() = runTest {
        coEvery { api.logout() } returns Response.success(Unit)

        repository.logout()

        verify(exactly = 1) { deviceInfoProvider.forgetPostQuantumKeypair() }
        // …but not the deviceId: a live account's slot reclamation keys on it.
        verify(exactly = 0) { deviceInfoProvider.resetDeviceIdentity() }
    }

    @Test
    fun `logout still completes when the keypair cannot be deleted`() = runTest {
        coEvery { api.logout() } returns Response.success(Unit)
        every { deviceInfoProvider.forgetPostQuantumKeypair() } throws IllegalStateException("keystore")

        repository.logout()

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
        // A client that uploaded an ML-KEM key MUST assert it can decapsulate,
        // or the server ships the PSK over TLS and the HNDL property is lost.
        assertEquals(true, request.captured.pqClientCanDecapsulate)
    }

    @Test
    fun `connectVpn forwards the BirdoShield flag and leaves it off by default`() = runTest {
        // D18: the per-device opt-in only exists on the connect body, so the
        // repository must pass it through verbatim on the single-hop path.
        val request = slot<ConnectRequest>()
        coEvery { api.connect(capture(request)) } returns Response.success(
            ConnectResponse(success = true, keyId = "key123")
        )

        repository.connectVpn(serverNodeId = "server_1", dnsFiltering = true)
        assertEquals(true, request.captured.dnsFiltering)

        repository.connectVpn(serverNodeId = "server_1")
        assertEquals(false, request.captured.dnsFiltering)
    }

    @Test
    fun `connectVpn does not claim decapsulation without an ML-KEM key`() = runTest {
        val request = slot<ConnectRequest>()
        coEvery { api.connect(capture(request)) } returns Response.success(
            ConnectResponse(success = true, keyId = "key123")
        )

        repository.connectVpn(serverNodeId = "server_1")

        assertEquals(false, request.captured.pqClientCanDecapsulate)
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

    /**
     * A1-005: Disconnect then a quick Connect. The Disconnect's DELETE was
     * still in flight when the new connect stored its key id — and on return
     * the old DELETE cleared it unconditionally: the new session stopped
     * heartbeating and its live peer was reaped five minutes later.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a late DELETE never clears the next session's key id`() = runTest {
        var storedKey: String? = "key-1"
        every { tokenManager.getLastKeyId() } answers { storedKey }
        every { tokenManager.setLastKeyId(any()) } answers { storedKey = firstArg() }
        every { tokenManager.clearLastKeyId() } answers { storedKey = null }
        val gate = kotlinx.coroutines.CompletableDeferred<Response<Unit>>()
        coEvery { api.disconnect("key-1") } coAnswers { gate.await() }
        coEvery { api.connect(any()) } returns Response.success(ConnectResponse(success = true, keyId = "key-2"))

        val late = async { repository.disconnectVpn() }
        runCurrent()
        repository.connectVpn("server_1")
        gate.complete(Response.success(Unit))
        late.await()

        assertEquals("key-2", storedKey)
        verify(exactly = 0) { tokenManager.clearWireGuardPrivateKey() }
    }

    @Test
    fun `disconnectVpn releases the key it is named, and clears the stored id only when it matches`() = runTest {
        coEvery { tokenManager.getLastKeyId() } returns "key-other"
        coEvery { api.disconnect("key-explicit") } returns Response.success(Unit)

        repository.disconnectVpn("key-explicit")

        coVerify { api.disconnect("key-explicit") }
        verify(exactly = 0) { tokenManager.clearLastKeyId() }
    }

    @Test
    fun `a heartbeat with no key id says so with its own code`() = runTest {
        coEvery { tokenManager.getLastKeyId() } returns null

        val result = repository.sendHeartbeat()

        assertEquals(BirdoRepository.CODE_NO_ACTIVE_KEY, (result as ApiResult.Error).code)
        coVerify(exactly = 0) { api.heartbeat(any()) }
    }

    @Test
    fun `a heartbeat names the session's own key when given one`() = runTest {
        coEvery { tokenManager.getLastKeyId() } returns "stored"
        coEvery { api.heartbeat("session-key") } returns Response.success(HeartbeatResponse())

        repository.sendHeartbeat("session-key")

        coVerify { api.heartbeat("session-key") }
        coVerify(exactly = 0) { api.heartbeat("stored") }
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
        // The multi-hop twin must carry the HNDL opt-in exactly like single-hop.
        assertEquals(true, request.captured.pqClientCanDecapsulate)
    }

    @Test
    fun `connectMultiHop forwards the BirdoShield flag and leaves it off by default`() = runTest {
        // D18 twin of the single-hop case: a double-hop user must be able to
        // opt into the filtering resolver exactly like a single-hop one.
        val request = slot<MultiHopConnectRequest>()
        coEvery { api.connectMultiHop(capture(request)) } returns Response.success(
            MultiHopConnectResponse(success = true, keyId = "mh-key")
        )

        repository.connectMultiHop(entryNodeId = "de-1", exitNodeId = "nl-1", dnsFiltering = true)
        assertEquals(true, request.captured.dnsFiltering)

        repository.connectMultiHop(entryNodeId = "de-1", exitNodeId = "nl-1")
        assertEquals(false, request.captured.dnsFiltering)
    }

    @Test
    fun `connectMultiHop forwards the adaptive-transport fallback reason`() = runTest {
        // Without this field on the wire, a multi-hop user on a DPI-filtered
        // network can never obtain the stealth rebuild single-hop already gets.
        val request = slot<MultiHopConnectRequest>()
        coEvery { api.connectMultiHop(capture(request)) } returns Response.success(
            MultiHopConnectResponse(success = true, keyId = "mh-key")
        )

        repository.connectMultiHop(
            entryNodeId = "de-1",
            exitNodeId = "nl-1",
            fallbackReason = app.birdo.vpn.shared.model.TransportFallbackReason.HANDSHAKE_TIMEOUT,
        )

        assertEquals(
            app.birdo.vpn.shared.model.TransportFallbackReason.HANDSHAKE_TIMEOUT,
            request.captured.fallbackReason,
        )
    }

    @Test
    fun `connectMultiHop omits fallbackReason on a normal first attempt`() = runTest {
        val request = slot<MultiHopConnectRequest>()
        coEvery { api.connectMultiHop(capture(request)) } returns Response.success(
            MultiHopConnectResponse(success = true, keyId = "mh-key")
        )

        repository.connectMultiHop(entryNodeId = "de-1", exitNodeId = "nl-1")

        assertNull(request.captured.fallbackReason)
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

    /**
     * A2-038: the hand-rolled copy of the refresh policy reported a failed
     * retry after a SUCCESSFUL refresh as "Session expired" 401, which signs a
     * user out of a session that was just proven good.
     */
    @Test
    fun `deletePortForward reports the retry's real status after a good refresh`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "live_refresh"
        coEvery { api.refreshToken(any()) } returns Response.success(
            RefreshResponse(accessToken = "new_access", expiresIn = 3600)
        )
        coEvery { api.deletePortForward("pf-1") } returnsMany listOf(
            Response.error(401, "Unauthorized".toResponseBody("text/plain".toMediaType())),
            Response.error(503, "down".toResponseBody("text/plain".toMediaType())),
        )

        val error = repository.deletePortForward("pf-1") as ApiResult.Error

        assertEquals(503, error.code)
        assertEquals(FailureReason.SERVER_UNAVAILABLE, error.reason)
    }

    @Test
    fun `a 204 from a no-body route is a success`() = runTest {
        coEvery { tokenManager.getLastKeyId() } returns "key123"
        coEvery { api.disconnect("key123") } returns Response.success(204, null as Unit?)
        coEvery { api.deletePortForward("pf-1") } returns Response.success(204, null as Unit?)

        assertTrue(repository.disconnectVpn() is ApiResult.Success)
        assertTrue(repository.deletePortForward("pf-1") is ApiResult.Success)
    }

    // ── Vouchers (A2-027) ───────────────────────────────────────

    @Test
    fun `a voucher redeemed with an expired access token refreshes and succeeds`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "live_refresh"
        coEvery { api.refreshToken(any()) } returns Response.success(
            RefreshResponse(accessToken = "new_access", expiresIn = 3600)
        )
        coEvery { api.redeemVoucher(any()) } returnsMany listOf(
            Response.error(401, """{"statusCode":401,"message":"Unauthorized"}""".toResponseBody("application/json".toMediaType())),
            Response.success(RedeemVoucherResponse(ok = true, plan = "OPERATIVE", durationDays = 30)),
        )

        val result = repository.redeemVoucher("BIRD-AAAA-BBBB-CCCC")

        assertTrue(result is ApiResult.Success)
        assertTrue((result as ApiResult.Success).data.ok)
    }

    @Test
    fun `a voucher refusal keeps its slug, and an outage is not reported as the code`() = runTest {
        coEvery { api.redeemVoucher(any()) } returns Response.error(
            409, """{"error":"already_redeemed"}""".toResponseBody("application/json".toMediaType()),
        )
        val refused = repository.redeemVoucher("BIRD-AAAA-BBBB-CCCC") as ApiResult.Success
        assertEquals("already_redeemed", refused.data.error)
        assertFalse(refused.data.ok)

        coEvery { api.redeemVoucher(any()) } returns Response.error(
            503, "<html>down</html>".toResponseBody("text/html".toMediaType()),
        )
        val outage = repository.redeemVoucher("BIRD-AAAA-BBBB-CCCC") as ApiResult.Error
        assertEquals(StringsXml.text("error_server_unavailable"), outage.message)
    }

    // ── A connect refused inside a 200 (A2-030) ─────────────────

    @Test
    fun `a device-limit refusal carries the canonical sentence and stores no key`() = runTest {
        coEvery { api.connect(any()) } returns Response.success(
            ConnectResponse(success = false, message = "Device limit reached (5 devices for OPERATIVE plan)")
        )

        val result = repository.connectVpn("server_1") as ApiResult.Success

        assertFalse(result.data.success)
        assertEquals(StringsXml.text("error_device_limit"), result.data.message)
        verify(exactly = 0) { tokenManager.setWireGuardPrivateKey(any()) }
    }
}
