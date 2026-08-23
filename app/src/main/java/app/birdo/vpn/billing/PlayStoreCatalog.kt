package app.birdo.vpn.billing

/**
 * The pure, testable half of the Google Play in-app-subscription rail.
 *
 * WHY THIS FILE HAS NO ANDROID AND NO BILLING IMPORTS. Everything here compiles
 * into the plain JVM unit-test source set, so the product-to-plan mapping, the
 * storefront state machine and — above all — the classification of the server's
 * refusals can be asserted on CI without a device, a Play Store, a test track
 * or a network. [PlayBillingManager] holds the half that genuinely needs
 * BillingClient and does as little thinking as possible.
 *
 * ── This is a PORT of iosApp/iosApp/Services/StoreCatalog.swift ─────────────
 * The Apple rail shipped first (92340bd) and survived two adversarial reviews.
 * Play has the same failure modes under different names, so the rules are the
 * same rules and are deliberately named the same way:
 *
 *   * an UNVERIFIED purchase is never trusted and never grants entitlement
 *     locally — on Play there is no client-side verification at all, which
 *     makes the rule stronger rather than weaker (see [StoreLinkRefusal]);
 *   * the purchase is ACKNOWLEDGED only after the server has accepted it;
 *   * the purchases listener is APP-scoped, not screen-scoped;
 *   * a 409 refusal is TERMINAL and must never be retried into the rail's
 *     20-per-60s bucket (see [PurchaseRefusalMemory]);
 *   * the zero-products state says so honestly instead of rendering a purchase
 *     button that dead-ends.
 *
 * ── Where Play DIFFERS from Apple, and why ──────────────────────────────────
 *
 *  1. PRODUCT SHAPE. Apple sells four separate product ids (plan x period).
 *     Play sells TWO subscription ids, each carrying a `monthly` and a `yearly`
 *     BASE PLAN. That is the modern Play Console shape and it is what the
 *     backend's DEFAULT_GOOGLE_PRODUCT_PLANS is written around — it maps
 *     `app.birdo.vpn.operative` and `app.birdo.vpn.sovereign`, and accepts the
 *     Apple-style `.monthly`/`.yearly` forms only as a fallback. See
 *     [BirdoPlayProduct].
 *
 *  2. THERE IS NO CLIENT-SIDE VERIFICATION STEP. Apple hands StoreKit a signed
 *     JWS the device can check, so `VerificationResult.unverified` is a state
 *     the iOS rail can observe and reject. Play hands the app an opaque
 *     purchase token and a signature that could only be checked against a
 *     public key baked into the APK — which Google explicitly advises against,
 *     and which an attacker who can repackage the APK can replace anyway. So
 *     the client verifies NOTHING: the token is a lookup key, and the server
 *     asks Google's Developer API what it means. Every purchase is treated the
 *     way iOS treats an unverified one until the server says otherwise.
 *
 *  3. ACKNOWLEDGEMENT HAS A DEADLINE. Apple re-presents an unfinished
 *     transaction forever, at no cost. Google AUTO-REFUNDS and revokes any
 *     subscription left unacknowledged for THREE DAYS. "Never acknowledge on
 *     refusal" is still the right rule — acknowledging a purchase the server
 *     rejected would tell Google to keep money for an entitlement our database
 *     does not have — but it is only SAFE because the server acknowledges
 *     independently, from both POST /payments/store/google/link and the RTDN
 *     sink (google-play-store.service.ts:364 and :644). The client's
 *     acknowledge is a third chance, not the only one.
 */

// ── Product catalogue ───────────────────────────────────────────────────────

/**
 * Billing period of a Birdo Play subscription. The [basePlanId] is the Play
 * Console BASE PLAN id inside the subscription, and it is the string the
 * purchase flow matches an offer on — get it wrong and the flow cannot be
 * launched at all, which is why it is pinned here rather than typed inline.
 */
enum class BirdoBillingPeriod(val basePlanId: String, val priceSuffix: String) {
    MONTHLY("monthly", "/mo"),
    YEARLY("yearly", "/yr"),
    ;

    val renewalSentence: String
        get() = when (this) {
            MONTHLY -> "Renews every month until cancelled."
            YEARLY -> "Renews every year until cancelled."
        }

    companion object {
        /** Parse the SubscriptionScreen's period key. Unknown values are not guessed. */
        fun fromKey(key: String): BirdoBillingPeriod? =
            entries.firstOrNull { it.basePlanId.equals(key, ignoreCase = true) }
    }
}

