package app.birdo.vpn.ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.data.model.AppUpdateInfo
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.perf.GlobePerf
import app.birdo.vpn.perf.GlobePerfControls
import app.birdo.vpn.perf.GlobePerfOverlay
import app.birdo.vpn.service.VpnState
import app.birdo.vpn.ui.TestTags
import app.birdo.vpn.ui.components.*
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.ui.viewmodel.MultiHopSelection
import app.birdo.vpn.ui.viewmodel.TrafficStats
import app.birdo.vpn.ui.viewmodel.VpnUiState
import app.birdo.vpn.utils.FormatUtils
import app.birdo.vpn.utils.countryCodeToFlag

/**
 * Home / Connect tab — redesigned hero experience with a brand-gradient
 * connect button, ambient state-driven glow, and a polished status pill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: VpnUiState,
    trafficStats: TrafficStats,
    userEmail: String?,
    killSwitchEnabled: Boolean,
    favoriteServers: Set<String> = emptySet(),
    multiHop: MultiHopSelection = MultiHopSelection(),
    onMultiHopChange: (enabled: Boolean, entryId: String?, exitId: String?) -> Unit = { _, _, _ -> },
    onConnect: () -> Unit,
    onConnectMultiHop: (entryId: String, exitId: String) -> Unit = { _, _ -> },
    onDisconnect: () -> Unit,
    onSelectServer: (VpnServer) -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
    onRefreshServers: () -> Unit = {},
    onOpenServers: () -> Unit,
    onLogout: () -> Unit,
    updateInfo: AppUpdateInfo? = null,
    showUpdateBanner: Boolean = false,
    onUpdateApp: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onViewPlans: () -> Unit = {},
    onDismissMessage: () -> Unit = {},
) {
    val palette = BirdoColors.current
    // What the screen says and offers is decided by the pure functions in
    // HomeConnectionModel.kt, so the vocabulary and controls are unit-tested.
    val connection = homeConnection(state)
    val isConnected = connection.isConnected
    val isConnecting = connection.isConnecting
    val isDisconnecting = connection.isDisconnecting
    val isKillSwitchActive = state.killSwitchActive
    var showServerSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Multi-Hop is a SOVEREIGN-only feature. Free / OPERATIVE users see the
    // toggle but tapping it surfaces an upgrade prompt rather than enabling it.
    val isSovereign = state.subscription?.plan?.equals("SOVEREIGN", ignoreCase = true) == true

    // Multi-Hop state — toggle lives in the top bar, server selection happens
    // inline (the single ServerSelector becomes Entry → Exit when armed).
    // State is hoisted to VpnViewModel and persisted in AppPreferences, so the
    // arming + entry/exit picks survive process death, not just rotation.
    // VpnServer is not Parcelable, so ids are stored and resolved back to
    // VpnServer from the loaded server list. Arming is honoured only while the
    // plan is SOVEREIGN: a persisted armed state from a since-downgraded plan
    // must not resurrect the paid feature.
    val multiHopEnabled = multiHop.enabled && isSovereign
    val multiHopEntryId = multiHop.entryId
    val multiHopExitId = multiHop.exitId
    val multiHopEntry = remember(multiHopEntryId, state.servers) {
        state.servers.firstOrNull { it.id == multiHopEntryId }
    }
    val multiHopExit = remember(multiHopExitId, state.servers) {
        state.servers.firstOrNull { it.id == multiHopExitId }
    }
    // A connectable multi-hop route, or null. Resolving it once means the connect
    // button and its click handler can't disagree about readiness, and neither
    // needs `!!` to reach the servers it just proved are non-null.
    val multiHopRoute: Pair<VpnServer, VpnServer>? =
        if (multiHopEnabled &&
            multiHopEntry != null &&
            multiHopExit != null &&
            multiHopEntry.id != multiHopExit.id
        ) {
            multiHopEntry to multiHopExit
        } else {
            null
        }

    var multiHopPickerTarget by remember { mutableStateOf<MultiHopTarget?>(null) }
    val multiHopSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val multiHopUpsell = stringResource(R.string.home_multihop_upsell)

    // Measured height of the bottom action panel — the snackbar rides just above
    // it. The panel grows and shrinks with connection state, so this cannot be a
    // constant.
    var bottomPanelHeightPx by remember { mutableIntStateOf(0) }
    val bottomPanelHeight = with(LocalDensity.current) { bottomPanelHeightPx.toDp() }

    // The moment protection engages is the emotional peak of the app — mark it
    // with a confirm haptic so the user physically feels the tunnel come up.
    LaunchedEffect(isConnected) {
        if (isConnected) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Mullvad-style full-bleed background map. Everything else floats
        // over it. We don't draw the map while the server sheet is open
        // because the sheet's scrim covers it anyway.
        // `globeSuppressed` is the perf HUD's globe-off baseline: same screen,
        // same everything, globe gone. It is a compile-time `false` in a stock
        // release build. See app.birdo.vpn.perf.GlobePerfOverlay.
        if (!showServerSheet && !globeSuppressed()) {
            WorldGlobe(
                servers = state.servers,
                // A Multi-Hop session focuses (and draws its arc to) the ENTRY
                // node, as on iOS — not the last single-hop selection (P1-parity-003).
                selectedServerId = state.liveMultiHopEntryId ?: state.selectedServer?.id,
                isConnected = isConnected,
                autoRotate = true,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Solid backdrop when the sheet is up so we don't see flicker.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.background),
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HomeTopBar(
                userEmail = userEmail,
                multiHopEnabled = multiHopEnabled,
                multiHopUnlocked = isSovereign,
                onToggleMultiHop = {
                    if (!isSovereign) {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        scope.launch {
                            snackbarHostState.showSnackbar(multiHopUpsell)
                        }
                    } else {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        // Disconnect any in-flight tunnel before swapping modes.
                        if (isConnected) onDisconnect()
                        val arming = !multiHopEnabled
                        onMultiHopChange(
                            arming,
                            if (arming) multiHopEntryId else null,
                            if (arming) multiHopExitId else null,
                        )
                    }
                },
                onLogout = onLogout,
            )

            // Status pill floats just below the top bar.
            Spacer(Modifier.height(12.dp))
            StatusPill(status = statusVisual(pillModel(state)))
            // P1-parity-019: the honest hint while the kill switch is on but
            // not armed yet — nothing is blocking until a session exists.
            if (killSwitchEnabled && !isConnected && !isKillSwitchActive) {
                Spacer(Modifier.height(8.dp))
                BirdoBadge(
                    text = stringResource(R.string.kill_switch_pending),
                    tone = BadgeTone.Neutral,
                    icon = Icons.Default.Shield,
                )
            }

            // Push the controls to the bottom of the screen so the map
            // breathes between the pill and the action panel.
            Spacer(Modifier.weight(1f))

            // Bottom action panel: stats (when connected), kill switch /
            // error banners, server selector, connect button. Sits in a
            // translucent surface so the map peeks through.
            Surface(
                color = palette.surface.copy(alpha = 0.92f),
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    // Report the panel's real height so the snackbar can sit just
                    // above it in every state instead of guessing an inset.
                    .onSizeChanged { bottomPanelHeightPx = it.height },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    // Single source of vertical rhythm — banners no longer bake
                    // in their own top padding, so spacing is uniform whichever
                    // combination of stats/alerts is visible.
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AnimatedVisibility(
                        visible = isConnected,
                        enter = fadeIn(tween(BirdoMotion.Standard, delayMillis = 80)) +
                            slideInVertically(initialOffsetY = { 16 }),
                        exit = fadeOut(),
                    ) {
                        StatsRow(connectedSince = state.connectedSince, stats = trafficStats)
                    }

                    // Server-driven update nudge. Optional updates are a calm,
                    // dismissible card; a backend-declared REQUIRED update (the
                    // owner-set floor that also refuses connects with 426) has
                    // no dismiss affordance.
                    if (showUpdateBanner && updateInfo != null) {
                        UpdateBanner(
                            info = updateInfo,
                            onUpdate = onUpdateApp,
                            onDismiss = if (updateInfo.updateRequired) null else onDismissUpdate,
                        )
                    }

                    // A1-027: with notifications off, the alert that says the
                    // VPN stopped or needs the user reaches nobody. Say so where
                    // the user will see it, with the way to fix it.
                    if (!rememberNotificationsEnabled()) {
                        val context = LocalContext.current
                        HomeBanner(
                            icon = Icons.Default.NotificationsOff,
                            message = stringResource(R.string.home_notifications_off),
                            tone = BannerTone.Info,
                            action = BannerAction(stringResource(R.string.home_notifications_turn_on)) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                                    )
                                }
                            },
                        )
                    }

                    AnimatedVisibility(visible = isKillSwitchActive) {
                        HomeBanner(
                            icon = Icons.Default.Shield,
                            message = stringResource(R.string.kill_switch_blocking),
                        )
                    }

                    // ADAPTIVE TRANSPORT: the connection is running over the
                    // stealth transport. Passive by design — the user is TOLD,
                    // never asked to decide, because the whole point of the
                    // feature is that it adapts without exposing the plumbing.
                    //
                    // But it must not be silent either. Stealth wraps WireGuard
                    // in XTLS-Reality, which costs throughput, and an
                    // unexplained slowdown reads as "this VPN is bad" rather
                    // than "this network is hostile and we routed around it".
                    // It also keeps faith with the rule the backend already
                    // follows for the inverse case (stealthUnavailableReason):
                    // never let a user be wrong about what is carrying their
                    // traffic.
                    //
                    // Gated on isConnected so it cannot linger over a torn-down
                    // tunnel — stealthActive is only meaningful for a live one.
                    //
                    // Info tone, never the red error style: this is a healthy,
                    // protected connection (P1-parity-004).
                    AnimatedVisibility(visible = isConnected && state.stealthActive) {
                        HomeBanner(
                            icon = Icons.Default.VisibilityOff,
                            message = stringResource(R.string.stealth_fallback_active),
                            tone = BannerTone.Info,
                        )
                    }

                    // ONE message banner (A2-011): a message of the Connect
                    // screen's own (dismissible), else the session's Error with
                    // the action that fixes it (P1-parity-040). The two used to
                    // render side by side, often with the same text.
                    val message = homeMessage(state)
                    if (message != null) {
                        val sessionError = state.vpnState as? VpnState.Error
                        val remedy = if (state.connectError == null) sessionError?.kind?.let { remedyFor(it) } else null
                        HomeBanner(
                            icon = Icons.Default.ErrorOutline,
                            message = message,
                            action = remedy?.let { r ->
                                BannerAction(stringResource(r.label)) {
                                    when (r) {
                                        Remedy.OPEN_SETTINGS -> onOpenSettings()
                                        Remedy.VIEW_PLANS -> onViewPlans()
                                        Remedy.UPDATE -> onUpdateApp()
                                        Remedy.CHOOSE_SERVER ->
                                            if (state.servers.isNotEmpty()) showServerSheet = true else onOpenServers()
                                    }
                                }
                            },
                            onDismiss = if (state.connectError != null) onDismissMessage else null,
                        )
                    }

                    if (multiHopEnabled) {
                        MultiHopServerPair(
                            entry = multiHopEntry,
                            exit = multiHopExit,
                            enabled = !isConnecting && !isDisconnecting,
                            onPickEntry = { multiHopPickerTarget = MultiHopTarget.Entry },
                            onPickExit = { multiHopPickerTarget = MultiHopTarget.Exit },
                        )
                    } else {
                        ServerSelector(
                            state = state,
                            enabled = !isConnecting && !isDisconnecting,
                            onClick = {
                                if (state.servers.isNotEmpty()) {
                                    showServerSheet = true
                                } else {
                                    onOpenServers()
                                }
                            },
                        )
                    }

                    val cta = ctaModel(
                        connection,
                        multiHopArmed = multiHopEnabled,
                        multiHopReady = multiHopRoute != null,
                    )
                    CompactConnectButton(
                        cta = cta,
                        isConnected = isConnected,
                        onClick = {
                            when (cta.action) {
                                CtaAction.DISCONNECT -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                    onDisconnect()
                                }
                                CtaAction.CONNECT_MULTI_HOP -> multiHopRoute?.let { route ->
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onConnectMultiHop(route.first.id, route.second.id)
                                }
                                CtaAction.CONNECT -> {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onConnect()
                                }
                                CtaAction.NONE -> Unit
                            }
                        },
                    )
                    // A1-010: the way out of a connect that is taking too long.
                    if (cta.showCancel) {
                        TextButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                onDisconnect()
                            },
                            modifier = Modifier.testTag(TestTags.CANCEL_CONNECT_BUTTON),
                        ) {
                            Text(
                                text = stringResource(R.string.cancel_connect),
                                color = palette.onSurfaceMuted,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }

        // Brand-styled snackbar — the stock M3 inverseSurface pill is a light
        // grey slab against the dark glass.
        //
        // It sits in the Column directly ABOVE the action panel rather than
        // being overlaid on the Box with a guessed bottom offset: the panel's
        // height changes with state (stats row, banners, the multi-hop entry/exit
        // pair), so any fixed inset is wrong in most states and would land the
        // snackbar on top of the server selector.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = bottomPanelHeight + 12.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = palette.surfaceElevated,
                contentColor = palette.onSurface,
                actionColor = palette.accent,
                shape = RoundedCornerShape(14.dp),
            )
        }

        // Frame-timing HUD. Compiles to nothing in a stock release build.
        GlobePerfOverlay(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 8.dp),
        )
    }

    if (showServerSheet) {
        ServerSelectorSheet(
            servers = state.servers,
            selectedServer = state.selectedServer,
            favoriteServers = favoriteServers,
            sheetState = sheetState,
            onSelectServer = onSelectServer,
            onToggleFavorite = onToggleFavorite,
            onDismiss = { showServerSheet = false },
        )
    }

    multiHopPickerTarget?.let { target ->
        ServerSelectorSheet(
            servers = state.servers,
            selectedServer = if (target == MultiHopTarget.Entry) multiHopEntry else multiHopExit,
            favoriteServers = favoriteServers,
            sheetState = multiHopSheetState,
            onSelectServer = { srv ->
                if (target == MultiHopTarget.Entry) {
                    onMultiHopChange(multiHopEnabled, srv.id, multiHopExitId)
                } else {
                    onMultiHopChange(multiHopEnabled, multiHopEntryId, srv.id)
                }
                multiHopPickerTarget = null
            },
            onToggleFavorite = onToggleFavorite,
            onDismiss = { multiHopPickerTarget = null },
        )
    }
}

// ── Multi-Hop Bar ───────────────────────────────────────────────────────────
private enum class MultiHopTarget { Entry, Exit }

/** Compact icon toggle that lives in the top-left of the Connect screen. */
@Composable
private fun MultiHopTopAction(
    enabled: Boolean,
    unlocked: Boolean,
    onClick: () -> Unit,
) {
    val tint by animateColorAsState(
        targetValue = when {
            !unlocked -> BirdoWhite40
            enabled -> BirdoBrand.Accent
            else -> Color.White
        },
        animationSpec = tween(BirdoMotion.Quick, easing = BirdoMotion.EaseStandard),
        label = "multiHopTint",
    )
    val bg by animateColorAsState(
        targetValue = if (enabled && unlocked) BirdoBrand.Accent.copy(alpha = 0.18f) else BirdoWhite05,
        animationSpec = tween(BirdoMotion.Quick, easing = BirdoMotion.EaseStandard),
        label = "multiHopBg",
    )
    val border by animateColorAsState(
        targetValue = if (enabled && unlocked) BirdoBrand.Accent.copy(alpha = 0.55f) else BirdoBrand.HairlineSoft,
        animationSpec = tween(BirdoMotion.Quick, easing = BirdoMotion.EaseStandard),
        label = "multiHopBorder",
    )
    val label = stringResource(if (unlocked) R.string.cd_multihop else R.string.cd_multihop_locked)
    val onState = stringResource(R.string.cd_state_on)
    val offState = stringResource(R.string.cd_state_off)

    Box(
        modifier = Modifier
            // 40dp visual chip, 48dp hit area.
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .toggleable(value = enabled, role = Role.Switch, onValueChange = { onClick() })
            .semantics {
                contentDescription = label
                stateDescription = if (enabled) onState else offState
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.AltRoute,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        if (!unlocked) {
            // Tiny lock badge in the lower-right corner to signal SOVEREIGN-only.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
    }
}

/** When Multi-Hop is armed the single ServerSelector is replaced by two
 *  full-width selectors, one for the entry server and one for the exit. */
@Composable
private fun MultiHopServerPair(
    entry: VpnServer?,
    exit: VpnServer?,
    enabled: Boolean,
    onPickEntry: () -> Unit,
    onPickExit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MultiHopServerCard(
            label = stringResource(R.string.home_entry_server),
            server = entry,
            enabled = enabled,
            onClick = onPickEntry,
        )
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = BirdoWhite40,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        MultiHopServerCard(
            label = stringResource(R.string.home_exit_server),
            server = exit,
            enabled = enabled,
            onClick = onPickExit,
        )
        if (entry != null && exit != null && entry.id == exit.id) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.home_entry_exit_must_differ),
                color = BirdoRed,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun MultiHopServerCard(
    label: String,
    server: VpnServer?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    BirdoCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BirdoWhite05)
                    .border(1.dp, BirdoBrand.HairlineSoft, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (server != null) countryCodeToFlag(server.countryCode) else "🌐",
                    fontSize = 22.sp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label.uppercase(),
                    color = BirdoBrand.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = server?.name ?: stringResource(R.string.home_choose_server),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (server != null) {
                    Text(
                        text = server.city.ifBlank { server.country },
                        color = BirdoWhite60,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.cd_select_server),
                tint = BirdoWhite40,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ── Top Bar ─────────────────────────────────────────────────────────────────

@Composable
private fun HomeTopBar(
    userEmail: String?,
    multiHopEnabled: Boolean,
    multiHopUnlocked: Boolean,
    onToggleMultiHop: () -> Unit,
    onLogout: () -> Unit,
) {
    val palette = BirdoColors.current
    Surface(color = palette.surface.copy(alpha = 0.78f), tonalElevation = 0.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MultiHopTopAction(
                    enabled = multiHopEnabled,
                    unlocked = multiHopUnlocked,
                    onClick = onToggleMultiHop,
                )
                Spacer(Modifier.width(8.dp))
                BrandLockup()
                Spacer(Modifier.weight(1f))
                if (userEmail != null) {
                    // A weight(1f) spacer collapses to ZERO once the row's content
                    // overflows, which it does for an anonymous account id — the
                    // username then sits flush against "BirdoVPN" with no gap at
                    // all. The start padding is what actually guarantees the gap;
                    // the spacer only distributes what is left over.
                    Text(
                        text = userEmail,
                        color = palette.onSurfaceFaint,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 12.dp, end = 6.dp)
                            .widthIn(max = 120.dp),
                    )
                }
                BirdoIconAction(
                    icon = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = stringResource(R.string.logout),
                    onClick = onLogout,
                    tint = palette.onSurfaceMuted,
                )
            }
            HorizontalDivider(color = palette.hairlineSoft, thickness = 1.dp)
        }
    }
}

@Composable
private fun BrandLockup() {
    val palette = BirdoColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIconMark(size = 32.dp, cornerRadius = 10.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.app_name),
            color = palette.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Status Pill ────────────────────────────────────────────────────────────

/** One immutable bundle so the pill animates label + tone + icon as a unit. */
@androidx.compose.runtime.Immutable
private data class StatusVisual(
    val text: String,
    val tone: BadgeTone,
    val icon: androidx.compose.ui.graphics.vector.ImageVector?,
    val pulse: Boolean,
)

/** The pill's words and look, from the pure [pillModel]. */
@Composable
private fun statusVisual(model: PillModel): StatusVisual = StatusVisual(
    text = stringResource(model.text),
    tone = model.tone,
    icon = when (model.icon) {
        PillIcon.NONE -> null
        PillIcon.SYNC -> Icons.Default.Sync
        PillIcon.ERROR -> Icons.Default.ErrorOutline
        PillIcon.OFFLINE -> Icons.Default.WifiOff
    },
    pulse = model.pulse,
)

@Composable
private fun StatusPill(
    status: StatusVisual,
    modifier: Modifier = Modifier,
) {
    // The animated state carries text, tone, icon and pulse TOGETHER. Animating
    // on the text alone and reading the rest from the enclosing scope would
    // render the OUTGOING label in the INCOMING colour mid-crossfade — e.g.
    // "Connecting…" flashing green on its way out.

    // Polite live region: TalkBack announces every connection-state change —
    // for a VPN, silent state transitions are a safety problem, not a nicety.
    Box(
        modifier = modifier
            .testTag(TestTags.VPN_STATUS)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (fadeIn(tween(BirdoMotion.Standard, easing = BirdoMotion.Decel)) +
                    slideInVertically(
                        animationSpec = tween(BirdoMotion.Standard, easing = BirdoMotion.Decel),
                        initialOffsetY = { it / 2 },
                    ))
                    .togetherWith(fadeOut(tween(BirdoMotion.Quick, easing = BirdoMotion.Accel)))
            },
            label = "statusPill",
        ) { s ->
            BirdoBadge(text = s.text, tone = s.tone, icon = s.icon, pulseDot = s.pulse)
        }
    }
}

