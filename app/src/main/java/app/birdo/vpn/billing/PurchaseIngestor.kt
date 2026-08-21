package app.birdo.vpn.billing

/**
 * A Play purchase, reduced to the four facts the ingest pipeline actually uses.
 *
 * Exists so [PurchaseIngestor] can be a plain JVM class with no
 * `com.android.billingclient` import, and therefore be unit-tested on CI
 * without a device, a Play Store or Robolectric. [PlayBillingManager] does the
 * one-line mapping from the real `Purchase`.
 */
data class IngestablePurchase(
    /** The opaque token. The ONLY thing the server is given. */
    val purchaseToken: String,
    /** Play's purchase state. See [State]. */
    val state: State,
    /** True when Play already considers this purchase acknowledged. */
    val isAcknowledged: Boolean,
    /** Product ids on the purchase, for logging and cross-grade only. */
    val productIds: List<String> = emptyList(),
) {
    /** Mirror of `Purchase.PurchaseState`, so the tests need no Play types. */
    enum class State { PURCHASED, PENDING, UNSPECIFIED }
}

/** What one trip through [PurchaseIngestor.ingest] concluded. */
sealed interface IngestOutcome {
    /**
     * The server verified the purchase and wrote the entitlement.
     * [acknowledged] records whether the follow-up acknowledgement succeeded —
     * a false here is not a customer-facing failure (the server acknowledges
     * independently) but it is worth surfacing in logs.
     */
    data class Accepted(
        val plan: String,
        val acknowledged: Boolean,
        val duplicateBilling: DuplicateBillingNotice?,
    ) : IngestOutcome

    /** Deferred / slow payment method. Nothing is owed and nothing is granted yet. */
    data class Pending(val message: String) : IngestOutcome

    /** The server refused, or there is no session to bind to. */
    data class Rejected(val refusal: StoreLinkRefusal, val message: String) : IngestOutcome

    /** Suppressed by [PurchaseRefusalMemory]; no request was made. */
    data object Skipped : IngestOutcome
}

/**
 * Verify-with-the-server, THEN acknowledge. The one path every Play purchase
 * takes, and the only place a purchase is ever acknowledged.
 *
 * ── THE ORDERING INVARIANT ──────────────────────────────────────────────────
 * [acknowledge] is called from exactly ONE branch of [ingest]: after [link] has
 * returned [StoreLinkOutcome.Accepted]. There is no other call site, and
 * `PurchaseIngestorTest` pins it by recording the ORDER of the two calls, not
 * merely that both happened - "both happened" is true of the broken version too.
 *
 * Why it matters, in Play's terms rather than Apple's. Acknowledging tells
 * Google "we have delivered this, keep the money". Do it before the server has
 * written the entitlement and the customer has paid for a subscription Birdo's
 * database does not know about — with no refund, because we told Google not to.
 * Do it never and Google auto-refunds after three days, which is the failure
 * this ordering deliberately accepts as the lesser one, because the SERVER
 * acknowledges independently from both the /link handler and the RTDN sink
 * (google-play-store.service.ts:364, :644). The client is the third chance, not
 * the only one.
 *
 * Everything is injected as a suspending lambda so the whole policy is testable
 * without Play, without the network and without a clock.
 *
 * @param link        POST payments/store/google/link.
 * @param acknowledge BillingClient.acknowledgePurchase; returns true on success.
 * @param isSignedIn  is there a usable Birdo session right now.
 * @param refusals    session memory of refusals; see [PurchaseRefusalMemory].
 */
class PurchaseIngestor(
    private val link: suspend (String) -> StoreLinkOutcome,
    private val acknowledge: suspend (String) -> Boolean,
    private val isSignedIn: () -> Boolean,
    private val refusals: PurchaseRefusalMemory = PurchaseRefusalMemory(),
) {

    suspend fun ingest(purchase: IngestablePurchase): IngestOutcome {
        when (purchase.state) {
            // Ask to Buy, a bank transfer, a cash top-up, or SCA still pending.
            // Google has not taken the money and the subscription is not active,
            // so there is nothing to link and — critically — nothing to
            // acknowledge: acknowledging a PENDING purchase is a DEVELOPER_ERROR
            // and would be a claim we had delivered something unpaid for. The
            // purchase reappears through the app-scoped listener if it is
            // approved while the app is open, and through the reconcile sweep on
            // the next launch otherwise. That is precisely why the listener is
            // not optional and not tied to a screen.
            IngestablePurchase.State.PENDING -> return IngestOutcome.Pending(PENDING_MESSAGE)

            // Play could not say what this is. Never trusted, never acknowledged;
            // it is re-presented on the next sweep.
            IngestablePurchase.State.UNSPECIFIED ->
                return IngestOutcome.Rejected(StoreLinkRefusal.TRANSIENT, UNSPECIFIED_MESSAGE)

            IngestablePurchase.State.PURCHASED -> Unit
        }

        // Already refused this session for a reason that will not change by
        // asking again, or still inside the rate-limit cooldown. No request.
        if (refusals.shouldSkip(purchase.purchaseToken)) return IngestOutcome.Skipped

        if (!isSignedIn()) {
            // NOT acknowledged: the purchase is real and must survive until
            // there is an account to bind it to. It is re-presented by the
            // reconcile sweep and by onSignedIn().
            return IngestOutcome.Rejected(
                StoreLinkRefusal.NEEDS_SIGN_IN,
                StoreLinkRefusal.NEEDS_SIGN_IN.fallbackMessage,
            )
        }

        return when (val outcome = link(purchase.purchaseToken)) {
            is StoreLinkOutcome.Accepted -> {
                // ONLY NOW. The server owns the entitlement; acknowledging tells
                // Google we have delivered it. Re-acknowledging is a no-op, but
                // skipping the call when Play already says acknowledged keeps the
                // happy path to one round trip.
                val acknowledged =
                    if (purchase.isAcknowledged) true else acknowledge(purchase.purchaseToken)
                IngestOutcome.Accepted(
                    plan = outcome.result.plan,
                    acknowledged = acknowledged,
                    duplicateBilling = outcome.result.duplicateBilling,
                )
            }

            is StoreLinkOutcome.Refused -> {
                refusals.record(purchase.purchaseToken, outcome.refusal)
                // Deliberately NOT acknowledged — see the class doc and
                // StoreLinkRefusal.shouldAcknowledge, which is always false.
                check(!outcome.refusal.shouldAcknowledge) {
                    "a refusal must never be acknowledged"
                }
                IngestOutcome.Rejected(outcome.refusal, outcome.message)
            }
        }
    }

    /** The user explicitly asked to try again; forget every suppression. */
    fun forgetRefusals() = refusals.clear()

    companion object {
        const val PENDING_MESSAGE =
            "Your purchase is waiting for approval or payment. Birdo will unlock automatically " +
                "once Google Play confirms it — you can close the app."

        const val UNSPECIFIED_MESSAGE =
            "Google Play returned a purchase Birdo could not read. If you were charged, tap " +
                "Restore Purchases."

        /** Copy for a successful link. */
        fun purchasedMessage(plan: String): String {
            // Uppercase only a real plan slug. Shouting the placeholder ("YOUR
            // NEW PLAN") would be worse than the missing name it stands in for.
            val name = if (plan.isBlank()) "your new plan" else plan.uppercase()
            return "Thank you — $name is now active on this Birdo account."
        }
    }
}