/**
 * The two auto-renewable subscriptions Birdo sells on Google Play.
 *
 * These identifiers are PROVISIONAL. They mirror `DEFAULT_GOOGLE_PRODUCT_PLANS`
 * in birdo-web's backend (`backend/src/payments/google/google-notification.ts`),
 * which is the authority for what a product id entitles. If an id is ever
 * renamed it must change in BOTH repos, or the server answers
 * STORE_PRODUCT_UNMAPPED and the purchase unlocks nothing.
 *
 * RECON is free and deliberately has no product at all.
 */
enum class BirdoPlayProduct(val productId: String, val planSlug: String) {
    OPERATIVE("app.birdo.vpn.operative", "OPERATIVE"),
    SOVEREIGN("app.birdo.vpn.sovereign", "SOVEREIGN"),
    ;

    companion object {
        /** Every id, in a stable order, for queryProductDetailsAsync. */
        val allProductIds: List<String> = entries.map { it.productId }

        /**
         * The product backing a plan card, or null when that plan is not sold.
         * Null is a legitimate answer (RECON) and callers must handle it rather
         * than defaulting to something purchasable.
         */
        fun forPlan(planSlug: String): BirdoPlayProduct? =
            entries.firstOrNull { it.planSlug.equals(planSlug, ignoreCase = true) }

        /**
         * Reverse lookup for an id that came back from Play. Unknown ids return
         * null rather than guessing a plan — an id we do not recognise is
         * exactly what the server refuses with STORE_PRODUCT_UNMAPPED, and
         * guessing here would paint a plan the user does not have.
         */
        fun fromProductId(productId: String): BirdoPlayProduct? =
            entries.firstOrNull { it.productId == productId }
    }
}

// ── Storefront state ────────────────────────────────────────────────────────

/**
 * Why nothing is purchasable. Kept apart from one another because the honest
 * sentence is different in each case, and because "the Play Store on this
 * device is too old" is a thing the USER can fix while "we have not published
 * the products yet" is not.
 */
enum class StorefrontFailure {
    /** Nothing has gone wrong; Play simply has no Birdo products to sell here. */
    NO_PRODUCTS,

    /**
     * BILLING_UNAVAILABLE / SERVICE_UNAVAILABLE from the Play Store app: it is
     * missing, disabled, out of date, or the device has no Google account.
     */
    PLAY_STORE_UNAVAILABLE,

    /** isFeatureSupported(SUBSCRIPTIONS) said no. Old or stripped Play Store. */
    SUBSCRIPTIONS_UNSUPPORTED,

    /** Could not connect, or the query failed / timed out. Retryable. */
    QUERY_FAILED,
    ;

    val message: String
        get() = when (this) {
            // The expected state until the Play Console products are published
            // and live. The copy must not read like a crash, and must not
            // promise a date we do not control.
            NO_PRODUCTS ->
                "Subscriptions cannot be bought in the app just yet — Google Play has no Birdo " +
                    "products to offer on this device. Nothing is wrong with your account, and " +
                    "anything you already pay for still works. Please try again after the next " +
                    "update."
            PLAY_STORE_UNAVAILABLE ->
                "The Google Play Store is not available on this device, so subscriptions cannot " +
                    "be bought here. Update or enable the Play Store and try again. Anything " +
                    "you already pay for still works."
            SUBSCRIPTIONS_UNSUPPORTED ->
                "This device's version of Google Play does not support subscriptions. Update " +
                    "the Play Store and try again. Anything you already pay for still works."
            QUERY_FAILED ->
                "Could not reach Google Play to load subscription prices. Check your connection " +
                    "and try again."
        }

    /** Is a retry worth offering? Only where the user can plausibly change the answer. */
    val isRetryable: Boolean
        get() = this != NO_PRODUCTS
}

/**
 * What the purchase UI shows. Exists so "no products resolved" can never render
 * as an empty list that looks like a broken screen, and so no state is a
 * spinner with no way out.
 *
 * THE CASE THAT MATTERS TODAY is [Unavailable]. The Play Console products do
 * not exist yet, so a real device gets an EMPTY list back — not an error. An
 * empty list must therefore be a first-class, explained state, and a purchase
 * affordance must never be rendered in it. A CTA that dead-ends in "purchasing
 * unavailable" is exactly what got the iOS build rejected.
 */
sealed interface StorefrontState {
    /** A fetch is in flight. The UI must pair this with [LOAD_DEADLINE_MS]. */
    data object Loading : StorefrontState

