package app.birdo.vpn.ui.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.birdo.vpn.billing.BillingManager
import app.birdo.vpn.billing.BillingUiState
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives Google Play Billing for the SubscriptionScreen. Purchases happen via
 * Play (BillingManager); the resulting token is verified server-side
 * (BirdoRepository.verifyGooglePlayPurchase), which grants the plan and
 * acknowledges the purchase. After a successful verify we signal the rest of the
 * app to refresh subscription status.
 */
@HiltViewModel
class BillingViewModel @Inject constructor(
    private val billing: BillingManager,
    private val repository: BirdoRepository,
) : ViewModel() {

    val state: StateFlow<BillingUiState> = billing.state

    private val _subscriptionChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits after a purchase is verified so the UI can refresh subscription status. */
    val subscriptionChanged: SharedFlow<Unit> = _subscriptionChanged.asSharedFlow()

    init {
        billing.connect()
        viewModelScope.launch {
            // Every completed purchase (new, restored, or self-healed) is verified
            // server-side before it counts as entitlement.
            billing.purchases.collect { p ->
                when (val r = repository.verifyGooglePlayPurchase(p.purchaseToken, p.productId)) {
                    is ApiResult.Success -> {
                        billing.onVerified(true, "Subscription active — thank you!")
                        _subscriptionChanged.tryEmit(Unit)
                    }
                    is ApiResult.Error -> billing.onVerified(
                        false,
                        "We couldn't confirm your purchase yet (${r.message}). It will retry automatically.",
                    )
                }
            }
        }
    }

    /** Re-establish the billing connection (e.g. on screen resume). */
    fun connect() = billing.connect()

    /**
     * Launch the Play purchase flow for the given plan + period. RECON is the
     * free tier and never triggers a purchase.
     */
    fun purchase(activity: Activity, planId: String, period: String, accountId: String?) {
        if (planId.equals("RECON", ignoreCase = true)) return
        billing.launchPurchase(activity, planId, period, accountId)
    }

    fun clearMessage() = billing.clearMessage()
}
