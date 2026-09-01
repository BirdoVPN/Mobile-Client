package app.birdo.vpn.ui.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.model.PortForward
import app.birdo.vpn.data.model.RedeemVoucherResponse
import app.birdo.vpn.data.model.SubscriptionStatus
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.service.VpnManager
import app.birdo.vpn.service.VpnState
import app.birdo.vpn.service.isConnectingPhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VpnUiState(
    val vpnState: VpnState = VpnState.Disconnected,
    val connectedServer: String? = null,
    val connectedSince: Long = 0,
    val servers: List<VpnServer> = emptyList(),
    val selectedServer: VpnServer? = null,
    val isLoadingServers: Boolean = false,
    val error: String? = null,
    val needsVpnPermission: Boolean = false,
    val killSwitchActive: Boolean = false,
    val tick: Long = 0L,
    val publicIp: String? = null,
    /** Whether the current connection uses Xray Reality stealth tunnel */
    val stealthActive: Boolean = false,
    /** Whether the current connection uses any post-quantum PSK mechanism (bilateral OR server-provided). */
    val quantumActive: Boolean = false,
    /**
     * PFA-M9: granular PQ mode for honest UI labelling.
     *  - "BILATERAL"       — genuine end-to-end ML-KEM-1024 PSK derivation
     *  - "SERVER_PROVIDED" — classical PSK delivered over TLS (NOT post-quantum)
     *  - "DISABLED"        — no PSK
     * Marketing copy MUST distinguish BILATERAL from SERVER_PROVIDED before
     * claiming post-quantum protection to a user.
     */
    val pqMode: String = "DISABLED",
    /** Current subscription status */
    val subscription: SubscriptionStatus? = null,
    /** Port forwards for the current connection */
    val portForwards: List<PortForward> = emptyList(),
    val isLoadingPortForwards: Boolean = false,
)

/** Persisted multi-hop arming + entry/exit node selection (see [VpnViewModel.multiHop]). */
data class MultiHopSelection(
    val enabled: Boolean = false,
    val entryId: String? = null,
    val exitId: String? = null,
)

/**
 * Hot, high-frequency counters kept OUT of [VpnUiState] on purpose.
 *
 * These tick every second while connected and the foreground UI is visible.
 * Folding them into VpnUiState made every tick a new UiState instance, so the
 * whole Home tree — globe, top bar, server selector, connect button — was
 * invalidated once a second just to redraw three little numbers. Collected as
 * its own flow, only the stats row recomposes.
 */
data class TrafficStats(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    /**
     * Wall clock at the last poll. Drives the connected-duration readout, which
     * must keep counting up even while the byte counters are idle. Advanced only
     * while connected, so a disconnected app emits nothing.
     */
    val tickMs: Long = 0L,
)