    /** At least one product resolved with a usable offer; the cards can be bought. */
    data object Ready : StorefrontState

    /** The fetch finished (or timed out) and nothing is purchasable. */
    data class Unavailable(val failure: StorefrontFailure) : StorefrontState {
        val message: String get() = failure.message
        val canRetry: Boolean get() = failure.isRetryable
    }

    companion object {
        /**
         * Hard ceiling on the spinner. A connection or query can hang behind a
         * captive portal or a wedged Play Store, and a purchase screen that
         * spins forever is indistinguishable from a broken app.
         */
        const val LOAD_DEADLINE_MS: Long = 15_000L

        /**
         * The single decision point, kept pure so every branch is testable.
         *
         * @param isLoading      a connect/query is in flight.
         * @param hasAttempted   at least one query has completed, successfully
         *                       or not. Distinguishes "not asked yet" from
         *                       "asked and got nothing".
         * @param purchasableCount how many products resolved WITH a usable
         *                       offer. Counting raw products instead would let
         *                       a product with no matching base plan render a
         *                       button that cannot launch a flow.
         * @param failure        why the last attempt produced nothing, if it did.
         */
        fun decide(
            isLoading: Boolean,
            hasAttempted: Boolean,
            purchasableCount: Int,
            failure: StorefrontFailure?,
        ): StorefrontState {
            // A stale-but-good catalogue beats a spinner: if products are
            // already loaded, a background refresh must not blank the screen.
            if (purchasableCount > 0) return Ready
            if (isLoading) return Loading
            if (!hasAttempted) return Loading
            return Unavailable(failure ?: StorefrontFailure.NO_PRODUCTS)
        }
    }
}

// ── Server refusals ─────────────────────────────────────────────────────────

/**
 * How `POST /payments/store/google/link` answered.
 *
 * Classification is on the HTTP STATUS and the backend's `details.code` ONLY —
 * never on the message text, which is copy that changes without notice.
 * `details` is the single key GlobalExceptionFilter copies through to the wire;
 * a top-level `code` is rebuilt away and never arrives.
 *
 * The codes come from GooglePlayStoreController / GooglePlayStoreService:
 *   401 GOOGLE_PLAY_PURCHASE_NOT_FOUND
 *   409 STORE_TRANSACTION_ALREADY_LINKED  (reason ALREADY_OWNED |
 *                                          PURCHASED_ON_ANOTHER_ACCOUNT)
 *   409 STORE_PRODUCT_UNMAPPED
 *   503 GOOGLE_PLAY_VERIFICATION_UNAVAILABLE
 *   503 GOOGLE_PLAY_STATE_UNRECOGNISED
 *
 * WARNING: GOOGLE_PLAY_PURCHASE_NOT_FOUND arrives on a **401**, not a 409 — the
 * service throws UnauthorizedException when Google says the token is not a
 * purchase. A 401 on this endpoint therefore has TWO meanings and only the
 * code tells them apart: WITH a code it is Google rejecting the token, WITHOUT
 * one it is the JwtAuthGuard and the Birdo session. Two consequences, and the
 * second one is Android-specific and worse than the iOS version:
 *   * classifying a coded 401 as [NEEDS_SIGN_IN] would tell an already-signed-in
 *     user to sign in (the exact iOS bug fixed in e3da2d5); and
 *   * routing a coded 401 through BirdoRepository.withAutoRefresh() would spend
 *     a single-use refresh token on a session that is perfectly healthy. Refresh
 *     rotation treats a replayed token as THEFT, so that mis-classification can
 *     end in a mass logout. BirdoRepository.linkGooglePurchase therefore
 *     refreshes only on an UNCODED 401.
 */
enum class StoreLinkRefusal {
    /**
     * This Play subscription belongs to a DIFFERENT Birdo account. The
     * account-sharing refusal. Not an error to retry — a decision to explain.
     */
    ALREADY_LINKED_TO_ANOTHER_ACCOUNT,

    /** The backend does not map this product id to a plan. */
    PRODUCT_UNMAPPED,

    /**
     * Google says this token is not a purchase. Usually propagation delay
     * immediately after buying, occasionally a wrong package/service account.
     * NOT terminal: the server's own copy tells the user to try again shortly.
     */
    PURCHASE_NOT_RECOGNISED,

    /**
     * Google reported a subscriptionState the backend does not map, and it
     * refuses to guess. Nothing the user can do; nothing is lost either.
     */
    STATE_UNRECOGNISED,

    /** No usable Birdo session. The purchase is safe; it just cannot be bound yet. */
    NEEDS_SIGN_IN,

