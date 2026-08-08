package app.birdo.vpn.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.utils.RootDetector
import com.wireguard.config.*
import com.wireguard.crypto.Key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.glance.appwidget.updateAll
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
 */
class BirdoVpnService : VpnService() {

    companion object {
        private const val TAG = "BirdoVPN"
        /** Max time (ms) to allow tunnel setup before forcing an error. */
        private const val CONNECT_TIMEOUT_MS = 30_000L
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

        const val EXTRA_KILL_SWITCH = "kill_switch"
        const val EXTRA_SPLIT_TUNNEL_ENABLED = "split_tunnel_enabled"
        const val EXTRA_SPLIT_TUNNEL_APPS = "split_tunnel_apps"
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

        private val _quantumActiveFlow = MutableStateFlow(false)
        /** Whether the current connection is using Rosenpass PQ-PSK */
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

        // FIX-2-12: Reactive state flow replaces 1-second polling.
        // VpnManager collects this flow to receive state changes immediately.
        private val _stateFlow = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val stateFlow: StateFlow<VpnState> = _stateFlow.asStateFlow()

        /**
         * FIX-2-12: Update VPN state atomically — sets both the volatile field
         * (for backward compat / quick reads) and the StateFlow (for reactive
         * collection in VpnManager).
         */
        private fun updateState(newState: VpnState) {
            currentState = newState
            _stateFlow.value = newState
        }

        @Volatile private var activeConfig: ConnectResponse? = null
        // @Volatile: written on the main thread by handleUpdateSettings (live
        // settings push) and read on the tunnel executor by the drop handler.
        @Volatile private var isKillSwitchEnabled: Boolean = true
        // True while startTunnel() runs on the tunnel executor. Set on the main
        // thread in handleStart before the executor hand-off and cleared in its
        // finally; handleUpdateSettings reads it to avoid releasing the block
        // while an establish() is in flight (the one unsafe window).
        @Volatile private var tunnelSetupInProgress = false
        private var isSplitTunnelingEnabled: Boolean = false
        private var splitTunnelAppList: Set<String> = emptySet()

        fun setConfig(config: ConnectResponse) { activeConfig = config }
    }

    // ── Dependencies (lazy to avoid init-order crashes) ──────────

    private val notifManager by lazy { VpnNotificationManager(this) }
    private val appPrefs: AppPreferences by lazy { AppPreferences(this) }

    // ── Tunnel state ─────────────────────────────────────────────

    /** VPN interface — only held during kill switch. */
    private var vpnInterface: ParcelFileDescriptor? = null
    /** wg-go tunnel handle (>= 0 when tunnel is active). */
    private var tunnelHandle: Int = -1
    /** Monitors the tunnel and re-protects sockets. */
    private var tunnelMonitor: TunnelMonitor? = null

    /**
     * Default-network callback — fires when the OS swaps the underlying
     * transport (Wi-Fi ↔ cellular ↔ ethernet). On every change we:
     *   1. Tell the framework which network actually carries our tunnel via
     *      [setUnderlyingNetworks] so battery / data attribution is correct.
     *   2. Immediately re-[protect] the wg-go UDP socket so it binds to the
     *      new transport instead of waiting for the 5s [TunnelMonitor] tick —
     *      makes Wi-Fi→cellular handover effectively seamless.
     */
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

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

    // ── Periodic runnables ───────────────────────────────────────

