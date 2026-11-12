package app.birdo.vpn.data.repository

import app.birdo.vpn.data.api.BirdoApi
import app.birdo.vpn.data.api.PowChallengeRequest
import app.birdo.vpn.data.api.PowChallengeResponse
import app.birdo.vpn.data.auth.ClientDeviceInfo
import app.birdo.vpn.data.auth.DeviceInfoProvider
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.AnonymousLoginRequest
import app.birdo.vpn.data.model.AnonymousLoginResponse
import app.birdo.vpn.data.model.LoginRequest
import app.birdo.vpn.data.model.LoginResponse
import app.birdo.vpn.data.model.TokenPair
import app.birdo.vpn.testing.StringsXml
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
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
import java.security.MessageDigest

/**
 * A-M78 / MR-2139: the sign-in proof-of-work during a failed-sign-in spike, on
 * `POST /auth/login/desktop` and `POST /auth/login/anonymous`. Same contract as
 * Desktop-Client #411: one request without a proof; only 429 `pow_required` for
 * op `login` is answered, with ONE retry carrying `pow`; the second answer is final.
 */
class LoginPowTest {

    private lateinit var api: BirdoApi
    private lateinit var repository: BirdoRepository
    private val pair = TokenPair("access_tok", "refresh_tok")
    private val sentLogin = mutableListOf<LoginRequest>()
    private val sentAnon = mutableListOf<AnonymousLoginRequest>()
    private val solved = mutableListOf<Pair<String, Int>>()

    private fun challenge(token: String, bits: Int = 8, op: String = "login") = Response.success(
        PowChallengeResponse("sha256-leading-zero-bits", op, token, bits, 300),
    )

    private fun envelope(status: Int, code: String, op: String?): String {
        val pow = if (op == null) "" else ""","pow":{"op":"$op","challengePath":"/auth/pow/challenge"}"""
        return """{"statusCode":$status,"message":"Too many sign-in attempts right now, please try again later","details":{"code":"$code"$pow}}"""
    }

    private fun <T> refusal(status: Int, code: String, op: String?): Response<T> =
        Response.error(status, envelope(status, code, op).toResponseBody("application/json".toMediaType()))

    private fun spike() = refusal<LoginResponse>(429, "pow_required", "login")
    private fun spikeAnon() = refusal<AnonymousLoginResponse>(429, "pow_required", "login")

