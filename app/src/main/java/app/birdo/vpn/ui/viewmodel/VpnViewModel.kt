package app.birdo.vpn.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.birdo.vpn.R
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.ClientConfigResponse
import app.birdo.vpn.data.model.PortForward
import app.birdo.vpn.data.model.RedeemVoucherResponse
import app.birdo.vpn.data.model.SubscriptionStatus
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.service.BirdoVpnService
import app.birdo.vpn.service.MultiHopPolicy
import app.birdo.vpn.service.QuotaGrace
import app.birdo.vpn.service.BirdoPqManager
import app.birdo.vpn.service.SessionCopy
import app.birdo.vpn.service.VpnManager
import app.birdo.vpn.service.VpnState
import app.birdo.vpn.service.WireGuardConfigBuilder
import app.birdo.vpn.service.isConnectingPhase
import app.birdo.vpn.utils.InputValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    /**
     * A message for the Connect screen that the session state does not carry
     * itself: a refused server switch, an incomplete Multi-Hop pair, a
     * retired node, a denied permission. Connect FAILURES are not copied here
     * any more: VpnManager publishes them as VpnState.Error, and Home drew
     * both — two identical red banners, which even disagreed for a 426
     * (A2-011). Dismissible.
     */
    val connectError: String? = null,
    /** The Servers screen's own error (A2-012: one shared field leaked between screens). */
    val serversError: String? = null,
    /** The Port Forwarding screen's own error. */
    val portForwardError: String? = null,
    val needsVpnPermission: Boolean = false,
    val killSwitchActive: Boolean = false,
    /**
     * The kill switch could not be armed, as the sentence to show, or null
     * (BirdoVpnService.killSwitchNotArmedFlow). Sticky across Reconnecting
     * and the re-dial, which have no message of their own (P2-2).
     */
    val killSwitchNotArmed: String? = null,
    /** The node the session was dialled to (VpnManager.connectedServerId), for the Servers list's marker. */
    val connectedServerId: String? = null,
    /** A1-025: Android's strict Private DNS overrides BirdoShield / Custom DNS on this connection. */
    val privateDnsOverridesDns: Boolean = false,
    /** The Free plan's grace window (birdo-web PR #590), while the session is up. */
    val quotaGrace: QuotaGrace? = null,
    val publicIp: String? = null,
    /** Whether the current connection uses Xray Reality stealth tunnel */
    val stealthActive: Boolean = false,
    /** Asked for Stealth, connected without it for the plan (VpnManager.stealthNotice). */
    val stealthNotice: String? = null,
    /** Whether the current connection uses any post-quantum PSK mechanism (bilateral OR server-provided). */
    val quantumActive: Boolean = false,
    /**
     * PFA-M9: granular PQ mode for honest UI labelling.
     *  - "BILATERAL"       — genuine end-to-end ML-KEM-1024 PSK derivation
     *  - "DISABLED"        — no PSK
     * Marketing copy MUST distinguish BILATERAL from SERVER_PROVIDED before
     * claiming post-quantum protection to a user.
     */
    val pqMode: String = "DISABLED",
    /** Current subscription status */
    val subscription: SubscriptionStatus? = null,
    /** A plan/usage fetch is in flight (drives the Limit tab's refresh feedback). */
    val isLoadingSubscription: Boolean = false,
    /**
     * Why the last plan/usage fetch failed, user-facing; null once one
     * succeeds. The Limit tab used to spin "Loading your usage…" forever on a
     * failure, because the error branch was silent (A2-018).
     */
    val subscriptionError: String? = null,
    /** Port forwards for the current connection */
    val portForwards: List<PortForward> = emptyList(),
    val isLoadingPortForwards: Boolean = false,
    /**
     * BirdoShield (D18) FLEET GATE from `GET /api/client-config`
     * (`dnsFilteringAvailable` = the backend's `DNS_FILTERING_ENABLED`).
     *
     * Distinct from the per-device `AppPreferences.dnsFilteringEnabled` opt-in:
     * this says whether turning that preference on can do anything. With the
     * gate off the backend ignores the connect flag and hands out the normal
     * resolver, so a toggle that read ON would be a lie about what the server
     * will do.
     *
     * `null` means UNKNOWN — a cold start before the fetch lands, an
     * unreachable web app, or a deploy older than birdo-web#465 — and the UI
     * treats unknown as AVAILABLE. See [nextDnsFilteringAvailable].
     */
    val dnsFilteringAvailable: Boolean? = null,
    /** A server switch is in flight: Home says "Switching server…" (P1-parity-016). */
    val switching: Boolean = false,
    /**
     * The entry node of the live (or dialling) Multi-Hop session, or null.
     * Drives "Protected · Multi-Hop" (P1-parity-017) and the globe's focus
     * and arc, which used to point at the last single-hop selection
     * (P1-parity-003).
     */
    val liveMultiHopEntryId: String? = null,
    /** The account session expired; the tunnel may still be up (see VpnManager.sessionExpired). */
    val sessionExpired: Boolean = false,
)

