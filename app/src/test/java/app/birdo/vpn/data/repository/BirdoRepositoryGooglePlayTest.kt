package app.birdo.vpn.data.repository

import app.birdo.vpn.billing.GooglePlayLinkRequest
import app.birdo.vpn.billing.GooglePlayLinkResponse
import app.birdo.vpn.billing.StoreLinkOrigin
import app.birdo.vpn.billing.StoreLinkOutcome
import app.birdo.vpn.billing.StoreLinkRefusal
import app.birdo.vpn.data.api.BirdoApi
import app.birdo.vpn.data.auth.ClientDeviceInfo
import app.birdo.vpn.data.auth.DeviceInfoProvider
import app.birdo.vpn.data.auth.TokenManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The branch that decides whether a healthy session survives a Play link failure.
 *
 * WHY THIS EXISTS AS ITS OWN SUITE. StoreLinkRefusal.classify is pinned by 21
 * tests, but that is the PURE function. The code that actually runs in
 * production — extracting the coded error and deciding whether to refresh — had
 * no coverage at all, and an adversarial review called it out by name.
 *
 * WHAT IS AT STAKE. Refresh tokens are single-use and rotated. The backend
 * treats a REPLAYED refresh token as theft and revokes the whole family, which
 * signs the user out everywhere. So spending a refresh on an error that was
 * never about the session is not a harmless extra request — it is a logout.
 *
 * The rule the implementation encodes, and that these tests pin:
 *
 *   401 WITHOUT details.code  -> a real session problem, refresh and retry
 *   401 WITH    details.code  -> a Play/verification refusal wearing a 401,
 *                                 never touch the refresh path
 *
 * Both directions are asserted. Testing only the coded case would pass against
 * an implementation that never refreshes at all, which would break real
 * expired-session recovery.
 */
class BirdoRepositoryGooglePlayTest {

    private lateinit var api: BirdoApi
    private lateinit var tokenManager: TokenManager
    private lateinit var deviceInfoProvider: DeviceInfoProvider
    private lateinit var repository: BirdoRepository