    private val notificationTicker = object : Runnable {
        override fun run() {
            if (currentState is VpnState.Connected) {
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
                            if (currentState is VpnState.Connected) {
                                updateNotification(buildConnectedText())
                            }
                        }
                    }
                } else {
                    // A read is still in flight; just refresh the notification with
                    // the last-known stats so the timer/uptime still advances.
                    updateNotification(buildConnectedText())
                }
                mainHandler.postDelayed(this, notifTickIntervalMs())
            }
        }
    }

    private val connectTimeoutRunnable = Runnable {
        if (currentState is VpnState.Connecting) {
            Log.e(TAG, "Connection timed out after ${CONNECT_TIMEOUT_MS}ms")
            // Fail closed on timeout, matching every startTunnel failure path.
            // The old cleanupTunnel() closed the blocking vpnInterface with NO
            // re-arm, so a >30s stall during a reconnect (e.g. PQ key exchange
            // wedged in a dead zone) dropped the kill-switch block and leaked
            // cleartext + DNS until the next reconnect re-armed it. Tear down the
            // stalled wg-go setup but keep traffic blocked: activateKillSwitch()
            // supersedes any stale/held interface with a fresh block. Only fully
            // release (fail open) when the user has the kill switch OFF.
            //
            // BLOCK FIRST, THEN PUBLISH Error — see the ordering contract on
            // [activateKillSwitch].
            if (isKillSwitchEnabled) {
                cleanupTunnelDataPlane()
                activateKillSwitch()
            } else {
                cleanupTunnel()
            }
            updateState(VpnState.Error("Connection timed out"))
            updateNotification("Connection timed out")
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        notifManager.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null action means the SYSTEM restarted us after an OOM kill
        // (START_STICKY redelivery), not an app request — every real start sets
        // an explicit ACTION_* (see VpnManager). The original START intent's
        // extras (tunnel config + the user's VpnService consent) are gone, so we
        // can't re-establish. Satisfy the foreground-start requirement, then stop
        // cleanly instead of parking in a fake "Connecting…" foreground state,
        // and don't ask to be restarted again.
        if (intent?.action == null) {
            Log.i(TAG, "onStartCommand with null action (system restart) — stopping cleanly")
            // The tunnel is down (the OS killed and is restarting us). The widget
            // pref survives process death, so without this the home-screen widget
            // keeps showing a green "Protected" for a VPN that is no longer up.
            updateWidgetState(false, null)
            startForeground(
                VpnNotificationManager.NOTIFICATION_ID,
                notifManager.buildForegroundNotification("Disconnected"),
            )
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 12+ requires startForeground() within ~5s of EVERY
        // startForegroundService() call (regardless of action) or the app is
        // killed with ForegroundServiceDidNotStartInTimeException. The STOP and
        // unknown branches previously didn't, which intermittently crashed the
        // app on a server switch (rapid STOP→START). Satisfy it up front for
        // every start, then dispatch.
        val foregroundNotif = when (intent.action) {
            ACTION_STOP -> notifManager.buildForegroundNotification("Disconnecting…")
            ACTION_SWITCH_TEARDOWN ->
                notifManager.buildForegroundNotification("Reconnecting…", VpnState.Connecting)
            ACTION_KILL_SWITCH_BLOCK ->
                notifManager.buildForegroundNotification("Kill Switch — Blocking traffic")
            // A settings push must not flash "Connecting…" over a healthy
            // session — keep the notification truthful for the actual state.
            ACTION_UPDATE_SETTINGS -> notifManager.buildForegroundNotification(
                when {
                    killSwitchActive -> "Kill Switch Active — Traffic blocked"
                    currentState is VpnState.Connected -> buildConnectedText()
                    else -> "Updating settings…"
                },
                currentState,
            )
            else -> notifManager.buildForegroundNotification("Connecting…", VpnState.Connecting)
        }
        startForeground(VpnNotificationManager.NOTIFICATION_ID, foregroundNotif)

        when (intent.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP  -> stopTunnel()
            ACTION_SWITCH_TEARDOWN -> switchTeardown(intent)
            ACTION_KILL_SWITCH_BLOCK -> activateKillSwitch()
            ACTION_UPDATE_SETTINGS -> handleUpdateSettings(intent)
        }
        return START_STICKY
    }

    private fun handleStart(intent: Intent) {
        notifManager.cancelDisconnected()

        isKillSwitchEnabled = intent.getBooleanExtra(EXTRA_KILL_SWITCH, true)
        isSplitTunnelingEnabled = intent.getBooleanExtra(EXTRA_SPLIT_TUNNEL_ENABLED, false)
        splitTunnelAppList = intent.getStringArrayExtra(EXTRA_SPLIT_TUNNEL_APPS)
            ?.toSet() ?: emptySet()

        // (startForeground already called in onStartCommand for every action.)

        mainHandler.removeCallbacks(connectTimeoutRunnable)
        mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)

        // Mark tunnel setup in-flight on the MAIN thread (serialized with
        // handleUpdateSettings) BEFORE handing off to the executor, so a
        // concurrent kill-switch-off toggle never releases the block while
        // startTunnel() is mid-establish. Cleared in the executor's finally.
        tunnelSetupInProgress = true
        tunnelExecutor.execute {
            try {
                startTunnel()
            } catch (t: Throwable) {
                Log.e(TAG, "Unhandled error in tunnel setup", t)
                // startTunnel's own catch only covers Exception; a Throwable that
                // escapes it left the tunnel half-built and the user unprotected
                // with no block. Fail closed here too, before publishing Error.
                if (isKillSwitchEnabled) {
                    cleanupTunnelDataPlane()
                    activateKillSwitch()
                }
                updateState(VpnState.Error(t.message ?: "Tunnel crashed"))
                mainHandler.post { updateNotification("Connection failed") }
            } finally {
                // Deferred-release pickup: if the user turned the kill switch OFF
                // WHILE this establish was in flight, handleUpdateSettings couldn't
                // release the block (the tunnelSetupInProgress gate deferred it).
                // Now that setup is done and — if it failed — the block is still
                // up, honour fail-open here.
                //
                // CLEAR THE GATE FIRST, then re-read isKillSwitchEnabled. Doing the
                // release check before clearing the gate was a lost update: a
                // toggle landing between the check and the clear was dropped by
                // BOTH this finally (read stale `true`) and handleUpdateSettings
                // (saw the gate still set → deferred to this finally, which had
                // already decided). Clearing first makes the two orderings form an
                // impossible cycle, so whichever handler runs last observes the
                // toggle and releases. A double deactivateKillSwitch is idempotent.
                tunnelSetupInProgress = false
                if (!isKillSwitchEnabled && killSwitchActive &&
                    currentState !is VpnState.Connected
                ) {
                    deactivateKillSwitch()
                    mainHandler.post { updateNotification("Reconnecting…") }
                }
            }
        }
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
     * rebuild — so that stopTunnel() raced an in-flight startTunnel() on the
     * tunnel executor and could strand a destroyed service claiming "Connected"
     * over live cleartext. Flag-only is race-free. A user who wants out of an
     * active block uses Disconnect (which is a clean, ordered teardown).
     */
    private fun handleUpdateSettings(intent: Intent) {
        isKillSwitchEnabled = intent.getBooleanExtra(EXTRA_KILL_SWITCH, isKillSwitchEnabled)
        Log.i(TAG, "Runtime settings update (flag-only): killSwitchEnabled=$isKillSwitchEnabled")

        // Kill switch turned OFF while it's actively blocking a DEAD tunnel:
        // honour fail-open by releasing the block. Safe whenever no establish()
        // is in flight — gate on `!tunnelSetupInProgress` (executor idle) rather
        // than on specific states, because the block also lingers in Disconnected
        // during an auto-reconnect BACKOFF (switchTeardown keeps it up, the next
        // /connect hasn't dispatched ACTION_START yet). currentState is never
        // Connected here (killSwitchActive is cleared on connect success).
        if (!isKillSwitchEnabled && killSwitchActive && !tunnelSetupInProgress) {
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
                // Error / Disconnected (mid auto-reconnect): block released, let
                // VpnManager's reconnect continue fail-open.
                mainHandler.post { updateNotification("Reconnecting…") }
            }
            return
        }

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
            mainHandler.post { updateNotification(buildConnectedText()) }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked")
        deactivateKillSwitch()
        activeConfig = null
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopNotificationTicker()
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        // The tunnel dies with the service, so the widget's "Protected" must
        // too. onDestroy is reached on paths that never go through stopTunnel
        // (handleUpdateSettings' stale-push stopSelf, an OOM kill, a force-stop
        // that runs it), and none of those reset the flag.
        updateWidgetState(false, null)
        cleanupTunnel()
        activeConfig = null
        tunnelExecutor.shutdownNow()
        statsExecutor.shutdownNow()
        super.onDestroy()
    }

    // ── Notification helpers (delegate to VpnNotificationManager) ─

    /** Build a one-line connected status string from current state + prefs. */
    private fun buildConnectedText(): String {
        val parts = mutableListOf<String>()
        // Show protection indicators
        if (stealthActive && quantumActive) {
            parts.add("Stealth + Quantum")
        } else if (stealthActive) {
            parts.add("Stealth")
        } else if (quantumActive) {
            parts.add("Quantum")
        }
        if (appPrefs.showLocationInNotification) {
            parts.add(connectedServer ?: "Server")
        }
        if (connectedSince > 0) {
            val secs = (System.currentTimeMillis() - connectedSince) / 1000
            val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
            parts.add(
                if (h > 0) String.format("%d:%02d:%02d", h, m, s)
                else String.format("%02d:%02d", m, s)
            )
        }
        if (appPrefs.showIpInNotification) {
            val ip = publicIp ?: activeConfig?.assignedIp
            if (!ip.isNullOrBlank()) parts.add(ip)
        }
        return if (parts.isEmpty()) "Protected" else parts.joinToString("  ·  ")
    }

    private fun updateNotification(status: String) {
        notifManager.update(
            notifManager.buildForegroundNotification(
                status = status,
                state = currentState,
                connectedSince = connectedSince,
                killSwitchActive = killSwitchActive,
                killSwitchEnabled = isKillSwitchEnabled,
                splitTunnelingEnabled = isSplitTunnelingEnabled,
                splitTunnelAppCount = splitTunnelAppList.size,
                rxBytes = rxBytes,
                txBytes = txBytes,
            )
        )
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
     * Extract the VPN server's public IP from the endpoint.
     * Format: "ip:port", "[ipv6]:port", or "hostname:port".
     */
    private fun extractServerIp() {
        val endpoint = activeConfig?.endpoint ?: return
        val host = if (endpoint.startsWith("[")) {
            endpoint.substringAfter("[").substringBefore("]")
        } else {
            endpoint.substringBeforeLast(":")
        }
        if (host.isBlank()) return

        val ipPattern = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
        if (ipPattern.matches(host)) {
            _publicIpFlow.value = host
            if (BuildConfig.DEBUG) Log.i(TAG, "VPN server IP: $host (from endpoint)")
            return
        }

        // Hostname — resolve on background thread
        tunnelExecutor.execute {
            try {
                val resolvedIp = InetAddress.getByName(host).hostAddress
                if (!resolvedIp.isNullOrBlank()) {
                    _publicIpFlow.value = resolvedIp
                    if (BuildConfig.DEBUG) Log.i(TAG, "VPN server IP: $resolvedIp (resolved from $host)")
                    mainHandler.post { updateNotification(buildConnectedText()) }
                }
            } catch (e: Exception) {
                _publicIpFlow.value = host
                Log.w(TAG, "Could not resolve $host, using hostname", e)
            }
        }
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
     */
    private fun activateKillSwitch() {
        Log.i(TAG, "Activating kill switch — blocking all traffic (including STUN/WebRTC)")
        try {
            // Tear down wg-go / monitor / callbacks but KEEP the current interface
            // (if any) up: establish() below atomically supersedes it, so there is
            // no cleartext gap when re-arming over an existing blocking interface
            // (e.g. a failed reconnect attempt re-arming for the next backoff).
            val stale = vpnInterface
            cleanupTunnelDataPlane()
            val builder = Builder()
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
                .addDisallowedApplication(packageName)

            val established = builder.establish()
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
                updateNotification("Kill Switch Active — Traffic blocked")
            } else {
                // establish() failed (e.g. permission revoked) — don't hold a dead fd.
                if (stale != null) { try { stale.close() } catch (_: Exception) {} }
                vpnInterface = null
                _killSwitchActiveFlow.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to activate kill switch", e)
            _killSwitchActiveFlow.value = false
        }
    }

    private fun deactivateKillSwitch() {
        _killSwitchActiveFlow.value = false
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        Log.i(TAG, "Kill switch deactivated")
    }

    // ── Tunnel Management ───────────────────────────────────────

    private fun startTunnel() {
        val config = activeConfig
        if (config == null || config.privateKey == null ||
            config.serverPublicKey == null || config.endpoint == null ||
            config.assignedIp == null
        ) {
            Log.e(TAG, "No VPN config available or config is incomplete")
            updateState(VpnState.Error("No VPN configuration"))
            mainHandler.removeCallbacks(connectTimeoutRunnable)
            mainHandler.post { updateNotification("Error: No VPN configuration") }
            return
        }

        // H-06 FIX: Reject tunnel establishment if debugger is attached in release.
        // A debugger can extract WireGuard private keys from memory.
        if (!BuildConfig.DEBUG && RootDetector.isDebuggerConnected()) {
            Log.e(TAG, "Debugger detected — refusing to start tunnel with key material in memory")
            updateState(VpnState.Error("Security check failed"))
            mainHandler.removeCallbacks(connectTimeoutRunnable)
            mainHandler.post { updateNotification("Error: Security check failed") }
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
            Log.e(TAG, "Quantum protection requested but not granted by the server — refusing to connect")
            cleanupStealthAndQuantum()
            if (isKillSwitchEnabled) activateKillSwitch()
            updateState(
                VpnState.Error(
                    "Quantum protection was requested but the server did not enable it. " +
                        "Not connecting, because that would use weaker encryption than shown. " +
                        "Try again, or turn off Quantum Protection in Settings."
                )
            )
            return
        }
        if (appPrefs.stealthModeEnabled && !config.stealthEnabled) {
            Log.e(TAG, "Stealth mode requested but not granted by the server — refusing to connect")
            cleanupStealthAndQuantum()
            if (isKillSwitchEnabled) activateKillSwitch()
            updateState(
                VpnState.Error(
                    "Stealth mode was requested but the server did not enable it. " +
                        "Not connecting, because traffic would not be disguised as shown. " +
                        "Try again, or turn off Stealth Mode in Settings."
                )
            )
            return
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
                    Log.e(TAG, "Stealth mode requested but Xray runtime is unavailable")
                    cleanupStealthAndQuantum()
                    if (isKillSwitchEnabled) activateKillSwitch()
                    updateState(VpnState.Error("Stealth engine unavailable"))
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    mainHandler.post { updateNotification("Error: Stealth engine unavailable") }
                    return
                }

                XrayManager.setVpnService(this)
                val xrayStarted = runBlocking(Dispatchers.IO) {
                    XrayManager.start(applicationContext, config)
                }

                if (xrayStarted) {
                    val xrayPort = XrayManager.getLocalPort()
                    stealthEndpointOverride = "127.0.0.1:$xrayPort"
                    _stealthActiveFlow.value = true
                    Log.i(TAG, "Xray Reality active — WireGuard will connect via 127.0.0.1:$xrayPort")
                } else {
                    Log.e(TAG, "Xray failed to start — refusing direct fallback because stealth was requested")
                    _stealthActiveFlow.value = false
                    cleanupStealthAndQuantum()
                    if (isKillSwitchEnabled) activateKillSwitch()
                    updateState(VpnState.Error("Stealth tunnel failed"))
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    mainHandler.post { updateNotification("Error: Stealth tunnel failed") }
                    return
                }
            } else {
                _stealthActiveFlow.value = false
            }

            // ── Phase 2: Quantum Protection (Rosenpass PQ-PSK) ──────
            // When quantum protection is enabled and the server provides a
            // Rosenpass public key, perform a post-quantum key exchange to
            // derive a 32-byte PSK. This PSK is injected as WireGuard's
            // PresharedKey field, providing post-quantum security even if
            // Curve25519 is broken by a future quantum computer.
            var quantumPsk: String? = null
            if (config.quantumEnabled) {
                if (config.rosenpassPublicKey == null || config.rosenpassEndpoint == null) {
                    Log.e(TAG, "Quantum protection was enabled by server but PQ payload is missing")
                    _quantumActiveFlow.value = false
                    cleanupStealthAndQuantum()
                    if (isKillSwitchEnabled) activateKillSwitch()
                    updateState(VpnState.Error("Quantum key exchange unavailable"))
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    mainHandler.post { updateNotification("Error: Quantum unavailable") }
                    return
                }

                Log.i(TAG, "Quantum protection enabled — performing PQ key exchange")
                mainHandler.post { updateNotification("Quantum key exchange…") }

                quantumPsk = runBlocking(Dispatchers.IO) {
                    RosenpassManager.performKeyExchange(applicationContext, config)
                }

                if (quantumPsk != null) {
                    _quantumActiveFlow.value = true
                    Log.i(TAG, "PQ-PSK derived — quantum protection active")
                } else {
                    _quantumActiveFlow.value = false
                    Log.e(TAG, "PQ key exchange failed — refusing non-PQ fallback")
                    cleanupStealthAndQuantum()
                    if (isKillSwitchEnabled) activateKillSwitch()
                    updateState(VpnState.Error("Quantum key exchange failed"))
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    mainHandler.post { updateNotification("Error: Quantum failed") }
                    return
                }
            } else {
                _quantumActiveFlow.value = false
            }

            // ── Phase 3: WireGuard Tunnel ───────────────────────────
            // Verify JNI library integrity before loading (mirrors Windows wintun.dll check)
            if (!app.birdo.vpn.utils.NativeLibraryVerifier.verifyLibrary(this, "wg-go")) {
                Log.e(TAG, "wg-go native library integrity check failed")
                cleanupStealthAndQuantum()
                if (isKillSwitchEnabled) activateKillSwitch()
                updateState(VpnState.Error("Security: library integrity check failed"))
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                mainHandler.post { updateNotification("Error: Security check failed") }
                return
            }

            if (!WgNative.init()) {
                Log.e(TAG, "WireGuard native library failed to initialize")
                cleanupStealthAndQuantum()
                if (isKillSwitchEnabled) activateKillSwitch()
                updateState(VpnState.Error("WireGuard engine unavailable"))
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                mainHandler.post { updateNotification("Error: WireGuard engine unavailable") }
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
            val vpnFd = buildVpnInterface(effectiveConfig) ?: run {
                Log.e(TAG, "Failed to establish VPN interface")
                cleanupStealthAndQuantum()
                if (isKillSwitchEnabled) activateKillSwitch()
                updateState(VpnState.Error("VPN permission denied"))
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                mainHandler.post { updateNotification("Error: VPN permission denied") }
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
                Log.e(TAG, "wgTurnOn failed with code: $handle")
                try { ParcelFileDescriptor.adoptFd(tunFd).close() } catch (_: Exception) {}
                cleanupStealthAndQuantum()
                if (isKillSwitchEnabled) activateKillSwitch()
                updateState(VpnState.Error("WireGuard tunnel failed to start"))
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                mainHandler.post { updateNotification("Error: WireGuard tunnel failed") }
                return
            }

            tunnelHandle = handle
            Log.i(TAG, "WireGuard tunnel started")
            if (BuildConfig.DEBUG) {
                Log.i(TAG, "VPN connected — ${config.assignedIp ?: "?"} → ${effectiveConfig.endpoint}")
            }
            Log.i(TAG, "Kill switch: $isKillSwitchEnabled | Split tunnel: $isSplitTunnelingEnabled (${splitTunnelAppList.size} apps)")
            Log.i(TAG, "Stealth: $stealthActive | Quantum: $quantumActive (mode=${RosenpassManager.modeFlow.value})")

            // No PSK rekey loop in BirdoPQ v1: wireguard-android doesn't
            // expose wgSetConfig so live PSK swap isn't possible. Each
            // /connect already derives a fresh per-session PQ-PSK via
            // ML-KEM-1024 decapsulation, which gives the same HNDL guarantee
            // — see RosenpassManager kdoc + native/ROADMAP.md.

            protectTunnelSockets(handle)
            startTunnelMonitor(handle)
            registerDefaultNetworkCallback(handle)

            // SEC: wg-go has consumed the private key from configString; wipe it
            // from the in-memory ConnectResponse so a heap dump or later code
            // path can't recover it. The kernel/wg-go now owns the key.
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
            startTransportProbe(handle, onStealthTransport = stealthEndpointOverride != null)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel", e)
            // Match the connect-watchdog idiom: keep the blocking interface up
            // across the re-arm for a fail-closed user (cleanupTunnel() closed it
            // first, leaving a cleartext window until activateKillSwitch()
            // re-established one), and only fully release when the kill switch is
            // OFF. Block first, publish Error last.
            if (isKillSwitchEnabled) {
                cleanupTunnelDataPlane()
                cleanupStealthAndQuantum()
                activateKillSwitch()
            } else {
                cleanupTunnel()
                cleanupStealthAndQuantum()
            }
            updateState(VpnState.Error(e.message ?: "Tunnel failed"))
            mainHandler.removeCallbacks(connectTimeoutRunnable)
            mainHandler.post { updateNotification("Connection failed: ${e.message ?: "Unknown error"}") }
        }
    }

    /**
     * Build the Android VPN interface via [Builder].
     *
     * Configures MTU, address, DNS, routes, and split-tunneling exclusions.
     * Returns the established [ParcelFileDescriptor] or `null` if the user
     * has not granted VPN permission.
     */
    private fun buildVpnInterface(config: ConnectResponse): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("BirdoVPN")
            .setBlocking(false)

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

        // DNS
        for (dns in resolveDnsServers(config)) {
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
                    builder.addRoute(parts[0], parts[1].toInt())
                }
                // Still route IPv6 through VPN for leak protection
                builder.addRoute("::", 0)
                Log.i(TAG, "Local network sharing enabled — LAN ranges excluded from VPN routes")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure LAN exclusion routes, falling back to full route: ${e.message}")
                builder.addRoute("0.0.0.0", 0)
                builder.addRoute("::", 0)
            }
        } else {
            var hasV6Default = false
            for (cidr in config.allowedIps ?: listOf("0.0.0.0/0", "::/0")) {
                try {
                    val parts = cidr.split("/")
                    val addr = parts[0]
                    val prefix = if (parts.size > 1) parts[1].toInt() else
                        if (cidr.contains(":")) 128 else 32
                    builder.addRoute(addr, prefix)
                    if (addr == "::" && prefix == 0) hasV6Default = true
                } catch (e: Exception) { Log.w(TAG, "Invalid route: $cidr — ${e.message}") }
            }
            // Enforce an IPv6 blackhole client-side even when the server omits
            // ::/0 from allowedIps. Our nodes are IPv4-only, so capturing all IPv6
            // into the tunnel drops it instead of letting it egress below the
            // tunnel on the physical adapter (a v6 default on the NIC would
            // otherwise win). Guarded so we never add a duplicate ::/0 (Android
            // throws IllegalArgumentException on duplicate routes).
            if (!hasV6Default) {
                try { builder.addRoute("::", 0) } catch (e: Exception) {
                    Log.w(TAG, "Failed to add IPv6 blackhole route: ${e.message}")
                }
            }
        }

        // Split Tunneling
        builder.addDisallowedApplication(packageName)
        if (isSplitTunnelingEnabled && splitTunnelAppList.isNotEmpty()) {
            for (app in splitTunnelAppList) {
                try {
                    packageManager.getPackageInfo(app, 0)
                    builder.addDisallowedApplication(app)
                } catch (_: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Split tunnel: $app not installed, skipping")
                }
            }
        }

        return builder.establish()
    }

    /** Resolve the DNS server list, preferring user overrides when enabled. */
    private fun resolveDnsServers(config: ConnectResponse): List<String> {
        val fallback = listOf("1.1.1.1", "1.0.0.1")
        if (!appPrefs.customDnsEnabled) {
            val serverDns = config.dns?.filter { isValidDnsAddress(it) } ?: emptyList()
            if (serverDns.isEmpty()) {
                Log.w(TAG, "Server provided no valid DNS servers — falling back to defaults (1.1.1.1)")
            }
            return serverDns.ifEmpty { fallback }
        }
        val custom = buildList {
            val p = appPrefs.customDnsPrimary.trim()
            if (p.isNotBlank() && isValidDnsAddress(p)) add(p)
            val s = appPrefs.customDnsSecondary.trim()
            if (s.isNotBlank() && isValidDnsAddress(s)) add(s)
        }
        if (custom.isEmpty()) {
            Log.w(TAG, "Custom DNS addresses invalid or empty — falling back to defaults")
        }
        return custom.ifEmpty { fallback }
    }

    /**
     * Validate a DNS address string at tunnel-start time.
     * Accepts IPv4 (dotted-quad) and IPv6 (colon-hex). Rejects hostnames,
     * blank strings, and obviously-invalid addresses.
     */
    private fun isValidDnsAddress(address: String): Boolean {
        return try {
            val addr = InetAddress.getByName(address)
            // Reject loopback (127.x.x.x, ::1) — would bypass VPN
            !addr.isLoopbackAddress && !addr.isAnyLocalAddress
        } catch (_: Exception) {
            false
        }
    }

    private fun protectTunnelSockets(handle: Int) {
        Thread({
            try {
                Thread.sleep(100)
                repeat(20) { attempt ->
                    if (tunnelHandle != handle) return@Thread
                    val v4 = WgNative.getSocketV4(handle)
                    if (v4 >= 0) protect(v4)
                    val v6 = WgNative.getSocketV6(handle)
                    if (v6 >= 0) protect(v6)
                    Thread.sleep(if (v4 >= 0 || v6 >= 0) 2000 else 200)
                }
            } catch (_: InterruptedException) { /* shutting down */ }
        }, "birdo-socket-protect").apply { isDaemon = true; start() }
    }

    private fun startTunnelMonitor(handle: Int) {
        tunnelMonitor = TunnelMonitor(
            handle = handle,
            service = this,
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
            onUnexpectedExit = {
                // Fail closed FIRST (block all traffic), THEN hand off to
                // auto-reconnect. Activating the kill switch alone tears wg-go
                // down and latches the state at KillSwitchActive, which
                // VpnManager never treats as a reconnect trigger — so a >3-min
                // stall (subway/flight-mode/dead-zone) left the user stranded
                // with all traffic blocked until a manual reconnect, and wg-go
                // could not self-heal even when connectivity returned. Emitting
                // Error drives VpnManager's existing exponential-backoff
                // reconnect (which re-arms the kill switch per attempt and
                // clears it on a successful connect), matching the desktop
                // client's behaviour on the same drop.
                if (isKillSwitchEnabled) activateKillSwitch()
                // The tunnel is no longer carrying traffic — clear the widget's
                // "Protected" so it doesn't keep asserting a connection through
                // the whole reconnect window (or indefinitely on a permanent
                // stall). Unconditional: even with the kill switch OFF the tunnel
                // is down, so a green widget would be a false safety signal.
                updateWidgetState(false, null)
                updateState(VpnState.Error("Connection lost — reconnecting…"))
            },
        ).also { it.start() }
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
     * stall tunnel setup and risk an ANR.
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
    private fun startTransportProbe(handle: Int, onStealthTransport: Boolean) {
        Thread({
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
                ).await()
            } catch (t: Throwable) {
                // Never strand the connect on an unexpected probe failure: with
                // no evidence either way, fall back to the pre-gate behaviour
                // and let TunnelMonitor's stall detection own the tunnel.
                Log.e(TAG, "Transport probe failed — publishing Connected unverified", t)
                TransportProbe.Result.HANDSHAKE_OK
            }
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
                    // contract on [activateKillSwitch].
                    if (isKillSwitchEnabled) {
                        cleanupTunnelDataPlane()
                        activateKillSwitch()
                    } else {
                        cleanupTunnel()
                    }
                    cleanupStealthAndQuantum()
                    updateState(VpnState.Error("No handshake — this network is blocking the VPN"))
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    mainHandler.post { updateNotification("Error: no handshake") }
                }

                // The tunnel went away while probing (disconnect, switch, kill
                // switch). Whoever tore it down owns the state.
                TransportProbe.Result.ABORTED -> Unit
            }
        }, "birdo-transport-probe").apply { isDaemon = true }.start()
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
            updateNotification(buildConnectedText())
            startNotificationTicker()
        }
    }

    private fun buildWireGuardConfig(response: ConnectResponse): Config {
        return WireGuardConfigBuilder.build(response, appPrefs)
    }

    /**
     * Apply the user's WireGuard port override to the endpoint string.
     * "auto" → keep server-provided port.
     */
    private fun applyPortOverride(endpoint: String): String {
        return WireGuardConfigBuilder.applyPortOverride(endpoint, appPrefs)
    }

    private fun stopTunnel() {
        Log.i(TAG, "Stopping VPN tunnel")
        updateState(VpnState.Disconnecting)
        stopNotificationTicker()
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        deactivateKillSwitch()
        cleanupTunnel()
        cleanupStealthAndQuantum()
        // Clear sensitive config from memory (private keys, etc.)
        activeConfig = null
        updateState(VpnState.Disconnected)
        _connectedServerFlow.value = null
        _connectedSinceFlow.value = 0L
        _rxBytesFlow.value = 0L; _txBytesFlow.value = 0L; _publicIpFlow.value = null
        _stealthActiveFlow.value = false; _quantumActiveFlow.value = false
        updateWidgetState(false, null)
        notifManager.postDisconnectedNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Tear down the data plane (wg-go handle, tunnel monitor, network callbacks)
     * but leave [vpnInterface] alone. Callers that must keep traffic fail-closed
     * across a transition (kill switch, reconnect/switch) use this so the blocking
     * interface stays up until a replacement interface atomically supersedes it.
     */
    private fun cleanupTunnelDataPlane() {
        unregisterDefaultNetworkCallback()
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
            activateKillSwitch()
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

    // ── Roaming (Wi-Fi ↔ Cellular handover) ────────────────────────

    /**
     * Watch the system's *default* network (the one carrying non-VPN traffic)
     * and, on every change, re-bind / re-protect the wg-go UDP socket so the
     * tunnel keeps flowing without a user-visible reconnect.
     */
    private fun registerDefaultNetworkCallback(handle: Int) {
        unregisterDefaultNetworkCallback()
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (tunnelHandle != handle) return
                Log.i(TAG, "Underlying network changed -> $network, reprotecting socket")
                try {
                    @Suppress("DEPRECATION")
                    setUnderlyingNetworks(arrayOf(network))
                } catch (e: Exception) {
                    Log.w(TAG, "setUnderlyingNetworks failed", e)
                }
                reprotectTunnelSockets(handle)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (tunnelHandle != handle) return
                // Transport swap (e.g. Wi-Fi caps -> cellular caps on same Network id)
                reprotectTunnelSockets(handle)
            }

            override fun onLost(network: Network) {
                if (tunnelHandle != handle) return
                // Don't trigger an error — the OS will surface a new default network
                // via onAvailable(). Just clear the underlying-network attribution
                // so the framework knows the previous transport is gone.
                try {
                    @Suppress("DEPRECATION")
                    setUnderlyingNetworks(null)
                } catch (_: Exception) { /* best effort */ }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            defaultNetworkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback failed", e)
        }
    }

    private fun unregisterDefaultNetworkCallback() {
        val cb = defaultNetworkCallback ?: return
        defaultNetworkCallback = null
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) { /* already unregistered */ }
        try {
            @Suppress("DEPRECATION")
            setUnderlyingNetworks(null)
        } catch (_: Exception) { /* not active */ }
    }

    private fun reprotectTunnelSockets(handle: Int) {
        try {
            val v4 = WgNative.getSocketV4(handle)
            if (v4 >= 0) protect(v4)
            val v6 = WgNative.getSocketV6(handle)
            if (v6 >= 0) protect(v6)
        } catch (e: Exception) {
            Log.w(TAG, "reprotectTunnelSockets failed", e)
        }
    }

    /** Stop Xray Reality and Rosenpass, zeroing all PQ key material. */
    private fun cleanupStealthAndQuantum() {
        try { XrayManager.stop() } catch (e: Exception) { Log.w(TAG, "Error stopping Xray", e) }
        try { RosenpassManager.stop() } catch (e: Exception) { Log.w(TAG, "Error stopping Rosenpass", e) }
    }

    // ── Widget ───────────────────────────────────────────────────

    private fun updateWidgetState(connected: Boolean, serverName: String?) {
        try {
            getSharedPreferences("birdo_widget", MODE_PRIVATE).edit()
                .putBoolean("vpn_connected", connected)
                .putString("server_name", serverName)
                .apply()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    app.birdo.vpn.widget.BirdoWidget().updateAll(this@BirdoVpnService)
                } catch (e: Exception) {
                    Log.w(TAG, "Glance widget update failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Widget state update failed", e)
        }
    }
}

// ── VPN State Sealed Class ──────────────────────────────────────

sealed class VpnState {
    data object Disconnected : VpnState()
    data object Connecting : VpnState()
    /** Authenticating with the API server (pre-tunnel). */
    data object Authenticating : VpnState()
    /** Establishing stealth tunnel (Xray Reality). */
    data object StealthConnecting : VpnState()
    data object Connected : VpnState()
    data object Disconnecting : VpnState()
    /** Automatic reconnection in progress after unexpected disconnect. */
    data class Reconnecting(val attempt: Int = 0) : VpnState()
    /** Kill switch is active — all traffic blocked to prevent leaks. */
    data object KillSwitchActive : VpnState()
    data class Error(val message: String) : VpnState()
}
