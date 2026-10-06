package app.birdo.vpn.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.network.BypassSockets
import app.birdo.vpn.data.network.NetworkMonitor
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.utils.FaultReporter
import app.birdo.vpn.utils.RootDetector
import com.wireguard.config.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.glance.appwidget.updateAll
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Android VPN Service with WireGuard tunnel, Kill Switch, and Split Tunneling.
 *
 * Uses wg-go native library (via [WgNative] reflection bridge) to establish a
 * real WireGuard tunnel. The VPN interface is created via [VpnService.Builder],
 * then the file descriptor is handed off to wg-go for packet processing.
 *
 * Architecture:
 * - Tunnel lifecycle (start / stop / monitor) → this class
 * - Notification building & posting          → [VpnNotificationManager]
 * - wg-go JNI bridge                        → [WgNative]
 * - High-level orchestration (API + prefs)   → [VpnManager]
 *
 * Kill Switch: on unexpected disconnect, a "blocking" VPN interface routes all
 * traffic to a black hole, preventing leaks.
 *
 * Split Tunneling: uses [VpnService.Builder.addDisallowedApplication] to let
 * selected apps bypass VPN.
 *
 * The app's own traffic (D-6, A1-016): BirdoVPN is INSIDE its tunnel, except
 * while Stealth runs Xray as a child process; it is outside the kill-switch
 * block, so sign-in and /connect work behind it. wg-go's UDP sockets are
 * protect()ed once, right after wgTurnOn, and the API goes around a tunnel
 * only through the bypass client this service lends [protect] to
 * ([BypassSockets]). The rules are in TunnelRouting.kt.
 */
class BirdoVpnService : VpnService() {

    companion object {
        private const val TAG = "BirdoVPN"
        /** Max time (ms) to allow tunnel setup before forcing an error. */
        private const val CONNECT_TIMEOUT_MS = 30_000L
        /**
         * What Android does with this service when the process dies: nothing,
         * on purpose.
         *
         * Live on API 35 (2026-09-30), START_STICKY bought no restart at all
         * after "am crash" or "kill -9" (restartCount=0, no scheduled restart
         * after 75 s), and it kept the dead service's record, so its
         * foreground notification went on saying "BirdoVPN — Protected" over a
         * device whose traffic was flowing in the clear. A non-sticky service
         * is brought down with its process, and its notification with it.
         *
         * Recovery does not depend on it: every new process of this app (the
         * user opening it, the widget, the tile) resumes a session the user
         * wanted through [ACTION_RESUME_SESSION] (BirdoApp), Always-on restarts
         * the VPN at boot and unlock, and protection that must survive a crash
         * is Android's own "Block connections without VPN", which the Kill
         * Switch row now says.
         */
        private const val RESTART_POLICY = START_NOT_STICKY

        /** Reads of wg-go's socket descriptors before the protect gives up (see protectTunnelSockets). */
        private const val PROTECT_ATTEMPTS = 10
        private const val PROTECT_RETRY_MS = 50L

        /**
         * establish() attempts for the kill-switch block: the first, and ONE
         * retry after [KILL_SWITCH_RETRY_MS]. A refusal from a revoked consent
         * will not change in a quarter of a second, but a throw from the
         * platform's interface setup can, and the retry costs no exposure —
         * whatever interface was up (the live tunnel, or an older block) stays
         * up until the attempts are over (see [activateKillSwitch]).
         */
        private const val KILL_SWITCH_ARM_ATTEMPTS = 2
        private const val KILL_SWITCH_RETRY_MS = 250L

        /**
         * How long a teardown waits for the interrupted transport probe to
         * exit. The probe's only blocking work is a 500 ms poll sleep (which
         * the interrupt ends at once) and a wg-go getConfig read, so this is a
         * bound, not an expected wait.
         */
        private const val PROBE_JOIN_MS = 500L

        /**
         * POWER: the notification-refresh cadence drives a blocking wg-go
         * getConfig JNI read (readTrafficStats) on every tick, 24/7 while
         * connected — the single biggest steady-state battery cost of the
         * service. The elapsed timer is rendered NATIVELY by the notification's
         * chronometer (setUsesChronometer), so a tick is only needed to refresh
         * the byte counters. When the app UI is FOREGROUND (the user is watching
         * live stats) we tick fast; when BACKGROUND (screen off, just the
         * ongoing notification) we tick ~8x slower — the byte counter in a
         * notification does not need per-second precision. This cuts the
         * background wg-go reads / CPU wakeups ~8x with no visible change.
         */
        private const val NOTIF_UPDATE_INTERVAL_FG_MS = 1_000L
        private const val NOTIF_UPDATE_INTERVAL_BG_MS = 8_000L

        /**
         * Set by MainActivity onResume/onStop. Chooses the notification/stats
         * tick cadence above. @Volatile so the ticker (main thread) sees the
         * activity's writes immediately.
         */
        @Volatile var uiForeground: Boolean = false

        private fun notifTickIntervalMs(): Long =
            if (uiForeground) NOTIF_UPDATE_INTERVAL_FG_MS else NOTIF_UPDATE_INTERVAL_BG_MS

        const val ACTION_START = "app.birdo.vpn.START_VPN"
        const val ACTION_STOP = "app.birdo.vpn.STOP_VPN"
        const val ACTION_KILL_SWITCH_BLOCK = "app.birdo.vpn.KILL_SWITCH_BLOCK"

        /**
         * Tear down the data plane for an immediate reconnect / server switch
         * WITHOUT dropping the kill-switch blocking interface or the foreground
         * service. Unlike [ACTION_STOP] (→ stopTunnel → deactivateKillSwitch),
         * this keeps traffic fail-closed for the whole rebuild window: the
         * blocking interface stays established until the replacement tunnel's
         * establish() atomically supersedes it.
         */
        const val ACTION_SWITCH_TEARDOWN = "app.birdo.vpn.SWITCH_TEARDOWN"
        /**
         * Runtime settings push (no tunnel rebuild): updates flags the service
         * consults during the CURRENT session — today just the kill switch,
         * which is otherwise captured once from the START intent and would
         * ignore a mid-session toggle until the next connect. TUN-level
         * settings (MTU, routes, split tunnel, DNS…) go through a full
         * reapply-reconnect instead (VpnManager.requestSettingsReapply()).
         */
        const val ACTION_UPDATE_SETTINGS = "app.birdo.vpn.UPDATE_SETTINGS"

        /**
         * Release the kill-switch block and tear down whatever is left of the
         * data plane, but KEEP the current Error on screen and the service in
         * the foreground. Sent by VpnManager when the supervisor gives up
         * (budget spent), or when a user dial that never connected created a
         * block it must not keep (A1-003). Unlike [ACTION_STOP] it does not
         * publish Disconnected, which would erase the explanation.
         */
        const val ACTION_RELEASE_BLOCK = "app.birdo.vpn.RELEASE_BLOCK"

        /**
         * Sent by PackageReplacedReceiver after an app update killed the
         * process, when the user's session should be up (A1-015). Handled as
         * a system start: see [SystemStartKind].
         */
        const val ACTION_HEADLESS_CONNECT = "app.birdo.vpn.HEADLESS_CONNECT"

        /**
         * The notification's Disconnect and "Stop blocking" actions. Routed
         * to VpnManager.disconnect() — the one path that releases the peer,
         * cancels recovery and supersedes an in-flight dial. They used to send
         * ACTION_STOP here directly, so a dial in flight could bring the
         * tunnel back seconds after the user stopped it (A1-009).
         */
        const val ACTION_USER_DISCONNECT = "app.birdo.vpn.USER_DISCONNECT"

        /** The notification's Reconnect action: VpnManager.connectPreferred(). */
        const val ACTION_USER_RECONNECT = "app.birdo.vpn.USER_RECONNECT"

        /**
         * A1-034: rebuild the live session in place with the config set by
         * [setRebuildConfig]: establish() the new interface on THIS running
         * service and swap wg-go to it, instead of tearing down to the block.
         * The outcome goes back through [completeLiveRebuild].
         */
        const val ACTION_LIVE_REBUILD = "app.birdo.vpn.LIVE_REBUILD"

        /** LIVE_REBUILD only: the id VpnManager waits on. */
        const val EXTRA_REBUILD_ID = "rebuild_id"

        /**
         * Sent by BirdoApp when its process starts and finds a session the
         * user wanted with no service running: the previous process died and
         * Android did not restart the service. A system start
         * ([SystemStartKind.PROCESS_RESTART]).
         */
        const val ACTION_RESUME_SESSION = "app.birdo.vpn.RESUME_SESSION"

        const val EXTRA_KILL_SWITCH = "kill_switch"
        const val EXTRA_SPLIT_TUNNEL_ENABLED = "split_tunnel_enabled"
        const val EXTRA_SPLIT_TUNNEL_APPS = "split_tunnel_apps"
        /** STOP only: the user asked for this teardown (no "Not connected" notice follows it). */
        const val EXTRA_USER_INITIATED = "user_initiated"
        /** STOP only: end in this Error instead of Disconnected, and post it as an alert. */
        const val EXTRA_STOP_REASON = "stop_reason"
        /** STOP only: the [FailureKind] name that goes with [EXTRA_STOP_REASON]. */
        const val EXTRA_STOP_KIND = "stop_kind"
        /**
         * SWITCH_TEARDOWN only: force the fail-closed blocking interface up for
         * this teardown even when the kill switch is OFF. Used by the settings
         * reapply blip so a deliberate, app-initiated ~2s rebuild never leaks
         * cleartext, regardless of the user's kill-switch preference (which
         * governs UNEXPECTED drops, not this momentary reconnect).
         */
        const val EXTRA_FORCE_BLOCK = "force_block"

        @Volatile var currentState: VpnState = VpnState.Disconnected; private set

        // StateFlow-backed fields replace @Volatile for proper concurrency.
        // Public getters retain the same API for backward compatibility.
        private val _connectedServerFlow = MutableStateFlow<String?>(null)
        val connectedServer: String? get() = _connectedServerFlow.value

        private val _connectedSinceFlow = MutableStateFlow(0L)
        val connectedSince: Long get() = _connectedSinceFlow.value

        private val _killSwitchActiveFlow = MutableStateFlow(false)
        val killSwitchActive: Boolean get() = _killSwitchActiveFlow.value
        val killSwitchActiveFlow: StateFlow<Boolean> = _killSwitchActiveFlow.asStateFlow()

        private val _publicIpFlow = MutableStateFlow<String?>(null)
        val publicIp: String? get() = _publicIpFlow.value

        private val _rxBytesFlow = MutableStateFlow(0L)
        val rxBytes: Long get() = _rxBytesFlow.value

        private val _txBytesFlow = MutableStateFlow(0L)
        val txBytes: Long get() = _txBytesFlow.value

        private val _stealthActiveFlow = MutableStateFlow(false)
        /** Whether the current connection is using Xray Reality stealth tunnel */
        val stealthActive: Boolean get() = _stealthActiveFlow.value

        /**
         * A1-025: Android's Private DNS is in STRICT mode (a hostname is set)
         * on the network under the tunnel. Its DNS-over-TLS then goes to that
         * provider — through the tunnel — instead of to the tunnel's resolver,
         * so BirdoShield's filtering and Custom DNS servers do not apply.
         */
        private val _privateDnsStrictFlow = MutableStateFlow(false)
        val privateDnsStrictFlow: StateFlow<Boolean> = _privateDnsStrictFlow.asStateFlow()

        private val _quantumActiveFlow = MutableStateFlow(false)
        /** Whether the current connection uses a BirdoPQ (ML-KEM-1024) PSK. */
        val quantumActive: Boolean get() = _quantumActiveFlow.value

        /**
         * ADAPTIVE TRANSPORT: emits when a freshly-established tunnel failed to
         * complete a WireGuard handshake inside [TransportProbe.WINDOW_MS] — i.e.
         * the interface is up but the network is eating our packets.
         *
         * [VpnManager] collects this and automatically retries the connection over
         * the stealth transport. It is a SharedFlow, not a StateFlow, because it
         * reports an EVENT ("this attempt was blocked") rather than a state: a
         * StateFlow would replay a stale `true` to the next collector and trigger
         * a spurious fallback on an unrelated, working connection.
         *
         * replay = 0 for the same reason. extraBufferCapacity keeps the emitting
         * probe thread from blocking if the collector is momentarily busy.
         */
        private val _transportBlockedFlow =
            MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
        val transportBlockedFlow: SharedFlow<Unit> = _transportBlockedFlow.asSharedFlow()

        /**
         * A1-017: "the device is awake again, or on a different network" —
         * screen on, unlock, a new underlying network. VpnManager answers with
         * an immediate heartbeat: its periodic one counts awake time only, so
         * a phone that slept past the backend's 5-minute reap learns about it
         * the moment the user is back, not a full interval later. An EVENT,
         * so a SharedFlow with no replay, like [transportBlockedFlow].
         */
        private val _wakeFlow = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
        val wakeFlow: SharedFlow<Unit> = _wakeFlow.asSharedFlow()

        /**
         * True between onCreate and onDestroy. VpnManager reads it to send a
         * plain startService() to a running (already foreground) service
         * instead of startForegroundService(), which the Android 12+
         * background-start restriction can refuse for a re-dial made with the
         * screen off.
         */
        @Volatile var running: Boolean = false
            private set

        /**
         * `VpnService.isLockdownEnabled()` as last seen by the service: Android's
         * "Block connections without VPN". Copy that says whether traffic is
         * still blocked must account for it.
         */
        @Volatile var lockdownActive: Boolean = false
            private set

        /** Process-lifetime scope for widget refreshes, which must outlive the service instance. */
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Whether the ongoing notification's stats ticker should run; see notificationTicker. */
        internal fun shouldTickNotification(state: VpnState, screenInteractive: Boolean): Boolean =
            state is VpnState.Connected && screenInteractive

        /** The post-stop "Not connected" notice: never for a stop the user asked for, and only if they want notices. */
        internal fun shouldPostDisconnectedNotice(userInitiated: Boolean, notificationsEnabled: Boolean): Boolean =
            !userInitiated && notificationsEnabled

        // FIX-2-12: Reactive state flow replaces 1-second polling.
        // VpnManager collects this flow to receive state changes immediately.
        private val _stateFlow = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val stateFlow: StateFlow<VpnState> = _stateFlow.asStateFlow()

        /**
         * Application context, captured in [onCreate], so [updateState] can ask
         * the platform to re-bind the Quick Settings tile. Application context
         * only — process-scoped, so holding it cannot leak the service.
         */
        @Volatile private var appContext: Context? = null

        /**
         * FIX-2-12: Update VPN state atomically — sets both the volatile field
         * (for backward compat / quick reads) and the StateFlow (for reactive
         * collection in VpnManager).
         */
        private fun updateState(newState: VpnState) {
            currentState = newState
            _stateFlow.value = newState
            // Drift guard. There are ~19 places that publish a VpnState.Error
            // and there will be more; requiring each new one to remember a
            // report call is exactly the shape of bug that put us here. A
            // breadcrumb is unconditional, costs a ring-buffer slot and never
            // a network request, and rides along on whatever event
            // FaultReporter.report does raise — so an error branch nobody
            // wired up still leaves a trace in the report next to it.
            // Deliberately NOT an event: "connection timed out" and "network
            // is blocking the VPN" are routine, and burying the engine
            // failures under them would undo the point of reporting at all.
            if (newState is VpnState.Error) {
                FaultReporter.trail(FaultReporter.PATH_CONNECT, "state=Error: ${newState.message}")
            }
            requestTileRefresh()
        }

        /**
         * Ask the platform to bind [BirdoTileService] so it can re-render.
         *
         * The tile declares ACTIVE_TILE, which means the platform binds it ONLY
         * on a tap or on this request — NOT when the shade opens. Nothing ever
         * made the request, so the tile showed whatever state was current the
         * last time the user tapped it: it could read "Disconnected" over a live
         * tunnel, or active over a dead one. Since onClick acts on the LIVE
         * state, a user tapping a tile that read "Disconnected" disconnected
         * their VPN — the exact opposite of their intent.
         */
        internal fun requestTileRefresh() {
            val ctx = appContext ?: return
            try {
                TileService.requestListeningState(
                    ctx,
                    ComponentName(ctx, BirdoTileService::class.java),
                )
            } catch (e: Exception) {
                // Tile not added to the shade, or the platform refused — never
                // let a cosmetic refresh break a tunnel state transition.
                Log.w(TAG, "Quick Settings tile refresh request failed", e)
            }
        }

        @Volatile private var activeConfig: ConnectResponse? = null
        // @Volatile: written by handleUpdateSettings (live settings push) and
        // read by the drop handler.
        @Volatile private var isKillSwitchEnabled: Boolean = true
        private var isSplitTunnelingEnabled: Boolean = false
        private var splitTunnelAppList: Set<String> = emptySet()

        fun setConfig(config: ConnectResponse) { activeConfig = config }

        /**
         * A1-034: the config a live rebuild swaps in. Kept apart from
         * [activeConfig], which still describes the session that is up until
         * the swap actually happens (and stays it, if the rebuild never does).
         */
        @Volatile private var rebuildConfig: ConnectResponse? = null

        fun setRebuildConfig(config: ConnectResponse) { rebuildConfig = config }

        /** Outcomes VpnManager is waiting for, by rebuild id ([ACTION_LIVE_REBUILD]). */
        private val pendingRebuilds = ConcurrentHashMap<Long, CompletableDeferred<LiveRebuildPolicy.Event>>()

        /** Register for the outcome of rebuild [id] BEFORE sending it, so it cannot be missed. */
        internal fun expectLiveRebuild(id: Long): CompletableDeferred<LiveRebuildPolicy.Event> =
            CompletableDeferred<LiveRebuildPolicy.Event>().also { pendingRebuilds[id] = it }

        internal fun completeLiveRebuild(id: Long, event: LiveRebuildPolicy.Event) {
            pendingRebuilds.remove(id)?.complete(event)
        }

        internal fun forgetLiveRebuild(id: Long) {
            pendingRebuilds.remove(id)
        }
    }

