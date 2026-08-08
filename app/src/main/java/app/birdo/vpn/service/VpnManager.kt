package app.birdo.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.utils.PlayIntegrityManager
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.model.MultiHopConnectResponse
import app.birdo.vpn.data.network.NetworkMonitor
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.shared.model.TransportFallbackReason
import dagger.hilt.android.qualifiers.ApplicationContext
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
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

    // ── Apply-on-change ─────────────────────────────────────────────
    /**
     * (entryNodeId, exitNodeId) of the ACTIVE multi-hop session, or null for
     * single-hop/none. Needed so a settings reapply rebuilds the same route —
     * `prefs.lastServerId` only tracks single-hop.
     */
    @Volatile private var activeMultiHop: Pair<String, String>? = null

    /** Debounced settings-changed signals — see [requestSettingsReapply]. */
    private val reapplyRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /** Exposed so the UI can show "Reconnecting (attempt 2/5)…" */
    private val _reconnectAttemptFlow = MutableStateFlow(0)
    val reconnectAttemptFlow: StateFlow<Int> = _reconnectAttemptFlow.asStateFlow()

    /**
     * ADAPTIVE TRANSPORT: true while a stealth fallback rebuild is running.
     * Single-threaded by the Main.immediate dispatcher this scope uses, so a
     * plain Boolean is sufficient — see [onTransportBlocked].
     */
    private var fallbackInFlight = false

    /** Epoch ms of the last stealth fallback, for the retrigger cooldown. */
    private var lastFallbackAt = 0L

    companion object {
        /** Max time (ms) to guard transitional states before letting service state through */
        private const val TRANSITION_GUARD_MS = 15_000L
        /**
         * Max time (ms) before we force Connecting → Error if service is stuck.
         *
         * The service now stays in Connecting until a WireGuard handshake is
         * observed, which adds up to [BirdoVpnService]'s probe window
         * (TransportProbe.WINDOW_MS, 10s) to every connect. At 35s a slow but
         * perfectly good establish would have been condemned as stuck right as
         * its handshake landed, so the budget carries that window.
         */
        private const val CONNECT_STUCK_TIMEOUT_MS = 45_000L
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
        /**
         * Settle window before a settings change rebuilds the live tunnel.
         * Long enough to collapse a burst (ticking five split-tunnel apps,
         * typing an MTU) into ONE reconnect; short enough to feel immediate.
         */
        private const val SETTINGS_REAPPLY_DEBOUNCE_MS = 1_200L
        /** Key rotation interval: rotate WireGuard keys every ~45 min for forward secrecy */
        private const val KEY_ROTATION_HEARTBEATS = 90 // 90 × 30s = 45 minutes

        /**
         * Minimum gap between automatic stealth fallbacks.
         *
         * Guards the case where BOTH transports fail — genuinely offline, the
         * node is down, or a network that blocks TLS as well as UDP. Without it
         * the probe would fail, trigger a fallback, fail again and cycle,
         * burning battery and connect-rate budget on a network that was never
         * going to work. 60s is comfortably longer than one full
         * connect + probe cycle, so a legitimate second fallback (e.g. the user
         * changed networks) is still allowed promptly.
         */
        private const val FALLBACK_COOLDOWN_MS = 60_000L
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
                    // Fire for BOTH single-hop (lastServerId) and multi-hop
                    // (activeMultiHop) sessions — a multi-hop drop has no
                    // lastServerId, and gating on it alone left multi-hop users
                    // stranded (blocked, or leaking if fail-open) with no retry.
                    if (vpnState is VpnState.Error &&
                        (prefs.lastServerId != null || activeMultiHop != null)
                    ) {
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

        // Apply-on-change: settings edits request a reapply; after the settle
        // window, rebuild the live tunnel ONCE with the current preferences.
        // The rebuild FORCES the fail-closed blocking interface up for its whole
        // window (reapplyInProgress → switchTeardown forceBlock) even when the
        // kill switch is off, so the deliberate "brief blip" never egresses
        // cleartext (see reapplySettingsNow).
        scope.launch {
            @OptIn(FlowPreview::class)
            reapplyRequests
                .debounce(SETTINGS_REAPPLY_DEBOUNCE_MS)
                .collect {
                    try {
                        reapplySettingsNow()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("VpnManager", "Settings reapply failed", e)
                    }
                }
        }

        // ── ADAPTIVE TRANSPORT ──
        // The tunnel came up but no WireGuard handshake landed inside the probe
        // window, which means the network is silently dropping our packets
        // (DPI filtering, UDP blocking, a hostile captive portal). Rebuild the
        // SAME connection over the stealth transport, automatically.
        //
        // The user is told what happened but never asked to decide: they cannot
        // be expected to know what a handshake is, and the whole point of this
        // feature is that the product adapts instead of exposing the plumbing.
        scope.launch {
            BirdoVpnService.transportBlockedFlow.collect {
                try {
                    onTransportBlocked()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("VpnManager", "Transport fallback failed", e)
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
    /**
     * @param fallbackReason ADAPTIVE TRANSPORT. Non-null asks the server for the
     *   stealth transport regardless of plan, because plain WireGuard has been
     *   observed failing on this network. Set by [onTransportBlocked] on a
     *   retry, and on a first attempt when [AppPreferences.shouldStartOnStealth]
     *   says a recent fallback already proved this network filters us. Null on
     *   an ordinary connect, which always tries the fast path first.
     */
    suspend fun connect(
        serverId: String,
        fallbackReason: String? = null,
    ): ApiResult<ConnectResponse> {
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
            // A user-initiated switch must cancel any pending auto-reconnect; the
            // Reconnecting branch must NOT — it IS the reconnect job and would
            // cancel THIS coroutine at its next suspension point, aborting the
            // reconnect on its first attempt.
            if (priorState !is VpnState.Reconnecting) cancelAutoReconnect()
            // Fail-closed teardown: hold the kill-switch blocking interface up
            // while we unregister the old peer and rebuild. The former paths
            // (ACTION_STOP via tearDownTunnel()/disconnect()) deactivated the kill
            // switch and egressed cleartext for the whole rebuild window — the
            // bounded wait below PLUS the /connect round-trip PLUS Xray/Rosenpass
            // setup, tens of seconds. switchTeardown() keeps the interface up until
            // the new tunnel's establish() atomically supersedes it.
            // reapplyInProgress forces the block up even with the kill switch
            // off, so the settings-apply blip can't leak (see reapplySettingsNow).
            //
            // fallbackInFlight forces it for the same reason, and the case is
            // stronger. A settings reapply and a server switch are things the
            // USER asked for; an Adaptive Transport fallback is not. The app
            // decided on its own to tear down a tunnel the user was told was
            // "Connected", and at that moment their traffic is stalled inside a
            // tunnel that never handshook — so every app on the device has
            // requests queued and waiting. Dropping the interface without the
            // block would release that backlog onto the physical interface in
            // cleartext, in one burst, for a user who believes they are
            // protected and did not ask for any of it.
            //
            // This is precisely the population that cannot afford it: the
            // fallback only fires on networks that are already interfering with
            // our traffic, i.e. networks that are watching.
            switchTeardown(forceBlock = reapplyInProgress || fallbackInFlight)
            // Wait (bounded) for the service to confirm the old tunnel is down so
            // the old keyId is unregistered and wg-go is torn down before we
            // register + bring up the new one. The blocking interface stays up
            // throughout this wait (and the /connect call that follows).
            var waited = 0
            while (_state.value !is VpnState.Disconnected && waited < 5000) {
                delay(150)
                waited += 150
            }
        }

        _state.value = VpnState.Connecting
        transitionStartTime = System.currentTimeMillis()
        // This is THE single-hop path — the session is now single-hop. Clear the
        // multi-hop route up front (not only on success) so a FAILED switch away
        // from a live multi-hop session can't leave auto-reconnect rebuilding the
        // stale double-hop route instead of this server.
        activeMultiHop = null

        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        // Upload our ML-KEM-1024 client public key when quantum protection is
        // enabled so the server can encapsulate against it (BirdoPQ v1).
        val pqClientPublicKey: String? = if (prefs.quantumProtectionEnabled) {
            RosenpassManager.getClientPublicKeyB64(context) ?: run {
                _state.value = VpnState.Error("Quantum engine unavailable")
                return ApiResult.Error("Quantum engine unavailable")
            }
        } else null

        val integrityToken = requestAttestationToken()

        // ADAPTIVE TRANSPORT: an explicit retry reason wins; otherwise, if a
        // recent fallback proved this network filters WireGuard, skip straight
        // to the transport that works instead of making the user watch another
        // probe window fail. The preference expires (see shouldStartOnStealth),
        // so the fast path is re-tested rather than abandoned.
        val effectiveFallbackReason = fallbackReason
            ?: TransportFallbackReason.TRANSPORT_BLOCKED.takeIf { prefs.shouldStartOnStealth }

        val result = repository.connectVpn(
            serverNodeId = serverId,
            deviceName = deviceName,
            stealthMode = prefs.stealthModeEnabled,
            fallbackReason = effectiveFallbackReason,
            quantumProtection = prefs.quantumProtectionEnabled,
            pqClientPublicKey = pqClientPublicKey,
            integrityToken = integrityToken,
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

                // Supersede guard: if the user hit Disconnect during the /connect
                // round-trip, disconnect() set _state to Disconnecting — do NOT
                // bring the tunnel up against their intent. Unregister the
                // just-issued peer and bail. Gated to Disconnecting specifically
                // (not "any non-Connecting") so a slow connect whose transition
                // guard expired to a stale service-Disconnected isn't false-aborted.
                if (_state.value is VpnState.Disconnecting) {
                    repository.disconnectVpn()
                    return ApiResult.Error("Connection superseded by a newer action")
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
                // activeMultiHop already cleared at the top of connect().

                return result
            }
            is ApiResult.Error -> {
                _state.value = VpnState.Error(result.message)
                return result
            }
        }
    }

    /**
     * ADAPTIVE TRANSPORT: the tunnel established but no WireGuard handshake
     * arrived, so this network is dropping our packets. Rebuild the same
     * connection over the stealth transport.
     *
     * LOOP SAFETY, which is the whole risk here — a fallback that can retrigger
     * itself is a battery-draining reconnect storm on exactly the flaky networks
     * this feature targets. Four independent brakes:
     *
     *  1. [fallbackInFlight] — one fallback at a time. The probe fires from a
     *     background thread and the flow has buffer capacity, so two emissions
     *     can arrive close together; without this they would both reconnect.
     *  2. The service skips the probe entirely when the connection is ALREADY
     *     stealth, so the retry cannot re-emit and recurse.
     *  3. [lastFallbackAt] — a cooldown, so a network that fails both transports
     *     cannot cycle. Beyond it we stop and let normal auto-reconnect own the
     *     failure.
     *  4. The server enforces its own per-device hourly grant ceiling, so even a
     *     client bug cannot turn into fleet load.
     *
     * Multi-hop sessions are deliberately excluded: rebuilding one from here
     * would race [connectMultiHop]'s own two-node setup, and the multi-hop path
     * requests stealth up front through the same server-side grant.
     */
    private suspend fun onTransportBlocked() {
        val serverId = prefs.lastServerId
        if (serverId == null || activeMultiHop != null) {
            android.util.Log.i(
                "VpnManager",
                "Transport blocked but no single-hop session to rebuild — leaving it to auto-reconnect",
            )
            return
        }
        if (fallbackInFlight) return

        val now = System.currentTimeMillis()
        if (now - lastFallbackAt < FALLBACK_COOLDOWN_MS) {
            android.util.Log.w(
                "VpnManager",
                "Transport blocked again inside the cooldown — not retrying. " +
                    "Both transports appear to be failing; auto-reconnect owns this now.",
            )
            return
        }

        fallbackInFlight = true
        lastFallbackAt = now
        try {
            android.util.Log.w("VpnManager", "Falling back to the stealth transport")
            _state.value = VpnState.Connecting
            val result = connect(
                serverId = serverId,
                fallbackReason = TransportFallbackReason.HANDSHAKE_TIMEOUT,
            )
            // Only remember the preference when the fallback actually produced a
            // stealth connection. Recording it on a failed retry would steer
            // every future connect onto a transport we have no evidence works,
            // and would do so for a full TTL.
            val gotStealth = (result as? ApiResult.Success)?.data?.stealthEnabled == true
            if (gotStealth) {
                prefs.stealthPreferredSince = System.currentTimeMillis()
                android.util.Log.i(
                    "VpnManager",
                    "Stealth fallback succeeded — preferring it on this device for the next 24h",
                )
            }
        } finally {
            fallbackInFlight = false
        }
    }

    /**
     * Client attestation, shared by EVERY peer-issuing connect path.
     *
     * On Play builds we fetch a single-use server nonce and bind a Play
     * Integrity token to it, so the backend can confirm this is the genuine,
     * unmodified official app on a genuine device before it hands out a
     * WireGuard peer. Non-Play builds (direct APK / F-Droid) and any failure
     * yield null; the server's ATTESTATION_POLICY decides what that means.
     *
     * Deliberately a single helper: the backend enforces attestation on the
     * multi-hop connect as well as the single-hop one, so any peer-issuing call
     * that forgot to attach a token would be an attestation bypass on one path
     * and a broken feature on the other the moment policy flips to enforce.
     * Never hard-blocks on the client — the server owns the decision.
     */
    private suspend fun requestAttestationToken(): String? {
        if (!BuildConfig.IS_PLAY_BUILD) return null
        val nonce = repository.getAttestationNonce() ?: return null
        return PlayIntegrityManager.requestToken(context, nonce)
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

        val integrityToken = requestAttestationToken()

        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val result = repository.connectMultiHop(
            entryNodeId = entryNodeId,
            exitNodeId = exitNodeId,
            deviceName = deviceName,
            stealthMode = prefs.stealthModeEnabled,
            quantumProtection = prefs.quantumProtectionEnabled,
            pqClientPublicKey = pqClientPublicKey,
            integrityToken = integrityToken,
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

                // VERIFY THE ROUTE WE ASKED FOR IS THE ROUTE WE GOT.
                //
                // `success: true` only means the request was handled. The
                // response carries a `multiHop` block describing what was
                // ACTUALLY installed, and it was never checked — the UI rendered
                // the route from our own request instead. So any failure mode
                // that still yields a working single-hop tunnel (forwarding
                // install skipped, a fallback path, a response for a different
                // pair) was shown to the user as their chosen multi-hop route.
                //
                // The user cannot observe their own egress country. This client
                // is the only thing that can tell them, and it was reporting its
                // own intent rather than what happened. Refuse instead: no
                // tunnel is recoverable, a single hop believed to be two is a
                // silent, indefinite jurisdiction leak.
                val mh = config.multiHop
                if (mh == null) {
                    val msg = "The server did not confirm the Multi-Hop route. Not connecting."
                    android.util.Log.e("VpnManager", "multi-hop: success but no route block; refusing")
                    _state.value = VpnState.Error(msg)
                    return ApiResult.Error(msg)
                }
                if (mh.entryNode.id != entryNodeId || mh.exitNode.id != exitNodeId) {
                    val msg = "The server established a different Multi-Hop route (${mh.route}) " +
                        "than the one selected. Not connecting."
                    android.util.Log.e(
                        "VpnManager",
                        "multi-hop route mismatch: asked ${entryNodeId}->${exitNodeId}, " +
                            "got ${mh.entryNode.id}->${mh.exitNode.id}",
                    )
                    _state.value = VpnState.Error(msg)
                    return ApiResult.Error(msg)
                }

                // Record the route from the REQUEST (the response's multiHop block
                // is nullable) so a reapply/auto-reconnect rebuilds multi-hop, not
                // a silent single-hop downgrade — but ONLY on success, mirroring
                // single-hop's lastServerId, so a FAILED cold-start multi-hop
                // connect can't arm a futile auto-reconnect storm.
                activeMultiHop = entryNodeId to exitNodeId
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
            // Preserve the tunnel IPv6 address on multi-hop too — omitting it
            // left ConnectResponse.clientIpv6 null, so buildVpnInterface never
            // added a v6 address and IPv6 was blackholed for the whole session
            // even through an IPv6-enabled exit (single-hop already carries it).
            clientIpv6 = config.clientIpv6,
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

        // Supersede guard: a user Disconnect during the multi-hop /connect
        // round-trip flips _state to Disconnecting — don't resurrect the tunnel.
        // (connectWithConfig isn't suspend; unregister the orphan peer off-thread.)
        if (_state.value is VpnState.Disconnecting) {
            scope.launch { repository.disconnectVpn() }
            return
        }

        BirdoVpnService.setConfig(connectConfig)

        val intent = Intent(context, BirdoVpnService::class.java).apply {
            action = BirdoVpnService.ACTION_START
            putExtra(BirdoVpnService.EXTRA_KILL_SWITCH, prefs.killSwitchEnabled)
            putExtra(BirdoVpnService.EXTRA_SPLIT_TUNNEL_ENABLED, prefs.splitTunnelingEnabled)
            putExtra(BirdoVpnService.EXTRA_SPLIT_TUNNEL_APPS, prefs.splitTunnelApps.toTypedArray())
        }
        // Wrap like connect()'s START dispatch: a rapid teardown→restart (exactly
        // what a multi-hop reapply does) can throw ForegroundServiceStartNotAllowed.
        // Convert to an Error return instead of an unwinding throw, so the reapply
        // fail-open block-release path sees a clean non-Connected end state.
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            android.util.Log.e("VpnManager", "startForegroundService(START multihop) failed", e)
            _state.value = VpnState.Error("Couldn't start the VPN service — please try again.")
            return
        }

        _connectedServer.value = config.multiHop?.let {
            "${it.entryNode.name} → ${it.exitNode.name}"
        } ?: "Multi-Hop"
        // Prefer the route captured from the request in connectMultiHop(); only
        // fill it from the response when present, NEVER clobber to null (the
        // multiHop block is nullable even on a successful multi-hop connect).
        config.multiHop?.let { activeMultiHop = it.entryNode.id to it.exitNode.id }
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
        // Pick the lowest-load node THIS user's plan can actually use.
        // `accessible` is the server's own verdict (plan rank >= node minPlan),
        // so it covers every plan; the old `!isPremium` filter hard-coded the
        // free user's view and then fell back to ANY online node — which handed
        // paying-plan-less users a node the backend would refuse.
        val bestServer = servers
            .filter { it.isOnline && it.accessible }
            .minByOrNull { it.load }

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
        // Win over any in-flight settings-reapply blip: a user Disconnect must
        // not be undone by the reconnect half of an apply-on-change rebuild.
        reapplyAbortGeneration++
        reapplyInProgress = false
        cancelAutoReconnect() // User-initiated disconnect — stop any pending reconnect
        tearDownTunnel()
    }

    /**
     * Tear down the active tunnel (stop the service, notify the backend) WITHOUT
     * cancelling any in-flight auto-reconnect. [disconnect] wraps this with a
     * [cancelAutoReconnect] for the user-initiated case; the auto-reconnect job
     * calls this directly so it does NOT cancel itself mid-flight — doing so
     * (via disconnect()) aborted the reconnect coroutine at its next suspension
     * point AND left the kill switch torn down, i.e. a traffic leak that only a
     * manual reconnect recovered.
     */
    private suspend fun tearDownTunnel() {
        _state.value = VpnState.Disconnecting
        transitionStartTime = System.currentTimeMillis()
        activeMultiHop = null

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
     * Fail-closed teardown for a server switch or auto-reconnect. Sends
     * [BirdoVpnService.ACTION_SWITCH_TEARDOWN] instead of ACTION_STOP so the
     * kill-switch blocking interface stays established for the whole rebuild
     * window — no cleartext egress while the old peer is unregistered, the
     * /connect round-trip runs, and the new tunnel is set up. The replacement
     * tunnel's establish() atomically supersedes the blocking interface. Also
     * best-effort unregisters the old peer server-side.
     */
    private suspend fun switchTeardown(forceBlock: Boolean = false) {
        _state.value = VpnState.Disconnecting
        transitionStartTime = System.currentTimeMillis()

        val intent = Intent(context, BirdoVpnService::class.java).apply {
            action = BirdoVpnService.ACTION_SWITCH_TEARDOWN
            putExtra(BirdoVpnService.EXTRA_KILL_SWITCH, prefs.killSwitchEnabled)
            // A settings reapply forces the block up even with the kill switch
            // off, so the deliberate ~2s blip can't leak cleartext.
            putExtra(BirdoVpnService.EXTRA_FORCE_BLOCK, forceBlock)
        }
        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            android.util.Log.e("VpnManager", "startForegroundService(SWITCH_TEARDOWN) failed", e)
        }

        // Unregister the old peer server-side (best effort) before the new /connect
        // registers a fresh keypair. The service keeps the kill switch up meanwhile.
        repository.disconnectVpn()
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

    // ── Apply-on-change ────────────────────────────────────────────

    /**
     * Push runtime-consultable settings (today: the kill switch) into the
     * RUNNING service without touching the tunnel. The service captures the
     * kill-switch flag once at START — without this push, enabling it while
     * connected gave a false sense of drop-protection for the whole session.
     * FLAG-ONLY on the service side (never tears down), so it's race-free.
     * No-op when nothing is running (the next START carries current prefs).
     */
    fun pushRuntimeSettings() {
        val svcState = BirdoVpnService.currentState
        if (svcState is VpnState.Disconnected && !BirdoVpnService.killSwitchActive) return
        val intent = Intent(context, BirdoVpnService::class.java).apply {
            action = BirdoVpnService.ACTION_UPDATE_SETTINGS
            putExtra(BirdoVpnService.EXTRA_KILL_SWITCH, prefs.killSwitchEnabled)
        }
        try {
            // Plain startService: the service is already foreground; this must
            // not be subject to the startForegroundService 5s contract when the
            // handler decides to quietly stopSelf on a stale push.
            context.startService(intent)
        } catch (e: Exception) {
            android.util.Log.e("VpnManager", "startService(UPDATE_SETTINGS) failed", e)
        }
    }

    /**
     * Request an apply-on-change rebuild of the live tunnel. Debounced
     * ([SETTINGS_REAPPLY_DEBOUNCE_MS]) so a burst of edits produces ONE
     * reconnect; a no-op unless currently Connected (a connect that begins
     * later reads the latest preferences anyway).
     */
    fun requestSettingsReapply() {
        reapplyRequests.tryEmit(Unit)
    }

    /** True while [reapplySettingsNow] owns the tunnel; makes [connect]'s
     *  teardown force the fail-closed block regardless of the kill-switch pref. */
    @Volatile private var reapplyInProgress = false

    /**
     * Bumped by a user-initiated [disconnect]. [reapplySettingsNow] captures it
     * at entry and aborts its rebuild if it changes — so tapping Disconnect
     * during the ~2s blip wins over the reconnect instead of the VPN springing
     * back up against the user's explicit intent.
     */
    @Volatile private var reapplyAbortGeneration = 0

    /**
     * Rebuild the ACTIVE session with the current saved settings — the
     * "brief blip". ALWAYS fail-closed for its window (forceBlock), even when
     * the kill switch is off, so this deliberate app-initiated reconnect can't
     * leak. Multi-hop rebuilds multi-hop, never a silent single-hop downgrade.
     * Two guarantees enforced explicitly (not inferred from connect()'s return,
     * which signals Success as soon as the /connect API replies — BEFORE the
     * tunnel actually establishes):
     *  - A user Disconnect that lands anytime during the rebuild wins: we tear
     *    the resurrected tunnel back down.
     *  - A fail-open user is NEVER left behind the forced block: if the rebuild
     *    doesn't reach Connected, we release the block (their choice for drops).
     */
    private suspend fun reapplySettingsNow() {
        if (_state.value !is VpnState.Connected) return
        val startGen = reapplyAbortGeneration
        reapplyInProgress = true
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Applying settings — reconnecting…", Toast.LENGTH_SHORT).show()
        }
        try {
            val mh = activeMultiHop
            if (mh != null) {
                cancelAutoReconnect()
                switchTeardown(forceBlock = true)
                // Wait (bounded) for the old tunnel to be down before rebuilding;
                // the forced block stays up across this whole window.
                waitUntil(5000) { _state.value is VpnState.Disconnected }
                if (reapplyAbortGeneration != startGen) return abortReapply()
                connectMultiHop(mh.first, mh.second)
            } else {
                val serverId = prefs.lastServerId ?: return
                // connect() runs switchTeardown(forceBlock = reapplyInProgress).
                connect(serverId)
            }

            // connect()/connectMultiHop() have returned, but Success only means
            // the API replied — the service is still establishing. Wait for the
            // real outcome so both guarantees below act on the TRUE end state.
            waitUntil(CONNECT_STUCK_TIMEOUT_MS) {
                _state.value is VpnState.Connected ||
                    _state.value is VpnState.Error ||
                    _state.value is VpnState.Disconnected ||
                    reapplyAbortGeneration != startGen
            }

            // A user Disconnect landed during the rebuild → honour it: undo the
            // reconnect the API may have already kicked off.
            if (reapplyAbortGeneration != startGen) return abortReapply()

            // Fail-open user must not be left blocked: if the rebuild didn't come
            // up, release the forced block (clean disconnect). Fail-closed users
            // stay blocked and let auto-reconnect retry.
            if (_state.value !is VpnState.Connected && !prefs.killSwitchEnabled) {
                cancelAutoReconnect()
                tearDownTunnel()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Couldn't apply settings — disconnected.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The rebuild THREW (e.g. a rapid-restart FGS exception). Surface an
            // Error so a fail-closed session auto-reconnects (block held); the
            // finally then releases the forced block for a fail-open user so an
            // exception can never leave them stuck behind a total block.
            android.util.Log.e("VpnManager", "reapply rebuild threw", e)
            if (_state.value is VpnState.Connecting) {
                _state.value = VpnState.Error("Couldn't apply settings")
            }
        } finally {
            reapplyInProgress = false
            // Fail-open safety net that also covers the throw path: never leave a
            // fail-open user behind the forced block. NonCancellable so a scope
            // cancellation can't skip the release mid-teardown.
            if (reapplyAbortGeneration == startGen &&
                !prefs.killSwitchEnabled &&
                isKillSwitchActive &&
                _state.value !is VpnState.Connected
            ) {
                withContext(NonCancellable) {
                    cancelAutoReconnect()
                    tearDownTunnel()
                }
            }
        }
    }

    /** Tear the tunnel down to honour a user Disconnect that raced the blip. */
    private suspend fun abortReapply() {
        cancelAutoReconnect()
        tearDownTunnel()
    }

    /** Poll `cond` up to `timeoutMs`; returns true if it became true in time. */
    private suspend fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        var waited = 0L
        while (!cond() && waited < timeoutMs) {
            delay(150); waited += 150
        }
        return cond()
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
                        // A 401 here means the session is definitively gone — the
                        // refresh token was rejected and has now been discarded, so
                        // no future heartbeat can succeed.
                        //
                        // Previously this only logged and kept looping every 30s.
                        // The `!resp.valid` teardown above is unreachable in this
                        // situation, because a dead session yields an Error, never a
                        // successful body — so the tunnel was never torn down. That
                        // is the dangerous part: the server had already deleted the
                        // WireGuard peer, so the user sat behind a BLACKHOLED tunnel
                        // believing they were protected, while the loop burned a
                        // request every 30s.
                        //
                        // Restricted to 401 on purpose. Transient failures (5xx,
                        // timeouts, a lost mobile signal) must NOT drop a working
                        // tunnel — those keep looping exactly as before.
                        if (result.code == 401) {
                            android.util.Log.w("VpnManager", "Heartbeat: session dead, tearing down tunnel")
                            withContext(Dispatchers.Main) {
                                disconnect()
                                _state.value = VpnState.Error("Session expired — please sign in again")
                            }
                            break
                        }
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
        // A multi-hop session reconnects multi-hop; only single-hop needs a
        // lastServerId. Without this branch a dropped/failed multi-hop session
        // silently downgraded to single-hop egress from a stale server.
        val mh = activeMultiHop
        val lastServer = prefs.lastServerId
        if (mh == null && lastServer == null) return
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

                val result = if (mh != null) connectMultiHop(mh.first, mh.second)
                    else connect(lastServer!!)
                if (result is ApiResult.Success) {
                    cancelAutoReconnect()
                    return@launch
                }
                // connect()/connectMultiHop() already sets VpnState.Error — loop continues
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
