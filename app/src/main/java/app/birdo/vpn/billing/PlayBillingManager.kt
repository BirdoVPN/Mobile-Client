package app.birdo.vpn.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * One purchasable card: a Birdo plan at one billing period, with the offer that
 * makes it purchasable already resolved.
 *
 * The presence of an entry in [PlayBillingUiState.offers] is the ONLY thing
 * that may enable a purchase affordance. Rendering a button from a plan
 * constant instead — which is what the screen used to do — is how you ship a
 * CTA that dead-ends in "purchasing unavailable", and that is exactly what got
 * the iOS build rejected under Guideline 2.1.
 */
data class PurchasableOffer(
    val product: BirdoPlayProduct,
    val period: BirdoBillingPeriod,
    /** Play's own localised price string. Never hardcode a price. */
    val formattedPrice: String,
    internal val productDetails: ProductDetails,
    internal val offerToken: String,
)

/** Everything the subscription UI needs from the Play rail. */
data class PlayBillingUiState(
    val storefront: StorefrontState = StorefrontState.Loading,
    val offers: List<PurchasableOffer> = emptyList(),
    /** Product id currently being bought, for a per-card spinner. */
    val purchasingProductId: String? = null,
    val isRestoring: Boolean = false,
    val notice: StoreNotice? = null,
    /** The account is being billed on another rail too. Sticky: it is about money. */
    val duplicateBilling: DuplicateBillingNotice? = null,
) {
    /** The offer for a plan card + period toggle, or null when it is not purchasable. */
    fun offerFor(planSlug: String, period: BirdoBillingPeriod): PurchasableOffer? =
        offers.firstOrNull { it.product.planSlug.equals(planSlug, true) && it.period == period }
}

/**
 * Google Play Billing — the half that genuinely needs `BillingClient`.
 *
 * Every decision this file could make instead lives in [PlayStoreCatalog] and
 * [PurchaseIngestor], which are plain JVM and unit tested. What is left here is
 * I/O: connecting, querying products, driving `launchBillingFlow`, listening
 * for purchase updates, and handing tokens to [PurchaseIngestor].
 *
 * ── Scope ───────────────────────────────────────────────────────────────────
 * `@Singleton`, constructed from [app.birdo.vpn.BirdoApp.onCreate]. The
 * `PurchasesUpdatedListener` is registered once, on the one long-lived
 * BillingClient, so it is APP-scoped by construction and cannot be missed
 * because a screen was closed. That matters for the three cases the purchase
 * call itself never sees: an Ask-to-Buy approval that lands minutes later, a
 * deferred payment method clearing, and a purchase made on another device.
 *
 * Play does not push renewals or refunds to the app at all — those reach the
 * server as RTDNs — so the client never polls for them. What it does do is
 * reconcile: `queryPurchasesAsync` on start, on resume and on sign-in, which is
 * the Play analogue of StoreKit's `Transaction.currentEntitlements`.
 *
 * ── Distribution gate ───────────────────────────────────────────────────────
 * The rail is live only in a Play build ([BuildConfig.IS_PLAY_BUILD]). Play
 * Billing cannot work in a sideloaded or F-Droid build — the Play Store will
 * not sell to an app it did not install — so binding the billing service there
 * would achieve nothing except a guaranteed BILLING_UNAVAILABLE and a purchase
 * button that dead-ends. Those builds keep the existing web-checkout path,
 * unchanged. Build one with `-PplayBuild=true`.
 *
 * SUBSCRIPTIONS ONLY. There is no `ProductType.INAPP` query and no
 * `consumeAsync` anywhere in this class: vouchers and one-time purchases stay
 * on the web by owner decision.
 */
