package app.birdo.vpn.data.network

import app.birdo.vpn.utils.FaultReporter
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * D-6: the app's own API traffic rides its tunnel once one is up, and goes
 * AROUND it only where it has to — before the first tunnel exists, behind the
 * kill-switch block and on the reconnect path (ApiRoutePolicy decides which).
 *
 * Going around is done by `VpnService.protect()`, which only the running
 * BirdoVpnService can call. It installs itself here while it exists; with no
 * service there is no tunnel of ours either, and an ordinary socket already
 * takes the physical network.
 */
object BypassSockets {

    @Volatile private var protector: ((Socket) -> Boolean)? = null

    fun install(protect: (Socket) -> Boolean) {
        protector = protect
    }

    /** Remove [protect] if it is still the installed one (a newer service may have replaced it). */
    fun uninstall(protect: (Socket) -> Boolean) {
        if (protector === protect) protector = null
    }

    /**
     * Mark [socket] to leave by the physical network. True when it was
     * protected, or when no service is running (nothing to go around).
     */
    fun protect(socket: Socket): Boolean {
        val protect = protector ?: return true
        // A fresh java.net.Socket has no file descriptor until something
        // touches its implementation, and protect() needs the descriptor.
        // Reading an option creates it — the same trick android.net.Network
        // .bindSocket uses for exactly this reason.
        socket.reuseAddress
        val protected = protect(socket)
        if (!protected) {
            // A bypass call that is not protected follows the app's default
            // network, which while a tunnel is up IS the tunnel — dead, on the
            // paths that use the bypass. Throttled in FaultReporter.
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "api_bypass_protect_failed",
                "VpnService.protect() refused an API socket — the call may be routed into a dead tunnel",
            )
        }
        return protected
    }
}

/**
 * A [SocketFactory] whose sockets go around any tunnel of ours. OkHttp asks
 * for unconnected sockets ([createSocket] with no arguments); the connecting
 * overloads exist for the contract and protect before they connect, too.
 */
class ProtectingSocketFactory(
    private val protect: (Socket) -> Boolean = BypassSockets::protect,
) : SocketFactory() {

    override fun createSocket(): Socket = Socket().also { protect(it) }

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
}

/**
 * A Retrofit `@Tag` that sends ONE call around the tunnel whatever the
 * session's state (REVIEW-AND2-001, -002).
 *
 * D-6 is wrong for exactly the calls whose answer the tunnel cannot carry back.
 * The server takes a WireGuard peer off its node BEFORE it answers the request
 * that removed it, and since D-6 that request rides the very peer:
 *  - an account deletion revokes every peer of the account, then replies
 *    (birdo-web gdpr.service deleteUserData): through the tunnel the reply is
 *    dropped at the node, and an erasure that worked reads as a failure, with
 *    the Play billing notice, the device-id and ML-KEM rotation and the sign-out
 *    all skipped;
 *  - an eviction, a revoke, a drain or the Free allowance's end removes the
 *    peer first, so the one heartbeat that asks why a dead tunnel died can only
 *    be answered off it.
 * Both leave from the real IP, as the re-dial that follows a dead tunnel does
 * anyway, so neither discloses more than the path it replaces.
 */
object AroundTunnel

/**
 * Retrofit's call factory: each call goes to the tunnel client or the bypass
 * client, decided when the call is created.
 *
 * Two clients rather than one switching socket factory, because OkHttp pools
 * connections: a connection opened around the tunnel would otherwise be reused
 * for "tunnel" calls long after the tunnel came up (and the reverse into a dead
 * tunnel). Each client has its own pool. On a change of path the pool that is
 * no longer used drops its idle connections, so nothing is left open around the
 * tunnel, or inside one that is going away.
 *
 * The bypass client's lookups open connections of their own, to the DoH
 * provider ([DohResolver], its bootstrap client's pool), and those are
 * [alsoAroundTunnel]: dropped with the bypass pool. Live on the emulator
 * (2026-10-01, F3) one idle `wlan0 -> 1.1.1.1:443` DoH connection stayed
 * ESTABLISHED outside the tunnel for the whole session. The flip to the
 * tunnel happens at the first call once the tunnel is up, which is the
 * heartbeat VpnManager sends the moment it is (startHeartbeat).
 */
class RoutingCallFactory(
    private val tunnel: OkHttpClient,
    private val bypass: OkHttpClient,
    private val alsoAroundTunnel: List<ConnectionPool> = emptyList(),
    private val useBypass: () -> Boolean,
) : Call.Factory {

    @Volatile private var lastBypass: Boolean? = null

    override fun newCall(request: Request): Call {
        // Tagged [AroundTunnel]: the bypass client whatever the path. It is
        // recorded as a bypass use, nothing more: the next ordinary call on
        // the tunnel path then drops what this one left open around it.
        if (request.tag(AroundTunnel::class.java) != null) {
            lastBypass = true
            return bypass.newCall(request)
        }
        val bypassNow = useBypass()
        val previous = lastBypass
        lastBypass = bypassNow
        if (previous != null && previous != bypassNow) {
            if (bypassNow) {
                tunnel.connectionPool.evictAll()
            } else {
                bypass.connectionPool.evictAll()
                alsoAroundTunnel.forEach { it.evictAll() }
            }
        }
        return (if (bypassNow) bypass else tunnel).newCall(request)
    }
}
