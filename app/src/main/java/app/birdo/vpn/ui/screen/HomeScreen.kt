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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.service.VpnState
import app.birdo.vpn.ui.TestTags
import app.birdo.vpn.ui.components.*
import app.birdo.vpn.ui.theme.*
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
    userEmail: String?,
    killSwitchEnabled: Boolean,
    favoriteServers: Set<String> = emptySet(),
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectServer: (VpnServer) -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
    onRefreshServers: () -> Unit = {},
    onOpenServers: () -> Unit,
    onLogout: () -> Unit,
) {
    val palette = BirdoColors.current
    val isConnected = state.vpnState is VpnState.Connected
    val isConnecting = state.vpnState is VpnState.Connecting
    val isDisconnecting = state.vpnState is VpnState.Disconnecting
    val isError = state.vpnState is VpnState.Error
    val isKillSwitchActive = state.killSwitchActive
    var showServerSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(modifier = Modifier.fillMaxSize()) {
        HomeTopBar(userEmail = userEmail, onLogout = onLogout)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // Hero globe — sized as a fraction of the available height so the
            // layout adapts to any screen (small phones, tablets, foldables).
            // PERF: pause the rotation animation while the server bottom sheet
            // is open — a 60fps animating Canvas underneath the modal sheet
            // makes drag/scroll feel laggy.
            WorldGlobe(
                servers = state.servers,
                selectedServerId = state.selectedServer?.id,
                isConnected = isConnected,
                autoRotate = !showServerSheet,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.55f)
                    .align(Alignment.TopCenter),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(16.dp))

                StatusPill(
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    isDisconnecting = isDisconnecting,
                    isError = isError,
                )

                Spacer(Modifier.height(8.dp))
                LocationLabel(state = state)

                // Flexible spacer so the connect button always sits below the
                // globe regardless of screen height.
                Spacer(Modifier.weight(1f))

                HeroConnectButton(
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    isDisconnecting = isDisconnecting,
                    onClick = {
                        when {
                            isConnected -> onDisconnect()
                            isConnecting || isDisconnecting -> Unit
                            else -> onConnect()
                        }
                    },
                )

                Spacer(Modifier.height(16.dp))

                AnimatedVisibility(
                    visible = isConnected,
                    enter = fadeIn(tween(BirdoMotion.Standard, delayMillis = 80)) +
                        slideInVertically(initialOffsetY = { 16 }),
                    exit = fadeOut(),
                ) {
                    StatsRow(state = state)
                }

                AnimatedVisibility(visible = isConnected) {
                    FeatureBadgesRow(
                        killSwitchEnabled = killSwitchEnabled,
                        stealth = state.stealthActive,
                        quantum = state.quantumActive,
                    )
                }

                AnimatedVisibility(visible = isKillSwitchActive) {
                    KillSwitchAlert()
                }

                if (isError) ErrorBanner((state.vpnState as VpnState.Error).message)
                if (state.error != null) ErrorBanner(state.error)

                Spacer(Modifier.weight(0.4f))

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

                Spacer(Modifier.height(8.dp))
            }
        }
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
}

// ── Top Bar ─────────────────────────────────────────────────────────────────

