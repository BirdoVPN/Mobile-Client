package app.birdo.vpn.billing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * THE ONE PLACE the birdo-web Google-Play store contract is written down.
 *
 * Every route string and every wire type for the Play rail lives here so that a
 * server-side rename is a single-file change on this side. A cross-repo
 * contract that two agents each ASSUMED has already broken this project once
 * (desktop parsed `requiredVersion`/`downloadUrl` while the backend sent
 * `minVersion`/`updateUrl`), so it is written down rather than remembered.
 *
 * ── WHAT THIS CODE ASSUMES THE SERVER DOES ─────────────────────────────────
 * Read from birdo-web at 2026-08-21, sibling agent's tree:
 *   backend/src/payments/google/google-play-store.controller.ts
 *   backend/src/payments/google/google-play-store.service.ts
 *   backend/src/payments/store/store-entitlement.writer.ts
 *   backend/src/common/global-exception.filter.ts
 *
 * 1. POST payments/store/google/purchase-token
 *      auth: JwtAuthGuard (Bearer), rate limit 20/60s
 *      body: none
 *      200 : { "obfuscatedAccountId": "<uuid>" }
 *
 * 2. POST payments/store/google/link
 *      auth: JwtAuthGuard (Bearer), rate limit 20/60s
 *      body: { "purchaseToken": "<opaque, 8..4096 chars>",
 *              "origin"?: "restore"|"auto" }               (F-967-02, below)
 *      200 : { "linked": true, "plan": "OPERATIVE"|"SOVEREIGN"|"RECON",
 *              "state": "<string>", "expiresAt": "<ISO-8601>"|null,
 *              "duplicateBilling"?: { ... },
 *              "entitled"?: boolean,                         (F-967-05)
 *              "needsRestore"?: boolean }                    (F-967-02)
 *      4xx/5xx: the GlobalExceptionFilter envelope, below.
 *      A server older than `origin` answers it with an UNCODED 400: its
 *      ValidationPipe runs forbidNonWhitelisted. BirdoRepository.linkGooglePurchase
 *      then presents the token once more without it (see [StoreLinkOrigin]).
 *
 * 3. Error envelope (GlobalExceptionFilter). `details` is the ONLY key the
 *    filter copies through from a thrown HttpException; a top-level `code` is
 *    rebuilt away and never reaches the wire. Classification therefore reads
 *    `details.code`, never the message text.
 *      { "statusCode": 409, "message": "...", "error": "Conflict",
 *        "timestamp": "...", "path": "...",
 *        "details": { "code": "STORE_TRANSACTION_ALREADY_LINKED",
 *                     "reason": "ALREADY_OWNED" } }
 *
 * 4. RTDN sink POST payments/store/google/notifications is Pub/Sub-only; the
 *    app never calls it and never needs to know about renewals or refunds.
 *
 * Everything is `ignoreUnknownKeys`-tolerant and every non-essential field is
 * nullable with a default, so the server may ADD fields freely. What it may not
 * do without a change here is rename `obfuscatedAccountId`, `purchaseToken`,
 * `plan`, or move the refusal code out of `details.code`.
 */
object GoogleStoreRoutes {
    /** Mints the obfuscatedAccountId that binds a purchase to a Birdo account. */
    const val PURCHASE_TOKEN = "payments/store/google/purchase-token"

    /** Presents a Play purchaseToken for server-side verification and linking. */
    const val LINK = "payments/store/google/link"
}

/**
 * Response of [GoogleStoreRoutes.PURCHASE_TOKEN].
 *
 * The SERVER mints this. Never invent one client-side: it is matched against
 * the StorePurchaseIntent table, and a value we made up names no account, so
 * Google would echo back a claim the server cannot honour. That matters most
 * for anonymous accounts, which have no email for the server to fall back on.
 */
@Serializable
data class GooglePurchaseIntentResponse(
    @SerialName("obfuscatedAccountId") val obfuscatedAccountId: String,
)

/**
 * Why the client is presenting a purchase token (F-967-02, owner 2026-11-13:
 * "Restore tap only").
 *
 * A purchase with no obfuscatedAccountId names no Birdo account (a Play promo
 * code, a resubscribe in the Play Store, a purchase made before the intent was
 * minted). The server binds such a token to whichever session presents it
 * first, for good, so presenting it silently on sign-in or resume could hand a
 * paid subscription to a freshly minted anonymous account. Only the explicit
 * Restore Purchases tap may present one; PurchaseIngestor enforces that, and
 * [wire] tells the server which kind of request it is looking at.
 */
