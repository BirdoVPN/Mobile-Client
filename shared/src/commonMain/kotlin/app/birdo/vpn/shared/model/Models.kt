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
    /**
     * Defaulted so a null or absent email still decodes (the shared `Json`
     * coerces null to the default): the account API's phase 2 sends null for
     * anonymous accounts (ACCOUNT-API-2026-10-01, item 86), and a required
     * field would fail the whole profile.
     */
    val email: String = "",
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
    /**
     * Item 86 (ACCOUNT-API-2026-10-01): `"anonymous"` or `"standard"`, and the
     * same fact as a boolean. Both absent from a server older than that API;
     * `isAnonymousAccount(user)` then falls back to the email's shape.
     */
    val accountType: String? = null,
    val isAnonymous: Boolean? = null,
    /**
     * Item 86: an anonymous account's bare 24-digit number, null for a
     * standard account or an older server. It is the account's ONLY
     * credential: never log it, never put it in a crash report.
     */
    val accountNumber: String? = null,
) {
    /** Leaves out [email] and [accountNumber]: either can carry an anonymous account's credential. */
    override fun toString(): String =
        "UserProfile(id=$id, accountType=$accountType, isAnonymous=$isAnonymous, hasPassword=$hasPassword, isSSO=$isSSO)"
}

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
    /**
     * Where the plan was bought — the backend resolver's EntitlementSource
     * ("WEB", "APPLE_APP_STORE", "GOOGLE_PLAY", "FREE_FLOOR"). NOT sent by
     * `/vpn/stats` today; read when a backend that sends it is live, so the
     * store paywalls can tell a web or other-store subscriber apart from their
     * own (audit 2026-09-29, A-9). Absent = unknown.
     */
    val source: String? = null,
    /** Every rail entitling the account right now, when the backend reports them. */
    val liveSources: List<String>? = null,
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
) {
    /**
     * Leaves out [anonymousId] (the account's only credential), [password]
     * and [deviceId] (a per-install identifier), like [UserProfile]
     * (REVIEW-AND2-010).
     */
    override fun toString(): String =
        "AnonymousLoginRequest(platform=$platform, platformVersion=$platformVersion, appVersion=$appVersion)"
}

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
) {
    /** Leaves out the account number, the tokens and the 2FA challenge (REVIEW-AND2-010). */
    override fun toString(): String =
        "AnonymousLoginResponse(ok=$ok, hasTokens=${tokens != null}, requiresTwoFactor=$requiresTwoFactor)"
}

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

// ─── Client configuration ────────────────────────────────────────────────────

/**
 * `GET /api/client-config` — only the fields this client acts on.
 *
 * NOT a full mirror of the payload. The endpoint also serves cert pins,
 * per-plan feature entitlements and consent copy; pins are vendored into
 * `third_party/` and enforced from there, and the plan entitlements already
 * arrive with the subscription, so modelling them here would create a second
 * source of truth for each. Unknown keys are ignored by the shared `Json`
 * (NetworkModule.json, `ignoreUnknownKeys = true`), so web-side additions never
 * break a shipped client.
 *
 * The field is NULLABLE and defaulted, and the difference between `false` and
 * absent is load-bearing — see [dnsFilteringAvailable].
 */
@Serializable
data class ClientConfigResponse(
    /**
     * BirdoShield (D18) fleet gate: is `DNS_FILTERING_ENABLED` on for the fleet
     * this account dials? The per-device opt-in is the `dnsFiltering` connect
     * flag; this says whether that flag can do anything at all.
     *
     * `null` (key absent — a web deploy older than birdo-web#465) means UNKNOWN,
     * which callers must treat as AVAILABLE, not as off. The asymmetry is the
     * point: a wrongly-`false` value hides a feature that works, while a
     * wrongly-available one costs a greyed-out row appearing a moment late.
     * Only an explicit `false` disables the toggle.
     */
    val dnsFilteringAvailable: Boolean? = null,
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
    /**
     * Item 85 (ACCOUNT-API-2026-10-01): the TOTP or backup code an account
     * with 2FA must add, after the server answered 403 `two_factor_required`.
     * Null is OMITTED from the body (the shared `Json` does not encode
     * defaults), so a server older than that API never sees the key.
     */
    val twoFactorCode: String? = null,
)

