package app.birdo.vpn.ui.viewmodel

import app.birdo.vpn.data.model.LoginResult
import app.birdo.vpn.data.model.TokenPair
import app.birdo.vpn.data.model.TwoFactorVerifyResponse
import app.birdo.vpn.data.model.UserProfile
import app.birdo.vpn.shared.model.LoginResult as SharedLoginResult
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.data.auth.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private lateinit var repository: BirdoRepository
    private lateinit var tokenManager: TokenManager
    private lateinit var viewModel: AuthViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    // ── Fixtures ─────────────────────────────────────────────────

    private val tokens = TokenPair("access_tok", "refresh_tok")
    private val loginSuccess = SharedLoginResult.Success(ok = true, tokens = tokens)
    private val twoFactorChallenge = SharedLoginResult.TwoFactorRequired(
        requiresTwoFactor = true,
        challengeToken = "challenge_abc123",
    )
    private val profile = UserProfile(id = "u1", email = "user@birdo.app", name = "Test User")
    private val twoFaVerifyResponse = TwoFactorVerifyResponse(ok = true, tokens = tokens)

    // ── Setup / Teardown ─────────────────────────────────────────

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        io.mockk.every { tokenManager.isLoggedIn() } returns true
        // Default: no existing session
        coEvery { repository.getProfile() } returns ApiResult.Error("Unauthorized", 401)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): AuthViewModel = AuthViewModel(repository, tokenManager)

    /**
     * Helper: create a ViewModel that is already past the init checkSession,
     * with the default "not logged in" state, ready for login tests.
     */
    private fun createLoggedOutViewModel(): AuthViewModel {
        coEvery { repository.getProfile() } returns ApiResult.Error("Unauthorized", 401)
        return createViewModel()
    }

    /**
     * Helper: create a ViewModel and drive it through a successful login + profile fetch,
     * so it's in the "logged in" state, ready for post-login tests.
     */
    private fun createLoggedInViewModel(): AuthViewModel {
        val vm = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)
        vm.login("user@birdo.app", "password")
        assertTrue(vm.uiState.value.isLoggedIn)
        return vm
    }

    // ═════════════════════════════════════════════════════════════
    //  1. INITIAL STATE — checkSession on init
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `init starts with isLoading true to prevent login screen flash`() = runTest {
        // With UnconfinedTestDispatcher the launch runs eagerly, so the final
        // state is already resolved. Verify it ends in a stable non-loading state.
        viewModel = createViewModel()
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `init with valid session sets logged in with user profile`() = runTest {
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertTrue(state.isLoggedIn)
        assertFalse(state.isLoading)
        assertEquals("user@birdo.app", state.user?.email)
        assertEquals("Test User", state.user?.name)
        assertNull(state.error)
        assertFalse(state.requiresTwoFactor)
    }

    @Test
    fun `init without valid session sets logged out`() = runTest {
        coEvery { repository.getProfile() } returns ApiResult.Error("Unauthorized", 401)

        viewModel = createViewModel()

        val state = viewModel.uiState.value
        assertFalse(state.isLoggedIn)
        assertFalse(state.isLoading)
        assertNull(state.user)
        assertNull(state.error)
    }

    @Test
    fun `init with network error keeps token-based session and does not crash`() = runTest {
        // Non-401 errors (e.g. network) should not log the user out if a token is stored;
        // we only force re-login on a true 401 from the server.
        io.mockk.every { tokenManager.isLoggedIn() } returns true
        coEvery { repository.getProfile() } returns ApiResult.Error("Network error")

        viewModel = createViewModel()

        assertTrue(viewModel.uiState.value.isLoggedIn)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `init with network error and no token stays logged out`() = runTest {
        io.mockk.every { tokenManager.isLoggedIn() } returns false
        coEvery { repository.getProfile() } returns ApiResult.Error("Network error")

        viewModel = createViewModel()

        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    // ═════════════════════════════════════════════════════════════
    //  2. INPUT VALIDATION — login() client-side checks
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `login with blank email shows validation error`() = runTest {
        viewModel = createLoggedOutViewModel()

        viewModel.login("", "password")

        val state = viewModel.uiState.value
        assertEquals("Please enter a valid email address", state.error)
        assertFalse(state.isLoading)
        assertFalse(state.isLoggedIn)
    }

    @Test
    fun `login with whitespace-only email shows validation error`() = runTest {
        viewModel = createLoggedOutViewModel()

        viewModel.login("   ", "password")

        assertEquals("Please enter a valid email address", viewModel.uiState.value.error)
    }

    @Test
    fun `login with missing domain shows validation error`() = runTest {
        viewModel = createLoggedOutViewModel()

        viewModel.login("user@", "password")

        assertEquals("Please enter a valid email address", viewModel.uiState.value.error)
    }

    @Test
    fun `login with missing at sign shows validation error`() = runTest {
        viewModel = createLoggedOutViewModel()

        viewModel.login("notanemail", "password")

        assertEquals("Please enter a valid email address", viewModel.uiState.value.error)
    }

    @Test
    fun `login with missing TLD shows validation error`() = runTest {
        viewModel = createLoggedOutViewModel()

        viewModel.login("user@domain", "password")

        assertEquals("Please enter a valid email address", viewModel.uiState.value.error)
    }

    @Test
    fun `login with too short password shows validation error`() = runTest {
        viewModel = createLoggedOutViewModel()

        viewModel.login("user@birdo.app", "12345")

        assertEquals("Password must be 6\u2013256 characters", viewModel.uiState.value.error)
    }

    @Test
    fun `login with 1 char password shows validation error`() = runTest {
        viewModel = createLoggedOutViewModel()

        viewModel.login("user@birdo.app", "a")

        assertEquals("Password must be 6\u2013256 characters", viewModel.uiState.value.error)
    }

    @Test
    fun `login with empty password shows validation error`() = runTest {
        viewModel = createLoggedOutViewModel()

        viewModel.login("user@birdo.app", "")

        assertEquals("Password must be 6\u2013256 characters", viewModel.uiState.value.error)
    }

    @Test
    fun `login with 257 char password shows validation error`() = runTest {
        viewModel = createLoggedOutViewModel()

        viewModel.login("user@birdo.app", "a".repeat(257))

        assertEquals("Password must be 6\u2013256 characters", viewModel.uiState.value.error)
    }

    @Test
    fun `login with exactly 6 char password passes validation`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.login("user@birdo.app", "123456")

        coVerify { repository.login("user@birdo.app", "123456") }
    }

    @Test
    fun `login with exactly 256 char password passes validation`() = runTest {
        viewModel = createLoggedOutViewModel()
        val longPassword = "a".repeat(256)
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.login("user@birdo.app", longPassword)

        coVerify { repository.login("user@birdo.app", longPassword) }
    }

    @Test
    fun `login trims email whitespace before sending`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.login("  user@birdo.app  ", "password")

        coVerify { repository.login("user@birdo.app", "password") }
    }

    @Test
    fun `login validation error does not call repository`() = runTest {
        viewModel = createLoggedOutViewModel()

        viewModel.login("bad", "password")

        coVerify(exactly = 0) { repository.login(any(), any()) }
    }

    @Test
    fun `login validation does not trim password`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.login("user@birdo.app", "  pass  ")

        // Password should be sent as-is (not trimmed) — backend decides
        coVerify { repository.login("user@birdo.app", "  pass  ") }
    }

    // ═════════════════════════════════════════════════════════════
    //  3. SUCCESSFUL LOGIN — standard (no 2FA)
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `successful login sets isLoggedIn and populates user`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.login("user@birdo.app", "password")

        val state = viewModel.uiState.value
        assertTrue(state.isLoggedIn)
        assertFalse(state.isLoading)
        assertEquals("user@birdo.app", state.user?.email)
        assertNull(state.error)
        assertFalse(state.requiresTwoFactor)
        assertNull(state.challengeToken)
    }

    @Test
    fun `successful login fetches profile after token storage`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.login("user@birdo.app", "password")

        // getProfile called twice: once during init (checkSession), once after login
        coVerify(atLeast = 2) { repository.getProfile() }
    }

    @Test
    fun `successful login with profile fetch failure still marks logged in`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        // First call (init) fails, second call (post-login) also fails
        coEvery { repository.getProfile() } returns ApiResult.Error("Profile error")

        viewModel.login("user@birdo.app", "password")

        val state = viewModel.uiState.value
        assertTrue(state.isLoggedIn)
        assertNull(state.user) // No profile data, but still logged in
        assertFalse(state.isLoading)
    }

    @Test
    fun `loading state is cleared after login completes`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.login("user@birdo.app", "password")

        assertFalse(viewModel.uiState.value.isLoading)
    }

    // ═════════════════════════════════════════════════════════════
    //  4. FAILED LOGIN — error mapping
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `login 401 invalid credentials shows user-friendly error`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Error("Invalid credentials", 401)

        viewModel.login("user@birdo.app", "wrongpass")

        val state = viewModel.uiState.value
        assertFalse(state.isLoggedIn)
        assertFalse(state.isLoading)
        assertEquals("Invalid email or password", state.error)
    }

    @Test
    fun `login error containing 401 code string shows credentials error`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Error("Error 401 unauthorized")

        viewModel.login("user@birdo.app", "wrongpass")

        assertEquals("Invalid email or password", viewModel.uiState.value.error)
    }

    @Test
    fun `login network timeout shows connection error`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Error("Network timeout")

        viewModel.login("user@birdo.app", "password")

        assertEquals("Unable to reach server. Check your connection.", viewModel.uiState.value.error)
    }

    @Test
    fun `login network error shows connection error`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Error("Network error")

        viewModel.login("user@birdo.app", "password")

        assertEquals("Unable to reach server. Check your connection.", viewModel.uiState.value.error)
    }

    @Test
    fun `login TIMEOUT case insensitive shows connection error`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Error("Connection TIMEOUT reached")

        viewModel.login("user@birdo.app", "password")

        assertEquals("Unable to reach server. Check your connection.", viewModel.uiState.value.error)
    }

    @Test
    fun `login 429 rate limit shows rate limit error`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Error("429 Too Many Requests")

        viewModel.login("user@birdo.app", "password")

        assertEquals("Too many attempts. Please wait a moment.", viewModel.uiState.value.error)
    }

    @Test
    fun `login unknown error shows generic message`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Error("Something unexpected")

        viewModel.login("user@birdo.app", "password")

        assertEquals("Login failed: Something unexpected", viewModel.uiState.value.error)
    }

    @Test
    fun `login error clears loading state`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Error("fail")

        viewModel.login("user@birdo.app", "password")

        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `login error does not set isLoggedIn`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Error("fail")

        viewModel.login("user@birdo.app", "password")

        assertFalse(viewModel.uiState.value.isLoggedIn)
        assertNull(viewModel.uiState.value.user)
    }

    // ═════════════════════════════════════════════════════════════
    //  5. TWO-FACTOR AUTH — challenge + verify flow
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `login with 2FA required sets challenge state`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)

        viewModel.login("user@birdo.app", "password")

        val state = viewModel.uiState.value
        assertTrue(state.requiresTwoFactor)
        assertEquals("challenge_abc123", state.challengeToken)
        assertFalse(state.isLoading)
        assertFalse(state.isLoggedIn)
        assertNull(state.error)
    }

    @Test
    fun `2FA challenge does not fetch profile`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)

        viewModel.login("user@birdo.app", "password")

        // getProfile called once (init checkSession), NOT after 2FA challenge
        coVerify(exactly = 1) { repository.getProfile() }
    }

    @Test
    fun `verifyTwoFactor with valid 6-digit code succeeds`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")

        coEvery { repository.verifyTwoFactor("challenge_abc123", "123456") } returns
            ApiResult.Success(twoFaVerifyResponse)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.verifyTwoFactor("123456")

        val state = viewModel.uiState.value
        assertTrue(state.isLoggedIn)
        assertFalse(state.requiresTwoFactor)
        assertNull(state.challengeToken)
        assertEquals("user@birdo.app", state.user?.email)
        assertFalse(state.isLoading)
    }

    @Test
    fun `verifyTwoFactor with valid 8-char backup code succeeds`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")

        coEvery { repository.verifyTwoFactor("challenge_abc123", "abcd1234") } returns
            ApiResult.Success(twoFaVerifyResponse)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.verifyTwoFactor("abcd1234")

        assertTrue(viewModel.uiState.value.isLoggedIn)
    }

    @Test
    fun `verifyTwoFactor with 7-char code succeeds (within 6-8 range)`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")

        coEvery { repository.verifyTwoFactor(any(), any()) } returns
            ApiResult.Success(twoFaVerifyResponse)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.verifyTwoFactor("1234567")

        assertTrue(viewModel.uiState.value.isLoggedIn)
    }

    @Test
    fun `verifyTwoFactor with too short code shows validation error`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")

        viewModel.verifyTwoFactor("12345") // 5 chars — too short

        val state = viewModel.uiState.value
        assertEquals("Enter a 6-digit code or 8-character backup code", state.error)
        assertFalse(state.isLoggedIn)
        assertTrue(state.requiresTwoFactor) // Still in 2FA state
        coVerify(exactly = 0) { repository.verifyTwoFactor(any(), any()) }
    }

    @Test
    fun `verifyTwoFactor with too long code shows validation error`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")

        viewModel.verifyTwoFactor("123456789") // 9 chars — too long

        assertEquals("Enter a 6-digit code or 8-character backup code", viewModel.uiState.value.error)
        coVerify(exactly = 0) { repository.verifyTwoFactor(any(), any()) }
    }

    @Test
    fun `verifyTwoFactor with empty code shows validation error`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")

        viewModel.verifyTwoFactor("")

        assertEquals("Enter a 6-digit code or 8-character backup code", viewModel.uiState.value.error)
    }

    @Test
    fun `verifyTwoFactor without challenge token is silent no-op`() = runTest {
        viewModel = createLoggedOutViewModel()
        // No login attempted, so no challengeToken is set

        viewModel.verifyTwoFactor("123456")

        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoggedIn)
        coVerify(exactly = 0) { repository.verifyTwoFactor(any(), any()) }
    }

    @Test
    fun `verifyTwoFactor API error shows verification error`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")

        coEvery { repository.verifyTwoFactor(any(), any()) } returns
            ApiResult.Error("Invalid code", 401)

        viewModel.verifyTwoFactor("999999")

        val state = viewModel.uiState.value
        assertEquals("Invalid verification code. Please try again.", state.error)
        assertFalse(state.isLoggedIn)
        assertFalse(state.isLoading)
        assertTrue(state.requiresTwoFactor) // Stays in 2FA mode for retry
    }

    @Test
    fun `verifyTwoFactor passes challenge token from login to repository`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")

        coEvery { repository.verifyTwoFactor(any(), any()) } returns
            ApiResult.Success(twoFaVerifyResponse)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.verifyTwoFactor("123456")

        coVerify { repository.verifyTwoFactor("challenge_abc123", "123456") }
    }

    @Test
    fun `verifyTwoFactor success with profile fetch failure still logs in`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")

        coEvery { repository.verifyTwoFactor(any(), any()) } returns
            ApiResult.Success(twoFaVerifyResponse)
        coEvery { repository.getProfile() } returns ApiResult.Error("Profile error")

        viewModel.verifyTwoFactor("123456")

        val state = viewModel.uiState.value
        assertTrue(state.isLoggedIn)
        assertNull(state.user)
    }

    // ═════════════════════════════════════════════════════════════
    //  6. CANCEL TWO-FACTOR — returning to login form
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `cancelTwoFactor clears challenge state`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")
        assertTrue(viewModel.uiState.value.requiresTwoFactor)

        viewModel.cancelTwoFactor()

        val state = viewModel.uiState.value
        assertFalse(state.requiresTwoFactor)
        assertNull(state.challengeToken)
        assertNull(state.error)
        assertFalse(state.isLoggedIn)
    }

    @Test
    fun `cancelTwoFactor also clears any existing error`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")

        // Trigger an error from failed 2FA verify
        coEvery { repository.verifyTwoFactor(any(), any()) } returns ApiResult.Error("bad code")
        viewModel.verifyTwoFactor("123456")
        assertNotNull(viewModel.uiState.value.error)

        viewModel.cancelTwoFactor()

        assertNull(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.requiresTwoFactor)
    }

    @Test
    fun `cancelTwoFactor when not in 2FA mode is safe no-op`() = runTest {
        viewModel = createLoggedOutViewModel()

        viewModel.cancelTwoFactor()

        assertFalse(viewModel.uiState.value.requiresTwoFactor)
        assertNull(viewModel.uiState.value.error)
    }

    // ═════════════════════════════════════════════════════════════
    //  7. CLEAR ERROR
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `clearError removes validation error`() = runTest {
        viewModel = createLoggedOutViewModel()
        viewModel.login("bad-email", "password")
        assertNotNull(viewModel.uiState.value.error)

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `clearError removes API error`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Error("fail")
        viewModel.login("user@birdo.app", "password")
        assertNotNull(viewModel.uiState.value.error)

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `clearError when no error is safe no-op`() = runTest {
        viewModel = createLoggedOutViewModel()
        assertNull(viewModel.uiState.value.error)

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `clearError preserves other state fields`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")
        // Trigger an error while in 2FA state
        viewModel.verifyTwoFactor("123") // too short → validation error
        assertNotNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.requiresTwoFactor)

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.requiresTwoFactor) // Preserved
        assertEquals("challenge_abc123", viewModel.uiState.value.challengeToken) // Preserved
    }

    // ═════════════════════════════════════════════════════════════
    //  8. LOGOUT
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `logout clears entire auth state`() = runTest {
        viewModel = createLoggedInViewModel()

        viewModel.logout()

        val state = viewModel.uiState.value
        assertFalse(state.isLoggedIn)
        assertFalse(state.isLoading)
        assertNull(state.user)
        assertNull(state.error)
        assertFalse(state.requiresTwoFactor)
        assertNull(state.challengeToken)
    }

    @Test
    fun `logout calls repository logout`() = runTest {
        viewModel = createLoggedInViewModel()

        viewModel.logout()

        coVerify(exactly = 1) { repository.logout() }
    }

    @Test
    fun `logout from 2FA state clears everything`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")
        assertTrue(viewModel.uiState.value.requiresTwoFactor)

        viewModel.logout()

        val state = viewModel.uiState.value
        assertFalse(state.isLoggedIn)
        assertFalse(state.requiresTwoFactor)
        assertNull(state.challengeToken)
    }

    @Test
    fun `logout when already logged out is safe`() = runTest {
        viewModel = createLoggedOutViewModel()
        assertFalse(viewModel.uiState.value.isLoggedIn)

        viewModel.logout()

        assertFalse(viewModel.uiState.value.isLoggedIn)
        coVerify { repository.logout() }
    }

    // ═════════════════════════════════════════════════════════════
    //  9. SEQUENTIAL LOGIN FLOWS — state transitions
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `failed login then successful login works correctly`() = runTest {
        viewModel = createLoggedOutViewModel()

        // First attempt: fail
        coEvery { repository.login(any(), any()) } returns ApiResult.Error("Invalid credentials", 401)
        viewModel.login("user@birdo.app", "wrong")
        assertEquals("Invalid email or password", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoggedIn)

        // Second attempt: succeed
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)
        viewModel.login("user@birdo.app", "correct")

        val state = viewModel.uiState.value
        assertTrue(state.isLoggedIn)
        assertNull(state.error)
    }

    @Test
    fun `validation error then successful login works correctly`() = runTest {
        viewModel = createLoggedOutViewModel()

        // First: validation error
        viewModel.login("bad", "password")
        assertNotNull(viewModel.uiState.value.error)

        // Second: valid login
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)
        viewModel.login("user@birdo.app", "password")

        assertTrue(viewModel.uiState.value.isLoggedIn)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `login then logout then login again works`() = runTest {
        viewModel = createLoggedOutViewModel()

        // Login
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)
        viewModel.login("user@birdo.app", "password")
        assertTrue(viewModel.uiState.value.isLoggedIn)

        // Logout
        viewModel.logout()
        assertFalse(viewModel.uiState.value.isLoggedIn)

        // Login again
        viewModel.login("user@birdo.app", "password")
        assertTrue(viewModel.uiState.value.isLoggedIn)
    }

    @Test
    fun `2FA fail then retry with correct code succeeds`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")

        // First 2FA attempt: wrong code
        coEvery { repository.verifyTwoFactor(any(), eq("000000")) } returns
            ApiResult.Error("Invalid code")
        viewModel.verifyTwoFactor("000000")
        assertEquals("Invalid verification code. Please try again.", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isLoggedIn)

        // Second 2FA attempt: correct code
        coEvery { repository.verifyTwoFactor(any(), eq("123456")) } returns
            ApiResult.Success(twoFaVerifyResponse)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)
        viewModel.verifyTwoFactor("123456")

        assertTrue(viewModel.uiState.value.isLoggedIn)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `2FA cancel then re-login triggers fresh challenge`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(twoFactorChallenge)
        viewModel.login("user@birdo.app", "password")
        assertTrue(viewModel.uiState.value.requiresTwoFactor)

        // Cancel
        viewModel.cancelTwoFactor()
        assertFalse(viewModel.uiState.value.requiresTwoFactor)

        // Re-login triggers new 2FA
        val newChallenge = SharedLoginResult.TwoFactorRequired(true, "challenge_new")
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(newChallenge)
        viewModel.login("user@birdo.app", "password")

        assertTrue(viewModel.uiState.value.requiresTwoFactor)
        assertEquals("challenge_new", viewModel.uiState.value.challengeToken)
    }

    // ═════════════════════════════════════════════════════════════
    //  10. EDGE CASES
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `login with email containing plus addressing passes validation`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.login("user+tag@birdo.app", "password")

        coVerify { repository.login("user+tag@birdo.app", "password") }
    }

    @Test
    fun `login with subdomain email passes validation`() = runTest {
        viewModel = createLoggedOutViewModel()
        coEvery { repository.login(any(), any()) } returns ApiResult.Success(loginSuccess)
        coEvery { repository.getProfile() } returns ApiResult.Success(profile)

        viewModel.login("user@mail.example.co.uk", "password")

        coVerify { repository.login("user@mail.example.co.uk", "password") }
    }

    @Test
    fun `multiple rapid clearError calls are safe`() = runTest {
        viewModel = createLoggedOutViewModel()
        viewModel.login("bad", "pass")

        viewModel.clearError()
        viewModel.clearError()
        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `AuthUiState default values are correct`() {
        val default = AuthUiState()

        assertFalse(default.isLoading)
        assertFalse(default.isLoggedIn)
        assertNull(default.error)
        assertNull(default.user)
        assertFalse(default.requiresTwoFactor)
        assertNull(default.challengeToken)
    }
}
