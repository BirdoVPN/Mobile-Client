package app.birdo.vpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.birdo.vpn.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.ui.screen.ServerCard
import app.birdo.vpn.ui.screen.ServerFilter
import app.birdo.vpn.ui.theme.BirdoColors

/**
 * Modal bottom sheet replacing the standalone Server tab. Triggered from the
 * Connect screen's bottom selector card. Filters/search/list reuse the same
 * design system as ServerListScreen so users get a consistent experience.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSelectorSheet(
    servers: List<VpnServer>,
    selectedServer: VpnServer?,
    favoriteServers: Set<String>,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    onSelectServer: (VpnServer) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = BirdoColors.current
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(ServerFilter.All) }

    val filtered = remember(servers, searchQuery, activeFilter, favoriteServers) {
        servers.asSequence()
            .filter { server ->
                val q = searchQuery.trim()
                val matchesSearch = q.isBlank() ||
                    server.name.contains(q, ignoreCase = true) ||
                    server.country.contains(q, ignoreCase = true) ||
                    server.city.contains(q, ignoreCase = true)
                val matchesFilter = when (activeFilter) {
                    ServerFilter.All -> true
                    ServerFilter.Favorites -> favoriteServers.contains(server.id)
                    ServerFilter.HighSpeed -> server.isHighSpeed
                    ServerFilter.PortForwarding -> server.isPortForwarding
                }
                matchesSearch && matchesFilter
            }
            .sortedWith(
                // Same ordering as ServerListScreen: locked nodes below usable.
                compareByDescending<VpnServer> { favoriteServers.contains(it.id) }
                    .thenBy { !it.accessible }
                    .thenBy { !it.isOnline }
                    .thenBy { it.load }
                    .thenBy { it.name }
            )
            .toList()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surfaceElevated,
        contentColor = palette.onSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.hairline),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 720.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Choose a server",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.onBackground,
                    )
                    Text(
                        text = "${filtered.size} of ${servers.size} servers",
                        fontSize = 12.sp,
                        color = palette.onSurfaceMuted,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        tint = palette.onSurfaceMuted,
                    )
                }
            }

            // Search
            BirdoTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = stringResource(R.string.sheet_search_placeholder),
                leadingIcon = Icons.Default.Search,
                trailingIcon = if (searchQuery.isNotBlank()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
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

            // Filter pills
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
                    // selectable, not clickable: TalkBack must be able to say
                    // WHICH filter is active, not just render it in accent.
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .selectable(
                                selected = isActive,
                                role = Role.Tab,
                                onClick = { activeFilter = filter },
                            ),
                        shape = RoundedCornerShape(20.dp),
                        color = if (isActive) palette.accent.copy(alpha = if (palette.isLight) 0.14f else 0.22f) else palette.surfaceRaised,
                        border = BorderStroke(
                            1.dp,
                            if (isActive) palette.accent.copy(alpha = 0.55f) else palette.hairlineSoft,
                        ),
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

            Spacer(Modifier.height(4.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = filtered, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        isSelected = server.id == selectedServer?.id,
                        isFavorite = favoriteServers.contains(server.id),
                        onSelect = {
                            onSelectServer(server)
                            onDismiss()
                        },
                        onToggleFavorite = { onToggleFavorite(server.id) },
                    )
                }
            }
        }
    }
}