@Serializable
data class DeleteAccountResponse(
    val success: Boolean = false,
    val message: String? = null,
    val deletedItems: Int = 0,
    val anonymizedItems: Int = 0,
    /**
     * App Store / Google Play subscriptions that are STILL BILLING after the
     * account was erased. Deleting a Birdo account cancels a web (Polar)
     * subscription, but only Apple or Google can cancel a store one (audit
     * 2026-09-29, A-8 / C-9), so the server lists them and the app tells the
     * user where to cancel.
     *
     * Optional on the wire: a backend that predates the field omits it, and
     * coerceInputValues maps an explicit null to the empty default.
     */
    val storeSubscriptionsStillBilling: List<StoreSubscriptionStillBilling> = emptyList(),
)

/**
 * `GET /api/v1/gdpr/delete/preflight`: what deleting the account will and will
 * not stop, fetched when the deletion dialog opens so it can name the stores
 * BEFORE the user confirms (second-pass #9; backend `DeletionPreflight`).
 *
 * Every field defaults: a failed or odd preflight must never block a deletion,
 * the dialog then shows its static store warning instead.
 */
@Serializable
data class DeletionPreflightResponse(
    val success: Boolean = false,
    /** Store subscriptions that will KEEP BILLING after the deletion. */
    val storeSubscriptionsStillBilling: List<StoreSubscriptionStillBilling> = emptyList(),
    /** A web (Polar) subscription is billing and the deletion will cancel it. */
    val webSubscriptionWillBeCancelled: Boolean = false,
)

/** One entry of [DeleteAccountResponse.storeSubscriptionsStillBilling]. Every field optional. */
@Serializable
data class StoreSubscriptionStillBilling(
    /** "GOOGLE_PLAY" or "APPLE_APP_STORE" (the backend's EntitlementSource). */
    val store: String? = null,
    val productId: String? = null,
    /** ISO-8601 end of the current paid period, when known. */
    val expiresAt: String? = null,
) {
    val isGooglePlay: Boolean get() = store.equals("GOOGLE_PLAY", ignoreCase = true)
    val isAppStore: Boolean get() = store.equals("APPLE_APP_STORE", ignoreCase = true)
}

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

/**
 * Wire values for [ConnectRequest.fallbackReason].
 *
 * These MUST match the backend's ConnectDto `fallbackReason` @IsIn list exactly
 * — it rejects anything else with a 400, and a 400 on the fallback retry means a
 * censored user is left with no working transport at all. Kept as constants
 * rather than an enum so the field stays a plain nullable String on the wire and
 * an older backend that doesn't know the field simply ignores it.
 */
object TransportFallbackReason {
    /** No WireGuard handshake inside the probe window — the common DPI case. */
    const val HANDSHAKE_TIMEOUT = "handshake-timeout"

    /** The transport was actively refused (ICMP unreachable, RST, port block). */
    const val TRANSPORT_BLOCKED = "transport-blocked"

    /** DNS resolution for the endpoint failed or was poisoned. */
    const val DNS_BLOCKED = "dns-blocked"
}