    private fun errorBody(json: String) =
        json.toResponseBody("application/json".toMediaType())

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
        repository = BirdoRepository(
            api, tokenManager, deviceInfoProvider, ApiErrorMapper(app.birdo.vpn.testing.StringsXml),
        )
    }

    // ── the defect this suite exists for ─────────────────────────────

    @Test
    fun `a 401 carrying a coded refusal never spends the refresh token`() = runTest {
        // GOOGLE_PLAY_PURCHASE_NOT_FOUND is returned as 401 by the backend even
        // though the session is perfectly healthy - it is about the purchase,
        // not the user.
        coEvery { api.linkGooglePurchase(any()) } returns Response.error(
            401,
            errorBody(
                """{"statusCode":401,"message":"That Google Play purchase could not be found.",
                   "details":{"code":"GOOGLE_PLAY_PURCHASE_NOT_FOUND"}}"""
            ),
        )

        val outcome = repository.linkGooglePurchase("tok_not_found", StoreLinkOrigin.AUTO)

        assertTrue("expected a refusal, got $outcome", outcome is StoreLinkOutcome.Refused)
        // The whole point: the refresh path must not have been entered.
        coVerify(exactly = 0) { api.refreshToken(any()) }
        // ...and the link must not have been retried either.
        coVerify(exactly = 1) { api.linkGooglePurchase(any()) }
        // A healthy session must survive intact.
        verify(exactly = 0) { tokenManager.clearAll() }
    }

    @Test
    fun `an UNCODED 401 still refreshes, so real session expiry recovers`() = runTest {
        // The control. Without this, an implementation that simply never
        // refreshed would pass the test above while breaking session recovery.
        coEvery { api.linkGooglePurchase(any()) } returns Response.error(
            401, errorBody("""{"statusCode":401,"message":"Unauthorized"}"""),
        )

        repository.linkGooglePurchase("tok_expired_session", StoreLinkOrigin.AUTO)

        coVerify(atLeast = 1) { api.refreshToken(any()) }
    }

    @Test
    fun `a 409 already-linked is terminal and is not retried`() = runTest {
        // 409 is a refusal, and /link is rate limited 20/60s. Retrying it is
        // useless and burns the bucket.
        coEvery { api.linkGooglePurchase(any()) } returns Response.error(
            409,
            errorBody(
                """{"statusCode":409,"message":"This Google Play subscription is already linked to a
                   different Birdo account.","details":{"code":"STORE_TRANSACTION_ALREADY_LINKED"}}"""
            ),
        )

        val outcome = repository.linkGooglePurchase("tok_already_linked", StoreLinkOrigin.AUTO)

        assertTrue(outcome is StoreLinkOutcome.Refused)
        assertEquals(
            StoreLinkRefusal.ALREADY_LINKED_TO_ANOTHER_ACCOUNT,
            (outcome as StoreLinkOutcome.Refused).refusal,
        )
        coVerify(exactly = 1) { api.linkGooglePurchase(any()) }
        coVerify(exactly = 0) { api.refreshToken(any()) }
    }

    @Test
    fun `a 503 verification-unavailable is transient, not a sign-in prompt`() = runTest {
        // The Play Developer API being unreachable means we could not verify -
        // which is NOT the same as verification having failed. Telling a paying
        // user to sign in here would be wrong.
        coEvery { api.linkGooglePurchase(any()) } returns Response.error(
            503,
            errorBody(
                """{"statusCode":503,"message":"Could not reach Google to verify this purchase.",
                   "details":{"code":"GOOGLE_PLAY_VERIFICATION_UNAVAILABLE"}}"""
            ),
        )

        val outcome = repository.linkGooglePurchase("tok_google_down", StoreLinkOrigin.AUTO)

        assertTrue(outcome is StoreLinkOutcome.Refused)
        assertNotEquals(
            "a transient Google outage must never read as NEEDS_SIGN_IN",
            StoreLinkRefusal.NEEDS_SIGN_IN,
            (outcome as StoreLinkOutcome.Refused).refusal,
        )
        coVerify(exactly = 0) { api.refreshToken(any()) }
    }

    // ── F-967-02: the origin, and a server that predates it ─────────

    private val granted = GooglePlayLinkResponse(linked = true, plan = "OPERATIVE", state = "ACTIVE")

    @Test
    fun `the origin goes on the wire`() = runTest {
        val sent = mutableListOf<GooglePlayLinkRequest>()
        coEvery { api.linkGooglePurchase(capture(sent)) } returns Response.success(granted)

        repository.linkGooglePurchase("tok_restore", StoreLinkOrigin.RESTORE)
        repository.linkGooglePurchase("tok_auto", StoreLinkOrigin.AUTO)

        assertEquals(listOf("restore", "auto"), sent.map { it.origin })
    }

    @Test
    fun `an uncoded 400 from a server that predates origin is re-sent once, without it`() = runTest {
        // The live GooglePlayLinkDto declares only purchaseToken and the global
        // ValidationPipe runs forbidNonWhitelisted: an unknown key is a 400
        // with no details.code. Without the re-send, every /link would fail
        // until the web half is deployed.
        val sent = mutableListOf<GooglePlayLinkRequest>()
        coEvery { api.linkGooglePurchase(capture(sent)) } returnsMany listOf(
            Response.error(
                400,
                errorBody("""{"statusCode":400,"message":"property origin should not exist","error":"Bad Request"}"""),
            ),
            Response.success(granted),
        )

        val outcome = repository.linkGooglePurchase("tok_old_server", StoreLinkOrigin.AUTO)

        assertTrue("expected the re-sent request to be accepted, got $outcome", outcome is StoreLinkOutcome.Accepted)
        assertEquals(listOf("auto", null), sent.map { it.origin })
        assertEquals("tok_old_server", sent[1].purchaseToken)
        coVerify(exactly = 0) { api.refreshToken(any()) }
    }

    @Test
    fun `a 400 that still fails without origin is reported, not re-sent forever`() = runTest {
        coEvery { api.linkGooglePurchase(any()) } returns Response.error(
            400, errorBody("""{"statusCode":400,"message":"Bad Request"}"""),
        )

        val outcome = repository.linkGooglePurchase("tok_bad_request", StoreLinkOrigin.AUTO)

        assertTrue(outcome is StoreLinkOutcome.Refused)
        coVerify(exactly = 2) { api.linkGooglePurchase(any()) }
    }

    @Test
    fun `a CODED 400 is the server's answer and is not re-sent`() = runTest {
        coEvery { api.linkGooglePurchase(any()) } returns Response.error(
            400,
            errorBody("""{"statusCode":400,"message":"No.","details":{"code":"STORE_SOMETHING_SPECIFIC"}}"""),
        )

        repository.linkGooglePurchase("tok_coded_400", StoreLinkOrigin.AUTO)

        coVerify(exactly = 1) { api.linkGooglePurchase(any()) }
    }
}
