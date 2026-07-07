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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        /** Interval (ms) for periodic notification updates (timer tick). */
        private const val NOTIF_UPDATE_INTERVAL_MS = 1_000L

        const val ACTION_START = "app.birdo.vpn.START_VPN"
        const val ACTION_STOP = "app.birdo.vpn.STOP_VPN"
        const val ACTION_KILL_SWITCH_BLOCK = "app.birdo.vpn.KILL_SWITCH_BLOCK"

        const val EXTRA_KILL_SWITCH = "kill_switch"
        const val EXTRA_SPLIT_TUNNEL_ENABLED = "split_tunnel_enabled"
        const val EXTRA_SPLIT_TUNNEL_APPS = "split_tunnel_apps"

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
        private var isKillSwitchEnabled: Boolean = true
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
                mainHandler.postDelayed(this, NOTIF_UPDATE_INTERVAL_MS)
            }
        }
    }

    private val connectTimeoutRunnable = Runnable {
        if (currentState is VpnState.Connecting) {
            Log.e(TAG, "Connection timed out after ${CONNECT_TIMEOUT_MS}ms")
            updateState(VpnState.Error("Connection timed out"))
            cleanupTunnel()
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
            ACTION_KILL_SWITCH_BLOCK ->
                notifManager.buildForegroundNotification("Kill Switch — Blocking traffic")
            else -> notifManager.buildForegroundNotification("Connecting…", VpnState.Connecting)
        }
        startForeground(VpnNotificationManager.NOTIFICATION_ID, foregroundNotif)

        when (intent.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP  -> stopTunnel()
            ACTION_KILL_SWITCH_BLOCK -> activateKillSwitch()
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

        tunnelExecutor.execute {
            try {
                startTunnel()
            } catch (t: Throwable) {
                Log.e(TAG, "Unhandled error in tunnel setup", t)
                updateState(VpnState.Error(t.message ?: "Tunnel crashed"))
                mainHandler.post { updateNotification("Connection failed") }
            }
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
            parts.add("🛡️ Stealth + Quantum")
        } else if (stealthActive) {
            parts.add("🛡️ Stealth")
        } else if (quantumActive) {
            parts.add("🔐 Quantum")
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
        mainHandler.postDelayed(notificationTicker, NOTIF_UPDATE_INTERVAL_MS)
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

    private fun activateKillSwitch() {
        Log.i(TAG, "Activating kill switch — blocking all traffic (including STUN/WebRTC)")
        try {
            cleanupTunnel()
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

            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                _killSwitchActiveFlow.value = true
                updateState(VpnState.KillSwitchActive)
                Log.i(TAG, "Kill switch active — all traffic blocked")
                updateNotification("⛔ Kill Switch Active — Traffic blocked")
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
        deactivateKillSwitch()
        cleanupTunnel()

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
                    updateState(VpnState.Error("Stealth engine unavailable"))
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    mainHandler.post { updateNotification("Error: Stealth engine unavailable") }
                    cleanupStealthAndQuantum()
                    if (isKillSwitchEnabled) activateKillSwitch()
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
                    updateState(VpnState.Error("Stealth tunnel failed"))
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    mainHandler.post { updateNotification("Error: Stealth tunnel failed") }
                    cleanupStealthAndQuantum()
                    if (isKillSwitchEnabled) activateKillSwitch()
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
                    updateState(VpnState.Error("Quantum key exchange unavailable"))
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    mainHandler.post { updateNotification("Error: Quantum unavailable") }
                    cleanupStealthAndQuantum()
                    if (isKillSwitchEnabled) activateKillSwitch()
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
                    updateState(VpnState.Error("Quantum key exchange failed"))
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    mainHandler.post { updateNotification("Error: Quantum failed") }
                    cleanupStealthAndQuantum()
                    if (isKillSwitchEnabled) activateKillSwitch()
                    return
                }
            } else {
                _quantumActiveFlow.value = false
            }

            // ── Phase 3: WireGuard Tunnel ───────────────────────────
            // Verify JNI library integrity before loading (mirrors Windows wintun.dll check)
            if (!app.birdo.vpn.utils.NativeLibraryVerifier.verifyLibrary(this, "wg-go")) {
                Log.e(TAG, "wg-go native library integrity check failed")
                updateState(VpnState.Error("Security: library integrity check failed"))
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                mainHandler.post { updateNotification("Error: Security check failed") }
                cleanupStealthAndQuantum()
                if (isKillSwitchEnabled) activateKillSwitch()
                return
            }

            if (!WgNative.init()) {
                Log.e(TAG, "WireGuard native library failed to initialize")
                updateState(VpnState.Error("WireGuard engine unavailable"))
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                mainHandler.post { updateNotification("Error: WireGuard engine unavailable") }
                cleanupStealthAndQuantum()
                if (isKillSwitchEnabled) activateKillSwitch()
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
                updateState(VpnState.Error("VPN permission denied"))
                Log.e(TAG, "Failed to establish VPN interface")
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                mainHandler.post { updateNotification("Error: VPN permission denied") }
                cleanupStealthAndQuantum()
                if (isKillSwitchEnabled) activateKillSwitch()
                return
            }

            val tunFd = vpnFd.detachFd()
            Log.i(TAG, "VPN interface established, fd=$tunFd")

            val configString = wgConfig.toWgUserspaceString()
            val handle = WgNative.turnOn("birdo0", tunFd, configString)
            if (handle < 0) {
                Log.e(TAG, "wgTurnOn failed with code: $handle")
                try { ParcelFileDescriptor.adoptFd(tunFd).close() } catch (_: Exception) {}
                updateState(VpnState.Error("WireGuard tunnel failed to start"))
                mainHandler.removeCallbacks(connectTimeoutRunnable)
                mainHandler.post { updateNotification("Error: WireGuard tunnel failed") }
                cleanupStealthAndQuantum()
                if (isKillSwitchEnabled) activateKillSwitch()
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

            updateState(VpnState.Connected)
            _connectedServerFlow.value = config.serverNode?.name ?: "Unknown"
            _connectedSinceFlow.value = System.currentTimeMillis()
            _rxBytesFlow.value = 0L; _txBytesFlow.value = 0L; _publicIpFlow.value = null

            updateWidgetState(true, connectedServer)
            extractServerIp()
            mainHandler.removeCallbacks(connectTimeoutRunnable)
            mainHandler.post {
                updateNotification(buildConnectedText())
                startNotificationTicker()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tunnel", e)
            updateState(VpnState.Error(e.message ?: "Tunnel failed"))
            cleanupTunnel()
            cleanupStealthAndQuantum()
            mainHandler.removeCallbacks(connectTimeoutRunnable)
            mainHandler.post { updateNotification("Connection failed: ${e.message ?: "Unknown error"}") }
            if (isKillSwitchEnabled) activateKillSwitch()
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
            for (cidr in config.allowedIps ?: listOf("0.0.0.0/0", "::/0")) {
                try {
                    val parts = cidr.split("/")
                    val addr = parts[0]
                    val prefix = if (parts.size > 1) parts[1].toInt() else
                        if (cidr.contains(":")) 128 else 32
                    builder.addRoute(addr, prefix)
                } catch (e: Exception) { Log.w(TAG, "Invalid route: $cidr — ${e.message}") }
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
            isAlive = { currentState == VpnState.Connected && tunnelHandle == handle },
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
                updateState(VpnState.Error("Connection lost — reconnecting…"))
            },
        ).also { it.start() }
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

    private fun cleanupTunnel() {
        unregisterDefaultNetworkCallback()
        tunnelMonitor?.stop()
        tunnelMonitor = null
        if (tunnelHandle >= 0) {
            Log.i(TAG, "Turning off WireGuard tunnel, handle=$tunnelHandle")
            WgNative.turnOff(tunnelHandle)
            tunnelHandle = -1
        }
        try { vpnInterface?.close() } catch (e: Exception) { Log.w(TAG, "Error closing VPN", e) }
        vpnInterface = null
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
