package app.birdo.vpn.ui.screen

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import app.birdo.vpn.R
import app.birdo.vpn.data.model.VpnServer
import androidx.compose.ui.semantics.Role
import app.birdo.vpn.ui.components.*
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.utils.countryCodeToFlag

/**
 * Server list screen — matches the Windows client design:
 * - Search input with glass styling
 * - Filter pills: All | ★ Favorites | ⚡ High-Speed | ⇄ Port Forwarding
 * - Server cards with country flag, name, load bar, favorite star
 * - White-based color scheme (no purple accents)
 * - Selected server: subtle white ring (ring-white/20)
 *
 * The old "Streaming" / "P2P" pills were removed: both flags were cosmetic and
 * false on every node, so the pills matched NOTHING in production. They are
 * replaced by the two capabilities Birdo actually ships — a low-load /
 * high-throughput node, and inbound port forwarding. Birdo does not offer
 * streaming-unblocking or P2P servers, so a pill must not imply it does.
 */
enum class ServerFilter(@StringRes val labelRes: Int, val icon: String) {
    All(R.string.filter_all, ""),
    Favorites(R.string.filter_favorites, "★"),
    HighSpeed(R.string.filter_high_speed, "⚡"),
    PortForwarding(R.string.filter_port_forwarding, "⇄"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    servers: List<VpnServer>,
    selectedServer: VpnServer?,
    isLoading: Boolean,
    favoriteServers: Set<String>,
    onSelectServer: (VpnServer) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(ServerFilter.All) }
    val palette = BirdoColors.current

    val filteredServers = remember(servers, searchQuery, activeFilter, favoriteServers) {
        servers
            .filter { server ->
                val matchesSearch = searchQuery.isBlank() ||
                    server.name.contains(searchQuery, ignoreCase = true) ||
                    server.country.contains(searchQuery, ignoreCase = true) ||
                    server.city.contains(searchQuery, ignoreCase = true)

                val matchesFilter = when (activeFilter) {
                    ServerFilter.All -> true
                    ServerFilter.Favorites -> favoriteServers.contains(server.id)
                    ServerFilter.HighSpeed -> server.isHighSpeed
                    ServerFilter.PortForwarding -> server.isPortForwarding
                }

                matchesSearch && matchesFilter
            }
            .sortedWith(
                // Locked nodes (plan too low) sink below usable ones — they are
                // shown, not hidden, so the user can see what a plan unlocks.
                compareByDescending<VpnServer> { favoriteServers.contains(it.id) }
                    .thenBy { !it.accessible }
                    .thenBy { !it.isOnline }
                    .thenBy { it.load }
                    .thenBy { it.name }
            )
    }

    Column(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // ── Header ──
        BirdoTopBar(
            title = stringResource(R.string.servers_title),
            subtitle = stringResource(R.string.servers_count, filteredServers.size),
            onBack = onBack,
            actions = {
                BirdoIconAction(
                    icon = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh),
                    onClick = onRefresh,
                )
            },
        )

        // ── Search bar ──
        BirdoTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = stringResource(R.string.servers_search_placeholder),
            leadingIcon = Icons.Default.Search,
            trailingIcon = if (searchQuery.isNotBlank()) {
                {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, stringResource(R.string.cd_clear), tint = BirdoWhite40, modifier = Modifier.size(16.dp))
                    }
                }
            } else null,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )

        // ── Filter pills ──
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ServerFilter.entries) { filter ->
                val isActive = filter == activeFilter
                val favCount = if (filter == ServerFilter.Favorites) favoriteServers.size else null
                val label = stringResource(filter.labelRes)
                val text = buildString {
                    if (filter.icon.isNotEmpty()) {
                        append(filter.icon); append(" ")
                    }
                    append(label)
                    if (favCount != null && favCount > 0) append(" ($favCount)")
                }
                // selectable (not clickable): TalkBack must announce WHICH
                // filter is active — colour alone doesn't reach a screen reader.
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .selectable(
                            selected = isActive,
                            role = Role.Tab,
                            onClick = { activeFilter = filter },
                        ),
                    shape = RoundedCornerShape(20.dp),
                    color = if (isActive) palette.accent.copy(alpha = if (palette.isLight) 0.18f else 0.22f) else palette.surface,
                    border = BorderStroke(1.dp, if (isActive) palette.accent.copy(alpha = 0.55f) else palette.hairlineSoft),
                ) {
                    Text(
                        text = text,
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isActive) palette.accent else palette.onSurfaceMuted,
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }

        // Thin progress bar only for refresh-with-content; a first load gets
        // ghost rows below instead of a blank void.
        if (isLoading && servers.isNotEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = BirdoBrand.AccentSoft,
                trackColor = BirdoWhite05,
            )
        }

        // ── Server list ──
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isLoading && servers.isEmpty()) {
                item {
                    val pulse = birdoSkeletonPulse()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(6) {
                            BirdoSkeletonServerRow(pulseAlpha = pulse)
                        }
                    }
                }
            }

            items(
                items = filteredServers,
                key = { it.id },
            ) { server ->
                ServerCard(
                    server = server,
                    isSelected = server.id == selectedServer?.id,
                    isFavorite = favoriteServers.contains(server.id),
                    onSelect = { onSelectServer(server) },
                    onToggleFavorite = { onToggleFavorite(server.id) },
                )
            }

            // ── Empty state ──
            if (filteredServers.isEmpty() && !isLoading) {
                item {
                    BirdoEmptyState(
                        icon = Icons.Default.Dns,
                        title = when {
                            activeFilter == ServerFilter.Favorites -> stringResource(R.string.servers_no_favorites)
                            searchQuery.isNotBlank() -> stringResource(R.string.servers_no_match, searchQuery)
                            activeFilter == ServerFilter.HighSpeed -> stringResource(R.string.servers_no_high_speed)
                            activeFilter == ServerFilter.PortForwarding -> stringResource(R.string.servers_no_port_forwarding)
                            else -> stringResource(R.string.no_servers)
                        },
                        description = when {
                            activeFilter == ServerFilter.Favorites -> stringResource(R.string.servers_favorites_hint)
                            servers.isEmpty() -> stringResource(R.string.servers_empty_hint)
                            else -> null
                        },
                        action = if (servers.isEmpty() && !isLoading) {
                            {
                                BirdoButton(
                                    text = stringResource(R.string.retry),
                                    onClick = onRefresh,
                                    variant = BirdoButtonVariant.Secondary,
                                    icon = Icons.Default.Refresh,
                                )
                            }
                        } else null,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }
        }
    }
}