/** True while a Multi-Hop session is the one that is connected. */
val VpnUiState.multiHopActive: Boolean
    get() = vpnState is VpnState.Connected && liveMultiHopEntryId != null

/**
 * The next fleet-gate value given the current one and a fetch outcome.
 *
 * Pure and `internal` so the defaulting rule can be tested on its own rather
 * than through the whole VpnViewModel, and so there is exactly ONE place that
 * decides it. Two rules, both deliberate:
 *
 *  - An ERROR keeps [current]. It never yields `false`. A network blip must not
 *    hide a working feature.
 *  - A success whose `dnsFilteringAvailable` is absent (`null`) also keeps
 *    [current] — absent is "the server did not say", not "off".
 */
internal fun nextDnsFilteringAvailable(
    current: Boolean?,
    result: ApiResult<ClientConfigResponse>,
): Boolean? = when (result) {
    is ApiResult.Success -> result.data.dnsFilteringAvailable ?: current
    is ApiResult.Error -> current
}

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

/**
 * The dial that asked for the VPN permission prompt, replayed EXACTLY when
 * the user grants it (A1-007). The grant used to call connect(), which never
 * consults the Multi-Hop policy: a first Multi-Hop dial on a fresh install
 * came back from the system dialog as a single hop while Home kept drawing
 * the entry -> exit pair.
 */
internal sealed interface PendingDial {
    data object Connect : PendingDial
    data object Quick : PendingDial
    data class MultiHop(val entryNodeId: String, val exitNodeId: String) : PendingDial
    data class Switch(val server: VpnServer) : PendingDial
}

/** What the voucher dialog shows after a redemption attempt (A2-027). */
sealed interface VoucherResult {
    data class Redeemed(val response: RedeemVoucherResponse) : VoucherResult

    /** The server refused the CODE; [slug] is its documented reason, if it gave one. */
    data class Rejected(val slug: String?) : VoucherResult

    /** The request failed for a reason that is not the code's; [message] is user-facing. */
    data class Failed(val message: String) : VoucherResult
}