    /** The rail's 20-per-60s bucket. Genuinely transient. */
    RATE_LIMITED,

    /** Everything else: 5xx, transport failures, unparseable answers. */
    TRANSIENT,
    ;

    /**
     * Is another attempt with the same purchase token pointless?
     *
     * This is what stops the account-sharing 409 from becoming a retry loop
     * against a rate-limited endpoint. Terminal refusals are recorded once and
     * never re-presented for the rest of the app session.
     */
    val isTerminal: Boolean
        get() = when (this) {
            ALREADY_LINKED_TO_ANOTHER_ACCOUNT, PRODUCT_UNMAPPED -> true
            PURCHASE_NOT_RECOGNISED, STATE_UNRECOGNISED, NEEDS_SIGN_IN, RATE_LIMITED, TRANSIENT -> false
        }

    /**
     * Should the purchase be acknowledged on this outcome?
     *
     * ALWAYS FALSE. A purchase is acknowledged only once the server has
     * ACCEPTED it. Acknowledging a refused purchase would tell Google to keep
     * the customer's money for an entitlement Birdo's database does not have.
     *
     * This does cost something Apple's equivalent does not: Google auto-refunds
     * anything left unacknowledged for three days. It is nevertheless the right
     * rule, because the server acknowledges independently from BOTH the /link
     * handler and the RTDN sink, so a client that never comes back is still
     * covered. Suppressing repeats WITHIN a session is [PurchaseRefusalMemory]'s
     * job, not acknowledgement's.
     */
    val shouldAcknowledge: Boolean get() = false

    /**
     * Fallback copy, used only when the server did not explain itself. When it
     * did, show ITS message — those strings were written for these exact cases
     * and the account-sharing one names the way out, including the support
     * transfer.
     */
    val fallbackMessage: String
        get() = when (this) {
            ALREADY_LINKED_TO_ANOTHER_ACCOUNT ->
                "This Google Play subscription is already linked to a different Birdo account. " +
                    "Sign in to that account to use it, or contact support and we can transfer it."
            PRODUCT_UNMAPPED ->
                "Birdo does not recognise this Google Play product yet. Your purchase is safe — " +
                    "please contact support and we will activate it."
            PURCHASE_NOT_RECOGNISED ->
                "Google Play has not confirmed this purchase yet. Your purchase is safe — wait " +
                    "a moment, then tap Restore Purchases."
            STATE_UNRECOGNISED ->
                "Google Play reported something Birdo does not yet understand about this " +
                    "subscription. This is a problem on Birdo's side, not with your account or " +
                    "your payment. Your purchase is safe; please contact support."
            NEEDS_SIGN_IN ->
                "Sign in to Birdo to add this subscription to your account. Your purchase is " +
                    "safe: tap Restore Purchases once you are signed in."
            RATE_LIMITED ->
                "Too many requests in a row. Your purchase is safe — wait a minute, then tap " +
                    "Restore Purchases."
            TRANSIENT ->
                "Your purchase went through, but Birdo could not confirm it just now. Your " +
                    "purchase is safe — tap Restore Purchases to try again."
        }

    /** Tone for the banner. A refusal the user cannot act on is not an alarm. */
    val isAlarming: Boolean
        get() = when (this) {
            // Deliberately calm: not actionable by the user, and their money is
            // not at risk. A red banner on every launch would be the worst
            // possible reading of any of these.
            ALREADY_LINKED_TO_ANOTHER_ACCOUNT, PRODUCT_UNMAPPED, STATE_UNRECOGNISED,
            PURCHASE_NOT_RECOGNISED,
            -> false
            NEEDS_SIGN_IN, RATE_LIMITED, TRANSIENT -> true
        }

    companion object {
        /**
         * Classify a failed /link call.
         *
         * @param status HTTP status, or null when the client minted the failure
         *               (transport error, unusable 2xx body).
         * @param code   the backend's `details.code`, when it sent one.
         */
        fun classify(status: Int?, code: String?): StoreLinkRefusal {
            // The code is authoritative when present — it is the field the
            // backend added precisely so a client would not have to guess.
            when (code) {
                "STORE_TRANSACTION_ALREADY_LINKED" -> return ALREADY_LINKED_TO_ANOTHER_ACCOUNT
                "STORE_PRODUCT_UNMAPPED" -> return PRODUCT_UNMAPPED
                "GOOGLE_PLAY_PURCHASE_NOT_FOUND" -> return PURCHASE_NOT_RECOGNISED
                "GOOGLE_PLAY_STATE_UNRECOGNISED" -> return STATE_UNRECOGNISED
                "GOOGLE_PLAY_VERIFICATION_UNAVAILABLE" -> return TRANSIENT
            }
            return when (status) {
                401, 403 -> NEEDS_SIGN_IN
                429 -> RATE_LIMITED
                // A 409 whose code we do not recognise is still a CONFLICT: the
                // server decided, and repeating the identical request gets the
                // identical answer. Treat it as terminal rather than retrying
                // into a wall.
                409 -> ALREADY_LINKED_TO_ANOTHER_ACCOUNT
                else -> TRANSIENT
            }
        }
    }
}