@HiltViewModel
class VpnViewModel @Inject constructor(
    private val vpnManager: VpnManager,
    private val repository: BirdoRepository,
    private val prefs: AppPreferences,
    private val tokenManager: TokenManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VpnUiState())
    val uiState: StateFlow<VpnUiState> = _uiState.asStateFlow()

    // Hot counters on their own flow — see [TrafficStats].
    private val _trafficStats = MutableStateFlow(TrafficStats())
    val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

    // ── Favorites state (observed by ServerListScreen) ───────────
    private val _favoriteServers = MutableStateFlow(prefs.favoriteServers)
    val favoriteServers: StateFlow<Set<String>> = _favoriteServers.asStateFlow()

    // ── Multi-hop arming + entry/exit selection (observed by HomeScreen) ──
    // Backed by AppPreferences so the selection survives process death, not
    // just rotation — HomeScreen previously held this in rememberSaveable
    // only, so a force-close disarmed multi-hop and dropped both picks.
    private val _multiHop = MutableStateFlow(
        MultiHopSelection(
            enabled = prefs.multiHopEnabled,
            entryId = prefs.multiHopEntryNodeId,
            exitId = prefs.multiHopExitNodeId,
        ),
    )
    val multiHop: StateFlow<MultiHopSelection> = _multiHop.asStateFlow()

    fun setMultiHopSelection(enabled: Boolean, entryId: String?, exitId: String?) {
        prefs.multiHopEnabled = enabled
        prefs.multiHopEntryNodeId = entryId
        prefs.multiHopExitNodeId = exitId
        _multiHop.value = MultiHopSelection(enabled, entryId, exitId)
    }

    init {
        // NOTE: loadServers() is NOT called here — it was causing a race condition
        // where the 401 from an unauthenticated GET /vpn/servers set error="Session expired"
        // before auth had settled. BirdoNavGraph calls loadServers() after login succeeds.
        startStateSync()
        // FIX-2-9: Auto-connect on startup if preference is enabled
        autoConnectIfEnabled()
        // Pre-publish any cached subscription so Profile tab is never empty on first paint.
        repository.cachedSubscriptionOrNull()?.let {
            _uiState.value = _uiState.value.copy(subscription = it)
        }
        // If we already have a token, start fetching subscription right away so it's
        // ready by the time the user taps the Profile tab. This eliminates the
        // "RECON → SOVEREIGN" flicker users were seeing on cold start.
        if (tokenManager.isLoggedIn()) {
            fetchSubscription()
        }
        // NOTE: Heartbeat is handled by VpnManager.startHeartbeat() which includes
        // key rotation, quality reports, and session-invalid disconnect. No redundant
        // heartbeat needed here — VpnManager is the authoritative keepalive source.
    }

    /**
     * FIX-2-9: Auto-connect to the last used server on app startup.
     * Only triggers if: auto-connect preference is enabled, VPN permission is granted,
     * user is authenticated, and not already connected.
     */
    private fun autoConnectIfEnabled() {
        if (!prefs.autoConnect) return
        if (!tokenManager.isLoggedIn()) return // Guard: must be authenticated
        if (!vpnManager.isVpnPermissionGranted()) return
        if (vpnManager.state.value != VpnState.Disconnected) return

        val lastServerId = prefs.lastServerId
        viewModelScope.launch {
            // Brief delay to let auth state initialize
            delay(1500)
            if (vpnManager.state.value != VpnState.Disconnected) return@launch

            // MULTI-HOP FIRST. Auto-connect used to go straight to connect()/
            // quickConnect(), both of which build a SINGLE-HOP tunnel, while
            // HomeScreen kept rendering the entry -> exit route from prefs. The
            // app therefore told the user their traffic left from the exit
            // country when it left from the entry — undetectable by them, and
            // the client is the only thing that could have said otherwise. For a
            // feature bought for jurisdictional separation, silently serving the
            // other thing is the worst available failure.
            val entry = prefs.multiHopEntryNodeId
            val exit = prefs.multiHopExitNodeId
            if (prefs.multiHopEnabled) {
                if (!entry.isNullOrBlank() && !exit.isNullOrBlank()) {
                    tracing("Auto-connect: multi-hop armed, connecting $entry -> $exit")
                    when (val result = vpnManager.connectMultiHop(entry, exit)) {
                        is ApiResult.Success -> { /* state syncs via startStateSync */ }
                        is ApiResult.Error ->
                            // Do NOT fall back to a single hop. That is the exact
                            // silent downgrade this branch exists to prevent, and
                            // it would look identical to success to the user.
                            tracing("Auto-connect multi-hop failed: ${result.message}")
                    }
                } else {
                    // Armed but incomplete — a node was destroyed, or prefs were
                    // half-written. Stay disconnected rather than quietly
                    // substituting a single hop.
                    tracing("Auto-connect: multi-hop enabled but entry/exit incomplete; not connecting")
                }
                return@launch
            }

            if (lastServerId != null) {
                tracing("Auto-connecting to last server: $lastServerId")
                when (val result = vpnManager.connect(lastServerId)) {
                    is ApiResult.Success -> { /* state syncs via startStateSync */ }
                    is ApiResult.Error -> {
                        tracing("Auto-connect to saved server failed: ${result.message}, trying quick connect")
                        vpnManager.quickConnect()
                    }
                }
            } else {
                tracing("Auto-connect: no last server, using quick connect")
                vpnManager.quickConnect()
            }
        }
    }

    private fun tracing(msg: String) {
        android.util.Log.d("VpnViewModel", msg)
    }

    // FIX-2-12: Reactive state sync via StateFlow collection.
    // VPN state changes propagate immediately (no 1s delay).
    // A separate 1s loop still updates traffic stats (rxBytes, txBytes) which
    // remain volatile companion fields on BirdoVpnService.
    private fun startStateSync() {
        // Reactive: collect state/connectedServer/connectedSince immediately
        viewModelScope.launch {
            vpnManager.state.collect { vpnState ->
                _uiState.value = _uiState.value.copy(
                    vpnState = vpnState,
                    connectedServer = vpnManager.connectedServer.value,
                    connectedSince = vpnManager.connectedSince.value,
                    killSwitchActive = app.birdo.vpn.service.BirdoVpnService.killSwitchActive,
                    stealthActive = app.birdo.vpn.service.BirdoVpnService.stealthActive,
                    quantumActive = app.birdo.vpn.service.BirdoVpnService.quantumActive,
                    pqMode = app.birdo.vpn.service.RosenpassManager.modeFlow.value.name,
                    tick = System.currentTimeMillis(),
                )
            }
        }
        // Periodic: poll traffic stats & public IP (volatile service fields the
        // state collector above doesn't carry).
        //
        // Traffic counters go to their own [trafficStats] flow so a per-second
        // byte tick only recomposes the stats row — not the globe, top bar,
        // server selector and connect button, which is what happened when they
        // lived on VpnUiState. `publicIp` and `tick` stay on VpnUiState but are
        // only re-emitted when they genuinely change, so a connected-but-idle
        // tunnel produces ZERO UiState emissions per tick.
        viewModelScope.launch {
            while (isActive) {
                val svcRx = app.birdo.vpn.service.BirdoVpnService.rxBytes
                val svcTx = app.birdo.vpn.service.BirdoVpnService.txBytes
                val svcIp = app.birdo.vpn.service.BirdoVpnService.publicIp

                val s = _uiState.value
                val connected = s.vpnState == VpnState.Connected
                val stats = _trafficStats.value
                // While connected, advance the tick every poll so the duration
                // readout counts up. While disconnected, only flush the service's
                // reset values (once) — no idle emissions.
                if (connected) {
                    _trafficStats.value = TrafficStats(
                        rxBytes = svcRx,
                        txBytes = svcTx,
                        tickMs = System.currentTimeMillis(),
                    )
                } else if (stats.rxBytes != svcRx || stats.txBytes != svcTx) {
                    _trafficStats.value = TrafficStats(rxBytes = svcRx, txBytes = svcTx, tickMs = 0L)
                }

                if (s.publicIp != svcIp) {
                    _uiState.value = s.copy(publicIp = svcIp, tick = System.currentTimeMillis())
                }

                // Live 1s cadence only matters while the UI is visible. When the
                // app is backgrounded nothing observes these flows, so match the
                // service's adaptive ticker (8s) and stop waking the main thread
                // behind the user's back.
                delay(if (app.birdo.vpn.service.BirdoVpnService.uiForeground) 1000L else 8000L)
            }
        }
    }

    fun loadServers(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingServers = true, error = null)
            when (val result = repository.getServers(forceRefresh)) {
                is ApiResult.Success -> {
                    val servers = result.data.sortedWith(
                        compareBy<VpnServer> { !it.isOnline }
                            .thenBy { it.country }
                            .thenBy { it.city }
                            .thenBy { it.name }
                    )
                    _uiState.value = _uiState.value.copy(
                        servers = servers,
                        isLoadingServers = false,
                        // Never auto-select a node this plan can't use: the user
                        // would tap Connect and eat a server-side refusal.
                        selectedServer = _uiState.value.selectedServer
                            ?: servers.firstOrNull { it.isOnline && it.accessible },
                    )
                    pruneRetiredMultiHopNodes(servers)
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoadingServers = false,
                        error = result.message,
                    )
                }
            }
        }
    }

    /**
     * S-4 (ported from the desktop Dashboard's prune effect): drop persisted
     * Multi-Hop selections whose node no longer exists in the fetched list.
     *
     * These ids are persisted and nothing ever removed them. When a node was
     * destroyed the saved pair simply stopped resolving: auto-connect's
     * incomplete-pair guard refused to connect and the picker sat blank, with
     * no explanation — leaving the user to work out for themselves that a
     * server they never touched had been retired.
     *
     * Only prunes on a NON-EMPTY list: an empty result is far more likely a
     * failed or unauthorized fetch than a fleet that ceased to exist, and
     * wiping the saved route on a transient error would be its own bug.
     * Skipped unless the session is settled (Disconnected/Error) so a live or
     * in-flight session's route is never rewritten underneath it.
     */
    private fun pruneRetiredMultiHopNodes(servers: List<VpnServer>) {
        if (servers.isEmpty()) return
        val state = vpnManager.state.value
        if (state != VpnState.Disconnected && state !is VpnState.Error) return
        val entry = prefs.multiHopEntryNodeId
        val exit = prefs.multiHopExitNodeId
        val live = servers.mapTo(HashSet()) { it.id }
        val entryGone = !entry.isNullOrBlank() && entry !in live
        val exitGone = !exit.isNullOrBlank() && exit !in live
        if (!entryGone && !exitGone) return

        if (entryGone) prefs.multiHopEntryNodeId = null
        if (exitGone) prefs.multiHopExitNodeId = null
        _multiHop.value = MultiHopSelection(
            enabled = prefs.multiHopEnabled,
            entryId = if (entryGone) null else entry,
            exitId = if (exitGone) null else exit,
        )
        // Name which end went, rather than leaving a silently half-empty picker.
        _uiState.value = _uiState.value.copy(
            error = when {
                entryGone && exitGone ->
                    "Both of your Multi-Hop servers were retired. Pick a new entry and exit."
                entryGone -> "Your Multi-Hop entry server was retired. Pick a new one."
                else -> "Your Multi-Hop exit server was retired. Pick a new one."
            },
        )
    }

    /**
     * Fetch the current subscription. Set [forceRefresh] to bypass the
     * 30s cache (e.g. immediately after a voucher redemption).
     *
     * If a fresh cached value exists it is published immediately so the
     * UI never falls back to the default "RECON" placeholder.
     */
    fun fetchSubscription(forceRefresh: Boolean = false) {
        // Publish cached value immediately so the Profile tab never shows stale RECON.
        if (!forceRefresh) {
            repository.cachedSubscriptionOrNull()?.let {
                if (_uiState.value.subscription != it) {
                    _uiState.value = _uiState.value.copy(subscription = it)
                }
            }
        }
        viewModelScope.launch {
            when (val result = repository.getSubscription(forceRefresh)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(subscription = result.data)
                }
                is ApiResult.Error -> { /* silent — non-critical */ }
            }
        }
    }

    /**
     * Redeem a voucher code. Refreshes the subscription on success so
     * the UI reflects the new period end. The provided callback receives
     * the parsed RedeemVoucherResponse (success body or parsed error
     * body) — see RedeemVoucherResponse.error for the slug.
     */
    fun redeemVoucher(code: String, onResult: (RedeemVoucherResponse?) -> Unit) {
        viewModelScope.launch {
            when (val result = repository.redeemVoucher(code)) {
                is ApiResult.Success -> {
                    if (result.data.ok) {
                        // Refresh subscription so SubscriptionScreen shows new period
                        fetchSubscription(forceRefresh = true)
                    }
                    onResult(result.data)
                }
                is ApiResult.Error -> onResult(null)
            }
        }
    }

    fun selectServer(server: VpnServer) {
        // Defence in depth: the list already renders out-of-plan nodes locked
        // and inert, so reaching here means a caller bypassed that. Refuse
        // rather than switch a live tunnel onto a node the backend will reject.
        if (!server.accessible) return

        val prev = _uiState.value.selectedServer

        // Live server switch: if already on the tunnel and a DIFFERENT node is
        // picked, switch to it instead of only changing the label. vpnManager
        // .connect() cleanly tears down the old tunnel + server-side peer and
        // brings up a FRESH session (new keypair) on the new node. When not
        // connected, this is just a selection used by the next Connect tap.
        val st = vpnManager.state.value
        val onTunnel = st == VpnState.Connected || st is VpnState.Reconnecting

        // REFUSE to downgrade a LIVE Multi-Hop session. A single server cannot
        // express an entry -> exit pair, so switching here would drop the second
        // hop while the user kept paying for -- and believing in -- a
        // jurisdictional separation that no longer existed. They cannot observe
        // their own egress country, so the app is the only thing that could say
        // otherwise, and it would carry on drawing the route.
        //
        // autoConnectIfEnabled() and quickConnect() already refuse to BUILD a
        // single hop while Multi-Hop is armed; this path -- switching one that
        // is already up -- was the one that was missed. iOS refuses it in
        // selectServerLive().
        //
        // ORDER MATTERS, and getting it wrong is how this drifted from iOS in
        // the first place. The refusal sits AFTER `onTunnel` -- mirroring
        // selectServerLive's `guard isConnected || isConnecting`, which precedes
        // its own refusal -- and BEFORE the same-node check, which is also where
        // iOS puts it. Two reasons it cannot move above `onTunnel`:
        //
        //   * A tap that only relabels (nothing connected) can downgrade
        //     nothing, so refusing it is pure obstruction.
        //   * activeMultiHopRoute goes STALE. VpnManager clears it in connect()
        //     and tearDownTunnel() only, and a teardown driven by the service --
        //     the notification's Disconnect action, onRevoke(), onDestroy() --
        //     reaches neither. It also gets set on connectMultiHop's API success,
        //     before the tunnel is established, so a multi-hop dial that fails
        //     leaves it set with nothing running. Gating on it alone would then
        //     refuse every server tap while the user is plainly disconnected,
        //     with no Disconnect control rendered anywhere to satisfy the
        //     message. Requiring `onTunnel` confines the refusal to a session
        //     that genuinely exists.
        //
        // The field is NOT cleared on a service-published Disconnect on purpose:
        // startAutoReconnect() reads it to rebuild the SAME route after a drop,
        // and clearing it there would silently convert a dropped multi-hop into
        // a single-hop reconnect -- the very failure this guard exists to stop.
        // No same-node exemption, deliberately, and this matches iOS. `prev` is
        // the SELECTION label, which during a multi-hop session does not track
        // the live route at all -- so exempting `prev?.id == server.id` let a tap
        // on the highlighted row fall through to the `error = null` below and
        // silently wipe the refusal the previous tap had just raised.
        if (onTunnel && vpnManager.activeMultiHopRoute != null) {
            _uiState.value = _uiState.value.copy(
                error = "Switching servers would replace your Multi-Hop route with " +
                    "a single hop. Disconnect first if you meant to switch.",
            )
            return
        }

        // Clear the refusal (and any stale connect error) once a selection is
        // actually accepted -- the Servers tab now renders uiState.error, so a
        // banner left standing would outlive the tap that caused it.
        _uiState.value = _uiState.value.copy(selectedServer = server, error = null)
        if (!onTunnel || prev?.id == server.id) return
        if (!vpnManager.isVpnPermissionGranted()) {
            _uiState.value = _uiState.value.copy(needsVpnPermission = true)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            try {
                when (val result = vpnManager.connect(server.id)) {
                    is ApiResult.Success -> { /* state syncs via startStateSync */ }
                    is ApiResult.Error -> {
                        _uiState.value = _uiState.value.copy(error = result.message)
                    }
                }
            } catch (t: Throwable) {
                // Never let a switch failure escape the coroutine and crash the app.
                _uiState.value = _uiState.value.copy(
                    error = t.message ?: "Couldn't switch server — please try again.",
                )
            }
        }
    }

    // ── Favorites ────────────────────────────────────────────────

    fun toggleFavorite(serverId: String) {
        prefs.toggleFavorite(serverId)
        _favoriteServers.value = prefs.favoriteServers
    }

    // ── Connection ───────────────────────────────────────────────

    fun connect() {
        val currentState = vpnManager.state.value
        if (currentState.isConnectingPhase || currentState == VpnState.Connected || currentState == VpnState.Disconnecting) return

        if (!vpnManager.isVpnPermissionGranted()) {
            _uiState.value = _uiState.value.copy(needsVpnPermission = true)
            return
        }

        val server = _uiState.value.selectedServer
        if (server == null) {
            quickConnect()
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            when (val result = vpnManager.connect(server.id)) {
                is ApiResult.Success -> {
                    // State is updated via syncState
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = connectErrorMessage(result),
                    )
                }
            }
        }
    }

    fun quickConnect() {
        val currentState = vpnManager.state.value
        if (currentState.isConnectingPhase || currentState == VpnState.Connected || currentState == VpnState.Disconnecting) return

        if (!vpnManager.isVpnPermissionGranted()) {
            _uiState.value = _uiState.value.copy(needsVpnPermission = true)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)

            // Same contract as autoConnectIfEnabled: quick connect is also
            // reached from the home-screen widget and the quick-settings tile,
            // neither of which has UI to gate on. Without this they build a
            // single hop while the app keeps displaying the chosen route.
            val entry = prefs.multiHopEntryNodeId
            val exit = prefs.multiHopExitNodeId
            if (prefs.multiHopEnabled) {
                if (entry.isNullOrBlank() || exit.isNullOrBlank()) {
                    // Refuse rather than silently downgrade — see below.
                    _uiState.value = _uiState.value.copy(
                        error = "Multi-Hop is on but no entry/exit pair is selected. " +
                            "Choose both in Settings, or turn Multi-Hop off.",
                    )
                    return@launch
                }
                when (val result = vpnManager.connectMultiHop(entry, exit)) {
                    is ApiResult.Success -> {}
                    is ApiResult.Error ->
                        // NOT falling back to vpnManager.quickConnect(): a
                        // single-hop tunnel presented as the user's chosen
                        // multi-hop route is indistinguishable from success to
                        // them, and leaks the jurisdiction they paid to hide.
                        _uiState.value =
                            _uiState.value.copy(error = connectErrorMessage(result))
                }
                return@launch
            }

            when (val result = vpnManager.quickConnect()) {
                is ApiResult.Success -> {}
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(error = connectErrorMessage(result))
                }
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            vpnManager.disconnect()
        }
    }

    fun connectMultiHop(entryNodeId: String, exitNodeId: String) {
        val currentState = vpnManager.state.value
        if (currentState.isConnectingPhase || currentState == VpnState.Connected || currentState == VpnState.Disconnecting) return

        if (!vpnManager.isVpnPermissionGranted()) {
            _uiState.value = _uiState.value.copy(needsVpnPermission = true)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            when (val result = vpnManager.connectMultiHop(entryNodeId, exitNodeId)) {
                is ApiResult.Success -> {
                    val body = result.data
                    if (!body.success) {
                        _uiState.value = _uiState.value.copy(
                            error = body.message ?: "Multi-hop connection failed",
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(error = connectErrorMessage(result))
                }
            }
        }
    }


    /**
     * Map a connect failure to a user-facing message. HTTP 426 is the backend's
     * version support floor: the raw error body is structured JSON (too long for
     * the sanitizer, which would degrade it to a generic "Connection failed"),
     * so translate it into the one actionable sentence.
     */
    private fun connectErrorMessage(result: ApiResult.Error): String =
        if (result.code == 426) {
            "This app version is no longer supported. Update BirdoVPN to reconnect."
        } else {
            result.message
        }

    fun onVpnPermissionGranted() {
        _uiState.value = _uiState.value.copy(needsVpnPermission = false)
        connect()
    }

    fun onVpnPermissionDenied() {
        _uiState.value = _uiState.value.copy(
            needsVpnPermission = false,
            error = "VPN permission is required to connect",
        )
    }

    fun getVpnPermissionIntent(): Intent? = vpnManager.getVpnPermissionIntent()

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Measure TCP connection latency to a server's IP/port.
     * Uses Socket connect timeout instead of ICMP ping (no root needed).
     */
    fun measureServerLatency(ipAddress: String, port: Int = 443, onResult: (Long?) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val latency = try {
                val start = System.nanoTime()
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(ipAddress, port), 3000)
                }
                (System.nanoTime() - start) / 1_000_000 // Convert to ms
            } catch (_: Exception) {
                null
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onResult(latency)
            }
        }
    }

    // ── Port Forwarding ──────────────────────────────────────────

    fun loadPortForwards() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPortForwards = true)
            when (val result = repository.getPortForwards()) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        portForwards = result.data,
                        isLoadingPortForwards = false,
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message,
                        isLoadingPortForwards = false,
                    )
                }
            }
        }
    }

    fun createPortForward(internalPort: Int, protocol: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPortForwards = true)
            when (val result = repository.createPortForward(internalPort, protocol)) {
                is ApiResult.Success -> {
                    val created = result.data.portForward
                    if (created != null) {
                        _uiState.value = _uiState.value.copy(
                            portForwards = _uiState.value.portForwards + created,
                            isLoadingPortForwards = false,
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            error = result.data.message ?: "Failed to create port forward",
                            isLoadingPortForwards = false,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        error = result.message,
                        isLoadingPortForwards = false,
                    )
                }
            }
        }
    }

    fun deletePortForward(id: String) {
        viewModelScope.launch {
            when (repository.deletePortForward(id)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        portForwards = _uiState.value.portForwards.filter { it.id != id },
                    )
                }
                is ApiResult.Error -> { /* silent */ }
            }
        }
    }
}
