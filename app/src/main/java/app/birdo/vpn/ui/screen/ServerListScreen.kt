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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.data.model.VpnServer
import androidx.compose.ui.semantics.Role
import app.birdo.vpn.ui.components.*
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.utils.countryCodeToFlag

/**
 * Server list screen — matches the Windows client design:
 * - Search input with glass styling
 * - Filter pills: All | ★ Favorites | 🎬 Streaming | ↓ P2P
 * - Server cards with country flag, name, load bar, favorite star
 * - White-based color scheme (no purple accents)
 * - Selected server: subtle white ring (ring-white/20)
 */
enum class ServerFilter(val label: String, val icon: String) {
    All("All", ""),
    Favorites("Favorites", "★"),
    Streaming("Streaming", "🎬"),
    P2P("P2P", "⬇"),
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
                    ServerFilter.Streaming -> server.isStreaming
                    ServerFilter.P2P -> server.isP2p
                }

                matchesSearch && matchesFilter
            }
            .sortedWith(
                compareByDescending<VpnServer> { favoriteServers.contains(it.id) }
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
                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
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
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ServerFilter.entries) { filter ->
                val isActive = filter == activeFilter
                val favCount = if (filter == ServerFilter.Favorites) favoriteServers.size else null
                val text = buildString {
                    if (filter.icon.isNotEmpty()) {
                        append(filter.icon); append(" ")
                    }
                    append(filter.label)
                    if (favCount != null && favCount > 0) append(" ($favCount)")
                }
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(role = Role.Tab) { activeFilter = filter },
                    shape = RoundedCornerShape(20.dp),
                    color = if (isActive) BirdoBrand.Surface3 else BirdoBrand.Surface1,
                    border = BorderStroke(1.dp, if (isActive) BirdoBrand.PurpleSoft.copy(alpha = 0.5f) else BirdoBrand.HairlineSoft),
                ) {
                    Text(
                        text = text,
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isActive) Color.White else BirdoWhite60,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // ── Loading indicator ──
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = BirdoBrand.PurpleSoft,
                trackColor = BirdoWhite05,
            )
        }

        // ── Server list ──
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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
                            else -> stringResource(R.string.no_servers)
                        },
                        description = when {
                            activeFilter == ServerFilter.Favorites -> stringResource(R.string.servers_favorites_hint)
                            servers.isEmpty() -> "Pull down to refresh or tap retry."
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

@Composable
private fun ServerCard(
    server: VpnServer,
    isSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val borderBrush = if (isSelected)
        androidx.compose.ui.graphics.Brush.linearGradient(listOf(BirdoBrand.PurpleSoft, BirdoBrand.Pink))
    else BirdoBrand.GlassStrokeGradient

    BirdoCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = server.isOnline, role = Role.Button) { onSelect() },
        cornerRadius = 14.dp,
        surface = if (isSelected) BirdoBrand.Surface2 else BirdoBrand.Surface1,
        border = borderBrush,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!server.isOnline) Modifier.alpha(0.5f) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Country flag badge
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BirdoWhite05)
                    .border(1.dp, BirdoBrand.HairlineSoft, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = countryCodeToFlag(server.countryCode),
                    fontSize = 20.sp,
                )
            }

            Spacer(Modifier.width(12.dp))

            // Server info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = server.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (server.isOnline) Color.White else BirdoWhite40,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (server.isPremium) Text("⚡", fontSize = 11.sp)
                    if (server.isStreaming) Text("🎬", fontSize = 11.sp)
                    if (server.isP2p) Text("⬇", fontSize = 11.sp)
                }
                Text(
                    text = if (server.city.isNotBlank()) "${server.city}, ${server.country}" else server.country,
                    fontSize = 12.sp,
                    color = BirdoWhite60,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            // Load indicator
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "${server.load}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = loadColor(server.load),
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(BirdoWhite10),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(server.load / 100f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    listOf(loadColor(server.load).copy(alpha = 0.6f), loadColor(server.load))
                                )
                            ),
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            // Favorite star
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isFavorite) stringResource(R.string.cd_remove_favorite) else stringResource(R.string.cd_add_favorite),
                    tint = if (isFavorite) BirdoYellowLight else BirdoWhite40,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ── Helpers ──

private fun loadColor(load: Int): Color = when {
    load < 50 -> BirdoGreenLight
    load < 80 -> BirdoYellowLight
    else -> BirdoRed
}
