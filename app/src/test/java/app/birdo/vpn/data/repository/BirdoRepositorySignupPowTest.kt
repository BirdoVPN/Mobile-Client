package app.birdo.vpn.data.repository

import app.birdo.vpn.data.api.AnonymousRegisterRequest
import app.birdo.vpn.data.api.BirdoApi
import app.birdo.vpn.data.api.PowChallengeRequest
import app.birdo.vpn.data.api.PowChallengeResponse
import app.birdo.vpn.data.auth.ClientDeviceInfo
import app.birdo.vpn.data.auth.DeviceInfoProvider
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.AnonymousLoginResponse
import app.birdo.vpn.data.model.TokenPair
import app.birdo.vpn.testing.StringsXml
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** MR-2152: the sign-up proof-of-work around `POST /auth/register/anonymous`. */
class BirdoRepositorySignupPowTest {

    private lateinit var api: BirdoApi
    private lateinit var repository: BirdoRepository
    private val pair = TokenPair("access_tok", "refresh_tok")
    private val sent = mutableListOf<AnonymousRegisterRequest>()
    private val solved = mutableListOf<Pair<String, Int>>()

    private fun challenge(token: String, bits: Int = 20) = Response.success(
        PowChallengeResponse("sha256-leading-zero-bits", "signup", token, bits, 300),
    )

    private fun refusal(status: Int, code: String): Response<AnonymousLoginResponse> =
        Response.error(
            status,
            """{"statusCode":$status,"message":"The security check did not pass. Please try again.","details":{"code":"$code"}}"""
                .toResponseBody("application/json".toMediaType()),
        )

    private fun created() =
        Response.success(AnonymousLoginResponse(ok = true, anonymousId = "123456789012345678901234", tokens = pair))

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        val deviceInfo = mockk<DeviceInfoProvider>(relaxed = true)
        every { deviceInfo.current() } returns
            ClientDeviceInfo(deviceId = "dev1", deviceName = "Pixel", platformVersion = "15", appVersion = "1.4.37")
        repository = BirdoRepository(api, mockk<TokenManager>(relaxed = true), deviceInfo, ApiErrorMapper(StringsXml))
        repository.powSolver = { c, b -> solved += c to b; "n" + solved.size }
        coEvery { api.registerAnonymous(capture(sent)) } returns created()
    }

    @Test
    fun `sign-up carries the solved challenge as pow`() = runTest {
        coEvery { api.powChallenge(PowChallengeRequest("signup")) } returns challenge("tok1")

        val result = repository.registerAnonymous()

        assertTrue(result is ApiResult.Success)
        assertEquals(1, sent.size)
        assertEquals("tok1", sent[0].pow?.challenge)
        assertEquals("n1", sent[0].pow?.nonce)
        assertEquals(listOf("tok1" to 20), solved)
    }

    @Test
    fun `progress is reported around the solve`() = runTest {
        coEvery { api.powChallenge(any()) } returns challenge("tok1")
        val events = mutableListOf<Boolean>()
        repository.signupPowProgress = { events += it }

        repository.registerAnonymous()

        assertEquals(listOf(true, false), events)
    }

    @Test
    fun `a challenge endpoint failure signs up without a proof`() = runTest {
        coEvery { api.powChallenge(any()) } returns Response.error(
            503, """{"details":{"code":"pow_unavailable"}}""".toResponseBody("application/json".toMediaType()),
        )

        val result = repository.registerAnonymous()

        assertTrue(result is ApiResult.Success)
        assertNull(sent.single().pow)
        assertTrue(solved.isEmpty())
    }

    @Test
    fun `a challenge call that throws signs up without a proof`() = runTest {
        coEvery { api.powChallenge(any()) } throws IOException("offline")

        assertTrue(repository.registerAnonymous() is ApiResult.Success)
        assertNull(sent.single().pow)
    }

    @Test
    fun `a difficulty beyond what the app attempts signs up without a proof`() = runTest {
        coEvery { api.powChallenge(any()) } returns challenge("tok1", 32)

        repository.registerAnonymous()

        assertNull(sent.single().pow)
        assertTrue(solved.isEmpty())
    }

    @Test
    fun `retries once with a fresh challenge on each retryable refusal`() = runTest {
        for ((code, status) in listOf("pow_difficulty_raised" to 428, "pow_expired" to 400, "pow_replayed" to 409)) {
            sent.clear()
            solved.clear()
            coEvery { api.powChallenge(any()) } returnsMany listOf(challenge("first"), challenge("second", 21))
            coEvery { api.registerAnonymous(capture(sent)) } returnsMany listOf(refusal(status, code), created())

            val result = repository.registerAnonymous()

            assertTrue(code, result is ApiResult.Success)
            assertEquals(code, listOf<String?>("first", "second"), sent.map { it.pow?.challenge })
            assertEquals(code, listOf("first" to 20, "second" to 21), solved)
        }
    }

    @Test
    fun `after two refusals the sign-up is sent once without a proof, never a loop`() = runTest {
        coEvery { api.powChallenge(any()) } returnsMany listOf(challenge("first"), challenge("second"), challenge("third"))
        // A fresh Response each time: an error body can be read once.
        coEvery { api.registerAnonymous(capture(sent)) } answers { refusal(428, "pow_difficulty_raised") }

        val result = repository.registerAnonymous()

        assertTrue(result is ApiResult.Error)
        assertEquals(listOf<String?>("first", "second", null), sent.map { it.pow?.challenge })
        coVerify(exactly = 2) { api.powChallenge(any()) }
    }

    @Test
    fun `other refusals are not retried and keep their mapped message`() = runTest {
        coEvery { api.powChallenge(any()) } returns challenge("first")
        coEvery { api.registerAnonymous(capture(sent)) } answers { refusal(429, "registration_network_limited") }

        val result = repository.registerAnonymous()

        assertTrue(result is ApiResult.Error)
        assertEquals(1, sent.size)
        assertEquals(FailureReason.RATE_LIMITED, (result as ApiResult.Error).reason)
    }

    @Test
    fun `cancelling during the solve stops the sign-up and sends nothing`() = runTest {
        coEvery { api.powChallenge(any()) } returns challenge("first")
        val inSolver = CompletableDeferred<Unit>()
        repository.powSolver = { _, _ -> inSolver.complete(Unit); awaitCancellation() }
        val events = mutableListOf<Boolean>()
        repository.signupPowProgress = { events += it }

        val job = async(start = CoroutineStart.UNDISPATCHED) { repository.registerAnonymous() }
        inSolver.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(sent.isEmpty())
        // The status line is switched off even though the solve was cancelled.
        assertEquals(listOf(true, false), events)
    }
}
