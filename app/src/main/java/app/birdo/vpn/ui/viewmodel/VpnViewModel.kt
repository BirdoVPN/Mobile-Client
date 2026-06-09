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
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
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

@HiltViewModel
class VpnViewModel @Inject constructor(
    private val vpnManager: VpnManager,
    private val repository: BirdoRepository,
    private val prefs: AppPreferences,
    private val tokenManager: TokenManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VpnUiState())
    val uiState: StateFlow<VpnUiState> = _uiState.asStateFlow()

    // ── Favorites state (observed by ServerListScreen) ───────────
    private val _favoriteServers = MutableStateFlow(prefs.favoriteServers)
    val favoriteServers: StateFlow<Set<String>> = _favoriteServers.asStateFlow()

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
        // Periodic: poll traffic stats & public IP that are still volatile fields
        viewModelScope.launch {
            while (isActive) {
                _uiState.value = _uiState.value.copy(
                    rxBytes = app.birdo.vpn.service.BirdoVpnService.rxBytes,
                    txBytes = app.birdo.vpn.service.BirdoVpnService.txBytes,
                    publicIp = app.birdo.vpn.service.BirdoVpnService.publicIp,
                    tick = System.currentTimeMillis(),
                )
                delay(1000)
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
                        selectedServer = _uiState.value.selectedServer
                            ?: servers.firstOrNull { it.isOnline },
                    )
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
        val prev = _uiState.value.selectedServer
        _uiState.value = _uiState.value.copy(selectedServer = server)

        // Live server switch: if already on the tunnel and a DIFFERENT node is
        // picked, switch to it instead of only changing the label. vpnManager
        // .connect() cleanly tears down the old tunnel + server-side peer and
        // brings up a FRESH session (new keypair) on the new node. When not
        // connected, this is just a selection used by the next Connect tap.
        val st = vpnManager.state.value
        val onTunnel = st == VpnState.Connected || st is VpnState.Reconnecting
        if (!onTunnel || prev?.id == server.id) return
        if (!vpnManager.isVpnPermissionGranted()) {
            _uiState.value = _uiState.value.copy(needsVpnPermission = true)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            when (val result = vpnManager.connect(server.id)) {
                is ApiResult.Success -> { /* state syncs via startStateSync */ }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
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
        if (currentState is VpnState.Connecting || currentState == VpnState.Connected || currentState == VpnState.Disconnecting) return

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
                        error = result.message,
                    )
                }
            }
        }
    }

    fun quickConnect() {
        val currentState = vpnManager.state.value
        if (currentState is VpnState.Connecting || currentState == VpnState.Connected || currentState == VpnState.Disconnecting) return

        if (!vpnManager.isVpnPermissionGranted()) {
            _uiState.value = _uiState.value.copy(needsVpnPermission = true)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            when (val result = vpnManager.quickConnect()) {
                is ApiResult.Success -> {}
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(error = result.message)
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
        if (currentState is VpnState.Connecting || currentState == VpnState.Connected || currentState == VpnState.Disconnecting) return

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
                    _uiState.value = _uiState.value.copy(error = result.message)
                }
            }
        }
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
