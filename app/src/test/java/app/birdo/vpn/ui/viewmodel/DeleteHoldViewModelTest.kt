package app.birdo.vpn.ui.viewmodel

import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.DeletionPreflightResponse
import app.birdo.vpn.data.model.DeletionStepUp
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
 * The user stays signed in (and so connected: the tunnel comes down only on a
 * confirmed erasure, BirdoRepository.accountErasures), the dialog closes and Profile shows
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
        every { tokenManager.getDeletionHoldFiledHere() } returns null
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
        // #883 final: the hold has its own switch, independent of `enforced`.
        assertTrue(offersDeletionHold(preflight(DeletionStepUp(listOf("account_number"), enforced = false, holdAvailable = true))))
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
        val sentence = "We could not tie this request to your signed-in session. Sign in to the app again, then try again. Nothing was deleted."
        coEvery { repository.requestDeletionHold(null, null) } returns ApiResult.Error(sentence, 409, FailureReason.REFUSED)

        vm.requestDeletionHold("")

        assertEquals(sentence, vm.uiState.value.deleteAccountError)
        assertNull(vm.uiState.value.pendingDeletion)
        assertTrue(vm.uiState.value.isLoggedIn)
        assertFalse(vm.uiState.value.accountDeleted)
    }

    @Test
    fun `429 past three holds a day shows the server's try-again-tomorrow`() = runTest {
        val vm = viewModelFor(anon)
        val tomorrow = "You have asked for a delayed deletion too many times today. Please try again tomorrow."
        coEvery { repository.requestDeletionHold(null, null) } returns ApiResult.Error(tomorrow, 429, FailureReason.RATE_LIMITED)

        vm.requestDeletionHold("")

        assertEquals(tomorrow, vm.uiState.value.deleteAccountError)
        assertTrue(vm.uiState.value.isLoggedIn)
        assertFalse(vm.uiState.value.accountDeleted)
    }

    @Test
    fun `503 step_up_unavailable says nothing was deleted, try again`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.requestDeletionHold(null, null) } returns
            ApiResult.Error(StringsXml.text("delete_hold_failed"), 503, FailureReason.SERVER_UNAVAILABLE)

        vm.requestDeletionHold("")

        assertEquals(StringsXml.text("delete_hold_failed"), vm.uiState.value.deleteAccountError)
        assertTrue(vm.uiState.value.isLoggedIn)
        assertNull(vm.uiState.value.pendingDeletion)
    }

    /** #883 final: the server auto-cancels a hold (sign-out, number rotation, password or 2FA change) or sweeps it. */
    @Test
    fun `a hold that left the list is simply gone, not an error`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        vm.refreshPendingDeletion()
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(null)

        vm.refreshPendingDeletion()

        assertNull(vm.uiState.value.pendingDeletion)
        assertNull(vm.uiState.value.pendingDeletionError)
        assertNull(vm.uiState.value.deleteAccountError)
        assertTrue(vm.uiState.value.isLoggedIn)
        assertFalse(vm.uiState.value.accountDeleted)
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
        // iOS #624's sentence for a cancel with no answer.
        assertEquals(StringsXml.text("hold_cancel_unreachable"), vm.uiState.value.pendingDeletionError)
    }

    // ── After applyAfter ────────────────────────────────────────

    /**
     * Review of #616 (the false positive): the user cancelled the hold from
     * the e-mail, and a day later the session died for another reason. That
     * is a plain expiry: no "account deleted", so the graph never runs the
     * deletion teardown (the kill-switch block stays, the device id and the
     * PQ key are kept). Only the wording admits a deletion may have happened.
     */
    @Test
    fun `the session dying after a remembered hold ran out is a plain expiry, never a deletion`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(PendingDeletion("p1", Instant.now().minusSeconds(60)))
        vm.refreshPendingDeletion()

        vm.onSessionExpired()

        val state = vm.uiState.value
        assertTrue(state.sessionExpired)
        assertFalse("a dead session is no proof of a deletion", state.accountDeleted)
        assertFalse(state.isLoggedIn)
        assertEquals(StringsXml.text("error_session_expired_after_hold"), state.error)
        coVerify(exactly = 0) { repository.logout() }
        coVerify(exactly = 0) { repository.deleteAccount(any(), any(), any(), any()) }
    }

    @Test
    fun `the session dying before the hold ran out is still an expiry`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        vm.refreshPendingDeletion()

        vm.onSessionExpired()

        assertTrue(vm.uiState.value.sessionExpired)
        assertFalse(vm.uiState.value.accountDeleted)
        assertEquals(StringsXml.text("error_session_expired"), vm.uiState.value.error)
    }

    @Test
    fun `a 401 on the list after the hold ran out is the plain expiry too`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(PendingDeletion("p1", Instant.now().minusSeconds(60)))
        vm.refreshPendingDeletion()
        coEvery { repository.pendingDeletion() } returns
            ApiResult.Error(StringsXml.text("error_session_expired"), 401, FailureReason.SESSION_EXPIRED)

        vm.refreshPendingDeletion()

        assertTrue(vm.uiState.value.sessionExpired)
        assertFalse(vm.uiState.value.accountDeleted)
    }

    /** Review of #616: a list read already in flight when the user cancels must not bring the hold back. */
    @Test
    fun `a list read in flight during a cancel cannot put the cancelled hold back`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        vm.refreshPendingDeletion()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.pendingDeletion() } coAnswers {
            gate.await()
            ApiResult.Success(held)
        }
        vm.refreshPendingDeletion() // suspended on the gate, still holding the old answer
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(true)

        vm.cancelPendingDeletion()
        gate.complete(Unit)

        assertNull(vm.uiState.value.pendingDeletion)
        assertFalse(vm.uiState.value.isCancellingPendingDeletion)
    }

    @Test
    fun `no list read starts while a cancel is in flight`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        vm.refreshPendingDeletion()
        val gate = CompletableDeferred<ApiResult<Boolean>>()
        coEvery { repository.cancelPendingChange("p1") } coAnswers { gate.await() }

        vm.cancelPendingDeletion()
        vm.refreshPendingDeletion() // e.g. ON_RESUME while the cancel is out
        gate.complete(ApiResult.Success(true))

        // The session-start read (MR-2367) and the one above, none during the cancel.
        coVerify(exactly = 2) { repository.pendingDeletion() }
        assertNull(vm.uiState.value.pendingDeletion)
    }

    @Test
    fun `sign-out forgets the hold`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        vm.refreshPendingDeletion()

        vm.logout()

        assertNull(vm.uiState.value.pendingDeletion)
    }

    // ── MR-2367: the hold is known from the start of the session ─

    /**
     * Cold start straight into Home, Profile never opened: Home's sign-out
     * confirmation reads the hold from this state, and it used to be filled
     * only when Profile opened, so signing out gave no "this cancels the
     * deletion" warning.
     */
    @Test
    fun `a cold start reads the hold once, so Home's sign-out can warn`() = runTest {
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)

        val vm = viewModelFor(anon)

        assertEquals(held, vm.uiState.value.pendingDeletion)
        coVerify(exactly = 1) { repository.pendingDeletion() }
    }

    @Test
    fun `a sign-in reads the hold once`() = runTest {
        every { tokenManager.isLoggedIn() } returns false
        val vm = viewModelFor(anon)
        coVerify(exactly = 0) { repository.pendingDeletion() }
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        coEvery { repository.loginAnonymous(any(), any()) } returns ApiResult.Success(
            app.birdo.vpn.data.model.AnonymousLoginResponse(ok = true),
        )

        vm.loginAnonymous("1".repeat(24))

        assertTrue(vm.uiState.value.isLoggedIn)
        assertEquals(held, vm.uiState.value.pendingDeletion)
        coVerify(exactly = 1) { repository.pendingDeletion() }
    }

    @Test
    fun `a dead session at cold start reads no hold`() = runTest {
        coEvery { repository.getProfile() } returns
            ApiResult.Error(StringsXml.text("error_session_expired"), 401, FailureReason.SESSION_EXPIRED)

        val vm = AuthViewModel(repository, tokenManager, mockk(relaxed = true), prefs, StringsXml)

        assertFalse(vm.uiState.value.isLoggedIn)
        coVerify(exactly = 0) { repository.pendingDeletion() }
    }

    // ── Review of #616: who filed the hold decides what sign-out does ──
    //
    // The server voids a hold only when the session that FILED it ends. A hold
    // asked for on the website or by another device survives a sign-out here,
    // so "signing out cancels it" may be said only of this session's own.

    @Test
    fun `a hold this session files is its own, remembered for a cold start`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.requestDeletionHold(null, null) } returns
            ApiResult.Success(DeletionHoldResult.Held(held, alreadyPending = false))

        vm.requestDeletionHold("")

        assertTrue(vm.uiState.value.pendingDeletionFiledHere)
        verify(exactly = 1) { tokenManager.setDeletionHoldFiledHere("p1") }
    }

    @Test
    fun `a hold that was already waiting is not claimed`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.requestDeletionHold(null, null) } returns
            ApiResult.Success(DeletionHoldResult.Held(held, alreadyPending = true))

        vm.requestDeletionHold("")

        assertEquals(held, vm.uiState.value.pendingDeletion)
        assertFalse("a 409 hold may be the website's", vm.uiState.value.pendingDeletionFiledHere)
        verify(exactly = 0) { tokenManager.setDeletionHoldFiledHere(any()) }
    }

    @Test
    fun `a cold start with this session's marker knows the hold is its own`() = runTest {
        every { tokenManager.getDeletionHoldFiledHere() } returns "p1"
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)

        val vm = viewModelFor(anon)

        assertEquals(held, vm.uiState.value.pendingDeletion)
        assertTrue(vm.uiState.value.pendingDeletionFiledHere)
        verify(exactly = 0) { tokenManager.clearDeletionHoldFiledHere() }
    }

    @Test
    fun `a hold filed on the website or another device is not this session's`() = runTest {
        // Nothing marked: the website filed it, with no session at all.
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)

        val vm = viewModelFor(anon)

        assertEquals(held, vm.uiState.value.pendingDeletion)
        assertFalse(vm.uiState.value.pendingDeletionFiledHere)
    }

    @Test
    fun `a marker for a hold that is gone is dropped, and a new hold under another id is not claimed`() = runTest {
        every { tokenManager.getDeletionHoldFiledHere() } returns "p-old"
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)

        val vm = viewModelFor(anon)

        assertFalse(vm.uiState.value.pendingDeletionFiledHere)
        assertNull(vm.uiState.value.deletionHoldFiledHereId)
        verify(exactly = 1) { tokenManager.clearDeletionHoldFiledHere() }
    }

    @Test
    fun `a plain deletion the server held is this session's, an already waiting one is not`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        coEvery { repository.deleteAccount(null, null, null, null) } returns
            ApiResult.Error(StringsXml.text("error_unexpected"), BirdoRepository.HTTP_ACCEPTED, FailureReason.UNEXPECTED)

        vm.deleteAccount("")

        assertTrue(vm.uiState.value.pendingDeletionFiledHere)
        verify(exactly = 1) { tokenManager.setDeletionHoldFiledHere("p1") }

        val other = viewModelFor(anon)
        coEvery { repository.deleteAccount(null, null, null, null) } returns
            ApiResult.Error(StringsXml.text("error_unexpected"), BirdoRepository.HTTP_CONFLICT, FailureReason.UNEXPECTED)
        other.deleteAccount("")

        assertEquals(held, other.uiState.value.pendingDeletion)
        assertFalse(other.uiState.value.pendingDeletionFiledHere)
        verify(exactly = 1) { tokenManager.setDeletionHoldFiledHere(any()) }
    }

    @Test
    fun `an unreadable or lost hold answer shows the hold but never claims it`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        coEvery { repository.requestDeletionHold(null, null) } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)

        vm.requestDeletionHold("")

        assertEquals(held, vm.uiState.value.pendingDeletion)
        assertFalse(vm.uiState.value.pendingDeletionFiledHere)
        verify(exactly = 0) { tokenManager.setDeletionHoldFiledHere(any()) }
    }

    @Test
    fun `cancelling this session's hold drops the marker`() = runTest {
        every { tokenManager.getDeletionHoldFiledHere() } returns "p1"
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        val vm = viewModelFor(anon)
        assertTrue(vm.uiState.value.pendingDeletionFiledHere)
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(true)

        vm.cancelPendingDeletion()

        assertNull(vm.uiState.value.pendingDeletion)
        assertFalse(vm.uiState.value.pendingDeletionFiledHere)
        verify(exactly = 1) { tokenManager.clearDeletionHoldFiledHere() }
    }

    @Test
    fun `cancel from the sign-out confirmation cancels a hold filed elsewhere too`() = runTest {
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        val vm = viewModelFor(anon)
        assertFalse(vm.uiState.value.pendingDeletionFiledHere)
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(true)

        vm.cancelPendingDeletion()

        coVerify(exactly = 1) { repository.cancelPendingChange("p1") }
        assertNull(vm.uiState.value.pendingDeletion)
    }

    @Test
    fun `a new sign-in has filed nothing`() = runTest {
        every { tokenManager.isLoggedIn() } returns false
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        coEvery { repository.loginAnonymous(any(), any()) } returns ApiResult.Success(
            app.birdo.vpn.data.model.AnonymousLoginResponse(ok = true),
        )

        vm.loginAnonymous("1".repeat(24))

        verify { tokenManager.clearDeletionHoldFiledHere() }
        assertFalse(vm.uiState.value.pendingDeletionFiledHere)
    }

    // ── MR-2378: sign-out cancels this session's own hold FIRST ──

    /** A cold start whose marker names the listed hold: this session filed it. */
    private fun viewModelWithOwnHold(): AuthViewModel {
        every { tokenManager.getDeletionHoldFiledHere() } returns "p1"
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        return viewModelFor(anon).also { assertTrue(it.uiState.value.pendingDeletionFiledHere) }
    }

    @Test
    fun `signing out with this session's hold cancels it first, then signs out`() = runTest {
        val vm = viewModelWithOwnHold()
        val gate = CompletableDeferred<ApiResult<Boolean>>()
        coEvery { repository.cancelPendingChange("p1") } coAnswers { gate.await() }
        var signedOut = 0

        vm.signOut { signedOut++ }
        assertEquals("nothing signs out before the cancel is confirmed", 0, signedOut)
        assertTrue(vm.uiState.value.isCancellingPendingDeletion)

        gate.complete(ApiResult.Success(true))

        assertEquals(1, signedOut)
        assertNull(vm.uiState.value.pendingDeletion)
        verify { tokenManager.clearDeletionHoldFiledHere() }
    }

    @Test
    fun `a failed cancel-first keeps the user signed in, then Retry signs out`() = runTest {
        val vm = viewModelWithOwnHold()
        coEvery { repository.cancelPendingChange("p1") } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)
        var signedOut = 0

        vm.signOut { signedOut++ }

        assertEquals("a failed cancel is no sign-out", 0, signedOut)
        assertTrue(vm.uiState.value.isLoggedIn)
        assertEquals(StringsXml.text("hold_cancel_unreachable"), vm.uiState.value.signOutHoldCancelError)
        assertEquals(held, vm.uiState.value.pendingDeletion)
        assertTrue(vm.uiState.value.pendingDeletionFiledHere)

        // Retry: the cancel goes through this time.
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(true)
        vm.signOut { signedOut++ }
        assertEquals(1, signedOut)
        assertNull(vm.uiState.value.signOutHoldCancelError)
        coVerify(exactly = 2) { repository.cancelPendingChange("p1") }
    }

    @Test
    fun `Sign out anyway signs out without another cancel`() = runTest {
        val vm = viewModelWithOwnHold()
        coEvery { repository.cancelPendingChange("p1") } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)
        var signedOut = 0
        vm.signOut { signedOut++ }

        vm.signOutAnyway { signedOut++ }

        assertEquals(1, signedOut)
        assertNull(vm.uiState.value.signOutHoldCancelError)
        coVerify(exactly = 1) { repository.cancelPendingChange("p1") }
    }

    @Test
    fun `closing the confirmation drops a failed cancel's message`() = runTest {
        val vm = viewModelWithOwnHold()
        coEvery { repository.cancelPendingChange("p1") } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)
        vm.signOut {}

        vm.dismissSignOutHoldError()

        assertNull(vm.uiState.value.signOutHoldCancelError)
    }

    @Test
    fun `signing out with a hold filed elsewhere signs out at once, cancelling nothing`() = runTest {
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        val vm = viewModelFor(anon)
        assertFalse(vm.uiState.value.pendingDeletionFiledHere)
        var signedOut = 0

        vm.signOut { signedOut++ }

        assertEquals(1, signedOut)
        coVerify(exactly = 0) { repository.cancelPendingChange(any()) }
    }

    @Test
    fun `sign-out drops this session's marker`() = runTest {
        val vm = viewModelWithOwnHold()

        vm.logout()

        verify { tokenManager.clearDeletionHoldFiledHere() }
        assertFalse(vm.uiState.value.pendingDeletionFiledHere)
        assertNull(vm.uiState.value.deletionHoldFiledHereId)
    }

    // ── MR-2378: the read is capped; a failed read promises nothing ──

    @Test
    fun `a failed read leaves the hold unknown, and a good one clears that`() = runTest {
        coEvery { repository.pendingDeletion() } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)

        val vm = viewModelFor(anon)

        assertTrue(vm.uiState.value.pendingDeletionUnknown)
        assertNull(vm.uiState.value.pendingDeletion)

        coEvery { repository.pendingDeletion() } returns ApiResult.Success(null)
        vm.refreshPendingDeletion()
        assertFalse(vm.uiState.value.pendingDeletionUnknown)
    }

    @Test
    fun `a read past 3 seconds is a failed read`() = runTest {
        coEvery { repository.pendingDeletion() } coAnswers { awaitCancellation() }

        val vm = viewModelFor(anon)
        testDispatcher.scheduler.advanceTimeBy(AuthViewModel.PENDING_DELETION_READ_TIMEOUT_MS - 1)
        assertFalse(vm.uiState.value.pendingDeletionUnknown)
        testDispatcher.scheduler.advanceTimeBy(2)
        testDispatcher.scheduler.runCurrent()

        assertTrue(vm.uiState.value.pendingDeletionUnknown)
        assertTrue(vm.uiState.value.isLoggedIn)
    }

    @Test
    fun `a failed read keeps a hold already shown, and is not unknown`() = runTest {
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)

        vm.refreshPendingDeletion()

        assertEquals(held, vm.uiState.value.pendingDeletion)
        assertFalse(vm.uiState.value.pendingDeletionUnknown)
    }

    // ── MR-2378: a 202 that lands after the cancel ─────────────

    @Test
    fun `a late 202 for a hold already cancelled is neither shown nor claimed`() = runTest {
        // The hold is on the list (Profile resumed while the request was out),
        // the user cancels it, and only then does the request's 202 arrive.
        // The list still names it for a moment: the server's cancel races the read.
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        val vm = viewModelFor(anon)
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(true)
        vm.cancelPendingDeletion()
        assertNull(vm.uiState.value.pendingDeletion)
        coEvery { repository.requestDeletionHold(null, null) } returns
            ApiResult.Success(DeletionHoldResult.Held(held, alreadyPending = false))

        vm.requestDeletionHold("")

        assertNull(vm.uiState.value.pendingDeletion)
        assertFalse(vm.uiState.value.pendingDeletionFiledHere)
        assertFalse(vm.uiState.value.isDeletingAccount)
        verify(exactly = 0) { tokenManager.setDeletionHoldFiledHere(any()) }
    }

    @Test
    fun `a late list read after a cancel cannot bring this session's hold back`() = runTest {
        val vm = viewModelWithOwnHold()
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.pendingDeletion() } coAnswers {
            gate.await()
            ApiResult.Success(held)
        }
        vm.refreshPendingDeletion(filedByThisRequest = true)
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(true)

        vm.cancelPendingDeletion()
        gate.complete(Unit)

        assertNull(vm.uiState.value.pendingDeletion)
        assertFalse(vm.uiState.value.pendingDeletionFiledHere)
    }

    // ── MR-2378: the common spec's remaining cases ─────────────

    /** This session filed p1, and asks again: the 409 names its own hold, which stays its own. */
    @Test
    fun `a 409 for this session's own hold keeps it this session's`() = runTest {
        val vm = viewModelWithOwnHold()
        coEvery { repository.requestDeletionHold(null, null) } returns
            ApiResult.Success(DeletionHoldResult.Held(held, alreadyPending = true))

        vm.requestDeletionHold("")

        assertTrue(vm.uiState.value.pendingDeletionFiledHere)
        assertEquals("p1", vm.uiState.value.deletionHoldFiledHereId)
        verify(exactly = 0) { tokenManager.clearDeletionHoldFiledHere() }
    }

    @Test
    fun `the hold step is not offered while a hold already waits`() {
        val holdOn = DeletionPreflightResponse(
            success = true,
            stepUp = DeletionStepUp(listOf("account_number"), enforced = true, holdAvailable = true),
        )
        assertTrue(offersDeletionHold(holdOn, holdWaiting = false))
        assertFalse(offersDeletionHold(holdOn, holdWaiting = true))
    }

    /**
     * MUST: a cold start while offline, with this session's hold id kept.
     * The list cannot be read, but the kept id is enough: the sign-out
     * cancels it first, and does not sign out while that fails.
     */
    @Test
    fun `a cold start offline cancels this session's hold by its kept id before signing out`() = runTest {
        every { tokenManager.getDeletionHoldFiledHere() } returns "p1"
        coEvery { repository.pendingDeletion() } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)
        val vm = viewModelFor(anon)
        assertNull(vm.uiState.value.pendingDeletion)
        assertTrue("the dialog shows the read-failed text", vm.uiState.value.pendingDeletionUnknown)
        assertEquals("p1", vm.uiState.value.deletionHoldFiledHereId)
        coEvery { repository.cancelPendingChange("p1") } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)
        var signedOut = 0

        vm.signOut { signedOut++ }

        coVerify(exactly = 1) { repository.cancelPendingChange("p1") }
        assertEquals("a failed cancel blocks the sign-out", 0, signedOut)
        assertEquals(StringsXml.text("hold_cancel_unreachable"), vm.uiState.value.signOutHoldCancelError)
        assertTrue(vm.uiState.value.pendingDeletionUnknown)

        // Back online: Retry cancels it, the list answers that nothing waits, and it signs out.
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(true)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(null)
        vm.signOut { signedOut++ }
        assertEquals(1, signedOut)
        verify { tokenManager.clearDeletionHoldFiledHere() }
    }

    @Test
    fun `a cold start offline can still sign out anyway`() = runTest {
        every { tokenManager.getDeletionHoldFiledHere() } returns "p1"
        coEvery { repository.pendingDeletion() } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)
        val vm = viewModelFor(anon)
        coEvery { repository.cancelPendingChange("p1") } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)
        var signedOut = 0
        vm.signOut { signedOut++ }

        vm.signOutAnyway { signedOut++ }

        assertEquals(1, signedOut)
    }

    @Test
    fun `the confirmation waits for the hold to be read`() = runTest {
        val vm = viewModelFor(anon)
        val gate = CompletableDeferred<ApiResult<PendingDeletion?>>()
        coEvery { repository.pendingDeletion() } coAnswers { gate.await() }

        vm.checkBeforeSignOut()
        assertTrue(vm.uiState.value.isCheckingBeforeSignOut)

        gate.complete(ApiResult.Success(held))

        assertFalse(vm.uiState.value.isCheckingBeforeSignOut)
        assertEquals(held, vm.uiState.value.pendingDeletion)
        assertFalse(vm.uiState.value.pendingDeletionFiledHere)
    }

    @Test
    fun `the confirmation waits at most 3 seconds, then says the hold could not be checked`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.pendingDeletion() } coAnswers { awaitCancellation() }

        vm.checkBeforeSignOut()
        testDispatcher.scheduler.advanceTimeBy(AuthViewModel.PENDING_DELETION_READ_TIMEOUT_MS + 1)
        testDispatcher.scheduler.runCurrent()

        assertFalse(vm.uiState.value.isCheckingBeforeSignOut)
        assertTrue(vm.uiState.value.pendingDeletionUnknown)
    }

    // ── MR-2378: matching iOS #624's flow ───────────────────────

    /**
     * iOS SignOutHold.prepareSignOut: after the cancel-first, the list is read
     * again, and the sign-out goes on only if it answered that nothing waits.
     */
    @Test
    fun `a cancel-first whose next read fails stops, and the next tap signs out`() = runTest {
        val vm = viewModelWithOwnHold()
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(true)
        coEvery { repository.pendingDeletion() } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)
        var signedOut = 0

        vm.signOut { signedOut++ }

        assertEquals("nobody is signed out on an unanswered read", 0, signedOut)
        assertEquals(1, vm.uiState.value.signOutReconsidered)
        assertTrue(vm.uiState.value.pendingDeletionUnknown)
        assertNull(vm.uiState.value.deletionHoldFiledHereId)

        // Its own hold is gone: this time nothing is cancelled first.
        vm.signOut { signedOut++ }
        assertEquals(1, signedOut)
        coVerify(exactly = 1) { repository.cancelPendingChange("p1") }
    }

    @Test
    fun `a cancel-first after which another hold is listed stops`() = runTest {
        val vm = viewModelWithOwnHold()
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(true)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(PendingDeletion("p9", tomorrow))
        var signedOut = 0

        vm.signOut { signedOut++ }

        assertEquals(0, signedOut)
        assertEquals("p9", vm.uiState.value.pendingDeletion?.id)
        assertFalse(vm.uiState.value.pendingDeletionFiledHere)
        assertEquals(1, vm.uiState.value.signOutReconsidered)
    }

    @Test
    fun `the check reads nothing when a hold is already known`() = runTest {
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        val vm = viewModelFor(anon)

        vm.checkBeforeSignOut()

        assertFalse(vm.uiState.value.isCheckingBeforeSignOut)
        coVerify(exactly = 1) { repository.pendingDeletion() }
    }

    @Test
    fun `the confirmation's Cancel deletion says what it did`() = runTest {
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        val vm = viewModelFor(anon)
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(true)

        vm.cancelPendingDeletion()
        assertEquals(HoldCancelNote.CANCELLED, vm.uiState.value.holdCancelNote)

        vm.dismissSignOutHoldError()
        assertNull(vm.uiState.value.holdCancelNote)

        // p1 was cancelled above, so a list that still names it is late.
        vm.refreshPendingDeletion()
        assertNull(vm.uiState.value.pendingDeletion)
    }

    @Test
    fun `an already gone hold says so`() = runTest {
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        val vm = viewModelFor(anon)
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(false)
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(null)

        vm.cancelPendingDeletion()

        assertEquals(HoldCancelNote.ALREADY_GONE, vm.uiState.value.holdCancelNote)
    }

    @Test
    fun `a refused cancel gives iOS's sentence`() = runTest {
        coEvery { repository.pendingDeletion() } returns ApiResult.Success(held)
        val vm = viewModelFor(anon)
        coEvery { repository.cancelPendingChange("p1") } returns
            ApiResult.Error("Server says no.", 500, FailureReason.SERVER_UNAVAILABLE)

        vm.cancelPendingDeletion()

        assertEquals(StringsXml.text("hold_cancel_failed"), vm.uiState.value.pendingDeletionError)
    }

    /** Offline cold start, no list: the confirmation's Cancel deletion cancels the kept id. */
    @Test
    fun `Cancel deletion with no list cancels this session's kept id`() = runTest {
        every { tokenManager.getDeletionHoldFiledHere() } returns "p1"
        coEvery { repository.pendingDeletion() } returns
            ApiResult.Error(StringsXml.text("error_unreachable"), 0, FailureReason.UNREACHABLE)
        val vm = viewModelFor(anon)
        coEvery { repository.cancelPendingChange("p1") } returns ApiResult.Success(true)

        vm.cancelPendingDeletion()

        coVerify(exactly = 1) { repository.cancelPendingChange("p1") }
        assertNull(vm.uiState.value.deletionHoldFiledHereId)
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
