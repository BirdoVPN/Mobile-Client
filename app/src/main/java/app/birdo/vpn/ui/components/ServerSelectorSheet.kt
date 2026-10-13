package app.birdo.vpn.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.ui.screen.BirdoSnackbarHost
import app.birdo.vpn.ui.screen.ServerCard
import app.birdo.vpn.ui.screen.ServerEmptyState
import app.birdo.vpn.ui.screen.ServerFilter
import app.birdo.vpn.ui.screen.ServerSearchAndFilters
import app.birdo.vpn.ui.screen.filterAndSortServers
import app.birdo.vpn.ui.screen.rememberLockedServerUpsell
import app.birdo.vpn.ui.theme.BirdoBrand
import app.birdo.vpn.ui.theme.BirdoColors
import app.birdo.vpn.ui.theme.BirdoWhite05

/**
 * Modal bottom sheet replacing the standalone Server tab. Triggered from the
 * Connect screen's bottom selector card. Filters/search/list reuse the same
 * design system as ServerListScreen so users get a consistent experience.
 *
 * It is the MAIN way to pick a server, so it carries every state the full
 * screen has (P1-015): a refresh with a progress bar, and an empty state that
 * says why the list is empty and offers Retry when there is nothing to show.
 * Pull-to-refresh is left out on purpose: a downward drag here already
 * dismisses the sheet.
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
    isLoading: Boolean = false,
    onRefresh: () -> Unit = {},
    /** The node the live tunnel is on, marked in its row. */
    connectedServerId: String? = null,
    /** Where a locked row's upsell leads. */
    onViewPlans: () -> Unit = {},
) {
    val palette = BirdoColors.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var activeFilter by rememberSaveable { mutableStateOf(ServerFilter.All) }
    val snackbarHostState = remember { SnackbarHostState() }
    val showUpsell = rememberLockedServerUpsell(snackbarHostState, onViewPlans)

    val filtered = remember(servers, searchQuery, activeFilter, favoriteServers) {
        filterAndSortServers(servers, searchQuery, activeFilter, favoriteServers)
    }

    BirdoModalBottomSheet(
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
        Box {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp, max = 720.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.choose_server),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.onBackground,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.sheet_server_count,
                                servers.size,
                                filtered.size,
                                servers.size,
                            ),
                            fontSize = 12.sp,
                            color = palette.onSurfaceMuted,
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !isLoading) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh),
                            tint = palette.onSurfaceMuted,
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

                ServerSearchAndFilters(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    filter = activeFilter,
                    onFilterChange = { activeFilter = it },
                    favoriteCount = favoriteServers.size,
                )

                Spacer(Modifier.height(4.dp))
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = BirdoBrand.AccentSoft,
                        trackColor = BirdoWhite05,
                    )
                }

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
                            isConnected = server.id == connectedServerId,
                            onSelect = {
                                onSelectServer(server)
                                onDismiss()
                            },
                            onToggleFavorite = { onToggleFavorite(server.id) },
                            onLockedTap = { showUpsell(server) },
                        )
                    }
                    if (filtered.isEmpty() && !isLoading) {
                        item {
                            ServerEmptyState(
                                hasServers = servers.isNotEmpty(),
                                query = searchQuery,
                                filter = activeFilter,
                                onRetry = onRefresh,
                            )
                        }
                    }
                }
            }
            BirdoSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        }
    }
}