enum class StoreLinkOrigin(val wire: String) {
    /** The user tapped Restore Purchases. The only origin that presents an unbound purchase. */
    RESTORE("restore"),

    /** Everything else: launch, resume and sign-in sweeps, the purchase listener, the already-owned reconcile. */
    AUTO("auto"),
}

/** Request body of [GoogleStoreRoutes.LINK]. */
@Serializable
data class GooglePlayLinkRequest(
    @SerialName("purchaseToken") val purchaseToken: String,
    /**
     * [StoreLinkOrigin.wire]. Null only on the one re-send to a server that
     * predates the field; the wire encoder does not encode defaults, so null
     * leaves the key out entirely and the body is exactly the old one.
     */
    @SerialName("origin") val origin: String? = null,
) {
    /** Never prints [purchaseToken], like the shared API models (MR-1870). */
    override fun toString(): String = "GooglePlayLinkRequest(purchaseToken=[REDACTED])"
}

/**
 * The server is ALSO billing this account on another rail. Rendered because it
 * is about money: the user is paying twice and needs to be told where to
 * cancel.
 *
 * Field names are the backend's `DuplicateBilling`
 * (backend/src/payments/store/store-entitlement.writer.ts:15), read rather than
 * guessed. An earlier draft of this class invented `rail` and `manageUrl`;
 * both would have deserialised to null forever and the banner would have shown
 * nothing, silently — which is the exact cross-repo-assumption failure this
 * file exists to prevent.
 *
 * Only [message] is rendered; the rest are carried so a future screen can name
 * the other subscription without another round of contract archaeology.
 */
@Serializable
data class DuplicateBillingNotice(
    /** The other rail: an EntitlementSource slug, e.g. "POLAR" or "APPLE". */
    val otherSource: String? = null,
    /** Plan slug held on the other rail. */
    val otherPlan: String? = null,
    /** ISO-8601 instant the other subscription runs to. */
    val otherPeriodEnd: String? = null,
    /** Server-authored copy naming the double charge and where to cancel. */
    val message: String? = null,
)

/** 200 response of [GoogleStoreRoutes.LINK]. */
@Serializable
data class GooglePlayLinkResponse(
    val linked: Boolean = true,
    val plan: String = "",
    val state: String = "",
    val expiresAt: String? = null,
    val duplicateBilling: DuplicateBillingNotice? = null,
    /**
     * F-967-05: whether the token grants access right now. `false` for an
     * expired or cancelled-pending token, which the server still links. Null
     * from a server that predates the field, which keeps the old reading: a
     * 200 means granted.
     */
    val entitled: Boolean? = null,
    /**
     * F-967-02: the server will not bind this unbound token from an automatic
     * request; only a Restore tap may. Null (or false) from a server that
     * predates the field.
     */
    val needsRestore: Boolean? = null,
)

/**
 * The GlobalExceptionFilter envelope, parsed only far enough to classify.
 *
 * `details.code` is the authoritative field. Parsing the whole envelope rather
 * than string-matching the message is the point — the message is copy that
 * changes without notice.
 */
@Serializable
data class StoreErrorEnvelope(
    val statusCode: Int? = null,
    val message: String? = null,
    val error: String? = null,
    val details: StoreErrorDetails? = null,
)

@Serializable
data class StoreErrorDetails(
    val code: String? = null,
    val reason: String? = null,
)

/**
 * The result of presenting one purchase token to the server.
 *
 * A deliberately non-[app.birdo.vpn.data.repository.ApiResult] type: the whole
 * point of this rail is that the CLASSIFICATION of a refusal drives behaviour
 * (acknowledge or not, retry or not, alarm or not), and an `Error(message,
 * httpCode)` pair throws away the `details.code` that the classification is
 * built on.
 */
sealed interface StoreLinkOutcome {
    /** The server verified the purchase with Google and wrote the entitlement. */
    data class Accepted(val result: GooglePlayLinkResponse) : StoreLinkOutcome

    /**
     * The server did not accept it. [message] is the server's own words when it
     * wrote any, otherwise [StoreLinkRefusal.fallbackMessageRes].
     */
    data class Refused(val refusal: StoreLinkRefusal, val message: String) : StoreLinkOutcome
}
