package app.birdo.vpn.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.birdo.vpn.R
import app.birdo.vpn.ui.components.BirdoTextField
import app.birdo.vpn.ui.components.BirdoTopBar
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.ui.viewmodel.AppInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(
    apps: List<AppInfo>,
    isLoading: Boolean,
    onToggleApp: (String) -> Unit,
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val excludedCount = apps.count { it.isExcluded }

    val palette = BirdoColors.current

    Scaffold(
        topBar = {
            BirdoTopBar(
                title = stringResource(R.string.split_tunnel_title),
                onBack = onBack,
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Info banner
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                color = palette.accentBg,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Info,
                        stringResource(R.string.cd_info),
                        tint = palette.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.split_tunnel_info, excludedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.accent,
                    )
                }
            }

            // Search bar
            BirdoTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = stringResource(R.string.split_tunnel_search_placeholder),
                leadingIcon = Icons.Default.Search,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = palette.accent, strokeWidth = 2.dp)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        AppItem(
                            app = app,
                            onToggle = { onToggleApp(app.packageName) },
                        )
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AppItem(
    app: AppInfo,
    onToggle: () -> Unit,
) {
    val palette = BirdoColors.current
    val bgColor by animateColorAsState(
        targetValue = if (app.isExcluded) palette.accentBg else palette.surface,
        label = "appBg",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = app.isExcluded, role = Role.Checkbox, onValueChange = { onToggle() }),
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // App icon
            if (app.icon != null) {
                Image(
                    bitmap = app.icon.toBitmap(48, 48).asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.surfaceRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Android, stringResource(R.string.cd_app), tint = palette.onSurfaceFaint, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.width(8.dp))

            // Bypass badge
            if (app.isExcluded) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = palette.accent.copy(alpha = 0.2f),
                ) {
                    Text(
                        stringResource(R.string.split_tunnel_bypass_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            // onCheckedChange = null: the row toggleable is the single labeled
            // accessibility target; a live Checkbox adds a second, unlabeled one.
            Checkbox(
                checked = app.isExcluded,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = palette.accent,
                    uncheckedColor = palette.onSurfaceFaint,
                    checkmarkColor = Color.White,
                ),
            )
        }
    }
}