@Composable
private fun HomeTopBar(userEmail: String?, onLogout: () -> Unit) {
    val palette = BirdoColors.current
    Surface(color = palette.surface, tonalElevation = 0.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .heightIn(min = 60.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandLockup()
                Spacer(Modifier.weight(1f))
                if (userEmail != null) {
                    Text(
                        text = userEmail,
                        color = palette.onSurfaceFaint,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .widthIn(max = 160.dp),
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
        )
    }
}

// ── Ambient Glow ────────────────────────────────────────────────────────────

@Composable
private fun HeroAmbientGlow(isConnected: Boolean, isError: Boolean, modifier: Modifier = Modifier) {
    val brush: Brush = when {
        isError -> BirdoBrand.ErrorGradient
        isConnected -> BirdoBrand.ConnectedGradient
        else -> BirdoBrand.IdleGradient
    }
    Box(modifier.background(brush))
}

// ── Status Pill ────────────────────────────────────────────────────────────

@Composable
private fun StatusPill(
    isConnected: Boolean,
    isConnecting: Boolean,
    isDisconnecting: Boolean,
    isError: Boolean,
) {
    val text: String
    val tone: BadgeTone
    val icon: androidx.compose.ui.graphics.vector.ImageVector?
    val pulse: Boolean
    when {
        isConnected -> { text = stringResource(R.string.status_protected); tone = BadgeTone.Success; icon = null; pulse = true }
        isConnecting -> { text = stringResource(R.string.connecting); tone = BadgeTone.Warning; icon = Icons.Default.Sync; pulse = false }
        isDisconnecting -> { text = stringResource(R.string.disconnecting); tone = BadgeTone.Warning; icon = Icons.Default.Sync; pulse = false }
        isError -> { text = stringResource(R.string.status_error); tone = BadgeTone.Danger; icon = Icons.Default.ErrorOutline; pulse = false }
        else -> { text = stringResource(R.string.status_not_connected); tone = BadgeTone.Neutral; icon = Icons.Default.WifiOff; pulse = false }
    }
    Box(modifier = Modifier.testTag(TestTags.VPN_STATUS)) {
        BirdoBadge(text = text, tone = tone, icon = icon, pulseDot = pulse)
    }
}

// ── Location Label ─────────────────────────────────────────────────────────

@Composable
private fun LocationLabel(state: VpnUiState) {
    val isConnected = state.vpnState is VpnState.Connected
    val server = state.connectedServer
    val ip = state.publicIp
    val text = when {
        isConnected && server != null && ip != null -> "$server  ·  $ip"
        isConnected && server != null -> server
        isConnected && ip != null -> ip
        else -> "Your real IP is exposed"
    }
    val color = if (isConnected) BirdoWhite80 else BirdoWhite60
    Text(
        text = text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
}

// ── Hero Connect Button ────────────────────────────────────────────────────

@Composable
private fun HeroConnectButton(
    isConnected: Boolean,
    isConnecting: Boolean,
    isDisconnecting: Boolean,
    onClick: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "btn")

    val ring1Scale by transition.animateFloat(
        initialValue = 1f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "r1s",
    )
    val ring1Alpha by transition.animateFloat(
        initialValue = 0.35f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "r1a",
    )
    val ring2Scale by transition.animateFloat(
        initialValue = 1f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing, delayMillis = 500), RepeatMode.Restart),
        label = "r2s",
    )
    val ring2Alpha by transition.animateFloat(
        initialValue = 0.25f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing, delayMillis = 500), RepeatMode.Restart),
        label = "r2a",
    )

    val buttonSize = 168.dp

    Box(
        modifier = Modifier.size(buttonSize * 1.7f),
        contentAlignment = Alignment.Center,
    ) {
        if (isConnected) {
            Box(
                Modifier
                    .size(buttonSize)
                    .scale(ring1Scale)
                    .clip(CircleShape)
                    .background(BirdoGreen.copy(alpha = ring1Alpha)),
            )
            Box(
                Modifier
                    .size(buttonSize)
                    .scale(ring2Scale)
                    .clip(CircleShape)
                    .background(BirdoGreen.copy(alpha = ring2Alpha)),
            )
        }

        if (!isConnected && !isConnecting && !isDisconnecting) {
            Box(
                Modifier
                    .size(buttonSize * 1.4f)
                    .clip(CircleShape)
                    .background(BirdoBrand.IdleGradient),
            )
        }

        // Outer gradient halo ring
        Box(
            modifier = Modifier
                .size(buttonSize + 14.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isConnected -> Brush.linearGradient(listOf(BirdoGreen, BirdoBrand.Teal))
                        isConnecting || isDisconnecting -> Brush.linearGradient(listOf(BirdoBrand.PurpleSoft, BirdoBrand.PurpleDeep))
                        else -> BirdoBrand.PrimaryGradient
                    }
                ),
        )

        // Inner button
        Box(
            modifier = Modifier
                .size(buttonSize)
                .shadow(
                    elevation = if (isConnected) 28.dp else 16.dp,
                    shape = CircleShape,
                    ambientColor = if (isConnected) BirdoGreenShadow else BirdoBrand.Purple.copy(alpha = 0.45f),
                    spotColor = if (isConnected) BirdoGreenShadow else BirdoBrand.Purple.copy(alpha = 0.45f),
                )
                .clip(CircleShape)
                .background(
                    when {
                        isConnected -> Brush.radialGradient(listOf(BirdoGreen, Color(0xFF166534)))
                        isConnecting || isDisconnecting -> Brush.radialGradient(listOf(BirdoBrand.PurpleDeep, Color(0xFF2E1065)))
                        else -> Brush.radialGradient(listOf(BirdoBrand.Surface3, BirdoBrand.Surface1))
                    }
                )
                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .testTag(TestTags.CONNECT_BUTTON),
            contentAlignment = Alignment.Center,
        ) {
            if (isConnecting || isDisconnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(64.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.2f),
                    strokeWidth = 4.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = if (isConnected) stringResource(R.string.disconnect)
                    else stringResource(R.string.connect),
                    tint = if (isConnected) Color.White else BirdoWhite80,
                    modifier = Modifier.size(58.dp),
                )
            }
        }
    }
}

