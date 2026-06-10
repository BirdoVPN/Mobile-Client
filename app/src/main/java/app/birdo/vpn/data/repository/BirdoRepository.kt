package app.birdo.vpn.data.repository

import app.birdo.vpn.data.api.BirdoApi
import app.birdo.vpn.data.auth.DeviceInfoProvider
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.*
import app.birdo.vpn.shared.model.LoginResult
import app.birdo.vpn.utils.InputValidator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

// ── Result type ──────────────────────────────────────────────────

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
}

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

    /** FIX C-1: Persist rotated refresh token from refresh response */
    suspend fun refreshToken(): Boolean = refreshMutex.withLock {
        val refreshToken = tokenManager.getRefreshToken() ?: return@withLock false
        return@withLock try {
            val response = api.refreshToken(RefreshRequest(refreshToken))
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                tokenManager.setAccessToken(body.accessToken)
                // FIX C-1: Store rotated refresh token if returned by backend
                body.refreshToken?.let { tokenManager.setRefreshToken(it) }
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
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

    suspend fun logout() {
        try { api.logout() } catch (_: Exception) { /* best effort */ }
        tokenManager.clearAll()
        invalidateServerCache()
        invalidateSubscriptionCache()
    }

    /**
     * GDPR Art. 17: Delete the user's account and all associated data.
     * Requires password re-confirmation to prevent deletion via stolen JWT.
     * On success, clears all local tokens and cached data.
     */
    suspend fun deleteAccount(password: String): ApiResult<DeleteAccountResponse> {
        val result = withAutoRefresh("Account deletion failed") {
            api.deleteAccount(DeleteAccountRequest(password))
        }
        if (result is ApiResult.Success) {
            // Clear local state — account no longer exists on the server
            tokenManager.clearAll()
            invalidateServerCache()
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
                if (refreshToken()) {
                    val retry = call()
                    if (retry.isSuccessful && retry.body() != null) {
                        ApiResult.Success(retry.body()!!)
                    } else {
                        ApiResult.Error("Session expired", 401)
                    }
                } else {
                    ApiResult.Error("Session expired", 401)
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

    suspend fun connectVpn(
        serverNodeId: String,
        deviceName: String = "Birdo-Android",
        stealthMode: Boolean = false,
        quantumProtection: Boolean = false,
        pqClientPublicKey: String? = null,
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
                    clientPublicKey = clientPublicKey,
                    stealthMode = stealthMode,
                    quantumProtection = quantumProtection,
                    pqClientPublicKey = pqClientPublicKey,
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
        quantumProtection: Boolean = false,
        pqClientPublicKey: String? = null,
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
                    clientPublicKey = clientPublicKey,
                    stealthMode = stealthMode,
                    quantumProtection = quantumProtection,
                    pqClientPublicKey = pqClientPublicKey,
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
                if (refreshToken()) {
                    val retry = api.deletePortForward(id)
                    if (retry.isSuccessful) ApiResult.Success(Unit)
                    else ApiResult.Error("Session expired", 401)
                } else {
                    ApiResult.Error("Session expired", 401)
                }
            } else {
                ApiResult.Error("Failed to delete port forward", response.code())
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Network error")
        }
    }
}
