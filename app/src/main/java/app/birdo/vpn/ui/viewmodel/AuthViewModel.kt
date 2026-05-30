package app.birdo.vpn.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.UserProfile
import app.birdo.vpn.shared.model.LoginResult
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.utils.InputValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val error: String? = null,
    val user: UserProfile? = null,
    /** FIX C-2: 2FA challenge state */
    val requiresTwoFactor: Boolean = false,
    val challengeToken: String? = null,
    /** Account deletion state */
    val isDeletingAccount: Boolean = false,
    val deleteAccountError: String? = null,
    val accountDeleted: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: BirdoRepository,
    private val tokenManager: TokenManager,
) : ViewModel() {

    companion object {
        /** Max login attempts before rate limiting kicks in. */
        private const val MAX_LOGIN_ATTEMPTS = 5
        /** Sliding window in seconds for rate limiting. */
        private const val LOGIN_WINDOW_SECS = 60L
    }

    /** Sliding window of recent failed login attempt timestamps. */
    private val loginAttempts = mutableListOf<Instant>()

    // FAST COLD START: Decide isLoggedIn synchronously from the locally
    // stored token so the NavHost can route straight to Home. The profile
    // fetch then runs in the background and only updates `user` (or, on a
    // 401, kicks the user back to Login).
    private val initiallyLoggedIn: Boolean = tokenManager.isLoggedIn()
    private val _uiState = MutableStateFlow(
        AuthUiState(
            isLoading = false,
            isLoggedIn = initiallyLoggedIn,
        )
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        checkSession()
    }

    private fun checkSession() {
        // Don't even hit the network if we have no token — UI is already on Login.
        if (!initiallyLoggedIn) return
        viewModelScope.launch {
            // Background refresh — UI is already showing Home.
            when (val result = repository.getProfile()) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoggedIn = true,
                        user = result.data,
                    )
                }
                is ApiResult.Error -> {
                    // 401 (or 401 after refresh failed) → token is dead, force re-login.
                    // Any other failure (network, 5xx, timeout) is transient — stay logged in.
                    if (result.code == 401) {
                        _uiState.value = _uiState.value.copy(
                            isLoggedIn = false,
                            user = null,
                        )
                    }
                }
            }
        }
    }

    fun login(email: String, password: String) {
        val trimmedEmail = email.trim()
        if (!InputValidator.isValidEmail(trimmedEmail)) {
            _uiState.value = _uiState.value.copy(error = "Please enter a valid email address")
            return
        }
        if (!InputValidator.isValidPassword(password)) {
            _uiState.value = _uiState.value.copy(error = "Password must be 6-256 characters")
            return
        }

        // Rate limit: max 5 failed attempts per 60-second window
        val now = Instant.now()
        val cutoff = now.minusSeconds(LOGIN_WINDOW_SECS)
        loginAttempts.removeAll { it.isBefore(cutoff) }
        if (loginAttempts.size >= MAX_LOGIN_ATTEMPTS) {
            val oldestInWindow = loginAttempts.first()
            val waitSecs = LOGIN_WINDOW_SECS - java.time.Duration.between(oldestInWindow, now).seconds
            _uiState.value = _uiState.value.copy(error = "Too many login attempts. Please wait ${waitSecs}s.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.login(trimmedEmail, password)) {
                is ApiResult.Success -> {
                    when (val loginResult = result.data) {
                        // FIX C-2: Handle 2FA challenge from backend
                        is LoginResult.TwoFactorRequired -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                requiresTwoFactor = true,
                                challengeToken = loginResult.challengeToken,
                            )
                        }
                        is LoginResult.Success -> {
                            fetchProfileAfterLogin()
                        }
                    }
                }
                is ApiResult.Error -> {
                    loginAttempts.add(Instant.now())
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = parseLoginError(result.message),
                    )
                }
            }
        }
    }

    /** FIX C-2: Verify 2FA token after challenge */
    fun verifyTwoFactor(code: String) {
        val token = _uiState.value.challengeToken ?: return
        if (code.length !in 6..8) {
            _uiState.value = _uiState.value.copy(error = "Enter a 6-digit code or 8-character backup code")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.verifyTwoFactor(token, code)) {
                is ApiResult.Success -> {
                    fetchProfileAfterLogin()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Invalid verification code. Please try again.",
                    )
                }
            }
        }
    }

    private suspend fun fetchProfileAfterLogin() {
        when (val profile = repository.getProfile()) {
            is ApiResult.Success -> {
                _uiState.value = AuthUiState(
                    isLoggedIn = true,
                    user = profile.data,
                )
            }
            is ApiResult.Error -> {
                _uiState.value = AuthUiState(
                    isLoggedIn = true,
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _uiState.value = AuthUiState(isLoggedIn = false)
        }
    }

    /**
     * Log in to an EXISTING anonymous account using its 24-digit ID and an
     * optional password. Anonymous accounts are created on the website only —
     * this client never creates new accounts.
     */
    fun loginAnonymous(anonymousId: String, password: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val cleanId = anonymousId.filter { it.isDigit() }
            if (cleanId.length != 24) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Anonymous ID must be 24 digits",
                )
                return@launch
            }
            when (val result = repository.loginAnonymous(cleanId, password?.takeIf { it.isNotBlank() })) {
                is ApiResult.Success -> {
                    val data = result.data
                    if (data.requiresTwoFactor && data.challengeToken != null) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            requiresTwoFactor = true,
                            challengeToken = data.challengeToken,
                            error = null,
                        )
                    } else if (data.ok) {
                        fetchProfileAfterLogin()
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Anonymous login failed",
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = parseLoginError(result.message),
                    )
                }
            }
        }
    }

    /**
     * GDPR Art. 17: Delete the user's account permanently.
     * Requires password re-confirmation for security.
     */
    fun deleteAccount(password: String) {
        if (!InputValidator.isValidPassword(password)) {
            _uiState.value = _uiState.value.copy(deleteAccountError = "Please enter your password")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeletingAccount = true, deleteAccountError = null)
            when (val result = repository.deleteAccount(password)) {
                is ApiResult.Success -> {
                    _uiState.value = AuthUiState(
                        isLoggedIn = false,
                        accountDeleted = true,
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isDeletingAccount = false,
                        deleteAccountError = parseDeleteError(result.message, result.code),
                    )
                }
            }
        }
    }

    fun clearDeleteAccountError() {
        _uiState.value = _uiState.value.copy(deleteAccountError = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Cancel 2FA challenge and return to the email/password form */
    fun cancelTwoFactor() {
        _uiState.value = _uiState.value.copy(
            requiresTwoFactor = false,
            challengeToken = null,
            error = null,
        )
    }

    private fun parseDeleteError(raw: String, code: Int): String {
        return when {
            code == 401 || "Incorrect password" in raw || "password" in raw.lowercase() -> "Incorrect password"
            code == 429 -> "Too many attempts. Please wait a moment."
            "Network" in raw || "timeout" in raw.lowercase() -> "Unable to reach server. Check your connection."
            else -> "Account deletion failed. Please try again."
        }
    }

    private fun parseLoginError(raw: String): String {
        val lower = raw.lowercase()
        return when {
            "Invalid credentials" in raw || "401" in raw -> "Invalid email or password"
            "429" in raw -> "Too many attempts. Please wait a moment."
            "Certificate" in raw || "SSL" in raw || "pinning" in lower ->
                "Secure connection failed. Please update the app."
            // DNS resolution failures: system DNS gives "Unable to resolve host",
            // DoH (DnsOverHttps) throws UnknownHostException whose message is
            // just the bare hostname (e.g. "api.birdo.app").
            "unable to resolve host" in lower ||
                "unknownhost" in lower ||
                Regex("^[a-z0-9.-]+\\.[a-z]{2,}$").matches(raw.trim()) ->
                "No internet connection."
            "Network" in raw || "timeout" in lower || "failed to connect" in lower ->
                "Unable to reach server. Check your connection."
            else -> "Login failed: $raw"
        }
    }
}