// ── Stats Row ──────────────────────────────────────────────────────────────

@Composable
private fun StatsRow(state: VpnUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile(
            icon = Icons.Default.Schedule,
            label = stringResource(R.string.stats_duration),
            value = FormatUtils.formatDuration(state.connectedSince),
            tint = BirdoBrand.PurpleSoft,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            icon = Icons.Default.ArrowDownward,
            label = stringResource(R.string.stats_download),
            value = FormatUtils.formatBytes(state.rxBytes),
            tint = BirdoGreenLight,
            modifier = Modifier.weight(1f),
        )
        StatTile(
            icon = Icons.Default.ArrowUpward,
            label = stringResource(R.string.stats_upload),
            value = FormatUtils.formatBytes(state.txBytes),
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
        modifier = modifier,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = label,
                color = BirdoWhite60,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// ── Feature Badges ─────────────────────────────────────────────────────────

@Composable
private fun FeatureBadgesRow(killSwitchEnabled: Boolean, stealth: Boolean, quantum: Boolean) {
    val any = killSwitchEnabled || stealth || quantum
    if (!any) return
    Row(
        modifier = Modifier.padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (killSwitchEnabled) {
            BirdoBadge(text = stringResource(R.string.kill_switch_active), tone = BadgeTone.Success, icon = Icons.Default.Shield)
        }
        if (stealth) {
            BirdoBadge(text = "Stealth", tone = BadgeTone.Info, icon = Icons.Default.VisibilityOff)
        }
        if (quantum) {
            BirdoBadge(text = "Quantum", tone = BadgeTone.Brand, icon = Icons.Default.Lock)
        }
    }
}

// ── Kill Switch Alert ──────────────────────────────────────────────────────

@Composable
private fun KillSwitchAlert() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = BirdoRedBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, BirdoRed.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Shield, null, tint = BirdoRed, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.kill_switch_blocking),
                color = BirdoRed,
                fontSize = 13.sp,
            )
        }
    }
}

// ── Error Banner ───────────────────────────────────────────────────────────

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(14.dp),
        color = BirdoRedBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, BirdoRed.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = BirdoRed, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                color = BirdoRed,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
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
                        text = "${server.city.ifBlank { server.country }}  ·  ${stringResource(R.string.server_load, server.load)}",
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
