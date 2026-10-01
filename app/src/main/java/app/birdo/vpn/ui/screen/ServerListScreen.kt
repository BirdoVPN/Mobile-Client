package app.birdo.vpn.ui.screen

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.ui.components.*
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.utils.countryCodeToFlag
import kotlinx.coroutines.launch

/**
 * The capability filters. The old "Streaming" / "P2P" pills were removed: both
 * flags were cosmetic and false on every node, so the pills matched NOTHING in
 * production. They are replaced by the two capabilities Birdo actually ships —
 * a low-load / high-throughput node, and inbound port forwarding. Birdo does
 * not offer streaming-unblocking or P2P servers, so a pill must not imply it
 * does.
 */
enum class ServerFilter(@param:StringRes val labelRes: Int, val icon: String) {
    All(R.string.filter_all, ""),
    Favorites(R.string.filter_favorites, "★"),
    HighSpeed(R.string.filter_high_speed, "⚡"),
    PortForwarding(R.string.filter_port_forwarding, "⇄"),
}

/**
 * The picker's list: matches for [query] and [filter], favourites first, then
 * usable before locked, online before offline, then by load and name. Locked
 * nodes are shown, not hidden, so the user can see what a plan unlocks.
 *
 * ONE copy for the sheet and the full screen, which used to carry two.
 */
internal fun filterAndSortServers(
    servers: List<VpnServer>,
    query: String,
    filter: ServerFilter,
    favorites: Set<String>,
): List<VpnServer> {
    val q = query.trim()
    return servers
        .filter { server ->
            val matchesSearch = q.isBlank() ||
                server.name.contains(q, ignoreCase = true) ||
                server.country.contains(q, ignoreCase = true) ||
                server.city.contains(q, ignoreCase = true)
            val matchesFilter = when (filter) {
                ServerFilter.All -> true
                ServerFilter.Favorites -> favorites.contains(server.id)
                ServerFilter.HighSpeed -> server.isHighSpeed
                ServerFilter.PortForwarding -> server.isPortForwarding
            }
            matchesSearch && matchesFilter
        }
        .sortedWith(
            compareByDescending<VpnServer> { favorites.contains(it.id) }
                .thenBy { !it.accessible }
                .thenBy { !it.isOnline }
                .thenBy { it.load }
                .thenBy { it.name },
        )
}

/**
 * Full-screen server list, reached from Connect when the list is empty (the
 * usual path is [ServerSelectorSheet]). Same rows, filters and empty states as
 * the sheet, plus pull-to-refresh (A2-023).
 */
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
    // A tap here can be REFUSED (selectServer declines to downgrade a live
    // Multi-Hop route). Without somewhere to say so, the refusal was a tap that
    // changed nothing and explained nothing on the app's primary switching
    // surface -- indistinguishable from the screen being broken.
    errorMessage: String? = null,
    /** The node the live tunnel is on, marked in its row. */
    connectedServerId: String? = null,
    /** Where a locked row's upsell leads. */
    onViewPlans: () -> Unit = {},
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activeFilter by rememberSaveable { mutableStateOf(ServerFilter.All) }
    val snackbarHostState = remember { SnackbarHostState() }
    val showUpsell = rememberLockedServerUpsell(snackbarHostState, onViewPlans)

    val filteredServers = remember(servers, searchQuery, activeFilter, favoriteServers) {
        filterAndSortServers(servers, searchQuery, activeFilter, favoriteServers)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            BirdoTopBar(
                title = stringResource(R.string.choose_server),
                subtitle = pluralStringResource(R.plurals.servers_count, filteredServers.size, filteredServers.size),
                onBack = onBack,
                actions = {
                    BirdoIconAction(
                        icon = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh),
                        onClick = onRefresh,
                    )
                },
            )

            // ── Refusal / error banner ──
            // Assertive live region: an alert that appears silently is an alert a
            // TalkBack user never receives, and silence is the defect this exists
            // to fix. Mirrors HomeScreen's HomeBanner, which is private to that file.
            if (errorMessage != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                    shape = RoundedCornerShape(14.dp),
                    color = BirdoRedBg,
                    border = BorderStroke(1.dp, BirdoRed.copy(alpha = 0.3f)),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = BirdoRed,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = errorMessage,
                            color = BirdoRed,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            ServerSearchAndFilters(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                filter = activeFilter,
                onFilterChange = { activeFilter = it },
                favoriteCount = favoriteServers.size,
            )

            // Thin progress bar only for refresh-with-content; a first load gets
            // ghost rows below instead of a blank void.
            if (isLoading && servers.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = BirdoBrand.AccentSoft,
                    trackColor = BirdoWhite05,
                )
            }

            BirdoPullToRefresh(
                isLoading = isLoading,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
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

                    items(items = filteredServers, key = { it.id }) { server ->
                        ServerCard(
                            server = server,
                            isSelected = server.id == selectedServer?.id,
                            isFavorite = favoriteServers.contains(server.id),
                            isConnected = server.id == connectedServerId,
                            onSelect = { onSelectServer(server) },
                            onToggleFavorite = { onToggleFavorite(server.id) },
                            onLockedTap = { showUpsell(server) },
                        )
                    }

                    if (filteredServers.isEmpty() && !isLoading) {
                        item {
                            ServerEmptyState(
                                hasServers = servers.isNotEmpty(),
                                query = searchQuery,
                                filter = activeFilter,
                                onRetry = onRefresh,
                                modifier = Modifier.padding(top = 32.dp),
                            )
                        }
                    }
                }
            }
        }

        BirdoSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

