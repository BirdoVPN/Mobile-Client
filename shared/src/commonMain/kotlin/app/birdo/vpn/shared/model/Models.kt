package app.birdo.vpn.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Auth ────────────────────────────────────────────────────────────────────

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val deviceType: String? = null,
    val platform: String? = null,
    val platformVersion: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class TokenPair(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
)

sealed class LoginResult {
    data class Success(val ok: Boolean, val tokens: TokenPair) : LoginResult()
    data class TwoFactorRequired(
        val requiresTwoFactor: Boolean,
        val challengeToken: String,
    ) : LoginResult()
}

@Serializable
data class LoginResponse(
    val ok: Boolean? = null,
    val tokens: TokenPair? = null,
    // FIX-MOBILE-COMPAT: Backend returns these as camelCase, not snake_case.
    // Source: backend/src/auth/auth.controller.ts loginDesktop() returns
    // `{ requiresTwoFactor: true, challengeToken }` directly (no NestJS interceptor remaps).
    val requiresTwoFactor: Boolean? = null,
    val challengeToken: String? = null,
) {
    fun toLoginResult(): LoginResult {
        return if (requiresTwoFactor == true && challengeToken != null) {
            LoginResult.TwoFactorRequired(true, challengeToken)
        } else if (tokens != null) {
            LoginResult.Success(ok ?: false, tokens)
        } else {
            LoginResult.Success(false, TokenPair("", ""))
        }
    }
}

/**
 * Native SSO handoff exchange. Presents the single-use handoff `code` the web
 * broker delivered to the app's `birdo://auth` redirect, plus the PKCE
 * `code_verifier` this app generated at the start of the flow (proves it is the
 * same client the challenge was bound to). The response reuses [LoginResponse]
 * (backend returns the same `{ ok, tokens }` / `{ requiresTwoFactor, challengeToken }`
 * shape as password login).
 */