@HiltViewModel
class VpnViewModel @Inject constructor(
    private val vpnManager: VpnManager,
    private val repository: BirdoRepository,
    private val prefs: AppPreferences,
    private val tokenManager: TokenManager,
    /** Every sentence this class writes comes from strings.xml through here (A2-031). */
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    internal companion object {
        /**
         * Auto-Connect falls back to the best server only when the saved one
         * is gone or out of this plan's reach (403/404). Never on the user's
         * own Cancel or Disconnect (SUPERSEDED), on the connect watchdog, or on
         * a refusal the next dial would get too: any of those used to start a
         * fresh dial to the best server against the user's intent
         * (REVIEW-AND-003).
         */
        fun fallsBackToQuickConnect(error: ApiResult.Error): Boolean =
            error.message != VpnManager.SUPERSEDED && (error.code == 403 || error.code == 404)
    }

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

    /** See [PendingDial]. Cleared when the permission is granted or denied. */
    private var pendingDial: PendingDial? = null

    /** The stats poll, alive only while connected (A1-036). */
    private var statsJob: Job? = null

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
        startPrivateDnsSync()
        startQuotaSync()
        // Pre-publish any cached subscription so Profile tab is never empty on first paint.
        repository.cachedSubscriptionOrNull()?.let {
            _uiState.value = _uiState.value.copy(subscription = it)
        }
        // Auto-connect and the plan fetch wait for the CURRENT consent, like the
        // client-config fetch below (audit D-12, A2-028): a returning user with
        // auto-connect on used to have a VPN session dialled (API connect,
        // integrity, PQ key) while the re-consent screen was on display.
        if (prefs.hasAcceptedCurrentConsent) onConsentAccepted()
        // BirdoShield fleet gate. Unauthenticated and public, so unlike the
        // subscription fetch it runs regardless of sign-in state — the VPN
        // Settings screen is reachable by anonymous accounts too.
        //
        // But NOT before the consent screen has been accepted: a request to
        // birdo.app is still a request from this device's IP, and the consent
        // screen is where the user is told what the app sends (audit
        // 2026-09-29, D-12). BirdoNavGraph fetches it the moment consent is
        // given; the VPN Settings screen refreshes it on every open.
        if (prefs.hasAcceptedCurrentConsent) fetchClientConfig()
        // NOTE: Heartbeat is handled by VpnManager.startHeartbeat() which includes
        // key rotation, quality reports, and session-invalid disconnect. No redundant
        // heartbeat needed here — VpnManager is the authoritative keepalive source.
    }

    /**
     * The work init holds back until the current consent is accepted; the
     * graph calls this from the consent screen's accept.
     */
    fun onConsentAccepted() {
        // FIX-2-9: Auto-connect on startup if preference is enabled
        autoConnectIfEnabled()
        // If we already have a token, start fetching subscription right away so it's
        // ready by the time the user taps the Profile tab. This eliminates the
        // "RECON → SOVEREIGN" flicker users were seeing on cold start.
        if (tokenManager.isLoggedIn()) {
            fetchSubscription()
        }
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
            when (
                val decision = MultiHopPolicy.forNewConnection(
                    multiHopArmedForDial(),
                    prefs.multiHopEntryNodeId,
                    prefs.multiHopExitNodeId,
                )
            ) {
                is MultiHopPolicy.NewConnection.MultiHop -> {
                    tracing("Auto-connect: multi-hop armed, connecting ${decision.entryNodeId} -> ${decision.exitNodeId}")
                    when (val result = vpnManager.connectMultiHop(decision.entryNodeId, decision.exitNodeId)) {
                        is ApiResult.Success -> { /* state syncs via startStateSync */ }
                        is ApiResult.Error ->
                            // Do NOT fall back to a single hop. That is the exact
                            // silent downgrade the policy exists to prevent, and
                            // it would look identical to success to the user.
                            tracing("Auto-connect multi-hop failed: ${result.message}")
                    }
                    return@launch
                }
                MultiHopPolicy.NewConnection.RefuseIncompletePair -> {
                    // Armed but incomplete — a node was destroyed, or prefs were
                    // half-written. Stay disconnected rather than quietly
                    // substituting a single hop.
                    tracing("Auto-connect: multi-hop enabled but entry/exit incomplete; not connecting")
                    return@launch
                }
                MultiHopPolicy.NewConnection.SingleHop -> Unit
            }

            if (lastServerId != null) {
                tracing("Auto-connecting to last server: $lastServerId")
                when (val result = vpnManager.connect(lastServerId)) {
                    is ApiResult.Success -> { /* state syncs via startStateSync */ }
                    is ApiResult.Error -> if (fallsBackToQuickConnect(result)) {
                        tracing("Auto-connect: the saved server is gone or locked, trying quick connect")
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
    private fun startStateSync() {
        // One combined stream, so the kill-switch banner, the switching label
        // and the session-expired flag follow their sources directly. They used
        // to be snapshots taken only when the connection state changed, so a
        // block released by a settings push left "All traffic blocked" on
        // screen over a free device (A1-044).
        viewModelScope.launch {
            combine(
                vpnManager.state,
                BirdoVpnService.killSwitchActiveFlow,
                vpnManager.switching,
                vpnManager.sessionExpired,
                BirdoVpnService.killSwitchNotArmedFlow,
            ) { state, blocking, switching, expired, notArmed -> SyncInput(state, blocking, switching, expired, notArmed) }
                .collect { input ->
                    val route = vpnManager.activeMultiHopRoute
                    val routeIsLive = input.state is VpnState.Connected || input.state.isConnectingPhase
                    _uiState.value = _uiState.value.copy(
                        vpnState = input.state,
                        connectedServer = vpnManager.connectedServer.value,
                        connectedServerId = vpnManager.connectedServerId.value,
                        connectedSince = vpnManager.connectedSince.value,
                        killSwitchActive = input.killSwitchActive,
                        killSwitchNotArmed = input.killSwitchNotArmed,
                        switching = input.switching,
                        sessionExpired = input.sessionExpired,
                        liveMultiHopEntryId = if (routeIsLive) route?.first else null,
                        stealthActive = BirdoVpnService.stealthActive,
                        quantumActive = BirdoVpnService.quantumActive,
                        pqMode = BirdoPqManager.modeFlow.value.name,
                        publicIp = BirdoVpnService.publicIp,
                    )
                    if (input.state is VpnState.Connected) startStatsPolling() else stopStatsPolling()
                }
        }
    }

    /** birdo-web PR #590: the Free allowance's grace window, from the heartbeat. */
    private fun startQuotaSync() {
        viewModelScope.launch {
            vpnManager.quotaGrace.collect { grace ->
                _uiState.value = _uiState.value.copy(quotaGrace = grace)
            }
        }
        viewModelScope.launch {
            vpnManager.stealthNotice.collect { notice ->
                _uiState.value = _uiState.value.copy(stealthNotice = notice)
            }
        }
    }

    /** A1-025: watch Android's Private DNS under the tunnel. */
    private fun startPrivateDnsSync() {
        viewModelScope.launch {
            BirdoVpnService.privateDnsStrictFlow.collect { strict ->
                _uiState.value = _uiState.value.copy(
                    privateDnsOverridesDns = WireGuardConfigBuilder.privateDnsOverrides(
                        strictPrivateDns = strict,
                        dnsFilteringEnabled = prefs.dnsFilteringEnabled,
                        customDnsEnabled = prefs.customDnsEnabled,
                    ),
                )
            }
        }
    }

    private data class SyncInput(
        val state: VpnState,
        val killSwitchActive: Boolean,
        val switching: Boolean,
        val sessionExpired: Boolean,
        val killSwitchNotArmed: String?,
    )

    /**
     * Poll the traffic counters (volatile service fields the state stream does
     * not carry) — ONLY while connected. The loop used to run every 1 s / 8 s
     * for the activity's whole lifetime, disconnected included (A1-036).
     *
     * Counters go to their own [trafficStats] flow so a per-second byte tick
     * only recomposes the stats row — not the globe, top bar, server selector
     * and connect button.
     */
    private fun startStatsPolling() {
        if (statsJob?.isActive == true) return
        statsJob = viewModelScope.launch {
            while (isActive) {
                _trafficStats.value = TrafficStats(
                    rxBytes = BirdoVpnService.rxBytes,
                    txBytes = BirdoVpnService.txBytes,
                    tickMs = System.currentTimeMillis(),
                )
                val ip = BirdoVpnService.publicIp
                if (_uiState.value.publicIp != ip) {
                    _uiState.value = _uiState.value.copy(publicIp = ip)
                }
                // Live 1s cadence only matters while the UI is visible. When the
                // app is backgrounded nothing observes these flows, so match the
                // service's adaptive ticker (8s).
                delay(if (BirdoVpnService.uiForeground) 1000L else 8000L)
            }
        }
    }

    private fun stopStatsPolling() {
        if (statsJob == null) return
        statsJob?.cancel()
        statsJob = null
        // Flush the service's reset values once, so a new session does not
        // start from the last one's counters.
        _trafficStats.value = TrafficStats(rxBytes = BirdoVpnService.rxBytes, txBytes = BirdoVpnService.txBytes)
    }

    fun loadServers(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingServers = true, serversError = null)
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
                        // K10 (A1-023): pre-select the lowest-load node this
                        // plan can use — the node a quick connect would dial —
                        // never the first row of a name-sorted list, which was
                        // the same country for every new user. One rule, shared
                        // with VpnManager.quickConnect (VpnManager.bestServer).
                        // The user's own last server comes first: a fresh
                        // process selected the best node instead, so the next
                        // Connect tap moved the user — São Paulo became Toronto
                        // on the emulator — and an Always-on boot then dialled
                        // Toronto as "the last server".
                        selectedServer = _uiState.value.selectedServer
                            ?: lastUsableServer(servers)
                            ?: VpnManager.bestServer(servers),
                    )
                    pruneRetiredMultiHopNodes(servers)
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoadingServers = false,
                        serversError = result.message,
                    )
                }
            }
        }
    }

    private fun lastUsableServer(servers: List<VpnServer>): VpnServer? {
        val last = prefs.lastServerId ?: return null
        return servers.firstOrNull { it.id == last && it.accessible && it.isOnline }
    }

    /** The server a single-hop recovery re-dials (VpnManager.redial: prefs.lastServerId), if listed. */
    private fun redialledServer(): VpnServer? {
        val last = prefs.lastServerId ?: return null
        return _uiState.value.servers.firstOrNull { it.id == last }
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
            connectError = when {
                entryGone && exitGone ->
                    appContext.getString(R.string.multihop_both_retired)
                entryGone -> appContext.getString(R.string.multihop_entry_retired)
                else -> appContext.getString(R.string.multihop_exit_retired)
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
            _uiState.value = _uiState.value.copy(isLoadingSubscription = true)
            when (val result = repository.getSubscription(forceRefresh)) {
                is ApiResult.Success -> {
                    // What dials with no UI in front of them read to decide
                    // Multi-Hop (REVIEW-AND-007/-020).
                    prefs.lastKnownPlan = result.data.plan
                    _uiState.value = _uiState.value.copy(
                        subscription = result.data,
                        isLoadingSubscription = false,
                        subscriptionError = null,
                    )
                }
                // Kept separate from `error`: the plan is shown on its own tab,
                // and a stale figure stays on screen with the reason beside it.
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoadingSubscription = false,
                    subscriptionError = result.message,
                )
            }
        }
    }

    /**
     * The plan changed (a Play purchase the server accepted, or a voucher).
     * Servers carry a PLAN-computed `accessible` flag, so they are re-read
     * too: refreshing only the plan left premium nodes locked right after the
     * user had paid for them (A2-003).
     */
    fun onEntitlementChanged() {
        fetchSubscription(forceRefresh = true)
        loadServers(forceRefresh = true)
    }

    /**
     * Forget everything that belongs to the account that just signed out.
     *
     * This ViewModel is activity-scoped and outlives a sign-out, so without
     * this the next account on the device inherited the previous one's server
     * list (with ITS plan's `accessible` flags), selection, plan and port
     * forwards (A2-003). iOS does the same in `resetForLogout`.
     */
    fun resetForSignOut() {
        // The next account must not dial this one's server or inherit its plan
        // (REVIEW-AND-007).
        prefs.lastKnownPlan = null
        prefs.lastServerId = null
        _uiState.value = _uiState.value.copy(
            servers = emptyList(),
            selectedServer = null,
            isLoadingServers = false,
            subscription = null,
            isLoadingSubscription = false,
            subscriptionError = null,
            portForwards = emptyList(),
            isLoadingPortForwards = false,
            connectError = null,
            serversError = null,
            portForwardError = null,
        )
    }

    /**
     * Fetch the BirdoShield fleet gate from the public client-config endpoint.
     *
     * Failure is silent BY DESIGN: [nextDnsFilteringAvailable] keeps the current
     * value (initially `null` = unknown = available), so an unreachable web app
     * leaves the toggle usable instead of greying out a feature that works.
     */
    fun fetchClientConfig() {
        viewModelScope.launch {
            val result = repository.getClientConfig()
            val next = nextDnsFilteringAvailable(_uiState.value.dnsFilteringAvailable, result)
            if (next != _uiState.value.dnsFilteringAvailable) {
                _uiState.value = _uiState.value.copy(dnsFilteringAvailable = next)
            }
        }
    }

    /**
     * Redeem a voucher code. On success the plan and the servers it unlocks
     * are re-read, so the new plan is usable immediately.
     */
    fun redeemVoucher(code: String, onResult: (VoucherResult) -> Unit) {
        viewModelScope.launch {
            when (val result = repository.redeemVoucher(code)) {
                is ApiResult.Success -> {
                    if (result.data.ok) {
                        onEntitlementChanged()
                        onResult(VoucherResult.Redeemed(result.data))
                    } else {
                        onResult(VoucherResult.Rejected(result.data.error))
                    }
                }
                is ApiResult.Error -> onResult(VoucherResult.Failed(result.message))
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
        //   * activeMultiHopRoute goes STALE. VpnManager clears it on a
        //     single-hop dial and a teardown it performs itself; a teardown
        //     driven by the service -- onRevoke(), onDestroy() -- does not. It
        //     also gets set on connectMultiHop's API success, before the tunnel
        //     is established, so a multi-hop dial that fails leaves it set with
        //     nothing running. Gating on it alone would then refuse every
        //     server tap while the user is plainly disconnected, with no
        //     Disconnect control rendered anywhere to satisfy the message.
        //     Requiring `onTunnel` confines the refusal to a session that
        //     genuinely exists.
        //
        // No same-node exemption, deliberately, and this matches iOS. `prev` is
        // the SELECTION label, which during a multi-hop session does not track
        // the live route at all -- so exempting `prev?.id == server.id` let a tap
        // on the highlighted row fall through to the `connectError = null` below
        // and silently wipe the refusal the previous tap had just raised.
        when (val change = MultiHopPolicy.forRouteChange(onTunnel, vpnManager.activeMultiHopRoute)) {
            is MultiHopPolicy.RouteChange.RefuseWouldDowngrade -> {
                _uiState.value = _uiState.value.copy(connectError = change.message)
                return
            }
            MultiHopPolicy.RouteChange.Allowed -> Unit
        }

        // Clear the refusal (and any stale connect error) once a selection is
        // actually accepted, so a banner cannot outlive the tap that caused it.
        _uiState.value = _uiState.value.copy(selectedServer = server, connectError = null)
        if (!onTunnel || prev?.id == server.id) return
        if (!vpnManager.isVpnPermissionGranted()) {
            requestPermissionFor(PendingDial.Switch(server))
            return
        }
        viewModelScope.launch {
            try {
                // A failure is published by VpnManager as the session's Error —
                // except a live switch that did not happen (A1-034): that one
                // kept the previous session, so say so and put the selection back.
                val result = vpnManager.connect(server.id)
                if (result is ApiResult.Error && result.message != VpnManager.SUPERSEDED) {
                    when (vpnManager.state.value) {
                        is VpnState.Connected ->
                            _uiState.value = _uiState.value.copy(selectedServer = prev, connectError = result.message)
                        // Round 7: a switch the network cut short hands the session
                        // it was moving back to the supervisor, which waits for the
                        // network and re-dials prefs.lastServerId — not the server
                        // tapped. The selection follows the re-dial. No banner: the
                        // state already says it is waiting, and a connectError would
                        // outlive the reconnect it describes.
                        is VpnState.Reconnecting ->
                            _uiState.value = _uiState.value.copy(selectedServer = redialledServer() ?: prev)
                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // Never let a switch failure escape the coroutine and crash the
                // app, and never show its text: an exception message is not
                // copy (REVIEW-AND-006).
                // A switch that threw before VpnManager could publish an Error.
                _uiState.value = _uiState.value.copy(connectError = appContext.getString(R.string.vpn_switch_failed))
            }
        }
    }

    // ── Favorites ────────────────────────────────────────────────

    fun toggleFavorite(serverId: String) {
        prefs.toggleFavorite(serverId)
        _favoriteServers.value = prefs.favoriteServers
    }

    // ── Connection ───────────────────────────────────────────────

    /**
     * A dial is refused while one is in flight, while connected, and while the
     * supervisor is reconnecting — Home shows Disconnect for that, and a
     * second dial beside the recovery used to mint a second peer (A1-008).
     */
    private fun dialInProgress(): Boolean {
        val s = vpnManager.state.value
        return s.isConnectingPhase || s == VpnState.Connected || s == VpnState.Disconnecting ||
            s is VpnState.Reconnecting
    }

    private fun requestPermissionFor(dial: PendingDial) {
        pendingDial = dial
        _uiState.value = _uiState.value.copy(needsVpnPermission = true)
    }

    /** Multi-Hop is armed exactly as Home draws it: the pref AND a SOVEREIGN plan. */
    private fun multiHopArmedAsShown(): Boolean =
        prefs.multiHopEnabled &&
            _uiState.value.subscription?.plan?.equals("SOVEREIGN", ignoreCase = true) == true

    /**
     * Auto-Connect and quick connect: the same rule, with the persisted plan
     * standing in until this launch's subscription fetch lands. They used the
     * raw pref, which a lapsed plan never clears (REVIEW-AND-007).
     */
    private fun multiHopArmedForDial(): Boolean =
        prefs.multiHopEnabled &&
            MultiHopPolicy.entitledByPlan(_uiState.value.subscription?.plan ?: prefs.lastKnownPlan) == true

    fun connect() {
        if (dialInProgress()) return

        if (!vpnManager.isVpnPermissionGranted()) {
            requestPermissionFor(PendingDial.Connect)
            return
        }

        // Second guard behind Home's own routing (A1-007): a single-hop dial is
        // never built while Multi-Hop is armed as Home draws it.
        when (
            val decision = MultiHopPolicy.forNewConnection(
                multiHopArmedAsShown(),
                prefs.multiHopEntryNodeId,
                prefs.multiHopExitNodeId,
            )
        ) {
            is MultiHopPolicy.NewConnection.MultiHop -> {
                connectMultiHop(decision.entryNodeId, decision.exitNodeId)
                return
            }
            MultiHopPolicy.NewConnection.RefuseIncompletePair -> {
                _uiState.value = _uiState.value.copy(connectError = SessionCopy.INCOMPLETE_MULTI_HOP)
                return
            }
            MultiHopPolicy.NewConnection.SingleHop -> Unit
        }

        val server = _uiState.value.selectedServer
        if (server == null) {
            quickConnect()
            return
        }

        _uiState.value = _uiState.value.copy(connectError = null)
        // A failure is published by VpnManager as the session's Error, with
        // the canonical copy (426 included); nothing to copy here.
        viewModelScope.launch { vpnManager.connect(server.id) }
    }

    fun quickConnect() {
        if (dialInProgress()) return

        if (!vpnManager.isVpnPermissionGranted()) {
            requestPermissionFor(PendingDial.Quick)
            return
        }

        _uiState.value = _uiState.value.copy(connectError = null)
        viewModelScope.launch {
            // Same contract as autoConnectIfEnabled: quick connect is also
            // reached from surfaces with no UI to gate on. Without this they
            // build a single hop while the app keeps displaying the chosen route.
            when (
                val decision = MultiHopPolicy.forNewConnection(
                    multiHopArmedForDial(),
                    prefs.multiHopEntryNodeId,
                    prefs.multiHopExitNodeId,
                )
            ) {
                MultiHopPolicy.NewConnection.RefuseIncompletePair -> {
                    // Refuse rather than silently downgrade — see below.
                    _uiState.value = _uiState.value.copy(connectError = SessionCopy.INCOMPLETE_MULTI_HOP)
                }
                // NOT falling back to a single hop on failure: a single-hop
                // tunnel presented as the user's chosen multi-hop route is
                // indistinguishable from success to them, and leaks the
                // jurisdiction they paid to hide.
                is MultiHopPolicy.NewConnection.MultiHop ->
                    vpnManager.connectMultiHop(decision.entryNodeId, decision.exitNodeId)
                MultiHopPolicy.NewConnection.SingleHop -> vpnManager.quickConnect()
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            vpnManager.disconnect()
        }
    }

    /**
     * Sign-out: tear the tunnel down and WAIT for the server to release the
     * slot, then [thenSignOut] — which wipes the tokens. Run as one sequence
     * so the DELETE cannot race the token wipe and leave the peer holding a
     * device slot until the reaper (A1-045, A2-005; iOS disconnectForSignOut).
     */
    fun disconnectForSignOut(thenSignOut: () -> Unit) {
        viewModelScope.launch {
            vpnManager.disconnectForSignOut()
            thenSignOut()
        }
    }

    /** The account is gone server-side, peer included: tear down locally only. */
    fun onAccountDeleted() {
        vpnManager.onAccountDeleted()
    }

    /** The account session died (a 401 the refresh could not fix). See VpnManager.onSessionExpired. */
    fun onSessionExpired() {
        vpnManager.onSessionExpired()
    }

    /** A session exists again: resume anything the expiry paused. */
    fun onSignedIn() {
        vpnManager.onSignedIn()
    }

    fun connectMultiHop(entryNodeId: String, exitNodeId: String) {
        if (dialInProgress()) return

        if (!vpnManager.isVpnPermissionGranted()) {
            requestPermissionFor(PendingDial.MultiHop(entryNodeId, exitNodeId))
            return
        }

        _uiState.value = _uiState.value.copy(connectError = null)
        // Refusals (no route block, a different route, the API) are published
        // by VpnManager as the session's Error.
        viewModelScope.launch { vpnManager.connectMultiHop(entryNodeId, exitNodeId) }
    }

    /** Replay exactly the dial that asked for the permission (A1-007). */
    fun onVpnPermissionGranted() {
        _uiState.value = _uiState.value.copy(needsVpnPermission = false)
        val dial = pendingDial
        pendingDial = null
        when (dial) {
            PendingDial.Connect -> connect()
            PendingDial.Quick -> quickConnect()
            is PendingDial.MultiHop -> connectMultiHop(dial.entryNodeId, dial.exitNodeId)
            is PendingDial.Switch -> selectServer(dial.server)
            null -> Unit
        }
    }

    fun onVpnPermissionDenied() {
        pendingDial = null
        _uiState.value = _uiState.value.copy(
            needsVpnPermission = false,
            connectError = SessionCopy.VPN_PERMISSION,
        )
    }

    fun getVpnPermissionIntent(): Intent? = vpnManager.getVpnPermissionIntent()

    /** Home's dismiss affordance on its message banner (A2-012). */
    fun dismissConnectError() {
        _uiState.value = _uiState.value.copy(connectError = null)
    }

    // ── Port Forwarding ──────────────────────────────────────────

    fun loadPortForwards() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPortForwards = true, portForwardError = null)
            when (val result = repository.getPortForwards()) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        portForwards = result.data,
                        isLoadingPortForwards = false,
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        portForwardError = result.message,
                        isLoadingPortForwards = false,
                    )
                }
            }
        }
    }

    fun createPortForward(internalPort: Int, protocol: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingPortForwards = true, portForwardError = null)
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
                            // The server's words only when they are words (no
                            // HTML, no stack trace): REVIEW-AND-006.
                            portForwardError = InputValidator.sanitizeErrorMessage(
                                result.data.message,
                                appContext.getString(R.string.port_forward_create_failed),
                            ),
                            isLoadingPortForwards = false,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        portForwardError = result.message,
                        isLoadingPortForwards = false,
                    )
                }
            }
        }
    }

    fun deletePortForward(id: String) {
        viewModelScope.launch {
            when (val result = repository.deletePortForward(id)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        portForwards = _uiState.value.portForwards.filter { it.id != id },
                    )
                }
                // It used to fail silently: the rule stayed, and nothing said so (A2-012).
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(portForwardError = result.message)
            }
        }
    }
}