/** Search field + filter pills, shared by this screen and [ServerSelectorSheet]. */
@Composable
internal fun ServerSearchAndFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: ServerFilter,
    onFilterChange: (ServerFilter) -> Unit,
    favoriteCount: Int,
) {
    val palette = BirdoColors.current
    BirdoTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.server_search_placeholder),
        leadingIcon = Icons.Default.Search,
        trailingIcon = if (query.isNotBlank()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_clear),
                        tint = palette.onSurfaceFaint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else null,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ServerFilter.entries) { option ->
            val isActive = option == filter
            val label = stringResource(option.labelRes)
            val text = buildString {
                if (option.icon.isNotEmpty()) {
                    append(option.icon); append(" ")
                }
                append(label)
                if (option == ServerFilter.Favorites && favoriteCount > 0) append(" ($favoriteCount)")
            }
            // selectable (not clickable): TalkBack must announce WHICH filter
            // is active — colour alone doesn't reach a screen reader.
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .selectable(
                        selected = isActive,
                        role = Role.Tab,
                        onClick = { onFilterChange(option) },
                    ),
                shape = RoundedCornerShape(20.dp),
                color = if (isActive) palette.accent.copy(alpha = if (palette.isLight) 0.18f else 0.22f) else palette.surfaceRaised,
                border = BorderStroke(1.dp, if (isActive) palette.accent.copy(alpha = 0.55f) else palette.hairlineSoft),
            ) {
                Text(
                    text = text,
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isActive) palette.accent else palette.onSurfaceMuted,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * Why the list is empty and what to do about it. The sheet had none of this,
 * so an empty filter or a failed load left the main picker blank (P1-015).
 */
@Composable
internal fun ServerEmptyState(
    hasServers: Boolean,
    query: String,
    filter: ServerFilter,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BirdoEmptyState(
        icon = Icons.Default.Dns,
        title = when {
            filter == ServerFilter.Favorites -> stringResource(R.string.servers_no_favorites)
            query.isNotBlank() -> stringResource(R.string.servers_no_match, query.trim())
            filter == ServerFilter.HighSpeed -> stringResource(R.string.servers_no_high_speed)
            filter == ServerFilter.PortForwarding -> stringResource(R.string.servers_no_port_forwarding)
            else -> stringResource(R.string.no_servers)
        },
        description = when {
            filter == ServerFilter.Favorites -> stringResource(R.string.servers_favorites_hint)
            !hasServers -> stringResource(R.string.servers_empty_hint)
            else -> null
        },
        action = if (!hasServers) {
            {
                BirdoButton(
                    text = stringResource(R.string.retry),
                    onClick = onRetry,
                    variant = BirdoButtonVariant.Secondary,
                    icon = Icons.Default.Refresh,
                )
            }
        } else null,
        modifier = modifier,
    )
}

/**
 * A tap on a locked row explains itself and offers the way in (P1-014).
 * It used to do nothing at all: the row was simply inert, and only TalkBack
 * users were ever told why.
 */
@Composable
internal fun rememberLockedServerUpsell(
    hostState: SnackbarHostState,
    onViewPlans: () -> Unit,
): (VpnServer) -> Unit {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // Resolved here, in composition: formatting inside the click lambda would
    // need Context.getString, which does not follow a configuration change.
    val template = stringResource(R.string.server_locked_upsell)
    val names = PlanNames(
        free = stringResource(R.string.plan_name_free),
        operative = stringResource(R.string.plan_name_operative),
        sovereign = stringResource(R.string.plan_name_sovereign),
    )
    val action = stringResource(R.string.view_plans)
    return { server ->
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        scope.launch {
            hostState.currentSnackbarData?.dismiss()
            val result = hostState.showSnackbar(
                message = template.format(names.of(server.minPlan)),
                actionLabel = action,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onViewPlans()
        }
    }
}

/** Plan names as prose uses them: Title Case, never the SLUG (canonical vocabulary). */
internal data class PlanNames(val free: String, val operative: String, val sovereign: String) {
    fun of(slug: String?): String = when (slug?.uppercase()) {
        "SOVEREIGN" -> sovereign
        "OPERATIVE" -> operative
        else -> free
    }
}

/** The brand-styled snackbar the pickers share (the stock inverse-surface slab is alien on dark glass). */
@Composable
internal fun BirdoSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val palette = BirdoColors.current
    SnackbarHost(hostState = hostState, modifier = modifier.padding(16.dp)) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = palette.surfaceElevated,
            contentColor = palette.onSurface,
            actionColor = palette.accent,
            shape = RoundedCornerShape(14.dp),
        )
    }
}