// ── Compact Connect Button ─────────────────────────────────────────────────

/**
 * Pill-style connect/disconnect action sized to match [ServerSelector] so the
 * two stack as a tidy pair under a much larger globe. State changes morph the
 * gradient (idle violet → busy → connected green) instead of hard-cutting.
 */
@Composable
private fun CompactConnectButton(
    cta: CtaModel,
    isConnected: Boolean,
    onClick: () -> Unit,
) {
    val busy = cta.busy
    val multiHopBlocked = cta.label == R.string.home_choose_entry_exit
    val multiHopReady = cta.action == CtaAction.CONNECT_MULTI_HOP

    // The idle → connecting → connected transition is the most important state
    // change in the app: morph the gradient rather than hard-cutting it.
    val (targetStart, targetEnd) = when {
        isConnected -> BirdoGreen to BirdoAccentDeep
        busy || cta.action == CtaAction.DISCONNECT -> BirdoBrand.AccentSoft to BirdoBrand.AccentDeep
        multiHopBlocked -> BirdoWhite10 to BirdoWhite10
        multiHopReady -> BirdoBrand.Accent to BirdoBrand.AccentDeep
        else -> Color(0xFF047857) to Color(0xFF064E3B) // PrimaryGradient stops (deep emerald)
    }
    val startColor by animateColorAsState(
        targetValue = targetStart,
        animationSpec = tween(BirdoMotion.Emphasis, easing = BirdoMotion.Decel),
        label = "connectStart",
    )
    val endColor by animateColorAsState(
        targetValue = targetEnd,
        animationSpec = tween(BirdoMotion.Emphasis, easing = BirdoMotion.Decel),
        label = "connectEnd",
    )
    val shadowColor by animateColorAsState(
        targetValue = if (isConnected) BirdoGreenShadow else BirdoBrand.Accent.copy(alpha = 0.45f),
        animationSpec = tween(BirdoMotion.Emphasis, easing = BirdoMotion.Decel),
        label = "connectShadow",
    )
    val brush = Brush.linearGradient(listOf(startColor, endColor))

    val label = stringResource(cta.label)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(120, easing = BirdoMotion.EaseStandard),
        label = "connectPress",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Min-height, not fixed: labels must survive large font scales.
            .heightIn(min = 60.dp)
            .scale(pressScale)
            .shadow(
                elevation = 14.dp,
                shape = RoundedCornerShape(16.dp),
                ambientColor = shadowColor,
                spotColor = shadowColor,
            )
            .clip(RoundedCornerShape(16.dp))
            .background(brush)
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !busy,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 12.dp)
            .testTag(TestTags.CONNECT_BUTTON),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeWidth = 2.4.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ── Stats Row ──────────────────────────────────────────────────────────────