/**
 * Which purchase tokens must not be re-presented to the server, and until when.
 *
 * Pure and clock-injected so the whole policy is unit-testable. This is the
 * concrete implementation of "a server refusal is terminal and must not be
 * retried into a rate-limited endpoint":
 *
 *   * a TERMINAL refusal is suppressed for the rest of the app session. The
 *     purchase is deliberately left UNACKNOWLEDGED even so, which is what lets
 *     a support ownership-transfer bind itself with no further action from the
 *     user — one re-presentation per launch, not a loop.
 *   * a NON-TERMINAL refusal is suppressed for [COOLDOWN_MS], so the reconcile
 *     that runs on every app resume cannot walk into the rail's 20-per-60s
 *     bucket and turn a transient failure into a rate-limit ban.
 *
 * [clear] is called only when the user explicitly asks — Restore Purchases —
 * because they may have just signed in to the right account.
 */
class PurchaseRefusalMemory(private val nowMs: () -> Long = System::currentTimeMillis) {

    private val terminal = mutableSetOf<String>()
    private val cooldownUntil = mutableMapOf<String, Long>()

    fun record(purchaseToken: String, refusal: StoreLinkRefusal) {
        if (refusal.isTerminal) {
            terminal += purchaseToken
        } else {
            cooldownUntil[purchaseToken] = nowMs() + COOLDOWN_MS
        }
    }

    /** True when re-presenting this token now would be pointless or abusive. */
    fun shouldSkip(purchaseToken: String): Boolean {
        if (purchaseToken in terminal) return true
        val until = cooldownUntil[purchaseToken] ?: return false
        if (nowMs() < until) return true
        cooldownUntil.remove(purchaseToken)
        return false
    }

    /** The user asked us to try again. Forget everything. */
    fun clear() {
        terminal.clear()
        cooldownUntil.clear()
    }

    companion object {
        /** Matches the server's rate-limit window (20 requests / 60 s). */
        const val COOLDOWN_MS: Long = 60_000L
    }
}

// ── Restore outcome ─────────────────────────────────────────────────────────

/**
 * What "Restore Purchases" found. It must say something in EVERY case,
 * including the common one where there is nothing to restore — a control that
 * silently does nothing reads as broken.
 */
sealed interface StoreRestoreOutcome {
    /** n subscriptions were found and accepted by the server. */
    data class Restored(val count: Int) : StoreRestoreOutcome

    /** Play reported no purchases for this Google account. */
    data object NothingToRestore : StoreRestoreOutcome

    /** Purchases were found but the server refused them. */
    data class Refused(val text: String) : StoreRestoreOutcome

    /** Could not talk to Play or to the server. */
    data class Failed(val text: String) : StoreRestoreOutcome

    val message: String
        get() = when (this) {
            is Restored ->
                if (count == 1) "Your subscription has been restored to this account."
                else "$count subscriptions have been restored to this account."
            NothingToRestore -> NOTHING_TO_RESTORE_MESSAGE
            is Refused -> text
            is Failed -> text
        }

    val isSuccess: Boolean get() = this is Restored

    companion object {
        const val NOTHING_TO_RESTORE_MESSAGE =
            "No Birdo subscription was found for this Google account. If you bought one with a " +
                "different Google account, switch to it in the Play Store and try again."
    }
}

// ── Purchase banner ─────────────────────────────────────────────────────────

/** One transient message for the purchase UI. The newest wins. */
data class StoreNotice(val kind: Kind, val text: String) {
    enum class Kind { SUCCESS, INFO, ERROR }

    val isError: Boolean get() = kind == Kind.ERROR

    companion object {
        fun success(text: String) = StoreNotice(Kind.SUCCESS, text)
        fun info(text: String) = StoreNotice(Kind.INFO, text)
        fun error(text: String) = StoreNotice(Kind.ERROR, text)
    }
}
