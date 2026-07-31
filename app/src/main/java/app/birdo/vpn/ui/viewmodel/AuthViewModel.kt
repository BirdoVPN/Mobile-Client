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
     * A just-minted 24-digit anonymous ID the user has NOT yet confirmed saving.
     * Non-null means sign-in is deliberately parked: `isLoggedIn` stays false so
     * the nav graph holds on Login and shows the save-your-ID dialog. See
     * [AuthViewModel.registerAnonymous].
     */
    val pendingAnonymousId: String? = null,
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

    /**
     * An anonymous ID minted on a previous run that the user never acknowledged
     * (process died / app was killed while the save-your-ID dialog was up).
     *
     * Re-surfacing it takes priority over the fast cold start below: the tokens
     * from that registration ARE valid, so without this the app would resume
     * straight into Home and the ID — the account's only credential — would never
     * be shown again.
     */
    private val unacknowledgedAnonymousId: String? =
        // Blank guard: an empty stored value would otherwise pin the app on a
        // dialog displaying nothing, with isLoggedIn held false, and no way for
        // the user to understand why.
        tokenManager.getPendingAnonymousId()?.takeIf { it.isNotBlank() }

    // FAST COLD START: Decide isLoggedIn synchronously from the locally
    // stored token so the NavHost can route straight to Home. The profile
    // fetch then runs in the background and only updates `user` (or, on a
    // 401, kicks the user back to Login).
    private val initiallyLoggedIn: Boolean =
        tokenManager.isLoggedIn() && unacknowledgedAnonymousId == null
    private val _uiState = MutableStateFlow(
        AuthUiState(
            isLoading = false,
            isLoggedIn = initiallyLoggedIn,
            pendingAnonymousId = unacknowledgedAnonymousId,
        )
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        checkSession()
    }

    private fun checkSession() {
        // Don't even hit the network if we have no token — UI is already on Login.
        // Also skipped while an unacknowledged anonymous ID is pending: a
        // successful profile fetch would flip isLoggedIn and navigate away from
        // the dialog that is the user's last chance to read the ID.
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
     * want email/SSO.
     *
     * The register response carries the minted 24-digit ID exactly once, and
     * that ID is the account's ONLY credential — no email, no password, no reset
     * path. This used to read nothing but `ok` and go straight to
     * fetchProfileAfterLogin(), which dropped the caller into the connected
     * Home screen with the ID discarded; any later token loss (reinstall,
     * storage wipe, Keystore corruption) then destroyed the account permanently
     * and irrecoverably.
     *
     * So on success we do NOT complete sign-in. The ID is persisted (so a
     * config change or process death cannot swallow it — see
     * TokenManager.setPendingAnonymousId) and parked in `pendingAnonymousId`
     * with `isLoggedIn` still false, which holds the nav graph on Login and
     * shows the save-your-ID dialog. [acknowledgeAnonymousId] finishes the
     * sign-in. This matches iOS (`createdAnonymousId` +
     * `acknowledgeAnonymousId()`) and the desktop client.
     */
    fun registerAnonymous() {
        // Single-flight: ignore a double-submit while a request is already running.
        if (authJob?.isActive == true) return
        authJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.registerAnonymous()) {
                is ApiResult.Success -> {
                    val body = result.data
                    val minted = body.anonymousId?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
                    if (body.ok && minted != null) {
                        // Persist BEFORE surfacing: the write has to survive the
                        // user killing the app the instant they see the dialog.
                        tokenManager.setPendingAnonymousId(minted)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = null,
                            pendingAnonymousId = minted,
                        )
                    } else if (body.ok) {
                        // Account exists but the server surfaced no ID (should never
                        // happen). There is nothing to acknowledge, so proceed —
                        // the Profile tab still derives the number from the synthetic
                        // anon_<id>@anonymous.local email. Blocking here would strand
                        // the user on Login holding valid tokens.
                        fetchProfileAfterLogin()
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
     * The user confirmed they have saved the freshly minted anonymous ID: drop
     * the pending copy and complete the sign-in that [registerAnonymous]
     * deliberately withheld.
     *
     * Guarded on a non-null pending ID so a double-tap on "I've saved it"
     * cannot fire a second profile fetch, and so it is a no-op for every other
     * auth path.
     */
    fun acknowledgeAnonymousId() {
        if (_uiState.value.pendingAnonymousId == null) return
        tokenManager.clearPendingAnonymousId()
        _uiState.value = _uiState.value.copy(pendingAnonymousId = null, isLoading = true)
        viewModelScope.launch { fetchProfileAfterLogin() }
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

    /**
     * Turn a sign-in failure into something the user can act on.
     *
     * `raw` is the SERVER'S ERROR BODY, not a bare status line: the repository
     * passes `InputValidator.sanitizeErrorMessage(response.errorBody())`
     * through, and Nest's body is `{"message":"…","statusCode":401}`. So the
     * substring "401" is present on EVERY 401, whatever it actually says.
     *
     * That is what broke this: the first arm used to be
     * `"Invalid credentials" in raw || "401" in raw -> "Invalid email or
     * password"`, and the backend answers 401 for three completely different
     * situations — wrong password ("Invalid credentials"), account lockout
     * ("Too many failed login attempts" / "Account locked due to multiple
     * failed login attempts") and a banned or suspended account ("Unable to
     * sign in. Please contact support."). All three were reported as a wrong
     * password. The locked-out user was handed the one instruction guaranteed
     * to make things worse — retype your password — and every retry on a locked
     * account extends nothing but their own frustration, while a banned user
     * hunted for a password problem that does not exist.
     *
     * The specific sentences are therefore matched BEFORE the generic 401, and
     * the generic 401 stays only as the last resort for a body we do not
     * recognise. Matching is on `lower` so a wording change in case cannot
     * silently drop an arm back into the catch-all.
     */
    private fun parseLoginError(raw: String): String {
        val lower = raw.lowercase()
        return when {
            // Banned / suspended. The backend deliberately returns ONE uniform
            // sentence for both (telling a prober which would be an account
            // oracle), so the honest thing for the app to do is repeat it —
            // there is no self-service step, only support.
            "unable to sign in" in lower -> "Unable to sign in. Please contact support."

            // Locked out after repeated failed attempts. Three server wordings
            // reach here: the lockout reason "Too many failed login attempts",
            // the controller's "Account locked due to multiple failed login
            // attempts", and validateUser's "Account locked. Try again in N
            // minutes." / "Account is locked".
            //
            // NOTE the "failed" in the first matcher: the 429 rate-limit body
            // says "Too many login attempts, please try later" — a different
            // condition (the IP bucket, not the account) with a different
            // remedy, and it must keep falling through to the 429 arm below.
            "account locked" in lower ||
                "account is locked" in lower ||
                "too many failed login attempts" in lower ->
                "Account locked after too many failed sign-in attempts. " +
                    "Wait a few minutes before trying again, or reset your password."

            // The password really was wrong.
            "invalid credentials" in lower -> "Invalid email or password"

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

            // Last resort for an UNRECOGNISED 401 body. Kept because a login
            // form that cannot explain itself should still point at the most
            // likely cause — but it is now genuinely last, so it can no longer
            // hide a lockout, a ban or a suspension.
            "401" in raw -> "Invalid email or password"

            else -> "Login failed: $raw"
        }
    }
}