/**
 * Reads [TrafficStats] — deliberately NOT VpnUiState — so the per-second byte
 * tick recomposes only these three tiles, not the whole Connect screen.
 * `stats.tickMs` is read so the duration recomputes each poll while connected.
 */
@Composable
private fun StatsRow(connectedSince: Long, stats: TrafficStats) {
    val duration = remember(connectedSince, stats.tickMs) {
        FormatUtils.formatDuration(connectedSince)
    }
    val rx = remember(stats.rxBytes) { FormatUtils.formatBytes(stats.rxBytes) }
    val tx = remember(stats.txBytes) { FormatUtils.formatBytes(stats.txBytes) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile(
            icon = Icons.Default.Schedule,
            label = stringResource(R.string.stats_duration),
            value = duration,
            tint = BirdoBrand.AccentSoft,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            icon = Icons.Default.ArrowDownward,
            label = stringResource(R.string.stats_download),
            value = rx,
            tint = BirdoGreenLight,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            icon = Icons.Default.ArrowUpward,
            label = stringResource(R.string.stats_upload),
            value = tx,
            tint = BirdoBlue,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    BirdoCard(
        // One node per tile, "Download: 1.2 MB", instead of an icon labelled
        // with an arrow glyph and a separate value node (P1-parity-030).
        modifier = modifier.clearAndSetSemantics { contentDescription = "$label: $value" },
        cornerRadius = 12.dp,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            // Roll the digits rather than teleporting them. Only recomposes when
            // the FORMATTED string changes, so a stat ticking within the same
            // display unit costs nothing.
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    (fadeIn(tween(BirdoMotion.Quick)) +
                        slideInVertically(
                            animationSpec = tween(BirdoMotion.Quick, easing = BirdoMotion.Decel),
                            initialOffsetY = { it / 2 },
                        ))
                        .togetherWith(
                            fadeOut(tween(BirdoMotion.Instant)) +
                                slideOutVertically(
                                    animationSpec = tween(BirdoMotion.Quick, easing = BirdoMotion.Accel),
                                    targetOffsetY = { -it / 2 },
                                ),
                        )
                },
                label = "statValue",
            ) { animatedValue ->
                Text(
                    text = animatedValue,
                    color = BirdoColors.current.onSurface,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ── Banner ────────────────────────────────────────────────────────────────

/**
 * Whether the app may post notifications, re-read every time the screen
 * resumes — the user turns them on in system settings and comes back.
 */
@Composable
private fun rememberNotificationsEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var enabled by remember {
        mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                enabled = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}

/** Danger for problems; Info (brand) for a notice about a healthy connection. */
private enum class BannerTone { Danger, Info }

private data class BannerAction(val label: String, val onClick: () -> Unit)

/**
 * One banner for every inline message on the Connect screen. No internal
 * padding — the parent Column owns vertical rhythm. A Danger banner is an
 * assertive live region: an alert that appears silently is an alert a TalkBack
 * user never receives. An Info banner is polite, and never styled as an error.
 */
@Composable
private fun HomeBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Danger,
    action: BannerAction? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val (fg, bg) = when (tone) {
        BannerTone.Danger -> BirdoRed to BirdoRedBg
        BannerTone.Info -> BirdoBrand.AccentSoft to BirdoAccentBg
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = if (tone == BannerTone.Danger) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, fg.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                color = fg,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = action.onClick) {
                    Text(text = action.label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_dismiss_message),
                        tint = fg.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// ── Update banner ──────────────────────────────────────────────────────────

/**
 * App-update nudge. Emerald (brand accent) for an optional update, red for a
 * required one — matching HomeBanner's severity language. `onDismiss = null`
 * removes the dismiss affordance entirely (required updates).
 */
@Composable
private fun UpdateBanner(
    info: AppUpdateInfo,
    onUpdate: () -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val required = info.updateRequired
    val accent = if (required) BirdoRed else BirdoAccent
    val bg = if (required) BirdoRedBg else BirdoAccentBg
    val title = stringResource(
        if (required) R.string.update_required_title else R.string.update_available_title,
    )
    val body = if (required) {
        stringResource(R.string.update_required_body)
    } else {
        stringResource(R.string.update_available_body, info.latestVersion ?: "")
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = body,
                    color = BirdoWhite60,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = onUpdate) {
                Text(
                    text = stringResource(R.string.update_action),
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_dismiss_update),
                        tint = BirdoWhite40,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// ── Server Selector ────────────────────────────────────────────────────────

@Composable
private fun ServerSelector(state: VpnUiState, enabled: Boolean, onClick: () -> Unit) {
    val server = state.selectedServer
    BirdoCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .testTag(TestTags.SERVER_SELECTOR),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BirdoWhite05)
                    .border(1.dp, BirdoBrand.HairlineSoft, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (server != null) countryCodeToFlag(server.countryCode) else "🌐",
                    fontSize = 22.sp,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server?.name ?: stringResource(R.string.select_server),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (server != null) {
                    Text(
                        text = server.city.ifBlank { server.country },
                        color = BirdoWhite60,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.cd_select_server),
                tint = BirdoWhite40,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * Whether the perf HUD is currently suppressing the globe to collect its
 * globe-off baseline. Always `false` unless the HUD is compiled in, and the
 * guard keeps the composition from subscribing to debug state in release.
 */
@Composable
private fun globeSuppressed(): Boolean =
    if (GlobePerf.ENABLED) GlobePerfControls.globeHidden.value else false
