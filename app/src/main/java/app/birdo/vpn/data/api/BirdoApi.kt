package app.birdo.vpn.data.api

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

    // ── Connection Quality Telemetry (P2-15) ─────────────────────

    @POST("vpn/quality-report")
    suspend fun reportQuality(
        @Body report: QualityReport,
    ): Response<Unit>

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
}
