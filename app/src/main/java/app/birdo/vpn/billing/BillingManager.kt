package app.birdo.vpn.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** UI-facing billing state surfaced to the SubscriptionScreen. */
data class BillingUiState(
    val ready: Boolean = false,
    val isPurchasing: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

/** A completed Play purchase whose token must be verified server-side. */
data class PurchaseToVerify(val purchaseToken: String, val productId: String?)

/**
 * Wraps the Google Play BillingClient. Subscriptions are PURCHASED via Play
 * Billing; the resulting purchaseToken is then verified + entitled by our
 * backend (`POST /payments/google-play/verify`), which also acknowledges it.
 * The client never grants its own plan.
 *
 * Self-healing: on connect we also re-emit any already-owned purchase that may
 * not have been verified yet (e.g. the verify call failed after a purchase), so
 * Google's 3-day acknowledgement window is not missed.
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "BillingManager"
        // Play subscription product ids (one per plan); each has monthly/yearly base plans.
        // Must match the backend GOOGLE_PLAY_PRODUCT_PLAN_MAP and the Play Console products.
        val SUBSCRIPTION_PRODUCT_IDS = listOf("operative", "sovereign")

        /** Map our (planId, period) to the Play (productId, basePlanId). */
        fun toPlayProduct(planId: String, period: String): Pair<String, String> {
            val productId = planId.lowercase()
            val basePlanId = if (period.equals("yearly", true)) "yearly" else "monthly"
            return productId to basePlanId
        }
    }

    private val _state = MutableStateFlow(BillingUiState())
    val state: StateFlow<BillingUiState> = _state.asStateFlow()

    private val _purchases = MutableSharedFlow<PurchaseToVerify>(extraBufferCapacity = 8)
    /** Emits a token each time a purchase needs server-side verification. */
    val purchases: SharedFlow<PurchaseToVerify> = _purchases.asSharedFlow()

    @Volatile
    private var productDetails: Map<String, ProductDetails> = emptyMap()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingResponseCode.OK -> purchases?.forEach { handlePurchase(it) }
            BillingResponseCode.USER_CANCELED ->
                _state.update { it.copy(isPurchasing = false, message = null, isError = false) }
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Already owned but maybe unverified — re-query to recover.
                _state.update { it.copy(isPurchasing = false) }
                queryOwnedPurchases()
            }
            else -> _state.update {
                it.copy(isPurchasing = false, isError = true, message = "Purchase failed: ${result.debugMessage}")
            }
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    /** Start (or resume) the billing connection, then load products + owned purchases. */
    fun connect() {
        if (billingClient.isReady) {
            queryProducts()
            queryOwnedPurchases()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) {
                    queryProducts()
                    queryOwnedPurchases()
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
                    _state.update { it.copy(ready = false) }
                }
            }

            override fun onBillingServiceDisconnected() {
                _state.update { it.copy(ready = false) }
            }
        })
    }

    private fun queryProducts() {
        val products = SUBSCRIPTION_PRODUCT_IDS.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(ProductType.SUBS)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        billingClient.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode == BillingResponseCode.OK) {
                productDetails = list.associateBy { it.productId }
                _state.update { it.copy(ready = true) }
            } else {
                Log.w(TAG, "queryProductDetails failed: ${result.debugMessage}")
            }
        }
    }

    /** Re-emit any active SUBS purchase so it can be (re)verified server-side. */
    private fun queryOwnedPurchases() {
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingResponseCode.OK) {
                purchases.forEach { handlePurchase(it) }
            }
        }
    }

    /**
     * Launch the Play purchase flow for (planId, period). `obfuscatedAccountId`
     * binds the purchase to the logged-in user for fraud prevention.
     */
    fun launchPurchase(activity: Activity, planId: String, period: String, obfuscatedAccountId: String?) {
        val (productId, basePlanId) = toPlayProduct(planId, period)
        val details = productDetails[productId]
        if (details == null) {
            _state.update { it.copy(isError = true, message = "Plan not available yet — please try again.") }
            connect()
            return
        }
        val offer = details.subscriptionOfferDetails
            ?.firstOrNull { it.basePlanId == basePlanId }
            ?: details.subscriptionOfferDetails?.firstOrNull()
        if (offer == null) {
            _state.update { it.copy(isError = true, message = "This billing period is unavailable.") }
            return
        }

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offer.offerToken)
            .build()
        val flowParamsBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
        if (!obfuscatedAccountId.isNullOrBlank()) {
            flowParamsBuilder.setObfuscatedAccountId(obfuscatedAccountId)
        }

        _state.update { it.copy(isPurchasing = true, message = null, isError = false) }
        val result = billingClient.launchBillingFlow(activity, flowParamsBuilder.build())
        if (result.responseCode != BillingResponseCode.OK) {
            _state.update { it.copy(isPurchasing = false, isError = true, message = "Could not start purchase: ${result.debugMessage}") }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        // Hand the token to the ViewModel for server-side verification (which
        // also acknowledges it). Do NOT acknowledge here — the backend does it.
        _purchases.tryEmit(PurchaseToVerify(purchase.purchaseToken, purchase.products.firstOrNull()))
    }

    /** Clear a transient billing banner message. */
    fun clearMessage() {
        _state.update { it.copy(message = null, isError = false) }
    }

    /** Reflect a server verification result in the UI state. */
    fun onVerified(success: Boolean, message: String?) {
        _state.update { it.copy(isPurchasing = false, isError = !success, message = message) }
    }
}
