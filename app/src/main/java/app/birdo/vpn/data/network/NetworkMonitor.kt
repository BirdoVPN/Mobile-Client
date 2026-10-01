package app.birdo.vpn.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the device's PHYSICAL networks can do right now. A VPN is never one of
 * them — ours included.
 *
 * WHY NOT_VPN, AND NOT THE DEFAULT NETWORK. This used to follow
 * `registerDefaultNetworkCallback`. Since D-6 the app rides its own tunnel, so
 * its default network IS the VPN while one is up; and even before, the
 * kill-switch's block-all interface counted as a network with INTERNET. Live on
 * the emulator (2026-09-30, airplane mode with the block up) the supervisor
 * therefore never saw the device go offline: it kept spending reconnect
 * attempts (4, 5, 6) instead of waiting, and the notification never said
 * "Waiting for a network connection…". Only networks with NOT_VPN and
 * INTERNET are watched now, so a VPN can neither keep this "online" nor take
 * it "offline".
 *
 * CAPTIVE PORTALS (A1-026). A hotel or airport Wi-Fi has INTERNET before its
 * sign-in page is passed, so it used to read as online: every connect failed
 * with a TLS or pinning error and burned the retry budget, and passing the
 * portal (which adds VALIDATED) changed nothing this class emitted, so nothing
 * retried. [status] tells the captive state apart; the supervisor waits on it
 * like on offline and re-dials on the edge to [Connectivity.ONLINE].
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    enum class Connectivity {
        /** No physical network with INTERNET. */
        OFFLINE,

        /** Every physical network is held behind a sign-in page. */
        CAPTIVE_PORTAL,

        /** A physical network that is not behind a captive portal (validated or still validating). */
        ONLINE,
    }

    companion object {
        /**
         * The request for the networks this app may be carried over: INTERNET,
         * and NOT_VPN spelled out. (NetworkRequest.Builder starts with NOT_VPN
         * in its defaults; it is written here so the rule is visible and pinned
         * by a test rather than inherited from a default.)
         */
        fun underlyingNetworkRequest(): NetworkRequest {
            val builder = NetworkRequest.Builder()
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            return builder.build()
        }

        /**
         * The device's connectivity from the capabilities of each physical
         * network it has. A null entry is a network the platform announced
         * before its capabilities: the request already guaranteed INTERNET, so
         * it counts as usable until it says otherwise. A VPN that reaches this
         * anyway (it cannot, through the request above) is ignored.
         */
        fun classify(networks: Collection<NetworkCapabilities?>): Connectivity {
            val physical = networks.filter { caps ->
                caps == null || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
            if (physical.isEmpty()) return Connectivity.OFFLINE
            val usable = physical.any { caps ->
                caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            }
            return if (usable) Connectivity.ONLINE else Connectivity.CAPTIVE_PORTAL
        }
    }

    private val connectivityManager =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
            ?: error("ConnectivityManager unavailable")

    val status: Flow<Connectivity> = callbackFlow {
        val networks = ConcurrentHashMap<Network, Capabilities>()
        fun publish() = trySend(classify(networks.values.map { it.caps }))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networks.putIfAbsent(network, Capabilities(null))
                publish()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                networks[network] = Capabilities(capabilities)
                publish()
            }

            override fun onLost(network: Network) {
                networks.remove(network)
                publish()
            }
        }

        // The state at subscription time. The callback reports every network
        // that already matches, but only asynchronously, and reports nothing at
        // all when there is none — so without this a device that starts
        // offline would never say so. allNetworks is the one snapshot that
        // includes networks other than the (VPN) default.
        @Suppress("DEPRECATION")
        val existing = connectivityManager.allNetworks
        for (network in existing) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                networks[network] = Capabilities(caps)
            }
        }
        publish()

        connectivityManager.registerNetworkCallback(underlyingNetworkRequest(), callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** True unless there is no physical network at all (a captive portal is a network). */
    val isOnline: Flow<Boolean> = status.map { it != Connectivity.OFFLINE }.distinctUntilChanged()

    /** A map value that may be null (ConcurrentHashMap refuses null values). */
    private class Capabilities(val caps: NetworkCapabilities?)
}