// ── Server Card ─────────────────────────────────────────────────────────────

private val ServerCardShape = RoundedCornerShape(14.dp)
private val FlagShape = RoundedCornerShape(10.dp)
private val LoadBarShape = RoundedCornerShape(2.dp)

/**
 * Flat, allocation-free server card optimised for fast scrolling.
 *
 * Previous version wrapped the row in [BirdoCard] (extra Box layers) and
 * allocated a new `Brush.linearGradient` per recomposition for both the card
 * border and the load-bar fill. With 100+ rows that dominated frame cost.
 *
 * This version uses solid colors only, hoists shapes to file-level vals, and
 * remembers all per-row derived state keyed on inputs that actually change.
 */
@Composable
internal fun ServerCard(
    server: VpnServer,
    isSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val palette = BirdoColors.current
    val haptics = LocalHapticFeedback.current
    val isOnline = server.isOnline
    // `accessible` is computed server-side from the user's plan vs the node's
    // minPlan. A locked node is shown (so the user can see what a plan buys)
    // but cannot be picked — offering it would only earn a server-side refusal.
    val isLocked = !server.accessible
    val isSelectable = isOnline && !isLocked
    val load = server.load
    val loadFraction = remember(load) { (load / 100f).coerceIn(0f, 1f) }
    val loadCol = remember(load) { loadColor(load) }
    // Solid colours only (the perf note above bans per-frame Brush allocation);
    // animateColorAsState is allocation-free and makes selection feel deliberate.
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) BirdoBrand.AccentSoft else palette.hairlineSoft,
        animationSpec = tween(BirdoMotion.Quick, easing = BirdoMotion.EaseStandard),
        label = "cardBorder",
    )
    val surfaceColor by animateColorAsState(
        targetValue = if (isSelected) palette.surfaceRaised else palette.surface,
        animationSpec = tween(BirdoMotion.Quick, easing = BirdoMotion.EaseStandard),
        label = "cardSurface",
    )
    // Springy pop when a server is starred.
    val starScale by animateFloatAsState(
        targetValue = if (isFavorite) 1.15f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "starPop",
    )
    val nameColor = if (isSelectable) palette.onSurface else palette.onSurfaceFaint

    val flag = remember(server.countryCode) { countryCodeToFlag(server.countryCode) }
    val location = remember(server.city, server.country) {
        if (server.city.isNotBlank()) "${server.city}, ${server.country}" else server.country
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ServerCardShape)
            .background(surfaceColor)
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, ServerCardShape)
            .clickable(enabled = isSelectable, role = Role.Button, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .then(if (!isSelectable) Modifier.alpha(0.5f) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Country flag badge
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(FlagShape)
                .background(palette.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = flag, fontSize = 20.sp)
        }

        Spacer(Modifier.width(12.dp))

        // Server info
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = server.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isLocked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Lock,
                        // Not decorative: the lock IS the reason the row is
                        // inert, so TalkBack has to say which plan unlocks it.
                        contentDescription = stringResource(R.string.cd_server_locked, server.minPlan),
                        tint = palette.onSurfaceFaint,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Text(
                text = location,
                fontSize = 12.sp,
                color = palette.onSurfaceMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        // Load indicator (solid colors only — no gradient brush)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$load%",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = loadCol,
            )
            Spacer(Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(LoadBarShape)
                    .background(BirdoWhite10),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(loadFraction)
                        .background(loadCol),
                )
            }
        }

        Spacer(Modifier.width(6.dp))

        // Favorite star (lightweight Box.clickable — no IconButton ripple stack).
        // 36dp visual, 48dp hit area: this sits in a fast-scrolling list, the
        // hardest place to hit a small target.
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(36.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onToggleFavorite()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (isFavorite) stringResource(R.string.cd_remove_favorite) else stringResource(R.string.cd_add_favorite),
                tint = if (isFavorite) BirdoYellowLight else palette.onSurfaceFaint,
                modifier = Modifier
                    .size(18.dp)
                    .scale(starScale),
            )
        }
    }
}

// ── Helpers ──

private fun loadColor(load: Int): Color = when {
    load < 50 -> BirdoGreenLight
    load < 80 -> BirdoYellowLight
    else -> BirdoRed
}