// ── Server Card ─────────────────────────────────────────────────────────────

private val ServerCardShape = RoundedCornerShape(14.dp)
private val FlagShape = RoundedCornerShape(10.dp)

/**
 * Flat, allocation-free server card optimised for fast scrolling.
 *
 * Previous version wrapped the row in [BirdoCard] (extra Box layers) and
 * allocated a new `Brush.linearGradient` per recomposition for the card
 * border. With 100+ rows that dominated frame cost.
 *
 * This version uses solid colors only, hoists shapes to file-level vals, and
 * remembers all per-row derived state keyed on inputs that actually change.
 *
 * What a row says (P1-014, iOS ServerListView's ServerRow): which plan unlocks
 * a locked node (a chip, and a tap that explains it), that a node is Offline
 * (in words, not only by fading), its capabilities, and which node the live
 * tunnel is on. TalkBack gets the selection as a state, not a border colour.
 */
@Composable
internal fun ServerCard(
    server: VpnServer,
    isSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLockedTap: () -> Unit = {},
    isConnected: Boolean = false,
) {
    val palette = BirdoColors.current
    val haptics = LocalHapticFeedback.current
    val isOnline = server.isOnline
    // `accessible` is computed server-side from the user's plan vs the node's
    // minPlan. A locked node is shown (so the user can see what a plan buys)
    // but cannot be picked — offering it would only earn a server-side refusal.
    val isLocked = !server.accessible
    val isSelectable = isOnline && !isLocked
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
    val connectedDescription = stringResource(R.string.cd_server_connected)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ServerCardShape)
            .background(surfaceColor)
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, ServerCardShape)
            // A locked row stays tappable: the tap explains the lock. An
            // offline one stays inert — there is nothing to offer.
            .clickable(
                enabled = isSelectable || isLocked,
                role = Role.Button,
                onClick = if (isLocked) onLockedTap else onSelect,
            )
            .semantics { selected = isSelected }
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Everything but the star is dimmed on an unusable node; the star stays
        // full strength because favouriting any node is allowed.
        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (!isSelectable) Modifier.alpha(0.5f) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Country flag badge. The flag is a picture drawn with an emoji, so
            // it is sized in dp like an icon: in sp the font scale grew it out
            // of its 40 dp tile, which clipped it (A2-024). The server name
            // beside it is the text that scales.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(FlagShape)
                    .background(palette.surfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = flag, fontSize = with(LocalDensity.current) { 20.dp.toSp() })
            }

            Spacer(Modifier.width(12.dp))

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
                    if (isConnected) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(BirdoGreen)
                                .semantics { contentDescription = connectedDescription },
                        )
                    }
                    if (isLocked) {
                        Spacer(Modifier.width(6.dp))
                        LockedPlanChip(server.minPlan)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = location,
                        fontSize = 12.sp,
                        color = palette.onSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (server.isHighSpeed) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = stringResource(R.string.cd_server_high_speed),
                            tint = palette.onSurfaceFaint,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    if (server.isPortForwarding) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = stringResource(R.string.cd_server_port_forwarding),
                            tint = palette.onSurfaceFaint,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }

            // Server load is never shown (as on iOS); a node that is down is
            // the only thing this column has left to say.
            if (!isOnline) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.server_offline),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.onSurfaceMuted,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // Favorite star: a TOGGLE, so TalkBack reads its state ("Favorite,
        // checked") instead of an action whose meaning flips with the state.
        // 36dp visual, 48dp hit area: this sits in a fast-scrolling list, the
        // hardest place to hit a small target.
        val favoriteLabel = stringResource(R.string.cd_favorite)
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(36.dp)
                .clip(CircleShape)
                .toggleable(value = isFavorite, role = Role.Checkbox) {
                    haptics.performHapticFeedback(
                        if (isFavorite) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                    )
                    onToggleFavorite()
                }
                .semantics { contentDescription = favoriteLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = null,
                tint = if (isFavorite) BirdoYellowLight else palette.onSurfaceFaint,
                modifier = Modifier
                    .size(18.dp)
                    .scale(starScale),
            )
        }
    }
}

/**
 * The plan a locked node needs. The SLUG is the chip's text, the one place the
 * canonical vocabulary allows it; TalkBack hears the sentence.
 */
@Composable
private fun LockedPlanChip(minPlan: String) {
    val palette = BirdoColors.current
    val planName = PlanNames(
        free = stringResource(R.string.plan_name_free),
        operative = stringResource(R.string.plan_name_operative),
        sovereign = stringResource(R.string.plan_name_sovereign),
    ).of(minPlan)
    val description = stringResource(R.string.cd_server_locked, planName)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.surfaceRaised)
            .border(1.dp, palette.hairline, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = palette.onSurfaceMuted, modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            text = minPlan.uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = palette.onSurfaceMuted,
            maxLines = 1,
        )
    }
}