    private fun ok() = Response.success(LoginResponse(ok = true, tokens = pair))
    private fun okAnon() = Response.success(AnonymousLoginResponse(ok = true, tokens = pair))

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        val deviceInfo = mockk<DeviceInfoProvider>(relaxed = true)
        every { deviceInfo.current() } returns
            ClientDeviceInfo(deviceId = "dev1", deviceName = "Pixel", platformVersion = "15", appVersion = "1.4.40")
        repository = BirdoRepository(api, mockk<TokenManager>(relaxed = true), deviceInfo, ApiErrorMapper(StringsXml))
        repository.powSolver = { c, b -> solved += c to b; "n" + solved.size }
        coEvery { api.login(capture(sentLogin)) } returns ok()
        coEvery { api.loginAnonymous(capture(sentAnon)) } returns okAnon()
    }

    // ── The solver against the SERVER's own login challenges ─────────────

    /**
     * Minted by birdo-web `backend/src/auth/signup-pow.ts` (origin/main) under
     * node: `mintPowToken(key, 'login', bits, fixed time and id)`, `solvePow`
     * for the nonce, `powDigest` for the digest; `parsePowToken` accepts each
     * as op `login`. The same three as Desktop-Client #411.
     */
    private data class LoginVector(val bits: Int, val token: String, val nonce: String, val digestHex: String)

    private val vectors = listOf(
        LoginVector(
            8,
            "p1.7f386f0d.login.8.1800000000.00112233445566778899aabbccddeeff.Gfoljd9Ww43uMw5hpkBNr2An9STpqSYJ2Hnlc5i-5Is",
            "2s",
            "000f1d00ae40e44661710c5571d460c633457e289be052afb6eb8c09cb307e3c",
        ),
        LoginVector(
            12,
            "p1.7f386f0d.login.12.1800000000.00112233445566778899aabbccddeeff.uzPNudqEP3ETCZQrBjWZGyxE4pCxo9X508ma6BGIxYA",
            "42o",
            "0000bf9e8d08ce6e71fdb0890b8431e8c7a11e86677710ce44b07b4a478155e1",
        ),
        LoginVector(
            16,
            "p1.7f386f0d.login.16.1800000000.00112233445566778899aabbccddeeff.U3WUdfGStXSHBuaTSNGYsd8xEgYbwS1-qswaNsieWVk",
            "5g7e",
            "00004ff5903c46e393a9ec3ae7578474503b68a6c63fe513b83dce38a44b79e0",
        ),
    )

    @Test
    fun `the solver finds the very nonce the server finds for each login challenge`() = runBlocking {
        for (v in vectors) {
            val digest = MessageDigest.getInstance("SHA-256").digest("${v.token}:${v.nonce}".toByteArray(Charsets.UTF_8))
            assertEquals(v.digestHex, digest.joinToString("") { "%02x".format(it) })
            assertTrue(SignupPow.meets(v.token, v.nonce, v.bits))
            assertEquals("bits=${v.bits}", v.nonce, SignupPow.solve(v.token, v.bits, Dispatchers.Default))
        }
    }

    // ── The envelope ─────────────────────────────────────────────────────

    @Test
    fun `the envelope parser reads pow op, and none when there is no pow member`() {
        assertEquals("login", ApiErrorMapper.parseEnvelope(envelope(429, "pow_required", "login"))?.powOp)
        assertEquals("signup", ApiErrorMapper.parseEnvelope(envelope(429, "pow_required", "signup"))?.powOp)
        val limited = ApiErrorMapper.parseEnvelope("""{"statusCode":429,"message":"x","details":{"code":"pow_rate_limited"}}""")
        assertEquals("pow_rate_limited", limited?.code)
        assertNull(limited?.powOp)
    }

    // ── Retry once with the proof ────────────────────────────────────────

    @Test
    fun `a sign-in spike is solved and the sign-in retried once with pow`() = runTest {
        coEvery { api.powChallenge(PowChallengeRequest("login")) } returns challenge("tok1")
        coEvery { api.login(capture(sentLogin)) } returnsMany listOf(spike(), ok())

        val result = repository.login("a@b.co", "pw-123456")

        assertTrue(result is ApiResult.Success)
        assertEquals(2, sentLogin.size)
        assertNull("the first try carries no proof", sentLogin[0].pow)
        assertEquals("tok1", sentLogin[1].pow?.challenge)
        assertEquals("n1", sentLogin[1].pow?.nonce)
        assertEquals(listOf("tok1" to 8), solved)
        coVerify(exactly = 1) { api.powChallenge(any()) }
    }

    @Test
    fun `an anonymous sign-in spike is solved and retried once with pow`() = runTest {
        coEvery { api.powChallenge(PowChallengeRequest("login")) } returns challenge("tok1")
        coEvery { api.loginAnonymous(capture(sentAnon)) } returnsMany listOf(spikeAnon(), okAnon())

        val result = repository.loginAnonymous("123456789012345678901234", null)

        assertTrue(result is ApiResult.Success)
        assertEquals(2, sentAnon.size)
        assertNull(sentAnon[0].pow)
        assertEquals("tok1", sentAnon[1].pow?.challenge)
    }

    @Test
    fun `a success sends once without a proof and fetches nothing`() = runTest {
        assertTrue(repository.login("a@b.co", "pw-123456") is ApiResult.Success)
        assertEquals(1, sentLogin.size)
        assertNull(sentLogin[0].pow)
        coVerify(exactly = 0) { api.powChallenge(any()) }
    }

    @Test
    fun `progress is reported around the sign-in solve`() = runTest {
        coEvery { api.powChallenge(any()) } returns challenge("tok1")
        coEvery { api.login(capture(sentLogin)) } returnsMany listOf(spike(), ok())
        val events = mutableListOf<Boolean>()
        repository.signupPowProgress = { events += it }

        repository.login("a@b.co", "pw-123456")

        assertEquals(listOf(true, false), events)
    }

    @Test
    fun `a second refusal is the answer, pow goes in the body once and nothing loops`() = runTest {
        for (second in listOf("pow_required", "pow_replayed", "pow_expired", "pow_invalid")) {
            sentLogin.clear()
            solved.clear()
            coEvery { api.powChallenge(any()) } returns challenge("tok1")
            var call = 0
            coEvery { api.login(capture(sentLogin)) } answers {
                if (++call == 1) spike() else refusal(if (second == "pow_required") 429 else 400, second, "login")
            }

            val result = repository.login("a@b.co", "pw-123456")

            assertTrue(second, result is ApiResult.Error)
            assertEquals(second, 2, sentLogin.size)
            assertEquals(second, 1, sentLogin.count { it.pow != null })
            assertEquals(second, 1, solved.size)
        }
        coVerify(exactly = 4) { api.powChallenge(any()) }
    }

    // ── No solve for anything else ───────────────────────────────────────

    @Test
    fun `nothing is solved unless it is a 429 pow_required for login`() = runTest {
        val cases = listOf(
            Triple(429, "pow_required", "signup"),
            Triple(429, "pow_required", null),
            Triple(429, "pow_required", "other"),
            Triple(429, "pow_invalid", "login"),
            Triple(429, "pow_expired", "login"),
            Triple(429, "pow_rate_limited", null),
            Triple(400, "pow_required", "login"),
            Triple(401, "invalid_credentials", null),
            Triple(403, "pow_required", "login"),
        )
        for ((status, code, op) in cases) {
            sentLogin.clear()
            coEvery { api.powChallenge(any()) } returns challenge("tok1")
            coEvery { api.login(capture(sentLogin)) } answers { refusal(status, code, op) }

            val result = repository.login("a@b.co", "pw-123456")

            assertTrue("$status $code $op", result is ApiResult.Error)
            assertEquals("$status $code $op", 1, sentLogin.size)
        }
        coVerify(exactly = 0) { api.powChallenge(any()) }
        assertTrue(solved.isEmpty())
    }

    @Test
    fun `an unusable challenge returns the original refusal`() = runTest {
        val unusable: List<() -> Response<PowChallengeResponse>> = listOf(
            { challenge("tok1", 32) },
            { challenge("tok1", 4) },
            { challenge("tok1", 8, op = "signup") },
            { Response.error(503, """{"details":{"code":"pow_unavailable"}}""".toResponseBody("application/json".toMediaType())) },
        )
        for (make in unusable) {
            sentLogin.clear()
            coEvery { api.powChallenge(any()) } answers { make() }
            coEvery { api.login(capture(sentLogin)) } answers { spike() }

            val result = repository.login("a@b.co", "pw-123456")

            assertTrue(result is ApiResult.Error)
            assertEquals(1, sentLogin.size)
        }
        coEvery { api.powChallenge(any()) } throws IOException("offline")
        sentLogin.clear()
        coEvery { api.login(capture(sentLogin)) } answers { spike() }
        assertTrue(repository.login("a@b.co", "pw-123456") is ApiResult.Error)
        assertEquals(1, sentLogin.size)
        assertTrue(solved.isEmpty())
    }

    @Test
    fun `the refusal an unusable challenge leaves is the same one the screen would have shown`() = runTest {
        coEvery { api.powChallenge(any()) } returns challenge("tok1", 32)
        coEvery { api.login(capture(sentLogin)) } answers { spike() }
        val unsolved = repository.login("a@b.co", "pw-123456") as ApiResult.Error

        coEvery { api.login(capture(sentLogin)) } answers { spike() }
        coEvery { api.powChallenge(any()) } throws IOException("offline")
        val failedFetch = repository.login("a@b.co", "pw-123456") as ApiResult.Error

        assertEquals(unsolved.reason, failedFetch.reason)
        assertEquals(unsolved.message, failedFetch.message)
    }

    // ── Cancel ───────────────────────────────────────────────────────────

    @Test
    fun `cancelling during the solve sends nothing more and clears the progress`() = runTest {
        coEvery { api.powChallenge(any()) } returns challenge("tok1")
        coEvery { api.login(capture(sentLogin)) } answers { spike() }
        val inSolver = CompletableDeferred<Unit>()
        repository.powSolver = { _, _ -> inSolver.complete(Unit); awaitCancellation() }
        val events = mutableListOf<Boolean>()
        repository.signupPowProgress = { events += it }

        val job = async(start = CoroutineStart.UNDISPATCHED) { repository.login("a@b.co", "pw-123456") }
        inSolver.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals("only the first, proof-less try was sent", 1, sentLogin.size)
        assertEquals(listOf(true, false), events)
    }
}
