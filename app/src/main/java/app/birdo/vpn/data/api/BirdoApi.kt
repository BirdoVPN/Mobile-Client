package app.birdo.vpn.data.api

import app.birdo.vpn.billing.GooglePlayLinkRequest
import app.birdo.vpn.billing.GooglePlayLinkResponse
import app.birdo.vpn.billing.GooglePurchaseIntentResponse
import app.birdo.vpn.billing.GoogleStoreRoutes
import app.birdo.vpn.data.model.*
import retrofit2.Response
import retrofit2.http.*

/**
 * Birdo VPN backend REST API.
 * Base URL: https://api.birdo.app (set via BuildConfig.API_BASE_URL).
 */
interface BirdoApi {

    // ── Auth ─────────────────────────────────────────────────────

    @POST("auth/login/desktop")
    suspend fun login(
        @Body request: LoginRequest,
    ): Response<LoginResponse>

    /** FIX C-2: 2FA verification endpoint */
    @POST("auth/2fa/verify")
    suspend fun verifyTwoFactor(
        @Body request: TwoFactorVerifyRequest,
    ): Response<TwoFactorVerifyResponse>

    @POST("auth/refresh")
    suspend fun refreshToken(
        @Body request: RefreshRequest,
    ): Response<RefreshResponse>

    @POST("auth/login/anonymous")
    suspend fun loginAnonymous(
        @Body request: AnonymousLoginRequest,
    ): Response<AnonymousLoginResponse>

    /**
     * Create a NEW anonymous account in-app (server mints the 24-digit ID) and
     * return tokens. Body is device context only. X-Desktop-Client is added
     * globally by AuthInterceptor.
     */
    @POST("auth/register/anonymous")
    suspend fun registerAnonymous(
        @Body request: DeviceInfoRequest,
    ): Response<AnonymousLoginResponse>

    /**
     * Native SSO handoff exchange. Swaps the web broker's single-use PKCE-bound
     * code (delivered to the app via the birdo://auth redirect) for tokens. The
     * X-Desktop-Client header is added globally by AuthInterceptor. Response is
     * the same shape as password login (LoginResponse).
     */
    @POST("auth/native/exchange")
    suspend fun exchangeNativeOAuth(
        @Body request: NativeOAuthExchangeRequest,
    ): Response<LoginResponse>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    // ── GDPR ─────────────────────────────────────────────────────

    /** GDPR Art. 17: Right to Erasure. Requires password re-confirmation. */
    @HTTP(method = "DELETE", path = "v1/gdpr/delete", hasBody = true)
    suspend fun deleteAccount(
        @Body request: DeleteAccountRequest,
    ): Response<DeleteAccountResponse>

    /** GDPR Art. 20: Right to Data Portability. Returns all user data as JSON. */
    @GET("v1/gdpr/export")
    suspend fun exportUserData(): Response<GdprExportResponse>

    // ── App updates ──────────────────────────────────────────────

    /**
     * Server-driven update policy: latest published release + the owner-set
     * support floor. Public endpoint (no auth), safe to poll at launch.
     */
    @GET("updates/android/{version}")
    suspend fun checkAppUpdate(@Path("version") version: String): Response<AppUpdateInfo>

    // ── User ─────────────────────────────────────────────────────

    @GET("auth/me")
    suspend fun getProfile(): Response<UserProfile>

    /**
     * FIX-MOBILE-COMPAT: Backend has no /users/subscription. The canonical
     * subscription/plan endpoint is GET /vpn/stats which returns
     * { plan, status, activeConnections, maxConnections, bandwidthLimitGb,
     *   hasPremiumServers, subscriptionEndsAt } — see VpnQueryService.getUsageStats.
     */
    @GET("vpn/stats")
    suspend fun getSubscription(): Response<SubscriptionStatus>

    /**
     * Redeem a voucher code (BIRD-XXXX-XXXX-XXXX). Vouchers extend the
     * caller's subscription `currentPeriodEnd` by 30 or 90 days. Backend
     * route: POST /vouchers/redeem (NestJS — see backend/src/vouchers).
     * Errors arrive as non-2xx with a JSON body matching RedeemVoucherResponse
     * (the `error` slug indicates which user-facing message to show).
     */
    @POST("vouchers/redeem")
    suspend fun redeemVoucher(
        @Body request: RedeemVoucherRequest,
    ): Response<RedeemVoucherResponse>

    // ── VPN ──────────────────────────────────────────────────────

    @GET("vpn/servers")
    suspend fun getServers(): Response<List<VpnServer>>

    /** Single-use Play Integrity nonce to bind the attestation token to (anti-replay). */
    @GET("vpn/attestation/nonce")
    suspend fun attestationNonce(): Response<AttestationNonceResponse>

    @POST("vpn/connect")
    suspend fun connect(
        @Body request: ConnectRequest,
    ): Response<ConnectResponse>

    @DELETE("vpn/connections/{keyId}")
    suspend fun disconnect(
        @Path("keyId") keyId: String,
    ): Response<Unit>

    @POST("vpn/heartbeat/{keyId}")
    suspend fun heartbeat(
        @Path("keyId") keyId: String,
    ): Response<HeartbeatResponse>

    // ── Key Rotation (P3-25) ─────────────────────────────────────

    @POST("vpn/connections/{keyId}/rotate")
    suspend fun rotateKey(
        @Path("keyId") keyId: String,
        @Body request: KeyRotationRequest,
    ): Response<KeyRotationResponse>

    // ── Multi-Hop (Double VPN) ───────────────────────────────────

    @GET("vpn/multi-hop/routes")
    suspend fun getMultiHopRoutes(): Response<List<MultiHopRoute>>

    @POST("vpn/multi-hop/connect")
    suspend fun connectMultiHop(
        @Body request: MultiHopConnectRequest,
    ): Response<MultiHopConnectResponse>

    // ── Port Forwarding ──────────────────────────────────────────

    @GET("vpn/port-forwards")
    suspend fun getPortForwards(): Response<List<PortForward>>

    @POST("vpn/port-forwards")
    suspend fun createPortForward(
        @Body request: CreatePortForwardRequest,
    ): Response<CreatePortForwardResponse>

    @DELETE("vpn/port-forwards/{id}")
    suspend fun deletePortForward(
        @Path("id") id: String,
    ): Response<Unit>

    // ── Google Play store rail ───────────────────────────────────
    //
    // SUBSCRIPTIONS ONLY. Vouchers and one-time purchases stay on the web by
    // owner decision, so there is deliberately no consumable/one-time endpoint
    // here and none is planned.
    //
    // The route strings live in GoogleStoreRoutes so a server-side rename is a
    // one-file change; see GoogleStoreContract.kt for the request/response
    // shapes this client assumes and where they were read from.

    /**
     * Mint the obfuscatedAccountId that binds the next purchase to this Birdo
     * account. Called BEFORE launchBillingFlow, never after: a purchase made
     * without it cannot be attributed to an anonymous account at all.
     */
    @POST(GoogleStoreRoutes.PURCHASE_TOKEN)
    suspend fun mintGooglePurchaseIntent(): Response<GooglePurchaseIntentResponse>

    /**
     * Present a Play purchaseToken for verification. The body carries no plan,
     * no price and no expiry, because nothing this client says about what it
     * bought is an input to what it gets — the server asks Google.
     */
    @POST(GoogleStoreRoutes.LINK)
    suspend fun linkGooglePurchase(
        @Body request: GooglePlayLinkRequest,
    ): Response<GooglePlayLinkResponse>
}
