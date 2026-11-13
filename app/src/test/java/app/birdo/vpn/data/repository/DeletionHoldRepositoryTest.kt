package app.birdo.vpn.data.repository

import app.birdo.vpn.data.api.BirdoApi
import app.birdo.vpn.data.auth.ClientDeviceInfo
import app.birdo.vpn.data.auth.DeviceInfoProvider
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.CancelPendingChangeResponse
import app.birdo.vpn.data.model.DeleteAccountRequest
import app.birdo.vpn.data.model.DeleteAccountResponse
import app.birdo.vpn.data.model.PendingChange
import app.birdo.vpn.data.model.PendingChangesResponse
import app.birdo.vpn.data.network.AroundTunnel
import app.birdo.vpn.di.NetworkModule
import app.birdo.vpn.testing.StringsXml
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.time.Instant

/**
 * MR-179 hold fallback (birdo-web #883): "Delete in 24 hours" for a user who
 * lost the account number. The same `DELETE api/v1/gdpr/delete` with
 * `stepUpMethod: "hold"` answers 202 `change_pending` (a hold, NOT a
 * deletion), 409 `change_already_pending` / `hold_unavailable`, or the usual
 * refusals, whose `stepUp` is an ARRAY where the hold's is an OBJECT.
 *
 * The rule pinned here: only a 2xx that is not the hold clears anything. A
 * hold, a refusal and an answer that cannot be read leave the session, the
 * tokens and the device identity exactly as they were.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletionHoldRepositoryTest {

    private lateinit var api: BirdoApi
    private lateinit var tokenManager: TokenManager
    private lateinit var deviceInfoProvider: DeviceInfoProvider
    private lateinit var repository: BirdoRepository

    private val applyAfter = "2026-11-14T12:00:00.000Z"

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
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        repository.refreshScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A 2xx body through the app's REAL wire Json, as Retrofit would decode it. */
    private fun answered(status: Int, body: String): Response<DeleteAccountResponse> =
        Response.success(status, NetworkModule.json.decodeFromString(serializer<DeleteAccountResponse>(), body))

    private fun refused(status: Int, body: String): Response<DeleteAccountResponse> =
        Response.error(status, body.toResponseBody("application/json".toMediaType()))

    private val held202 =
        """{"success":true,"pending":true,"code":"change_pending","message":"Your account will be deleted in 24 hours unless you cancel it.","stepUp":{"methods":["hold"],"pendingChangeId":"p1","applyAfter":"$applyAfter"}}"""

    private fun assertNothingCleared() {
        verify(exactly = 0) { tokenManager.clearAll() }
        verify(exactly = 0) { deviceInfoProvider.resetDeviceIdentity() }
        verify(exactly = 0) { deviceInfoProvider.forgetPostQuantumKeypair() }
    }

    // ── 202: a hold, not a deletion ─────────────────────────────

    @Test
    fun `a hold request sends stepUpMethod hold with the password and code, on the deletion route`() = runTest {
        coEvery { api.deleteAccount(any(), any()) } returns answered(202, held202)

        repository.requestDeletionHold("pass123", "123456")

        coVerify(exactly = 1) {
            api.deleteAccount(DeleteAccountRequest("pass123", "123456", stepUpMethod = "hold"), AroundTunnel)
        }
        assertEquals(
            """{"password":"pass123","twoFactorCode":"123456","stepUpMethod":"hold"}""",
            NetworkModule.json.encodeToString(
                serializer<DeleteAccountRequest>(),
                DeleteAccountRequest("pass123", "123456", stepUpMethod = "hold"),
            ),
        )
    }

    @Test
    fun `202 change_pending is a hold with its id and time, and clears nothing`() = runTest {
        coEvery { api.deleteAccount(any(), any()) } returns answered(202, held202)

        val result = repository.requestDeletionHold(null)

        val held = (result as ApiResult.Success).data as DeletionHoldResult.Held
        assertEquals(PendingDeletion("p1", Instant.parse(applyAfter)), held.pending)
        assertFalse(held.alreadyPending)
        assertNothingCleared()
    }

    @Test
    fun `a 202 whose stepUp cannot be read is an error, never a success or a deletion`() = runTest {
        for (body in listOf(
            """{"success":true,"pending":true,"code":"change_pending","stepUp":["hold"]}""",
            """{"success":true,"pending":true,"code":"change_pending","stepUp":{"pendingChangeId":"","applyAfter":"$applyAfter"}}""",
            """{"success":true,"pending":true,"code":"change_pending","stepUp":{"pendingChangeId":"p1","applyAfter":"tomorrow"}}""",
            """{"success":true,"pending":true,"code":"change_pending"}""",
        )) {
            coEvery { api.deleteAccount(any(), any()) } returns answered(202, body)

            val result = repository.requestDeletionHold(null)

            assertTrue(body, result is ApiResult.Error)
            assertEquals(body, 202, (result as ApiResult.Error).code)
        }
        assertNothingCleared()
    }

    @Test
    fun `a 202 to a plain deletion is not a deletion either`() = runTest {
        coEvery { api.deleteAccount(any(), any()) } returns answered(202, held202)

        val result = repository.deleteAccount("pass123")

        assertTrue(result is ApiResult.Error)
        assertEquals(BirdoRepository.HTTP_ACCEPTED, (result as ApiResult.Error).code)
        assertNothingCleared()
    }

    @Test
    fun `a 200 with the hold's markers is still not a deletion`() = runTest {
        coEvery { api.deleteAccount(any(), any()) } returns answered(200, held202)

        val result = repository.requestDeletionHold(null)

        assertTrue((result as ApiResult.Success).data is DeletionHoldResult.Held)
        assertNothingCleared()
    }

    // ── 200: a server with the step-up off deleted at once ──────

    @Test
    fun `a plain 200 deletion answering a hold request finishes the deletion`() = runTest {
        coEvery { api.deleteAccount(any(), any()) } returns
            answered(200, """{"success":true,"message":"deleted","deletedItems":3,"storeSubscriptionsStillBilling":[]}""")

        val result = repository.requestDeletionHold(null)

        assertTrue((result as ApiResult.Success).data is DeletionHoldResult.DeletedNow)
        verify(exactly = 1) { tokenManager.clearAll() }
        verify(exactly = 1) { deviceInfoProvider.resetDeviceIdentity() }
    }

    // ── 409 ─────────────────────────────────────────────────────

    @Test
    fun `409 change_already_pending is the hold already waiting, from stepUp or details`() = runTest {
        for (body in listOf(
            """{"statusCode":409,"error":"change_already_pending","message":"A deletion is already on hold.","stepUp":{"methods":["hold"],"pendingChangeId":"p0","applyAfter":"$applyAfter"}}""",
            """{"statusCode":409,"error":"change_already_pending","message":"x","details":{"stepUp":{"pendingChangeId":"p0","applyAfter":"$applyAfter"}}}""",
            """{"statusCode":409,"error":"change_already_pending","message":"x","details":{"pendingChangeId":"p0","applyAfter":"$applyAfter"}}""",
        )) {
            coEvery { api.deleteAccount(any(), any()) } returns refused(409, body)

            val held = (repository.requestDeletionHold(null) as ApiResult.Success).data as DeletionHoldResult.Held

            assertEquals(body, PendingDeletion("p0", Instant.parse(applyAfter)), held.pending)
            assertTrue(held.alreadyPending)
        }
        assertNothingCleared()
    }

    /** The API's exception filter passes only `details` through: the contract's `stepUp` may not arrive. */
    @Test
    fun `409 change_already_pending with no stepUp on the wire reads the hold from the list`() = runTest {
        coEvery { api.deleteAccount(any(), any()) } returns refused(
            409,
            """{"statusCode":409,"message":"A deletion is already on hold. Cancel it first to ask for a different one.","error":"change_already_pending","timestamp":"t","path":"/api/v1/gdpr/delete"}""",
        )
        coEvery { api.pendingChanges() } returns Response.success(
            PendingChangesResponse(listOf(PendingChange("p0", "account_delete", "delete the account", applyAfter, applyAfter))),
        )

        val held = (repository.requestDeletionHold(null) as ApiResult.Success).data as DeletionHoldResult.Held

        assertEquals("p0", held.pending.id)
        assertTrue(held.alreadyPending)
    }

    @Test
    fun `409 hold_unavailable shows the server's sentence and deletes nothing`() = runTest {
        coEvery { api.deleteAccount(any(), any()) } returns refused(
            409,
            """{"statusCode":409,"error":"hold_unavailable","message":"Deleting in 24 hours is not available right now."}""",
        )

        val result = repository.requestDeletionHold(null) as ApiResult.Error

        assertEquals(409, result.code)
        assertEquals("Deleting in 24 hours is not available right now.", result.message)
        assertNothingCleared()
    }

    // ── The refusals: stepUp is an ARRAY there ──────────────────

    @Test
    fun `the refusals carry a stepUp array and map as before, deleting nothing`() = runTest {
        coEvery { api.deleteAccount(any(), any()) } returns refused(
            403,
            """{"statusCode":403,"error":"two_factor_required","message":"Two-factor code required","stepUp":["password","two_factor"]}""",
        )
        assertEquals(FailureReason.TWO_FACTOR_REQUIRED, (repository.requestDeletionHold("pass123") as ApiResult.Error).reason)

        coEvery { api.deleteAccount(any(), any()) } returns refused(
            403,
            """{"statusCode":403,"error":"two_factor_invalid","message":"x","stepUp":["two_factor"]}""",
        )
        assertEquals(FailureReason.INVALID_CODE, (repository.requestDeletionHold("pass123", "000000") as ApiResult.Error).reason)

        coEvery { api.deleteAccount(any(), any()) } returns refused(
            429,
            """{"statusCode":429,"error":"too_many_attempts","message":"Too many attempts.","stepUp":["account_number"]}""",
        )
        assertEquals(FailureReason.RATE_LIMITED, (repository.requestDeletionHold(null) as ApiResult.Error).reason)

        coEvery { api.deleteAccount(any(), any()) } returns refused(
            403,
            """{"statusCode":403,"error":"step_up_no_factor","message":"We cannot verify this account for an in-app deletion. Please contact support to delete it.","stepUp":[]}""",
        )
        val noFactor = repository.requestDeletionHold(null) as ApiResult.Error
        assertEquals(FailureReason.REFUSED, noFactor.reason)
        assertEquals(403, noFactor.code)

        assertNothingCleared()
    }

    @Test
    fun `500 is a failure that deleted nothing`() = runTest {
        coEvery { api.deleteAccount(any(), any()) } returns
            refused(500, """{"statusCode":500,"message":"An unexpected error occurred","error":"Internal Server Error"}""")

        val result = repository.requestDeletionHold(null) as ApiResult.Error

        assertEquals(500, result.code)
        assertEquals(FailureReason.SERVER_UNAVAILABLE, result.reason)
        assertNothingCleared()
    }

    // ── The list and the cancel ─────────────────────────────────

    @Test
    fun `the list yields the account_delete entry and nothing else`() = runTest {
        coEvery { api.pendingChanges() } returns Response.success(
            PendingChangesResponse(
                listOf(
                    PendingChange("e1", "email_change", "change the e-mail address", "2026-11-14T10:00:00.000Z", applyAfter),
                    PendingChange("p1", "account_delete", "delete the account", applyAfter, applyAfter),
                ),
            ),
        )
        assertEquals(PendingDeletion("p1", Instant.parse(applyAfter)), (repository.pendingDeletion() as ApiResult.Success).data)

        coEvery { api.pendingChanges() } returns Response.success(PendingChangesResponse(emptyList()))
        assertNull((repository.pendingDeletion() as ApiResult.Success).data)

        val decoded = NetworkModule.json.decodeFromString(
            serializer<PendingChangesResponse>(),
            """{"pendingChanges":[{"id":"p1","action":"account_delete","description":"delete the account","applyAfter":"$applyAfter","createdAt":"2026-11-13T12:00:00.000Z"}]}""",
        )
        assertEquals("p1", DeletionHoldWire.accountDeletionIn(decoded.pendingChanges)?.id)
    }

    @Test
    fun `cancel is true on 200, false on 404 (already gone), an error otherwise`() = runTest {
        coEvery { api.cancelPendingChange("p1") } returns Response.success(CancelPendingChangeResponse(success = true, cancelled = true))
        assertEquals(true, (repository.cancelPendingChange("p1") as ApiResult.Success).data)

        coEvery { api.cancelPendingChange("p1") } returns Response.error(
            404,
            """{"statusCode":404,"error":"pending_change_not_found","message":"There is no such pending change on this account."}"""
                .toResponseBody("application/json".toMediaType()),
        )
        assertEquals(false, (repository.cancelPendingChange("p1") as ApiResult.Success).data)

        coEvery { api.cancelPendingChange("p1") } returns Response.error(500, "{}".toResponseBody("application/json".toMediaType()))
        assertTrue(repository.cancelPendingChange("p1") is ApiResult.Error)
        assertNothingCleared()
    }

    /** step-up.controller.ts: `@Controller('auth')`, unprefixed like every other auth route. */
    @Test
    fun `the pending-changes routes resolve to the auth controller`() {
        val base = "https://api.birdo.app/".toHttpUrl()
        val list = BirdoApi::class.java.methods.single { it.name == "pendingChanges" }
            .getAnnotation(retrofit2.http.GET::class.java)
        assertNotNull(list)
        assertEquals("https://api.birdo.app/auth/pending-changes", base.resolve(list!!.value).toString())
        val cancel = BirdoApi::class.java.methods.single { it.name == "cancelPendingChange" }
            .getAnnotation(retrofit2.http.POST::class.java)
        assertNotNull(cancel)
        assertEquals(
            "https://api.birdo.app/auth/pending-changes/p1/cancel",
            base.resolve(cancel!!.value.replace("{id}", "p1")).toString(),
        )
    }

    // ── The wire readers on their own ───────────────────────────

    @Test
    fun `stepUp is read as a hold only when it is an object with an id and an instant`() {
        val json = NetworkModule.json
        fun stepUp(raw: String) = json.parseToJsonElement(raw)
        assertEquals(
            PendingDeletion("p1", Instant.parse(applyAfter)),
            DeletionHoldWire.holdOf(stepUp("""{"methods":["hold"],"pendingChangeId":"p1","applyAfter":"$applyAfter"}""")),
        )
        assertEquals(
            Instant.parse("2026-11-14T11:00:00Z"),
            DeletionHoldWire.holdOf(stepUp("""{"pendingChangeId":"p1","applyAfter":"2026-11-14T12:00:00+01:00"}"""))?.applyAfter,
        )
        assertNull(DeletionHoldWire.holdOf(stepUp("""["account_number","two_factor"]""")))
        assertNull(DeletionHoldWire.holdOf(stepUp("""{"pendingChangeId":7,"applyAfter":"$applyAfter"}""")))
        assertNull(DeletionHoldWire.holdOf(stepUp("""{"pendingChangeId":"p1"}""")))
        assertNull(DeletionHoldWire.holdOf(stepUp("null")))
        assertNull(DeletionHoldWire.holdOf(null))
        assertNull(DeletionHoldWire.holdOfErrorBody("<html>502</html>"))
        assertNull(DeletionHoldWire.holdOfErrorBody("""{"error":"two_factor_required","stepUp":["two_factor"]}"""))
    }

    @Test
    fun `the deletion response decodes every stepUp shape without failing`() {
        val json = NetworkModule.json
        for (stepUp in listOf("""["password"]""", """{"pendingChangeId":"p1"}""", "null", "\"hold\"", "3")) {
            val decoded = json.decodeFromString(serializer<DeleteAccountResponse>(), """{"success":true,"stepUp":$stepUp}""")
            assertTrue(decoded.success)
        }
        val plain = json.decodeFromString(serializer<DeleteAccountResponse>(), """{"success":true,"deletedItems":2}""")
        assertFalse(DeletionHoldWire.isHoldAnswer(200, plain))
        assertTrue(DeletionHoldWire.isHoldAnswer(202, plain))
    }
}
