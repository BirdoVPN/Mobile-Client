package app.birdo.vpn.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import app.birdo.vpn.data.auth.openSsoBroker
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.data.auth.OAuthStateStore
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.FailureReason
import app.birdo.vpn.data.model.DeletionPreflightResponse
import app.birdo.vpn.data.model.StoreSubscriptionStillBilling
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
    /**
     * A deletion just succeeded. The graph confirms it once on the Login
     * screen (A2-029): an irreversible erasure that ends in a silent jump to
     * Login reads as a sign-out or a crash.
     */
    val accountDeleted: Boolean = false,
    /**
     * App Store / Google Play subscriptions the server reports as still billing
     * after the account was deleted. Non-empty means the user must be told to
     * cancel them in the store: deleting a Birdo account cannot (audit
     * 2026-09-29, A-8 / C-9). Empty when the backend does not send the field.
     */
    val storeSubscriptionsStillBilling: List<StoreSubscriptionStillBilling> = emptyList(),
    /**
     * The deletion preflight, fetched when the deletion dialog opens so it can
     * name the store subscriptions that will keep billing BEFORE the user
     * confirms (second-pass #9). Null while loading, and when the request
     * failed: the dialog then shows its static store warning, and deletion is
     * never blocked on it.
     */
    val deletionPreflight: DeletionPreflightResponse? = null,
    /**
     * A just-minted 24-digit anonymous ID the user has NOT yet confirmed saving.
     * Non-null means sign-in is deliberately parked: `isLoggedIn` stays false so
     * the nav graph holds on Login and shows the save-your-ID dialog. See
     * [AuthViewModel.registerAnonymous].
     */
    val pendingAnonymousId: String? = null,
    /**
     * The session ENDED on its own — a 401 the refresh could not fix — as
     * opposed to the user signing out. The nav graph hands it to the VPN
     * (A2-004: the Login screen used to appear with the tunnel still up and
     * nothing on it to say so or to stop it), and [error] carries the
     * sentence Login shows.
     */
    val sessionExpired: Boolean = false,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repository: BirdoRepository,
    private val tokenManager: TokenManager,
    private val oauthStore: OAuthStateStore,
    private val prefs: AppPreferences,
) : ViewModel() {

    companion object {
        /** Max login attempts before rate limiting kicks in. */
        private const val MAX_LOGIN_ATTEMPTS = 5
        /** Sliding window in seconds for rate limiting. */
        private const val LOGIN_WINDOW_SECS = 60L

        /** Providers the Birdo web broker will start a PKCE flow for.
         *
         *  MUST mirror `NATIVE_OAUTH_PROVIDERS` in
         *  `birdo-web/lib/native-oauth.ts`. Drift is silent in the worst
         *  direction: a provider listed here but not there sends the user out to
         *  a browser that answers 400, and the app is left on a spinner with no
         *  way back. Internal, but not private — [AuthViewModelSsoProviderTest]
         *  asserts the contents. */
        internal val SSO_PROVIDERS = setOf("google", "github", "apple")

        /** The failures that count toward the client-side sign-in throttle. */
        private val CREDENTIAL_REJECTIONS = setOf(
            FailureReason.INVALID_CREDENTIALS,
            FailureReason.INVALID_CODE,
            FailureReason.ACCOUNT_LOCKED,
            FailureReason.ACCOUNT_BLOCKED,
        )
    }

    /** Sliding window of recent failed login attempt timestamps.
     *  SHARED across every credential path (email, 24-digit anonymous ID, 2FA
     *  submission): the credential with the weakest shape must not be the one
     *  the client leaves unthrottled, and duplicate 2FA submits can consume a
     *  backup code twice server-side. */
    private val loginAttempts = mutableListOf<Instant>()

    /**
     * Client-side sliding-window throttle: max [MAX_LOGIN_ATTEMPTS] FAILED
     * attempts per [LOGIN_WINDOW_SECS]. Sets the UI error and returns true when
     * the caller must bail. Defence-in-depth only — the server bucket remains
     * the real limit.
     */
    private fun isRateLimited(): Boolean {
        val now = Instant.now()
        val cutoff = now.minusSeconds(LOGIN_WINDOW_SECS)
        loginAttempts.removeAll { it.isBefore(cutoff) }
        if (loginAttempts.size >= MAX_LOGIN_ATTEMPTS) {
            val oldestInWindow = loginAttempts.first()
            val waitSecs = LOGIN_WINDOW_SECS - java.time.Duration.between(oldestInWindow, now).seconds
            _uiState.value = _uiState.value.copy(error = "Too many login attempts. Please wait ${waitSecs}s.")
            return true
        }
        return false
    }

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
        // Not before the CURRENT consent is accepted (audit D-12, A2-028): GET
        // auth/me is a request from this device's IP, and the consent screen
        // is where the user is told what the app sends. The graph calls
        // onConsentAccepted() the moment it is given.
        if (prefs.hasAcceptedCurrentConsent) checkSession()
    }

    /** The consent screen was just accepted: run what init held back. */
    fun onConsentAccepted() = checkSession()

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
                    if (result.code == 401) onSessionExpired()
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

        if (isRateLimited()) return

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
                    recordFailedAttempt(result)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message,
                    )
                }
            }
        }
    }

    /**
     * Only a DEFINITIVE rejection of the credential counts toward the client
     * throttle. Counting a timeout or an offline attempt locked people out of
     * a correct password they had simply retried on a bad connection (A2-008).
     */
    private fun recordFailedAttempt(error: ApiResult.Error) {
        if (error.reason in CREDENTIAL_REJECTIONS) loginAttempts.add(Instant.now())
    }

    // ── Native SSO (Google / GitHub / Apple) ─────────────────────────────────
    // The app is a public PKCE client of Birdo: startSso opens the system
    // browser to the Birdo broker; the browser redirects back to birdo://auth,
    // which MainActivity routes into completeSso to exchange the code for tokens.

    /** Begin native SSO for `provider` (one of [SSO_PROVIDERS]): generate PKCE +
     *  anti-CSRF state, persist them (the browser round-trip may recreate this
     *  ViewModel / the Activity / the process), then open the system browser at
     *  the Birdo broker. */
    fun startSso(provider: String, context: Context) {
        // Mirrors NATIVE_OAUTH_PROVIDERS in birdo-web/lib/native-oauth.ts. The
        // broker rejects anything else with 400 anyway; this guard only makes
        // the failure legible instead of bouncing the user out to a browser.
        // Apple is a broker provider on ANDROID only — iOS uses the native
        // ASAuthorization flow and never calls startSso.
        if (provider !in SSO_PROVIDERS) {
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
            openSsoBroker(context, url.toUri())
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
                        error = result.message,
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
        // Same throttle as the other credential paths, and single-flight so a
        // double-tap cannot submit (and consume) the same backup code twice.
        if (isRateLimited()) return
        if (authJob?.isActive == true) return

        authJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = repository.verifyTwoFactor(token, code)) {
                is ApiResult.Success -> {
                    fetchProfileAfterLogin()
                }
                is ApiResult.Error -> {
                    recordFailedAttempt(result)
                    _uiState.value = if (result.reason == FailureReason.CHALLENGE_EXPIRED) {
                        // The challenge from the password step is gone and no
                        // code can pass it now. Say so and go back to the start,
                        // instead of calling a correct code wrong until the
                        // throttle locks the user out (A2-008).
                        _uiState.value.copy(
                            isLoading = false,
                            requiresTwoFactor = false,
                            challengeToken = null,
                            error = result.message,
                        )
                    } else {
                        _uiState.value.copy(isLoading = false, error = result.message)
                    }
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
     * The session is dead: from [checkSession], or from the VPN heartbeat's
     * 401 (the graph forwards VpnManager.sessionExpired). Back to Login, with
     * the reason on it. The tokens are left as BirdoRepository left them.
     */
    fun onSessionExpired() {
        if (!_uiState.value.isLoggedIn) return
        _uiState.value = _uiState.value.copy(
            isLoggedIn = false,
            user = null,
            sessionExpired = true,
            error = app.birdo.vpn.service.SessionCopy.SESSION_EXPIRED,
        )
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
        // Same sliding-window throttle as email login — the 24-digit ID is the
        // weakest-shaped credential and must not be the unthrottled one.
        if (isRateLimited()) return
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
                    recordFailedAttempt(result)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message,
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
                        error = result.message,
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
                        storeSubscriptionsStillBilling =
                            result.data.storeSubscriptionsStillBilling,
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isDeletingAccount = false,
                        deleteAccountError = result.message,
                    )
                }
            }
        }
    }

    private var deletionPreflightJob: Job? = null

    /**
     * Ask the server what a deletion would leave billing. Called when the
     * deletion dialog opens. Best effort by design: an error leaves
     * [AuthUiState.deletionPreflight] null and the dialog falls back to its
     * static warning; it never touches the deletion itself.
     */
    fun loadDeletionPreflight() {
        deletionPreflightJob?.cancel()
        _uiState.value = _uiState.value.copy(deletionPreflight = null)
        deletionPreflightJob = viewModelScope.launch {
            val result = repository.deletionPreflight()
            if (result is ApiResult.Success) {
                _uiState.value = _uiState.value.copy(deletionPreflight = result.data)
            }
        }
    }

    fun clearDeleteAccountError() {
        _uiState.value = _uiState.value.copy(deleteAccountError = null)
    }

    /**
     * The user has read the post-deletion notice: the plain confirmation, or
     * the "your store subscription is still billing" one, which also says the
     * account was deleted. Either way it is shown once.
     */
    fun dismissAccountDeletedNotice() {
        _uiState.value = _uiState.value.copy(
            accountDeleted = false,
            storeSubscriptionsStillBilling = emptyList(),
        )
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
}