@Serializable
data class NativeOAuthExchangeRequest(
    val code: String,
    @SerialName("code_verifier") val codeVerifier: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val deviceType: String? = null,
    val platform: String? = null,
    val platformVersion: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class TwoFactorVerifyRequest(
    // FIX-MOBILE-COMPAT: Backend Zod schema VerifyCodeSchema expects camelCase.
    val challengeToken: String,
    val token: String,
)

@Serializable
data class TwoFactorVerifyResponse(
    val ok: Boolean,
    val tokens: TokenPair? = null,
    // FIX-MOBILE-COMPAT: Backend two-factor.controller.ts returns camelCase.
    val backupCodeUsed: Boolean = false,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class RefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

// ─── User ────────────────────────────────────────────────────────────────────

@Serializable
data class UserProfile(
    val id: String,
    val email: String,
    val name: String? = null,
    val emailVerified: Boolean = false,
    val createdAt: String = "",
    /**
     * Whether this account has a password at all. SSO accounts sign in through
     * Google/GitHub and have none, so any UI that demands a password from them
     * (the delete-account dialog) can never be satisfied.
     *
     * Defaults to `true` deliberately: if the field is missing because the app
     * is talking to a backend that predates it, fall back to the old
     * always-ask behaviour rather than silently dropping the password prompt
     * for accounts that genuinely need one.
     */
    val hasPassword: Boolean = true,
    val isSSO: Boolean = false,
)

/**
 * FIX-MOBILE-COMPAT: Realigned with backend `GET /vpn/stats` (VpnQueryService.getUsageStats).
 * Backend returns: { plan, status, activeConnections, maxConnections, bandwidthLimitGb,
 *                    bandwidthUsedGb, bandwidthPeriodEnd, bandwidthLastSyncAt,
 *                    bandwidthIsFresh, hasPremiumServers, subscriptionEndsAt }
 *
 * `bandwidthLimitGb` stays a non-null Long: the backend sends null for unlimited
 * (paid / reward window) and coerceInputValues maps that to 0 — so 0 == unlimited
 * (readers gate on `> 0`). The usage fields below are only populated for a real
 * RECON cap; they are null for unlimited subs, so the data meter simply hides.
 */
@Serializable
data class SubscriptionStatus(
    val plan: String = "RECON",
    val status: String = "INACTIVE",
    val activeConnections: Int = 0,
    val maxConnections: Int = 1,
    val bandwidthLimitGb: Long = 0,
    /** GB used this period (2dp). null = unlimited sub (no meter). */
    val bandwidthUsedGb: Double? = null,
    /** ISO-8601 instant when the current cap window resets. */
    val bandwidthPeriodEnd: String? = null,
    /** ISO-8601 instant of the last node usage sync (counters sync ~every 5 min). */
    val bandwidthLastSyncAt: String? = null,
    /** True when the usage figure was synced recently (<6 min); false/null = stale/awaiting first sync. */
    val bandwidthIsFresh: Boolean? = null,
    val hasPremiumServers: Boolean = false,
    val subscriptionEndsAt: String? = null,
)

// ─── Anonymous Login ─────────────────────────────────────────────────────────

@Serializable
data class AnonymousLoginRequest(
    @SerialName("anonymousId") val anonymousId: String,
    val password: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val deviceType: String? = null,
    val platform: String? = null,
    val platformVersion: String? = null,
    val appVersion: String? = null,
)

/** Body for POST /auth/register/anonymous — device context only (all optional);
 *  the server mints the 24-digit ID. Response reuses [AnonymousLoginResponse]. */
@Serializable
data class DeviceInfoRequest(
    val deviceId: String? = null,
    val deviceName: String? = null,
    val deviceType: String? = null,
    val platform: String? = null,
    val platformVersion: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class AnonymousLoginResponse(
    val ok: Boolean = false,
    @SerialName("anonymousId") val anonymousId: String? = null,
    val tokens: TokenPair? = null,
    @SerialName("requiresTwoFactor") val requiresTwoFactor: Boolean = false,
    @SerialName("challengeToken") val challengeToken: String? = null,
)

// ─── Vouchers ────────────────────────────────────────────────────────────────
//
// Vouchers are time-extension codes (30 or 90 days) that extend a user's
// subscription `currentPeriodEnd` and optionally upgrade their plan.
// Backend: POST /vouchers/redeem (NestJS — see backend/src/vouchers).

@Serializable
data class RedeemVoucherRequest(
    val code: String,
)

@Serializable
data class RedeemVoucherResponse(
    val ok: Boolean = false,
    val plan: String = "RECON",
    val durationDays: Int = 0,
    val newPeriodEnd: String? = null,
    val extended: Boolean = false,
    /** Present when ok=false; one of the slugs documented in the controller. */
    val error: String? = null,
)

// ─── App Update ──────────────────────────────────────────────────────────────

/**
 * Server-driven update policy from GET /updates/android/{version}.
 *
 * latestVersion derives from the newest published GitHub release;
 * minSupportedVersion is the owner-set support floor (null = no floor).
 * updateRequired means the backend will refuse VPN connects (HTTP 426) until
 * the app is updated — account access keeps working. All fields default so a
 * newer backend adding fields never breaks an older client.
 */
@Serializable
data class AppUpdateInfo(
    val currentVersion: String = "",
    val latestVersion: String? = null,
    val updateAvailable: Boolean = false,
    val updateRequired: Boolean = false,
    val minSupportedVersion: String? = null,
    val downloadUrl: String? = null,
    val releaseUrl: String? = null,
    val notes: String? = null,
    val publishedAt: String? = null,
)

// ─── GDPR / Account Deletion ─────────────────────────────────────────────────

@Serializable
data class DeleteAccountRequest(
    /**
     * Nullable: SSO (Google/GitHub) and password-less anonymous accounts have no
     * password, so they must be able to request erasure without one (the backend
     * confirms deletion by the authenticated session, and only enforces a
     * password where the account actually has one). Requiring a non-null
     * password here previously stranded that entire cohort's GDPR Art. 17 right
     * to erasure on Android, while iOS already sent nil.
     */
    val password: String? = null,
)

@Serializable
data class DeleteAccountResponse(
    val success: Boolean = false,
    val message: String? = null,
    val deletedItems: Int = 0,
    val anonymizedItems: Int = 0,
)

@Serializable
data class GdprExportResponse(
    val success: Boolean = false,
    val message: String? = null,
    val data: kotlinx.serialization.json.JsonObject? = null,
)

// ─── VPN Servers ─────────────────────────────────────────────────────────────

@Serializable
data class VpnServer(
    val id: String,
    val name: String,
    val country: String,
    val countryCode: String,
    val city: String = "",
    val hostname: String = "",
    val ipAddress: String = "",
    val port: Int = 51820,
    val load: Int = 0,
    /**
     * Minimum plan required to use this node: RECON | OPERATIVE | SOVEREIGN.
     * Owner-controlled per node. A String (not an enum) on purpose: a plan the
     * client has never heard of must not blow up decoding of the whole list.
     */
    val minPlan: String = "RECON",
    /**
     * Server-computed: does THIS user's plan reach [minPlan]? The backend is
     * the only authority on access — the client just renders it. Defaults to
     * true so an older backend that doesn't emit the field doesn't blank the
     * list; the node would simply refuse the connect, as it does today.
     */
    val accessible: Boolean = true,
    /** Convenience mirror of `minPlan != "RECON"`, emitted by the backend. */
    val isPremium: Boolean = false,
    /** Low-load / high-throughput node. NOT a streaming-unblocking claim. */
    val isHighSpeed: Boolean = false,
    /** Node supports inbound port forwarding (see PortForwardScreen). */
    val isPortForwarding: Boolean = false,
    val isOnline: Boolean = true,
    /**
     * DEPRECATED — the backend now hard-codes both to false. Kept purely so
     * this model still decodes the legacy keys. Do not read them: use
     * [isHighSpeed] / [isPortForwarding].
     */
    val isStreaming: Boolean = false,
    /** DEPRECATED — see [isStreaming]. */
    val isP2p: Boolean = false,
)

// ─── VPN Connect ─────────────────────────────────────────────────────────────

/** Response of GET vpn/attestation/nonce — the value the Play Integrity token binds to. */
@Serializable
data class AttestationNonceResponse(
    val nonce: String,
)

@Serializable
data class ConnectRequest(
    val serverNodeId: String? = null,
    val deviceName: String? = null,
    /**
     * Stable device identity (see DeviceInfoProvider). Survives app UPDATE and
     * REINSTALL, so the backend reclaims THIS device's own connection slot on
     * reconnect instead of treating it as a new device (which used to trip
     * "device limit reached" after every update).
     */
    val deviceId: String? = null,
    val preferredRegion: String? = null,
    val clientPublicKey: String? = null,
    val stealthMode: Boolean = false,
    val quantumProtection: Boolean = false,
    /**
     * Base64 ML-KEM-1024 public key for BirdoPQ v1 PSK derivation.
     * When present, the server encapsulates a fresh shared secret against
     * this key and returns the resulting ciphertext in
     * `ConnectResponse.rosenpassPublicKey`. ~2.1 KB Base64 overhead per
     * connect — see `app/src/main/java/app/birdo/vpn/service/RosenpassManager.kt`.
     */
    val pqClientPublicKey: String? = null,
    /**
     * Google Play Integrity token (official Android Play build only), bound to a
     * server-issued nonce (GET vpn/attestation/nonce). The backend verifies it
     * and enforces ATTESTATION_POLICY so only the genuine, unmodified Play app
     * can obtain a peer. Null on non-Play builds / when integrity is unavailable.
     */
    val integrityToken: String? = null,
)

@Serializable
data class ServerNodeInfo(
    val id: String,
    val name: String,
    val region: String = "",
    val country: String = "",
    val hostname: String = "",
)

/**
 * NOTE ON KEY MATERIAL LIFETIME:
 * The `privateKey`, `presharedKey`, and `rosenpassPublicKey` (BirdoPQ ciphertext)
 * fields below carry sensitive cryptographic secrets as immutable `String`s. Because
 * `String` is immutable on both the JVM and Native (Swift), these bytes cannot be
 * securely zeroed and may persist in memory until garbage-collected. Callers MUST
 * minimise the lifetime of any copies and avoid logging/persisting these fields.
 */
@Serializable
data class ConnectResponse(
    val success: Boolean = false,
    val message: String? = null,
    val config: String? = null,
    val keyId: String? = null,
    /** Sensitive: WireGuard private key. See class-level key-material note. */
    val privateKey: String? = null,
    val publicKey: String? = null,
    /** Sensitive: WireGuard pre-shared key. See class-level key-material note. */
    val presharedKey: String? = null,
    val assignedIp: String? = null,
    // IPv6 dual-stack: the client's tunnel IPv6 address (e.g. "fd00:b1d0::5/128"),
    // sent ONLY when the chosen node is IPv6-enabled. When null/absent the client
    // stays IPv4-only and IPv6 remains captured-and-blackholed by the tunnel
    // (leak-safe) — exactly today's behaviour. Mirrors desktop `client_ipv6`.
    val clientIpv6: String? = null,
    val serverPublicKey: String? = null,
    val endpoint: String? = null,
    val dns: List<String>? = null,
    val allowedIps: List<String>? = null,
    val mtu: Int? = null,
    val persistentKeepalive: Int? = null,
    val serverNode: ServerNodeInfo? = null,
    // Stealth Mode (Xray Reality)
    val stealthEnabled: Boolean = false,
    val xrayEndpoint: String? = null,
    val xrayUuid: String? = null,
    val xrayPublicKey: String? = null,
    val xrayShortId: String? = null,
    val xraySni: String? = null,
    val xrayFlow: String? = null,
    // Quantum Protection — BirdoPQ v1 (ML-KEM-1024 PSK derivation).
    //
    // The two `rosenpass*` fields below are RE-USED for BirdoPQ v1 to avoid a
    // breaking schema change. Their semantics changed in client v0.2.0:
    //
    //   `rosenpassPublicKey` — Base64 ML-KEM-1024 ciphertext (1568 B).
    //                          The server encapsulates against the client's
    //                          ML-KEM public key (uploaded in ConnectRequest)
    //                          and returns the resulting ciphertext here.
    //                          The client decapsulates with its persisted
    //                          secret key to recover the shared secret.
    //
    //   `rosenpassEndpoint`  — Base64 per-connect nonce mixed into HKDF so
    //                          each session derives a distinct PSK from the
    //                          same KEM output. Server may use a timestamp,
    //                          random bytes, or any opaque value.
    //
    // See `app/src/main/java/app/birdo/vpn/service/RosenpassManager.kt` and
    // `native/rosenpass-jni/src/lib.rs` for the canonical protocol spec.
    val quantumEnabled: Boolean = false,
    val rosenpassPublicKey: String? = null,
    val rosenpassEndpoint: String? = null,
)

// ─── Multi-Hop (Double VPN) ──────────────────────────────────────────────────

@Serializable
data class MultiHopRoute(
    val entryNodeId: String,
    val exitNodeId: String,
    val entryCountry: String,
    val exitCountry: String,
)

@Serializable
data class MultiHopConnectRequest(
    val entryNodeId: String,
    val exitNodeId: String,
    val deviceName: String? = null,
    /** Stable device identity — see [ConnectRequest.deviceId]. */
    val deviceId: String? = null,
    val clientPublicKey: String? = null,
    val stealthMode: Boolean = false,
    val quantumProtection: Boolean = false,
    val pqClientPublicKey: String? = null,
    /**
     * Google Play Integrity token — see [ConnectRequest.integrityToken].
     *
     * The backend enforces attestation on the multi-hop connect exactly as it
     * does on the single-hop one. Without this field a client could reach a
     * peer-issuing endpoint while skipping attestation entirely, so multi-hop
     * MUST attach the same token single-hop does.
     */
    val integrityToken: String? = null,
)

@Serializable
data class MultiHopNodeInfo(
    val id: String,
    val name: String,
    val country: String,
    val region: String = "",
)

@Serializable
data class MultiHopInfo(
    val entryNode: MultiHopNodeInfo,
    val exitNode: MultiHopNodeInfo,
    val route: String,
)

/** Sensitive key material — see the key-material note on [ConnectResponse]. */
@Serializable
data class MultiHopConnectResponse(
    val success: Boolean = false,
    val message: String? = null,
    val config: String? = null,
    val keyId: String? = null,
    /** Sensitive: WireGuard private key. See [ConnectResponse]'s key-material note. */
    val privateKey: String? = null,
    val publicKey: String? = null,
    /** Sensitive: WireGuard pre-shared key. See [ConnectResponse]'s key-material note. */
    val presharedKey: String? = null,
    val assignedIp: String? = null,
    // IPv6 dual-stack — see ConnectResponse.clientIpv6. Null unless the exit node
    // is IPv6-enabled; otherwise IPv6 stays blackholed by the tunnel (leak-safe).
    val clientIpv6: String? = null,
    val serverPublicKey: String? = null,
    val endpoint: String? = null,
    val dns: List<String>? = null,
    val allowedIps: List<String>? = null,
    val mtu: Int? = null,
    val persistentKeepalive: Int? = null,
    val multiHop: MultiHopInfo? = null,
    val stealthEnabled: Boolean = false,
    val xrayEndpoint: String? = null,
    val xrayUuid: String? = null,
    val xrayPublicKey: String? = null,
    val xrayShortId: String? = null,
    val xraySni: String? = null,
    val xrayFlow: String? = null,
    val quantumEnabled: Boolean = false,
    val rosenpassPublicKey: String? = null,
    val rosenpassEndpoint: String? = null,
)

// ─── Port Forwarding ─────────────────────────────────────────────────────────

@Serializable
data class PortForward(
    val id: String,
    /** TCP/UDP port; valid range is 1..65535. Validated by the UI before submission. */
    val externalPort: Int,
    /** TCP/UDP port; valid range is 1..65535. Validated by the UI before submission. */
    val internalPort: Int,
    val protocol: String = "tcp",
    val enabled: Boolean = true,
)

@Serializable
data class CreatePortForwardRequest(
    /** TCP/UDP port; valid range is 1..65535. Validated by the UI before submission. */
    val internalPort: Int,
    val protocol: String = "tcp",
)

@Serializable
data class CreatePortForwardResponse(
    val success: Boolean = false,
    val portForward: PortForward? = null,
    val message: String? = null,
)

// ─── Google Play Billing ─────────────────────────────────────────────────────
// Removed: Android distributed as APK from GitHub Releases; no Play Billing.

// ─── Key Rotation ────────────────────────────────────────────────────────────

@Serializable
data class KeyRotationRequest(
    val clientPublicKey: String,
)

@Serializable
data class KeyRotationResponse(
    val success: Boolean = false,
    val newKeyId: String = "",
    val serverPublicKey: String = "",
    val presharedKey: String? = null,
    val expiresAt: String = "",
)

// ─── Protocol Error Codes ────────────────────────────────────────────────────

@Serializable
enum class ProtocolErrorCode {
    @SerialName("AUTH_REQUIRED") AUTH_REQUIRED,
    @SerialName("AUTH_EXPIRED") AUTH_EXPIRED,
    @SerialName("SUBSCRIPTION_REQUIRED") SUBSCRIPTION_REQUIRED,
    @SerialName("SUBSCRIPTION_EXPIRED") SUBSCRIPTION_EXPIRED,
    @SerialName("DEVICE_LIMIT_REACHED") DEVICE_LIMIT_REACHED,
    @SerialName("RATE_LIMITED") RATE_LIMITED,
    @SerialName("SERVER_OFFLINE") SERVER_OFFLINE,
    @SerialName("SERVER_FULL") SERVER_FULL,
    @SerialName("NO_SERVERS_AVAILABLE") NO_SERVERS_AVAILABLE,
    @SerialName("TUNNEL_CREATION_FAILED") TUNNEL_CREATION_FAILED,
    @SerialName("TUNNEL_START_FAILED") TUNNEL_START_FAILED,
    @SerialName("DNS_CONFIGURATION_FAILED") DNS_CONFIGURATION_FAILED,
    @SerialName("ROUTE_CONFIGURATION_FAILED") ROUTE_CONFIGURATION_FAILED,
    @SerialName("KILL_SWITCH_FAILED") KILL_SWITCH_FAILED,
    @SerialName("IPV6_BLOCK_FAILED") IPV6_BLOCK_FAILED,
    @SerialName("STEALTH_TUNNEL_FAILED") STEALTH_TUNNEL_FAILED,
    @SerialName("QUANTUM_HANDSHAKE_FAILED") QUANTUM_HANDSHAKE_FAILED,
    @SerialName("ADMIN_REQUIRED") ADMIN_REQUIRED,
    @SerialName("NETWORK_UNREACHABLE") NETWORK_UNREACHABLE,
    @SerialName("HANDSHAKE_TIMEOUT") HANDSHAKE_TIMEOUT,
    @SerialName("DLL_INTEGRITY_FAILED") DLL_INTEGRITY_FAILED,
    @SerialName("JNI_INTEGRITY_FAILED") JNI_INTEGRITY_FAILED,
    @SerialName("SETTINGS_TAMPERED") SETTINGS_TAMPERED,
    @SerialName("BIOMETRIC_FAILED") BIOMETRIC_FAILED,
    @SerialName("UNKNOWN") UNKNOWN;

    val userMessage: String get() = when (this) {
        AUTH_REQUIRED -> "Please sign in to continue"
        AUTH_EXPIRED -> "Your session has expired — please sign in again"
        SUBSCRIPTION_REQUIRED -> "A subscription is required for this feature"
        SUBSCRIPTION_EXPIRED -> "Your subscription has expired"
        DEVICE_LIMIT_REACHED -> "Device limit reached — remove a device to connect"
        RATE_LIMITED -> "Too many requests — please wait a moment"
        SERVER_OFFLINE -> "This server is currently offline"
        SERVER_FULL -> "This server is at capacity — try another"
        NO_SERVERS_AVAILABLE -> "No servers available — check back shortly"
        TUNNEL_CREATION_FAILED -> "Failed to create VPN tunnel"
        TUNNEL_START_FAILED -> "Failed to start VPN tunnel"
        DNS_CONFIGURATION_FAILED -> "Failed to configure DNS"
        ROUTE_CONFIGURATION_FAILED -> "Failed to configure routing"
        KILL_SWITCH_FAILED -> "Kill switch activation failed"
        IPV6_BLOCK_FAILED -> "IPv6 leak protection failed"
        STEALTH_TUNNEL_FAILED -> "Stealth tunnel failed — try without stealth mode"
        QUANTUM_HANDSHAKE_FAILED -> "Post-quantum handshake failed — try without quantum protection"
        ADMIN_REQUIRED -> "Administrator privileges are required"
        NETWORK_UNREACHABLE -> "Network is unreachable — check your connection"
        HANDSHAKE_TIMEOUT -> "Connection timed out — try a closer server"
        DLL_INTEGRITY_FAILED -> "Security check failed — application files may be corrupted"
        JNI_INTEGRITY_FAILED -> "Security check failed — application files may be corrupted"
        SETTINGS_TAMPERED -> "Settings integrity check failed"
        BIOMETRIC_FAILED -> "Biometric authentication failed"
        UNKNOWN -> "An unexpected error occurred"
    }
}

@Serializable
data class ApiErrorBody(
    val errorCode: ProtocolErrorCode? = null,
    val message: String? = null,
)

// ─── Heartbeat ───────────────────────────────────────────────────────────────

@Serializable
data class HeartbeatResponse(
    val valid: Boolean = true,
    val serverOnline: Boolean = true,
    val message: String? = null,
)

// ─── Connection State ────────────────────────────────────────────────────────

/** Cross-platform VPN connection state. */
enum class VpnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR,
    KILL_SWITCH_ACTIVE,
}

/** Sealed result type for API operations. */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: ProtocolErrorCode? = null) : ApiResult<Nothing>()
}

// ─── Connection Quality Reporting ────────────────────────────────────────────

/** Client-reported quality telemetry sent periodically while connected. */
@Serializable
data class QualityReport(
    val keyId: String,
    val latencyMs: Double,
    val jitterMs: Double,
    val packetLossPercent: Double,
    val bytesIn: Long,
    val bytesOut: Long,
    val handshakeAgeSeconds: Long,
    val connectionState: String,
    val platform: String,
)
