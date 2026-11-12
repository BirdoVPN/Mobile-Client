package app.birdo.vpn.data.repository

import app.birdo.vpn.data.api.BirdoApi
import app.birdo.vpn.data.auth.ClientDeviceInfo
import app.birdo.vpn.data.auth.DeviceInfoProvider
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.shared.model.RefreshResponse
import app.birdo.vpn.shared.model.TokenPair
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * MR-1707: how each answer of the two rotation calls is classified, and the
 * 401 rule. A 401 the handler wrote (rotation_proof_invalid) is its verdict on
 * the proof and must never spend the single-use refresh token (a replayed
 * refresh token is read as theft); only a bare "Unauthorized" refreshes, once.
 */
class AnonNumberRotationTest {

    private lateinit var api: BirdoApi
    private lateinit var tokenManager: TokenManager
    private lateinit var repository: BirdoRepository

    private fun body(json: String) = json.toResponseBody("application/json".toMediaType())
    private fun <T> err(code: Int, json: String): Response<T> = Response.error(code, body(json))

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        val device = mockk<DeviceInfoProvider>(relaxed = true)
        every { device.current() } returns ClientDeviceInfo("dev", "Test", platformVersion = "15", appVersion = "1.0.0")
        every { tokenManager.getAccessToken() } returns "a-token"
        every { tokenManager.getRefreshToken() } returns "r-token"
        repository = BirdoRepository(api, tokenManager, device, ApiErrorMapper(app.birdo.vpn.testing.StringsXml))
    }

    private fun envelope(json: String) = ApiErrorMapper.parseEnvelope(json)

    // classification

    @Test
    fun `each documented refusal maps to its own failure`() {
        fun c(status: Int, json: String) = classifyRotationFailure(status, envelope(json))
        assertEquals(RotationFailure.DISABLED, c(403, """{"statusCode":403,"error":"anon_number_rotation_disabled","message":"x"}"""))
        assertEquals(RotationFailure.PROOF_INVALID, c(401, """{"statusCode":401,"error":"rotation_proof_invalid","message":"Invalid credentials"}"""))
        assertEquals(RotationFailure.NOT_PENDING, c(409, """{"statusCode":409,"error":"rotation_not_pending","message":"x"}"""))
        assertEquals(RotationFailure.ACCOUNT_CHANGED, c(409, """{"statusCode":409,"error":"rotation_account_changed","message":"x"}"""))
        assertEquals(RotationFailure.MISMATCH, c(400, """{"statusCode":400,"error":"rotation_number_mismatch","message":"x"}"""))
        assertEquals(RotationFailure.TOO_MANY, c(429, """{"statusCode":429,"message":"Too many attempts, please try later"}"""))
        assertEquals(RotationFailure.REJECTED, c(400, """{"statusCode":400,"message":"Only an anonymous account has a number to replace"}"""))
        assertEquals(RotationFailure.REJECTED, c(500, "<html>bad gateway</html>"))
    }

    @Test
    fun `only a bare Unauthorized is bare`() {
        assertTrue(isBareUnauthorized(envelope("""{"statusCode":401,"message":"Unauthorized"}""")))
        assertTrue(isBareUnauthorized(null))
        assertTrue(isBareUnauthorized(envelope("""{"statusCode":401,"error":"Unauthorized"}""")))
        assertFalse(isBareUnauthorized(envelope("""{"statusCode":401,"error":"rotation_proof_invalid","message":"Invalid credentials"}""")))
        assertFalse(isBareUnauthorized(envelope("""{"statusCode":401,"message":"No user","error":"Unauthorized"}""")))
        assertFalse(isBareUnauthorized(envelope("""{"statusCode":401,"message":"Unauthorized","details":{"code":"x"}}""")))
    }

    // the 401 rule, through the real repository

    @Test
    fun `a proof 401 on prepare is the handlers verdict and never refreshes or retries`() = runTest {
        coEvery { api.rotateNumberPrepare(any()) } returns
            err(401, """{"statusCode":401,"error":"rotation_proof_invalid","message":"Invalid credentials"}""")

        val r = repository.rotateNumberPrepare("111111111111111111111111", null, null)

        assertEquals(RotationResult.Refused(RotationFailure.PROOF_INVALID), r)
        coVerify(exactly = 0) { api.refreshToken(any()) }
        coVerify(exactly = 1) { api.rotateNumberPrepare(any()) }
        verify(exactly = 0) { tokenManager.clearAll() }
    }

    @Test
    fun `a message other than bare Unauthorized is not retried either`() = runTest {
        coEvery { api.rotateNumberConfirm(any()) } returns err(401, """{"statusCode":401,"message":"Invalid credentials"}""")

        val r = repository.rotateNumberConfirm("222222222222222222222222")

        assertEquals(RotationResult.Refused(RotationFailure.PROOF_INVALID), r)
        coVerify(exactly = 0) { api.refreshToken(any()) }
        coVerify(exactly = 1) { api.rotateNumberConfirm(any()) }
    }

    @Test
    fun `a bare 401 refreshes once and retries once`() = runTest {
        coEvery { api.rotateNumberPrepare(any()) } returnsMany listOf(
            err(401, """{"statusCode":401,"message":"Unauthorized"}"""),
            Response.success(RotatePrepareResponse(ok = true, newNumber = "222222222222222222222222", expiresInSeconds = 600)),
        )
        coEvery { api.refreshToken(any()) } returns Response.success(RefreshResponse("a2", "r2"))

        val r = repository.rotateNumberPrepare("111111111111111111111111", null, null)

        assertTrue("got $r", r is RotationResult.Ok)
        coVerify(exactly = 2) { api.rotateNumberPrepare(any()) }
        coVerify(exactly = 1) { api.refreshToken(any()) }
    }

    @Test
    fun `a bare 401 that stays 401 after the refresh is retried only once`() = runTest {
        coEvery { api.rotateNumberPrepare(any()) } returns err(401, """{"statusCode":401,"message":"Unauthorized"}""")
        coEvery { api.refreshToken(any()) } returns Response.success(RefreshResponse("a2", "r2"))

        val r = repository.rotateNumberPrepare("111111111111111111111111", null, null)

        assertEquals(RotationResult.Refused(RotationFailure.SESSION_EXPIRED), r)
        coVerify(exactly = 2) { api.rotateNumberPrepare(any()) }
        coVerify(exactly = 1) { api.refreshToken(any()) }
    }

    @Test
    fun `a transport failure on confirm is NETWORK, not a refusal of the number`() = runTest {
        coEvery { api.rotateNumberConfirm(any()) } throws java.io.IOException("reset")
        val r = repository.rotateNumberConfirm("222222222222222222222222")
        assertEquals(RotationResult.Refused(RotationFailure.NETWORK), r)
    }

    @Test
    fun `a successful confirm stores the fresh tokens`() = runTest {
        coEvery { api.rotateNumberConfirm(any()) } returns Response.success(
            RotateConfirmResponse(ok = true, tokens = TokenPair("new-access", "new-refresh")),
        )
        val r = repository.rotateNumberConfirm("222222222222222222222222")
        assertEquals(RotationResult.Ok(Unit), r)
        verify(exactly = 1) { tokenManager.setTokens("new-access", "new-refresh") }
    }

    @Test
    fun `a 200 with no tokens stores nothing and keeps the flow on step three`() = runTest {
        coEvery { api.rotateNumberConfirm(any()) } returns Response.success(RotateConfirmResponse(ok = true, tokens = null))
        val r = repository.rotateNumberConfirm("222222222222222222222222")
        assertEquals(RotationResult.Refused(RotationFailure.NETWORK), r)
        verify(exactly = 0) { tokenManager.setTokens(any(), any()) }
    }

    @Test
    fun `the wire bodies carry the contract field names and never print a number`() {
        val json = app.birdo.vpn.di.NetworkModule.json
        val prepare = json.encodeToString(RotatePrepareRequest.serializer(), RotatePrepareRequest("111111111111111111111111", "pw", "123456"))
        assertTrue(prepare.contains("\"currentNumber\"") && prepare.contains("\"twoFactorCode\""))
        val confirmed = json.decodeFromString(
            RotateConfirmResponse.serializer(),
            """{"ok":true,"tokens":{"access_token":"a","refresh_token":"r"}}""",
        )
        assertEquals("a", confirmed.tokens?.accessToken)
        assertFalse(RotatePrepareResponse(true, "222222222222222222222222", 600).toString().contains("2222"))
        assertFalse(RotatePrepareRequest("111111111111111111111111").toString().contains("1111"))
        assertFalse(RotateConfirmRequest("222222222222222222222222").toString().contains("2222"))
    }
}
