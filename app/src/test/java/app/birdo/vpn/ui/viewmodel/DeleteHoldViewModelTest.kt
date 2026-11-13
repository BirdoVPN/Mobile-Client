package app.birdo.vpn.ui.viewmodel

import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.DeleteAccountResponse
import app.birdo.vpn.data.model.DeletionPreflightResponse
import app.birdo.vpn.data.model.DeletionStepUp
import app.birdo.vpn.data.model.StoreSubscriptionStillBilling
import app.birdo.vpn.data.model.UserProfile
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.data.repository.DeletionHoldResult
import app.birdo.vpn.data.repository.FailureReason
import app.birdo.vpn.data.repository.PendingDeletion
import app.birdo.vpn.testing.StringsXml
import app.birdo.vpn.ui.screen.offersDeletionHold
import app.birdo.vpn.ui.screen.pendingDeletionWhen
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * MR-179 hold fallback (birdo-web #883): "Delete in 24 hours (I lost my
 * account number)". The rule that matters most: a hold is NOT a deletion.
 * The user stays signed in (and so connected: the graph tears the tunnel down
 * only on [AuthUiState.accountDeleted]), the dialog closes and Profile shows
 * when the account will be deleted, with Cancel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteHoldViewModelTest {

    private lateinit var repository: BirdoRepository
    private lateinit var tokenManager: TokenManager
    private lateinit var prefs: AppPreferences
    private val testDispatcher = UnconfinedTestDispatcher()

    private val anon = UserProfile(id = "u1", email = "anon_ab@anonymous.local", name = "Anonymous", hasPassword = false)
    private val passwordUser = UserProfile(id = "u3", email = "user@birdo.app", name = "User")

    private val tomorrow: Instant = Instant.now().plus(Duration.ofHours(24))
    private val held = PendingDeletion("p1", tomorrow)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        every { prefs.hasAcceptedCurrentConsent } returns true
        every { tokenManager.isLoggedIn() } returns true
        every { tokenManager.getPendingAnonymousId() } returns null
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModelFor(profile: UserProfile): AuthViewModel {
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)
        return AuthViewModel(repository, tokenManager, mockk(relaxed = true), prefs, StringsXml)
    }

    // ── Offered only when the preflight says so ─────────────────

    @Test
    fun `the hold is offered only when the preflight says holdAvailable true`() {
        fun preflight(stepUp: DeletionStepUp?) = DeletionPreflightResponse(success = true, stepUp = stepUp)
        assertTrue(offersDeletionHold(preflight(DeletionStepUp(listOf("account_number"), enforced = true, holdAvailable = true))))
        assertFalse(offersDeletionHold(preflight(DeletionStepUp(listOf("account_number"), enforced = true))))
        assertFalse(offersDeletionHold(preflight(null)))
        assertFalse(offersDeletionHold(null))
    }

    // ── 202: held, not deleted ──────────────────────────────────

    @Test
    fun `a hold keeps the user signed in, closes the dialog and shows when`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.requestDeletionHold(null, null) } returns
            ApiResult.Success(DeletionHoldResult.Held(held, alreadyPending = false))

        vm.requestDeletionHold("")

        val state = vm.uiState.value
        assertTrue("a hold is not a sign-out", state.isLoggedIn)
        assertFalse("a hold is not a deletion: the tunnel stays up", state.accountDeleted)
        assertEquals(held, state.pendingDeletion)
        assertTrue(state.deletionHoldFiled)
        assertFalse(state.isDeletingAccount)
        assertNull(state.deleteAccountError)
        verify(exactly = 0) { repository.forgetErasedAccount() }
        coVerify(exactly = 0) { repository.logout() }
        coVerify(exactly = 0) { repository.deleteAccount(any(), any(), any(), any()) }

        vm.onDeletionHoldShown()
        assertFalse(vm.uiState.value.deletionHoldFiled)
        assertEquals("the banner keeps the hold", held, vm.uiState.value.pendingDeletion)
    }

    @Test
    fun `409 change_already_pending shows the hold that already waits`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.requestDeletionHold(null, null) } returns
            ApiResult.Success(DeletionHoldResult.Held(held, alreadyPending = true))

        vm.requestDeletionHold("")

        assertEquals(held, vm.uiState.value.pendingDeletion)
        assertTrue(vm.uiState.value.isLoggedIn)
        assertFalse(vm.uiState.value.accountDeleted)
    }

    @Test
    fun `a server that deleted at once ends in the account-deleted path`() = runTest {
        val vm = viewModelFor(anon)
        val billing = listOf(StoreSubscriptionStillBilling(store = "GOOGLE_PLAY"))
        coEvery { repository.requestDeletionHold(null, null) } returns ApiResult.Success(
            DeletionHoldResult.DeletedNow(DeleteAccountResponse(success = true, storeSubscriptionsStillBilling = billing)),
        )

        vm.requestDeletionHold("")

        assertFalse(vm.uiState.value.isLoggedIn)
        assertTrue(vm.uiState.value.accountDeleted)
        assertEquals(billing, vm.uiState.value.storeSubscriptionsStillBilling)
    }

    // ── Password and 2FA still required ─────────────────────────

    @Test
    fun `a password account must type its password, and it is sent with the hold`() = runTest {
        val vm = viewModelFor(passwordUser)

        vm.requestDeletionHold("")

        assertEquals(DeleteAccountField.PASSWORD, vm.uiState.value.deleteErrorField)
        coVerify(exactly = 0) { repository.requestDeletionHold(any(), any()) }

        coEvery { repository.requestDeletionHold("password1", null) } returns
            ApiResult.Success(DeletionHoldResult.Held(held, alreadyPending = false))
        vm.requestDeletionHold("password1")
        coVerify(exactly = 1) { repository.requestDeletionHold("password1", null) }
    }

    @Test
    fun `two_factor_required asks for the code with no error line, and the retry sends it`() = runTest {
        val vm = viewModelFor(passwordUser)
        coEvery { repository.requestDeletionHold("password1", null) } returns
            ApiResult.Error(StringsXml.text("delete_dialog_2fa_required"), 403, FailureReason.TWO_FACTOR_REQUIRED)

        vm.requestDeletionHold("password1")

        assertTrue(vm.uiState.value.deleteRequiresTwoFactor)
        assertNull(vm.uiState.value.deleteAccountError)
        assertNull(vm.uiState.value.pendingDeletion)
        assertTrue(vm.uiState.value.isLoggedIn)

        vm.requestDeletionHold("password1", "12")
        assertEquals(DeleteAccountField.CODE, vm.uiState.value.deleteErrorField)

        coEvery { repository.requestDeletionHold("password1", "123456") } returns
            ApiResult.Success(DeletionHoldResult.Held(held, alreadyPending = false))
        vm.requestDeletionHold("password1", "123456")
        assertEquals(held, vm.uiState.value.pendingDeletion)
    }

    @Test
    fun `a wrong password is drawn on the password field`() = runTest {
        val vm = viewModelFor(passwordUser)
        coEvery { repository.requestDeletionHold("wrong-pass", null) } returns
            ApiResult.Error(StringsXml.text("error_incorrect_password"), 401, FailureReason.INVALID_CREDENTIALS)

        vm.requestDeletionHold("wrong-pass")

        assertEquals(DeleteAccountField.PASSWORD, vm.uiState.value.deleteErrorField)
        assertTrue(vm.uiState.value.isLoggedIn)
    }

    // ── Failures say nothing was deleted ────────────────────────

    @Test
    fun `a 5xx or an unreadable answer says nothing was deleted, and the hold is re-read`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.requestDeletionHold(null, null) } returns
            ApiResult.Error(StringsXml.text("error_server_unavailable"), 500, FailureReason.SERVER_UNAVAILABLE)

        vm.requestDeletionHold("")

        assertEquals(StringsXml.text("delete_hold_failed"), vm.uiState.value.deleteAccountError)
        assertTrue(vm.uiState.value.isLoggedIn)
        assertFalse(vm.uiState.value.accountDeleted)

        // An unreadable 202: a hold may exist, so the list is asked.
        coEvery { repository.requestDeletionHold(null, null) } returns
            ApiResult.Error(StringsXml.text("error_unexpected"), 202, FailureReason.UNEXPECTED)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        vm.requestDeletionHold("")
        assertEquals(StringsXml.text("delete_hold_failed"), vm.uiState.value.deleteAccountError)
        assertEquals(held, vm.uiState.value.pendingDeletion)
    }

    @Test
    fun `409 hold_unavailable shows the server's own sentence`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.requestDeletionHold(null, null) } returns
            ApiResult.Error("Deleting in 24 hours is not available right now.", 409, FailureReason.REFUSED)

        vm.requestDeletionHold("")

        assertEquals("Deleting in 24 hours is not available right now.", vm.uiState.value.deleteAccountError)
        assertNull(vm.uiState.value.pendingDeletion)
        assertTrue(vm.uiState.value.isLoggedIn)
    }

    @Test
    fun `a plain deletion the server held instead is not a deletion, and the hold is read`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.deleteAccount(null, null, null, null) } returns
            ApiResult.Error(StringsXml.text("error_unexpected"), BirdoRepository.HTTP_ACCEPTED, FailureReason.UNEXPECTED)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)

        vm.deleteAccount("")

        assertTrue(vm.uiState.value.isLoggedIn)
        assertFalse(vm.uiState.value.accountDeleted)
        assertEquals(held, vm.uiState.value.pendingDeletion)
    }

    // ── The banner: listing and cancel ──────────────────────────

    @Test
    fun `the banner state follows the list`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)

        vm.refreshPendingDeletion()
        assertEquals(held, vm.uiState.value.pendingDeletion)

        coEvery { repository.pendingDeletion() } returns ApiResult.Success(null)
        vm.refreshPendingDeletion()
        assertNull(vm.uiState.value.pendingDeletion)

        // A failure keeps what is shown.
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        vm.refreshPendingDeletion()
        coEvery { repository.pendingDeletion() } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)
        vm.refreshPendingDeletion()
        assertEquals(held, vm.uiState.value.pendingDeletion)
        assertTrue(vm.uiState.value.isLoggedIn)
    }

    @Test
    fun `cancel removes the banner`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        vm.refreshPendingDeletion()
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(true)

        vm.cancelPendingDeletion()

        coVerify(exactly = 1) { repository.cancelPendingChange("p1") }
        assertNull(vm.uiState.value.pendingDeletion)
        assertFalse(vm.uiState.value.isCancellingPendingDeletion)
        assertTrue(vm.uiState.value.isLoggedIn)
    }

    @Test
    fun `a 404 on cancel means already gone, and the list is read again`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        vm.refreshPendingDeletion()
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(false)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(null)

        vm.cancelPendingDeletion()

        assertNull(vm.uiState.value.pendingDeletion)
        coVerify(atLeast = 2) { repository.pendingDeletion() }
    }

    @Test
    fun `a failed cancel keeps the banner and says why`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        vm.refreshPendingDeletion()
        coEvery { repository.cancelPendingChange("p1") } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)

        vm.cancelPendingDeletion()

        assertEquals(held, vm.uiState.value.pendingDeletion)
        assertEquals(StringsXml.text("error_unreachable"), vm.uiState.value.pendingDeletionError)
    }

    // ── After applyAfter ────────────────────────────────────────

    @Test
    fun `the session dying after the hold ran out is the account deleted, not an expiry`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(PendingDeletion("p1", Instant.now().minusSeconds(60)))
        vm.refreshPendingDeletion()

        vm.onSessionExpired()

        assertTrue(vm.uiState.value.accountDeleted)
        assertFalse(vm.uiState.value.sessionExpired)
        assertFalse(vm.uiState.value.isLoggedIn)
        verify(exactly = 1) { repository.forgetErasedAccount() }
    }

    @Test
    fun `the session dying before the hold ran out is still an expiry`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        vm.refreshPendingDeletion()

        vm.onSessionExpired()

        assertTrue(vm.uiState.value.sessionExpired)
        assertFalse(vm.uiState.value.accountDeleted)
        verify(exactly = 0) { repository.forgetErasedAccount() }
    }

    @Test
    fun `a 401 on the list after the hold ran out routes to the account-deleted path`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(PendingDeletion("p1", Instant.now().minusSeconds(60)))
        vm.refreshPendingDeletion()
        coEvery { repository.pendingDeletion() } returns
            ApiResult.Error(StringsXml.text("error_session_expired"), 401, FailureReason.SESSION_EXPIRED)

        vm.refreshPendingDeletion()

        assertTrue(vm.uiState.value.accountDeleted)
    }

    @Test
    fun `sign-out forgets the hold`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        vm.refreshPendingDeletion()

        vm.logout()

        assertNull(vm.uiState.value.pendingDeletion)
    }

    // ── The banner's date ───────────────────────────────────────

    @Test
    fun `the banner shows applyAfter in the device's zone`() {
        val at = Instant.parse("2026-11-14T12:00:00Z")
        val berlin = pendingDeletionWhen(at, ZoneId.of("Europe/Berlin"), Locale.UK)
        assertTrue(berlin, berlin.contains("13:00"))
        assertTrue(berlin, berlin.contains("2026"))
        val utc = pendingDeletionWhen(at, ZoneId.of("UTC"), Locale.UK)
        assertTrue(utc, utc.contains("12:00"))
        assertEquals(
            "Your account will be deleted on $berlin.",
            StringsXml.text("profile_pending_deletion_body").replace("%1\$s", berlin),
        )
    }
}
