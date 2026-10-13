package app.birdo.vpn.service

import android.content.Context
import app.birdo.vpn.R
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.utils.FaultReporter
import app.birdo.vpn.utils.PlayIntegrityManager
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.model.MultiHopConnectResponse
import app.birdo.vpn.data.model.ServerNodeInfo
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.data.network.NetworkMonitor
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.shared.model.TransportFallbackReason
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import android.widget.Toast
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Hilt access for components the framework creates without injection:
 * BirdoVpnService (a VpnService the platform starts for Always-on) and the
 * home-screen widget's action callback.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface VpnManagerEntryPoint {
    fun vpnManager(): VpnManager
    fun tokenManager(): TokenManager
    fun repository(): BirdoRepository
}

/**
 * The single owner of the VPN session: API calls, preferences, the kill
 * switch, split tunnelling, and the service lifecycle — and, since the
 * 2026-09-30 overhaul, the session SUPERVISOR too.
 *
 * Every connect, disconnect, retry and give-up decision is made here, in this
 * class's application-lifetime [scope], never in a caller's. The Home screen,
 * the Quick Settings tile, the widget, the notification actions and the
 * system (Always-on) are all just triggers. The rules for whether and when to
 * re-dial are the pure [ReconnectPolicy]; this class carries out its verdicts.
 */
