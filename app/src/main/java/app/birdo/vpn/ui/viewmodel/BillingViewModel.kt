package app.birdo.vpn.ui.viewmodel

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.birdo.vpn.billing.PlayBillingManager
import app.birdo.vpn.billing.PlayBillingUiState
import app.birdo.vpn.billing.PurchasableOffer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin adapter between the subscription UI and the app-scoped
 * [PlayBillingManager].
 *
 * DELIBERATELY THIN. The manager is a `@Singleton` and owns all the state,
 * because the purchases listener and the entitlement reconcile must outlive any
 * screen: an Ask-to-Buy approval or a deferred payment clearing arrives minutes
 * after the purchase sheet closed, and a ViewModel scoped to the subscription
 * route would be gone. This class holds no state of its own — it forwards.
 */
@HiltViewModel
class BillingViewModel @Inject constructor(
    private val billing: PlayBillingManager,
) : ViewModel() {

    val state: StateFlow<PlayBillingUiState> = billing.state

    /** Emits after the server accepts an entitlement, so plan gates can reopen. */
    val entitlementChanged: SharedFlow<Unit> = billing.entitlementChanged

    /** Refresh the catalogue and reconcile. Safe to call on every screen open. */
    fun refresh() = billing.refresh()

    /**
     * A Birdo session has appeared. Re-present anything Play still considers
     * current: this is the moment a purchase made while signed out, on another
     * device, or on a previous install can finally be bound to an account.
     */
    fun onSignedIn() = billing.onSignedIn()

    /**
     * Start a purchase.
     *
     * [offer] is a resolved, purchasable offer — not a plan id and not a price
     * string. Requiring the offer object here is what makes it impossible to
     * launch a flow for something Play never returned, which is the whole
     * defence against a CTA that dead-ends.
     */
    fun purchase(activity: Activity, offer: PurchasableOffer, onSignInRequired: () -> Unit) =
        billing.purchase(activity, offer, onSignInRequired)

    fun restorePurchases() {
        viewModelScope.launch { billing.restorePurchases() }
    }

    fun dismissNotice() = billing.dismissNotice()

    fun dismissDuplicateBilling() = billing.dismissDuplicateBilling()
}
