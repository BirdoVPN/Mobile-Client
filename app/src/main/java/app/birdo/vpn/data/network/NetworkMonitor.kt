package app.birdo.vpn.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes network connectivity changes using ConnectivityManager.
 * Emits true when internet is available, false when offline.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val connectivityManager =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?: error("ConnectivityManager unavailable")

    val isOnline: Flow<Boolean> = callbackFlow {
        // Use the DEFAULT-network callback, not a request that matches every
        // network with internet. The latter fires onLost() for the underlying
        // Wi-Fi/cellular network the moment the VPN tunnel takes over (or when
        // any one of several networks drops), leaving the banner stuck on
        // "No Internet" even though the device is fully online. The default
        // callback instead always reflects the SINGLE network the device is
        // actually using for traffic — which carries NET_CAPABILITY_INTERNET
        // whether it's Wi-Fi, cellular, or the VPN tunnel — so it never
        // mistakes a handover for an outage.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                // Only fires when there is no longer ANY default network, i.e.
                // the device is genuinely offline.
                trySend(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                val hasInternet = capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                trySend(hasInternet)
            }
        }

        connectivityManager.registerDefaultNetworkCallback(callback)

        // Emit initial state from the current default network.
        val currentNetwork = connectivityManager.activeNetwork
        val currentCapabilities = connectivityManager.getNetworkCapabilities(currentNetwork)
        val isCurrentlyOnline = currentCapabilities?.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_INTERNET
        ) == true
        trySend(isCurrentlyOnline)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()
}
