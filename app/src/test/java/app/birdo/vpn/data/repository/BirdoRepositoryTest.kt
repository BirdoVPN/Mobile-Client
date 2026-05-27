package app.birdo.vpn.data.repository

import app.birdo.vpn.data.api.BirdoApi
import app.birdo.vpn.data.auth.ClientDeviceInfo
import app.birdo.vpn.data.auth.DeviceInfoProvider
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.*
import app.birdo.vpn.shared.model.LoginResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

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

    @Test
    fun `login network exception returns error`() = runTest {
        coEvery { api.login(any()) } throws java.net.SocketTimeoutException("Connection timed out")

        val result = repository.login("user@test.com", "pass")

        assertTrue(result is ApiResult.Error)
        assertEquals("Connection timed out", (result as ApiResult.Error).message)
    }

    // ── Refresh Token ───────────────────────────────────────────

    @Test
    fun `refreshToken success returns true and stores new token`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "old_refresh"
        coEvery { api.refreshToken(any()) } returns Response.success(
            RefreshResponse(accessToken = "new_access", expiresIn = 3600)
        )

        val result = repository.refreshToken()

        assertTrue(result)
        verify { tokenManager.setAccessToken("new_access") }
    }

    @Test
    fun `refreshToken with no refresh token returns false`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns null

        val result = repository.refreshToken()

        assertFalse(result)
    }

    @Test
    fun `refreshToken failure returns false`() = runTest {
        coEvery { tokenManager.getRefreshToken() } returns "old_refresh"
        val errorBody = "Invalid token".toResponseBody("text/plain".toMediaType())
        coEvery { api.refreshToken(any()) } returns Response.error(401, errorBody)

        val result = repository.refreshToken()

        assertFalse(result)
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