@Singleton
class PlayBillingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BirdoRepository,
    private val tokenManager: TokenManager,
) : PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(PlayBillingUiState())
    val state: StateFlow<PlayBillingUiState> = _state.asStateFlow()

    /**
     * Emitted after the server ACCEPTS an entitlement, so the plan snapshot
     * (`/vpn/stats`) is re-fetched and every plan gate in the app opens. A flow
     * rather than a callback so nothing here has to reach into a ViewModel.
     */
    private val _entitlementChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val entitlementChanged: SharedFlow<Unit> = _entitlementChanged.asSharedFlow()

    private val refusals = PurchaseRefusalMemory()

    private val ingestor = PurchaseIngestor(
        link = { token -> repository.linkGooglePurchase(token) },
        acknowledge = { token -> acknowledge(token) },
        isSignedIn = { tokenManager.isLoggedIn() },
        refusals = refusals,
    )

    /** Serialises connect + query + reconcile so a resume storm cannot pile up. */
    private val gate = Mutex()

    private var hasAttemptedLoad = false
    private var lastFailure: StorefrontFailure? = null
    private var isLoading = false

    /** Purchases from the most recent sweep, used to build a cross-grade flow. */
    @Volatile
    private var knownPurchases: List<Purchase> = emptyList()

    private val billingClient: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(this)
            // Required since PBL 8: build() throws without it. Birdo sells no
            // one-time products, but a subscription bought with a slow payment
            // method still arrives PENDING and must be delivered to the listener
            // rather than dropped.
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build(),
            )
            // Let the library re-establish the service binding itself. Without
            // it, one SERVICE_DISCONNECTED (a Play Store self-update, which
            // happens silently and often) leaves the rail dead until the next
            // cold start.
            .enableAutoServiceReconnection()
            .build()
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Connect, load the catalogue and reconcile. Idempotent and safe to call
     * from `Application.onCreate`, from `onResume` and from the subscription
     * screen — the mutex collapses concurrent calls and the storefront is
     * recomputed from scratch each time.
     */
    fun start() {
        if (!isRailEnabled) {
            markUnavailable(StorefrontFailure.PLAY_STORE_UNAVAILABLE)
            return
        }
        scope.launch { refreshInternal(alsoReconcile = true) }
    }

    /** Products only, plus a reconcile. Called when the purchase screen opens. */
    fun refresh() = start()

    /**
     * A session has appeared (sign-in, or an anonymous account being minted).
     * This is the moment a purchase made while signed out can finally be bound.
     */
    fun onSignedIn() {
        if (!isRailEnabled) return
        scope.launch { reconcile(announce = false) }
    }

    private val isRailEnabled: Boolean get() = BuildConfig.IS_PLAY_BUILD

    // ── Products ────────────────────────────────────────────────────────────

    private suspend fun refreshInternal(alsoReconcile: Boolean) = gate.withLock {
        isLoading = true
        recomputeStorefront()

        // Race everything against one hard deadline. A wedged Play Store or a
        // captive portal can hang the connection indefinitely, and a purchase
        // screen that spins forever is indistinguishable from a broken app.
        //
        // BOXED IN A LIST ON PURPOSE. `withTimeoutOrNull` returns null when the
        // deadline expires — but null is also exactly what SUCCESS looks like
        // here, because these helpers return a nullable failure. Reading the
        // bare result would report QUERY_FAILED on every successful load; that
        // happened to stay invisible only because a successful load with
        // products then discards the failure below, which is the kind of
        // accident that survives until the next edit. An empty list means "no
        // failure"; a null list means "the deadline won".
        val boxed: List<StorefrontFailure>? =
            withTimeoutOrNull(StorefrontState.LOAD_DEADLINE_MS) {
                val connectFailure = connect()
                listOfNotNull(connectFailure ?: loadProducts())
            }
        val failure =
            if (boxed == null) StorefrontFailure.QUERY_FAILED else boxed.firstOrNull()

        isLoading = false
        hasAttemptedLoad = true
        // A failure is only worth reporting when it left nothing to sell: a
        // refresh that failed after a previous one succeeded must not blank a
        // good catalogue.
        lastFailure = failure?.takeIf { _state.value.offers.isEmpty() }
        recomputeStorefront()

        if (alsoReconcile) reconcile(announce = false)
    }

    /** @return null on success, otherwise why the storefront is unavailable. */
    private suspend fun connect(): StorefrontFailure? {
        if (billingClient.isReady) return null
        val result = suspendCancellableCoroutine { cont ->
            billingClient.startConnection(object : BillingClientStateListener {
                private var resumed = false
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (resumed) return
                    resumed = true
                    cont.resume(billingResult)
                }

                override fun onBillingServiceDisconnected() {
                    // Only meaningful before setup finished; auto-reconnection
                    // handles the rest. Resuming once keeps the coroutine from
                    // hanging until the deadline on a hard failure.
                    if (resumed) return
                    resumed = true
                    cont.resume(
                        BillingResult.newBuilder()
                            .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
                            .build(),
                    )
                }
            })
        }
        return when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val subs = billingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
                if (subs.responseCode == BillingClient.BillingResponseCode.OK) null
                else StorefrontFailure.SUBSCRIPTIONS_UNSUPPORTED
            }
            // The Play Store is missing, disabled, out of date, or there is no
            // Google account on the device. A user-fixable state, and one the
            // copy names explicitly rather than blaming the network.
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            -> StorefrontFailure.PLAY_STORE_UNAVAILABLE

            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
                StorefrontFailure.SUBSCRIPTIONS_UNSUPPORTED

            else -> {
                log("billing connect failed: ${result.responseCode} ${result.debugMessage}")
                StorefrontFailure.QUERY_FAILED
            }
        }
    }

    /** @return null on success, otherwise why nothing is purchasable. */
    private suspend fun loadProducts(): StorefrontFailure? {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                BirdoPlayProduct.allProductIds.map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()

        val (result, details) = suspendCancellableCoroutine { cont ->
            billingClient.queryProductDetailsAsync(params) { billingResult, queryResult ->
                cont.resume(billingResult to queryResult.productDetailsList)
            }
        }

        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            log("queryProductDetails failed: ${result.responseCode} ${result.debugMessage}")
            _state.update { it.copy(offers = emptyList()) }
            return StorefrontFailure.QUERY_FAILED
        }

        val offers = buildOffers(details)
        _state.update { it.copy(offers = offers) }
        // An OK response with nothing purchasable is NOT an error: it is the
        // expected answer until the Play Console products are published. It gets
        // its own honest sentence rather than a connection excuse.
        return if (offers.isEmpty()) StorefrontFailure.NO_PRODUCTS else null
    }

    /**
     * Turn Play's product/offer graph into flat, purchasable cards.
     *
     * Only offers whose `basePlanId` matches a Birdo period survive, so a
     * product that resolved but has no `monthly`/`yearly` base plan yields NO
     * card — a button that could not launch a flow is worse than no button.
     *
     * Where a base plan has several offers, the plain base plan (`offerId ==
     * null`) is preferred over promotional ones. A promo offer the account is
     * not eligible for fails the flow at launch, and silently pushing everyone
     * down a free-trial offer is not a pricing decision this code should make.
     */
    private fun buildOffers(details: List<ProductDetails>): List<PurchasableOffer> {
        val byId = details.associateBy { it.productId }
        // Iterate the catalogue, not Play's answer, so card order is stable.
        return BirdoPlayProduct.entries.flatMap { product ->
            val detail = byId[product.productId] ?: return@flatMap emptyList()
            BirdoBillingPeriod.entries.mapNotNull { period ->
                val candidates = detail.subscriptionOfferDetails.orEmpty()
                    .filter { it.basePlanId == period.basePlanId }
                val offer = candidates.firstOrNull { it.offerId == null }
                    ?: candidates.firstOrNull()
                    ?: return@mapNotNull null
                // The recurring price is the LAST pricing phase; earlier phases
                // are trials and introductory rates.
                val price = offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice
                    ?: return@mapNotNull null
                PurchasableOffer(
                    product = product,
                    period = period,
                    formattedPrice = price,
                    productDetails = detail,
                    offerToken = offer.offerToken,
                )
            }
        }
    }

    private fun recomputeStorefront() {
        _state.update {
            it.copy(
                storefront = StorefrontState.decide(
                    isLoading = isLoading,
                    hasAttempted = hasAttemptedLoad,
                    purchasableCount = it.offers.size,
                    failure = lastFailure,
                ),
            )
        }
    }

    private fun markUnavailable(failure: StorefrontFailure) {
        hasAttemptedLoad = true
        isLoading = false
        lastFailure = failure
        _state.update { it.copy(offers = emptyList()) }
        recomputeStorefront()
    }

    // ── Purchase ────────────────────────────────────────────────────────────

    /**
     * Buy [offer], bound to the signed-in Birdo account.
     *
     * Order matters and is the server's contract: mint the obfuscatedAccountId
     * FIRST, so the purchase itself carries the binding. A purchase launched
     * without one names no account, and an anonymous Birdo account has no email
     * for the server to fall back on — the entitlement would sit unlinked until
     * the user found Restore Purchases.
     */
    fun purchase(activity: Activity, offer: PurchasableOffer, onSignInRequired: () -> Unit) {
        if (_state.value.purchasingProductId != null) return
        if (!tokenManager.isLoggedIn()) {
            // Not an error state — an honest prerequisite. The purchase can be
            // retried straight after signing in.
            onSignInRequired()
            return
        }
        _state.update { it.copy(purchasingProductId = offer.product.productId, notice = null) }
        scope.launch { launchFlow(activity, offer) }
    }

    private suspend fun launchFlow(activity: Activity, offer: PurchasableOffer) {
        val intent = repository.mintGooglePurchaseIntent()
        if (intent !is ApiResult.Success) {
            // Nothing has been charged: the Play sheet was never shown, and the
            // copy has to say so rather than leaving the user wondering.
            val reason = (intent as? ApiResult.Error)?.let {
                if (it.code == 429) {
                    "Too many attempts in a row. Wait a minute and try again"
                } else {
                    "Birdo could not start the purchase. Check your connection and try again"
                }
            } ?: "Birdo could not start the purchase"
            _state.update {
                it.copy(
                    purchasingProductId = null,
                    notice = StoreNotice.error("$reason — nothing was charged."),
                )
            }
            return
        }

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(offer.productDetails)
            .setOfferToken(offer.offerToken)
            .apply {
                // CROSS-GRADE. If this Google account already owns a Birdo
                // subscription, launching a plain flow answers
                // ITEM_ALREADY_OWNED and the user is stuck on the plan they
                // have. Naming the old product turns it into an upgrade with
                // prorated credit instead.
                //
                // The PBL 9 shape, keyed on the old PRODUCT id rather than the
                // old purchase token: BillingFlowParams.SubscriptionUpdateParams
                // .setSubscriptionReplacementMode is deprecated. The server
                // copes with the resulting token change either way — the new
                // purchase carries `linkedPurchaseToken` and canonicalToken()
                // keys the row on the original.
                oldSubscriptionProductId(offer)?.let { oldProductId ->
                    setSubscriptionProductReplacementParams(
                        BillingFlowParams.ProductDetailsParams
                            .SubscriptionProductReplacementParams.newBuilder()
                            .setOldProductId(oldProductId)
                            .setReplacementMode(
                                BillingFlowParams.SubscriptionUpdateParams
                                    .ReplacementMode.WITH_TIME_PRORATION,
                            )
                            .build(),
                    )
                }
            }
            .build()

        val builder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            // The binding. Play echoes this back to the server through the
            // Developer API, which matches it against the StorePurchaseIntent
            // row it minted. Play truncates past 64 characters; a UUID is 36.
            .setObfuscatedAccountId(intent.data.obfuscatedAccountId)

        val result = billingClient.launchBillingFlow(activity, builder.build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            handleFlowLaunchFailure(result)
        }
        // On OK the outcome arrives at onPurchasesUpdated, which clears the
        // spinner. Clearing it here would hide the sheet's own progress.
    }

    /**
     * The product id of an existing Birdo subscription on this Google account,
     * or null when there is nothing to replace.
     *
     * Returns null when the only thing owned is the exact product being bought:
     * Play rejects a replacement of a product by itself, and the honest handling
     * of that case is the ITEM_ALREADY_OWNED reconcile path, not a doomed flow.
     * A monthly-to-yearly move inside ONE subscription id is a base-plan change
     * and needs no replacement params at all.
     */
    private fun oldSubscriptionProductId(target: PurchasableOffer): String? =
        knownPurchases
            .asSequence()
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            .flatMap { it.products.asSequence() }
            .filter { BirdoPlayProduct.fromProductId(it) != null }
            .firstOrNull { it != target.product.productId }

    private fun handleFlowLaunchFailure(result: BillingResult) {
        val notice = when (result.responseCode) {
            BillingClient.BillingResponseCode.USER_CANCELED -> null
            // Already owned, but not linked here — reconcile rather than
            // apologise. This is the normal reinstall / second-device path.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                reconcileAfterAlreadyOwned()
                null
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            -> StoreNotice.error(StorefrontFailure.PLAY_STORE_UNAVAILABLE.message)

            // SERVICE_TIMEOUT is deprecated in PBL 9 and folded into
            // SERVICE_UNAVAILABLE above, so it is deliberately not listed.
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            -> StoreNotice.error(StorefrontFailure.QUERY_FAILED.message)

            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
                StoreNotice.error("That subscription is not available on Google Play right now.")

            else -> {
                log("launchBillingFlow failed: ${result.responseCode} ${result.debugMessage}")
                StoreNotice.error(
                    "The purchase did not start. If you were charged, tap Restore Purchases.",
                )
            }
        }
        _state.update { it.copy(purchasingProductId = null, notice = notice ?: it.notice) }
    }

    /**
     * Play says the account already owns this, so bind what it owns instead of
     * apologising. The normal reinstall / second-device path.
     *
     * The `NothingToRestore` branch is the one that matters: Play can answer
     * ITEM_ALREADY_OWNED while `queryPurchasesAsync` returns nothing we
     * recognise (a stale Play cache, or a purchase of a product id we no longer
     * sell). Without this, the user taps Subscribe, the spinner stops, and
     * absolutely nothing happens or is said — a silent dead end, which is the
     * same class of defect as a CTA that leads nowhere.
     */
    private fun reconcileAfterAlreadyOwned() {
        scope.launch {
            if (reconcile(announce = true) is StoreRestoreOutcome.NothingToRestore) {
                _state.update { it.copy(notice = StoreNotice.info(ALREADY_OWNED_UNSEEN)) }
            }
        }
    }

    /** The app-scoped listener. Registered once, on the one long-lived client. */
    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases.orEmpty()
                scope.launch {
                    var accepted = false
                    list.forEach { if (ingestAndReport(it, announce = true)) accepted = true }
                    _state.update { it.copy(purchasingProductId = null) }
                    if (accepted) _entitlementChanged.tryEmit(Unit)
                }
            }
            // The user said no. Saying anything at all here is noise.
            BillingClient.BillingResponseCode.USER_CANCELED ->
                _state.update { it.copy(purchasingProductId = null) }

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _state.update { it.copy(purchasingProductId = null) }
                reconcileAfterAlreadyOwned()
            }

            else -> handleFlowLaunchFailure(result)
        }
    }

    // ── Reconcile / restore ─────────────────────────────────────────────────

    /**
     * Present everything Play still considers current. The Play analogue of
     * StoreKit's `currentEntitlements` sweep, and the only way a purchase that
     * completed while the app was dead ever reaches the server from here.
     */
    private suspend fun reconcile(announce: Boolean): StoreRestoreOutcome {
        if (!isRailEnabled) return StoreRestoreOutcome.NothingToRestore
        if (connect() != null) {
            return StoreRestoreOutcome.Failed(StorefrontFailure.PLAY_STORE_UNAVAILABLE.message)
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val (result, purchases) = suspendCancellableCoroutine { cont ->
            billingClient.queryPurchasesAsync(params) { billingResult, list ->
                cont.resume(billingResult to list)
            }
        }
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            log("queryPurchases failed: ${result.responseCode} ${result.debugMessage}")
            return StoreRestoreOutcome.Failed(StorefrontFailure.QUERY_FAILED.message)
        }

        knownPurchases = purchases
        // Only purchases of a product we recognise. Play can return a purchase
        // from an id we no longer sell; presenting it just earns a 409.
        val ours = purchases.filter { p -> p.products.any { BirdoPlayProduct.fromProductId(it) != null } }
        if (ours.isEmpty()) return StoreRestoreOutcome.NothingToRestore

        var linked = 0
        var firstRefusal: String? = null
        ours.forEach { purchase ->
            if (ingestAndReport(purchase, announce = announce)) {
                linked++
            } else if (firstRefusal == null) {
                firstRefusal = lastRefusalText
            }
        }
        if (linked > 0) {
            _entitlementChanged.tryEmit(Unit)
            return StoreRestoreOutcome.Restored(linked)
        }
        return StoreRestoreOutcome.Refused(
            firstRefusal ?: StoreLinkRefusal.TRANSIENT.fallbackMessage,
        )
    }

    /**
     * Restore Purchases. Must answer in every case — including "there was
     * nothing to restore", which is the common one. A control that silently
     * does nothing reads as broken.
     */
    suspend fun restorePurchases(): StoreRestoreOutcome {
        if (_state.value.isRestoring) {
            return StoreRestoreOutcome.Failed("A restore is already running.")
        }
        if (!tokenManager.isLoggedIn()) {
            return StoreRestoreOutcome.Failed(StoreLinkRefusal.NEEDS_SIGN_IN.fallbackMessage)
        }
        _state.update { it.copy(isRestoring = true, notice = null) }
        // The user has explicitly asked us to try again, and they may have just
        // signed in to the right account. Forget every suppression.
        ingestor.forgetRefusals()
        val outcome = try {
            reconcile(announce = false)
        } finally {
            _state.update { it.copy(isRestoring = false) }
        }
        _state.update {
            it.copy(
                notice = if (outcome.isSuccess) StoreNotice.success(outcome.message)
                else StoreNotice.info(outcome.message),
            )
        }
        return outcome
    }

    // ── The one path every purchase takes ───────────────────────────────────

    /** Text of the most recent refusal, so a sweep can report the first one. */
    @Volatile
    private var lastRefusalText: String? = null

    /** @return true when the server accepted the entitlement. */
    private suspend fun ingestAndReport(purchase: Purchase, announce: Boolean): Boolean {
        val outcome = ingestor.ingest(purchase.toIngestable())
        when (outcome) {
            is IngestOutcome.Accepted -> {
                lastRefusalText = null
                if (!outcome.acknowledged) {
                    // Not customer-facing: the server acknowledges from /link
                    // and from the RTDN sink, so this is a third chance that
                    // missed. Logged because three days later it is a refund.
                    log("acknowledge failed after server accept — server-side ack is the backstop")
                }
                _state.update { st ->
                    st.copy(
                        notice = if (announce) {
                            StoreNotice.success(PurchaseIngestor.purchasedMessage(outcome.plan))
                        } else {
                            st.notice
                        },
                        duplicateBilling = outcome.duplicateBilling ?: st.duplicateBilling,
                    )
                }
                return true
            }

            is IngestOutcome.Pending -> {
                lastRefusalText = outcome.message
                _state.update { it.copy(notice = StoreNotice.info(outcome.message)) }
                return false
            }

            is IngestOutcome.Rejected -> {
                lastRefusalText = outcome.message
                log("link refused (${outcome.refusal}) for a purchase of ${purchase.products}")
                _state.update {
                    it.copy(
                        notice = if (outcome.refusal.isAlarming) StoreNotice.error(outcome.message)
                        else StoreNotice.info(outcome.message),
                    )
                }
                return false
            }

            IngestOutcome.Skipped -> return false
        }
    }

    /**
     * Acknowledge. Called from exactly one place — [PurchaseIngestor], after the
     * server accepted — and nowhere else. Grep for `acknowledgePurchase` before
     * adding a second call site.
     */
    private suspend fun acknowledge(purchaseToken: String): Boolean {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        val result = suspendCancellableCoroutine { cont ->
            billingClient.acknowledgePurchase(params) { cont.resume(it) }
        }
        // ITEM_ALREADY_OWNED here means the server's own acknowledgement won the
        // race, which is a success, not a failure.
        return result.responseCode == BillingClient.BillingResponseCode.OK ||
            result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
    }

    // ── UI plumbing ─────────────────────────────────────────────────────────

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun dismissDuplicateBilling() = _state.update { it.copy(duplicateBilling = null) }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "PlayBilling"

        const val ALREADY_OWNED_UNSEEN =
            "Google Play says you already have this subscription, but Birdo cannot see it on " +
                "this device yet. Wait a moment and tap Restore Purchases; if it still does not " +
                "appear, contact support and we will sort it out."
    }
}

/** Map Play's `Purchase` onto the four facts the pure pipeline uses. */
private fun Purchase.toIngestable() = IngestablePurchase(
    purchaseToken = purchaseToken,
    state = when (purchaseState) {
        Purchase.PurchaseState.PURCHASED -> IngestablePurchase.State.PURCHASED
        Purchase.PurchaseState.PENDING -> IngestablePurchase.State.PENDING
        else -> IngestablePurchase.State.UNSPECIFIED
    },
    isAcknowledged = isAcknowledged,
    productIds = products,
)
