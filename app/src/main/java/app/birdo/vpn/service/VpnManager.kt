package app.birdo.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.model.MultiHopConnectResponse
import app.birdo.vpn.data.network.NetworkMonitor
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level VPN connection manager.
 * Orchestrates API calls, preferences, kill switch, split tunneling,
 * and the VPN service lifecycle.
 */
@Singleton
class VpnManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BirdoRepository,
    private val prefs: AppPreferences,
    private val networkMonitor: NetworkMonitor,
) {
    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _connectedServer = MutableStateFlow<String?>(null)
    val connectedServer: StateFlow<String?> = _connectedServer.asStateFlow()

    private val _connectedSince = MutableStateFlow(0L)
    val connectedSince: StateFlow<Long> = _connectedSince.asStateFlow()

    /** Timestamp when we last entered a transitional state (Connecting/Disconnecting) */
    @Volatile private var transitionStartTime = 0L

    // ── Auto-reconnect with exponential backoff ─────────────────────
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    // ── Heartbeat keepalive ─────────────────────────────────────────
    private var heartbeatJob: Job? = null

    /** Exposed so the UI can show "Reconnecting (attempt 2/5)…" */
    private val _reconnectAttemptFlow = MutableStateFlow(0)
    val reconnectAttemptFlow: StateFlow<Int> = _reconnectAttemptFlow.asStateFlow()

    companion object {
        /** Max time (ms) to guard transitional states before letting service state through */
        private const val TRANSITION_GUARD_MS = 15_000L
        /** Max time (ms) before we force Connecting → Error if service is stuck */
        private const val CONNECT_STUCK_TIMEOUT_MS = 35_000L
        /** Max time (ms) before we force Disconnecting → Disconnected if service is stuck */
        private const val DISCONNECT_STUCK_TIMEOUT_MS = 15_000L
        /** Maximum number of automatic reconnection attempts */
        private const val MAX_RECONNECT_ATTEMPTS = 5
        /** Initial reconnect delay (ms) — doubles each attempt: 2s, 4s, 8s, 16s, 32s */
        private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
        /** Maximum reconnect delay cap (ms) */
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        /** Heartbeat interval (ms) — sends keepalive to backend while connected */
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        /** Key rotation interval: rotate WireGuard keys every ~45 min for forward secrecy */
        private const val KEY_ROTATION_HEARTBEATS = 90 // 90 × 30s = 45 minutes
    }

    // FIX-2-12: Singleton scope for reactive state collection from the service.
    // Replaces 1-second polling — state changes now propagate immediately.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // FIX-2-12: Reactively collect state from BirdoVpnService's StateFlow.
        // Applies the same transition guards as the old syncState() polling, but
        // fires immediately on every state change instead of with ≤1s delay.
        scope.launch {
            BirdoVpnService.stateFlow.collect { serviceState ->
                // Guard each emission so an unexpected exception in the guard
                // logic cannot silently kill this collector — it is the sole
                // pipeline propagating service state to the UI (replaced polling).
                try {
                    applyStateWithGuards(serviceState)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("VpnManager", "State collector failed", e)
                }
            }
        }

        // Auto-reconnect: when VPN drops to Error state and we were previously
        // connected, start exponential backoff reconnection.
        scope.launch {
            _state.collect { vpnState ->
                try {
                    if (vpnState is VpnState.Error && prefs.lastServerId != null) {
                        stopHeartbeat()
                        startAutoReconnect()
                    } else if (vpnState is VpnState.Connected) {
                        cancelAutoReconnect()
                        startHeartbeat()
                    } else if (vpnState is VpnState.Disconnected) {
                        stopHeartbeat()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("VpnManager", "Reconnect collector failed", e)
                }
            }
        }

        // Network-aware reconnect: when connectivity returns after going offline
        // and we have a pending reconnect, trigger immediate attempt.
        scope.launch {
            networkMonitor.isOnline
                .distinctUntilChanged()
                .filter { it } // only react to online transitions
                .collect {
                    try {
                        if (reconnectJob?.isActive == true && _state.value is VpnState.Error) {
                            reconnectJob?.cancel()
                            startAutoReconnect(resetAttempts = false)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("VpnManager", "Network collector failed", e)
                    }
                }
        }
    }

    fun isVpnPermissionGranted(): Boolean = VpnService.prepare(context) == null

    fun getVpnPermissionIntent(): Intent? = VpnService.prepare(context)

    /**
     * Connect to a VPN server.
     * Passes kill switch + split tunneling preferences to the service.
     */
    suspend fun connect(serverId: String): ApiResult<ConnectResponse> {
        // ── Server switch / reconnect: fully tear down the existing tunnel +
        // server-side peer BEFORE establishing the new one. Without this, picking
        // a new server (Germany → Amsterdam) leaves the old peer registered and
        // the local tunnel racing the new one. Each connect must be a clean
        // disconnect → fresh keypair (repository.connectVpn always generates one)
        // → connect cycle, so every session is a new identity.
        val priorState = _state.value
        if (priorState is VpnState.Connected ||
            priorState is VpnState.Connecting ||
            priorState is VpnState.Reconnecting
        ) {
            disconnect()
            // Wait (bounded) for the service to confirm the old tunnel is down so
            // the old keyId is unregistered and wg-go is torn down before we
            // register + bring up the new one.
            var waited = 0
            while (_state.value !is VpnState.Disconnected && waited < 5000) {
                delay(150)
                waited += 150
            }
        }

        _state.value = VpnState.Connecting
        transitionStartTime = System.currentTimeMillis()

        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        // Upload our ML-KEM-1024 client public key when quantum protection is
        // enabled so the server can encapsulate against it (BirdoPQ v1).
        val pqClientPublicKey: String? = if (prefs.quantumProtectionEnabled) {
            RosenpassManager.getClientPublicKeyB64(context) ?: run {
                _state.value = VpnState.Error("Quantum engine unavailable")
                return ApiResult.Error("Quantum engine unavailable")
            }
        } else null

        val result = repository.connectVpn(
            serverNodeId = serverId,
            deviceName = deviceName,
            stealthMode = prefs.stealthModeEnabled,
            quantumProtection = prefs.quantumProtectionEnabled,
            pqClientPublicKey = pqClientPublicKey,
        )

        when (result) {
            is ApiResult.Success -> {
                val config = result.data
                if (!config.success || config.privateKey == null ||
                    config.serverPublicKey == null || config.endpoint == null ||
                    config.assignedIp == null
                ) {
                    _state.value = VpnState.Error(config.message ?: "Invalid server response")
                    return ApiResult.Error(config.message ?: "Invalid server response")
                }

                BirdoVpnService.setConfig(config)

                val intent = Intent(context, BirdoVpnService::class.java).apply {
                    action = BirdoVpnService.ACTION_START
                    putExtra(BirdoVpnService.EXTRA_KILL_SWITCH, prefs.killSwitchEnabled)
                    putExtra(BirdoVpnService.EXTRA_SPLIT_TUNNEL_ENABLED, prefs.splitTunnelingEnabled)
                    putExtra(
                        BirdoVpnService.EXTRA_SPLIT_TUNNEL_APPS,
                        prefs.splitTunnelApps.toTypedArray(),
                    )
                }
                // Guard against ForegroundServiceStartNotAllowedException and the
                // "didn't call startForeground in time" crash, which can fire when
                // a foreground service is (re)started rapidly during a server switch.
                // Turn it into a recoverable error instead of crashing the app.
                try {
                    context.startForegroundService(intent)
                } catch (e: Exception) {
                    android.util.Log.e("VpnManager", "startForegroundService(START) failed", e)
                    _state.value = VpnState.Error("Couldn't start the VPN service — please try again.")
                    return ApiResult.Error("Couldn't start the VPN service: ${e.message}")
                }

                // Don't set Connected here — the service sets currentState = Connected
                // once the tunnel is actually up. syncState() will pick it up.
                // We stay in Connecting until the service confirms.
                _connectedServer.value = config.serverNode?.name ?: "Unknown Server"
                prefs.lastServerId = serverId

                return result
            }
            is ApiResult.Error -> {
                _state.value = VpnState.Error(result.message)
                return result
            }
        }
    }

    /**
     * Connect through an entry and exit server with the same advanced feature
     * contract as single-hop: Stealth and BirdoPQ are requested up front and
     * the service fails closed if the server enables them but the local engine
     * cannot complete setup.
     */
    suspend fun connectMultiHop(
        entryNodeId: String,
        exitNodeId: String,
    ): ApiResult<MultiHopConnectResponse> {
        _state.value = VpnState.Connecting
        transitionStartTime = System.currentTimeMillis()

        val pqClientPublicKey: String? = if (prefs.quantumProtectionEnabled) {
            RosenpassManager.getClientPublicKeyB64(context) ?: run {
                _state.value = VpnState.Error("Quantum engine unavailable")
                return ApiResult.Error("Quantum engine unavailable")
            }
        } else null

        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val result = repository.connectMultiHop(
            entryNodeId = entryNodeId,
            exitNodeId = exitNodeId,
            deviceName = deviceName,
            stealthMode = prefs.stealthModeEnabled,
            quantumProtection = prefs.quantumProtectionEnabled,
            pqClientPublicKey = pqClientPublicKey,
        )

        when (result) {
            is ApiResult.Success -> {
                val config = result.data
                if (!config.success || config.privateKey == null ||
                    config.serverPublicKey == null || config.endpoint == null ||
                    config.assignedIp == null
                ) {
                    _state.value = VpnState.Error(config.message ?: "Invalid multi-hop config")
                    return ApiResult.Error(config.message ?: "Invalid multi-hop config")
                }

                connectWithConfig(config)
                return result
            }
            is ApiResult.Error -> {
                _state.value = VpnState.Error(result.message)
                return result
            }
        }
    }

    /**
     * Connect using a pre-fetched multi-hop configuration.
     * Called from VpnViewModel after repository.connectMultiHop() succeeds.
     */
    fun connectWithConfig(config: MultiHopConnectResponse) {
        if (config.privateKey == null || config.serverPublicKey == null ||
            config.endpoint == null || config.assignedIp == null
        ) {
            _state.value = VpnState.Error(config.message ?: "Invalid multi-hop config")
            return
        }

        _state.value = VpnState.Connecting
        transitionStartTime = System.currentTimeMillis()

        // Convert MultiHopConnectResponse to ConnectResponse for the service
        val connectConfig = ConnectResponse(
            success = config.success,
            message = config.message,
            config = config.config,
            keyId = config.keyId,
            privateKey = config.privateKey,
            publicKey = config.publicKey,
            presharedKey = config.presharedKey,
            assignedIp = config.assignedIp,
            serverPublicKey = config.serverPublicKey,
            endpoint = config.endpoint,
            dns = config.dns,
            allowedIps = config.allowedIps,
            mtu = config.mtu,
            persistentKeepalive = config.persistentKeepalive,
            stealthEnabled = config.stealthEnabled,
            xrayEndpoint = config.xrayEndpoint,
            xrayUuid = config.xrayUuid,
            xrayPublicKey = config.xrayPublicKey,
            xrayShortId = config.xrayShortId,
            xraySni = config.xraySni,
            xrayFlow = config.xrayFlow,
            quantumEnabled = config.quantumEnabled,
            rosenpassPublicKey = config.rosenpassPublicKey,
            rosenpassEndpoint = config.rosenpassEndpoint,
        )

        BirdoVpnService.setConfig(connectConfig)

        val intent = Intent(context, BirdoVpnService::class.java).apply {
            action = BirdoVpnService.ACTION_START
            putExtra(BirdoVpnService.EXTRA_KILL_SWITCH, prefs.killSwitchEnabled)
            putExtra(BirdoVpnService.EXTRA_SPLIT_TUNNEL_ENABLED, prefs.splitTunnelingEnabled)
            putExtra(BirdoVpnService.EXTRA_SPLIT_TUNNEL_APPS, prefs.splitTunnelApps.toTypedArray())
        }
        context.startForegroundService(intent)

        _connectedServer.value = config.multiHop?.let {
            "${it.entryNode.name} → ${it.exitNode.name}"
        } ?: "Multi-Hop"
    }

    /**
     * Quick connect — pick the best online server automatically.
     */
    suspend fun quickConnect(): ApiResult<ConnectResponse> {
        _state.value = VpnState.Connecting
        transitionStartTime = System.currentTimeMillis()

        val serversResult = repository.getServers()
        if (serversResult is ApiResult.Error) {
            _state.value = VpnState.Error(serversResult.message)
            return ApiResult.Error(serversResult.message)
        }

        val servers = (serversResult as ApiResult.Success).data
        val bestServer = servers
            .filter { it.isOnline && !it.isPremium }
            .minByOrNull { it.load }
            ?: servers.firstOrNull { it.isOnline }

        if (bestServer == null) {
            _state.value = VpnState.Error("No servers available")
            return ApiResult.Error("No servers available")
        }

        return connect(bestServer.id)
    }

    /**
     * Disconnect from VPN.
     */
    suspend fun disconnect() {
        cancelAutoReconnect() // User-initiated disconnect — stop any pending reconnect
        _state.value = VpnState.Disconnecting
        transitionStartTime = System.currentTimeMillis()

        val intent = Intent(context, BirdoVpnService::class.java).apply {
            action = BirdoVpnService.ACTION_STOP
        }
        // Use startForegroundService to ensure delivery on Android 12+
        // when the app may be transitioning to background. Guarded so a stop
        // signal during a rapid switch can never crash the app.
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            android.util.Log.e("VpnManager", "startForegroundService(STOP) failed", e)
        }

        // Notify backend (best effort)
        repository.disconnectVpn()

        // Don't set Disconnected here — the service sets currentState = Disconnected
        // after the tunnel is actually stopped. syncState() will pick it up.
        // We stay in Disconnecting until the service confirms.
    }

    /**
     * Toggle VPN — connect or disconnect.
     * Used by Quick Settings Tile.
     */
    suspend fun toggle(): Boolean {
        return if (_state.value is VpnState.Connected) {
            disconnect()
            false
        } else {
            val result = quickConnect()
            result is ApiResult.Success
        }
    }

    /**
     * Sync state from the VPN service.
     *
     * FIX-2-12: Now also called reactively from the init{} StateFlow collector.
     * Kept as a public method for backward compatibility with any remaining
     * callers (e.g. VpnViewModel stats polling for rxBytes/txBytes).
     *
     * @deprecated Prefer collecting [state] StateFlow, which propagates
     *   state changes immediately. This method is retained only for
     *   non-reactive call-sites and will be removed in a future release.
     */
    @Deprecated("Collect state StateFlow instead", ReplaceWith("state"))
    fun syncState() {
        applyStateWithGuards(BirdoVpnService.currentState)
    }

    /**
     * FIX-2-12: Core state transition logic with guards.
     * Extracted from syncState() so both the reactive StateFlow collector and
     * the legacy polling path share the same rules.
     *
     * Guards against race conditions:
     * - When Connecting, don't let stale Disconnected from the service reset us
     * - When Disconnecting, don't let stale Connected from the service reset us
     * Guards expire after 15s to prevent getting stuck (e.g. tunnel fails → kill
     * switch → Disconnected, which the guard would otherwise block forever).
     * Error states from the service always propagate immediately.
     */
    private fun applyStateWithGuards(serviceState: VpnState) {
        val localState = _state.value

        // Always propagate Error states from the service immediately
        if (serviceState is VpnState.Error) {
            _state.value = serviceState
            _connectedServer.value = BirdoVpnService.connectedServer
            _connectedSince.value = BirdoVpnService.connectedSince
            return
        }

        // Guard transitional states, but only for a limited window
        val elapsed = System.currentTimeMillis() - transitionStartTime
        if (elapsed < TRANSITION_GUARD_MS) {
            when {
                // We set Connecting/Reconnecting but service hasn't started yet (still Disconnected)
                (localState is VpnState.Connecting || localState is VpnState.Reconnecting) && serviceState is VpnState.Disconnected -> return
                // We set Disconnecting but service hasn't stopped yet (still Connected/Connecting)
                localState is VpnState.Disconnecting &&
                    (serviceState is VpnState.Connected || serviceState is VpnState.Connecting) -> return
            }
        }

        // Safety net: if Connecting for too long (service + guard both stuck), force Error
        if ((localState is VpnState.Connecting || serviceState is VpnState.Connecting) &&
            elapsed > CONNECT_STUCK_TIMEOUT_MS
        ) {
            _state.value = VpnState.Error("Connection timed out — server may be unreachable")
            return
        }

        // Safety net: if Disconnecting for too long, force Disconnected
        if ((localState is VpnState.Disconnecting || serviceState is VpnState.Disconnecting) &&
            elapsed > DISCONNECT_STUCK_TIMEOUT_MS
        ) {
            _state.value = VpnState.Disconnected
            _connectedServer.value = null
            _connectedSince.value = 0L
            return
        }

        _state.value = serviceState
        _connectedServer.value = BirdoVpnService.connectedServer
        _connectedSince.value = BirdoVpnService.connectedSince
    }

    val isKillSwitchActive: Boolean
        get() = BirdoVpnService.killSwitchActive

    // ── Heartbeat keepalive ────────────────────────────────────────

    /**
     * Start periodic heartbeat to backend while connected.
     * Mirrors the Windows client pattern (POST /vpn/heartbeat/{keyId} every 30s).
     * Prevents the server from pruning idle connections.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var qualityTickCount = 0
            var keyRotationTickCount = 0
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (_state.value !is VpnState.Connected) break
                when (val result = repository.sendHeartbeat()) {
                    is ApiResult.Success -> {
                        val resp = result.data
                        if (!resp.valid) {
                            // Session invalidated server-side — disconnect immediately
                            android.util.Log.w("VpnManager", "Heartbeat: session invalid, disconnecting")
                            withContext(Dispatchers.Main) {
                                disconnect()
                                _state.value = VpnState.Error("Session expired — please reconnect")
                            }
                            break
                        }
                        if (!resp.serverOnline) {
                            android.util.Log.w("VpnManager", "Heartbeat: server going offline")
                        }
                    }
                    is ApiResult.Error -> {
                        android.util.Log.w("VpnManager", "Heartbeat failed: ${result.message}")
                    }
                }

                // P2-15: Quality report every other heartbeat (~60s)
                qualityTickCount++
                if (qualityTickCount >= 2) {
                    qualityTickCount = 0
                    val keyId = repository.getLastKeyId()
                    if (keyId != null) {
                        val connectedSince = BirdoVpnService.connectedSince
                        val handshakeAge = if (connectedSince > 0)
                            (System.currentTimeMillis() - connectedSince) / 1000 else 0L
                        val report = app.birdo.vpn.data.model.QualityReport(
                            keyId = keyId,
                            latencyMs = 0.0, // Not measurable from userspace; requires kernel handshake timestamps
                            jitterMs = 0.0,
                            packetLossPercent = 0.0,
                            bytesIn = BirdoVpnService.rxBytes,
                            bytesOut = BirdoVpnService.txBytes,
                            handshakeAgeSeconds = handshakeAge,
                            connectionState = "connected",
                            platform = "android",
                        )
                        repository.sendQualityReport(report) // fire-and-forget
                    }
                }

                // P1-13: Periodic key rotation for forward secrecy (~45 min).
                // Skip entirely while the backend rotate endpoint is unshipped
                // (repository.keyRotationSupported == false): the existing key is
                // kept and we never fire a request that can only return 501.
                if (repository.keyRotationSupported) {
                    keyRotationTickCount++
                    if (keyRotationTickCount >= KEY_ROTATION_HEARTBEATS) {
                        keyRotationTickCount = 0
                        try {
                            repository.rotateKey()
                        } catch (e: Exception) {
                            android.util.Log.w("VpnManager", "Key rotation failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // ── Auto-reconnect with exponential backoff ─────────────────────

    /**
     * Start automatic reconnection with exponential backoff.
     * Attempts: 2s → 4s → 8s → 16s → 32s (capped at [MAX_RECONNECT_DELAY_MS]).
     * After [MAX_RECONNECT_ATTEMPTS] failures, stays in Error for manual retry.
     */
    private fun startAutoReconnect(resetAttempts: Boolean = true) {
        if (reconnectJob?.isActive == true && resetAttempts) return
        val lastServer = prefs.lastServerId ?: return
        // Don't auto-reconnect if user explicitly disconnected
        if (_state.value is VpnState.Disconnected) return

        if (resetAttempts) reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempt++
                _reconnectAttemptFlow.value = reconnectAttempt

                val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1L shl (reconnectAttempt - 1)))
                    .coerceAtMost(MAX_RECONNECT_DELAY_MS)
                delay(delayMs)

                // Abort if state changed (user manually connected/disconnected)
                if (_state.value !is VpnState.Error && _state.value !is VpnState.Reconnecting) return@launch

                _state.value = VpnState.Reconnecting(reconnectAttempt)
                transitionStartTime = System.currentTimeMillis()

                val result = connect(lastServer)
                if (result is ApiResult.Success) {
                    cancelAutoReconnect()
                    return@launch
                }
                // connect() already sets VpnState.Error — loop continues
            }
            // Exhausted all attempts — stay in Error for manual retry
            _reconnectAttemptFlow.value = 0
        }
    }

    /** Cancel any pending auto-reconnect and reset attempt counter. */
    fun cancelAutoReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempt = 0
        _reconnectAttemptFlow.value = 0
    }
}
