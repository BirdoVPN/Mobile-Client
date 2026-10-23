package app.birdo.vpn.data.repository

import app.birdo.vpn.data.api.BirdoApi
import app.birdo.vpn.data.auth.ClientDeviceInfo
import app.birdo.vpn.data.auth.DeviceInfoProvider
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.*
import app.birdo.vpn.data.network.AroundTunnel
import app.birdo.vpn.di.NetworkModule
import app.birdo.vpn.shared.model.LoginResult
import app.birdo.vpn.shared.model.PlanFeature
import app.birdo.vpn.testing.StringsXml
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
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
        // The refresh runs in the repository's own scope (round 7). Put that
        // scope on runTest's scheduler — runTest adopts a test Main's — or the
        // virtual clock races a real IO thread: a withTimeout would fire
        // before the refresh ever got a turn.
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        repository.refreshScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
    fun `a 503 that says when to come back carries the wait`() = runTest {
        // birdo-web PR #590: a free connect while the quota check is down.
        coEvery { api.getServers() } returns Response.error(
            503,
            """{"statusCode":503,"error":"quota_check_unavailable","message":"Try again shortly","details":{"retryable":true,"retryAfterSeconds":30}}"""
                .toResponseBody("application/json".toMediaType()),
        )

        val error = repository.getServers(forceRefresh = true) as ApiResult.Error

        assertEquals(503, error.code)
        assertEquals(30_000L, error.retryAfterMs)
        assertFalse(error.message.startsWith("{"))
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

    /**
     * Round 6 P3-3, then round 7. The server has consumed the presented
     * refresh token by the time it answers. A caller cancelled mid-flight (a
     * live rebuild's /connect cut short) used to drop the rotated pair, and
     * the next refresh replayed the used token: the server's theft detection,
     * an account-wide revoke. Round 6 kept the rotation by running it
     * NonCancellable in the caller, which made the caller wait it out: its
     * withTimeout or cancel() stopped nothing until OkHttp's callTimeout.
     * Both halves: the caller is gone the moment it is cancelled, and the
     * rotation still lands.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a caller cancelled mid-refresh stops waiting at once and the rotated tokens are still stored`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "old_refresh"
        val answer = CompletableDeferred<Unit>()
        coEvery { api.refreshToken(any()) } coAnswers {
            answer.await()
            Response.success(RefreshResponse(accessToken = "new_access", refreshToken = "new_refresh", expiresIn = 3600))
        }

        val caller = launch { repository.refreshToken() }
        runCurrent()
        coVerify(exactly = 1) { api.refreshToken(any()) }
        caller.cancel()
        runCurrent()
        val stoppedWaiting = caller.isCompleted
        // Answered before asserting: a caller that waits the refresh out would
        // otherwise hang the test instead of failing it.
        answer.complete(Unit)
        runCurrent()

        assertTrue("a cancelled caller must stop waiting for the refresh", stoppedWaiting)
        verify(exactly = 1) { tokenManager.setTokens("new_access", "new_refresh") }
    }

    /**
     * Round 7. A caller that gives up while still queued behind another
     * refresh has put nothing on the wire, so nothing needs finishing: it must
     * not spend a rotation of its own once the lock frees.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a caller cancelled while queued behind a refresh sends no refresh of its own`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "old_refresh"
        val answer = CompletableDeferred<Unit>()
        coEvery { api.refreshToken(any()) } coAnswers {
            answer.await()
            Response.success(RefreshResponse(accessToken = "new_access", refreshToken = "new_refresh", expiresIn = 3600))
        }

        launch { repository.refreshToken() }
        val queued = launch { repository.refreshToken() }
        runCurrent()
        queued.cancel()
        answer.complete(Unit)
        runCurrent()

        coVerify(exactly = 1) { api.refreshToken(any()) }
    }

    /**
     * Round 7, the case the finding named: logout()'s 15 s budget used to
     * stretch to the refresh's own callTimeout when its 401 → refresh step
     * stalled. Logout must return at its budget, and the refresh that outlives
     * it must not write its rotated pair back over the sign-out.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `logout returns at its budget while its refresh is stalled and the late rotation does not resurrect the session`() = runTest {
        var access: String? = "old_access"
        var refresh: String? = "old_refresh"
        every { tokenManager.getAccessToken() } answers { access }
        every { tokenManager.getRefreshToken() } answers { refresh }
        every { tokenManager.clearAll() } answers { access = null; refresh = null }
        every { tokenManager.setTokens(any(), any()) } answers { access = firstArg(); refresh = secondArg() }
        coEvery { api.logout() } returns
            Response.error(401, "Unauthorized".toResponseBody("text/plain".toMediaType()))
        val answer = CompletableDeferred<Unit>()
        coEvery { api.refreshToken(any()) } coAnswers {
            answer.await()
            Response.success(RefreshResponse(accessToken = "new_access", refreshToken = "new_refresh", expiresIn = 3600))
        }
        // Bounds the old behaviour: without this a caller that waits the
        // refresh out would hang the test instead of failing it.
        backgroundScope.launch {
            delay(60_000)
            answer.complete(Unit)
        }

        val startedAt = currentTime
        repository.logout()

        assertEquals(
            "logout must stop waiting for a stalled refresh at its own budget",
            BirdoRepository.LOGOUT_SERVER_CALL_TIMEOUT_MS,
            currentTime - startedAt,
        )
        answer.complete(Unit)
        runCurrent()
        assertNull("the late rotation must not write a signed-out session back", refresh)
        verify(exactly = 0) { tokenManager.setTokens(any(), any()) }
    }

    /**
     * Review of #463. Sign-out runs disconnectForSignOut, bounded at 10 s, and
     * then logout(). The disconnect's DELETE got a 401, and its refresh is
     * still on the wire when the bound gives up on it: the server has already
     * consumed the refresh token it presented. logout() used to bump the
     * session generation before its own server call, so that refresh's fence
     * dropped the rotated pair, the consumed token stayed on disk, and
     * logout's own 401 → refresh presented it AGAIN (a reuse: a soft 401 in
     * the server's rotation grace, an account-wide revoke past it).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `logout after a sign-out disconnect timed out mid-refresh never replays the consumed refresh token`() = runTest {
        var access: String? = "old_access"
        var refresh: String? = "old_refresh"
        every { tokenManager.getAccessToken() } answers { access }
        every { tokenManager.getRefreshToken() } answers { refresh }
        every { tokenManager.getLastKeyId() } returns "key-1"
        every { tokenManager.clearAll() } answers { access = null; refresh = null }
        every { tokenManager.setTokens(any(), any()) } answers { access = firstArg(); refresh = secondArg() }
        val unauthorized = "Unauthorized".toResponseBody("text/plain".toMediaType())
        // The server 401s anything carrying the expired access token.
        coEvery { api.disconnect(any()) } coAnswers {
            if (access == "old_access") Response.error(401, unauthorized) else Response.success(Unit)
        }
        coEvery { api.logout() } coAnswers {
            if (access == "old_access") Response.error(401, unauthorized) else Response.success(Unit)
        }
        val presented = mutableListOf<String>()
        val answer = CompletableDeferred<Unit>()
        coEvery { api.refreshToken(any()) } coAnswers {
            presented += firstArg<RefreshRequest>().refreshToken
            answer.await()
            Response.success(RefreshResponse(accessToken = "new_access", refreshToken = "new_refresh", expiresIn = 3600))
        }

        // VpnManager.disconnectForSignOut's bound gives up on the DELETE while
        // its refresh is on the wire.
        val signOutDisconnect = launch { kotlinx.coroutines.withTimeoutOrNull(10_000) { repository.disconnectVpn("key-1") } }
        runCurrent()
        assertEquals(listOf("old_refresh"), presented)
        advanceTimeBy(10_001)
        assertTrue(signOutDisconnect.isCompleted)

        // The orphaned refresh is answered while logout is under way.
        backgroundScope.launch {
            delay(2_000)
            answer.complete(Unit)
        }
        repository.logout()

        assertEquals("the consumed refresh token must not be presented twice", listOf("old_refresh"), presented)
        // …and the logout reached the server on the rotated pair.
        coVerify(exactly = 2) { api.logout() }
        assertNull(refresh)
    }

    /** Review of #463: every sign-in writes its pair under the refresh fence's lock. */
    @Test
    fun `every sign-in stores its tokens under the session lock`() = runTest {
        val lockField = BirdoRepository::class.java.getDeclaredField("sessionLock").apply { isAccessible = true }
        val sessionLock = lockField.get(repository)
        val heldWhileWriting = mutableListOf<Boolean>()
        every { tokenManager.setTokens(any(), any()) } answers { heldWhileWriting += Thread.holdsLock(sessionLock) }
        val pair = TokenPair("access_tok", "refresh_tok")
        coEvery { api.login(any()) } returns Response.success(LoginResponse(ok = true, tokens = pair))
        coEvery { api.exchangeNativeOAuth(any()) } returns Response.success(LoginResponse(ok = true, tokens = pair))
        coEvery { api.verifyTwoFactor(any()) } returns
            Response.success(TwoFactorVerifyResponse(ok = true, tokens = pair))
        coEvery { api.loginAnonymous(any()) } returns
            Response.success(AnonymousLoginResponse(ok = true, tokens = pair))
        coEvery { api.registerAnonymous(any()) } returns
            Response.success(AnonymousLoginResponse(ok = true, tokens = pair))

        repository.login("user@test.com", "pass123")
        repository.exchangeNativeOAuth("code", "verifier")
        repository.verifyTwoFactor("challenge", "123456")
        repository.loginAnonymous("123456789012345678901234")
        repository.registerAnonymous()

        assertEquals(listOf(true, true, true, true, true), heldWhileWriting)
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

    /**
     * Round 7, item 2: the 401 branch is fenced like the success branch. A
     * refresh outlives its cancelled caller; the user signs out and straight
     * back in; THEN the old refresh is rejected. The token it presented is the
     * old account's and already gone — clearing on that 401 wiped the new
     * account's pair and signed the user out of the session they just opened.
     */
    @Test
    fun `a 401 for a refresh that outlived a sign-out and sign-in leaves the new account's tokens alone`() = runTest {
        var access: String? = "old_access"
        var refresh: String? = "old_refresh"
        every { tokenManager.getAccessToken() } answers { access }
        every { tokenManager.getRefreshToken() } answers { refresh }
        every { tokenManager.clearAll() } answers { access = null; refresh = null }
        every { tokenManager.setTokens(any(), any()) } answers { access = firstArg(); refresh = secondArg() }
        coEvery { api.logout() } returns Response.success(Unit)
        coEvery { api.login(any()) } returns Response.success(
            LoginResponse(ok = true, tokens = TokenPair("new_account_access", "new_account_refresh")),
        )
        val answer = CompletableDeferred<Unit>()
        coEvery { api.refreshToken(any()) } coAnswers {
            answer.await()
            Response.error(401, "Unauthorized".toResponseBody("text/plain".toMediaType()))
        }

        val caller = launch { repository.refreshToken() }
        runCurrent()
        caller.cancel()
        repository.logout()
        repository.login("next@test.com", "pass123")
        answer.complete(Unit)
        runCurrent()

        assertEquals("new_account_access", access)
        assertEquals("new_account_refresh", refresh)
        verify(exactly = 1) { tokenManager.clearAll() } // logout's own, nothing more
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

    // ── MR-1771: a refresh 429 is a rate limit, never a verdict on the token ──

    /** A non-2xx /auth/refresh answer, with a Retry-After header when [retryAfter] is set. */
    private fun refreshAnswer(status: Int, retryAfter: String? = null): Response<RefreshResponse> {
        val raw = okhttp3.Response.Builder()
            .code(status)
            .message("refused")
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .request(okhttp3.Request.Builder().url("https://api.birdo.app/auth/refresh").build())
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .build()
        val body = """{"statusCode":$status,"message":"Too many requests"}"""
        return Response.error(body.toResponseBody("application/json".toMediaType()), raw)
    }

    /**
     * birdo-web #739 answers every /auth/refresh rate limit with 429 and
     * Retry-After (it was a 401, which every client read as "token dead").
     * The 429 comes before the token is read, so the stored pair must stay
     * exactly as it is, the caller must get a non-401 failure that says how
     * long to wait (checkSession signs out only on a 401), and no refresh may
     * be re-sent before the wait is over — the heartbeat's next 401 used to
     * ask again 30 s later and earn another 429. Once it is over, the next
     * 401 refreshes again.
     */
    @Test
    fun `a refresh 429 keeps the stored session and is not re-sent before its Retry-After`() = runTest {
        var now = 1_000_000L
        repository.clock = { now }
        var access: String? = "expired_access"
        var refresh: String? = "live_refresh"
        every { tokenManager.getAccessToken() } answers { access }
        every { tokenManager.getRefreshToken() } answers { refresh }
        every { tokenManager.clearAll() } answers { access = null; refresh = null }
        every { tokenManager.setTokens(any(), any()) } answers { access = firstArg(); refresh = secondArg() }
        every { tokenManager.setAccessToken(any()) } answers { access = firstArg() }
        val profile = UserProfile(id = "1", email = "user@test.com")
        coEvery { api.getProfile() } coAnswers {
            if (access == "expired_access") {
                Response.error(401, "Unauthorized".toResponseBody("text/plain".toMediaType()))
            } else {
                Response.success(profile)
            }
        }
        coEvery { api.refreshToken(any()) } returns refreshAnswer(429, retryAfter = "60")

        val first = repository.getProfile() as ApiResult.Error

        assertNotEquals("a 401 here is what signs the user out", 401, first.code)
        assertEquals(FailureReason.SERVER_UNAVAILABLE, first.reason)
        assertEquals(60_000L, first.retryAfterMs)
        assertEquals("expired_access", access)
        assertEquals("live_refresh", refresh)
        verify(exactly = 0) { tokenManager.clearAll() }
        verify(exactly = 0) { tokenManager.setTokens(any(), any()) }
        verify(exactly = 0) { tokenManager.setAccessToken(any()) }

        // 59 s later: still held. The session is kept and no refresh is sent.
        now += 59_000L
        val held = repository.getProfile() as ApiResult.Error
        assertNotEquals(401, held.code)
        assertEquals(1_000L, held.retryAfterMs)
        assertEquals(RefreshOutcome.TRANSIENT, repository.refreshToken())
        coVerify(exactly = 1) { api.refreshToken(any()) }
        assertEquals("live_refresh", refresh)

        // The wait is over: the next 401 refreshes, and the session goes on.
        now += 1_000L
        coEvery { api.refreshToken(any()) } returns Response.success(
            RefreshResponse(accessToken = "new_access", refreshToken = "new_refresh", expiresIn = 3600),
        )
        val after = repository.getProfile()
        assertTrue("$after", after is ApiResult.Success)
        coVerify(exactly = 2) { api.refreshToken(any()) }
        assertEquals("new_refresh", refresh)
    }

    @Test
    fun `a refresh 429 waits the retryable default when it names no wait, and ten minutes at most`() = runTest {
        var now = 5_000L
        repository.clock = { now }
        coEvery { tokenManager.getRefreshToken() } returns "live_refresh"
        coEvery { tokenManager.getAccessToken() } returns "expired_access"
        coEvery { api.getProfile() } returns Response.error(401, "Unauthorized".toResponseBody("text/plain".toMediaType()))

        // No header, or an HTTP date (the device clock is not the server's): the default.
        for (header in listOf(null, "Wed, 21 Oct 2026 07:28:00 GMT")) {
            coEvery { api.refreshToken(any()) } returns refreshAnswer(429, retryAfter = header)
            val e = repository.getProfile() as ApiResult.Error
            assertEquals("Retry-After: $header", RetryAfter.RETRYABLE_DEFAULT_MS, e.retryAfterMs)
            now += RetryAfter.RETRYABLE_DEFAULT_MS
        }
        // A day: clamped, so a wrong header cannot park the session's refresh for hours.
        coEvery { api.refreshToken(any()) } returns refreshAnswer(429, retryAfter = "86400")
        assertEquals(RetryAfter.MAX_MS, (repository.getProfile() as ApiResult.Error).retryAfterMs)
        // A clock set back before the hold began ends it rather than stretching it.
        now -= 1_000L
        repository.getProfile()
        coVerify(exactly = 4) { api.refreshToken(any()) }
        verify(exactly = 0) { tokenManager.clearAll() }
    }

    @Test
    fun `a refresh 503 with Retry-After is waited out too, and one without it is not held`() = runTest {
        var now = 0L
        repository.clock = { now }
        coEvery { tokenManager.getRefreshToken() } returns "live_refresh"
        coEvery { api.refreshToken(any()) } returns refreshAnswer(503, retryAfter = "20")
        assertEquals(RefreshOutcome.TRANSIENT, repository.refreshToken())
        assertEquals(RefreshOutcome.TRANSIENT, repository.refreshToken())
        coVerify(exactly = 1) { api.refreshToken(any()) }

        now += 20_000L
        coEvery { api.refreshToken(any()) } returns refreshAnswer(503)
        assertEquals(RefreshOutcome.TRANSIENT, repository.refreshToken())
        assertEquals(RefreshOutcome.TRANSIENT, repository.refreshToken())
        coVerify(exactly = 3) { api.refreshToken(any()) }
        verify(exactly = 0) { tokenManager.clearAll() }
    }

    /**
     * Unchanged by MR-1771 (lead decision 2026-10-23): a refresh 403 still
     * ends the session — the caller gets "Session expired" — but leaves the
     * stored tokens alone, as before.
     */
    @Test
    fun `a refresh 403 still signs out and leaves the tokens`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "live_refresh"
        coEvery { api.refreshToken(any()) } returns refreshAnswer(403)

        assertEquals(RefreshOutcome.UNAUTHORIZED, repository.refreshToken())
        verify(exactly = 0) { tokenManager.clearAll() }
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
        coEvery { api.deleteAccount(any(), any()) } returns Response.success(
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
        coEvery { api.deleteAccount(any(), any()) } returns Response.success(DeleteAccountResponse(success = true))

        repository.deleteAccount("pass123", "123456")
        repository.deleteAccount("pass123")

        coVerify(exactly = 1) { api.deleteAccount(DeleteAccountRequest("pass123", "123456"), AroundTunnel) }
        coVerify(exactly = 1) { api.deleteAccount(DeleteAccountRequest("pass123", null), AroundTunnel) }
    }

    @Test
    fun `failed deleteAccount keeps the device identity`() = runTest {
        val err = "nope".toResponseBody("text/plain".toMediaType())
        coEvery { api.deleteAccount(any(), any()) } returns Response.error(400, err)

        repository.deleteAccount("wrong-pass")

        // The account still exists — its slot reclamation depends on the id
        // staying stable, so a refused deletion must not rotate it.
        verify(exactly = 0) { deviceInfoProvider.resetDeviceIdentity() }
        verify(exactly = 0) { deviceInfoProvider.forgetPostQuantumKeypair() }
    }

    // ── ML-KEM keypair rotation (audit 2026-09-29, D-15) ────────

    @Test
    fun `deleteAccount success forgets the ML-KEM keypair`() = runTest {
        coEvery { api.deleteAccount(any(), any()) } returns Response.success(
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
        // The private key is generated locally and handed back for this session
        // only: it is never written to the token store (A1-041).
        assertNotNull((result as ApiResult.Success).data.privateKey)
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

    @Test
    fun `both connect routes turn a 503 psk_delivery_unavailable into a retryable error with the server's wait`() = runTest {
        // MR-1639: vpn.controller.ts rethrowPskDeliveryRefusal, on /vpn/connect
        // and /vpn/multi-hop/connect alike. No Retry-After header: the wait is
        // only in details.
        fun <T> refusal(): Response<T> = Response.error(
            503,
            ("""{"statusCode":503,"message":"We could not set up a secure connection to this server just now. """ +
                """Please try again in a moment.","error":"psk_delivery_unavailable","timestamp":"2026-10-13T09:00:00.000Z",""" +
                """"path":"/vpn/connect","details":{"retryable":true,"retryAfterSeconds":30}}""")
                .toResponseBody("application/json".toMediaType()),
        )
        coEvery { api.connect(any()) } returns refusal()
        coEvery { api.connectMultiHop(any()) } returns refusal()

        val single = repository.connectVpn(serverNodeId = "server_1", quantumProtection = true, pqClientPublicKey = "pq")
            as ApiResult.Error
        val multi = repository.connectMultiHop("de-1", "nl-1", quantumProtection = true, pqClientPublicKey = "pq")
            as ApiResult.Error

        for (error in listOf(single, multi)) {
            assertEquals(503, error.code)
            assertEquals(ConnectErrorCode.PSK_DELIVERY_UNAVAILABLE, error.serverCode)
            assertEquals(FailureReason.SERVER_UNAVAILABLE, error.reason)
            assertEquals(30_000L, error.retryAfterMs)
            assertEquals(StringsXml.text("error_secure_setup_unavailable"), error.message)
        }
        // A refusal mints nothing: no key id is recorded for a peer the server rolled back.
        verify(exactly = 0) { tokenManager.setLastKeyId(any()) }
    }

    @Test
    fun `both connect routes turn a 503 tunnel_address_unavailable into a retryable error with the server's 15 s`() = runTest {
        // MR-2058: vpn.controller.ts rethrowRetryableConnectRefusal for
        // TunnelAddressUnavailableError (birdo-web #711), on /vpn/connect and
        // /vpn/multi-hop/connect alike. It used to decode as UNKNOWN.
        fun <T> refusal(): Response<T> = Response.error(
            503,
            ("""{"statusCode":503,"message":"We could not assign your connection an address on this server """ +
                """just now. Please try again in a moment.","error":"tunnel_address_unavailable",""" +
                """"timestamp":"2026-10-15T09:00:00.000Z","path":"/vpn/connect",""" +
                """"details":{"retryable":true,"retryAfterSeconds":15}}""")
                .toResponseBody("application/json".toMediaType()),
        )
        coEvery { api.connect(any()) } returns refusal()
        coEvery { api.connectMultiHop(any()) } returns refusal()

        val single = repository.connectVpn(serverNodeId = "server_1") as ApiResult.Error
        val multi = repository.connectMultiHop("de-1", "nl-1") as ApiResult.Error

        for (error in listOf(single, multi)) {
            assertEquals(503, error.code)
            assertEquals("tunnel_address_unavailable", error.serverCode.wire)
            assertTrue(error.serverCode.retryable)
            assertEquals(FailureReason.SERVER_UNAVAILABLE, error.reason)
            assertEquals(15_000L, error.retryAfterMs)
            assertEquals(StringsXml.text("error_server_unavailable"), error.message)
        }
        verify(exactly = 0) { tokenManager.setLastKeyId(any()) }
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
        coVerify(exactly = 0) { api.heartbeat(any(), any()) }
    }

    @Test
    fun `a heartbeat names the session's own key when given one`() = runTest {
        coEvery { tokenManager.getLastKeyId() } returns "stored"
        coEvery { api.heartbeat("session-key", any()) } returns Response.success(HeartbeatResponse())

        repository.sendHeartbeat("session-key")

        coVerify { api.heartbeat("session-key", null) }
        coVerify(exactly = 0) { api.heartbeat("stored", any()) }
    }

    /** REVIEW-AND2-001: the dead-tunnel probe carries the tag that sends it around the tunnel. */
    @Test
    fun `only the dead-tunnel probe is tagged to go around the tunnel`() = runTest {
        coEvery { api.heartbeat(any(), any()) } returns Response.success(HeartbeatResponse())

        repository.sendHeartbeat("session-key", aroundTunnel = true)
        repository.sendHeartbeat("session-key")

        coVerify(exactly = 1) { api.heartbeat("session-key", AroundTunnel) }
        coVerify(exactly = 1) { api.heartbeat("session-key", null) }
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
        verify(exactly = 0) { tokenManager.setLastKeyId(any()) }
    }

    /**
     * MR-139: birdo-web #739 types the refusal (`refusalReason`), so the
     * device cap is known by its code on both connect routes, however the
     * server words it; another reason keeps the server's sentence even when
     * the words look like the cap's.
     */
    @Test
    fun `both connect routes tell the device cap by refusalReason, not by the wording`() = runTest {
        val reworded = "Your plan allows 5 devices and all of them are connected."
        coEvery { api.connect(any()) } returns Response.success(
            ConnectResponse(success = false, message = reworded, refusalReason = "device_limit"),
        )
        coEvery { api.connectMultiHop(any()) } returns Response.success(
            MultiHopConnectResponse(success = false, message = reworded, refusalReason = "device_limit"),
        )

        val single = repository.connectVpn("server_1") as ApiResult.Success
        val multi = repository.connectMultiHop("entry_1", "exit_1") as ApiResult.Success

        assertEquals(StringsXml.text("error_device_limit"), single.data.message)
        assertEquals(StringsXml.text("error_device_limit"), multi.data.message)
        verify(exactly = 0) { tokenManager.setLastKeyId(any()) }

        val full = "Device limit reached on this server's pool. Pick another server."
        coEvery { api.connect(any()) } returns Response.success(
            ConnectResponse(success = false, message = full, refusalReason = "server_capacity"),
        )
        assertEquals(full, (repository.connectVpn("server_1") as ApiResult.Success).data.message)
    }

    // ── MR-1517: the plan-gating table every gate reads ──────────

    @Test
    fun `the client config's plan table is kept for every gate, and only a newer table replaces it`() = runTest {
        fun config(body: String) = NetworkModule.json.decodeFromString<ClientConfigResponse>(body)
        assertNull("nothing fetched yet: the built-in table", repository.cachedPlanGates())

        coEvery { api.getClientConfig(any()) } returns Response.success(
            config("""{"features":{"SOVEREIGN":{"multiHop":false,"portForwarding":true}}}"""),
        )
        repository.getClientConfig()
        val table = mapOf("SOVEREIGN" to mapOf(PlanFeature.MULTI_HOP to false, PlanFeature.PORT_FORWARDING to true))
        assertEquals(table, repository.cachedPlanGates())

        // An older deploy without `features`, an HTTP error and no network all keep it.
        coEvery { api.getClientConfig(any()) } returns Response.success(config("""{"dnsFilteringAvailable":true}"""))
        assertTrue(repository.getClientConfig() is ApiResult.Success)
        assertEquals(table, repository.cachedPlanGates())
        coEvery { api.getClientConfig(any()) } returns Response.error(503, "down".toResponseBody("text/plain".toMediaType()))
        assertTrue(repository.getClientConfig() is ApiResult.Error)
        coEvery { api.getClientConfig(any()) } throws IOException("offline")
        assertTrue(repository.getClientConfig() is ApiResult.Error)
        assertEquals(table, repository.cachedPlanGates())

        coEvery { api.getClientConfig(any()) } returns Response.success(
            config("""{"features":{"OPERATIVE":{"multiHop":true}}}"""),
        )
        repository.getClientConfig()
        assertEquals(mapOf("OPERATIVE" to mapOf(PlanFeature.MULTI_HOP to true)), repository.cachedPlanGates())
    }
}