@Serializable
data class ConnectRequest(
    val serverNodeId: String? = null,
    val deviceName: String? = null,
    /**
     * Stable device identity (see DeviceInfoProvider). Survives an app UPDATE,
     * so the backend reclaims THIS device's own connection slot on reconnect
     * instead of treating it as a new device (which used to trip "device limit
     * reached" after every update). A REINSTALL mints a new one, deliberately:
     * see DeviceInfoProvider's "KNOWN COST".
     */
    val deviceId: String? = null,
    val preferredRegion: String? = null,
    val clientPublicKey: String? = null,
    val stealthMode: Boolean = false,
    /**
     * ADAPTIVE TRANSPORT: set when this connect is a RETRY after plain
     * WireGuard failed to complete a handshake (see TransportProbe). The server
     * grants the stealth transport on any plan in response — on a filtered
     * network stealth is not a premium feature, it is the only transport that
     * carries packets.
     *
     * Distinct from [stealthMode], which is a deliberate user preference and
     * stays plan-gated. Null on a normal first attempt.
     *
     * Wire values are pinned by the backend's ConnectDto enum; an unrecognised
     * string is rejected with a 400, so build this from
     * [app.birdo.vpn.shared.model.TransportFallbackReason] rather than a literal.
     */
    val fallbackReason: String? = null,
    val quantumProtection: Boolean = false,
    /**
     * Base64 ML-KEM-1024 public key for BirdoPQ v1 PSK derivation.
     * When present, the server encapsulates a fresh shared secret against
     * this key and returns the resulting ciphertext in
     * `ConnectResponse.rosenpassPublicKey`. ~2.1 KB Base64 overhead per
     * connect — see `app/src/main/java/app/birdo/vpn/service/BirdoPqManager.kt`.
     */
    val pqClientPublicKey: String? = null,
    /**
     * BirdoPQ v1 HNDL opt-in: true asserts this client will ML-KEM-decapsulate
     * the returned ciphertext and derive the WireGuard PSK ITSELF, so the
     * server WITHHOLDS the PSK from the response — it never crosses the wire.
     * That is the actual harvest-now-decrypt-later property the feature is
     * sold on: without it the PSK travels under classical TLS and a recorded
     * session plus a future CRQC recovers it. Only ever set true alongside a
     * non-null [pqClientPublicKey] (BirdoPqManager produced the keypair, so
     * the native engine is present); if decapsulation still fails at tunnel
     * time the client fails closed rather than downgrading.
     */
    val pqClientCanDecapsulate: Boolean = false,
    /**
     * Google Play Integrity token (official Android Play build only), bound to a
     * server-issued nonce (GET vpn/attestation/nonce). The backend verifies it
     * and enforces ATTESTATION_POLICY so only the genuine, unmodified Play app
     * can obtain a peer. Null on non-Play builds / when integrity is unavailable.
     */
    val integrityToken: String? = null,
    /**
     * BirdoShield (D18): per-DEVICE opt-in to the filtering DNS resolver
     * (ads, trackers, malware domains blocked at the node's Blocky). Sent at
     * connect time exactly like [stealthMode] — the server decides, from this
     * flag plus its fleet gate, which `dns` array the returned config carries,
     * so the choice takes effect on the NEXT connection, never mid-session.
     *
     * Default false, and NetworkModule.json keeps defaults off the wire, so an
     * untouched toggle sends exactly the pre-D18 body: a backend that predates
     * the field (its ConnectDto is forbidNonWhitelisted) only ever sees the key
     * when the user has switched it on. Not plan-gated. Twin: [MultiHopConnectRequest].
     */
    val dnsFiltering: Boolean = false,
    /**
     * A1-034: this connect REPLACES the live session [currentKeyId] rides,
     * through that very tunnel (the in-place live rebuild, iOS #350). The
     * server defers that one key's eviction until the new peer handshakes,
     * instead of evicting it inline and blackholing the request's own path.
     * Both off the wire on an ordinary connect (defaults stay off).
     */
    val rebuild: Boolean = false,
    val currentKeyId: String? = null,
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
    // See `app/src/main/java/app/birdo/vpn/service/BirdoPqManager.kt` and
    // `native/rosenpass-jni/src/lib.rs` for the canonical protocol spec.
    val quantumEnabled: Boolean = false,
    val rosenpassPublicKey: String? = null,
    val rosenpassEndpoint: String? = null,
    /**
     * A1-034: echoes [ConnectRequest.currentKeyId] when — and only when — the
     * server deferred that key's eviction for a rebuild. Anything else means
     * the old peer was, or may have been, evicted inline.
     */
    val deferredKeyId: String? = null,
    /**
     * A1-034: beside `success: false`, why a rebuild was refused before the
     * server touched anything (the schema's RebuildRefusal values, a String so
     * a new one can never fail the decode).
     */
    val rebuildRefused: String? = null,
    /**
     * Beside `success: false`: the Free plan's data allowance for this period
     * is used up (birdo-web #590, the connect gate in vpn.service.ts), and
     * [message] says how much and when it resets. A plan decision that asking
     * again cannot change, so it ends the session as QUOTA_EXCEEDED rather
     * than as a generic refusal (REVIEW-AND2-001).
     */
    val quotaExceeded: Boolean = false,
)

// ─── Multi-Hop (Double VPN) ──────────────────────────────────────────────────

