package app.birdo.vpn.ui.viewmodel

import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.DeleteAccountResponse
import app.birdo.vpn.data.model.DeletionCodeResponse
import app.birdo.vpn.data.model.DeletionPreflightResponse
import app.birdo.vpn.data.model.DeletionStepUp
import app.birdo.vpn.data.model.UserProfile
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.data.repository.FailureReason
import app.birdo.vpn.testing.StringsXml
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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

/**
 * MR-179 (owner 2026-11-13): a fresh re-auth before account deletion. The
 * server (birdo-web #766) asks an anonymous account for its 24-digit number
 * and an SSO account for an e-mailed code, on top of the password and 2FA code
 * the app already handled (MR-922 / MR-928, AuthViewModelTest). These cover the
 * new factors; the old paths are pinned again at the end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteStepUpViewModelTest {

    private lateinit var repository: BirdoRepository
    private lateinit var tokenManager: TokenManager
    private lateinit var prefs: AppPreferences
    private val testDispatcher = UnconfinedTestDispatcher()

    private val number = "123456789012345678901234"
    private val anon = UserProfile(id = "u1", email = "anon_ab@anonymous.local", name = "Anonymous", hasPassword = false)
    private val sso = UserProfile(id = "u2", email = "owner@example.com", name = "Owner", hasPassword = false)
    private val passwordUser = UserProfile(id = "u3", email = "user@birdo.app", name = "User")

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        every { prefs.hasAcceptedCurrentConsent } returns true
        every { tokenManager.isLoggedIn() } returns true
        every { tokenManager.getPendingAnonymousId() } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModelFor(profile: UserProfile): AuthViewModel {
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)
        return AuthViewModel(repository, tokenManager, mockk(relaxed = true), prefs, StringsXml)
    }

    private fun refusal(reason: FailureReason, key: String, status: Int = 403) =
        ApiResult.Error(StringsXml.text(key), status, reason)

    private val deleted = ApiResult.Success(DeleteAccountResponse(success = true))

    // ── Anonymous account: the number ───────────────────────────

    @Test
    fun `the preflight makes the dialog ask for the number before the first attempt`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.deletionPreflight() } returns ApiResult.Success(
            DeletionPreflightResponse(success = true, stepUp = DeletionStepUp(listOf("account_number"), enforced = false)),
        )

        vm.loadDeletionPreflight()

        assertTrue(vm.uiState.value.deleteRequiresAccountNumber)
        assertFalse(vm.uiState.value.deleteRequiresEmailCode)
    }

    @Test
    fun `a server older than the step-up sends no stepUp and the dialog asks for nothing new`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.deletionPreflight() } returns ApiResult.Success(DeletionPreflightResponse(success = true))

        vm.loadDeletionPreflight()

        assertFalse(vm.uiState.value.deleteRequiresAccountNumber)
        assertFalse(vm.uiState.value.deleteRequiresEmailCode)
    }

    @Test
    fun `a refusal for the number asks for it with no error line, then the retry sends the proof`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.deleteAccount(null, null, null, null) } returns
            refusal(FailureReason.ACCOUNT_NUMBER_REQUIRED, "delete_dialog_account_number_required")

        vm.deleteAccount("")

        var state = vm.uiState.value
        assertTrue(state.deleteRequiresAccountNumber)
        assertNull("asking for the number is not an error", state.deleteAccountError)
        assertTrue(state.isLoggedIn)
        assertFalse(state.accountDeleted)

        coEvery { repository.deleteAccount(null, null, number, null) } returns deleted
        vm.deleteAccount("", null, "1234 5678 9012 3456 7890 1234")

        coVerify(exactly = 1) { repository.deleteAccount(null, null, number, null) }
        assertTrue(vm.uiState.value.accountDeleted)
        assertFalse(vm.uiState.value.isLoggedIn)
    }

    @Test
    fun `once asked, a short number is refused here and never sent`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.deleteAccount(null, null, null, null) } returns
            refusal(FailureReason.ACCOUNT_NUMBER_REQUIRED, "delete_dialog_account_number_required")
        vm.deleteAccount("")

        vm.deleteAccount("", null, "12345")

        val state = vm.uiState.value
        assertEquals(StringsXml.text("delete_dialog_account_number_format"), state.deleteAccountError)
        assertEquals(DeleteAccountField.ACCOUNT_NUMBER, state.deleteErrorField)
        coVerify(exactly = 0) { repository.deleteAccount(any(), any(), "12345", any()) }
    }

    @Test
    fun `a wrong number says so on its own field and allows a retry`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.deleteAccount(null, null, null, null) } returns
            refusal(FailureReason.ACCOUNT_NUMBER_REQUIRED, "delete_dialog_account_number_required")
        vm.deleteAccount("")
        coEvery { repository.deleteAccount(null, null, "999999999999999999999999", null) } returns
            refusal(FailureReason.ACCOUNT_NUMBER_INVALID, "delete_dialog_account_number_invalid")

        vm.deleteAccount("", null, "999999999999999999999999")

        val state = vm.uiState.value
        assertEquals(StringsXml.text("delete_dialog_account_number_invalid"), state.deleteAccountError)
        assertEquals(DeleteAccountField.ACCOUNT_NUMBER, state.deleteErrorField)
        assertTrue(state.deleteRequiresAccountNumber)
        assertFalse(state.accountDeleted)
    }

    // ── SSO account: the e-mailed code ──────────────────────────

    @Test
    fun `an SSO refusal asks for the e-mailed code, Send code shows where it went, and the code deletes`() = runTest {
        val vm = viewModelFor(sso)
        coEvery { repository.deleteAccount(null, null, null, null) } returns
            refusal(FailureReason.EMAIL_CODE_REQUIRED, "delete_dialog_email_code_required")

        vm.deleteAccount("")

        assertTrue(vm.uiState.value.deleteRequiresEmailCode)
        assertNull(vm.uiState.value.deleteAccountError)

        coEvery { repository.sendDeletionCode() } returns
            ApiResult.Success(DeletionCodeResponse(success = true, sentTo = "o***@example.com", expiresInMinutes = 10))
        vm.sendDeletionCode()

        assertTrue(vm.uiState.value.deleteEmailCodeSent)
        assertEquals("o***@example.com", vm.uiState.value.deleteEmailCodeSentTo)
        assertFalse(vm.uiState.value.isSendingDeleteCode)

        coEvery { repository.deleteAccount(null, null, null, "654321") } returns deleted
        vm.deleteAccount("", null, null, "654321")

        coVerify(exactly = 1) { repository.deleteAccount(null, null, null, "654321") }
        assertTrue(vm.uiState.value.accountDeleted)
    }

    @Test
    fun `a rate-limited or failed send shows the mapped copy on the code field and keeps the dialog`() = runTest {
        val vm = viewModelFor(sso)
        coEvery { repository.sendDeletionCode() } returns
            ApiResult.Error(StringsXml.text("error_rate_limited"), 429, FailureReason.RATE_LIMITED)

        vm.sendDeletionCode()

        val state = vm.uiState.value
        assertEquals(StringsXml.text("error_rate_limited"), state.deleteAccountError)
        assertEquals(DeleteAccountField.EMAIL_CODE, state.deleteErrorField)
        assertFalse(state.deleteEmailCodeSent)
        assertFalse(state.isSendingDeleteCode)
    }

    @Test
    fun `a wrong or expired e-mailed code says so and keeps the field`() = runTest {
        val vm = viewModelFor(sso)
        coEvery { repository.deleteAccount(null, null, null, null) } returns
            refusal(FailureReason.EMAIL_CODE_REQUIRED, "delete_dialog_email_code_required")
        vm.deleteAccount("")
        coEvery { repository.deleteAccount(null, null, null, "111111") } returns
            refusal(FailureReason.EMAIL_CODE_INVALID, "delete_dialog_email_code_invalid")

        vm.deleteAccount("", null, null, "111111")

        val state = vm.uiState.value
        assertEquals(StringsXml.text("delete_dialog_email_code_invalid"), state.deleteAccountError)
        assertEquals(DeleteAccountField.EMAIL_CODE, state.deleteErrorField)
        assertTrue(state.deleteRequiresEmailCode)
    }

    @Test
    fun `an incomplete e-mailed code is refused here and never sent`() = runTest {
        val vm = viewModelFor(sso)
        coEvery { repository.deleteAccount(null, null, null, null) } returns
            refusal(FailureReason.EMAIL_CODE_REQUIRED, "delete_dialog_email_code_required")
        vm.deleteAccount("")

        vm.deleteAccount("", null, null, "123")

        assertEquals(StringsXml.text("delete_dialog_email_code_format"), vm.uiState.value.deleteAccountError)
        coVerify(exactly = 0) { repository.deleteAccount(any(), any(), any(), "123") }
    }

    @Test
    fun `closing the dialog forgets every step-up prompt`() = runTest {
        val vm = viewModelFor(sso)
        coEvery { repository.deleteAccount(null, null, null, null) } returns
            refusal(FailureReason.EMAIL_CODE_REQUIRED, "delete_dialog_email_code_required")
        vm.deleteAccount("")
        coEvery { repository.sendDeletionCode() } returns ApiResult.Success(DeletionCodeResponse(success = true, sentTo = "o***@example.com"))
        vm.sendDeletionCode()

        vm.clearDeleteAccountError()

        val state = vm.uiState.value
        assertFalse(state.deleteRequiresEmailCode)
        assertFalse(state.deleteRequiresAccountNumber)
        assertFalse(state.deleteEmailCodeSent)
        assertNull(state.deleteEmailCodeSentTo)
    }

    // ── Not enforced yet: the factor is optional ────────────────

    @Test
    fun `before the server enforces it, an SSO user with no code still deletes`() = runTest {
        val vm = viewModelFor(sso)
        coEvery { repository.deletionPreflight() } returns ApiResult.Success(
            DeletionPreflightResponse(success = true, stepUp = DeletionStepUp(listOf("email_code"), enforced = false)),
        )
        vm.loadDeletionPreflight()
        assertTrue(vm.uiState.value.deleteRequiresEmailCode)
        assertFalse(vm.uiState.value.deleteStepUpEnforced)
        coEvery { repository.deleteAccount(null, null, null, null) } returns deleted

        vm.deleteAccount("")

        coVerify(exactly = 1) { repository.deleteAccount(null, null, null, null) }
        assertTrue(vm.uiState.value.accountDeleted)
    }

    @Test
    fun `when the preflight says enforced, an empty number is refused here`() = runTest {
        val vm = viewModelFor(anon)
        coEvery { repository.deletionPreflight() } returns ApiResult.Success(
            DeletionPreflightResponse(success = true, stepUp = DeletionStepUp(listOf("account_number"), enforced = true)),
        )
        vm.loadDeletionPreflight()

        vm.deleteAccount("")

        assertEquals(DeleteAccountField.ACCOUNT_NUMBER, vm.uiState.value.deleteErrorField)
        coVerify(exactly = 0) { repository.deleteAccount(any(), any(), any(), any()) }
    }

    // ── The old paths still work (MR-922 / MR-928) ──────────────

    @Test
    fun `a password account still deletes with the password alone, and a wrong password keeps its field`() = runTest {
        val vm = viewModelFor(passwordUser)
        coEvery { repository.deleteAccount("wrong-password", null, null, null) } returns
            ApiResult.Error("Incorrect password", 401, FailureReason.INVALID_CREDENTIALS)

        vm.deleteAccount("wrong-password")

        assertEquals(DeleteAccountField.PASSWORD, vm.uiState.value.deleteErrorField)
        assertFalse(vm.uiState.value.deleteRequiresAccountNumber)
        assertFalse(vm.uiState.value.deleteRequiresEmailCode)

        coEvery { repository.deleteAccount("password1", null, null, null) } returns deleted
        vm.deleteAccount("password1")
        assertTrue(vm.uiState.value.accountDeleted)
    }

    @Test
    fun `a password account with 2FA still asks for the code and sends it with the password`() = runTest {
        val vm = viewModelFor(passwordUser)
        coEvery { repository.deleteAccount("password1", null, null, null) } returns
            refusal(FailureReason.TWO_FACTOR_REQUIRED, "delete_dialog_2fa_required")
        vm.deleteAccount("password1")
        assertTrue(vm.uiState.value.deleteRequiresTwoFactor)
        assertNull(vm.uiState.value.deleteAccountError)

        coEvery { repository.deleteAccount("password1", "123456", null, null) } returns deleted
        vm.deleteAccount("password1", "123456")

        assertTrue(vm.uiState.value.accountDeleted)
    }

    @Test
    fun `an anonymous account with a password and 2FA sends all three proofs together`() = runTest {
        val vm = viewModelFor(anon.copy(hasPassword = true))
        coEvery { repository.deletionPreflight() } returns ApiResult.Success(
            DeletionPreflightResponse(
                success = true,
                stepUp = DeletionStepUp(listOf("account_number", "password", "two_factor"), enforced = true),
            ),
        )
        vm.loadDeletionPreflight()
        coEvery { repository.deleteAccount("password1", "123456", number, null) } returns deleted

        vm.deleteAccount("password1", "123456", number)

        coVerify(exactly = 1) { repository.deleteAccount("password1", "123456", number, null) }
        assertTrue(vm.uiState.value.accountDeleted)
    }
}