@Singleton
class VpnManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: BirdoRepository,
    private val prefs: AppPreferences,
    private val networkMonitor: NetworkMonitor,
) {
    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    /**
     * The ONLY place this class constructs a [VpnState.Error].
     *
     * The same drift guard as BirdoVpnService.updateState, for the same
     * reason: this class is a second, independent state funnel, and its error
     * branches (seventeen of them) assigned `_state.value = VpnState.Error(…)`
     * directly — published to the UI and to nobody else. Routing every one
     * through here leaves a breadcrumb for each present and future branch;
     * the branches that are FAILURES rather than outcomes (a service intent
     * that would not dispatch, a multi-hop route the server did not confirm)
     * also call FaultReporter.report at the site. DataplaneFaultReportingTest
     * fails the build if a `VpnState.Error(` construction reappears anywhere
     * else in this file.
     *
     * @param verdict true for an Error the supervisor publishes as its OWN
     *   decision (a spent budget). The state collector must not feed that back
     *   into the policy as a fresh failure, so it is remembered by identity.
     */
    private fun publishError(message: String, kind: FailureKind, verdict: Boolean = false) {
        FaultReporter.trail(FaultReporter.PATH_CONNECT, "manager state=Error(${kind.name}): $message")
        val error = VpnState.Error(message, kind)
        if (verdict) verdictError = error
        _state.value = error
    }

    private val _connectedServer = MutableStateFlow<String?>(null)
    val connectedServer: StateFlow<String?> = _connectedServer.asStateFlow()

    private val _connectedSince = MutableStateFlow(0L)
    val connectedSince: StateFlow<Long> = _connectedSince.asStateFlow()

    /**
     * The node this session was DIALLED to: the server, or a Multi-Hop entry.
     * The Servers list marks it "Connected"; it used to mark the selected row,
     * which Auto-Connect, the tile, the widget and a headless start never
     * touch (REVIEW-AND-008).
     */
    private val _connectedServerId = MutableStateFlow<String?>(null)
    val connectedServerId: StateFlow<String?> = _connectedServerId.asStateFlow()

    /**
     * True from a user dial that starts on a live session (a server switch)
     * until it lands: Home reads it to say "Switching server…" instead of a
     * generic "Connecting…" (P1-parity-016).
     */
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    /**
     * Latched when a heartbeat comes back 401 — BirdoRepository has already
     * had the refresh token rejected and discarded it — and cleared by
     * [onSignedIn]. While set, the working tunnel is LEFT UP (iOS parity,
     * A1-035): the account session is dead but the WireGuard peer is not, and
     * blocking the device immediately bought nothing. What it does change is
     * recovery: nothing can re-dial without credentials, so the next drop is
     * terminal ([FailureKind.SIGN_IN_REQUIRED]) instead of a retry loop.
     */
    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    /**
     * The Free plan's grace window, from the last heartbeat (birdo-web PR
     * #590): Home says the connection ends soon, with View plans. Null when
     * the allowance is not used up, and whenever the session is not up.
     */
    private val _quotaGrace = MutableStateFlow<QuotaGrace?>(null)
    val quotaGrace: StateFlow<QuotaGrace?> = _quotaGrace.asStateFlow()

    /**
     * The dial asked for Stealth and the server connected it without, for the
     * plan ([StealthPolicy.Transport.DIRECT_NOT_IN_PLAN]): Home says so while
     * the session is up, so nobody believes their traffic is disguised when
     * it is not. Set by each dial, cleared by a Disconnect.
     */
    private val _stealthNotice = MutableStateFlow<String?>(null)
    val stealthNotice: StateFlow<String?> = _stealthNotice.asStateFlow()

    /**
     * What a dial asks for: the stored setting. The server decides entitlement
     * and the service acts on its answer (StealthPolicy) — no client-side plan
     * gate, which a stale lastKnownPlan turned against a re-upgraded user.
     */
    private fun stealthRequested(): Boolean = prefs.stealthModeEnabled

    /**
     * The stored Stealth setting changed (SettingsViewModel, every writer).
     * The notice describes the request a dial made with the OLD setting, and
     * it also decides rebuild eligibility (liveRebuildEligible): kept past a
     * change, it held that verdict for the whole session (final review of
     * #463, #5).
     */
    fun onStealthSettingChanged() {
        _stealthNotice.value = null
    }

    /** The Home notice for a dial that asked for Stealth and got [config]. */
    private fun stealthNoticeFor(requested: Boolean, config: ConnectResponse): String? =
        SessionCopy.STEALTH_NOT_IN_PLAN.takeIf {
            StealthPolicy.transport(requested, config.stealthEnabled, config.xrayEndpoint, config.stealthUnavailableReason) ==
                StealthPolicy.Transport.DIRECT_NOT_IN_PLAN
        }

    /**
     * The wait the last failed dial's server asked for (a 503
     * quota_check_unavailable says 30 s). The next automatic re-dial waits at
     * least this long; consumed by [onFailure].
     */
    @Volatile private var retryAfterHintMs = 0L

    /** Timestamp when we last entered a transitional state (Connecting/Disconnecting) */
    @Volatile private var transitionStartTime = 0L

    // ── Session supervision ─────────────────────────────────────────

    /**
     * The supervisor's model of the session. Confined to [scope]'s thread
     * (Main), like the rest of this class's mutable state.
     */
    private var session = ReconnectPolicy.Session.IDLE

    /**
     * Bumped by every USER action (connect, disconnect, sign-out) and every
     * headless start. A dial captures it when it begins and re-checks it at
     * each suspension point before it touches the tunnel; a mismatch means a
     * newer action superseded it, so it releases the peer it may have minted
     * and returns without publishing anything.
     *
     * This replaces the old state-based supersede checks, which compared
     * `_state.value is Disconnecting` and so could be defeated three ways:
     * connectWithConfig set Connecting on the line before its own check
     * (A1-006), the notification's Disconnect never went through this class at
     * all (A1-009), and a permission grant replayed a different dial (A1-007).
     */
    @Volatile private var intentGeneration = 0L

    /** Whether the user was protected when the current dial started (see [giveUp]). */
    private var protectedAtDialStart = false

    /**
     * A server the heartbeat said went offline for good (`server_offline`,
     * valid = false): the next automatic re-dial picks another one. Cleared
     * once a session is up and by every user or system dial.
     */
    private var avoidServerId: String? = null

    /** The dial in flight, owned by [scope] so a caller's cancellation cannot strand it (A1-011). */
    private var dialJob: Job? = null
    private var connectWatchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var cooldownJob: Job? = null

    /** The Error this class published as a give-up verdict; see [publishError]. */
    private var verdictError: VpnState.Error? = null

    /** A physical network that is not behind a captive portal exists ([NetworkMonitor.status]). */
    @Volatile private var online = true

    /** Every physical network is behind a captive portal's sign-in page. */
    @Volatile private var captivePortal = false

    /**
     * The WireGuard key id of the session THIS process started. Heartbeats
     * and teardown name it explicitly rather than re-reading the persisted
     * copy, so a late DELETE from a previous session can never clear the id
     * of the next one (A1-005).
     */
    @Volatile private var sessionKeyId: String? = null

    /**
     * The key of an ESTABLISHED session the service's dead-tunnel check just
     * declared dead, until the first re-dial has asked the server about it,
     * around the tunnel ([probeDeadSession], REVIEW-AND2-001). Confined to
     * [scope]'s thread like [session].
     */
    private var deadSessionKey: String? = null

    /**
     * REVIEW-AND2-004: the process-start resume's dial ([generation]) and when
     * it began, offered to the one tap that may have started the process
     * ([claimTapForResume]). Cleared by every user or system dial.
     */
    private data class ResumeTap(val generation: Long, val startedAtMs: Long)
    private val resumeTap = AtomicReference<ResumeTap?>(null)

    /** Clock and dispatcher seams, so the heartbeat and backoff logic run under a test scheduler. */
    internal var elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }
    internal var jitter: () -> Double = { Random.nextDouble(-1.0, 1.0) }
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * REVIEW-AND-012: re-render the home-screen widget. The service's render
     * loop refreshes it only while the service runs, so an API-phase refusal
     * from a widget tap (no service yet) left it on "Connecting… Tap to
     * cancel". A seam so unit tests never drive Glance.
     */
    internal var refreshWidget: suspend () -> Unit = {
        try {
            withContext(Dispatchers.IO) { app.birdo.vpn.widget.BirdoWidget().updateAll(context) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("VpnManager", "Widget refresh failed", e)
        }
    }

    /**
     * MR-938 (REVIEW-AND-012): post and withdraw the alert for a widget tap
     * whose start was refused while no service ran. Its own notification id,
     * so it never replaces or withdraws an alert the service posted. Seams,
     * so unit tests never build a notification.
     */
    internal var postTapRefusedAlert: (VpnNotificationManager.AlertModel) -> Unit = { alert ->
        VpnNotificationManager(context).apply {
            // No service may ever have run in this process to create them.
            createChannels()
            postAlert(alert, VpnNotificationManager.TAP_REFUSED_NOTIFICATION_ID)
        }
    }
    internal var withdrawTapRefusedAlert: () -> Unit = {
        VpnNotificationManager(context).cancelAlert(VpnNotificationManager.TAP_REFUSED_NOTIFICATION_ID)
    }

    // ── Heartbeat keepalive ─────────────────────────────────────────
    private var heartbeatJob: Job? = null
    private val heartbeatMutex = Mutex()
    /** elapsedRealtime of the last `valid = true` beat; see [HeartbeatPolicy]. */
    @Volatile private var lastHeartbeatOkAt = 0L
    /** elapsedRealtime of the last beat attempt, to rate-limit nudges. */
    @Volatile private var lastBeatAt = 0L

    // ── Apply-on-change ─────────────────────────────────────────────
    /**
     * (entryNodeId, exitNodeId) of the ACTIVE multi-hop session, or null for
     * single-hop/none. Needed so a settings reapply rebuilds the same route —
     * `prefs.lastServerId` only tracks single-hop.
     */
    @Volatile private var activeMultiHop: Pair<String, String>? = null

    /**
     * Read-only view of [activeMultiHop] for the UI layer, so a caller can
     * refuse to downgrade a live multi-hop route to a single hop (see
     * `VpnViewModel.selectServer`).
     *
     * NOT a liveness signal on its own, and callers MUST combine it with the
     * connection state. It is set by [connectMultiHop] on API success -- before
     * the tunnel is actually established -- and cleared by a single-hop dial
     * and by [tearDownTunnel]. A teardown the service performs on its own
     * (onRevoke, onDestroy) does not clear it.
     *
     * That staleness is deliberate rather than a bug to fix here: the
     * supervisor reads this field to rebuild the SAME entry -> exit route
     * after a drop, and clearing it on a service-published Disconnected
     * would silently turn a dropped multi-hop session into a single-hop
     * reconnect -- exactly the downgrade the readers exist to prevent.
     *
     * So: "the pair this session would rebuild", not "a session is live now".
     */
    val activeMultiHopRoute: Pair<String, String>?
        get() = activeMultiHop

    /** Debounced settings-changed signals — see [requestSettingsReapply]. */
    private val reapplyRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /**
     * ADAPTIVE TRANSPORT: true while a stealth fallback rebuild is running.
     * Single-threaded by the Main.immediate dispatcher this scope uses, so a
     * plain Boolean is sufficient — see [onTransportBlocked].
     */
    private var fallbackInFlight = false

    /** Epoch ms of the last stealth fallback, for the retrigger cooldown. */
    private var lastFallbackAt = 0L

    /** Where a dial came from; decides whether it bumps [intentGeneration] and owns the session. */
    private enum class DialOrigin { USER, HEADLESS, AUTO_RECONNECT, FALLBACK, REAPPLY }

    companion object {
        /** Max time (ms) to guard transitional states before letting service state through */
        private const val TRANSITION_GUARD_MS = 15_000L
        /**
         * The connect watchdog for the API phase: from Connecting until the
         * START intent reaches the service. A /vpn/connect can cost three
         * round trips (401 → refresh → retry), and Play Integrity has no
         * timeout of its own (A1-033), so the budget is generous; what matters
         * is that it is a TIMER. The old net lived inside the service-state
         * collector and only ran when the service emitted, which it never does
         * for a dial that dies before ACTION_START (A1-011).
         */
        private const val CONNECT_API_TIMEOUT_MS = 45_000L
        /**
         * The watchdog once the service owns the setup: its own connect
         * timeout (30 s) plus TransportProbe.WINDOW_MS (10 s) plus slack, so it
         * never races the service's verdict or the stealth rebuild a BLOCKED
         * verdict kicks off.
         */
        private const val CONNECT_SERVICE_TIMEOUT_MS = 60_000L
        /** Max time (ms) before we force Disconnecting → Disconnected if service is stuck */
        private const val DISCONNECT_STUCK_TIMEOUT_MS = 15_000L
        /**
         * Heartbeat interval. The backend reaps a WireGuard key after 5
         * minutes without a heartbeat (birdo-web cleanup.service.ts:22,
         * `lastSeen` older than 5 min) and throttles the `lastSeen` write to
         * once per 90 s (vpn.service.ts:1884-1899). So a beat that lands under
         * 90 s after the last write is not persisted, and the worst gap
         * between two WRITES is two intervals. At 60 s ±10 % that is at most
         * 132 s; two consecutive lost beats still keep the gap under 270 s,
         * inside the 300 s reap. 90-120 s (the audit's suggestion) would let a
         * single lost beat reach the reap. Still half the old 30 s radio
         * wake-ups (A1-018).
         */
        internal const val HEARTBEAT_INTERVAL_MS = 60_000L
        /** The backend's reap window, above. */
        internal const val REAP_WINDOW_MS = HeartbeatPolicy.REAP_WINDOW_MS
        /** A screen-on or network nudge inside this gap of the last beat is dropped. */
        private const val HEARTBEAT_NUDGE_MIN_GAP_MS = 20_000L
        /**
         * Settle window before a settings change rebuilds the live tunnel.
         * Long enough to collapse a burst (ticking five split-tunnel apps,
         * typing an MTU) into ONE reconnect; short enough to feel immediate.
         */
        private const val SETTINGS_REAPPLY_DEBOUNCE_MS = 1_200L
        /**
         * Bound on a live rebuild's swap: the service's probe window plus its
         * connect watchdog, so the service always reaches a verdict first.
         */
        private const val LIVE_REBUILD_TIMEOUT_MS = LiveRebuildPolicy.PROBE_WINDOW_MS + 30_000L + 5_000L
        /** Bound on the server-side slot release at sign-out; local sign-out proceeds regardless. */
        private const val SIGN_OUT_RELEASE_TIMEOUT_MS = 10_000L
        /**
         * Bound on the dead-tunnel probe ([probeDeadSession]). It delays a
         * re-dial the device is waiting for, behind the block, so it is short;
         * no answer in time is the same as a transport failure: re-dial.
         */
        internal const val DEAD_SESSION_PROBE_TIMEOUT_MS = 5_000L

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

        /** What a superseded dial returns; no state is published for it. */
        internal const val SUPERSEDED = "Connection superseded by a newer action"

        /**
         * The server a quick connect picks: the lowest-load node THIS user's
         * plan can actually use. `accessible` is the server's own verdict
         * (plan rank >= node minPlan), so it covers every plan; the old
         * `!isPremium` filter hard-coded the free user's view and then fell
         * back to ANY online node — which handed paying-plan-less users a
         * node the backend would refuse.
         *
         * Shared with VpnViewModel's pre-selection (A1-023): the Android twin
         * of iOS QuickSelect.bestServer(in:), so the server a new user sees
         * selected is the one a quick connect would dial, not the
         * alphabetically first country (K10).
         */
        fun bestServer(servers: List<VpnServer>): VpnServer? =
            servers.filter { it.isOnline && it.accessible }.minByOrNull { it.load }

        /**
         * The live session state, for the one reader that cannot be injected
         * with this class: the API's call factory, which VpnManager itself
         * depends on (through the repository), so DI would be a cycle. Only
         * the StateFlow is held — no Context, nothing to leak.
         */
        @Volatile private var liveState: StateFlow<VpnState>? = null

        /** VpnManager's state, or Disconnected before it exists (ApiRoutePolicy). */
        fun sessionState(): VpnState = liveState?.value ?: VpnState.Disconnected
    }

    // FIX-2-12: Singleton scope for reactive state collection from the service.
    // Replaces 1-second polling — state changes now propagate immediately.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        liveState = state

        // FIX-2-12: Reactively collect state from BirdoVpnService's StateFlow.
        // Applies the transition guards below; fires immediately on every
        // state change instead of with the ≤1s delay of the old polling.
        scope.launch {
            BirdoVpnService.stateFlow.collect { serviceState ->
                // Guard each emission so an unexpected exception in the guard
                // logic cannot silently kill this collector — it is the sole
                // pipeline propagating service state to the UI (replaced polling).
                try {
                    applyStateWithGuards(serviceState)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // This collector is the sole pipeline from the service to
                    // the UI; an emission it drops is a state the user never
                    // sees. Reported — every Log.e in this file was deleted
                    // from the release build by R8.
                    FaultReporter.report(
                        FaultReporter.PATH_CONNECT,
                        "manager_state_collector_threw",
                        "VpnManager state collector threw while applying a service state",
                        e,
                    )
                }
            }
        }

        // The supervisor: every state this class ends up in is judged here,
        // whoever produced it (a dial, the service, the heartbeat).
        scope.launch {
            _state.collect { vpnState ->
                try {
                    onStateChanged(vpnState)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FaultReporter.report(
                        FaultReporter.PATH_CONNECT,
                        "manager_reconnect_collector_threw",
                        "VpnManager reconnect collector threw",
                        e,
                    )
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FaultReporter.report(
                            FaultReporter.PATH_CONNECT,
                            "manager_settings_reapply_collector_threw",
                            "VpnManager settings-reapply collector threw",
                            e,
                        )
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FaultReporter.report(
                        FaultReporter.PATH_CONNECT,
                        "manager_transport_fallback_threw",
                        "VpnManager adaptive-transport fallback threw",
                        e,
                    )
                }
            }
        }

        // Network-aware recovery: a session waiting for the network (or
        // sitting in a backoff delay) re-dials the moment it returns — whether
        // or not a retry job happens to be running, which is what the old
        // `reconnectJob?.isActive` condition required (A1-001). Physical
        // networks only (NetworkMonitor): our own block or tunnel can neither
        // keep this "online" nor take it "offline". A captive portal counts as
        // not usable — a dial behind one only burns the budget — and passing
        // it is the edge that re-dials (A1-026).
        scope.launch {
            networkMonitor.status
                .distinctUntilChanged()
                .collect { status ->
                    try {
                        online = status == NetworkMonitor.Connectivity.ONLINE
                        captivePortal = status == NetworkMonitor.Connectivity.CAPTIVE_PORTAL
                        if (online) onNetworkAvailable() else onNetworkUnavailable()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FaultReporter.report(
                            FaultReporter.PATH_CONNECT,
                            "manager_network_collector_threw",
                            "VpnManager network-online collector threw",
                            e,
                        )
                    }
                }
        }

        // A-1-017: the heartbeat's delay() measures AWAKE time, so a phone that
        // suspends for minutes sends nothing while the backend's 5-minute reap
        // clock keeps running. The service nudges on screen-on, unlock and
        // every underlying-network change; beating then lets a reaped peer be
        // noticed (and rebuilt) the moment the user is back.
        scope.launch {
            BirdoVpnService.wakeFlow.collect { heartbeatNow() }
        }

        // REVIEW-AND-012: the widget follows THIS state (its model is read
        // from it), including the states only the manager publishes.
        scope.launch {
            _state
                .map { app.birdo.vpn.widget.BirdoWidget.widgetModel(it, BirdoVpnService.killSwitchActive) }
                .distinctUntilChanged()
                .drop(1)
                .collect { refreshWidget() }
        }

        // MR-938: a refused widget tap's alert is true until a session is up.
        // Withdrawn on EVERY arrival at Connected, posted or not: it outlives
        // the process that posted it, and a memory of having posted it does
        // not. Cancelling a notification that is not there costs nothing.
        scope.launch {
            _state
                .map { it is VpnState.Connected }
                .distinctUntilChanged()
                .filter { it }
                .collect { withdrawTapRefusedAlert() }
        }
    }

    fun isVpnPermissionGranted(): Boolean = VpnService.prepare(context) == null

    fun getVpnPermissionIntent(): Intent? = VpnService.prepare(context)

    // ── Dials ─────────────────────────────────────────────────────────

    /**
     * Connect to a VPN server (a user action).
     *
     * @param fallbackReason ADAPTIVE TRANSPORT. Non-null asks the server for the
     *   stealth transport regardless of plan, because plain WireGuard has been
     *   observed failing on this network. Null on an ordinary connect, which
     *   always tries the fast path first.
     */
    suspend fun connect(
        serverId: String,
        fallbackReason: String? = null,
    ): ApiResult<ConnectResponse> = runDial {
        val prior = _state.value
        val gen = beginDial(DialOrigin.USER, prior)
        dialSingle(serverId, fallbackReason, gen, prior)
    }

    /**
     * Connect through an entry and exit server with the same advanced feature
     * contract as single-hop: Stealth and BirdoPQ are requested up front and
     * the service fails closed if the server enables them but the local engine
     * cannot complete setup.
     *
     * @param fallbackReason ADAPTIVE TRANSPORT — see [connect].
     */
    suspend fun connectMultiHop(
        entryNodeId: String,
        exitNodeId: String,
        fallbackReason: String? = null,
    ): ApiResult<MultiHopConnectResponse> = runDial {
        val prior = _state.value
        val gen = beginDial(DialOrigin.USER, prior)
        dialMultiHop(entryNodeId, exitNodeId, fallbackReason, gen, prior)
    }

    /** Quick connect — pick the best online server automatically. */
    suspend fun quickConnect(): ApiResult<ConnectResponse> = runDial {
        val prior = _state.value
        val gen = beginDial(DialOrigin.USER, prior)
        quickDial(gen, prior)
    }

    /**
     * The route a one-tap surface should dial (A1-021): the live or armed
     * Multi-Hop pair, else the user's last server, else the best server. The
     * Quick Settings tile, the widget and the notification's Reconnect action
     * all use this, so none of them substitutes "the lowest-load node
     * anywhere" for the server the user actually chose.
     *
     * A tap that started this process while the process-start resume is
     * still on its way JOINS that resume instead of dialling again
     * (REVIEW-AND2-004): the surface decided on the state it read before the
     * resume began, and a second dial would mint a second peer and supersede
     * the first only after its /connect had gone out.
     *
     * @param multiHopEntitled what the caller knows about the plan: false
     *   dials the single hop the app is drawing for a lapsed plan (see
     *   BirdoTileService); null reads the persisted last-known plan.
     */
    suspend fun connectPreferred(multiHopEntitled: Boolean? = null): ApiResult<Any> {
        if (claimTapForResume()) return ApiResult.Success(Unit)
        return runDial { preferredDial(DialOrigin.USER, multiHopEntitled) }
    }

    /**
     * Fire-and-forget [connectPreferred] for callers with no coroutine of
     * their own: the notification's Reconnect action and the widget, whose
     * broadcast must not wait out an API call.
     *
     * @param alertIfRefused the widget's tap (MR-938, REVIEW-AND-012): a
     *   start refused while no service runs posts the alert the service's
     *   render loop would have posted, had there been one. A tap from idle is
     *   refused before any service exists (a 426, a 401, the device limit, or
     *   a foreground-service start Android would not allow), so it used to
     *   change the widget's text and tell nobody looking at anything else.
     */
    fun requestConnectPreferred(multiHopEntitled: Boolean? = null, alertIfRefused: Boolean = false) {
        scope.launch {
            val result = connectPreferred(multiHopEntitled)
            if (alertIfRefused && result is ApiResult.Error && result.message != SUPERSEDED) {
                alertTapRefused()
            }
        }
    }

    /**
     * The alert for the Error a refused tap left, as the service would word
     * it ([VpnNotificationManager.alertFor]: none while the app is on screen,
     * where Home already says it). Only while no service runs: a running one
     * alerts from its own render loop, and two alerts would sound twice.
     */
    private fun alertTapRefused() {
        if (BirdoVpnService.running) return
        val alert = VpnNotificationManager.alertFor(
            state = _state.value,
            killSwitchActive = BirdoVpnService.killSwitchActive,
            sessionExpired = _sessionExpired.value,
            uiForeground = BirdoVpnService.uiForeground,
        ) ?: return
        postTapRefusedAlert(alert)
    }

    /**
     * A system start (Always-on, a sticky restart, an app update) asks for the
     * session back (A1-014). Runs the same [MultiHopPolicy] and dial as a tap,
     * but the session is HEADLESS-owned, so retryable failures heal
     * themselves — the phone may have booted with no network yet.
     *
     * @param kind which system start this is. A [SystemStartKind.PROCESS_RESTART]
     *   may have been caused by the very tap a tile or widget is about to
     *   deliver, so its dial is offered to that tap ([claimTapForResume]).
     * @return false when a session is already up or in flight, so a platform
     *   start and our own MY_PACKAGE_REPLACED start cannot dial twice.
     */
    fun connectHeadless(kind: SystemStartKind): Boolean {
        if (sessionInProgress()) return false
        val resume = kind == SystemStartKind.PROCESS_RESTART
        scope.launch { runDial { preferredDial(DialOrigin.HEADLESS, multiHopEntitled = null, resume = resume) } }
        return true
    }

    /**
     * REVIEW-AND2-004: whether a tile or widget tap is the one that started
     * this process, and so belongs to the resume that start began.
     *
     * After a crash, the tap on the Quick Settings tile or the widget is what
     * starts the new process; BirdoApp's process-start resume then dials
     * before the tap is decided. Decided on the resume's Connecting, the tile's
     * tap meant DISCONNECT and ended the session it had just brought back; the
     * widget, deciding on the Disconnected it read first, dialled a second time.
     * The tap meant "connect", which is already happening, so it does nothing.
     *
     * At most ONE tap is claimed per resume, and only while that resume's own
     * dial is still connecting and [QuickToggle.RESUME_TAP_WINDOW_MS] old: a
     * later tap is the user's own decision and acts on the state as shown.
     * Thread-safe: the tile decides on the main thread, the widget does not.
     */
    fun claimTapForResume(): Boolean {
        val resume = resumeTap.getAndSet(null) ?: return false
        return QuickToggle.joinsResume(
            resumeAgeMs = elapsedRealtime() - resume.startedAtMs,
            sameDial = resume.generation == intentGeneration,
            state = _state.value,
        )
    }

    /**
     * A session is up or on its way: connected, dialling, recovering or
     * tearing down. A system start that finds one leaves it alone
     * (REVIEW-AND-022: two starts after a reboot used to supersede each
     * other's dial).
     */
    fun sessionInProgress(): Boolean {
        val s = _state.value
        return s is VpnState.Connected || s.isConnectingPhase || s is VpnState.Reconnecting ||
            s is VpnState.Disconnecting || dialJob?.isActive == true
    }

    /**
     * The system started us but the session cannot come up without the user
     * (signed out, consent missing). Publish that as a typed Error so every
     * surface — the notification's "Action needed" alert included — says why.
     */
    fun reportHeadlessBlocked(kind: FailureKind) {
        publishError(SessionCopy.actionNeeded(kind), kind)
    }

    /**
     * Run a dial in [scope], not the caller's coroutine (A1-011). The caller
     * only awaits it: cancelling the tile's scope or the ViewModel's no longer
     * strands this class in Connecting half-way through an API call.
     */
    private suspend fun <T> runDial(block: suspend () -> ApiResult<T>): ApiResult<T> {
        val dial = scope.async { block() }
        dialJob = dial
        return try {
            dial.await()
        } catch (e: CancellationException) {
            // The DIAL was cancelled (the connect watchdog), not the caller: an
            // outcome, and the watchdog has already published it.
            if (dial.isCancelled && currentCoroutineContext().isActive) {
                ApiResult.Error(SessionCopy.NO_TUNNEL)
            } else {
                throw e
            }
        }
    }

    /**
     * Start a dial: bump the intent generation for user and headless starts
     * (so anything older is superseded), cancel pending recovery, and record
     * who owns the new session.
     */
    private fun beginDial(origin: DialOrigin, prior: VpnState): Long {
        when (origin) {
            DialOrigin.USER, DialOrigin.HEADLESS -> {
                intentGeneration++
                cancelRecovery()
                avoidServerId = null
                deadSessionKey = null
                resumeTap.set(null)
                session = if (origin == DialOrigin.USER) {
                    ReconnectPolicy.Session.userDial()
                } else {
                    ReconnectPolicy.Session.headless()
                }
                protectedAtDialStart = prior is VpnState.Connected ||
                    prior is VpnState.Reconnecting || isKillSwitchActive
                _switching.value = origin == DialOrigin.USER &&
                    (prior is VpnState.Connected || prior is VpnState.Reconnecting)
                prefs.sessionShouldBeUp = true
            }
            DialOrigin.AUTO_RECONNECT, DialOrigin.FALLBACK, DialOrigin.REAPPLY -> Unit
        }
        return intentGeneration
    }

    private fun superseded(gen: Long): Boolean = gen != intentGeneration

    /**
     * A1-024: BirdoShield as it will really be: never while Custom DNS
     * replaces the filtering resolver in the tunnel. The server used to be
     * told dnsFiltering = true for a tunnel that never asked its resolver.
     */
    private fun shieldInEffect(): Boolean =
        WireGuardConfigBuilder.shieldInEffect(prefs.dnsFilteringEnabled, prefs.customDnsEnabled)

    /**
     * A1-033: attest only on a user-initiated fresh dial with nothing blocked.
     * Behind the block Play services cannot reach Google (only BirdoVPN is
     * exempt), so a re-dial waited out Play's own timeout fully blocked.
     */
    private fun mayAttest(prior: VpnState): Boolean = AttestationPolicy.mayAttest(
        priorWasLive = prior is VpnState.Connected || prior is VpnState.Reconnecting,
        blockActive = isKillSwitchActive,
        automatic = fallbackInFlight || reapplyInProgress,
    )

    /**
     * The words for a failed API call. A transport failure (code 0) while
     * every network is behind a captive portal is the portal, not the server:
     * say so, instead of the generic "couldn't reach" (A1-026).
     */
    private fun apiErrorCopy(code: Int, message: String): String =
        if (code == 0 && captivePortal) SessionCopy.CAPTIVE_PORTAL else SessionCopy.forApiError(code, message)

    /**
     * A /connect the server refused inside a 2xx. The Free allowance's end is
     * a plan decision and says so (REVIEW-AND2-001): QUOTA_EXCEEDED releases
     * the block and offers View plans, where a generic refusal of a session
     * that was up kept the device blocked behind "can't connect". It is also
     * the second line of defence behind the dead-tunnel probe, for a quota end
     * whose heartbeat reply was lost with the peer.
     */
    private fun refusal(serverMessage: String?, quotaExceeded: Boolean): Pair<String, FailureKind> =
        if (quotaExceeded) {
            (serverMessage?.takeIf { it.isNotBlank() } ?: context.getString(R.string.session_quota_exceeded)) to
                FailureKind.QUOTA_EXCEEDED
        } else {
            (serverMessage ?: SessionCopy.BAD_SERVER_CONFIG) to FailureKind.REFUSED
        }

    /**
     * Server switch / reconnect: fully tear down the existing tunnel +
     * server-side peer BEFORE establishing the new one. Without this, picking
     * a new server (Germany → Amsterdam) leaves the old peer registered and
     * the local tunnel racing the new one. Each connect must be a clean
     * disconnect → fresh keypair → connect cycle, so every session is a new
     * identity.
     *
     * Fail-closed: [switchTeardown] holds the kill-switch blocking interface
     * up while the old peer is unregistered and rebuilt, until the new
     * tunnel's establish() atomically supersedes it. reapplyInProgress forces
     * the block even with the kill switch off, so the settings-apply blip
     * can't leak (see reapplySettingsNow).
     *
     * fallbackInFlight forces it for the same reason, and the case is
     * stronger. A settings reapply and a server switch are things the USER
     * asked for; an Adaptive Transport fallback is not. The app decided on its
     * own to tear down a tunnel the user was told was "Connected", and at that
     * moment their traffic is stalled inside a tunnel that never handshook —
     * so every app on the device has requests queued and waiting. Dropping the
     * interface without the block would release that backlog onto the
     * physical interface in cleartext, in one burst, for a user who believes
     * they are protected and did not ask for any of it.
     *
     * @return false when a newer action superseded the dial during the wait.
     */
    private suspend fun teardownLiveSession(prior: VpnState, gen: Long): Boolean {
        if (prior !is VpnState.Connected && !prior.isConnectingPhase && prior !is VpnState.Reconnecting) {
            return true
        }
        switchTeardown(forceBlock = reapplyInProgress || fallbackInFlight)
        // Wait (bounded) for the service to confirm the old tunnel is down so
        // wg-go is torn down before we register + bring up the new one. The
        // blocking interface stays up throughout this wait (and the /connect
        // call that follows).
        waitUntil(5000) { _state.value is VpnState.Disconnected }
        return !superseded(gen)
    }

    /**
     * Publish Connecting and arm the API-phase watchdog. A TIMER, so a dial
     * that dies before ACTION_START cannot leave the UI in Connecting forever.
     */
    private fun enterConnecting(gen: Long) {
        _state.value = VpnState.Connecting
        transitionStartTime = System.currentTimeMillis()
        armConnectWatchdog(gen, CONNECT_API_TIMEOUT_MS)
    }

    private fun armConnectWatchdog(gen: Long, timeoutMs: Long) {
        connectWatchdogJob?.cancel()
        connectWatchdogJob = scope.launch {
            delay(timeoutMs)
            val s = _state.value
            if (!superseded(gen) && (s.isConnectingPhase || s is VpnState.Disconnecting)) {
                dialJob?.cancel()
                publishError(SessionCopy.NO_TUNNEL, FailureKind.NEVER_ESTABLISHED)
            }
        }
    }

    private suspend fun dialSingle(
        serverId: String,
        fallbackReason: String?,
        gen: Long,
        prior: VpnState,
        allowLiveRebuild: Boolean = true,
    ): ApiResult<ConnectResponse> {
        // A1-034: a switch or a settings reapply on a live single-hop session
        // rebuilds it in place instead of tearing it down to the block.
        if (allowLiveRebuild && fallbackReason == null && activeMultiHop == null && liveRebuildEligible(prior)) {
            return liveRebuildSingle(serverId, gen, prior)
        }
        if (!teardownLiveSession(prior, gen)) return ApiResult.Error(SUPERSEDED)

        enterConnecting(gen)
        // This is THE single-hop path — the session is now single-hop. Clear the
        // multi-hop route up front (not only on success) so a FAILED switch away
        // from a live multi-hop session can't leave auto-reconnect rebuilding the
        // stale double-hop route instead of this server.
        activeMultiHop = null

        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        // Upload our ML-KEM-1024 client public key when quantum protection is
        // enabled so the server can encapsulate against it (BirdoPQ v1).
        val pqClientPublicKey: String? = if (prefs.quantumProtectionEnabled) {
            BirdoPqManager.getClientPublicKeyB64(context) ?: run {
                // RosenpassNative/BirdoPqManager report the cause; this is
                // the count of users refused a connection because of it.
                FaultReporter.report(
                    FaultReporter.PATH_QUANTUM,
                    "connect_refused_pq_engine_unavailable",
                    "Refused to connect: quantum protection is on and the PQ engine could not supply a client public key",
                )
                publishError(SessionCopy.QUANTUM_FAILED, FailureKind.QUANTUM_FAILED)
                return ApiResult.Error(SessionCopy.QUANTUM_FAILED)
            }
        } else null

        val integrityToken = if (mayAttest(prior)) requestAttestationToken() else null

        // ADAPTIVE TRANSPORT: an explicit retry reason wins; otherwise, if a
        // recent fallback proved this network filters WireGuard, skip straight
        // to the transport that works instead of making the user watch another
        // probe window fail. The preference expires (see shouldStartOnStealth),
        // so the fast path is re-tested rather than abandoned.
        val effectiveFallbackReason = fallbackReason
            ?: TransportFallbackReason.TRANSPORT_BLOCKED.takeIf { prefs.shouldStartOnStealth }

        if (superseded(gen)) return ApiResult.Error(SUPERSEDED)
        // Asked once, and the same answer goes to the service with the config.
        val askedForStealth = stealthRequested()
        val result = repository.connectVpn(
            serverNodeId = serverId,
            deviceName = deviceName,
            stealthMode = askedForStealth,
            fallbackReason = effectiveFallbackReason,
            quantumProtection = prefs.quantumProtectionEnabled,
            pqClientPublicKey = pqClientPublicKey,
            integrityToken = integrityToken,
            // BirdoShield (D18): read at dial time like stealth, so a flip while
            // connected reaches the server on the reapply reconnect, not before.
            dnsFiltering = shieldInEffect(),
        )

        when (result) {
            is ApiResult.Success -> {
                val config = result.data
                if (!config.success || config.privateKey == null ||
                    config.serverPublicKey == null || config.endpoint == null ||
                    config.assignedIp == null
                ) {
                    val (message, kind) = refusal(config.message, config.quotaExceeded)
                    if (!superseded(gen)) publishError(message, kind)
                    return ApiResult.Error(message)
                }

                // Supersede guard: a Disconnect, sign-out or newer dial landed
                // during the /connect round trip. Do NOT bring the tunnel up
                // against it; release exactly the peer this call minted — by
                // its own key id, so a newer session's peer is never touched.
                if (superseded(gen)) {
                    releasePeer(config.keyId)
                    return ApiResult.Error(SUPERSEDED)
                }

                if (!startServiceFor(config, gen, askedForStealth)) {
                    return ApiResult.Error(SessionCopy.ENGINE_FAILED)
                }
                _stealthNotice.value = stealthNoticeFor(askedForStealth, config)
                // Don't set Connected here — the service publishes it once a
                // WireGuard handshake is observed. We stay in Connecting.
                _connectedServer.value = config.serverNode?.name ?: "Unknown Server"
                _connectedServerId.value = serverId
                prefs.lastServerId = serverId
                return result
            }
            is ApiResult.Error -> {
                if (!superseded(gen)) {
                    retryAfterHintMs = result.retryAfterMs ?: 0L
                    publishError(
                        apiErrorCopy(result.code, result.message),
                        FailureKind.fromHttpStatus(result.code),
                    )
                }
                return result
            }
        }
    }

    private suspend fun dialMultiHop(
        entryNodeId: String,
        exitNodeId: String,
        fallbackReason: String?,
        gen: Long,
        prior: VpnState,
        allowLiveRebuild: Boolean = false,
    ): ApiResult<MultiHopConnectResponse> {
        // A1-034, for a settings reapply of the SAME pair only (the caller
        // opts in). A user's Multi-Hop dial over a live session keeps today's
        // path, as on iOS: the node-agent allows one exit per entry, and the
        // server answers a moved exit with same-entry-exit-change anyway.
        if (allowLiveRebuild && fallbackReason == null && liveRebuildEligible(prior)) {
            return liveRebuildMultiHop(entryNodeId, exitNodeId, gen, prior)
        }
        // Mirror the single-hop fail-closed teardown: the Adaptive Transport
        // rebuild and a reconnect arrive over a LIVE (or dead-but-held)
        // session, so the old tunnel + server-side peers must come down behind
        // the blocking interface before the new two-node setup registers fresh
        // peers.
        if (!teardownLiveSession(prior, gen)) return ApiResult.Error(SUPERSEDED)

        enterConnecting(gen)

        val pqClientPublicKey: String? = if (prefs.quantumProtectionEnabled) {
            BirdoPqManager.getClientPublicKeyB64(context) ?: run {
                // Twin of the single-hop connect_refused_pq_engine_unavailable.
                FaultReporter.report(
                    FaultReporter.PATH_QUANTUM,
                    "multihop_refused_pq_engine_unavailable",
                    "Refused to connect multi-hop: quantum protection is on and the PQ engine could not supply a client public key",
                )
                publishError(SessionCopy.QUANTUM_FAILED, FailureKind.QUANTUM_FAILED)
                return ApiResult.Error(SessionCopy.QUANTUM_FAILED)
            }
        } else null

        val integrityToken = if (mayAttest(prior)) requestAttestationToken() else null

        // Same skip-the-doomed-probe logic as the single hop: a recent fallback
        // proved this network filters WireGuard, so ask for stealth up front.
        val effectiveFallbackReason = fallbackReason
            ?: TransportFallbackReason.TRANSPORT_BLOCKED.takeIf { prefs.shouldStartOnStealth }

        if (superseded(gen)) return ApiResult.Error(SUPERSEDED)
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val askedForStealth = stealthRequested()
        val result = repository.connectMultiHop(
            entryNodeId = entryNodeId,
            exitNodeId = exitNodeId,
            deviceName = deviceName,
            stealthMode = askedForStealth,
            fallbackReason = effectiveFallbackReason,
            quantumProtection = prefs.quantumProtectionEnabled,
            pqClientPublicKey = pqClientPublicKey,
            integrityToken = integrityToken,
            // BirdoShield (D18) — the multi-hop twin of the single hop's flag.
            dnsFiltering = shieldInEffect(),
        )

        when (result) {
            is ApiResult.Success -> {
                val config = result.data
                // The supersede check sits directly after the API call, before
                // anything is recorded. It used to live in connectWithConfig,
                // one line after `_state.value = Connecting`, where it could
                // never be true (A1-006).
                if (superseded(gen)) {
                    releasePeer(config.keyId)
                    return ApiResult.Error(SUPERSEDED)
                }
                if (!config.success || config.privateKey == null ||
                    config.serverPublicKey == null || config.endpoint == null ||
                    config.assignedIp == null
                ) {
                    val (message, kind) = refusal(config.message, config.quotaExceeded)
                    publishError(message, kind)
                    return ApiResult.Error(message)
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
                    // The jurisdiction-leak guard. A refusal here is a backend
                    // contract violation, and the user cannot observe their own
                    // egress country — this client is the only witness.
                    FaultReporter.report(
                        FaultReporter.PATH_CONNECT,
                        "multihop_route_unconfirmed",
                        "Refused multi-hop: the server reported success without a route block",
                    )
                    releasePeer(config.keyId)
                    publishError(msg, FailureKind.REFUSED)
                    return ApiResult.Error(msg)
                }
                if (mh.entryNode.id != entryNodeId || mh.exitNode.id != exitNodeId) {
                    // The server's route string is not shown: it is server text.
                    val msg = "The server established a different Multi-Hop route than the one " +
                        "selected. Not connecting."
                    // Node ids deliberately not sent — the fact is the signal.
                    FaultReporter.report(
                        FaultReporter.PATH_CONNECT,
                        "multihop_route_mismatch",
                        "Refused multi-hop: the server established a different route than the one requested",
                    )
                    android.util.Log.d(
                        "VpnManager",
                        "multi-hop route mismatch: asked ${entryNodeId}->${exitNodeId}, " +
                            "got ${mh.entryNode.id}->${mh.exitNode.id}",
                    )
                    releasePeer(config.keyId)
                    publishError(msg, FailureKind.REFUSED)
                    return ApiResult.Error(msg)
                }

                // Record the route from the REQUEST (the response's multiHop
                // block is nullable in the schema) so a reapply/auto-reconnect
                // rebuilds multi-hop, not a silent single-hop downgrade — but
                // ONLY on success, mirroring single-hop's lastServerId, so a
                // FAILED cold-start multi-hop connect can't arm a futile
                // auto-reconnect storm.
                activeMultiHop = entryNodeId to exitNodeId
                if (!startServiceFor(config.toConnectResponse(), gen, askedForStealth)) {
                    return ApiResult.Error(SessionCopy.ENGINE_FAILED)
                }
                _stealthNotice.value = stealthNoticeFor(askedForStealth, config.toConnectResponse())
                _connectedServer.value = "${mh.entryNode.name} → ${mh.exitNode.name}"
                _connectedServerId.value = entryNodeId
                return result
            }
            is ApiResult.Error -> {
                if (!superseded(gen)) {
                    retryAfterHintMs = result.retryAfterMs ?: 0L
                    publishError(
                        apiErrorCopy(result.code, result.message),
                        FailureKind.fromHttpStatus(result.code),
                    )
                }
                return result
            }
        }
    }

    /**
     * The service speaks [ConnectResponse]. The multi-hop response has no
     * `serverNode`, so one is synthesised from the confirmed route: without
     * it the service named the session "Unknown", and the notification, tile
     * and widget showed that to Multi-Hop customers (A1-019). The id is the
     * ENTRY node — the only peer this device handshakes with (iOS records
     * connectedServerId the same way).
     */
    private fun MultiHopConnectResponse.toConnectResponse(): ConnectResponse = ConnectResponse(
        success = success,
        message = message,
        config = config,
        keyId = keyId,
        privateKey = privateKey,
        publicKey = publicKey,
        presharedKey = presharedKey,
        assignedIp = assignedIp,
        // Preserve the tunnel IPv6 address on multi-hop too — omitting it
        // left ConnectResponse.clientIpv6 null, so buildVpnInterface never
        // added a v6 address and IPv6 was blackholed for the whole session
        // even through an IPv6-enabled exit (single-hop already carries it).
        clientIpv6 = clientIpv6,
        serverPublicKey = serverPublicKey,
        endpoint = endpoint,
        dns = dns,
        allowedIps = allowedIps,
        mtu = mtu,
        persistentKeepalive = persistentKeepalive,
        serverNode = multiHop?.let {
            ServerNodeInfo(id = it.entryNode.id, name = "${it.entryNode.name} → ${it.exitNode.name}")
        },
        stealthEnabled = stealthEnabled,
        // Dropped here, the service read a plan downgrade on a Multi-Hop dial
        // as "not granted" and refused it.
        stealthUnavailableReason = stealthUnavailableReason,
        xrayEndpoint = xrayEndpoint,
        xrayUuid = xrayUuid,
        xrayPublicKey = xrayPublicKey,
        xrayShortId = xrayShortId,
        xraySni = xraySni,
        xrayFlow = xrayFlow,
        quantumEnabled = quantumEnabled,
        rosenpassPublicKey = rosenpassPublicKey,
        rosenpassEndpoint = rosenpassEndpoint,
    )

    /**
     * Hand a validated config to the service. Guarded against
     * ForegroundServiceStartNotAllowedException and the "didn't call
     * startForeground in time" crash, which can fire when a foreground service
     * is (re)started rapidly during a server switch: a recoverable error
     * instead of a crash.
     */
    private fun startServiceFor(config: ConnectResponse, gen: Long, askedForStealth: Boolean): Boolean {
        BirdoVpnService.setConfig(config)
        val intent = Intent(context, BirdoVpnService::class.java).apply {
            action = BirdoVpnService.ACTION_START
            putExtra(BirdoVpnService.EXTRA_KILL_SWITCH, prefs.killSwitchEnabled)
            putExtra(BirdoVpnService.EXTRA_STEALTH_REQUESTED, askedForStealth)
            putExtra(BirdoVpnService.EXTRA_SPLIT_TUNNEL_ENABLED, prefs.splitTunnelingEnabled)
            putExtra(BirdoVpnService.EXTRA_SPLIT_TUNNEL_APPS, prefs.splitTunnelApps.toTypedArray())
        }
        try {
            sendToService(intent)
        } catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "service_start_dispatch_failed",
                "Dispatching START threw — the connect never reached the service",
                e,
            )
            releasePeer(config.keyId)
            publishError(SessionCopy.ENGINE_FAILED, FailureKind.TRANSIENT)
            return false
        }
        sessionsBuilt++
        sessionKeyId = config.keyId
        // The service owns the setup from here, with its own watchdog; ours
        // stays as the backstop.
        armConnectWatchdog(gen, CONNECT_SERVICE_TIMEOUT_MS)
        return true
    }

    /**
     * Send an intent to the service. A plain startService() while it is
     * already running and foreground — that is never a foreground-service
     * START, so the Android 12+ background-start restriction cannot refuse a
     * re-dial the supervisor makes with the screen off — and
     * startForegroundService() only when it has to be created.
     */
    private fun sendToService(intent: Intent) {
        if (BirdoVpnService.running) context.startService(intent) else context.startForegroundService(intent)
    }

    private suspend fun quickDial(gen: Long, prior: VpnState, exclude: String? = null): ApiResult<ConnectResponse> {
        // Connecting is published for the server lookup so every surface shows
        // the dial at once; dialSingle is handed the state from BEFORE it, so
        // a quick connect from idle no longer mistakes its own Connecting for
        // a live session and runs a switch teardown (A1-022).
        enterConnecting(gen)
        val serversResult = repository.getServers()
        if (serversResult is ApiResult.Error) {
            if (!superseded(gen)) {
                publishError(
                    apiErrorCopy(serversResult.code, serversResult.message),
                    FailureKind.fromHttpStatus(serversResult.code),
                )
            }
            return ApiResult.Error(serversResult.message, serversResult.code)
        }
        if (superseded(gen)) return ApiResult.Error(SUPERSEDED)
        // [exclude]: a server the heartbeat said went offline for good.
        val servers = (serversResult as ApiResult.Success).data.filter { it.id != exclude }
        val bestServer = bestServer(servers)
        if (bestServer == null) {
            publishError(SessionCopy.NO_SERVERS, FailureKind.REFUSED)
            return ApiResult.Error(SessionCopy.NO_SERVERS)
        }
        return dialSingle(bestServer.id, null, gen, prior)
    }

    private suspend fun preferredDial(
        origin: DialOrigin,
        multiHopEntitled: Boolean?,
        resume: Boolean = false,
    ): ApiResult<Any> {
        val prior = _state.value
        val gen = beginDial(origin, prior)
        if (resume) resumeTap.set(ResumeTap(gen, elapsedRealtime()))
        // ONE entitlement rule for every dial (REVIEW-AND-007): what the caller
        // knows, else the persisted last-known plan. Headless and quick dials
        // used to read the raw pref, which a lapsed plan never clears, so an
        // ex-Sovereign user with Always-on booted into a refused Multi-Hop dial
        // and a held block every time.
        val entitled = multiHopEntitled ?: MultiHopPolicy.entitledByPlan(prefs.lastKnownPlan)
        val armed = prefs.multiHopEnabled && entitled != false
        // The live or last route rebuilds that pair — but only while Multi-Hop
        // is still armed and not known to be lapsed (REVIEW-AND-019).
        val route = activeMultiHop?.takeIf { armed }
        val decision = if (route != null) {
            MultiHopPolicy.NewConnection.MultiHop(route.first, route.second)
        } else {
            MultiHopPolicy.forNewConnection(armed, prefs.multiHopEntryNodeId, prefs.multiHopExitNodeId)
        }
        if (decision is MultiHopPolicy.NewConnection.MultiHop && entitled == null) {
            // Armed, plan unknown: neither guess. A single hop would silently
            // downgrade a paying user's jurisdiction; Multi-Hop would dead-end a
            // lapsed one. Opening the app loads the plan.
            publishError(SessionCopy.SETUP_REQUIRED, FailureKind.SETUP_REQUIRED)
            return ApiResult.Error(SessionCopy.SETUP_REQUIRED)
        }
        return when (decision) {
            is MultiHopPolicy.NewConnection.MultiHop ->
                dialMultiHop(decision.entryNodeId, decision.exitNodeId, null, gen, prior)
            // Armed but incomplete: refuse rather than quietly substitute a
            // single hop — the downgrade the policy exists to prevent.
            MultiHopPolicy.NewConnection.RefuseIncompletePair -> {
                publishError(SessionCopy.INCOMPLETE_MULTI_HOP, FailureKind.REFUSED)
                ApiResult.Error(SessionCopy.INCOMPLETE_MULTI_HOP)
            }
            MultiHopPolicy.NewConnection.SingleHop -> {
                val last = prefs.lastServerId
                if (last != null) dialSingle(last, null, gen, prior) else quickDial(gen, prior)
            }
        }
    }

    /** Re-dial the session the supervisor is recovering, without bumping the generation. */
    private suspend fun redial() {
        val gen = beginDial(DialOrigin.AUTO_RECONNECT, _state.value)
        // The first re-dial after an established session died asks the
        // server why, around the tunnel, before it mints anything: a key the
        // server ended on purpose must not be dialled back (REVIEW-AND2-001).
        val deadKey = deadSessionKey?.takeIf { it == sessionKeyId }
        deadSessionKey = null
        if (deadKey != null) {
            val verdict = probeDeadSession(deadKey)
            // A Disconnect or a newer dial landed while the probe was out.
            if (superseded(gen) || !session.wantUp) return
            val redialHere = when (verdict) {
                HeartbeatPolicy.Verdict.ALIVE, HeartbeatPolicy.Verdict.REAPED -> true
                // A single hop moves to another server below; a Multi-Hop route
                // cannot be re-chosen for the user, so onHeartbeatVerdict stops it.
                HeartbeatPolicy.Verdict.SERVER_GONE -> activeMultiHop == null
                // Evicted, revoked, the Free allowance's end: stop, under the
                // owner rule for the block, and never re-dial.
                HeartbeatPolicy.Verdict.EVICTED, HeartbeatPolicy.Verdict.REVOKED,
                HeartbeatPolicy.Verdict.QUOTA_EXCEEDED -> false
            }
            if (!redialHere) {
                onHeartbeatVerdict(verdict)
                return
            }
            if (verdict == HeartbeatPolicy.Verdict.SERVER_GONE) avoidServerId = prefs.lastServerId
        }
        val prior = _state.value
        val route = activeMultiHop
        val last = prefs.lastServerId
        when {
            route != null -> dialMultiHop(route.first, route.second, null, gen, prior)
            // The heartbeat said this server went offline: the best other one.
            last != null && last == avoidServerId -> quickDial(gen, prior, exclude = last)
            last != null -> dialSingle(last, null, gen, prior)
            else -> quickDial(gen, prior)
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
     *     cannot cycle. Beyond it we stop and let the supervisor own the
     *     failure.
     *  4. The server enforces its own per-device hourly grant ceiling, so even a
     *     client bug cannot turn into fleet load.
     *
     * Multi-hop sessions rebuild too: the probe fires only AFTER the two-node
     * setup completed (the tunnel is up, just not handshaking), so there is no
     * setup to race, and all four brakes above apply identically.
     */
    private suspend fun onTransportBlocked() {
        val serverId = prefs.lastServerId
        val multiHop = activeMultiHop
        if (serverId == null && multiHop == null) {
            android.util.Log.i(
                "VpnManager",
                "Transport blocked but no session to rebuild — leaving it to the supervisor",
            )
            return
        }
        if (fallbackInFlight) return

        val now = System.currentTimeMillis()
        if (now - lastFallbackAt < FALLBACK_COOLDOWN_MS) {
            android.util.Log.w(
                "VpnManager",
                "Transport blocked again inside the cooldown — not retrying. " +
                    "Both transports appear to be failing; the supervisor owns this now.",
            )
            return
        }

        fallbackInFlight = true
        lastFallbackAt = now
        try {
            android.util.Log.w("VpnManager", "Falling back to the stealth transport")
            val prior = _state.value
            val gen = beginDial(DialOrigin.FALLBACK, prior)
            // The multi-hop route describes the CURRENT session when set (a
            // single-hop dial clears it, a multi-hop dial records it on
            // success), so it wins over the last single-hop server id.
            val dialled = runDial {
                if (multiHop != null) {
                    dialMultiHop(multiHop.first, multiHop.second, TransportFallbackReason.HANDSHAKE_TIMEOUT, gen, prior)
                } else {
                    dialSingle(serverId!!, TransportFallbackReason.HANDSHAKE_TIMEOUT, gen, prior)
                }
            } is ApiResult.Success
            // Only remember the preference when the fallback actually produced a
            // WORKING stealth tunnel (A1-031). The /connect reply only says the
            // server granted stealth — before the tunnel has handshaked — and
            // recording it then switched every connect on this device to the
            // slower transport for 24 h after a node that was simply down.
            val stealthWorked = dialled &&
                waitUntil(CONNECT_SERVICE_TIMEOUT_MS) { !_state.value.isConnectingPhase } &&
                StealthPreference.provenBy(_state.value, BirdoVpnService.stealthActive) &&
                !superseded(gen)
            if (stealthWorked) {
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

    // ── A1-034: the in-place live rebuild ─────────────────────────────

    /** Matches a service outcome to the rebuild that asked for it. */
    private var liveRebuildCounter = 0L

    /**
     * A live rebuild is deciding (its /connect through the live tunnel, then
     * the swap). An Error that lands meanwhile — the old tunnel died under it
     * — is HELD (a [RebuildHold]), not fed to the supervisor, because the
     * rebuild's outcome may itself be the recovery (second-pass review of
     * #463, NEW-1). The supervisor acting in parallel either gave a user's
     * switch up as a dial that "never connected" (clearing the session intent,
     * so the legacy dial that followed was never re-dialled after a drop and
     * nothing resumed it after process death), or scheduled a re-dial racing
     * the legacy one: two STARTs, two peers. Confined to [scope]'s thread,
     * like the rest of the supervisor's state.
     */
    private class RebuildHold {
        /** The Error that landed while this rebuild owned the hold. */
        var held: VpnState.Error? = null
        /** The swap is with the service, which will answer it. */
        var swapping = false
        /** The old tunnel died before the swap was sent: the /connect is cut short. */
        var cutShort = false
        /** The rebuild's /connect while it is out ([rebuildRequest]). */
        var request: Job? = null
    }

    /**
     * The rebuild that owns the hold: the NEWEST one. Two rebuilds overlap
     * when the user taps a second server while the first one's /connect is
     * out; a shared flag let the first one's finally clear it in the middle
     * of the second one's window, and the second one's Error reached the
     * supervisor after all (final review of #463, #1). Each rebuild releases
     * the hold only if it still owns it, and settles only what it held.
     */
    private var rebuildHold: RebuildHold? = null

    /**
     * The rebuild's /connect, cut short when the old tunnel dies under it
     * (final review of #463, #4). The request rides that tunnel, so it would
     * only time out — the API's 45 s callTimeout — while the device waited
     * dead. Null when cut short; the rebuild then takes today's path at once.
     */
    private suspend fun <T> rebuildRequest(hold: RebuildHold, call: suspend () -> ApiResult<T>): ApiResult<T>? =
        coroutineScope {
            val request = async { call() }
            hold.request = request
            try {
                request.await()
            } catch (e: CancellationException) {
                if (hold.cutShort && isActive) null else throw e
            } finally {
                hold.request = null
            }
        }

    /** Take the hold for a rebuild that is starting. */
    private fun holdFailuresForRebuild(): RebuildHold = RebuildHold().also { rebuildHold = it }

    /** Give the hold back — only if [hold] still owns it (a newer rebuild may). */
    private fun releaseRebuildHold(hold: RebuildHold) {
        if (rebuildHold === hold) rebuildHold = null
    }

    /**
     * The rebuild threw or was cancelled before it could decide (final review
     * of #463, #3): what it held still reaches the supervisor, if it is still
     * the state. It used to be skipped, and the failure was never recovered.
     */
    private fun abandonRebuildHold(hold: RebuildHold) {
        releaseRebuildHold(hold)
        val held = hold.held ?: return
        hold.held = null
        if (_state.value == held) onStateChanged(held)
    }

    /**
     * After a live rebuild decided: today's path is the one recovery for a
     * held failure (it dials now); otherwise the supervisor takes the failure
     * after all, if it is still the state — a kept session that had died, a
     * swap that failed closed, an abandoned rebuild. A committed new session
     * published Connected over it, so there is nothing to recover.
     *
     * A TERMINAL held failure is never swallowed by today's path (final
     * review of #463, #2): a re-dial cannot fix a takeover by another VPN, a
     * revoke or a sign-in, and running one replaced the takeover's own message
     * with a misleading kill-switch alert. It goes to the supervisor, and it
     * is returned so the caller skips the legacy dial.
     *
     * Nor is a request that was cut short while the device is OFFLINE
     * (round 6, P3-1): today's dial would fail at once, and as a fresh user
     * dial it "never connected", so the supervisor gave the session up
     * (NEVER_CONNECTED, the intent cleared) where the same drop outside a
     * rebuild waits for the network. The session the rebuild was moving WAS
     * up, so the supervisor takes the failure as that session's: it waits for
     * the network and re-dials when it returns.
     *
     * That re-dial is the OLD session's (prefs.lastServerId, set only on a
     * successful dial), so the switch is over here (round 7): [switching]
     * drops, as when KEEP_OLD_SESSION keeps it, or the wait and the re-dial
     * showed "Switching server…" with a Cancel. The caller gets the held
     * failure as its error, which is what puts the screen's selection back
     * on the server being re-dialled.
     *
     * The same holds when nothing was held: a /connect that got no answer
     * because there is no network ([unanswered], REQUEST_UNANSWERED while
     * offline) over a tunnel that still shows Connected. Today's dial failed
     * at once there too and gave the session up as NEVER_CONNECTED, the block
     * kept and nothing re-dialling (review of #463). The supervisor takes it
     * as a transient failure of the session that was up, which waits for the
     * network; the old tunnel stays up meanwhile (nothing leaves the device
     * while it is offline), and the re-dial on the online edge replaces it.
     * No Error is published for it: the wait itself is the state.
     *
     * @param unanswered the rebuild's request failure when its event was
     *   REQUEST_UNANSWERED, else null.
     * @return the message of the failure that stops today's path, or null.
     */
    private fun settleHeldFailure(
        hold: RebuildHold,
        directive: LiveRebuildPolicy.Directive,
        unanswered: ApiResult.Error? = null,
    ): String? {
        val legacy = directive == LiveRebuildPolicy.Directive.LEGACY_TEARDOWN
        val held = hold.held
        if (held == null) {
            if (!legacy || unanswered == null || online) return null
            FaultReporter.trail(FaultReporter.PATH_CONNECT, "live rebuild unanswered while offline: waiting for the network")
            endSwitchAsOldSession()
            stopHeartbeat()
            onFailure(FailureKind.TRANSIENT)
            return apiErrorCopy(unanswered.code, unanswered.message)
        }
        hold.held = null
        val offline = legacy && hold.cutShort && !online
        if (legacy && !held.kind.terminal && !offline) return null
        if (offline) endSwitchAsOldSession()
        if (_state.value == held) onStateChanged(held)
        return held.message.takeIf { legacy }
    }

    /**
     * The session a rebuild was moving WAS up: its failure is that session's
     * (healed under the established budget, waiting out an offline spell), and
     * the switch is over — the re-dial is the old session's
     * (prefs.lastServerId), not "Switching server…" with a Cancel.
     */
    private fun endSwitchAsOldSession() {
        session = session.connected()
        _switching.value = false
    }

    /** The rebuild's failed request when it got no HTTP answer at all ([settleHeldFailure]). */
    private fun unansweredRequest(event: LiveRebuildPolicy.Event, result: ApiResult<*>?): ApiResult.Error? =
        (result as? ApiResult.Error)?.takeIf { event == LiveRebuildPolicy.Event.REQUEST_UNANSWERED }

    private fun liveRebuildEligible(prior: VpnState): Boolean = LiveRebuildPolicy.eligible(
        sessionConnected = prior is VpnState.Connected,
        currentKeyId = sessionKeyId,
        stealthActive = BirdoVpnService.stealthActive,
        // A dial the server already answered "not in your plan" runs direct:
        // counting it as Stealth-wanted sent every switch and settings change
        // of a downgraded user through the legacy teardown, a blackout each.
        stealthWanted = (stealthRequested() && _stealthNotice.value == null) || prefs.shouldStartOnStealth,
        blockActive = isKillSwitchActive,
    )

    /**
     * The ML-KEM public key for a rebuild's /connect, or the event that keeps
     * the old session: a PQ engine that cannot supply one must not end a
     * session that is up (A1-034), it only refuses the change.
     */
    private suspend fun rebuildPqKey(): Pair<String?, LiveRebuildPolicy.Event?> {
        if (!prefs.quantumProtectionEnabled) return null to null
        val key = BirdoPqManager.getClientPublicKeyB64(context)
            ?: return null to LiveRebuildPolicy.Event.REQUEST_FAILED
        return key to null
    }

    private suspend fun liveRebuildSingle(serverId: String, gen: Long, prior: VpnState): ApiResult<ConnectResponse> {
        val oldKey = sessionKeyId ?: return dialSingle(serverId, null, gen, prior, allowLiveRebuild = false)
        val hold = holdFailuresForRebuild()
        val (result, event) = try {
            val (pqKey, pqRefusal) = rebuildPqKey()
            // Through the live tunnel (ApiRoutePolicy: still Connected), naming the
            // key it rides. No attestation: the session is live, and attestation is
            // a property of the install, not of each switch (A1-033).
            val result = if (pqRefusal != null || hold.cutShort) {
                null
            } else {
                rebuildRequest(hold) {
                    repository.connectVpn(
                        serverNodeId = serverId,
                        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        stealthMode = false,
                        fallbackReason = null,
                        quantumProtection = prefs.quantumProtectionEnabled,
                        pqClientPublicKey = pqKey,
                        integrityToken = null,
                        dnsFiltering = shieldInEffect(),
                        rebuildOf = oldKey,
                    )
                }
            }
            val config = (result as? ApiResult.Success)?.data
            result to when {
                superseded(gen) -> LiveRebuildPolicy.Event.SUPERSEDED
                pqRefusal != null -> pqRefusal
                // The old tunnel died under the request: no answer is coming
                // through it. Today's path, now.
                hold.cutShort && config == null -> LiveRebuildPolicy.Event.REQUEST_UNANSWERED
                config == null -> (result as? ApiResult.Error)
                    ?.let { LiveRebuildPolicy.forRequestFailure(it.code, it.reason) }
                    ?: LiveRebuildPolicy.Event.REQUEST_FAILED
                !config.success -> LiveRebuildPolicy.forRefusal(config.rebuildRefused)
                !LiveRebuildPolicy.deferralHonoured(oldKey, config.deferredKeyId) ->
                    LiveRebuildPolicy.Event.DEFERRAL_NOT_HONOURED
                config.privateKey == null || config.serverPublicKey == null || config.endpoint == null ||
                    config.assignedIp == null -> LiveRebuildPolicy.Event.FAILED_BEFORE_SWAP
                else -> {
                    hold.swapping = true
                    swapInService(config, gen, oldKey)
                }
            }
        } catch (t: Throwable) {
            abandonRebuildHold(hold)
            throw t
        }
        releaseRebuildHold(hold)
        val config = (result as? ApiResult.Success)?.data
        val directive = finishLiveRebuild(event, oldKey, config?.keyId)
        val stopped = settleHeldFailure(hold, directive, unansweredRequest(event, result))
        return when (directive) {
            LiveRebuildPolicy.Directive.LEGACY_TEARDOWN ->
                if (stopped != null) ApiResult.Error(stopped) else dialSingle(serverId, null, gen, prior, allowLiveRebuild = false)
            LiveRebuildPolicy.Directive.KEEP_OLD_SESSION ->
                ApiResult.Error(keptSessionCopy(event, config?.message, result as? ApiResult.Error))
            LiveRebuildPolicy.Directive.COMMIT_NEW -> {
                _connectedServer.value = config!!.serverNode?.name ?: "Unknown Server"
                _connectedServerId.value = serverId
                prefs.lastServerId = serverId
                ApiResult.Success(config)
            }
            LiveRebuildPolicy.Directive.FAILED_CLOSED -> ApiResult.Error(SessionCopy.switchFailedClosed(prefs.killSwitchEnabled))
            LiveRebuildPolicy.Directive.ABANDON -> ApiResult.Error(SUPERSEDED)
        }
    }

    private suspend fun liveRebuildMultiHop(
        entryNodeId: String,
        exitNodeId: String,
        gen: Long,
        prior: VpnState,
    ): ApiResult<MultiHopConnectResponse> {
        val oldKey = sessionKeyId
            ?: return dialMultiHop(entryNodeId, exitNodeId, null, gen, prior, allowLiveRebuild = false)
        val hold = holdFailuresForRebuild()
        val (result, event) = try {
            val (pqKey, pqRefusal) = rebuildPqKey()
            val result = if (pqRefusal != null || hold.cutShort) {
                null
            } else {
                rebuildRequest(hold) {
                    repository.connectMultiHop(
                        entryNodeId = entryNodeId,
                        exitNodeId = exitNodeId,
                        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                        stealthMode = false,
                        fallbackReason = null,
                        quantumProtection = prefs.quantumProtectionEnabled,
                        pqClientPublicKey = pqKey,
                        integrityToken = null,
                        dnsFiltering = shieldInEffect(),
                        rebuildOf = oldKey,
                    )
                }
            }
            val config = (result as? ApiResult.Success)?.data
            val mh = config?.multiHop
            result to when {
                superseded(gen) -> LiveRebuildPolicy.Event.SUPERSEDED
                pqRefusal != null -> pqRefusal
                hold.cutShort && config == null -> LiveRebuildPolicy.Event.REQUEST_UNANSWERED
                config == null -> (result as? ApiResult.Error)
                    ?.let { LiveRebuildPolicy.forRequestFailure(it.code, it.reason) }
                    ?: LiveRebuildPolicy.Event.REQUEST_FAILED
                !config.success -> LiveRebuildPolicy.forRefusal(config.rebuildRefused)
                !LiveRebuildPolicy.deferralHonoured(oldKey, config.deferredKeyId) ->
                    LiveRebuildPolicy.Event.DEFERRAL_NOT_HONOURED
                // The same rule as a fresh Multi-Hop dial: never ride a route the
                // server did not confirm.
                mh == null || mh.entryNode.id != entryNodeId || mh.exitNode.id != exitNodeId ->
                    LiveRebuildPolicy.Event.ROUTE_NOT_CONFIRMED
                config.privateKey == null || config.serverPublicKey == null || config.endpoint == null ||
                    config.assignedIp == null -> LiveRebuildPolicy.Event.FAILED_BEFORE_SWAP
                else -> {
                    hold.swapping = true
                    swapInService(config.toConnectResponse(), gen, oldKey)
                }
            }
        } catch (t: Throwable) {
            abandonRebuildHold(hold)
            throw t
        }
        releaseRebuildHold(hold)
        val config = (result as? ApiResult.Success)?.data
        val mh = config?.multiHop
        val directive = finishLiveRebuild(event, oldKey, config?.keyId)
        val stopped = settleHeldFailure(hold, directive, unansweredRequest(event, result))
        return when (directive) {
            LiveRebuildPolicy.Directive.LEGACY_TEARDOWN ->
                if (stopped != null) {
                    ApiResult.Error(stopped)
                } else {
                    dialMultiHop(entryNodeId, exitNodeId, null, gen, prior, allowLiveRebuild = false)
                }
            LiveRebuildPolicy.Directive.KEEP_OLD_SESSION ->
                ApiResult.Error(keptSessionCopy(event, config?.message, result as? ApiResult.Error))
            LiveRebuildPolicy.Directive.COMMIT_NEW -> {
                activeMultiHop = entryNodeId to exitNodeId
                _connectedServer.value = "${mh!!.entryNode.name} → ${mh.exitNode.name}"
                _connectedServerId.value = entryNodeId
                ApiResult.Success(config)
            }
            LiveRebuildPolicy.Directive.FAILED_CLOSED -> ApiResult.Error(SessionCopy.switchFailedClosed(prefs.killSwitchEnabled))
            LiveRebuildPolicy.Directive.ABANDON -> ApiResult.Error(SUPERSEDED)
        }
    }

    /**
     * Hand the new config to the RUNNING service (ACTION_LIVE_REBUILD) and
     * wait for what it did with it. The new key is the session's from here:
     * once the service publishes Connected, the heartbeat must name it — the
     * old one is only deferred, and its reply would be "evicted".
     */
    private suspend fun swapInService(config: ConnectResponse, gen: Long, oldKey: String): LiveRebuildPolicy.Event {
        val id = ++liveRebuildCounter
        val outcome = BirdoVpnService.expectLiveRebuild(id)
        BirdoVpnService.setRebuildConfig(config)
        sessionKeyId = config.keyId
        val intent = Intent(context, BirdoVpnService::class.java).apply {
            action = BirdoVpnService.ACTION_LIVE_REBUILD
            putExtra(BirdoVpnService.EXTRA_REBUILD_ID, id)
            putExtra(BirdoVpnService.EXTRA_KILL_SWITCH, prefs.killSwitchEnabled)
            // A live rebuild never asks for Stealth (its /connect sends
            // stealthMode = false; Stealth is not rebuilt in place).
            putExtra(BirdoVpnService.EXTRA_STEALTH_REQUESTED, false)
            putExtra(BirdoVpnService.EXTRA_SPLIT_TUNNEL_ENABLED, prefs.splitTunnelingEnabled)
            putExtra(BirdoVpnService.EXTRA_SPLIT_TUNNEL_APPS, prefs.splitTunnelApps.toTypedArray())
        }
        try {
            sendToService(intent)
        } catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "live_rebuild_dispatch_failed",
                "Dispatching LIVE_REBUILD threw — the live session is kept",
                e,
            )
            BirdoVpnService.forgetLiveRebuild(id)
            sessionKeyId = oldKey
            return LiveRebuildPolicy.Event.FAILED_BEFORE_SWAP
        }
        val event = withTimeoutOrNull(LIVE_REBUILD_TIMEOUT_MS) { outcome.await() }
        if (event == null) {
            // The service's own connect watchdog has failed it closed by now.
            BirdoVpnService.forgetLiveRebuild(id)
            return LiveRebuildPolicy.Event.FAILED_AFTER_SWAP
        }
        // The new peer carries traffic: a session built from the settings its
        // /connect read, whoever owns it now ([deferReapply]).
        if (event == LiveRebuildPolicy.Event.NEW_PEER_HANDSHAKED) sessionsBuilt++
        return if (superseded(gen) && event != LiveRebuildPolicy.Event.FAILED_AFTER_SWAP) {
            LiveRebuildPolicy.Event.SUPERSEDED
        } else {
            event
        }
    }

    /**
     * The one interpreter of [LiveRebuildPolicy]: release the keys the event
     * leaves unused, and put the old session back where it is kept.
     */
    private fun finishLiveRebuild(
        event: LiveRebuildPolicy.Event,
        oldKey: String,
        newKey: String?,
    ): LiveRebuildPolicy.Directive {
        val directive = LiveRebuildPolicy.directive(event)
        val release = LiveRebuildPolicy.release(event)
        // No identifiers in this line (node-agent privacy convention).
        android.util.Log.i("VpnManager", "Live rebuild: $event -> $directive")
        if (release.newKey && newKey != null && newKey != oldKey) {
            if (sessionKeyId == newKey) sessionKeyId = null
            scope.launch { repository.disconnectVpn(newKey) }
        }
        if (release.oldKey) scope.launch { repository.disconnectVpn(oldKey) }
        when (directive) {
            LiveRebuildPolicy.Directive.KEEP_OLD_SESSION -> {
                // The live session never stopped: it keeps its key, its intent
                // and its place in the supervisor (a later drop is a drop of an
                // ESTABLISHED session, healed under the full budget).
                sessionKeyId = oldKey
                repository.rememberKeyId(oldKey)
                session = session.connected()
                _switching.value = false
            }
            LiveRebuildPolicy.Directive.FAILED_CLOSED -> sessionKeyId = null
            // Today's path releases the session key in its teardown. That must
            // be the OLD key, the one still held back for us: swapInService
            // had already moved the session to the new one, which the line
            // above gave back, and the old one stayed out until the stale sweep.
            LiveRebuildPolicy.Directive.LEGACY_TEARDOWN -> sessionKeyId = oldKey
            LiveRebuildPolicy.Directive.COMMIT_NEW,
            LiveRebuildPolicy.Directive.ABANDON -> Unit
        }
        return directive
    }

    /**
     * What the user is told when the change did not happen but the session did
     * not move: a server switch, or a settings reapply ([reapplyInProgress]),
     * which is no switch (REVIEW-AND2-012).
     */
    private fun keptSessionCopy(
        event: LiveRebuildPolicy.Event,
        serverMessage: String?,
        apiError: ApiResult.Error?,
    ): String {
        val why = when (event) {
            LiveRebuildPolicy.Event.REFUSED -> serverMessage?.takeIf { it.isNotBlank() }
            LiveRebuildPolicy.Event.REQUEST_FAILED -> apiError?.let { apiErrorCopy(it.code, it.message) }
            else -> null
        }
        return SessionCopy.keptSession(why, settingsChange = reapplyInProgress)
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
     *
     * P6-CLI-A-03: this is NOT once per connect any more. The due check comes
     * first, before the nonce round trip, so a connect that is not due costs
     * neither a request to our backend nor — the point of the change — a request
     * to Google from the user's real IP at the exact moment they start a VPN
     * session. See [PlayIntegrityManager] for the window and what it costs.
     */
    private suspend fun requestAttestationToken(): String? {
        if (!BuildConfig.IS_PLAY_BUILD) return null
        if (!PlayIntegrityManager.isAttestationDue(context)) return null
        val nonce = repository.getAttestationNonce() ?: return null
        return PlayIntegrityManager.requestToken(context, nonce)
    }

    // ── Disconnects ───────────────────────────────────────────────────

    /**
     * Disconnect from VPN: the single path every surface uses — Home, the
     * tile, the widget and, since A1-009, the notification's Disconnect and
     * "Stop blocking" actions, which used to send ACTION_STOP straight to the
     * service and so skipped the peer release, the reconnect cancel and the
     * supersede of an in-flight dial.
     */
    suspend fun disconnect() = withContext(Dispatchers.Main.immediate) {
        // On Main, like every other writer of the supervisor's state: the
        // tile calls this from its own IO scope, and a Disconnect racing
        // onFailure there could leave a re-dial scheduled (REVIEW-AND-017).
        //
        // Win over any in-flight settings-reapply blip: a user Disconnect must
        // not be undone by the reconnect half of an apply-on-change rebuild.
        reapplyAbortGeneration++
        reapplyInProgress = false
        // …and over any dial in flight: it checks this before touching the tunnel.
        intentGeneration++
        session = ReconnectPolicy.Session.IDLE
        cancelRecovery()
        connectWatchdogJob?.cancel()
        _switching.value = false
        prefs.sessionShouldBeUp = false
        _stealthNotice.value = null
        // The not-armed warning describes the session the user just ended.
        // Cleared here, not only when the service's stop lands: Home must not
        // keep saying it over the user's own Disconnect.
        BirdoVpnService.clearKillSwitchNotArmed()
        tearDownTunnel(userInitiated = true)
    }

    /** Fire-and-forget [disconnect] for callers with no coroutine of their own. */
    fun requestDisconnect() {
        scope.launch { disconnect() }
    }

    /**
     * Sign-out and account switch: tear the tunnel down and AWAIT the
     * server-side slot release, bounded, so the caller can wipe credentials
     * straight after without the DELETE racing the token wipe (A1-045,
     * A2-005; iOS disconnectForSignOut). Local teardown happens first and
     * regardless.
     */
    suspend fun disconnectForSignOut() {
        withTimeoutOrNull(SIGN_OUT_RELEASE_TIMEOUT_MS) { disconnect() }
        _sessionExpired.value = false
    }

    /**
     * The account was deleted: the server has already removed the peer and
     * the tokens are gone, so tear down locally without the release call.
     */
    fun onAccountDeleted() {
        reapplyAbortGeneration++
        reapplyInProgress = false
        intentGeneration++
        session = ReconnectPolicy.Session.IDLE
        cancelRecovery()
        connectWatchdogJob?.cancel()
        _switching.value = false
        _sessionExpired.value = false
        prefs.sessionShouldBeUp = false
        sessionKeyId = null
        scope.launch { tearDownTunnel(userInitiated = true, releasePeer = false) }
    }

    /**
     * Tear down the active tunnel (stop the service, notify the backend).
     *
     * THIS PATH RELEASES THE KILL SWITCH: ACTION_STOP → stopTunnel() →
     * deactivateKillSwitch(). It is therefore only for a teardown the USER
     * asked for, or one where dropping the block is the point (a revoke, by
     * owner decision). A rebuild must use [switchTeardown] instead, which
     * holds the block across it.
     *
     * @param reason when non-null the service ends in an Error with this
     *   message and [reasonKind] (and posts it as an alert) instead of
     *   Disconnected, so the explanation survives the teardown.
     */
    private suspend fun tearDownTunnel(
        userInitiated: Boolean,
        reason: String? = null,
        reasonKind: FailureKind = FailureKind.TRANSIENT,
        releasePeer: Boolean = true,
    ) {
        _state.value = VpnState.Disconnecting
        transitionStartTime = System.currentTimeMillis()
        activeMultiHop = null
        _connectedServerId.value = null

        val intent = Intent(context, BirdoVpnService::class.java).apply {
            action = BirdoVpnService.ACTION_STOP
            putExtra(BirdoVpnService.EXTRA_USER_INITIATED, userInitiated)
            if (reason != null) {
                putExtra(BirdoVpnService.EXTRA_STOP_REASON, reason)
                putExtra(BirdoVpnService.EXTRA_STOP_KIND, reasonKind.name)
            }
        }
        // Guarded so a stop signal during a rapid switch can never crash the app.
        try {
            sendToService(intent)
        } catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "service_stop_dispatch_failed",
                "Dispatching STOP threw — the tunnel may still be up",
                e,
            )
        }

        // Notify backend (best effort), naming THIS session's key.
        val key = sessionKeyId
        sessionKeyId = null
        if (releasePeer) repository.disconnectVpn(key)

        // Don't set Disconnected here — the service sets it after the tunnel is
        // actually stopped, and the state collector picks it up.
    }

    /**
     * Fail-closed teardown for a server switch or a re-dial. Sends
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
            sendToService(intent)
        } catch (e: Exception) {
            // The fail-closed teardown: if this does not dispatch, the block
            // is not held across the rebuild window it exists for.
            FaultReporter.report(
                FaultReporter.PATH_KILL_SWITCH,
                "switch_teardown_dispatch_failed",
                "Dispatching SWITCH_TEARDOWN threw — the block may not be held across the rebuild",
                e,
            )
        }

        // Unregister the old peer server-side (best effort) before the new /connect
        // registers a fresh keypair. The service keeps the kill switch up meanwhile.
        val key = sessionKeyId
        sessionKeyId = null
        repository.disconnectVpn(key)
    }

    /**
     * Release the kill-switch block but leave the session's Error on screen
     * and the service in the foreground: the supervisor has given up, or a
     * user dial that never connected created a block it must not keep.
     */
    private fun releaseBlock() {
        val intent = Intent(context, BirdoVpnService::class.java).apply {
            action = BirdoVpnService.ACTION_RELEASE_BLOCK
        }
        try {
            sendToService(intent)
        } catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_KILL_SWITCH,
                "release_block_dispatch_failed",
                "Dispatching RELEASE_BLOCK threw — the device stays blocked after the supervisor gave up",
                e,
            )
        }
    }

    /** Best-effort release of one specific peer, off the caller's path. */
    private fun releasePeer(keyId: String?) {
        if (keyId == null) return
        if (sessionKeyId == keyId) sessionKeyId = null
        scope.launch { repository.disconnectVpn(keyId) }
    }

    // ── The supervisor ────────────────────────────────────────────────

    private fun onStateChanged(vpnState: VpnState) {
        BirdoVpnService.requestTileRefresh()
        if (vpnState !is VpnState.Connected) _quotaGrace.value = null
        when (vpnState) {
            is VpnState.Error -> {
                stopHeartbeat()
                if (vpnState === verdictError) return
                val hold = rebuildHold
                if (hold != null) {
                    // The rebuild that owns the hold decides (settleHeldFailure).
                    hold.held = vpnState
                    // Before the swap is sent, its /connect rides the tunnel
                    // that just died: stop waiting for it (rebuildRequest).
                    if (!hold.swapping) {
                        hold.cutShort = true
                        hold.request?.cancel()
                    }
                    return
                }
                connectWatchdogJob?.cancel()
                // The service's dead-tunnel check (or a dead Xray) ended a
                // session that was up: its key goes to the first re-dial's
                // probe. A drop the heartbeat already explained cleared the
                // key before publishing, so it is never asked about twice.
                if (vpnState.kind == FailureKind.DIED_AFTER_HANDSHAKE && session.established) {
                    deadSessionKey = sessionKeyId
                }
                onFailure(vpnState.kind)
            }
            is VpnState.Connected -> {
                session = session.connected()
                avoidServerId = null
                cancelRecovery()
                connectWatchdogJob?.cancel()
                _switching.value = false
                startHeartbeat()
            }
            is VpnState.Disconnected -> stopHeartbeat()
            else -> Unit
        }
    }

    /** The supervisor's verdict on a failure of [failedKind] (the Error that is, or would be, the state). */
    private fun onFailure(failedKind: FailureKind) {
        // After a heartbeat 401 nothing can re-dial: every attempt would need
        // credentials we no longer hold, and a retry loop would bury the one
        // message the user needs under generic connect failures.
        val expired = _sessionExpired.value && !failedKind.terminal
        val kind = if (expired) FailureKind.SIGN_IN_REQUIRED else failedKind
        // Monotonic time that includes deep sleep: a wall-clock change must
        // not split or merge a failure streak.
        val outcome = ReconnectPolicy.onFailure(session, kind, online, elapsedRealtime(), jitter())
        session = outcome.session
        // A server that said when to come back is not asked sooner.
        val serverWait = retryAfterHintMs
        retryAfterHintMs = 0L
        when (val decision = outcome.decision) {
            is ReconnectPolicy.Decision.Retry -> scheduleReconnect(decision.attempt, maxOf(decision.delayMs, serverWait))
            ReconnectPolicy.Decision.WaitForNetwork -> waitForNetwork(session.failures + 1)
            is ReconnectPolicy.Decision.GiveUp -> {
                giveUp(decision, kind)
                // The dropped tunnel said "Reconnecting…"; say why nothing will.
                if (expired) publishError(SessionCopy.SESSION_EXPIRED, FailureKind.SIGN_IN_REQUIRED, verdict = true)
            }
        }
    }

    private fun giveUp(decision: ReconnectPolicy.Decision.GiveUp, kind: FailureKind) {
        reconnectJob?.cancel()
        _switching.value = false
        // The kill switch is for unexpected drops of an ESTABLISHED session,
        // not for a connect that never worked (A1-003). When a user dial from
        // an unprotected state is what put the block up — the service arms it
        // on every setup failure, the stealth fallback forces it — the dial
        // takes it down again and leaves its error on screen. A switch away
        // from a protected session, a session that was up, and anything the
        // system started keep it.
        val attemptOwnsBlock = session.owner == ReconnectPolicy.Owner.USER && !session.established &&
            !protectedAtDialStart && isKillSwitchActive
        when (decision.reason) {
            // A Disconnect or a revoke teardown already owns the outcome.
            ReconnectPolicy.GiveUpReason.NOT_WANTED -> Unit

            // A user dial that never connected.
            ReconnectPolicy.GiveUpReason.NEVER_CONNECTED -> {
                prefs.sessionShouldBeUp = false
                // Nothing is rebuilt from this route now (REVIEW-AND-019); the
                // prefs keep the user's pair.
                activeMultiHop = null
                releasePeer(sessionKeyId)
                if (attemptOwnsBlock) releaseBlock()
            }

            // Asking again gets the same answer. Stop, keep the block if the
            // kill switch or Android's lockdown holds one for a session that
            // was up or that the system started (never fail open silently: the
            // "Action needed" alert says why), and release the peer a local
            // refusal minted. A SIGN_IN_REQUIRED session keeps its intent, so
            // signing in again resumes it (see onSignedIn).
            ReconnectPolicy.GiveUpReason.TERMINAL -> {
                if (kind != FailureKind.SIGN_IN_REQUIRED) {
                    prefs.sessionShouldBeUp = false
                    activeMultiHop = null
                }
                if (kind != FailureKind.REVOKED && kind != FailureKind.SIGN_IN_REQUIRED) {
                    releasePeer(sessionKeyId)
                }
                // The Free allowance's end is the server ending the session on
                // purpose, like a revoke: the owner rule releases the block
                // (FailureKind.QUOTA_EXCEEDED), here as on the heartbeat path.
                if (attemptOwnsBlock || kind == FailureKind.QUOTA_EXCEEDED) releaseBlock()
            }

            // The budget is spent. Say so in the iOS words, release the
            // app's own block (parity with iOS and Windows: a device must not
            // sit silently blocked behind a server that is not coming back),
            // and try once more after the cooldown.
            ReconnectPolicy.GiveUpReason.BUDGET_EXHAUSTED -> {
                releasePeer(sessionKeyId)
                releaseBlock()
                publishError(
                    SessionCopy.giveUp(kind, decision.attempts, BirdoVpnService.lockdownActive),
                    kind,
                    verdict = true,
                )
                scheduleCooldown()
            }
        }
    }

    private fun scheduleReconnect(attempt: Int, delayMs: Long) {
        reconnectJob?.cancel()
        // Reconnecting is published for the whole backoff, so Home, the tile
        // and the notification say what is happening instead of "Not
        // connected" with a tappable Connect (A1-008).
        _state.value = VpnState.Reconnecting(attempt)
        transitionStartTime = System.currentTimeMillis()
        val gen = intentGeneration
        reconnectJob = scope.launch {
            delay(delayMs)
            if (superseded(gen) || !session.wantUp) return@launch
            runDial { redial(); ApiResult.Success(Unit) }
        }
    }

    private fun scheduleCooldown() {
        cooldownJob?.cancel()
        val gen = intentGeneration
        cooldownJob = scope.launch {
            delay(ReconnectPolicy.TRIP_COOLDOWN_MS)
            if (superseded(gen) || !session.wantUp) return@launch
            session = ReconnectPolicy.afterCooldown(session)
            if (online) {
                runDial { redial(); ApiResult.Success(Unit) }
            } else {
                waitForNetwork(1)
            }
        }
    }

    /**
     * Hold the session in "Reconnecting… / Waiting for a network connection…"
     * without spending budget; [onNetworkAvailable] re-dials on the edge.
     */
    private fun waitForNetwork(attempt: Int) {
        reconnectJob?.cancel()
        _state.value = VpnState.Reconnecting(attempt, waitingForNetwork = true, captivePortal = captivePortal)
        transitionStartTime = System.currentTimeMillis()
    }

    private fun onNetworkAvailable() {
        val s = _state.value
        if (s is VpnState.Reconnecting && session.mayAutoRetry && dialJob?.isActive != true) {
            scheduleReconnect(s.attempt, delayMs = 0L)
        }
        heartbeatNow()
    }

    /**
     * The device went offline (or behind a captive portal) while a re-dial
     * was scheduled: stop counting attempts against a network that is not
     * there and wait for it instead. Live on the emulator (2026-09-30) the
     * attempt counter kept rising through airplane mode because the offline
     * edge never arrived; with NOT_VPN tracking it does, and this is what it
     * does. A dial already in flight finishes on its own and lands in
     * WaitForNetwork through [onFailure].
     */
    private fun onNetworkUnavailable() {
        val s = _state.value
        if (s is VpnState.Reconnecting && session.mayAutoRetry && dialJob?.isActive != true) {
            waitForNetwork(s.attempt)
        }
    }

    /** Cancel any pending re-dial and the post-give-up cooldown. */
    private fun cancelRecovery() {
        reconnectJob?.cancel()
        reconnectJob = null
        cooldownJob?.cancel()
        cooldownJob = null
    }

    // ── Session expiry (A1-035, A2-004) ───────────────────────────────

    /**
     * The account session is dead (a heartbeat 401 after the refresh was
     * rejected, or the app's own profile check). The working tunnel is NOT
     * torn down — iOS does not either, and the peer lives until the backend
     * reaps it. Recovery stops: the next drop ends in SIGN_IN_REQUIRED, which
     * keeps any block and raises the "Action needed" alert.
     */
    fun onSessionExpired() {
        if (_sessionExpired.value) return
        _sessionExpired.value = true
        stopHeartbeat()
        val s = _state.value
        if (s is VpnState.Reconnecting || (s is VpnState.Error && !s.kind.terminal)) {
            cancelRecovery()
            publishError(SessionCopy.SESSION_EXPIRED, FailureKind.SIGN_IN_REQUIRED)
        }
    }

    /**
     * The user signed in again. Resume: a live tunnel gets its heartbeat back
     * (the key id is still in memory); a session that stopped for want of
     * credentials re-dials, behind any block it is still holding.
     */
    fun onSignedIn() {
        val s = _state.value
        val stoppedForSignIn = s is VpnState.Error && s.kind == FailureKind.SIGN_IN_REQUIRED
        if (!_sessionExpired.value && !stoppedForSignIn) return
        _sessionExpired.value = false
        when {
            // Keep the last good beat's time: heartbeats stopped at the 401, so
            // the backend may have reaped the peer while the user was signed
            // out, and the first beat must be judged against that gap. Starting
            // the clock afresh made the inevitable "not found" read as a
            // revoke, which released the block (REVIEW-AND-002).
            s is VpnState.Connected -> startHeartbeat(resetLastOk = false)
            stoppedForSignIn && (prefs.sessionShouldBeUp || isKillSwitchActive) ->
                scope.launch { runDial { preferredDial(DialOrigin.HEADLESS, multiHopEntitled = null) } }
        }
    }

    /**
     * FIX-2-12: Core state transition logic with guards.
     * Shared by every path that applies a service state, so they all follow
     * the same rules.
     *
     * Guards against race conditions:
     * - When Connecting, don't let stale Disconnected from the service reset us
     * - When Disconnecting, don't let stale Connected from the service reset us
     * Guards expire after 15s to prevent getting stuck (e.g. tunnel fails → kill
     * switch → Disconnected, which the guard would otherwise block forever).
     * Error states from the service always propagate immediately. A stuck
     * Connecting is resolved by the connect watchdog TIMER, not here: this
     * function only runs when the service emits.
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
                (localState.isConnectingPhase || localState is VpnState.Reconnecting) && serviceState is VpnState.Disconnected -> return
                // We set Disconnecting but service hasn't stopped yet (still Connected/Connecting)
                localState is VpnState.Disconnecting &&
                    (serviceState is VpnState.Connected || serviceState.isConnectingPhase) -> return
            }
        }
        // A backoff or a wait for the network outlives any guard window, and
        // the service has nothing new to say in the meantime; its stale
        // Disconnected must not erase "Reconnecting…".
        if (localState is VpnState.Reconnecting && serviceState is VpnState.Disconnected) return
        // A block armed under a dial that is still in its API phase — a
        // system start arms it before the headless connect reaches the API —
        // is expected; the dial's own establish() will supersede it.
        if (localState.isConnectingPhase && serviceState is VpnState.KillSwitchActive &&
            dialJob?.isActive == true
        ) {
            return
        }
        // Nor may the block's KillSwitchActive overwrite the session's own
        // verdict. The block is armed on the service's executor and its state
        // arrives a hop later, so it used to replace an Error the system start
        // had just published ("sign in", "set up") or a Reconnecting that was
        // waiting for the network — and then nothing ever re-dialled: sign-in
        // looked for an Error, the online edge for a Reconnecting, and both
        // were gone (REVIEW-AND-001). The block itself is still on every
        // surface through killSwitchActiveFlow.
        if ((localState is VpnState.Error || localState is VpnState.Reconnecting) &&
            serviceState is VpnState.KillSwitchActive
        ) {
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
            // Carries the kill-switch preference: a push that does not
            // dispatch leaves the service on the previous setting.
            FaultReporter.report(
                FaultReporter.PATH_KILL_SWITCH,
                "settings_push_dispatch_failed",
                "startService(UPDATE_SETTINGS) threw — the running service keeps its previous kill-switch setting",
                e,
            )
        }
    }

    /**
     * Request an apply-on-change rebuild of the live tunnel. Debounced
     * ([SETTINGS_REAPPLY_DEBOUNCE_MS]) so a burst of edits produces ONE
     * reconnect; a no-op unless currently Connected (a connect that begins
     * later reads the latest preferences anyway). One that lands while a dial
     * is in flight waits for that dial ([deferReapply]).
     */
    fun requestSettingsReapply() {
        reapplyRequests.tryEmit(Unit)
    }

    /** The reapply waiting for the dial in flight ([deferReapply]); at most one. */
    private var deferredReapply: Job? = null

    /**
     * A dial owns the session: a user's switch, a re-dial, a fallback, or a
     * live rebuild inside one ([rebuildHold]). A switch riding the live tunnel
     * still shows Connected while its /connect is out.
     */
    private fun dialInFlight(): Boolean = dialJob?.isActive == true || rebuildHold != null

    /**
     * Re-queue a reapply that found a dial in flight, instead of rebuilding
     * beside it. REAPPLY does not bump [intentGeneration], so a reapply during
     * a user's live-rebuild switch started a SECOND rebuild under the switch's
     * own generation: neither superseded the other, the newer one took the
     * [RebuildHold] from the first, and both rode the same old key with
     * `rebuild: true` — of the OLD server, since the switch had not committed
     * (review of #463). Bumping the generation instead would have abandoned the
     * user's switch. Once the dial is over (and its service setup, which a
     * fresh dial leaves in Connecting), the request goes back through the
     * debounce and applies to whatever session the dial left; a session that
     * ended makes it the usual no-op.
     *
     * It is dropped only when a newer user or system intent superseded it
     * AND a session was built since (review of #472): that session's dial
     * read the current settings, so applying them again would rebuild one
     * that already has them. A newer intent that built nothing does not drop
     * it: a switch the server refused keeps the OLD session
     * (LiveRebuildPolicy.Directive.KEEP_OLD_SESSION), which still lacks the
     * change, and Quantum protection shown ON over a session without it is
     * the lie this re-queue exists to prevent. A Disconnect builds nothing
     * either, and the re-queued request is then the usual no-op.
     *
     * Every request refreshes what it is measured against, so a change that
     * lands while one already waits is not dropped by a session built before
     * it.
     */
    private fun deferReapply() {
        deferredReapplyGen = intentGeneration
        deferredReapplyBuilt = sessionsBuilt
        if (deferredReapply?.isActive == true) return
        deferredReapply = scope.launch {
            while (true) {
                val dial = dialJob
                when {
                    dial?.isActive == true -> dial.join()
                    rebuildHold != null -> delay(150)
                    else -> break
                }
            }
            if (deferredReapplyRedundant()) return@launch
            waitUntil(CONNECT_SERVICE_TIMEOUT_MS) { !_state.value.isConnectingPhase }
            if (deferredReapplyRedundant()) return@launch
            requestSettingsReapply()
        }
    }

    /**
     * Sessions built from the settings as they were when their dial ran: a
     * START handed to the service, or a live rebuild's new peer carrying
     * traffic. Main-confined, like [session].
     */
    private var sessionsBuilt = 0L

    /** [intentGeneration] and [sessionsBuilt] at the latest [deferReapply]. */
    private var deferredReapplyGen = 0L
    private var deferredReapplyBuilt = 0L

    private fun deferredReapplyRedundant(): Boolean =
        superseded(deferredReapplyGen) && sessionsBuilt != deferredReapplyBuilt

    /** True while [reapplySettingsNow] owns the tunnel; makes the dial's
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
     * Two guarantees enforced explicitly (not inferred from the dial's return,
     * which signals Success as soon as the /connect API replies — BEFORE the
     * tunnel actually establishes):
     *  - A user Disconnect that lands anytime during the rebuild wins: we tear
     *    the resurrected tunnel back down.
     *  - A fail-open user is NEVER left behind the forced block: if the rebuild
     *    doesn't reach Connected, we release the block (their choice for drops).
     */
    private suspend fun reapplySettingsNow() {
        if (dialInFlight()) return deferReapply()
        if (_state.value !is VpnState.Connected) return
        val startGen = reapplyAbortGeneration
        reapplyInProgress = true
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Applying settings — reconnecting…", Toast.LENGTH_SHORT).show()
        }
        try {
            val mh = activeMultiHop
            val serverId = prefs.lastServerId
            if (mh == null && serverId == null) return
            runDial {
                val prior = _state.value
                val gen = beginDial(DialOrigin.REAPPLY, prior)
                // In place when it can be (A1-034); otherwise the dial's teardown
                // runs switchTeardown(forceBlock = reapplyInProgress).
                if (mh != null) {
                    dialMultiHop(mh.first, mh.second, null, gen, prior, allowLiveRebuild = true)
                } else {
                    dialSingle(serverId!!, null, gen, prior)
                }
            }.also { result ->
                if (result is ApiResult.Error && _state.value is VpnState.Connected && result.message != SUPERSEDED) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }

            // The dial has returned, but Success only means the API replied —
            // the service is still establishing. Wait for the real outcome so
            // both guarantees below act on the TRUE end state.
            waitUntil(CONNECT_SERVICE_TIMEOUT_MS) {
                _state.value is VpnState.Connected ||
                    _state.value is VpnState.Error ||
                    _state.value is VpnState.Reconnecting ||
                    _state.value is VpnState.Disconnected ||
                    reapplyAbortGeneration != startGen
            }

            // A user Disconnect landed during the rebuild → honour it: undo the
            // reconnect the API may have already kicked off.
            if (reapplyAbortGeneration != startGen) return abortReapply()

            // Fail-open user must not be left blocked: if the rebuild didn't come
            // up, release the forced block (clean disconnect). Fail-closed users
            // stay blocked and let the supervisor retry.
            if (_state.value !is VpnState.Connected && !prefs.killSwitchEnabled) {
                cancelRecovery()
                // The session ended here, and the app says so: nothing may
                // restore it later (an app update did, REVIEW-AND-018).
                endIntent()
                tearDownTunnel(userInitiated = false)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Couldn't apply settings — disconnected.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The rebuild THREW (e.g. a rapid-restart FGS exception). Surface an
            // Error so a fail-closed session is recovered by the supervisor
            // (block held); the finally then releases the forced block for a
            // fail-open user so an exception can never leave them stuck behind
            // a total block.
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "settings_reapply_threw",
                "Settings reapply rebuild threw",
                e,
            )
            if (_state.value.isConnectingPhase) {
                publishError(SessionCopy.SETTINGS_NOT_APPLIED, FailureKind.TRANSIENT)
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
                    cancelRecovery()
                    endIntent()
                    tearDownTunnel(userInitiated = false)
                }
            }
        }
    }

    /** The session is over: nobody wants it back, not the supervisor and not a later system start. */
    private fun endIntent() {
        session = ReconnectPolicy.Session.IDLE
        prefs.sessionShouldBeUp = false
    }

    /** Tear the tunnel down to honour a user Disconnect that raced the blip. */
    private suspend fun abortReapply() {
        cancelRecovery()
        tearDownTunnel(userInitiated = true)
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
     * Start the periodic heartbeat to the backend while connected
     * (POST /vpn/heartbeat/{keyId}, every [HEARTBEAT_INTERVAL_MS] ±10 %). It
     * is what refreshes the peer's `lastSeen`, so it is what keeps the backend
     * from reaping a live session.
     *
     * The FIRST beat goes out as soon as the tunnel is up (WEB-HB): the
     * backend's stranded-peer purge drops a key that never checked in, and
     * since D-6 that beat is also the first data through the tunnel, which
     * the dead-tunnel check (TunnelMonitor) watches for an answer.
     *
     * (A ~45-minute key rotation used to tick here behind
     * `repository.keyRotationSupported`, which was false: the backend has no
     * rotate endpoint. It never ran and is gone, A2-036.)
     */
    private fun startHeartbeat(resetLastOk: Boolean = true) {
        heartbeatJob?.cancel()
        if (resetLastOk) lastHeartbeatOkAt = elapsedRealtime()
        heartbeatJob = scope.launch(ioDispatcher) {
            while (isActive) {
                if (!beat()) break
                delay(ReconnectPolicy.jittered(HEARTBEAT_INTERVAL_MS, jitter()))
            }
        }
    }

    /**
     * An immediate beat, for screen-on, unlock and network changes (A1-017).
     * Dropped when a beat went out in the last [HEARTBEAT_NUDGE_MIN_GAP_MS].
     */
    fun heartbeatNow() {
        if (_state.value !is VpnState.Connected || heartbeatJob?.isActive != true) return
        if (elapsedRealtime() - lastBeatAt < HEARTBEAT_NUDGE_MIN_GAP_MS) return
        scope.launch(ioDispatcher) { beat() }
    }

    /** One heartbeat round trip. Returns false when the loop must stop. */
    private suspend fun beat(): Boolean = heartbeatMutex.withLock {
        if (_state.value !is VpnState.Connected) return@withLock false
        val key = sessionKeyId
        val now = elapsedRealtime()
        lastBeatAt = now
        val sinceLastOk = now - lastHeartbeatOkAt
        val result = repository.sendHeartbeat(key)
        // Act only on a reply for the session it was sent for (WEB-HB): a
        // switch or a Disconnect that landed while this beat was in flight
        // owns the session now, and a reply about the key it just released
        // ("evicted" for a rebuild's old key, "revoked" after a DELETE) must
        // not tear the new one down or supersede the user's dial
        // (REVIEW-AND-013).
        if (key != sessionKeyId || _state.value !is VpnState.Connected) return@withLock false
        when (result) {
            is ApiResult.Success -> {
                val resp = result.data
                val verdict = HeartbeatPolicy.verdict(resp.valid, resp.reason, sinceLastOk, resp.quotaExceeded)
                when (verdict) {
                    HeartbeatPolicy.Verdict.ALIVE -> {
                        if (resp.valid) lastHeartbeatOkAt = elapsedRealtime()
                        _quotaGrace.value = QuotaPolicy.grace(
                            valid = resp.valid,
                            quotaExceeded = resp.quotaExceeded,
                            secondsRemaining = resp.quotaGraceSecondsRemaining,
                            endsAtIso = resp.quotaGraceEndsAt,
                            nowEpochMs = System.currentTimeMillis(),
                        )
                        if (!resp.serverOnline) {
                            android.util.Log.w("VpnManager", "Heartbeat: server going offline")
                        }
                        true
                    }
                    else -> {
                        withContext(Dispatchers.Main) { onHeartbeatVerdict(verdict) }
                        false
                    }
                }
            }
            is ApiResult.Error -> {
                android.util.Log.w("VpnManager", "Heartbeat failed: ${result.message}")
                when (result.code) {
                    // withAutoRefresh already tried the refresh and the server
                    // rejected it. Transient failures (5xx, timeouts, a lost
                    // signal) never reach here as 401, so they keep looping.
                    401 -> {
                        withContext(Dispatchers.Main) { onSessionExpired() }
                        false
                    }
                    // A Connected session with no key id to beat for is an
                    // invariant failure (the A1-005 race, or a regression of
                    // it): the backend will reap the peer, silently, within 5
                    // minutes. Say so, and rebuild now instead.
                    BirdoRepository.CODE_NO_ACTIVE_KEY -> {
                        FaultReporter.report(
                            FaultReporter.PATH_CONNECT,
                            "heartbeat_no_key_id",
                            "Connected with no WireGuard key id to heartbeat for — rebuilding before the peer is reaped",
                        )
                        withContext(Dispatchers.Main) {
                            onHeartbeatVerdict(HeartbeatPolicy.Verdict.REAPED)
                        }
                        false
                    }
                    else -> true
                }
            }
        }
    }

    /**
     * The heartbeat says the key is no longer this device's live session
     * ([HeartbeatPolicy] decides which case it is):
     *
     *  - REAPED: the server dropped an idle peer; nobody ended anything. One
     *    quiet rebuild behind the block ([FailureKind.REAPED], budget 1 per
     *    10 min). Phase A inferred this from the gap since the last good beat;
     *    birdo-web now says it (`reason: "reaped"`), and the inference stays
     *    for an older backend.
     *  - REVOKED: ended on purpose (a Disconnect from another device, a
     *    sign-out everywhere, a plan change). Owner decision 2026-09-30, as on
     *    iOS: tear down, RELEASE the kill-switch block, say so, no re-dial.
     *  - EVICTED: another device of this account took the slot. As REVOKED,
     *    with the sentence that says so — and never re-dialled, or the two
     *    devices would evict each other in turn (A1-004's ping-pong).
     *  - SERVER_GONE: the node was drained or destroyed. Re-dial a DIFFERENT
     *    server, fail-closed meanwhile; a Multi-Hop route cannot be re-chosen
     *    for the user, so it stops and says which choice to make.
     */
    private suspend fun onHeartbeatVerdict(verdict: HeartbeatPolicy.Verdict) {
        when (verdict) {
            HeartbeatPolicy.Verdict.ALIVE -> Unit
            HeartbeatPolicy.Verdict.REAPED -> {
                android.util.Log.w("VpnManager", "Heartbeat: peer reaped — rebuilding behind the block")
                sessionDeadTeardown(SessionCopy.REAPED, FailureKind.REAPED)
            }
            HeartbeatPolicy.Verdict.REVOKED -> {
                android.util.Log.w("VpnManager", "Heartbeat: connection revoked by the server")
                // The canonical sentence, never the server's message: a
                // revoke is often INFERRED from today's "Connection not
                // found" reply (HeartbeatPolicy), which would misname it.
                endSessionForServer(SessionCopy.REVOKED, FailureKind.REVOKED)
            }
            HeartbeatPolicy.Verdict.EVICTED -> {
                android.util.Log.w("VpnManager", "Heartbeat: another device took this session's slot")
                endSessionForServer(SessionCopy.EVICTED, FailureKind.EVICTED)
            }
            // The Free allowance is used and the grace window is over: the
            // peer is gone. A plan decision, ended like a revoke (block
            // released, intent forgotten, never re-dialled).
            HeartbeatPolicy.Verdict.QUOTA_EXCEEDED -> {
                android.util.Log.w("VpnManager", "Heartbeat: free data allowance used — session ended by the server")
                endSessionForServer(context.getString(R.string.session_quota_exceeded), FailureKind.QUOTA_EXCEEDED)
            }
            HeartbeatPolicy.Verdict.SERVER_GONE -> if (activeMultiHop != null) {
                sessionDeadTeardown(SessionCopy.MULTI_HOP_ROUTE_OFFLINE, FailureKind.REFUSED)
            } else {
                android.util.Log.w("VpnManager", "Heartbeat: this server went offline — moving to another")
                avoidServerId = prefs.lastServerId
                sessionDeadTeardown(SessionCopy.SERVER_OFFLINE, FailureKind.DIED_AFTER_HANDSHAKE)
            }
        }
    }

    /**
     * REVIEW-AND2-001: ONE heartbeat for the key of a session the dead-tunnel
     * check declared dead, around the tunnel
     * ([app.birdo.vpn.data.network.AroundTunnel]: a protect()ed
     * socket on the physical network, resolved over DoH), bounded by
     * [DEAD_SESSION_PROBE_TIMEOUT_MS].
     *
     * Off-Connected, ApiRoutePolicy would choose the bypass client anyway; the
     * tag makes it this call's own property, because through the dead peer it
     * can never be answered. It discloses nothing new: the re-dial it precedes
     * goes around the tunnel from the same address.
     *
     * @return [HeartbeatPolicy.forDeadTunnel]'s verdict.
     */
    private suspend fun probeDeadSession(key: String): HeartbeatPolicy.Verdict {
        val sinceLastOk = elapsedRealtime() - lastHeartbeatOkAt
        val result = withTimeoutOrNull(DEAD_SESSION_PROBE_TIMEOUT_MS) {
            repository.sendHeartbeat(key, aroundTunnel = true)
        }
        val reply = (result as? ApiResult.Success)?.data
        val verdict = HeartbeatPolicy.forDeadTunnel(reply, sinceLastOk)
        // No identifiers in this line (node-agent privacy convention).
        android.util.Log.i("VpnManager", "Dead-tunnel probe: ${if (reply == null) "no answer" else reply.reason} -> $verdict")
        return verdict
    }

    /**
     * The server ended this session on purpose: release the block, show
     * [message], forget the intent, and never re-dial on our own.
     */
    private suspend fun endSessionForServer(message: String, kind: FailureKind) {
        _quotaGrace.value = null
        intentGeneration++
        session = ReconnectPolicy.Session.IDLE
        cancelRecovery()
        prefs.sessionShouldBeUp = false
        // The peer is already gone server-side; nothing to release.
        sessionKeyId = null
        withContext(NonCancellable) {
            tearDownTunnel(
                userInitiated = false,
                reason = message,
                reasonKind = kind,
                releasePeer = false,
            )
        }
    }

    /**
     * Fail-closed recovery for a peer the server reaped: block first (when the
     * kill switch is on), then publish the Error the supervisor re-dials on.
     *
     * WHY NOT [disconnect]: that path sends ACTION_STOP, and the service's
     * stopTunnel() deactivates the kill switch — releasing everything queued
     * behind the dead tunnel onto the physical interface in cleartext, at the
     * one moment we already know the tunnel is dead. The block goes up FIRST
     * and wg-go comes down second, the ordering contract [switchTeardown]
     * relies on; the re-dial then runs entirely behind it.
     *
     * A user who turned the kill switch OFF chose fail-open: no block, just
     * the Error, and the re-dial's own teardown replaces the dead tunnel.
     */
    private suspend fun sessionDeadTeardown(reason: String, kind: FailureKind) {
        // Win over any in-flight settings-reapply blip, exactly as [disconnect].
        reapplyAbortGeneration++
        reapplyInProgress = false
        cancelRecovery()

        // NonCancellable for everything below, and it is not optional here:
        // any state this publishes that the supervisor reacts to makes it call
        // stopHeartbeat(), which cancels heartbeatJob — the very coroutine this
        // function may run in. Bounded: the 5s block confirmation plus one
        // release round trip, with the block armed throughout.
        withContext(NonCancellable) {
            if (!prefs.killSwitchEnabled) {
                publishError(reason, kind)
                return@withContext
            }

            _state.value = VpnState.Disconnecting
            transitionStartTime = System.currentTimeMillis()

            val intent = Intent(context, BirdoVpnService::class.java).apply {
                action = BirdoVpnService.ACTION_KILL_SWITCH_BLOCK
            }
            // Everything that decides protection happens before the first
            // suspension point below. Guarded like every other service start
            // in this file.
            try {
                sendToService(intent)
            } catch (e: Exception) {
                // If this throws the kill switch is never armed AND the
                // service is never reached, so none of its own kill-switch
                // reports can fire. The outermost failure of the kill-switch
                // path is reported here or nowhere.
                FaultReporter.report(
                    FaultReporter.PATH_KILL_SWITCH,
                    "kill_switch_block_dispatch_failed",
                    "Dispatching KILL_SWITCH_BLOCK threw — the block was never requested, traffic is NOT protected",
                    e,
                )
            }

            // Unregister the dead peer so a re-dial mints a clean one. Best
            // effort; ordered after the block intent, never before it.
            val key = sessionKeyId
            sessionKeyId = null
            repository.disconnectVpn(key)

            // Publish only AFTER the service confirms the block is up, so its
            // own KillSwitchActive emission cannot land on top and leave a
            // fully blocked device with nothing on screen saying why. This
            // Error is also what the supervisor re-dials on, one more reason it
            // must not be published before the block is up.
            val blocked = waitUntil(5000) { isKillSwitchActive }
            // false means establish() refused (in practice: VPN consent
            // revoked) — and activateKillSwitch has ALREADY torn the data
            // plane down by then, so traffic really IS in the clear. Say so out
            // loud: silent failure of a security control is worse than a loud
            // one. Never render reassurance we have not confirmed.
            if (!blocked) {
                FaultReporter.report(
                    FaultReporter.PATH_KILL_SWITCH,
                    "kill_switch_block_unconfirmed",
                    "Kill switch block was not confirmed within 5s of dispatch — traffic is NOT protected",
                )
            }
            publishError(
                if (blocked) reason else "$reason — kill switch could NOT be armed, traffic is NOT protected",
                kind,
            )
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
