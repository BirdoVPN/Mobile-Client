package app.birdo.vpn.data.repository

import app.birdo.vpn.data.api.BirdoApi
import app.birdo.vpn.data.auth.DeviceInfoProvider
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.*
import app.birdo.vpn.shared.model.LoginResult
import app.birdo.vpn.utils.InputValidator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import retrofit2.Response
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// ── Result type ──────────────────────────────────────────────────

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
}

/**
 * Outcome of a token refresh. Distinguishing these is load-bearing (parity with
 * the iOS APIClient): only a DEFINITIVE 401/403 should force the user to
 * re-login; a transient failure (5xx, timeout, network) must keep the session,
 * and an ABORTED refresh (a logout/delete landed while the refresh was in
 * flight) must neither resurrect the session nor trigger a second logout.
 */
internal enum class RefreshOutcome { SUCCESS, UNAUTHORIZED, TRANSIENT }

// ── Repository ──────────────────────────────────────────────────

@Singleton
class BirdoRepository @Inject constructor(
    private val api: BirdoApi,
    private val tokenManager: TokenManager,
    private val deviceInfoProvider: DeviceInfoProvider,
) {
    /**
     * Mutex prevents concurrent token refresh races — multiple 401s triggering
     * parallel refreshes that would invalidate each other's tokens.
     */
    private val refreshMutex = Mutex()

    /**
     * Monotonic session generation. Bumped on logout()/deleteAccount() so an
     * in-flight refreshToken() that started before the sign-out can detect it
     * completed too late and drop its rotated tokens instead of resurrecting a
     * signed-out session on disk (parity with iOS APIClient's SessionGeneration).
     */
    private val sessionGeneration = AtomicLong(0)

    // ── Server cache ─────────────────────────────────────────────

    @Volatile private var cachedServers: List<VpnServer>? = null
    @Volatile private var serverCacheTimestamp: Long = 0L

    // ── Subscription cache ──────────────────────────────────────
    @Volatile private var cachedSubscription: SubscriptionStatus? = null
    @Volatile private var subscriptionCacheTimestamp: Long = 0L

    /** Server list is considered fresh for 60 seconds. */
    companion object {
        private const val SERVER_CACHE_TTL_MS = 60_000L
        /** Subscription is considered fresh for 30 seconds. */
        private const val SUBSCRIPTION_CACHE_TTL_MS = 30_000L

        /**
         * Whole budget for logout's best-effort server-side revocation.
         *
         * The call now runs through withAutoRefresh, which on an expired access
         * token costs three round trips (401 → refresh → retry). NetworkModule
         * sets connect/read/write to 30 s each and sets NO `callTimeout`, so
         * OkHttp does not bound a whole call at all — one round trip that
         * connects slowly and then stalls mid-body can burn 60 s on its own,
         * and three of them are minutes of spinner on a button whose local half
         * has already succeeded. That is why the bound lives here, at the
         * coroutine level, rather than being left to the HTTP client.
         *
         * `internal` so the regression test can assert against the real budget
         * instead of restating 15_000 and quietly drifting from it.
         */
        internal const val LOGOUT_SERVER_CALL_TIMEOUT_MS = 15_000L
    }

    fun invalidateServerCache() {
        cachedServers = null
        serverCacheTimestamp = 0L
    }

    fun invalidateSubscriptionCache() {
        cachedSubscription = null
        subscriptionCacheTimestamp = 0L
    }

    /** Last cached subscription, if still fresh — for instant UI on tab switch. */
    fun cachedSubscriptionOrNull(): SubscriptionStatus? {
        val cached = cachedSubscription ?: return null
        return if (System.currentTimeMillis() - subscriptionCacheTimestamp < SUBSCRIPTION_CACHE_TTL_MS) cached
        else null
    }

    // ── Auth ─────────────────────────────────────────────────────

    /**
     * FIX C-2: Login now returns LoginResult which may be either a Success
     * (with tokens) or a TwoFactorRequired (with a challenge token).
     * Tokens are only stored on Success.
     */
    suspend fun login(email: String, password: String): ApiResult<LoginResult> {
        return try {
            val device = deviceInfoProvider.current()
            val response = api.login(LoginRequest(
                email = email,
                password = password,
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                deviceType = device.deviceType,
                platform = device.platform,
                platformVersion = device.platformVersion,
                appVersion = device.appVersion,
            ))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val result = body.toLoginResult()
                // Only store tokens when login is fully complete (no 2FA pending)
                if (result is LoginResult.Success) {
                    val tokens = result.tokens
                    if (tokens.accessToken.isBlank()) {
                        return ApiResult.Error("Unexpected server response (no tokens)", 0)
                    }
                    tokenManager.setTokens(tokens.accessToken, tokens.refreshToken)
                }
                ApiResult.Success(result)
            } else {
                ApiResult.Error(
                    InputValidator.sanitizeErrorMessage(
                        response.errorBody()?.string(), "Login failed"
                    ),
                    response.code(),
                )
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Network error")
        }
    }

    /**
     * Native SSO: exchange the web broker's handoff code (+ our PKCE verifier)
     * for tokens. Mirrors [login] — tokens stored only on a complete Success
     * (no 2FA pending). The response is the same shape as password login.
     */
    suspend fun exchangeNativeOAuth(code: String, codeVerifier: String): ApiResult<LoginResult> {
        return try {
            val device = deviceInfoProvider.current()
            val response = api.exchangeNativeOAuth(NativeOAuthExchangeRequest(
                code = code,
                codeVerifier = codeVerifier,
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                deviceType = device.deviceType,
                platform = device.platform,
                platformVersion = device.platformVersion,
                appVersion = device.appVersion,
            ))
            if (response.isSuccessful && response.body() != null) {
                val result = response.body()!!.toLoginResult()
                if (result is LoginResult.Success) {
                    val tokens = result.tokens
                    if (tokens.accessToken.isBlank()) {
                        return ApiResult.Error("Unexpected server response (no tokens)", 0)
                    }
                    tokenManager.setTokens(tokens.accessToken, tokens.refreshToken)
                }
                ApiResult.Success(result)
            } else {
                ApiResult.Error(
                    InputValidator.sanitizeErrorMessage(
                        response.errorBody()?.string(), "Sign-in failed"
                    ),
                    response.code(),
                )
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Network error")
        }
    }

    /** FIX C-2: Verify 2FA code after receiving a challenge token from login */
    suspend fun verifyTwoFactor(challengeToken: String, code: String): ApiResult<TwoFactorVerifyResponse> {
        return try {
            val response = api.verifyTwoFactor(TwoFactorVerifyRequest(challengeToken, code))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val tokens = body.tokens
                if (body.ok && tokens != null) {
                    tokenManager.setTokens(tokens.accessToken, tokens.refreshToken)
                }
                ApiResult.Success(body)
            } else {
                ApiResult.Error("2FA verification failed", response.code())
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Network error")
        }
    }

    /**
     * FIX C-1: Persist rotated refresh token from refresh response.
     * Fences against a concurrent logout/delete (finding #6) and separates a
     * definitive 401/403 from a transient failure (finding #7).
     */
    internal suspend fun refreshToken(): RefreshOutcome = refreshMutex.withLock {
        val presented = tokenManager.getRefreshToken() ?: return@withLock RefreshOutcome.UNAUTHORIZED
        // Captured BEFORE the network round-trip; compared after, so a sign-out
        // that lands mid-flight is detected.
        val genAtStart = sessionGeneration.get()
        return@withLock try {
            val response = api.refreshToken(RefreshRequest(presented))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                // Logout fence + CAS: if a logout/delete bumped the generation,
                // or the stored refresh token no longer matches the one we
                // presented, a sign-out (or a competing refresh) landed while we
                // were in flight — drop the rotated pair rather than re-persist
                // a signed-out session. Treated as TRANSIENT so it neither forces
                // a second logout nor resurrects the session.
                if (sessionGeneration.get() != genAtStart ||
                    tokenManager.getRefreshToken() != presented
                ) {
                    RefreshOutcome.TRANSIENT
                } else {
                    tokenManager.setAccessToken(body.accessToken)
                    // FIX C-1: Store rotated refresh token if returned by backend
                    body.refreshToken?.let { tokenManager.setRefreshToken(it) }
                    RefreshOutcome.SUCCESS
                }
            } else if (response.code() == 401) {
                // Definitive: the server rejected this refresh token — so DISCARD it.
                //
                // Not clearing it is what turned one dead token into a storm. The
                // VPN heartbeat runs every 30s and, on 401, called refreshToken()
                // with the SAME stored token, forever: nothing here cleared it and
                // the loop only logs the failure. Server-side, every replay of a
                // consumed jti re-ran reuse detection and revoked the account's
                // sessions AND every WireGuard peer — four revocations in one
                // minute in production on 2026-07-28, for one anonymous session,
                // while the user sat behind a tunnel whose peer was already gone.
                //
                // The server is now idempotent about this, but the client must not
                // replay either: already-shipped app versions cannot be fixed by a
                // release, so both halves need to hold on their own.
                //
                // Bump BEFORE clearing, matching logout()/deleteAccount(): an
                // in-flight refresh completing after this point then sees the new
                // generation and drops its rotated tokens instead of resurrecting
                // a dead session.
                sessionGeneration.incrementAndGet()
                tokenManager.clearAll()
                RefreshOutcome.UNAUTHORIZED
            } else if (response.code() == 403) {
                // Also non-retryable, but deliberately does NOT destroy the tokens.
                // A 403 on this path is not always the token's fault: an
                // infrastructure-level block returns it too — the Caddy client-ip
                // regression put every user into one rate-limit bucket and the API
                // answered 403 across the board. Wiping credentials on that would
                // have irrecoverably signed out the entire userbase during an
                // outage that had nothing to do with their tokens. Sign out, but
                // leave the tokens alone so recovery is possible.
                RefreshOutcome.UNAUTHORIZED
            } else {
                // 5xx / unexpected status — the credentials may still be valid.
                RefreshOutcome.TRANSIENT
            }
        } catch (_: Exception) {
            // Network error / timeout — transient, keep the session.
            RefreshOutcome.TRANSIENT
        }
    }

    suspend fun loginAnonymous(anonymousId: String, password: String? = null): ApiResult<AnonymousLoginResponse> {
        return try {
            val device = deviceInfoProvider.current()
            val response = api.loginAnonymous(AnonymousLoginRequest(
                anonymousId = anonymousId,
                password = password,
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                deviceType = device.deviceType,
                platform = device.platform,
                platformVersion = device.platformVersion,
                appVersion = device.appVersion,
            ))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val tokens = body.tokens
                if (body.ok && tokens != null) {
                    tokenManager.setTokens(tokens.accessToken, tokens.refreshToken)
                }
                ApiResult.Success(body)
            } else {
                ApiResult.Error(
                    InputValidator.sanitizeErrorMessage(
                        response.errorBody()?.string(), "Anonymous login failed"
                    ),
                    response.code(),
                )
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Network error")
        }
    }

    /** Create a NEW anonymous account in-app and store its tokens. Same response
     *  shape as loginAnonymous; the 24-digit ID is returned so the UI can tell
     *  the user to save it. */
    suspend fun registerAnonymous(): ApiResult<AnonymousLoginResponse> {
        return try {
            val device = deviceInfoProvider.current()
            val response = api.registerAnonymous(DeviceInfoRequest(
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                deviceType = device.deviceType,
                platform = device.platform,
                platformVersion = device.platformVersion,
                appVersion = device.appVersion,
            ))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val tokens = body.tokens
                if (body.ok && tokens != null) {
                    tokenManager.setTokens(tokens.accessToken, tokens.refreshToken)
                }
                ApiResult.Success(body)
            } else {
                ApiResult.Error(
                    InputValidator.sanitizeErrorMessage(
                        response.errorBody()?.string(), "Could not create anonymous account"
                    ),
                    response.code(),
                )
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Network error")
        }
    }

    /**
     * Sign out: kill the session SERVER-side, then clear local state.
     *
     * The server call used to be a bare `api.logout()` — the only authenticated
     * call in this repository that did NOT go through [withAutoRefresh]. Access
     * tokens live one hour and refresh tokens thirty days, so the ordinary case
     * (open the app, use it, come back later, tap "Log out") presented an
     * EXPIRED access token: /auth/logout answered 401, nothing retried, and the
     * server never ran `revokeDeviceSessionLineage`. Local tokens were wiped, so
     * the user was shown a clean logout while the refresh lineage stayed alive
     * on the server for up to another thirty days — anyone holding a copy of
     * that refresh token (the exact thing "log out" is supposed to defeat) could
     * still mint sessions. Routing through withAutoRefresh spends one refresh to
     * obtain a live jti and retries, so the lineage actually dies.
     *
     * Everything after the call stays UNCONDITIONAL. Local sign-out must happen
     * even when the device is offline, the server is down, or the refresh token
     * is itself dead — "Log out" can never leave the user signed in on the
     * handset. The server half is best-effort and time-boxed: it can now cost up
     * to three round trips (401 → refresh → retry) and OkHttp allows 30 s each,
     * which is far longer than anyone will watch a spinner for a button whose
     * local work is already done. On timeout the local clear still runs.
     */
    suspend fun logout() {
        // Bump BEFORE clearing so an in-flight refresh that completes after this
        // point sees the new generation and drops its rotated tokens (finding #6).
        // Safe to bump before the refresh below: refreshToken() captures the
        // generation when IT starts, so its fence sees a stable value and the
        // logout's own refresh is not mistaken for a raced one.
        sessionGeneration.incrementAndGet()
        try {
            withTimeout(LOGOUT_SERVER_CALL_TIMEOUT_MS) {
                withAutoRefresh("Logout failed") { api.logout() }
            }
        } catch (_: Exception) { /* best effort — local sign-out proceeds regardless */ }
        tokenManager.clearAll()
        invalidateServerCache()
        invalidateSubscriptionCache()
    }

    /**
     * GDPR Art. 17: Delete the user's account and all associated data.
     * Requires password re-confirmation to prevent deletion via stolen JWT.
     * On success, clears all local tokens and cached data.
     */
    suspend fun deleteAccount(password: String?): ApiResult<DeleteAccountResponse> {
        val result = withAutoRefresh("Account deletion failed") {
            api.deleteAccount(DeleteAccountRequest(password))
        }
        if (result is ApiResult.Success) {
            // Clear local state — account no longer exists on the server.
            // Bump the generation to fence any in-flight refresh (finding #6),
            // and invalidate BOTH caches so the next account on this device can
            // never briefly read the deleted account's plan (finding #15) — the
            // server cache was already cleared, the subscription cache was not.
            sessionGeneration.incrementAndGet()
            tokenManager.clearAll()
            invalidateServerCache()
            invalidateSubscriptionCache()
            // PRIVACY: the SSAID-derived deviceId would otherwise survive the
            // deletion and link the NEXT account registered on this handset to
            // the one just erased. Mint a fresh random identity so the erased
            // account's device fingerprint dies with it.
            deviceInfoProvider.resetDeviceIdentity()
        }
        return result
    }

    // ── Generic auto-refresh wrapper ────────────────────────────

    /**
     * Execute an API call with automatic 401 token refresh and retry.
     *
     * Eliminates the duplicated "call → check 401 → refreshToken → retry"
     * pattern that was copy-pasted across getProfile, getServers, connectVpn.
     */
    private suspend fun <T> withAutoRefresh(
        errorFallback: String,
        call: suspend () -> Response<T>,
    ): ApiResult<T> {
        return try {
            val response = call()
            if (response.isSuccessful && response.body() != null) {
                ApiResult.Success(response.body()!!)
            } else if (response.code() == 401) {
                when (refreshToken()) {
                    RefreshOutcome.SUCCESS -> {
                        val retry = call()
                        if (retry.isSuccessful && retry.body() != null) {
                            ApiResult.Success(retry.body()!!)
                        } else {
                            // Do NOT hard-code 401 here. The refresh SUCCEEDED, so
                            // the session is demonstrably valid — the retry failed
                            // for some other reason. Reporting 401 made
                            // checkSession() sign the user out of a perfectly good
                            // session over a transient 5xx or a timeout on the
                            // retry, which is the same false-logout class the
                            // TRANSIENT outcome below exists to prevent.
                            // Propagate the retry's real status instead.
                            ApiResult.Error(
                                InputValidator.sanitizeErrorMessage(
                                    retry.errorBody()?.string(), errorFallback
                                ),
                                retry.code(),
                            )
                        }
                    }
                    // Definitive: the refresh token is dead → force re-login.
                    RefreshOutcome.UNAUTHORIZED -> ApiResult.Error("Session expired", 401)
                    // Transient (5xx/timeout/network) or a logout landed mid-flight:
                    // keep the session. A non-401 code means checkSession() will
                    // NOT sign the user out (finding #7).
                    RefreshOutcome.TRANSIENT ->
                        ApiResult.Error("Service temporarily unavailable", 503)
                }
            } else {
                ApiResult.Error(
                    InputValidator.sanitizeErrorMessage(
                        response.errorBody()?.string(), errorFallback
                    ),
                    response.code(),
                )
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Network error")
        }
    }

    // ── App updates ──────────────────────────────────────────────

    /**
     * Ask the backend whether a newer app exists and whether this version is
     * still above the support floor. Callers must treat Error as "no update
     * info" — an unreachable update check must never produce UI noise.
     */
    suspend fun checkAppUpdate(): ApiResult<AppUpdateInfo> =
        withAutoRefresh("Update check failed") {
            api.checkAppUpdate(app.birdo.vpn.BuildConfig.APP_VERSION)
        }

    // ── User ─────────────────────────────────────────────────────

    suspend fun getProfile(): ApiResult<UserProfile> =
        withAutoRefresh("Failed to get profile") { api.getProfile() }

    suspend fun getSubscription(forceRefresh: Boolean = false): ApiResult<SubscriptionStatus> {
        if (!forceRefresh) {
            cachedSubscriptionOrNull()?.let { return ApiResult.Success(it) }
        }
        val result = withAutoRefresh("Failed to get subscription") { api.getSubscription() }
        if (result is ApiResult.Success) {
            cachedSubscription = result.data
            subscriptionCacheTimestamp = System.currentTimeMillis()
        }
        return result
    }

    /**
     * Redeem a voucher code. Returns the parsed RedeemVoucherResponse on
     * 2xx; on 4xx the body is parsed as RedeemVoucherResponse so the
     * `error` slug can drive the UI error string. Any non-JSON error
     * (network, 5xx) is returned as ApiResult.Error with a generic message.
     */
    suspend fun redeemVoucher(code: String): ApiResult<RedeemVoucherResponse> = try {
        val response = api.redeemVoucher(RedeemVoucherRequest(code = code))
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) ApiResult.Success(body)
            else ApiResult.Error("Empty response", response.code())
        } else {
            // Try to parse the JSON error body so callers can show a
            // specific user-facing message based on `error`.
            val raw = response.errorBody()?.string()
            val parsed = raw?.let {
                runCatching {
                    kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        coerceInputValues = true
                    }.decodeFromString(RedeemVoucherResponse.serializer(), it)
                }.getOrNull()
            }
            if (parsed?.error != null) {
                ApiResult.Success(parsed.copy(ok = false))
            } else {
                ApiResult.Error("Voucher redemption failed", response.code())
            }
        }
    } catch (e: Exception) {
        ApiResult.Error(e.message ?: "Network error")
    }

    // ── VPN ──────────────────────────────────────────────────────

    /**
     * Fetch the server list, using a short-lived in-memory cache to avoid
     * redundant network calls when navigating back and forth between screens.
     *
     * @param forceRefresh  Bypass the cache (pull-to-refresh).
     */
    suspend fun getServers(forceRefresh: Boolean = false): ApiResult<List<VpnServer>> {
        // Return cached servers if still fresh
        if (!forceRefresh) {
            val cached = cachedServers
            if (cached != null && System.currentTimeMillis() - serverCacheTimestamp < SERVER_CACHE_TTL_MS) {
                return ApiResult.Success(cached)
            }
        }

        val result = withAutoRefresh("Failed to get servers") { api.getServers() }
        if (result is ApiResult.Success) {
            cachedServers = result.data
            serverCacheTimestamp = System.currentTimeMillis()
        }
        return result
    }

    /**
     * Fetch a single-use Play Integrity nonce from the backend. Returns null on
     * any failure (non-fatal — connect then proceeds without an attestation token
     * and the server policy decides what to do).
     */
    suspend fun getAttestationNonce(): String? {
        return try {
            val res = api.attestationNonce()
            if (res.isSuccessful) res.body()?.nonce else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun connectVpn(
        serverNodeId: String,
        deviceName: String = "Birdo-Android",
        stealthMode: Boolean = false,
        /**
         * ADAPTIVE TRANSPORT: non-null marks this connect as a retry after a
         * failed WireGuard handshake, and asks the server for the stealth
         * transport regardless of plan. See TransportFallbackReason.
         */
        fallbackReason: String? = null,
        quantumProtection: Boolean = false,
        pqClientPublicKey: String? = null,
        integrityToken: String? = null,
    ): ApiResult<ConnectResponse> {
        // FIX-1-1: Generate X25519 keypair locally — private key never leaves the device.
        // Uses wireguard-android's crypto module which wraps Curve25519.
        val keyPair = com.wireguard.crypto.KeyPair()
        val clientPublicKey = keyPair.publicKey.toBase64()

        // H-11 FIX: Use CharArray for private key to enable zeroing after use.
        // JVM Strings are immutable and cannot be reliably scrubbed from heap.
        val privateKeyChars = keyPair.privateKey.toBase64().toCharArray()

        // Zero the Key object's internal byte[] via the raw bytes
        val privateKeyBytes = keyPair.privateKey.bytes
        try {
            val result = withAutoRefresh("Connection failed") {
                api.connect(ConnectRequest(
                    serverNodeId = serverNodeId,
                    deviceName = deviceName,
                    deviceId = deviceInfoProvider.current().deviceId,
                    clientPublicKey = clientPublicKey,
                    stealthMode = stealthMode,
                    fallbackReason = fallbackReason,
                    quantumProtection = quantumProtection,
                    pqClientPublicKey = pqClientPublicKey,
                    // A non-null key proves RosenpassManager loaded the native
                    // engine and minted the ML-KEM keypair, so this client WILL
                    // decapsulate — tell the server to withhold the PSK from
                    // the response (the HNDL-safe path). BirdoVpnService fails
                    // closed if decapsulation later fails, so this can never
                    // silently downgrade.
                    pqClientCanDecapsulate = pqClientPublicKey != null,
                    integrityToken = integrityToken,
                ))
            }
            if (result is ApiResult.Success) {
                val body = result.data
                body.keyId?.let { tokenManager.setLastKeyId(it) }
                // FIX-1-1: Store locally generated private key instead of server-provided one.
                // The server no longer returns privateKey when clientPublicKey was sent.
                val localPrivateKey = String(privateKeyChars)
                tokenManager.setWireGuardPrivateKey(localPrivateKey)
                tokenManager.setLastServer(serverNodeId)
                // Inject the locally-generated private key into the response so
                // VpnManager and BirdoVpnService can build the WireGuard config.
                // The server intentionally omits privateKey when clientPublicKey was sent.
                return ApiResult.Success(body.copy(privateKey = localPrivateKey))
            }
            return result
        } finally {
            // H-11 FIX: Zero sensitive key material from memory
            privateKeyChars.fill('\u0000')
            privateKeyBytes.fill(0)
        }
    }

    suspend fun disconnectVpn(): ApiResult<Unit> {
        val keyId = tokenManager.getLastKeyId()
        if (keyId != null) {
            try { api.disconnect(keyId) } catch (_: Exception) { /* best effort */ }
        }
        // FIX-1-8: Clear WG private key from storage after disconnect.
        // Fresh keys are generated on each new connection.
        tokenManager.clearWireGuardPrivateKey()
        return ApiResult.Success(Unit)
    }

    /**
     * FIX-2-10: Send heartbeat to backend to report connection health.
     * P1-9: Returns HeartbeatResponse so callers can act on valid/serverOnline.
     */
    suspend fun sendHeartbeat(): ApiResult<HeartbeatResponse> {
        val keyId = tokenManager.getLastKeyId() ?: return ApiResult.Error("No active key ID")
        return withAutoRefresh("Heartbeat failed") {
            api.heartbeat(keyId)
        }
    }

    /**
     * P2-15: Send quality telemetry to backend. Fire-and-forget — callers ignore failures.
     */
    suspend fun sendQualityReport(report: QualityReport): ApiResult<Unit> {
        return withAutoRefresh("Quality report failed") {
            api.reportQuality(report)
        }
    }

    /** P2-15: Expose key ID for quality reporting */
    fun getLastKeyId(): String? = tokenManager.getLastKeyId()

    /**
     * P1-13: Whether in-session WireGuard key rotation is available.
     *
     * FIX-MOBILE-COMPAT: Backend currently exposes no `POST vpn/connections/{keyId}/rotate`
     * endpoint (route is planned as P3-25). Callers should check this before
     * invoking [rotateKey] and degrade gracefully (keep the existing key) rather
     * than firing a request that can only ever return 501. No UI surfaces this
     * action, so there is nothing promising a feature that does not exist yet.
     */
    val keyRotationSupported: Boolean = false

    /**
     * P1-13: Rotate WireGuard key during a long-running session.
     *
     * Returns a graceful "not available" error while [keyRotationSupported] is
     * false so any caller that reaches this anyway keeps the existing key
     * instead of crashing or spamming the network.
     */
    suspend fun rotateKey(): ApiResult<KeyRotationResponse> {
        return ApiResult.Error("Key rotation not yet supported by backend", 501)
    }

    // ── Multi-Hop (Double VPN) ───────────────────────────────────

    suspend fun getMultiHopRoutes(): ApiResult<List<MultiHopRoute>> =
        withAutoRefresh("Failed to get multi-hop routes") { api.getMultiHopRoutes() }

    suspend fun connectMultiHop(
        entryNodeId: String,
        exitNodeId: String,
        deviceName: String = "Birdo-Android",
        stealthMode: Boolean = false,
        /**
         * ADAPTIVE TRANSPORT — same contract as [connectVpn]'s fallbackReason:
         * non-null marks this connect as a RETRY after plain WireGuard failed
         * to handshake, asking for the stealth transport. The wire model
         * (MultiHopConnectRequest.fallbackReason) and the backend schema both
         * already accept the field on the multi-hop route.
         */
        fallbackReason: String? = null,
        quantumProtection: Boolean = false,
        pqClientPublicKey: String? = null,
        integrityToken: String? = null,
    ): ApiResult<MultiHopConnectResponse> {
        val keyPair = com.wireguard.crypto.KeyPair()
        val clientPublicKey = keyPair.publicKey.toBase64()
        val privateKeyChars = keyPair.privateKey.toBase64().toCharArray()
        val privateKeyBytes = keyPair.privateKey.bytes
        try {
            val result = withAutoRefresh("Multi-hop connection failed") {
                api.connectMultiHop(MultiHopConnectRequest(
                    entryNodeId = entryNodeId,
                    exitNodeId = exitNodeId,
                    deviceName = deviceName,
                    deviceId = deviceInfoProvider.current().deviceId,
                    clientPublicKey = clientPublicKey,
                    stealthMode = stealthMode,
                    fallbackReason = fallbackReason,
                    quantumProtection = quantumProtection,
                    pqClientPublicKey = pqClientPublicKey,
                    // Same HNDL opt-in as the single-hop site — the twin pair
                    // must always change together. See ConnectRequest above.
                    pqClientCanDecapsulate = pqClientPublicKey != null,
                    integrityToken = integrityToken,
                ))
            }
            if (result is ApiResult.Success) {
                val body = result.data
                body.keyId?.let { tokenManager.setLastKeyId(it) }
                val localPrivateKey = String(privateKeyChars)
                tokenManager.setWireGuardPrivateKey(localPrivateKey)
                return ApiResult.Success(body.copy(privateKey = localPrivateKey))
            }
            return result
        } finally {
            privateKeyChars.fill('\u0000')
            privateKeyBytes.fill(0)
        }
    }

    // ── Port Forwarding ──────────────────────────────────────────

    suspend fun getPortForwards(): ApiResult<List<PortForward>> =
        withAutoRefresh("Failed to get port forwards") { api.getPortForwards() }

    suspend fun createPortForward(internalPort: Int, protocol: String = "tcp"): ApiResult<CreatePortForwardResponse> =
        withAutoRefresh("Failed to create port forward") {
            api.createPortForward(CreatePortForwardRequest(internalPort, protocol))
        }

    suspend fun deletePortForward(id: String): ApiResult<Unit> {
        return try {
            val response = api.deletePortForward(id)
            if (response.isSuccessful) {
                ApiResult.Success(Unit)
            } else if (response.code() == 401) {
                when (refreshToken()) {
                    RefreshOutcome.SUCCESS -> {
                        val retry = api.deletePortForward(id)
                        if (retry.isSuccessful) ApiResult.Success(Unit)
                        else ApiResult.Error("Session expired", 401)
                    }
                    RefreshOutcome.UNAUTHORIZED -> ApiResult.Error("Session expired", 401)
                    RefreshOutcome.TRANSIENT ->
                        ApiResult.Error("Service temporarily unavailable", 503)
                }
            } else {
                ApiResult.Error("Failed to delete port forward", response.code())
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Network error")
        }
    }
}
