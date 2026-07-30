package app.birdo.vpn.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.data.auth.OAuthStateStore
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.UserProfile
import app.birdo.vpn.shared.model.LoginResult
import app.birdo.vpn.utils.PkceGenerator
import app.birdo.vpn.utils.is2faCodeComplete
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.utils.InputValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    /**
     * A freshly-minted 24-digit anonymous ID, shown EXACTLY ONCE.
     *
     * While this is non-null the router must HOLD before Home: it is the
     * account's ONLY credential and the server never reveals it again. It used
     * to be dropped on the floor here (the response was checked for `ok` and
     * nothing else) while the user was sent straight to Home — so every
     * anonymous account created on Android was permanently unrecoverable the
     * moment the app forgot its tokens. iOS and desktop both show it once and
     * gate on acknowledgement; this is that gate.
     */
    val createdAnonymousId: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: BirdoRepository,
    private val tokenManager: TokenManager,
    private val oauthStore: OAuthStateStore,
) : ViewModel() {

    companion object {
        /** Max login attempts before rate limiting kicks in. */
        private const val MAX_LOGIN_ATTEMPTS = 5
        /** Sliding window in seconds for rate limiting. */
        private const val LOGIN_WINDOW_SECS = 60L
    }

    /** Sliding window of recent failed login attempt timestamps. */
    private val loginAttempts = mutableListOf<Instant>()

    /**
     * The in-flight auth request (email login / anon login / anon register).
     *
     * SESSION-DEDUP: the login buttons disable on `isLoading`, but Compose applies
     * that state asynchronously, so two fast taps can BOTH pass the `enabled`
     * check before the first recomposition and fire two logins seconds apart.
     * Each backend login mints a session row, so a double-submit surfaced as
     * duplicate "Active sessions" for one device. This single-flights auth: a new
     * submit while one is already running is ignored.
     */
    private var authJob: Job? = null

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

        // Single-flight: ignore a double-submit while a login is already running.
        if (authJob?.isActive == true) return
        authJob = viewModelScope.launch {
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

    // ── Native SSO (Google / GitHub) ─────────────────────────────────────────
    // The app is a public PKCE client of Birdo: startSso opens the system
    // browser to the Birdo broker; the browser redirects back to birdo://auth,
    // which MainActivity routes into completeSso to exchange the code for tokens.

    /** Begin native SSO for `provider` ("google" | "github"): generate PKCE +
     *  anti-CSRF state, persist them (the browser round-trip may recreate this
     *  ViewModel / the Activity / the process), then open the system browser at
     *  the Birdo broker. */
    fun startSso(provider: String, context: Context) {
        if (provider != "google" && provider != "github") {
            _uiState.value = _uiState.value.copy(error = "Unsupported sign-in provider")
            return
        }
        val pkce = PkceGenerator.generate()
        val state = PkceGenerator.randomState()
        // Persist to disk — in-memory state does NOT survive the browser
        // round-trip when Android recreates the Activity/ViewModel on the
        // birdo://auth redirect (that made every sign-in fail "session expired").
        oauthStore.save(pkce.verifier, state)

        val url = "${BuildConfig.WEB_BASE_URL}/native/oauth/start" +
            "?provider=$provider" +
            "&code_challenge=${pkce.challenge}" +
            "&redirect_uri=${Uri.encode("birdo://auth")}" +
            "&state=$state"
        try {
            // System browser (ACTION_VIEW), NOT a WebView — the redirect returns
            // via the birdo://auth intent-filter to MainActivity.
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            _uiState.value = _uiState.value.copy(error = null)
        } catch (e: Exception) {
            oauthStore.clear()
            _uiState.value = _uiState.value.copy(error = "Could not open the browser to sign in.")
        }
    }

    /** Handle the birdo://auth?code=&state= redirect: verify state, then
     *  exchange the handoff code (+ our PKCE verifier) for tokens. Reuses the
     *  same 2FA / profile-fetch path as password login. Reads the pending PKCE
     *  state from disk so it works even if a new ViewModel handles the callback. */
    fun completeSso(code: String, state: String) {
        val pending = oauthStore.load()
        if (pending == null) {
            _uiState.value = _uiState.value.copy(error = "Sign-in session expired. Please try again.")
            return
        }
        val (verifier, savedState) = pending
        if (state != savedState) {
            oauthStore.clear()
            _uiState.value = _uiState.value.copy(error = "Sign-in could not be verified. Please try again.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.exchangeNativeOAuth(code, verifier)) {
                is ApiResult.Success -> when (val loginResult = result.data) {
                    is LoginResult.TwoFactorRequired -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            requiresTwoFactor = true,
                            challengeToken = loginResult.challengeToken,
                        )
                    }
                    is LoginResult.Success -> fetchProfileAfterLogin()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = parseLoginError(result.message),
                    )
                }
            }
            oauthStore.clear()
        }
    }

    /** FIX C-2: Verify 2FA token after challenge */
    fun verifyTwoFactor(code: String) {
        val token = _uiState.value.challengeToken ?: return
        if (!is2faCodeComplete(code)) {
            _uiState.value = _uiState.value.copy(error = "Enter a 6-digit code or a backup code")
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

    /**
     * The user confirmed they saved the freshly-minted anonymous ID — release the
     * gate and proceed to Home. Mirrors iOS `acknowledgeAnonymousId()`.
     */
    fun acknowledgeAnonymousId() {
        val state = _uiState.value
        if (state.createdAnonymousId == null) return
        _uiState.value = state.copy(createdAnonymousId = null, isLoggedIn = true)
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
        // Single-flight: ignore a double-submit while a login is already running.
        if (authJob?.isActive == true) return
        authJob = viewModelScope.launch {
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
     * Create a NEW anonymous account in-app and sign in. For users who don't
     * want email/SSO. The 24-digit ID is the only credential; on success the
     * user should be told to save it (surfaced via the profile screen).
     */
    fun registerAnonymous() {
        // Single-flight: ignore a double-submit while a request is already running.
        if (authJob?.isActive == true) return
        authJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.registerAnonymous()) {
                is ApiResult.Success -> {
                    if (result.data.ok) {
                        val minted = result.data.anonymousId?.takeIf { it.isNotBlank() }
                        if (minted != null) {
                            // HOLD before Home until the user acknowledges it.
                            // `isLoggedIn` deliberately stays false: the nav graph
                            // routes on it, so the account-number screen cannot be
                            // swiped past. Tokens are already stored, so
                            // acknowledging is instant and cannot fail.
                            val profile =
                                (repository.getProfile() as? ApiResult.Success)?.data
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                isLoggedIn = false,
                                user = profile,
                                createdAnonymousId = minted,
                            )
                        } else {
                            // The server did not surface an ID (should never
                            // happen). Nothing to save, so do not trap the user
                            // behind a gate with no content — proceed as before.
                            fetchProfileAfterLogin()
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = "Could not create an anonymous account. Please try again.",
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
     *
     * Password re-confirmation is required ONLY for accounts that actually have
     * a password. SSO (Google/GitHub) and password-less anonymous accounts have
     * none, so demanding one here previously made erasure impossible for that
     * entire cohort (the dialog shows no password field yet the ViewModel
     * rejected the blank value). Gate on `hasPassword`, matching the UI
     * (ProfileScreen `requiresPassword = user?.hasPassword ?: true`) and iOS.
     */
    fun deleteAccount(password: String) {
        val requiresPassword = _uiState.value.user?.hasPassword ?: true
        if (requiresPassword && !InputValidator.isValidPassword(password)) {
            _uiState.value = _uiState.value.copy(deleteAccountError = "Please enter your password")
            return
        }
        // Send null for password-less accounts so the backend takes the
        // session-authenticated path rather than a blank-password comparison.
        val submitted: String? = if (requiresPassword) password else password.ifBlank { null }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeletingAccount = true, deleteAccountError = null)
            when (val result = repository.deleteAccount(submitted)) {
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