@Serializable
data class MultiHopConnectRequest(
    val entryNodeId: String,
    val exitNodeId: String,
    val deviceName: String? = null,
    /** Stable device identity — see [ConnectRequest.deviceId]. */
    val deviceId: String? = null,
    val clientPublicKey: String? = null,
    val stealthMode: Boolean = false,
    /**
     * ADAPTIVE TRANSPORT: set when this connect is a RETRY after plain
     * WireGuard failed to complete a handshake (see TransportProbe). The server
     * grants the stealth transport on any plan in response — on a filtered
     * network stealth is not a premium feature, it is the only transport that
     * carries packets.
     *
     * Distinct from [stealthMode], which is a deliberate user preference and
     * stays plan-gated. Null on a normal first attempt.
     *
     * Wire values are pinned by the backend's ConnectDto enum; an unrecognised
     * string is rejected with a 400, so build this from
     * [app.birdo.vpn.shared.model.TransportFallbackReason] rather than a literal.
     */
    val fallbackReason: String? = null,
    val quantumProtection: Boolean = false,
    val pqClientPublicKey: String? = null,
    /**
     * BirdoPQ v1 HNDL opt-in — see [ConnectRequest.pqClientCanDecapsulate].
     * Declared on BOTH request types (this pair is the classic duplicated
     * wire-model twin): the double-hop route is the MOST privacy-sensitive
     * path, and the backend forwards the flag there too so the PSK is
     * withheld from the wire exactly as on single-hop.
     */
    val pqClientCanDecapsulate: Boolean = false,
    /**
     * Google Play Integrity token — see [ConnectRequest.integrityToken].
     *
     * The backend enforces attestation on the multi-hop connect exactly as it
     * does on the single-hop one. Without this field a client could reach a
     * peer-issuing endpoint while skipping attestation entirely, so multi-hop
     * MUST attach the same token single-hop does.
     */
    val integrityToken: String? = null,
    /**
     * BirdoShield (D18) — see [ConnectRequest.dnsFiltering]. Declared on BOTH
     * request types (the duplicated wire-model twin): the backend's
     * multiHopConnectSchema carries the same optional boolean, so a double-hop
     * user gets the filtering resolver exactly as a single-hop one does.
     */
    val dnsFiltering: Boolean = false,
    /** A1-034 — see [ConnectRequest.rebuild]; the multi-hop twin. */
    val rebuild: Boolean = false,
    val currentKeyId: String? = null,
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
    /** A1-034 — see [ConnectResponse.deferredKeyId]. */
    val deferredKeyId: String? = null,
    /** A1-034 — see [ConnectResponse.rebuildRefused]. */
    val rebuildRefused: String? = null,
    /** See [ConnectResponse.quotaExceeded]: multi-hop passes the single-hop gate's refusal through. */
    val quotaExceeded: Boolean = false,
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

// ─── Key Rotation ─ REMOVED 2026-10-01 ───────────────────────────────────────
//
// KeyRotationRequest/Response described `POST vpn/connections/{keyId}/rotate`,
// which the backend never shipped; the only caller was gated off by a constant
// `keyRotationSupported = false` (A2-036). A fresh key per connect is the key
// lifetime today. Bring the types back with the endpoint, not before it.

// ─── Protocol Error Codes ─ RETIRED 2026-09-20 ─────────────────
//
// `ProtocolErrorCode` (25 values with a user-facing message per value) and
// `ApiErrorBody` lived here mirroring birdo-shared's protocol.json. Owner
// decision: retire rather than implement on the wire.
//
// Unlike the desktop mirror, which at least fed two real code paths, these
// two were referenced by NOTHING outside their own declarations and the two
// typealias lines in app/.../data/model/Models.kt. Android never parsed an
// error body through them.
//
// The key they described, camelCase `errorCode`, appears nowhere in
// birdo-web either: its only `errorCode` is Turnstile's unrelated
// `errorCodes` array. Removed from the SSOT in birdo-shared #10 and from
// the desktop in Desktop-Client #193.
//
// To bring it back, implement it SERVER-SIDE first and pin it with a test
// against the serialized response. A type that describes a field nobody
// sends reads as a contract and is not one.

// ─── Heartbeat ───────────────────────────────────────────────────────────────

@Serializable
data class HeartbeatResponse(
    val valid: Boolean = true,
    val serverOnline: Boolean = true,
    val message: String? = null,
    /**
     * WHY the key is in this state, from a backend with birdo-web's heartbeat
     * reasons (WEB-HB): "ok", "server_offline", "revoked", "evicted", "reaped"
     * or "not_found". Absent from an older backend, and a value this build
     * does not know means the same: today's handling. A plain String, never an
     * enum, so a new reason can never fail the decode of a whole heartbeat.
     *
     * Not in the vendored contract yet: WEB-HB adds it to
     * backend/contract/vpn-protocol.schema.json, and the copy in contract/ is
     * re-vendored once that is on birdo-web's main.
     */
    val reason: String? = null,
    /**
     * The Free plan's monthly data allowance is used (birdo-web PR #590,
     * enforced at check-in). With `valid: true` the session is inside its
     * grace window and ends at [quotaGraceEndsAt]; with `valid: false` (and
     * `reason: "quota_exceeded"`) the peer is already removed. Absent from a
     * backend without the quota check.
     */
    val quotaExceeded: Boolean = false,
    /** ISO-8601 instant the grace window ends. */
    val quotaGraceEndsAt: String? = null,
    /** Seconds left in the grace window, from the server's clock. Preferred over [quotaGraceEndsAt]. */
    val quotaGraceSecondsRemaining: Long? = null,
)