    // ── Dependencies (lazy to avoid init-order crashes) ──────────

    private val notifManager by lazy { VpnNotificationManager(this) }
    private val appPrefs: AppPreferences by lazy { AppPreferences(this) }

    /**
     * Hilt singletons the platform-created service cannot have injected. A
     * seam (not a plain lazy) so a unit test can drive onStartCommand without
     * a Hilt application.
     */
    internal var entryPointProvider: () -> VpnManagerEntryPoint = {
        EntryPointAccessors.fromApplication(applicationContext, VpnManagerEntryPoint::class.java)
    }
    private val entryPoint: VpnManagerEntryPoint? by lazy {
        try {
            entryPointProvider()
        } catch (e: Exception) {
            // Without it the service can still hold a block and tear down,
            // but cannot reconnect headlessly or render the session state.
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "service_entry_point_unavailable",
                "BirdoVpnService could not reach VpnManager — no headless connect, no state rendering",
                e,
            )
            null
        }
    }

    // ── Tunnel state ─────────────────────────────────────────────

    // @Volatile on all three: this is tunnel-lifecycle state mutated from at
    // least four threads — the tunnel executor (startTunnel, the drop handler),
    // the main thread (onStartCommand's STOP / SWITCH_TEARDOWN /
    // KILL_SWITCH_BLOCK dispatch and onDestroy), the TunnelMonitor thread (via
    // onUnexpectedExit) — and read from four more: statsExecutor,
    // birdo-socket-protect, birdo-transport-probe and the ConnectivityManager
    // callback. Without it the JIT is free to hoist these reads out of a loop,
    // so the socket-protect daemon's `tunnelHandle != handle` exit condition and
    // both isAlive predicates could run on a stale value: protect() applied to a
    // recycled descriptor (excluding an unrelated app's socket from the tunnel),
    // a leaked fd, or a monitor still watching a dead handle. The neighbouring
    // isKillSwitchEnabled is already @Volatile for exactly this reason.
    //
    // Visibility only; ATOMICITY comes from [serial]. Every lifecycle
    // transition — start, stop, switch teardown, kill-switch block, release,
    // settings push, watchdog, drop, probe verdict, revoke — runs on
    // tunnelExecutor, one at a time, in arrival order (A1-012). The old
    // objection ("a user's Disconnect queued behind a 30 s PQ establish") no
    // longer holds: BirdoPQ is a local ML-KEM decapsulation now, and a STOP
    // bumps [transitionGen] on arrival, so an in-flight setup abandons itself
    // at its next checkpoint instead of making the Disconnect wait for it.

    /** VPN interface — only held during kill switch. */
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    /** wg-go tunnel handle (>= 0 when tunnel is active). */
    @Volatile private var tunnelHandle: Int = -1
    /** Monitors the tunnel and re-protects sockets. */
    @Volatile private var tunnelMonitor: TunnelMonitor? = null

    /**
     * The running transport probe ([startTransportProbe]), so teardown can
     * stop it (P1-dk-orphan-daemon-threads). It was a fire-and-forget daemon:
     * up to [TransportProbe.WINDOW_MS] — longer for a live rebuild — of wg-go
     * reads against a handle that might already be gone, holding the service
     * and its whole object graph after onDestroy. An AtomicReference so the
     * probe can clear itself on exit without erasing a newer one.
     */
    private val transportProbe = AtomicReference<Thread?>(null)

    /**
     * Watches the PHYSICAL networks under the tunnel (NOT_VPN + INTERNET; see
     * [NetworkMonitor.underlyingNetworkRequest]). Not the default-network
     * callback any more: since D-6 the app rides its own tunnel, so its
     * default network IS the VPN, and that callback would describe the tunnel
     * to itself.
     *
     * It no longer re-protects anything (A1-037): protect() marks wg-go's
     * unconnected UDP socket once, and the socket then follows the system
     * default network on every send. Nor does it call setUnderlyingNetworks:
     * a VPN that declares none is taken to use the system default network,
     * which is exactly the network wg-go's protected socket uses.
     *
     * What it does: a new network asks VpnManager for an immediate heartbeat
     * (A1-017), and "no physical network at all" feeds the dead-tunnel check
     * ([TunnelMonitor], [underlyingMissingSince]).
     */
    private var underlyingNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /** The physical networks currently available, maintained by [underlyingNetworkCallback]. */
    private val underlyingNetworks: MutableSet<Network> = ConcurrentHashMap.newKeySet()

    /** Which of them run Private DNS in strict mode (A1-025). */
    private val strictPrivateDns = ConcurrentHashMap<Network, Boolean>()

    /**
     * elapsedRealtime when the last physical network went away, or 0 while
     * one exists. Starts "missing" at registration: the platform reports the
     * networks that already exist right after, and the monitor's start-up
     * grace covers that gap.
     */
    @Volatile private var underlyingMissingSince = 0L

    /**
     * What the API's bypass client calls to go around the tunnel. One
     * instance for this service's lifetime, so [onDestroy] removes exactly
     * the one it installed.
     */
    private val bypassProtector: (Socket) -> Boolean = { socket -> protect(socket) }

    /** Single-thread executor for tunnel operations — avoids ANR on main thread. */
    private val tunnelExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "birdo-tunnel-setup").apply { isDaemon = true }
    }

    /**
     * Dedicated executor for the 1 s traffic-stats poll. [readTrafficStats] makes
     * a blocking JNI call into wg-go ([WgNative.getConfig]) which locks the
     * device and serialises its full UAPI config — cheap normally, but doing it
     * on the main thread every second risks an ANR under load. Kept separate from
     * [tunnelExecutor] so a slow stats read never delays an urgent socket
     * re-protect during a network handover.
     */
    private val statsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "birdo-stats").apply { isDaemon = true }
    }

    /** Guards against stats reads piling up if a getConfig ever runs long. */
    private val statsReadInFlight = AtomicBoolean(false)

    /** Main-thread handler for periodic ticks and timeouts. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Bumped on the MAIN thread by every START / STOP / SWITCH_TEARDOWN /
     * KILL_SWITCH_BLOCK the moment it arrives, before it is queued. An
     * in-flight startTunnel captured the value it was started with; a mismatch
     * at one of its checkpoints means a newer transition owns the tunnel, so
     * it abandons its setup without a block and without an Error (A1-012).
     */
    private val transitionGen = AtomicLong(0)

    /** Set first thing in onDestroy: nothing may establish() on a destroyed service. */
    @Volatile private var destroyed = false

    /** Whether the screen is on; the stats ticker sleeps while it is off (A1-036). */
    @Volatile private var screenInteractive = true

    /**
     * Service-lifetime scope that renders VpnManager's state into the
     * notification. Created in onCreate, not at construction: a service built
     * by a unit test has no main dispatcher to hand it.
     */
    private var serviceScope: CoroutineScope? = null

    /** Progress text for the ongoing notification during setup ("Starting stealth tunnel…"). */
    @Volatile private var notificationDetail: String? = null

    /**
     * The alert currently posted, so an unchanged state does not re-alert.
     * Written by the render collector (main) and by stopTunnel (executor).
     */
    @Volatile private var postedAlertKey: String? = null

    /**
     * Screen on/off and unlock. Screen off stops the ongoing notification's
     * stats ticker — a wg-go JNI read and a notify() every 8 s for a
     * notification nobody can see (A1-036); screen on refreshes it once and
     * resumes. Screen on and unlock also tell VpnManager the device is awake,
     * for an immediate heartbeat (A1-017).
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    stopNotificationTicker()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = true
                    if (currentState is VpnState.Connected) {
                        updateNotification()
                        startNotificationTicker()
                    }
                    _wakeFlow.tryEmit(Unit)
                }
                Intent.ACTION_USER_PRESENT -> _wakeFlow.tryEmit(Unit)
            }
        }
    }

    /**
     * Run a lifecycle transition on [tunnelExecutor], after every transition
     * that arrived before it. Once onDestroy has shut the executor down there
     * is nothing left to transition; the rejection is expected and ignored.
     */
    private fun serial(task: () -> Unit) {
        try {
            tunnelExecutor.execute(task)
        } catch (_: RejectedExecutionException) {
            Log.i(TAG, "Transition arrived after onDestroy — ignored")
        }
    }

    /** True while [gen] is still the newest transition and the service is alive. */
    private fun isCurrent(gen: Long): Boolean = !destroyed && transitionGen.get() == gen

    // ── Periodic runnables ───────────────────────────────────────

    private val notificationTicker = object : Runnable {
        override fun run() {
            if (shouldTickNotification(currentState, screenInteractive)) {
                // Read wg-go stats off the main thread (blocking JNI getConfig),
                // then refresh the notification back on the main thread. Skip this
                // tick if the previous read is still running so reads can't pile
                // up. rx/tx land in thread-safe StateFlows that buildConnectedText
                // reads on the main thread.
                if (statsReadInFlight.compareAndSet(false, true)) {
                    statsExecutor.execute {
                        try {
                            readTrafficStats()
                        } finally {
                            statsReadInFlight.set(false)
                        }
                        mainHandler.post {
                            if (currentState is VpnState.Connected) updateNotification()
                        }
                    }
                } else {
                    // A read is still in flight; just refresh the notification with
                    // the last-known stats.
                    updateNotification()
                }
                mainHandler.postDelayed(this, notifTickIntervalMs())
            }
        }
    }

    private val connectTimeoutRunnable = Runnable {
        val gen = transitionGen.get()
        serial {
            if (!isCurrent(gen) || !currentState.isConnectingPhase) return@serial
            // Routine (a dead zone, a captive portal): Log.w plus the
            // VpnState.Error breadcrumb from updateState, not an event. Error
            // severity in this file means FaultReporter — a bare Log.e is
            // banned here by DataplaneFaultReportingTest.
            Log.w(TAG, "Connection timed out after ${CONNECT_TIMEOUT_MS}ms")
            // Fail closed on timeout, matching every startTunnel failure path.
            // The old cleanupTunnel() closed the blocking vpnInterface with NO
            // re-arm, so a >30s stall during a reconnect (e.g. PQ key exchange
            // wedged in a dead zone) dropped the kill-switch block and leaked
            // cleartext + DNS until the next reconnect re-armed it. Keep traffic
            // blocked: activateKillSwitch() establishes a fresh block that
            // supersedes any stale/held interface OR a still-live wg-go tunnel,
            // and only then tears the stalled wg-go setup down — no teardown here
            // first, or the tun fd closes and routing reverts to the physical
            // network before the block is up. Only fully release (fail open) when
            // the user has the kill switch OFF.
            //
            // BLOCK FIRST, THEN PUBLISH Error — see the ordering contract on
            // [activateKillSwitch].
            blockThenPublish(gen, VpnState.Error(SessionCopy.NO_TUNNEL, FailureKind.NEVER_ESTABLISHED)) {
                cleanupStealthAndQuantum()
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        running = true
        BypassSockets.install(bypassProtector)
        // A process that died with Stealth up left Xray's config (its VLESS
        // UUID) in the cache; nothing reads it again (A1-041).
        XrayManager.deleteStaleConfig(this)
        notifManager.createChannels()
        screenInteractive = (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive != false
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        renderManagerState()
    }

    /**
     * VpnManager is the single owner of the session state (A1-002). The
     * service used to render its own copy, which went stale the moment the
     * manager moved on without it: "Reconnecting…" forever after the retries
     * stopped, backend refusals that never reached the notification, a tile
     * reading "Disconnected" over a blocked device. Now the ongoing
     * notification, the "Action needed" alert and the widget are all drawn
     * from the manager's state, whoever changed it.
     */
    private fun renderManagerState() {
        val manager = entryPoint?.vpnManager() ?: return
        val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = renderScope
        renderScope.launch {
            combine(
                manager.state,
                killSwitchActiveFlow,
                manager.sessionExpired,
                manager.switching,
            ) { state, blocking, expired, switching -> RenderInput(state, blocking, expired, switching) }
                .collect { input ->
                    updateNotification()
                    renderAlert(input)
                    updateWidgetState(input.state is VpnState.Connected, connectedServer)
                }
        }
    }

    private data class RenderInput(
        val state: VpnState,
        val killSwitchActive: Boolean,
        val sessionExpired: Boolean,
        val switching: Boolean,
    )

    /** Post, replace or withdraw the high-importance alert for [input]. */
    private fun renderAlert(input: RenderInput) {
        val alert = VpnNotificationManager.alertFor(
            state = input.state,
            killSwitchActive = input.killSwitchActive,
            sessionExpired = input.sessionExpired,
            uiForeground = uiForeground,
        )
        if (alert == null) {
            // Only withdraw once the session is healthy again; an alert the
            // user has not seen yet must survive "Connecting…".
            if (input.state is VpnState.Connected && postedAlertKey != null) {
                notifManager.cancelAlert()
                postedAlertKey = null
            }
            return
        }
        if (alert.key == postedAlertKey) return
        notifManager.postAlert(alert)
        postedAlertKey = alert.key
    }

    /** What the notification shows: the manager's state when there is one, else the service's own. */
    private fun displayState(): VpnState = entryPoint?.vpnManager()?.state?.value ?: currentState

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android's "Block connections without VPN" can be switched off (or on)
        // at any time, and the platform sends no start when it is. Read it on
        // every command, so the give-up copy never claims Android is still
        // blocking traffic when it is not (REVIEW-AND-005).
        lockdownActive = isLockdownEnabled
        // Four starts have no app request behind them: a sticky restart (a
        // null intent), Always-on (VpnService.SERVICE_INTERFACE, sent by the
        // platform at boot, unlock and setting changes), our own
        // MY_PACKAGE_REPLACED receiver, and BirdoApp finding the session dead
        // when its process starts. Every app-initiated start sets one of the
        // explicit ACTION_* below.
        val systemStart = when (intent?.action) {
            null -> SystemStartKind.STICKY_RESTART
            VpnService.SERVICE_INTERFACE -> SystemStartKind.ALWAYS_ON
            ACTION_HEADLESS_CONNECT -> SystemStartKind.PACKAGE_REPLACED
            ACTION_RESUME_SESSION -> SystemStartKind.PROCESS_RESTART
            else -> null
        }
        if (systemStart != null) return handleSystemStart(systemStart)
        val action = intent!!.action

        // Android 12+ requires startForeground() within ~5s of EVERY
        // startForegroundService() call (regardless of action) or the app is
        // killed with ForegroundServiceDidNotStartInTimeException. The STOP and
        // unknown branches previously didn't, which intermittently crashed the
        // app on a server switch (rapid STOP→START). Satisfy it up front for
        // every start, then dispatch. The notification is drawn from the
        // current state, never a hard-coded "Connecting…": an unrecognised
        // start must not claim a connect that nothing is running.
        startForeground(VpnNotificationManager.NOTIFICATION_ID, buildCurrentNotification())

        when (action) {
            ACTION_START -> {
                val gen = transitionGen.incrementAndGet()
                serial { handleStart(intent, gen) }
            }
            ACTION_STOP -> {
                transitionGen.incrementAndGet()
                val reason = intent.getStringExtra(EXTRA_STOP_REASON)?.let { message ->
                    val kind = intent.getStringExtra(EXTRA_STOP_KIND)
                        ?.let { name -> FailureKind.entries.firstOrNull { it.name == name } }
                        ?: FailureKind.TRANSIENT
                    VpnState.Error(message, kind)
                }
                val userInitiated = intent.getBooleanExtra(EXTRA_USER_INITIATED, false)
                serial { stopTunnel(reason, userInitiated) }
            }
            ACTION_SWITCH_TEARDOWN -> {
                transitionGen.incrementAndGet()
                serial { switchTeardown(intent) }
            }
            ACTION_KILL_SWITCH_BLOCK -> {
                transitionGen.incrementAndGet()
                serial { handleKillSwitchBlock() }
            }
            ACTION_RELEASE_BLOCK -> serial { handleReleaseBlock() }
            ACTION_UPDATE_SETTINGS -> serial { handleUpdateSettings(intent) }
            ACTION_LIVE_REBUILD -> {
                val gen = transitionGen.incrementAndGet()
                serial { handleLiveRebuild(intent, gen) }
            }
            ACTION_USER_DISCONNECT -> {
                val manager = entryPoint?.vpnManager()
                if (manager != null) {
                    manager.requestDisconnect()
                } else {
                    transitionGen.incrementAndGet()
                    serial { stopTunnel(reason = null, userInitiated = true) }
                }
            }
            ACTION_USER_RECONNECT -> entryPoint?.vpnManager()?.requestConnectPreferred()
            else -> serial {
                // Nothing we know how to do; do not park in the foreground.
                if (currentState is VpnState.Disconnected && !killSwitchActive) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return RESTART_POLICY
    }

    /**
     * A start the SYSTEM made (A1-014, A1-015): decide what to do from the
     * persisted intent and the platform's own settings, arm the block first
     * when it is due, then hand VpnManager a headless connect — or, when the
     * session cannot come up without the user, say so and keep any block.
     *
     * Never fails open silently: expired credentials, missing consent or a
     * missing VPN permission end in a typed Error and an "Action needed"
     * alert, with the block held whenever the kill switch or Android's
     * lockdown asks for one. The app itself is exempt from lockdown (AOSP
     * Vpn.setVpnForcedLocked exempts the VPN package), so signing in works
     * from behind it.
     */
    private fun handleSystemStart(kind: SystemStartKind): Int {
        val manager = entryPoint?.vpnManager()
        // Two system starts can land together: Always-on and our own
        // process-start or package-replaced start, after a reboot or an
        // update. The second used to arm the block again, which superseded
        // the first one's dial mid-setup (REVIEW-AND-022). The first one owns
        // the session; the second only satisfies its startForegroundService().
        if (manager?.sessionInProgress() == true) {
            startForeground(VpnNotificationManager.NOTIFICATION_ID, buildCurrentNotification())
            return RESTART_POLICY
        }
        // The tunnel is down (the OS killed and is restarting us, or it never
        // ran). The widget pref survives process death, so without this the
        // home-screen widget keeps showing a green "Protected" for a VPN that
        // is no longer up.
        updateWidgetState(false, null)

        val killSwitchPref = try {
            appPrefs.killSwitchEnabled
        } catch (e: Exception) {
            // An unreadable preference decides "do not block" below —
            // fail-open — for a user who may have asked for fail-closed.
            // The decision stands (a fail-closed default would block a
            // user who never enabled it), but it must not be silent.
            FaultReporter.report(
                FaultReporter.PATH_KILL_SWITCH,
                "kill_switch_pref_unreadable_restart",
                "Kill-switch preference unreadable on system restart — defaulting to NOT blocking",
                e,
            )
            false
        }
        val plan = SystemStartPolicy.plan(
            kind = kind,
            sessionShouldBeUp = appPrefs.sessionShouldBeUp,
            alwaysOn = isAlwaysOn,
            lockdown = lockdownActive,
            killSwitchPref = killSwitchPref,
            signedIn = entryPoint?.tokenManager()?.isLoggedIn() == true,
            consentAccepted = appPrefs.hasAcceptedCurrentConsent,
            vpnPermissionGranted = VpnService.prepare(this) == null,
        )
        Log.i(TAG, "System start $kind: $plan")
        if (plan.idle) {
            // Every system start but a sticky restart arrives through
            // startForegroundService(); stopping without startForeground()
            // first crashes the app ("did not then call startForeground").
            startForeground(VpnNotificationManager.NOTIFICATION_ID, buildCurrentNotification())
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val shownState = when {
            // A session that was up and died says "Reconnecting…" from the
            // first frame, replacing anything a dead process left on screen.
            plan.connect && kind != SystemStartKind.ALWAYS_ON -> VpnState.Reconnecting(1)
            plan.connect -> VpnState.Connecting
            plan.actionNeeded != null ->
                VpnState.Error(SessionCopy.actionNeeded(plan.actionNeeded), plan.actionNeeded)
            else -> VpnState.KillSwitchActive
        }
        try {
            startForeground(
                VpnNotificationManager.NOTIFICATION_ID,
                notifManager.buildForegroundNotification(
                    state = shownState,
                    killSwitchActive = plan.armBlock,
                    body = (shownState as? VpnState.Error)?.message,
                ),
            )
        } catch (e: Exception) {
            // A sticky restart is not on Android's list of background
            // foreground-service start exemptions (Always-on and
            // MY_PACKAGE_REPLACED are covered: a 60 s power allowlist and a
            // documented broadcast exemption). Refused → nothing can run, so
            // at least tell the user.
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "system_start_foreground_refused",
                "startForeground refused on a system start — the VPN cannot restore itself",
                e,
            )
            notifManager.postAlert(VpnNotificationManager.stoppedUnexpectedlyAlert())
            stopSelf()
            return START_NOT_STICKY
        }

        if (plan.armBlock) {
            transitionGen.incrementAndGet()
            serial { armBlockForSystemStart() }
        }
        when {
            plan.connect -> if (manager?.connectHeadless(kind) == null) {
                // No VpnManager: nothing can dial. The block (if any) holds.
                notifManager.postAlert(VpnNotificationManager.stoppedUnexpectedlyAlert())
            }
            plan.actionNeeded != null -> {
                manager?.reportHeadlessBlocked(plan.actionNeeded)
                if (!plan.armBlock) {
                    // Nothing to hold: the alert is the whole story.
                    notifManager.postAlert(VpnNotificationManager.alertFor(shownState, false, false, false)
                        ?: VpnNotificationManager.stoppedUnexpectedlyAlert())
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return RESTART_POLICY
    }

    /**
     * RE-ARM THE KILL SWITCH on a system start. It is process-local — the
     * blocking interface died with the process. activateKillSwitch() needs no
     * config and no consent prompt — only hard-coded addresses — so it can be
     * up before the headless connect has even reached the API.
     */
    private fun armBlockForSystemStart() {
        Log.i(TAG, "System start with the kill switch or lockdown on — arming the block first")
        isKillSwitchEnabled = true
        if (activateKillSwitch() == BlockArm.FAILED) {
            // Silent failure of a security control is worse than a loud one:
            // the user believes they are fail-closed and they are not.
            // "Loud" has to mean loud to the OPERATOR too — Log.e is
            // stripped from release builds, so this reports.
            FaultReporter.report(
                FaultReporter.PATH_KILL_SWITCH,
                "kill_switch_rearm_failed_restart",
                "Kill switch could not be re-armed after a system restart — traffic is NOT blocked",
            )
            publishKillSwitchFailure(FailureKind.VPN_PERMISSION_REQUIRED)
        }
    }

    /**
     * Block for a session the server invalidated (VpnManager's reap
     * recovery): block first, the re-dial follows behind it.
     */
    private fun handleKillSwitchBlock() {
        // Latch the service-side flag too. It is otherwise captured ONCE from
        // the START intent; handleUpdateSettings RELEASES the block when this
        // reads false. The restart re-arm sets it for the same reason — a
        // guard on one of several parallel paths is how a fail-open window
        // gets reintroduced here.
        isKillSwitchEnabled = true
        if (activateKillSwitch() == BlockArm.FAILED) {
            // establish() refused (in practice: VPN consent revoked).
            // activateKillSwitch has already torn the data plane down,
            // so traffic is in the clear while currentState still reads
            // Connected and the notification still says "Blocking
            // traffic". Silent failure of a security control is worse
            // than a loud one — same handling as the restart re-arm,
            // including reporting it: this branch is unreachable in a
            // debug build (it needs a revoked consent on a live
            // session), so the release channel is the only one that
            // will ever see it.
            FaultReporter.report(
                FaultReporter.PATH_KILL_SWITCH,
                "kill_switch_rearm_failed_invalidated",
                "Kill switch could not be armed for an invalidated session — traffic is NOT blocked",
            )
            publishKillSwitchFailure(FailureKind.VPN_PERMISSION_REQUIRED)
        }
        // The tunnel is gone on BOTH branches (activateKillSwitch tears
        // wg-go down either way), but the widget's "Protected" flag
        // lives in SharedPreferences and outlives it. Unconditional, as in
        // the drop handler.
        updateWidgetState(false, null)
    }

    /**
     * [ACTION_RELEASE_BLOCK]: take the block and whatever is left of the data
     * plane down, keep the Error and the foreground. Skipped when a newer
     * session already owns the interface.
     */
    private fun handleReleaseBlock() {
        if (currentState is VpnState.Connected || currentState.isConnectingPhase) return
        deactivateKillSwitch()
        cleanupTunnel()
        cleanupStealthAndQuantum()
        if (!appPrefs.sessionShouldBeUp) {
            // Nothing is wanted any more (a user dial that never connected, a
            // terminal refusal): nothing to keep the process alive for. The
            // error stays on Home. A service left foreground here used to
            // come back after a process kill as a full block nobody asked
            // for, with no alert (REVIEW-AND-004).
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // A spent budget keeps its intent: the process stays (foreground) for
        // the cooldown's one re-dial.
        mainHandler.post { updateNotification() }
    }

    private fun handleStart(intent: Intent, gen: Long) {
        notifManager.cancelDisconnected()

        isKillSwitchEnabled = intent.getBooleanExtra(EXTRA_KILL_SWITCH, true)
        isSplitTunnelingEnabled = intent.getBooleanExtra(EXTRA_SPLIT_TUNNEL_ENABLED, false)
        splitTunnelAppList = intent.getStringArrayExtra(EXTRA_SPLIT_TUNNEL_APPS)
            ?.toSet() ?: emptySet()

        // (startForeground already called in onStartCommand for every action.)

        mainHandler.removeCallbacks(connectTimeoutRunnable)
        mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)

        try {
            startTunnel(gen)
        } catch (t: Throwable) {
            // Reported, not just logged: this catch exists precisely for
            // the errors startTunnel's `catch (e: Exception)` cannot see
            // (UnsatisfiedLinkError, OutOfMemoryError, NoSuchMethodError
            // from a native/AGP bump) — the class of failure that hits a
            // whole device family at once and never reaches a developer's
            // logcat.
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "tunnel_setup_throwable",
                "Unhandled Throwable escaped tunnel setup",
                t,
            )
            // startTunnel's own catch only covers Exception; a Throwable that
            // escapes it left the tunnel half-built and the user unprotected
            // with no block. Fail closed here too, before publishing Error.
            // activateKillSwitch() does its own ordered teardown (establish
            // the block first, then turn wg-go off) — no teardown here first.
            // Never the throwable's own text on screen (REVIEW-AND-006).
            failSetup(gen, SessionCopy.ENGINE_FAILED, FailureKind.TRANSIENT)
        }
        // No deferred kill-switch release here any more: a settings push that
        // arrived during this setup is queued behind it on the same executor,
        // so handleUpdateSettings runs next and sees the setup's outcome.
    }

    /**
     * Apply a mid-session settings push — FLAG ONLY, never touches the tunnel.
     * The kill-switch flag is otherwise captured ONCE from the START intent, so
     * a user who enables it while connected believes they're drop-protected but
     * the running session still holds the old value. This updates the flag the
     * drop handler ([TunnelMonitor.onUnexpectedExit]) consults, so a subsequent
     * drop is handled per the new preference.
     *
     * Deliberately does NOT tear anything down: an earlier version called
     * stopTunnel() when the kill switch was disabled "while blocking", but
     * killSwitchActive is also latched true throughout every reconnect/reapply
     * rebuild. Flag-only is race-free. A user who wants out of an active block
     * uses Disconnect (which is a clean, ordered teardown).
     */
    private fun handleUpdateSettings(intent: Intent) {
        isKillSwitchEnabled = intent.getBooleanExtra(EXTRA_KILL_SWITCH, isKillSwitchEnabled)
        Log.i(TAG, "Runtime settings update (flag-only): killSwitchEnabled=$isKillSwitchEnabled")

        // Kill switch turned OFF while it's actively blocking a DEAD tunnel:
        // honour fail-open by releasing the block. This runs on the tunnel
        // executor, so no establish() can be in flight underneath it; the
        // block also lingers in Disconnected during a reconnect BACKOFF
        // (switchTeardown keeps it up, the next /connect hasn't dispatched
        // ACTION_START yet). currentState is never Connected here
        // (killSwitchActive is cleared on connect success).
        if (!isKillSwitchEnabled && killSwitchActive) {
            deactivateKillSwitch()
            if (currentState is VpnState.KillSwitchActive) {
                // KillSwitchActive was the resting state (nothing reconnecting) —
                // releasing it means we're fully idle; settle to Disconnected.
                updateState(VpnState.Disconnected)
                _connectedServerFlow.value = null
                _connectedSinceFlow.value = 0L
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                // Error / Disconnected (mid recovery): block released, let
                // VpnManager's recovery continue fail-open.
                mainHandler.post { updateNotification() }
            }
            return
        }

        // switchTeardown publishes Disconnected before the incoming
        // ACTION_START lands; a START already queued behind this push is safe
        // (it re-creates nothing we stop here), and one not yet sent re-creates
        // the service when it arrives.
        if (currentState is VpnState.Disconnected && !killSwitchActive) {
            // Nothing is running — a stale push started us; don't park in a
            // fake foreground state.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Live session: refresh the notification so its kill-switch line
        // reflects the new value immediately.
        if (currentState is VpnState.Connected) {
            mainHandler.post { updateNotification() }
        }
    }

    /**
     * Another VPN app took over, or the user removed BirdoVPN's VPN
     * permission. It used to look like an ordinary disconnect: no reason, and
     * the peer left for the reap (A1-029). Now it ends in a typed Error with
     * an alert that says what happened; VpnManager releases the peer and
     * clears the session intent when it sees that Error. May be called off
     * the main thread (VpnService docs), so it is serialised like every other
     * transition.
     */
    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked")
        transitionGen.incrementAndGet()
        serial {
            deactivateKillSwitch()
            activeConfig = null
            stopTunnel(
                reason = VpnState.Error(SessionCopy.VPN_TAKEN_OVER, FailureKind.VPN_TAKEN_OVER),
                userInitiated = false,
            )
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        // Stop accepting work, and tell an in-flight setup it is orphaned
        // BEFORE anything else: its next checkpoint then abandons without an
        // establish() on a destroyed service (A1-012).
        destroyed = true
        running = false
        BypassSockets.uninstall(bypassProtector)
        transitionGen.incrementAndGet()
        stopNotificationTicker()
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) { /* never registered */ }
        serviceScope?.cancel()
        // The tunnel dies with the service, so the widget's "Protected" must
        // too. onDestroy is reached on paths that never go through stopTunnel
        // (handleUpdateSettings' stale-push stopSelf, an OOM kill, a force-stop
        // that runs it), and none of those reset the flag.
        updateWidgetState(false, null)
        // Cleanup is queued behind whatever transition is running, then the
        // executor drains. shutdown(), not shutdownNow(): an interrupt made
        // runBlocking in a stealth setup throw, and its catch armed a block on
        // this destroyed service. An in-flight setup that outlives the bounded
        // wait abandons itself at its next checkpoint.
        serial {
            cleanupTunnel()
            cleanupStealthAndQuantum()
            activeConfig = null
        }
        tunnelExecutor.shutdown()
        try {
            tunnelExecutor.awaitTermination(1_500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // The queued cleanup above stops the transport probe when it runs;
        // this covers a cleanup still stuck behind a setup that outlived the
        // wait. Nothing this service started may keep running after it.
        stopTransportProbe()
        statsExecutor.shutdownNow()
        super.onDestroy()
    }

    // ── Notification helpers (delegate to VpnNotificationManager) ─

    /** The body line while connected: "via {location}[ · {IP}]", each part behind its preference. */
    private fun buildConnectedText(): String? {
        val location = if (appPrefs.showLocationInNotification) connectedServer else null
        // Only the server's address, never the tunnel-internal assignedIp the
        // old fallback showed in its place (A1-020).
        val ip = if (appPrefs.showIpInNotification) publicIp else null
        return VpnNotificationManager.connectedBody(location, ip)
    }

    /**
     * The ongoing notification for the current state. Drawn from VpnManager's
     * state (the single owner) whenever it is reachable, so it cannot go stale
     * behind the manager; [notificationDetail] only fills the body while a
     * setup is in progress.
     */
    private fun buildCurrentNotification(): android.app.Notification {
        val manager = entryPoint?.vpnManager()
        val state = displayState()
        val body = when {
            state is VpnState.Connected -> buildConnectedText()
            state is VpnState.Error -> state.message
            state.isConnectingPhase -> notificationDetail
            else -> null
        }
        return notifManager.buildForegroundNotification(
            state = state,
            body = body,
            killSwitchActive = killSwitchActive,
            switching = manager?.switching?.value == true,
            multiHop = manager?.activeMultiHopRoute != null,
            connectedSince = connectedSince,
            details = if (state is VpnState.Connected) {
                notifManager.connectedDetails(
                    rxBytes = rxBytes,
                    txBytes = txBytes,
                    stealthActive = stealthActive,
                    quantumActive = quantumActive,
                    killSwitchEnabled = isKillSwitchEnabled,
                    splitTunnelAppCount = if (isSplitTunnelingEnabled) splitTunnelAppList.size else 0,
                )
            } else {
                emptyList()
            },
        )
    }

    /** Re-render the ongoing notification; [detail] replaces the setup progress text. */
    private fun updateNotification(detail: String? = notificationDetail) {
        notificationDetail = detail
        notifManager.update(buildCurrentNotification())
    }

    private fun startNotificationTicker() {
        mainHandler.removeCallbacks(notificationTicker)
        mainHandler.postDelayed(notificationTicker, notifTickIntervalMs())
    }

    private fun stopNotificationTicker() {
        mainHandler.removeCallbacks(notificationTicker)
    }

    // ── Traffic stats ────────────────────────────────────────────

    /** Read rx_bytes / tx_bytes from wg-go UAPI output using lazy line sequence. */
    private fun readTrafficStats() {
        val handle = tunnelHandle
        if (handle < 0) return
        try {
            val config = WgNative.getConfig(handle) ?: return
            var rx = 0L; var tx = 0L
            for (line in config.lineSequence()) {
                when {
                    line.startsWith("rx_bytes=") ->
                        rx += line.substringAfter('=').toLongOrNull() ?: 0L
                    line.startsWith("tx_bytes=") ->
                        tx += line.substringAfter('=').toLongOrNull() ?: 0L
                }
            }
            _rxBytesFlow.value = rx; _txBytesFlow.value = tx
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read traffic stats", e)
        }
    }

    /**
     * The server address the surfaces show ("VPN server · {IP}", the
     * notification's "via … · {IP}"), from the session's endpoint.
     */
    private fun extractServerIp() {
        val multiHop = entryPoint?.vpnManager()?.activeMultiHopRoute != null
        _publicIpFlow.value = VpnNotificationManager.serverAddressForDisplay(activeConfig?.endpoint, multiHop)
    }

    // ── Kill Switch ──────────────────────────────────────────────

    /**
     * Arm the fail-closed blocking interface.
     *
     * ORDERING CONTRACT — callers on a FAILURE path must call this BEFORE
     * publishing [VpnState.Error], never after.
     *
     * On success this publishes [VpnState.KillSwitchActive]. VpnManager's
     * auto-reconnect only fires on Error (and its backoff loop aborts when the
     * state is neither Error nor Reconnecting), so a failure path that set Error
     * and THEN called this overwrote the trigger with a state nothing reacts to:
     * the device sat with every packet blocked, "Kill Switch Active — Traffic
     * blocked" in the notification, and no retry, indefinitely, until the user
     * intervened by hand. Arming first makes Error the terminal state, so the
     * block is held AND auto-reconnect runs — fail-closed and self-healing.
     *
     * THE RESULT IS NOT OPTIONAL (P1-dk-killswitch-establish-failure-silent).
     * This used to return Unit. A refused or throwing establish() was reported
     * to FaultReporter and then nearly every caller went on to publish its
     * usual Error — "Connection lost. Reconnecting…", the setup's own reason —
     * as if the block had come up, so the user who turned the kill switch on
     * was never told it had failed. A failure path goes through
     * [blockThenPublish], which publishes the kill-switch failure in place of
     * its own Error; the other callers check the result themselves.
     *
     * @return [BlockArm.ARMED] when the block is up; [BlockArm.FAILED] when
     *   establish() refused or threw on both attempts, and traffic is NOT
     *   blocked; [BlockArm.SERVICE_GONE] when onDestroy has begun, before or
     *   during the attempts, so nothing was established. The data plane is
     *   down on every outcome.
     */
    private fun activateKillSwitch(): BlockArm {
        if (destroyed) {
            // Nothing may establish() on a destroyed service (A1-012): the
            // interface would outlive its owner. Tear down what is ours; a held
            // block is closed by onDestroy's own cleanupTunnel.
            cleanupTunnelDataPlane()
            return BlockArm.SERVICE_GONE
        }
        Log.i(TAG, "Activating kill switch — blocking all traffic (including STUN/WebRTC)")
        // ESTABLISH FIRST, TEAR DOWN SECOND. When arming over a LIVE tunnel
        // (server switch, connect timeout, KILL_SWITCH_BLOCK, stall) the sole
        // tun fd lives inside wg-go — startTunnel detachFd()s it and nulls
        // vpnInterface — so running cleanupTunnelDataPlane() first had
        // WgNative.turnOff close that fd and destroy the interface, reverting
        // routing to the physical network for the whole wg-go-shutdown +
        // establish() window: a cleartext leak at the exact moment the user
        // asked to be blocked. establish() below atomically supersedes
        // whatever interface is up — the live tunnel's OR a previous blocking
        // one (the same semantic startTunnel relies on when its new tunnel
        // supersedes this block) — so tearing wg-go down AFTER it can never
        // expose traffic. Callers therefore must NOT tear down first either.
        //
        // The retry sits inside the same window: a failed establish() leaves
        // the existing interface untouched (VpnService.Builder.establish
        // docs), so whatever was carrying or blocking traffic keeps doing so
        // until the last attempt is over.
        val stale = vpnInterface
        var established: ParcelFileDescriptor? = null
        var threw: Exception? = null
        var interrupted = false
        for (attempt in 1..KILL_SWITCH_ARM_ATTEMPTS) {
            if (attempt > 1) {
                FaultReporter.trail(
                    FaultReporter.PATH_KILL_SWITCH,
                    "block establish() ${if (threw != null) "threw" else "refused"} — retrying once",
                )
                try {
                    Thread.sleep(KILL_SWITCH_RETRY_MS)
                } catch (_: InterruptedException) {
                    // Nothing in this service interrupts the tunnel executor:
                    // onDestroy drains it with shutdown(), not shutdownNow().
                    // Whoever did, stop retrying. The flag is restored only
                    // after the teardown below, whose probe join an interrupt
                    // would cut short.
                    interrupted = true
                    break
                }
                // onDestroy sets [destroyed] on the main thread, so it can
                // begin DURING the sleep. Checked again here, after it: nothing
                // may establish() once it has (A1-012).
                if (destroyed) break
            }
            threw = null
            try {
                established = Builder()
                    .setSession("BirdoVPN Kill Switch")
                    .setMtu(1420)
                    .addAddress("10.255.255.1", 32)
                    .addAddress("fd00::1", 128)
                    // Route all IPv4 + IPv6 into the blocking VPN — this covers:
                    // - All TCP/UDP (including STUN ports 3478-3479, 5349)
                    // - All WebRTC ICE candidates (STUN/TURN)
                    // - DNS (prevents leaks to system resolver)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
                    // Point DNS at the blocking interface so queries don't leak
                    .addDnsServer("10.255.255.1")
                    .setBlocking(true)
                    // BirdoVPN itself stays OUTSIDE the block — unlike the tunnel
                    // (D-6). The block is not a tunnel, it carries nothing; the
                    // app has to reach the API through it to sign in, re-dial and
                    // release peers. Android's own lockdown exempts the VPN
                    // package for the same reason (AOSP Vpn.setVpnForcedLocked).
                    .addDisallowedApplication(packageName)
                    .establish()
            } catch (e: Exception) {
                threw = e
            }
            if (established != null) break
        }
        // The routing decision is made (block up, or establish() refused) —
        // only now tear down wg-go / monitor / callbacks. On EVERY outcome,
        // a throw included: the contract is that this call tears the data
        // plane down.
        cleanupTunnelDataPlane()
        if (interrupted) Thread.currentThread().interrupt()
        if (established != null) {
            vpnInterface = established
            // Release the stale interface — the OS atomically replaced its
            // routing with the new blocking interface above.
            if (stale != null && stale !== established) {
                try { stale.close() } catch (_: Exception) {}
            }
            _killSwitchActiveFlow.value = true
            updateState(VpnState.KillSwitchActive)
            Log.i(TAG, "Kill switch active — all traffic blocked")
            mainHandler.post { updateNotification() }
            return BlockArm.ARMED
        }
        if (destroyed) {
            // A teardown, not a failure of the control: the service went away
            // while this waited to retry. A block still held stays in
            // vpnInterface for onDestroy's cleanupTunnel to close (and clear
            // the flag), exactly as on the early return above.
            FaultReporter.trail(FaultReporter.PATH_KILL_SWITCH, "block not retried — the service is being destroyed")
            return BlockArm.SERVICE_GONE
        }
        _killSwitchActiveFlow.value = false
        if (threw != null) {
            FaultReporter.report(
                FaultReporter.PATH_KILL_SWITCH,
                "kill_switch_activate_threw",
                "Failed to activate the kill switch — traffic is NOT blocked",
                threw,
            )
        } else {
            // establish() failed (e.g. permission revoked) — don't hold a dead fd.
            if (stale != null) { try { stale.close() } catch (_: Exception) {} }
            vpnInterface = null
            // Reported HERE, at the root, as well as through the result: there
            // is no throwable on this branch — establish() returns null rather
            // than throwing — so without this the refusal is invisible to the
            // operator in every channel.
            FaultReporter.report(
                FaultReporter.PATH_KILL_SWITCH,
                "kill_switch_establish_refused",
                "VpnService.Builder.establish() returned null for the blocking interface — traffic is NOT blocked",
            )
        }
        return BlockArm.FAILED
    }

    /**
     * The failure-path idiom, written once: block FIRST — when the kill switch
     * is on — and only THEN publish [error] (the ordering contract on
     * [activateKillSwitch]). [beforePublish] is the caller's own teardown,
     * run between the two.
     *
     * When the block cannot be armed, [error] is NOT published: it would tell
     * the user the usual story ("Reconnecting…", the kill switch "is blocking
     * traffic until you reconnect") over a device whose traffic is in the
     * clear. [publishKillSwitchFailure] goes out in its place.
     *
     * Nothing is published, either, when a newer transition arrived while
     * this armed ([gen] no longer current): the arm can take a 250 ms retry,
     * and a Disconnect landing in it used to be followed by this path's
     * Error and alert, stale over the Disconnected the queued stop then
     * published. The same check covers a service onDestroy reached
     * ([BlockArm.SERVICE_GONE]): a teardown, over which "traffic is NOT
     * protected" would be an alarm about nothing the user can act on (A1-012:
     * an abandoned setup leaves no Error).
     *
     * @param gen the transition this failure belongs to; re-checked after the
     *   arm, before anything is published.
     * @param releaseWhenOff tear the tunnel down when the kill switch is off
     *   (every caller but the dead-tunnel handler, which leaves that to the
     *   re-dial's own teardown).
     */
    private fun blockThenPublish(
        gen: Long,
        error: VpnState.Error,
        releaseWhenOff: Boolean = true,
        beforePublish: () -> Unit = {},
    ) {
        if (!isKillSwitchEnabled) {
            if (releaseWhenOff) cleanupTunnel()
            beforePublish()
            if (isCurrent(gen)) updateState(error)
            return
        }
        val arm = activateKillSwitch()
        beforePublish()
        if (arm == BlockArm.SERVICE_GONE || !isCurrent(gen)) {
            Log.i(TAG, "Superseded while arming ($arm) — the newer transition owns the state")
            return
        }
        if (arm == BlockArm.ARMED) updateState(error) else publishKillSwitchFailure(error.kind)
    }

    /**
     * The kill switch could not be armed: say so, loudly — the Error AND the
     * alert. Silent failure of a security control is worse than a loud one.
     *
     * Alerted here rather than only through the render collector, which draws
     * from VpnManager and so says nothing when the entry point is unavailable.
     * The shared key keeps the collector from posting it a second time — so
     * the alert, and the key with it, go out BEFORE the state: the collector
     * runs on the main thread the moment the state lands, and a key set after
     * that was a check-then-set race it could lose, posting the alert twice.
     *
     * @param kind what the supervisor decides on. A failure path passes its
     *   own, so recovery runs exactly as it would have — a retryable drop
     *   still re-dials behind it, and the user's wish to be connected is kept.
     *   The two re-arm paths pass VPN_PERMISSION_REQUIRED, as they always did.
     */
    private fun publishKillSwitchFailure(kind: FailureKind) {
        val error = VpnState.Error(SessionCopy.KILL_SWITCH_NOT_ARMED, kind)
        // No tunnel is up on any path that gets here; a green widget would be
        // a false safety signal.
        updateWidgetState(false, null)
        postKillSwitchAlert(kind)
        updateState(error)
        mainHandler.post { updateNotification() }
    }

    /** The alert half of [publishKillSwitchFailure], posted at most once per key. */
    private fun postKillSwitchAlert(kind: FailureKind) {
        val error = VpnState.Error(SessionCopy.KILL_SWITCH_NOT_ARMED, kind)
        val alert = VpnNotificationManager.alertFor(
            state = error,
            killSwitchActive = false,
            sessionExpired = false,
            uiForeground = uiForeground,
        ) ?: return
        if (alert.key == postedAlertKey) return
        notifManager.postAlert(alert)
        postedAlertKey = alert.key
    }

    private fun deactivateKillSwitch() {
        _killSwitchActiveFlow.value = false
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        Log.i(TAG, "Kill switch deactivated")
    }

    // ── Tunnel Management ───────────────────────────────────────

    /**
     * The shared failure path of a tunnel setup, so the kill-switch ordering
     * contract is written once: block FIRST (or a full cleanup for a fail-open
     * user), THEN publish the Error — or the kill-switch failure in its place
     * ([blockThenPublish]). A setup that a newer transition has already
     * superseded publishes nothing at all — the newer one owns the tunnel and
     * the state (A1-012).
     */
    private fun failSetup(gen: Long, message: String, kind: FailureKind) {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        cleanupStealthAndQuantum()
        if (!isCurrent(gen)) {
            Log.i(TAG, "Failed setup was already superseded — no block, no Error")
            return
        }
        blockThenPublish(gen, VpnState.Error(message, kind))
    }

    /** A checkpoint in [startTunnel]: true (and the setup abandoned) when a newer transition owns the tunnel. */
    private fun supersededAt(gen: Long, where: String): Boolean {
        if (isCurrent(gen)) return false
        Log.i(TAG, "Setup superseded at $where — abandoning without a block or an Error")
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        cleanupStealthAndQuantum()
        return true
    }

    private fun startTunnel(gen: Long) {
        notificationDetail = null
        val config = activeConfig
        if (config == null || config.privateKey == null ||
            config.serverPublicKey == null || config.endpoint == null ||
            config.assignedIp == null
        ) {
            // A contract violation — the service was asked to start with no
            // usable config — not a network outcome, so it is an event.
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "connect_no_config",
                "Tunnel start requested with no or an incomplete VPN configuration",
            )
            failSetup(gen, SessionCopy.ENGINE_FAILED, FailureKind.REFUSED)
            return
        }

        // H-06 FIX: Reject tunnel establishment if debugger is attached in release.
        // A debugger can extract WireGuard private keys from memory.
        if (!BuildConfig.DEBUG && RootDetector.isDebuggerConnected()) {
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "connect_refused_debugger",
                "Refused to start the tunnel: a debugger is attached to a release build",
            )
            failSetup(gen, SessionCopy.DEBUGGER_ATTACHED, FailureKind.REFUSED)
            return
        }

        updateState(VpnState.Connecting)
        // Keep any kill-switch blocking interface UP through stealth/quantum setup
        // and the establish() below. The old deactivateKillSwitch() + cleanupTunnel()
        // here closed it immediately, egressing cleartext for the whole setup window
        // during a reconnect/switch. buildVpnInterface().establish() atomically
        // supersedes the blocking interface; the stale fd is released only after it
        // succeeds. Failure paths re-arm via activateKillSwitch(), which also keeps
        // traffic fail-closed seamlessly.
        cleanupTunnelDataPlane()

        // REQUESTED-VS-GRANTED GUARD.
        //
        // Compare what the user asked for against what the server actually
        // granted, and refuse rather than connect with less protection than the
        // UI is showing. Desktop treats this as load-bearing at four connect
        // paths; Android had no equivalent.
        //
        // Today the backend refuses rather than downgrades, so this is
        // defence-in-depth — but it is precisely the guard that catches a backend
        // regression, a partial rollout, or a MitM stripping the fields on the
        // way back. Silently connecting with weaker protection than advertised is
        // the one outcome that must not happen.
        if (appPrefs.quantumProtectionEnabled && !config.quantumEnabled) {
            // These two guards fire only on a backend regression, a partial
            // rollout or a MitM stripping fields — precisely the fleet-wide
            // events an operator needs to see, and precisely the ones a user
            // cannot describe.
            FaultReporter.report(
                FaultReporter.PATH_QUANTUM,
                "connect_refused_quantum_not_granted",
                "Refused to connect: quantum protection was requested but the server did not grant it",
            )
            // The canonical sentence (P1-parity): the remedy is the same
            // whichever half of the exchange failed.
            failSetup(gen, SessionCopy.QUANTUM_FAILED, FailureKind.QUANTUM_FAILED)
            return
        }
        if (appPrefs.stealthModeEnabled && !config.stealthEnabled) {
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "connect_refused_stealth_not_granted",
                "Refused to connect: stealth mode was requested but the server did not grant it",
            )
            failSetup(gen, SessionCopy.STEALTH_FAILED, FailureKind.STEALTH_FAILED)
            return
        }
        // GRANTED BUT UNUSABLE (P1-dk-probe-skip-on-unstarted-stealth): the
        // server said Stealth is on and sent no Xray endpoint to run it to. The
        // Phase 1 gate below needs both, so this fell through to its else and
        // dialled plain WireGuard to the normal endpoint — the unwrapped
        // connection a Stealth user asked not to make, on the networks where
        // it is most likely to be seen. Same answer as the guard above.
        //
        // Only when the USER asked for Stealth. A grant the client did not ask
        // for by preference (an Adaptive Transport fallback, or the 24 h
        // stealth preference after one) still goes direct: nothing on screen
        // claims Stealth (_stealthActiveFlow stays false), and the probe judges
        // that tunnel as a direct one (onStealthTransport below), so a blocked
        // network is still noticed.
        if (appPrefs.stealthModeEnabled && config.xrayEndpoint == null) {
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "connect_refused_stealth_no_endpoint",
                "Refused to connect: the server granted stealth mode but sent no Xray endpoint",
            )
            failSetup(gen, SessionCopy.STEALTH_FAILED, FailureKind.STEALTH_FAILED)
            return
        }
        if (config.stealthEnabled && config.xrayEndpoint == null) {
            FaultReporter.trail(FaultReporter.PATH_STEALTH, "stealth granted with no Xray endpoint — dialling direct")
        }

        try {
            // ── Phase 1: Stealth Tunnel (Xray Reality) ──────────────
            // When stealth mode is enabled and the server provides Xray config,
            // start a local Xray Reality client that wraps WireGuard UDP in
            // VLESS + XTLS-Reality TLS 1.3, making traffic appear as HTTPS
            // to www.microsoft.com (or configured SNI domain).
            var stealthEndpointOverride: String? = null
            if (config.stealthEnabled && config.xrayEndpoint != null) {
                Log.i(TAG, "Stealth mode enabled — starting Xray Reality tunnel")
                updateState(VpnState.StealthConnecting)
                mainHandler.post { updateNotification("Starting stealth tunnel…") }

                if (!XrayManager.isAvailable(applicationContext)) {
                    FaultReporter.report(
                        FaultReporter.PATH_STEALTH,
                        "connect_refused_stealth_unavailable",
                        "Refused to connect: stealth was requested and no Xray runtime is packaged",
                    )
                    failSetup(gen, SessionCopy.STEALTH_FAILED, FailureKind.STEALTH_FAILED)
                    return
                }

                val xrayStarted = runBlocking(Dispatchers.IO) {
                    XrayManager.start(applicationContext, config, appPrefs.wireGuardPort) {
                        onXrayExited(gen)
                    }
                }
                // Checkpoint: the Xray start is the one slow step left in a
                // setup, so it is where a Disconnect most often lands.
                if (supersededAt(gen, "stealth start")) return

                if (xrayStarted) {
                    val xrayPort = XrayManager.getLocalPort()
                    stealthEndpointOverride = "127.0.0.1:$xrayPort"
                    _stealthActiveFlow.value = true
                    Log.i(TAG, "Xray Reality active — WireGuard will connect via 127.0.0.1:$xrayPort")
                } else {
                    // XrayManager reports WHY it failed; this counts the users
                    // who were refused a connection because of it.
                    FaultReporter.report(
                        FaultReporter.PATH_STEALTH,
                        "connect_refused_stealth_start_failed",
                        "Refused to connect: the Xray stealth tunnel failed to start and a direct fallback would downgrade protection",
                    )
                    _stealthActiveFlow.value = false
                    failSetup(gen, SessionCopy.STEALTH_FAILED, FailureKind.STEALTH_FAILED)
                    return
                }
            } else {
                _stealthActiveFlow.value = false
            }

            // ── Phase 2: Quantum Protection (BirdoPQ v1) ──────
            // When the server enabled quantum protection, its /connect reply
            // carries an ML-KEM-1024 ciphertext (in the historical
            // rosenpassPublicKey field) and a nonce (rosenpassEndpoint). The
            // client decapsulates them LOCALLY — no network exchange — into a
            // 32-byte PSK injected as WireGuard's PresharedKey, so the session
            // stays confidential even if Curve25519 is broken later.
            var quantumPsk: String? = null
            if (config.quantumEnabled) {
                if (config.rosenpassPublicKey == null || config.rosenpassEndpoint == null) {
                    FaultReporter.report(
                        FaultReporter.PATH_QUANTUM,
                        "connect_refused_pq_payload_missing",
                        "Refused to connect: the server enabled quantum protection but sent no PQ payload",
                    )
                    _quantumActiveFlow.value = false
                    failSetup(gen, SessionCopy.QUANTUM_FAILED, FailureKind.QUANTUM_FAILED)
                    return
                }

                Log.i(TAG, "Quantum protection enabled — deriving the BirdoPQ PSK")
                mainHandler.post { updateNotification("Quantum key exchange…") }

                quantumPsk = runBlocking(Dispatchers.IO) {
                    BirdoPqManager.performKeyExchange(applicationContext, config)
                }
                if (supersededAt(gen, "quantum key exchange")) return

                if (quantumPsk != null) {
                    _quantumActiveFlow.value = true
                    Log.i(TAG, "PQ-PSK derived — quantum protection active")
                } else {
                    _quantumActiveFlow.value = false
                    // BirdoPqManager reports the specific cause; this is the
                    // refusal count.
                    FaultReporter.report(
                        FaultReporter.PATH_QUANTUM,
                        "connect_refused_pq_exchange_failed",
                        "Refused to connect: the PQ key exchange failed and a classical fallback would downgrade protection",
                    )
                    failSetup(gen, SessionCopy.QUANTUM_FAILED, FailureKind.QUANTUM_FAILED)
                    return
                }
            } else {
                _quantumActiveFlow.value = false
            }

            // ── Phase 3: WireGuard Tunnel ───────────────────────────
            // Verify JNI library integrity before loading (mirrors Windows wintun.dll check)
            if (!app.birdo.vpn.utils.NativeLibraryVerifier.verifyLibrary(this, "wg-go")) {
                // The verifier reports WHY (hash mismatch, untrusted signature,
                // …); this reports that a real user was actually refused a
                // tunnel because of it, which is the number that tells a
                // repackaging attempt apart from a stale hash injection in one
                // build.
                FaultReporter.report(
                    FaultReporter.PATH_INTEGRITY,
                    "connect_refused_integrity",
                    "Refused to start the tunnel: wg-go integrity verification failed",
                )
                failSetup(gen, SessionCopy.INTEGRITY_FAILED, FailureKind.REFUSED)
                return
            }

            if (!WgNative.init()) {
                // Distinct from WgNative's own wg_native_init_failed, which
                // fires once per process from the catch that HAS the
                // throwable. init() memoises its failure, so every subsequent
                // connect attempt returns false silently — this is the only
                // place those repeats are counted, and the repeat count is
                // what separates "one bad handset" from "the ABI split broke".
                FaultReporter.report(
                    FaultReporter.PATH_CONNECT,
                    "connect_engine_unavailable",
                    "Refused to start the tunnel: the WireGuard native bridge is unavailable",
                )
                failSetup(gen, SessionCopy.ENGINE_FAILED, FailureKind.REFUSED)
                return
            }

            // Build WireGuard config with stealth endpoint override and PQ-PSK
            val effectiveConfig = if (stealthEndpointOverride != null || quantumPsk != null) {
                config.copy(
                    endpoint = stealthEndpointOverride ?: config.endpoint,
                    presharedKey = quantumPsk ?: config.presharedKey,
                )
            } else {
                config
            }

            val wgConfig = buildWireGuardConfig(effectiveConfig)
            // Checkpoint before establish(): nothing may establish() for a
            // setup that a Disconnect superseded, or on a destroyed service.
            if (supersededAt(gen, "establish")) return
            val vpnFd = buildVpnInterface(effectiveConfig, stealth = stealthEndpointOverride != null) ?: run {
                // Twin of activateKillSwitch's kill_switch_establish_refused:
                // establish() returns null rather than throwing (VPN consent
                // revoked, another VPN holding the interface, a route the
                // Builder rejected), so without this the refusal is invisible
                // in every channel.
                FaultReporter.report(
                    FaultReporter.PATH_CONNECT,
                    "connect_establish_refused",
                    "VpnService.Builder.establish() returned null for the tunnel interface",
                )
                failSetup(gen, SessionCopy.VPN_PERMISSION, FailureKind.VPN_PERMISSION_REQUIRED)
                return
            }

            // The new tunnel interface has atomically superseded any kill-switch
            // blocking interface. Now — and only now — release the stale blocking
            // fd: traffic is captured by the live tunnel interface, so there is no
            // cleartext gap.
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null

            val tunFd = vpnFd.detachFd()
            Log.i(TAG, "VPN interface established, fd=$tunFd")

            val configString = wgConfig.toWgUserspaceString()
            val handle = WgNative.turnOn("birdo0", tunFd, configString)
            if (handle < 0) {
                // wg-go refusing the config it was handed. WgNative only sees
                // (and reports) the case where the JNI call THROWS; a genuine
                // negative return travels back as a plain Int and is silent
                // unless it is reported here. The handle is a small negative
                // error code, not user data.
                FaultReporter.report(
                    FaultReporter.PATH_CONNECT,
                    "wg_turn_on_rejected",
                    "wgTurnOn refused the tunnel configuration (code $handle)",
                )
                try { ParcelFileDescriptor.adoptFd(tunFd).close() } catch (_: Exception) {}
                failSetup(gen, SessionCopy.ENGINE_FAILED, FailureKind.NEVER_ESTABLISHED)
                return
            }

            tunnelHandle = handle
            Log.i(TAG, "WireGuard tunnel started")
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "VPN connected — ${config.assignedIp ?: "?"} → ${effectiveConfig.endpoint}")
            }
            Log.i(TAG, "Kill switch: $isKillSwitchEnabled | Split tunnel: $isSplitTunnelingEnabled (${splitTunnelAppList.size} apps)")
            Log.i(TAG, "Stealth: $stealthActive | Quantum: $quantumActive (mode=${BirdoPqManager.modeFlow.value})")

            // No PSK rekey loop in BirdoPQ v1: wireguard-android doesn't
            // expose wgSetConfig so live PSK swap isn't possible. Each
            // /connect already derives a fresh per-session PQ-PSK via
            // ML-KEM-1024 decapsulation, which gives the same HNDL guarantee
            // — see BirdoPqManager kdoc + native/ROADMAP.md.

            // D-6: the app is inside its own tunnel now, so wg-go's UDP
            // sockets MUST go around it or every WireGuard packet loops back
            // into the interface it came from. Once, here, before anything
            // relies on the tunnel; a socket that cannot be protected fails
            // the connect (block first, as every setup failure does).
            if (!protectTunnelSockets(handle)) {
                failSetup(gen, SessionCopy.ENGINE_FAILED, FailureKind.TRANSIENT)
                return
            }
            startTunnelMonitor(handle, WireGuardConfigBuilder.effectiveKeepaliveSec(effectiveConfig))
            registerUnderlyingNetworkCallback(handle)

            // SEC (honest scope): drop OUR references to the key from the
            // retained ConnectResponse. This does NOT scrub the key from process
            // memory — the key crossed this function as immutable Strings
            // (configString included), and those copies live until the GC
            // reclaims them; a heap dump taken in that window can still recover
            // them. A real fix needs a byte[]-accepting JNI entry point on wg-go
            // (design work, tracked). What this line buys: later code paths and
            // long-lived state no longer hold the key.
            activeConfig = activeConfig?.copy(privateKey = "", presharedKey = null)

            // The tunnel is up, so we are no longer in the blocking state — clear
            // the kill-switch flag.
            //
            // Every failure path arms the kill switch, but the SUCCESS path used
            // to leave the flag set: a successful establish() atomically
            // supersedes the blocking interface (see the note above the rebuild),
            // so deactivateKillSwitch() is deliberately not called here, and
            // nothing else reset it. The result was that any connect following a
            // failed attempt showed "Protected" and "Kill Switch — All traffic
            // blocked" at the same time — a flatly contradictory claim about
            // whether the user's traffic was flowing. Observed on-device.
            _killSwitchActiveFlow.value = false

            _connectedServerFlow.value = config.serverNode?.name ?: "Unknown"
            _rxBytesFlow.value = 0L; _txBytesFlow.value = 0L; _publicIpFlow.value = null
            extractServerIp()

            // DO NOT publish Connected here. The interface being up and wg-go
            // having accepted the config says nothing about whether a WireGuard
            // handshake will ever complete — on a DPI-filtered network it never
            // does, and the UI, the notification and the home-screen widget all
            // asserted "Protected" over a tunnel carrying zero packets. A green
            // shield is a safety claim; it must be backed by evidence.
            //
            // Stay in Connecting (the honest state: we are still establishing)
            // and let [startTransportProbe] publish Connected once it OBSERVES a
            // non-zero last_handshake_time_sec. Republishing Connecting also
            // normalises the StealthConnecting path back onto the state the
            // connect watchdog below guards on.
            updateState(VpnState.Connecting)
            // Backstop only: the probe is self-bounded at TransportProbe.WINDOW_MS
            // and always produces a verdict, so this fires solely if a wg-go JNI
            // read wedges. Deliberately generous — it must not race the ~10s
            // verdict or the stealth rebuild that a BLOCKED verdict kicks off.
            mainHandler.removeCallbacks(connectTimeoutRunnable)
            mainHandler.postDelayed(
                connectTimeoutRunnable,
                TransportProbe.WINDOW_MS + CONNECT_TIMEOUT_MS,
            )
            // Whether we are ON the stealth transport is what the fallback
            // decision turns on, and that is `stealthEndpointOverride != null` —
            // the endpoint we actually dialled. The old `config.stealthEnabled`
            // was the server's CLAIM, so a granted-but-unusable response
            // (stealthEnabled with no xrayEndpoint) skipped the probe on a plain
            // WireGuard tunnel and disabled the fallback for exactly the
            // filtered-network users Adaptive Transport exists for.
            startTransportProbe(handle, gen, onStealthTransport = stealthEndpointOverride != null)

        } catch (e: InterruptedException) {
            // Nothing in this service interrupts the tunnel executor (onDestroy
            // drains it with shutdown(), not shutdownNow()), so this is someone
            // else asking the thread to stop: abandon, as a user abort. No
            // block, no Error for VpnManager to answer with a re-dial (A1-012).
            // The throw cleared the flag, so the teardown's probe join below
            // runs in full; the flag is restored after it.
            Log.i(TAG, "Tunnel setup interrupted — abandoning")
            cleanupStealthAndQuantum()
            cleanupTunnelDataPlane()
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "start_tunnel_threw",
                "Unhandled exception while starting the tunnel",
                e,
            )
            // Match the connect-watchdog idiom: for a fail-closed user, arm the
            // block BEFORE any teardown — activateKillSwitch() establishes the
            // blocking interface first (superseding a live wg-go tun or a held
            // blocking fd) and only then turns wg-go off, so there is no window
            // where routing reverts to the physical network. Only fully release
            // when the kill switch is OFF. Block first, publish Error last.
            //
            // Never e.message: WireGuardConfigBuilder's validation messages quote
            // the node's endpoint ("Invalid endpoint: <ip:port>"), and they reached
            // Home, the notification and the alert (REVIEW-AND-006). A config the
            // server sent that does not validate is a refusal, not something a
            // retry fixes, so it no longer spends eight attempts either.
            val (message, kind) = SessionCopy.forSetupFailure(e)
            failSetup(gen, message, kind)
        }
    }

    /**
     * Build the Android VPN interface via [Builder].
     *
     * Configures MTU, address, DNS, routes, and split-tunneling exclusions.
     * Returns the established [ParcelFileDescriptor] or `null` if the user
     * has not granted VPN permission.
     *
     * @param stealth this tunnel's WireGuard runs over the local Xray relay:
     *   BirdoVPN stays outside the tunnel by UID and Xray's server is carved
     *   out of the routes (D-6; see [TunnelAppRules] and [XrayCarveOut]).
     */
    private fun buildVpnInterface(config: ConnectResponse, stealth: Boolean): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("BirdoVPN")
            .setBlocking(false)
            // A1-013: at targetSdk >= 29 a VPN network is METERED unless it
            // says otherwise, so with BirdoVPN up every app treated home Wi-Fi
            // as metered — "Wi-Fi only" updates, photo backups and UNMETERED
            // jobs waited for as long as the tunnel stayed up. false makes the
            // VPN inherit the meteredness of the network it runs over — the
            // system default network, since no underlying networks are
            // declared (wg-go's protected socket follows that network too).
            .setMetered(false)

        // Xray's server leaves by the physical network, never through the
        // tunnel that Xray itself carries (D-6). API 33+ excludes it outright;
        // below that, every route that contains it is split around it
        // ([addRoute]).
        val xrayServer = if (stealth) XrayCarveOut.serverIpv4(config.xrayEndpoint) else null
        val carveAround = xrayServer?.takeIf {
            XrayCarveOut.method(Build.VERSION.SDK_INT) == XrayCarveOut.Method.ROUTE_TABLE
        }
        val addRoute: (String, Int) -> Unit = { address, prefix ->
            if (carveAround != null && !address.contains(':')) {
                for (cidr in XrayCarveOut.split("$address/$prefix", carveAround)) {
                    builder.addRoute(cidr.substringBefore('/'), cidr.substringAfter('/').toInt())
                }
            } else {
                builder.addRoute(address, prefix)
            }
        }
        if (xrayServer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.excludeRoute(IpPrefix(InetAddress.getByName(xrayServer), 32))
        } else if (stealth && xrayServer == null) {
            // A hostname, or no endpoint: nothing to carve without a lookup.
            // The stealth UID exclusion below still keeps Xray off the tunnel.
            FaultReporter.trail(FaultReporter.PATH_STEALTH, "xray server is not an IPv4 literal — no route carve-out")
        }

        // MTU
        val userMtu = appPrefs.wireGuardMtu
        val effectiveMtu = (if (userMtu > 0) userMtu else (config.mtu ?: 1420)).coerceIn(1280, 1500)
        builder.setMtu(effectiveMtu)

        // Address
        builder.addAddress(config.assignedIp!!, 32)

        // IPv6 dual-stack: assign the tunnel IPv6 address ONLY when the node is
        // IPv6-enabled (backend sends clientIpv6). Otherwise IPv6 stays captured
        // by the ::/0 route below with no address = blackholed (leak-safe), which
        // is exactly today's behaviour. Mirrors the desktop client's gating.
        config.clientIpv6?.takeIf { it.isNotBlank() }?.let { v6 ->
            try {
                val parts = v6.split("/")
                val addr = parts[0]
                val prefix = parts.getOrNull(1)?.toIntOrNull() ?: 128
                builder.addAddress(addr, prefix)
                Log.i(TAG, "Dual-stack: added tunnel IPv6 address")
            } catch (e: Exception) {
                Log.w(TAG, "Invalid clientIpv6 address: $v6 — ${e.message}")
            }
        }

        // DNS — resolved by the SAME code that bakes DNS into the wg-go config
        // (WireGuardConfigBuilder), so the two can never diverge. The resolver
        // also filters out addresses unreachable through the tunnel's routes
        // (RFC1918/link-local/ULA): depending on localNetworkSharing those
        // either leak queries onto the LAN or blackhole all name resolution.
        // The one private address it admits — the node's own BirdoShield
        // resolver, 10.13.13.1 — is pinned back into the tunnel below when
        // local network sharing would otherwise leave it to the LAN.
        val tunnelDns = WireGuardConfigBuilder.resolveDnsServers(config, appPrefs)
        for (dns in tunnelDns) {
            try { builder.addDnsServer(InetAddress.getByName(dns)) }
            catch (e: Exception) { Log.w(TAG, "Invalid DNS: $dns") }
        }

        // Routes
        // F-19 FIX: When local network sharing is enabled, we must NOT route LAN
        // traffic through the VPN. Android's addRoute() directs traffic INTO the VPN,
        // not around it. So instead of the blanket 0.0.0.0/0, we add non-LAN routes
        // that cover the full IPv4 space minus private ranges.
        if (appPrefs.localNetworkSharing) {
            try {
                // Route everything EXCEPT private LAN ranges through the VPN.
                // This covers the full IPv4 space minus 10.0.0.0/8, 172.16.0.0/12,
                // and 192.168.0.0/16, leaving LAN traffic to go through the default
                // network interface directly.
                val nonLanRoutes = listOf(
                    // 0.0.0.0/5 covers 0.x-7.x
                    "0.0.0.0/5",
                    // 8.0.0.0/7 covers 8.x-9.x
                    "8.0.0.0/7",
                    // Skip 10.0.0.0/8 (LAN)
                    // 11.0.0.0/8 through 172.15.x.x
                    "11.0.0.0/8",
                    "12.0.0.0/6",
                    "16.0.0.0/4",
                    "32.0.0.0/3",
                    "64.0.0.0/2",
                    "128.0.0.0/3",
                    "160.0.0.0/5",
                    "168.0.0.0/6",
                    "172.0.0.0/12",
                    // Skip 172.16.0.0/12 (LAN)
                    "172.32.0.0/11",
                    "172.64.0.0/10",
                    "172.128.0.0/9",
                    "173.0.0.0/8",
                    "174.0.0.0/7",
                    "176.0.0.0/4",
                    // Skip 192.168.0.0/16 (LAN)
                    "192.0.0.0/9",
                    "192.128.0.0/11",
                    "192.160.0.0/13",
                    "192.169.0.0/16",
                    "192.170.0.0/15",
                    "192.172.0.0/14",
                    "192.176.0.0/12",
                    "192.192.0.0/10",
                    "193.0.0.0/8",
                    "194.0.0.0/7",
                    "196.0.0.0/6",
                    "200.0.0.0/5",
                    "208.0.0.0/4",
                    "224.0.0.0/3",
                )
                for (cidr in nonLanRoutes) {
                    val parts = cidr.split("/")
                    addRoute(parts[0], parts[1].toInt())
                }
                // BirdoShield (D18): the filtering resolver (10.13.13.1) sits
                // inside the 10.0.0.0/8 hole this set leaves for the LAN.
                // Without a more specific route every query to it would egress
                // on the physical network in cleartext — a DNS leak — and never
                // reach the node. A /32 wins on longest prefix, so only the
                // resolver is pulled back into the tunnel; the LAN stays local.
                // Empty unless the resolved DNS holds a tunnel-gateway address.
                for (cidr in WireGuardConfigBuilder.pinnedResolverRoutes(tunnelDns, config.assignedIp)) {
                    val parts = cidr.split("/")
                    addRoute(parts[0], parts[1].toInt())
                }
                // Still route IPv6 through VPN for leak protection
                addRoute("::", 0)
                Log.i(TAG, "Local network sharing enabled — LAN ranges excluded from VPN routes")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure LAN exclusion routes, falling back to full route: ${e.message}")
                addRoute("0.0.0.0", 0)
                addRoute("::", 0)
            }
        } else {
            var hasV6Default = false
            var hasV4Default = false
            for (cidr in config.allowedIps ?: listOf("0.0.0.0/0", "::/0")) {
                try {
                    val parts = cidr.split("/")
                    val addr = parts[0]
                    val prefix = if (parts.size > 1) parts[1].toInt() else
                        if (cidr.contains(":")) 128 else 32
                    addRoute(addr, prefix)
                    if (addr == "::" && prefix == 0) hasV6Default = true
                    if (addr == "0.0.0.0" && prefix == 0) hasV4Default = true
                } catch (e: Exception) { Log.w(TAG, "Invalid route: $cidr — ${e.message}") }
            }
            // Enforce an IPv6 blackhole client-side even when the server omits
            // ::/0 from allowedIps. Our nodes are IPv4-only, so capturing all IPv6
            // into the tunnel drops it instead of letting it egress below the
            // tunnel on the physical adapter (a v6 default on the NIC would
            // otherwise win). Guarded so we never add a duplicate ::/0 (Android
            // throws IllegalArgumentException on duplicate routes).
            if (!hasV6Default) {
                try { addRoute("::", 0) } catch (e: Exception) {
                    // Leak-shaped: without ::/0 captured, IPv6 egresses on the
                    // physical adapter below the tunnel. The connect proceeds
                    // (unchanged here), so this report is the only witness.
                    FaultReporter.report(
                        FaultReporter.PATH_CONNECT,
                        "connect_v6_blackhole_route_failed",
                        "Could not add the ::/0 blackhole route — IPv6 may egress outside the tunnel",
                        e,
                    )
                }
            }
            // The IPv4 twin of the check above, which did not exist. Every
            // addRoute() failure in the loop is caught and merely logged, so a
            // single unparseable entry silently drops that route -- and if the
            // dropped one is the default, IPv4 leaves on the physical interface
            // in cleartext while establish() succeeds and the UI says Connected.
            // Two inputs reach this, both surviving the validation that runs
            // first (WireGuardConfigBuilder.isValidCidr, applied at line 1014
            // before buildVpnInterface is called at 1015):
            //   * a well-formed list with no v4 default, e.g.
            //     ["10.0.0.0/8", "::/0"] -- every entry valid, no 0.0.0.0/0;
            //   * an entry with host bits set, e.g. "10.0.0.1/8", which
            //     isValidCidr accepts but VpnService.Builder.addRoute rejects
            //     with "Bad address", so the catch above swallows it.
            // A prefix-less "0.0.0.0" is NOT one of them -- isValidCidr requires
            // a '/' and the connect aborts before reaching this loop.
            //
            // Unlike IPv6 -- where we blackhole and a failure merely leaks v6 --
            // there is no safe degraded state for v4 here, so fail CLOSED:
            // returning null makes the caller engage the kill switch and surface
            // an error, which is the correct outcome for a privacy VPN that
            // cannot capture the traffic it promised to capture.
            if (!hasV4Default) {
                try {
                    addRoute("0.0.0.0", 0)
                    Log.w(TAG, "allowedIps carried no IPv4 default route - added one")
                } catch (e: Exception) {
                    // Fails closed (null → kill switch + error), which is the
                    // right outcome for the user and a blind spot for us
                    // unless it is reported.
                    FaultReporter.report(
                        FaultReporter.PATH_CONNECT,
                        "connect_v4_default_route_failed",
                        "allowedIps carried no IPv4 default route and one could not be added",
                        e,
                    )
                    return null
                }
            }
        }

        // Who stays out of the tunnel: the split-tunnel apps, and BirdoVPN
        // itself ONLY while Stealth runs Xray as a child process (D-6).
        val ownPackage = packageName
        val disallowed = TunnelAppRules.disallowedPackages(
            ownPackage = ownPackage,
            stealthActive = stealth,
            splitTunnelEnabled = isSplitTunnelingEnabled,
            splitTunnelApps = splitTunnelAppList,
        )
        for (app in disallowed) {
            if (app == ownPackage) {
                builder.addDisallowedApplication(app)
                continue
            }
            try {
                packageManager.getPackageInfo(app, 0)
                builder.addDisallowedApplication(app)
            } catch (_: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Split tunnel: $app not installed, skipping")
            }
        }

        return builder.establish()
    }

    /**
     * Protect wg-go's UDP sockets from the tunnel, ONCE, synchronously.
     *
     * Before D-6 this was a background thread that re-protected every 2 s for
     * 40 s, joined by a 30 s loop in TunnelMonitor and a re-protect on every
     * capability change (A1-037) — all of it without effect while the app was
     * excluded from its own tunnel. Now that the app is inside it, the one
     * protect that matters is this one: protect() marks the descriptor, and
     * the unconnected socket then follows the system default network on every
     * send, across roams, for its whole life.
     *
     * wgTurnOn opens the sockets before it returns (device.Up binds them), so
     * the first read normally finds them; the bounded retry only covers a bind
     * that lands a moment later.
     *
     * @return true when at least one socket exists and every socket that
     *   exists is protected. Anything else would send WireGuard's own packets
     *   into the tunnel they are meant to carry.
     */
    private fun protectTunnelSockets(handle: Int): Boolean {
        var v4 = -1
        var v6 = -1
        for (attempt in 1..PROTECT_ATTEMPTS) {
            v4 = WgNative.getSocketV4(handle)
            v6 = WgNative.getSocketV6(handle)
            if (v4 >= 0 || v6 >= 0) break
            if (attempt < PROTECT_ATTEMPTS) {
                try {
                    Thread.sleep(PROTECT_RETRY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        val protected4 = v4 >= 0 && protectSocketFd(v4)
        val protected6 = v6 >= 0 && protectSocketFd(v6)
        val ok = TunnelSocketProtection.complete(v4, v6, protected4, protected6)
        if (!ok) {
            FaultReporter.report(
                FaultReporter.PATH_TUNNEL,
                "socket_protect_failed",
                "wg-go's UDP sockets could not be protected from the tunnel — refusing the connect",
            )
        }
        return ok
    }

    private fun protectSocketFd(fd: Int): Boolean = try {
        protect(fd)
    } catch (e: Exception) {
        // The verdict is reported once by protectTunnelSockets; the cause
        // travels as a breadcrumb.
        FaultReporter.trail(FaultReporter.PATH_TUNNEL, "protect() threw: ${e.javaClass.simpleName}")
        false
    }

    private fun startTunnelMonitor(handle: Int, keepaliveSec: Int) {
        tunnelMonitor = TunnelMonitor(
            handle = handle,
            keepaliveSec = keepaliveSec,
            underlyingMissingSince = { underlyingMissingSince },
            // Same predicate as the transport probe, and for the same reason:
            // the monitor starts BEFORE Connected is published (now up to
            // TransportProbe.WINDOW_MS before it), so gating on Connected made
            // the monitor thread exit on its very first check and left the
            // session with no stall detection at all.
            isAlive = {
                tunnelHandle == handle &&
                    currentState !is VpnState.Disconnected &&
                    currentState !is VpnState.Disconnecting &&
                    currentState !is VpnState.Error
            },
            onUnexpectedExit = { neverHandshook ->
                // Serialised like every other transition, and dropped if this
                // tunnel is no longer the live one by the time it runs.
                serial {
                    if (tunnelHandle != handle || destroyed) return@serial
                    val gen = transitionGen.get()
                    // Fail closed FIRST (block all traffic), THEN hand off to
                    // the supervisor. Activating the kill switch alone tears
                    // wg-go down and latches the state at KillSwitchActive,
                    // which VpnManager does not treat as a failure — so a
                    // >3-min stall (subway/flight-mode/dead-zone) left the user
                    // stranded with all traffic blocked until a manual
                    // reconnect. Emitting Error drives VpnManager's recovery
                    // (which holds the block across each re-dial and clears it
                    // on a successful connect), matching the desktop client's
                    // behaviour on the same drop. A block that cannot be armed
                    // says so instead of "Reconnecting…" (blockThenPublish).
                    val error = VpnState.Error(
                        "Connection lost. Reconnecting…",
                        if (neverHandshook) FailureKind.NEVER_ESTABLISHED else FailureKind.DIED_AFTER_HANDSHAKE,
                    )
                    blockThenPublish(gen, error, releaseWhenOff = false) {
                        // The tunnel is no longer carrying traffic — clear the
                        // widget's "Protected" so it doesn't keep asserting a
                        // connection through the whole reconnect window.
                        // Unconditional: even with the kill switch OFF the tunnel is
                        // down, so a green widget would be a false safety signal.
                        updateWidgetState(false, null)
                    }
                }
            },
        ).also { it.start() }
    }

    /**
     * The Xray child process exited on its own (A1-032): a crash or a kill.
     * WireGuard's packets to the local relay now go nowhere, which the stall
     * rules would only notice tens of seconds later — so treat it like a
     * dead tunnel at once: block first (kill switch on), then the Error the
     * supervisor re-dials on. Ignored unless it is THIS setup's tunnel that is
     * up on the stealth transport.
     */
    private fun onXrayExited(gen: Long) {
        serial {
            if (!isCurrent(gen) || tunnelHandle < 0 || !stealthActive) return@serial
            FaultReporter.trail(FaultReporter.PATH_STEALTH, "xray exited on its own — tunnel declared dead")
            blockThenPublish(gen, VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE)) {
                cleanupStealthAndQuantum()
                updateWidgetState(false, null)
            }
        }
    }

    /**
     * ADAPTIVE TRANSPORT + HANDSHAKE GATE: confirm the freshly-established
     * tunnel is actually carrying traffic, and publish the user-visible
     * "Protected" state only once it is.
     *
     * This is the SOLE publisher of [VpnState.Connected] on the connect path.
     * Until it runs the service stays in Connecting, so no surface — UI,
     * notification or widget — ever claims protection that is not in force.
     *
     * Runs on its own short-lived daemon thread — [TransportProbe.await] blocks
     * for up to [TransportProbe.WINDOW_MS], and doing that on the caller would
     * stall tunnel setup and risk an ANR. The thread is tracked in
     * [transportProbe]: every data-plane teardown and onDestroy interrupt it
     * (the probe answers ABORTED) and wait for it, bounded ([stopTransportProbe]).
     *
     * The probe now runs for stealth connections too, because the gate applies
     * to every transport. `onStealthTransport` no longer skips it; it selects
     * what a BLOCKED verdict MEANS:
     *  - direct: stealth is still untried → signal VpnManager to retry over it.
     *  - stealth: this is the last transport we have, so a failure here is a
     *    genuine connection failure. Fail closed and publish Error, which drives
     *    the ordinary backoff reconnect. Emitting "blocked" instead would ask
     *    VpnManager for a fallback it has already made — a reconnect loop.
     */
    private fun startTransportProbe(
        handle: Int,
        gen: Long,
        onStealthTransport: Boolean,
        liveRebuildId: Long? = null,
    ) {
        // One probe at a time. The teardown in front of every caller has
        // already stopped the last one; this is the backstop.
        stopTransportProbe()
        val probe = Thread({
            try {
                runTransportProbe(handle, gen, onStealthTransport, liveRebuildId)
            } finally {
                // Clear our own entry only: a newer probe may already own it.
                transportProbe.compareAndSet(Thread.currentThread(), null)
            }
        }, "birdo-transport-probe").apply { isDaemon = true }
        transportProbe.set(probe)
        probe.start()
    }

    /**
     * Interrupt the running transport probe and wait for it to exit, bounded
     * by [PROBE_JOIN_MS]. Interrupted, it returns ABORTED from its poll sleep,
     * and its verdict hop finds the tunnel superseded and does nothing.
     * Reachable from the main thread (onDestroy) and from the tunnel executor
     * (every data-plane teardown); never from the probe itself, which hands
     * its verdict to the executor rather than acting on it, but guarded the
     * way TunnelMonitor.stop is, since joining yourself is a no-op that leaks
     * an interrupt.
     */
    private fun stopTransportProbe() {
        val probe = transportProbe.getAndSet(null) ?: return
        if (probe === Thread.currentThread()) return
        probe.interrupt()
        try {
            probe.join(PROBE_JOIN_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** The body of the [startTransportProbe] thread: probe, then hand the verdict to the executor. */
    private fun runTransportProbe(
        handle: Int,
        gen: Long,
        onStealthTransport: Boolean,
        liveRebuildId: Long?,
    ) {
        val verdict = try {
            TransportProbe(
                handle = handle,
                // True while THIS tunnel is the live one and nothing has
                // torn it down. Deliberately not `is Connected`: during the
                // verify window the state is Connecting by design, and
                // gating on Connected would abort the probe instantly and
                // strand the connect. A kill-switch arm clears tunnelHandle,
                // so it is covered by the handle check.
                isAlive = {
                    tunnelHandle == handle &&
                        currentState !is VpnState.Disconnected &&
                        currentState !is VpnState.Disconnecting &&
                        currentState !is VpnState.Error
                },
                windowMs = if (liveRebuildId != null) LiveRebuildPolicy.PROBE_WINDOW_MS else TransportProbe.WINDOW_MS,
            ).await()
        } catch (t: Throwable) {
            // Never strand the connect on an unexpected probe failure: with
            // no evidence either way, fall back to the pre-gate behaviour
            // and let TunnelMonitor's stall detection own the tunnel.
            // Reported, not only logged: this is the one branch that tells
            // the user "Connected" with no evidence, and because it
            // publishes Connected rather than Error the updateState
            // breadcrumb never fires for it.
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "transport_probe_threw",
                "Transport probe threw — publishing Connected unverified",
                t,
            )
            TransportProbe.Result.HANDSHAKE_OK
        }
        // The verdict is a transition like any other: serialised, and
        // dropped if a newer one (a Disconnect, a switch) owns the tunnel.
        serial {
            val current = isCurrent(gen) && tunnelHandle == handle
            when {
                liveRebuildId != null && current -> onLiveRebuildVerdict(verdict, handle, gen, liveRebuildId)
                liveRebuildId != null -> completeLiveRebuild(liveRebuildId, LiveRebuildPolicy.Event.SUPERSEDED)
                current -> onProbeVerdict(verdict, handle, gen, onStealthTransport)
            }
        }
    }

    private fun onProbeVerdict(verdict: TransportProbe.Result, handle: Int, gen: Long, onStealthTransport: Boolean) {
        when (verdict) {
            TransportProbe.Result.HANDSHAKE_OK -> publishConnected(handle)

            TransportProbe.Result.BLOCKED -> if (!onStealthTransport) {
                Log.w(TAG, "Transport probe: no handshake — requesting stealth fallback")
                // tryEmit, not emit: this is a non-suspending context and the
                // buffer is sized for it. A dropped emission would only mean a
                // missed fallback, never a blocked tunnel thread. If VpnManager
                // declines the fallback (cooldown, multi-hop, no server) the
                // connect watchdog re-armed by startTunnel resolves the state.
                _transportBlockedFlow.tryEmit(Unit)
            } else {
                Log.w(TAG, "Transport probe: no handshake over stealth — failing the connect")
                // Fail closed first, then publish Error — see the ordering
                // contract on [activateKillSwitch]. The tunnel here is LIVE
                // (established, just no handshake) and wg-go holds the sole
                // tun fd, so activateKillSwitch() must do its own ordered
                // establish-then-teardown — a teardown here first would
                // revert routing to the physical network before the block.
                blockThenPublish(gen, VpnState.Error(SessionCopy.NO_TUNNEL, FailureKind.NEVER_ESTABLISHED)) {
                    cleanupStealthAndQuantum()
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                }
            }

            // The tunnel went away while probing (disconnect, switch, kill
            // switch). Whoever tore it down owns the state.
            TransportProbe.Result.ABORTED -> Unit
        }
    }

    // ── A1-034: the in-place live rebuild ────────────────────────────

    /**
     * Rebuild the live session in place: the new config is checked first, the
     * new interface is established on THIS running service — which moves
     * routing to it atomically and leaves the old one untouched if it fails —
     * and only then does wg-go move over. The pure rules and why Android fails
     * closed after the swap instead of reverting: LiveRebuildPolicy.
     */
    private fun handleLiveRebuild(intent: Intent, gen: Long) {
        val id = intent.getLongExtra(EXTRA_REBUILD_ID, -1L)
        val config = rebuildConfig
        rebuildConfig = null
        fun keepOld() = completeLiveRebuild(id, LiveRebuildPolicy.Event.FAILED_BEFORE_SWAP)
        if (config == null || !isCurrent(gen) || currentState !is VpnState.Connected || tunnelHandle < 0 ||
            stealthActive
        ) {
            keepOld()
            return
        }
        isKillSwitchEnabled = intent.getBooleanExtra(EXTRA_KILL_SWITCH, isKillSwitchEnabled)
        isSplitTunnelingEnabled = intent.getBooleanExtra(EXTRA_SPLIT_TUNNEL_ENABLED, isSplitTunnelingEnabled)
        intent.getStringArrayExtra(EXTRA_SPLIT_TUNNEL_APPS)?.let { splitTunnelAppList = it.toSet() }

        val prepared = prepareLiveRebuild(config) ?: run { keepOld(); return }
        if (!isCurrent(gen)) {
            completeLiveRebuild(id, LiveRebuildPolicy.Event.SUPERSEDED)
            return
        }
        // establish() on the running service. null leaves "the existing
        // interface and its file descriptor untouched" (VpnService.Builder
        // .establish docs): the old session is exactly as it was.
        val vpnFd = buildVpnInterface(prepared.first, stealth = false) ?: run {
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "live_rebuild_establish_refused",
                "establish() refused the live rebuild's interface — the live session is kept",
            )
            keepOld()
            return
        }

        // ── THE SWAP. The new interface carries the traffic from here; the
        // old one is deactivated, and its wg-go instance goes with it.
        updateState(VpnState.Connecting)
        stopNotificationTicker()
        cleanupTunnelDataPlane()
        val tunFd = vpnFd.detachFd()
        val handle = WgNative.turnOn("birdo0", tunFd, prepared.second.toWgUserspaceString())
        if (handle < 0) {
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "live_rebuild_turn_on_rejected",
                "wgTurnOn refused the live rebuild's configuration (code $handle) — failing closed",
            )
            try { ParcelFileDescriptor.adoptFd(tunFd).close() } catch (_: Exception) {}
            failLiveRebuildClosed(gen, id)
            return
        }
        tunnelHandle = handle
        if (!protectTunnelSockets(handle)) {
            failLiveRebuildClosed(gen, id)
            return
        }
        startTunnelMonitor(handle, WireGuardConfigBuilder.effectiveKeepaliveSec(prepared.first))
        registerUnderlyingNetworkCallback(handle)
        activeConfig = prepared.first.copy(privateKey = "", presharedKey = null)
        _connectedServerFlow.value = config.serverNode?.name ?: "Unknown"
        _rxBytesFlow.value = 0L; _txBytesFlow.value = 0L; _publicIpFlow.value = null
        extractServerIp()
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        mainHandler.postDelayed(connectTimeoutRunnable, LiveRebuildPolicy.PROBE_WINDOW_MS + CONNECT_TIMEOUT_MS)
        startTransportProbe(handle, gen, onStealthTransport = false, liveRebuildId = id)
    }

    /**
     * Everything a live rebuild can refuse BEFORE the old tunnel is touched:
     * the requested-vs-granted guards, the PQ derivation, the engine and the
     * config. Null keeps the live session.
     */
    private fun prepareLiveRebuild(config: ConnectResponse): Pair<ConnectResponse, Config>? {
        if (appPrefs.quantumProtectionEnabled && !config.quantumEnabled) {
            FaultReporter.report(
                FaultReporter.PATH_QUANTUM,
                "live_rebuild_refused_quantum_not_granted",
                "Live rebuild refused: quantum protection was requested but the server did not grant it",
            )
            return null
        }
        // Stealth is never rebuilt in place (one Xray process, one port).
        if (config.stealthEnabled || appPrefs.stealthModeEnabled) return null
        var psk: String? = null
        if (config.quantumEnabled) {
            if (config.rosenpassPublicKey == null || config.rosenpassEndpoint == null) return null
            // BirdoPqManager reports the specific cause.
            psk = runBlocking(Dispatchers.IO) { BirdoPqManager.performKeyExchange(applicationContext, config) }
                ?: return null
        }
        if (!app.birdo.vpn.utils.NativeLibraryVerifier.verifyLibrary(this, "wg-go") || !WgNative.init()) return null
        val effective = if (psk != null) config.copy(presharedKey = psk) else config
        return try {
            effective to buildWireGuardConfig(effective)
        } catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "live_rebuild_config_rejected",
                "The live rebuild's configuration failed validation — the live session is kept",
                e,
            )
            null
        }
    }

    private fun onLiveRebuildVerdict(verdict: TransportProbe.Result, handle: Int, gen: Long, id: Long) {
        when (verdict) {
            TransportProbe.Result.HANDSHAKE_OK -> {
                publishConnected(handle)
                completeLiveRebuild(id, LiveRebuildPolicy.Event.NEW_PEER_HANDSHAKED)
            }
            TransportProbe.Result.BLOCKED -> failLiveRebuildClosed(gen, id)
            TransportProbe.Result.ABORTED -> completeLiveRebuild(id, LiveRebuildPolicy.Event.SUPERSEDED)
        }
    }

    /**
     * After the swap the old session cannot come back (its key is gone from
     * memory), so a new peer that does not work fails CLOSED — block first
     * when the kill switch is on, then the Error — and says so (iOS #354).
     */
    private fun failLiveRebuildClosed(gen: Long, id: Long) {
        failSetup(gen, SessionCopy.switchFailedClosed(isKillSwitchEnabled), FailureKind.NEVER_ESTABLISHED)
        completeLiveRebuild(id, LiveRebuildPolicy.Event.FAILED_AFTER_SWAP)
    }

    /**
     * Publish the verified-connected state. Called only after a WireGuard
     * handshake has been observed on [handle] (or on a build that cannot be
     * probed at all), so every "Protected" surface it lights up is backed by a
     * tunnel that has demonstrably passed a packet.
     */
    private fun publishConnected(handle: Int) {
        // A switch/reconnect may have superseded this tunnel while we probed.
        if (tunnelHandle != handle) return
        _connectedSinceFlow.value = System.currentTimeMillis()
        updateState(VpnState.Connected)
        updateWidgetState(true, connectedServer)
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        mainHandler.post {
            updateNotification()
            startNotificationTicker()
        }
    }

    private fun buildWireGuardConfig(response: ConnectResponse): Config {
        return WireGuardConfigBuilder.build(response, appPrefs)
    }

    /**
     * Full teardown: tunnel, block and foreground service.
     *
     * @param reason end in this Error instead of Disconnected (a revoke, a
     *   takeover), and post it as an alert, since the ongoing notification
     *   that could have carried it goes away with the service.
     * @param userInitiated the user asked for this stop. The standalone "Not
     *   connected" notice is only for a stop they did NOT ask for, and only
     *   while the Notifications setting is on (A1-028, P1-parity-012) — that
     *   setting used to be read by nothing at all.
     */
    private fun stopTunnel(reason: VpnState.Error?, userInitiated: Boolean) {
        Log.i(TAG, "Stopping VPN tunnel")
        updateState(VpnState.Disconnecting)
        stopNotificationTicker()
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        deactivateKillSwitch()
        cleanupTunnel()
        cleanupStealthAndQuantum()
        // Clear sensitive config from memory (private keys, etc.)
        activeConfig = null
        updateState(reason ?: VpnState.Disconnected)
        _connectedServerFlow.value = null
        _connectedSinceFlow.value = 0L
        _rxBytesFlow.value = 0L; _txBytesFlow.value = 0L; _publicIpFlow.value = null
        _stealthActiveFlow.value = false; _quantumActiveFlow.value = false
        updateWidgetState(false, null)
        if (userInitiated) {
            // The user acted: an alert about the session they just ended
            // ("Kill switch could not be armed", "Can't connect") is stale
            // the moment they did. The render collector only withdraws one on
            // Connected, so a Disconnect left it in the shade.
            notifManager.cancelAlert()
            postedAlertKey = null
        }
        if (reason != null) {
            val alert = VpnNotificationManager.alertFor(reason, killSwitchActive = false, sessionExpired = false, uiForeground = uiForeground)
            if (alert != null) {
                notifManager.postAlert(alert)
                // The render collector sees the same Error a moment later;
                // the shared key keeps it from posting it again (REVIEW-AND-014).
                postedAlertKey = alert.key
            }
        } else if (shouldPostDisconnectedNotice(userInitiated, appPrefs.notificationsEnabled)) {
            notifManager.postDisconnectedNotification()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Tear down the data plane (wg-go handle, tunnel monitor, network callbacks)
     * but leave [vpnInterface] alone. Callers that must keep traffic fail-closed
     * across a transition (kill switch, reconnect/switch) use this so the blocking
     * interface stays up until a replacement interface atomically supersedes it.
     *
     * ORDERING: never call this immediately BEFORE [activateKillSwitch]. When the
     * live tunnel's tun fd is inside wg-go (the normal connected state —
     * [vpnInterface] is null after detachFd), [WgNative.turnOff] closes that fd
     * and destroys the interface, reverting routing to the physical network until
     * the block's establish() lands. [activateKillSwitch] performs this teardown
     * itself, AFTER its blocking interface is established.
     */
    private fun cleanupTunnelDataPlane() {
        // The probe first, while the handle it reads is still valid; it is
        // gone (bounded wait) before wg-go is turned off below.
        stopTransportProbe()
        unregisterUnderlyingNetworkCallback()
        tunnelMonitor?.stop()
        tunnelMonitor = null
        if (tunnelHandle >= 0) {
            Log.i(TAG, "Turning off WireGuard tunnel, handle=$tunnelHandle")
            WgNative.turnOff(tunnelHandle)
            tunnelHandle = -1
        }
    }

    private fun cleanupTunnel() {
        cleanupTunnelDataPlane()
        try { vpnInterface?.close() } catch (e: Exception) { Log.w(TAG, "Error closing VPN", e) }
        vpnInterface = null
        // Whatever interface this service held is closed now, a block
        // included, so nothing is blocking. The flag is process-wide and
        // outlives this instance: onDestroy's cleanup used to leave it true
        // over a closed block, and VpnManager went on believing in it.
        _killSwitchActiveFlow.value = false
    }

    /**
     * Data-plane teardown for an immediate reconnect / server switch (see
     * [ACTION_SWITCH_TEARDOWN]). Keeps the kill-switch blocking interface and the
     * foreground service alive so traffic stays fail-closed while VpnManager
     * re-registers the peer and the incoming ACTION_START rebuilds the tunnel.
     */
    private fun switchTeardown(intent: Intent) {
        isKillSwitchEnabled = intent.getBooleanExtra(EXTRA_KILL_SWITCH, isKillSwitchEnabled)
        // A settings reapply forces the block up for its brief rebuild even when
        // the kill switch is off, so the deliberate ~2s blip can't leak.
        val forceBlock = intent.getBooleanExtra(EXTRA_FORCE_BLOCK, false)
        Log.i(TAG, "Switch/reconnect teardown — holding block (ks=$isKillSwitchEnabled force=$forceBlock)")
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        stopNotificationTicker()
        // Ensure fail-closed: if the block should be up but no blocking interface is
        // yet (e.g. a user-initiated switch from a healthy tunnel — the tunnel fd
        // lives in wg-go, not vpnInterface), arm it now. On an auto-reconnect the
        // blocking interface is already up from TunnelMonitor.onUnexpectedExit, so
        // this is a no-op there. establish() for the new tunnel supersedes it.
        if ((isKillSwitchEnabled || forceBlock) && vpnInterface == null) {
            if (activateKillSwitch() == BlockArm.FAILED && isKillSwitchEnabled) {
                // The user's kill switch could not hold the rebuild window.
                // Not an Error: VpnManager is waiting for the Disconnected
                // below to send the rebuild, and an Error here would start its
                // recovery in parallel with that dial. So the alert alone, now;
                // the rebuild then either connects (which withdraws it) or
                // fails through failSetup, whose own block attempt publishes
                // the failure as the session's Error. (A forced block for a
                // fail-open user's settings blip is not a promise they relied
                // on, so it raises nothing.)
                postKillSwitchAlert(FailureKind.VPN_PERMISSION_REQUIRED)
            }
        } else {
            // wg-go may still be running (user switch from a live tunnel); tear the
            // data plane down but keep the interface (blocking, if armed) up.
            cleanupTunnelDataPlane()
        }
        cleanupStealthAndQuantum()
        activeConfig = null
        _connectedServerFlow.value = null
        _connectedSinceFlow.value = 0L
        _rxBytesFlow.value = 0L; _txBytesFlow.value = 0L; _publicIpFlow.value = null
        _stealthActiveFlow.value = false; _quantumActiveFlow.value = false
        // Report Disconnected so VpnManager's bounded wait proceeds to the /connect
        // + ACTION_START rebuild. The blocking interface (killSwitchActive) stays up.
        updateState(VpnState.Disconnected)
    }

    // ── The physical networks under the tunnel ─────────────────────

    /**
     * Track the physical networks (NOT_VPN + INTERNET) while [handle] is the
     * live tunnel; see [underlyingNetworkCallback] for why this is not the
     * default-network callback any more and why it neither re-protects nor
     * declares underlying networks.
     */
    private fun registerUnderlyingNetworkCallback(handle: Int) {
        unregisterUnderlyingNetworkCallback()
        // `as? ConnectivityManager ?: return` folded a missing manager into
        // exactly the outcome the catch below reports — no callback — but
        // silently. Same branch, same consequence, so the same channel: an
        // elvis here hid the twin of a reported failure.
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            FaultReporter.report(
                FaultReporter.PATH_TUNNEL,
                "network_callback_no_manager",
                "ConnectivityManager unavailable — network loss will not be noticed until the stall check",
            )
            return
        }
        underlyingNetworks.clear()
        underlyingMissingSince = SystemClock.elapsedRealtime()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (tunnelHandle != handle) return
                underlyingNetworks.add(network)
                underlyingMissingSince = 0L
                // A new path is also a moment to prove the peer is still
                // registered (A1-017): VpnManager beats immediately.
                _wakeFlow.tryEmit(Unit)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (tunnelHandle != handle) return
                strictPrivateDns[network] = linkProperties.privateDnsServerName != null
                _privateDnsStrictFlow.value = strictPrivateDns.values.any { it }
            }

            override fun onLost(network: Network) {
                if (tunnelHandle != handle) return
                underlyingNetworks.remove(network)
                strictPrivateDns.remove(network)
                _privateDnsStrictFlow.value = strictPrivateDns.values.any { it }
                // Not an error by itself — a roam usually brings the next
                // network within a second. TunnelMonitor declares the tunnel
                // dead only when NONE has come back for a while.
                if (underlyingNetworks.isEmpty()) underlyingMissingSince = SystemClock.elapsedRealtime()
            }
        }
        try {
            cm.registerNetworkCallback(NetworkMonitor.underlyingNetworkRequest(), cb)
            underlyingNetworkCallback = cb
        } catch (e: Exception) {
            // Without the callback a network loss goes unnoticed until the
            // handshake checks catch it, and a roam sends no heartbeat nudge.
            underlyingMissingSince = 0L
            FaultReporter.report(
                FaultReporter.PATH_TUNNEL,
                "network_callback_register_failed",
                "registerNetworkCallback threw — network loss will not be noticed until the stall check",
                e,
            )
        }
    }

    private fun unregisterUnderlyingNetworkCallback() {
        val cb = underlyingNetworkCallback ?: return
        underlyingNetworkCallback = null
        underlyingNetworks.clear()
        underlyingMissingSince = 0L
        strictPrivateDns.clear()
        _privateDnsStrictFlow.value = false
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) { /* already unregistered */ }
    }

    /** Stop Xray Reality and BirdoPQ, zeroing all PQ key material. */
    private fun cleanupStealthAndQuantum() {
        try { XrayManager.stop() } catch (e: Exception) { Log.w(TAG, "Error stopping Xray", e) }
        try { BirdoPqManager.stop() } catch (e: Exception) { Log.w(TAG, "Error stopping BirdoPQ", e) }
    }

    // ── Widget ───────────────────────────────────────────────────

    private fun updateWidgetState(connected: Boolean, serverName: String?) {
        try {
            getSharedPreferences("birdo_widget", MODE_PRIVATE).edit {
                putBoolean("vpn_connected", connected)
                putString("server_name", serverName)
            }
            // The application context and a process-lifetime scope: the
            // refresh must outlive this service instance (onDestroy calls this
            // on its way out) without holding the instance itself.
            val appCtx = applicationContext
            widgetScope.launch {
                try {
                    app.birdo.vpn.widget.BirdoWidget().updateAll(appCtx)
                } catch (e: Exception) {
                    Log.w(TAG, "Glance widget update failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Widget state update failed", e)
        }
    }
}

/** What [BirdoVpnService]'s kill-switch arm achieved. */
internal enum class BlockArm {
    /** The block is up. */
    ARMED,

    /** establish() refused or threw, the retry included: traffic is NOT blocked. */
    FAILED,

    /**
     * onDestroy began before or during the attempts, so nothing was (or may
     * be) established (A1-012). A teardown, not a failure of the control.
     */
    SERVICE_GONE,
}

// ── VPN State Sealed Class ──────────────────────────────────────

sealed class VpnState {
    data object Disconnected : VpnState()
    data object Connecting : VpnState()
    /** Establishing stealth tunnel (Xray Reality). */
    data object StealthConnecting : VpnState()
    data object Connected : VpnState()
    data object Disconnecting : VpnState()
    /**
     * The supervisor is recovering a session: a backoff delay before re-dial
     * [attempt], or — [waitingForNetwork] — holding until the device is online
     * again. Published by VpnManager for the whole wait, so every surface says
     * "Reconnecting…" instead of "Not connected" (A1-008).
     *
     * @param captivePortal while waiting: the network is there but held behind
     *   a sign-in page, so the surfaces say "sign in to this Wi-Fi" rather than
     *   "waiting for a network" (A1-026).
     */
    data class Reconnecting(
        val attempt: Int = 0,
        val waitingForNetwork: Boolean = false,
        val captivePortal: Boolean = false,
    ) : VpnState()
    /** Kill switch is active — all traffic blocked to prevent leaks. */
    data object KillSwitchActive : VpnState()
    /**
     * @param kind why, for recovery: the supervisor retries, stops or asks the
     *   user on this, never on the message text.
     */
    data class Error(val message: String, val kind: FailureKind = FailureKind.TRANSIENT) : VpnState()
}

/**
 * True while a tunnel setup is IN FLIGHT — the window in which a second connect
 * must be refused, a watchdog must be able to fire, and a fail-closed teardown
 * must be armed before anything touches the existing tunnel.
 *
 * WHY THIS EXISTS AS ONE PREDICATE.
 *
 * [VpnState.StealthConnecting] is published for the whole stealth setup —
 * Xray start, the BirdoPQ derivation, WgNative init/turnOn and establish() —
 * and every single consumer that enumerated transitional states listed only
 * `Connecting` and missed it:
 *
 *   - the 30s connect watchdog (BirdoVpnService.connectTimeoutRunnable)
 *   - VpnManager's 45s stuck-connect safety net
 *   - VpnManager.connect() / connectMultiHop()'s fail-closed switchTeardown
 *   - VpnViewModel's three re-entry gates
 *   - HomeScreen's isConnecting, which drives whether Connect is tappable
 *
 * The consequence was a real leak window, not a cosmetic one: during stealth
 * setup the Home button rendered as an idle, tappable "Connect", a second tap
 * passed the ViewModel gate AND the switchTeardown predicate, so no blocking
 * interface was armed — and the second startTunnel's cleanupTunnelDataPlane()
 * then closed the LIVE tunnel's fd, reverting routing to the physical network
 * with nothing blocking for the whole Xray+PQ+establish window of the retry.
 * For a user who explicitly chose fail-closed. Meanwhile neither watchdog could
 * fire, because both guarded on `Connecting`.
 *
 * Enumerating states at each call site is what produced that. Adding a new
 * transitional state must be a ONE-line change here, not a hunt through five
 * files — so use this everywhere instead of matching states inline.
 */
val VpnState.isConnectingPhase: Boolean
    get() = this is VpnState.Connecting || this is VpnState.StealthConnecting
