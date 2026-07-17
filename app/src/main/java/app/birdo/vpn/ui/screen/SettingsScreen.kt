package app.birdo.vpn.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import app.birdo.vpn.ui.components.BirdoCard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.R
import app.birdo.vpn.ui.TestTags
import app.birdo.vpn.ui.components.BirdoSectionHeader
import app.birdo.vpn.ui.components.BirdoSegmentedControl
import app.birdo.vpn.ui.components.BirdoTopBar
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.ui.viewmodel.AppInfo
import app.birdo.vpn.ui.viewmodel.SettingsUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAutoConnectChange: (Boolean) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onShowIpInNotificationChange: (Boolean) -> Unit,
    onShowLocationInNotificationChange: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSplitTunnelingChange: (Boolean) -> Unit,
    onOpenSplitTunnelApps: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onBiometricLockChange: (Boolean) -> Unit = {},
    onThemeModeChange: (String) -> Unit = {},
) {
    Scaffold(
        topBar = {
            BirdoTopBar(
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Appearance ───────────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.settings_section_appearance)) }

            item {
                ThemeModeSelector(
                    currentMode = state.themeMode,
                    onModeSelected = onThemeModeChange,
                )
            }

            // ── Security ─────────────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.settings_section_security)) }

            item {
                SettingsToggle(
                    icon = Icons.Default.Fingerprint,
                    iconColor = BirdoGreen,
                    title = stringResource(R.string.settings_biometric),
                    description = stringResource(R.string.settings_biometric_desc),
                    checked = state.biometricLockEnabled,
                    onCheckedChange = onBiometricLockChange,
                )
            }

            // ── Connection ───────────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.settings_section_connection)) }

            item {
                SettingsToggle(
                    icon = Icons.Default.Wifi,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.settings_auto_connect),
                    description = stringResource(R.string.settings_auto_connect_desc),
                    checked = state.autoConnect,
                    onCheckedChange = onAutoConnectChange,
                    testTag = TestTags.AUTO_CONNECT_TOGGLE,
                )
            }

            item {
                SettingsToggle(
                    icon = Icons.Default.Notifications,
                    iconColor = BirdoYellow,
                    title = stringResource(R.string.settings_notifications),
                    description = stringResource(R.string.settings_notifications_desc),
                    checked = state.notificationsEnabled,
                    onCheckedChange = onNotificationsChange,
                    testTag = TestTags.NOTIFICATIONS_TOGGLE,
                )
            }

            if (state.notificationsEnabled) {
                item {
                    SettingsToggle(
                        icon = Icons.Default.Language,
                        iconColor = BirdoWhite60,
                        title = stringResource(R.string.settings_notif_show_ip),
                        description = stringResource(R.string.settings_notif_show_ip_desc),
                        checked = state.showIpInNotification,
                        onCheckedChange = onShowIpInNotificationChange,
                    )
                }

                item {
                    SettingsToggle(
                        icon = Icons.Default.LocationOn,
                        iconColor = BirdoWhite60,
                        title = stringResource(R.string.settings_notif_show_location),
                        description = stringResource(R.string.settings_notif_show_location_desc),
                        checked = state.showLocationInNotification,
                        onCheckedChange = onShowLocationInNotificationChange,
                    )
                }
            }

            item {
                SettingsLink(
                    icon = Icons.Default.NotificationsActive,
                    iconColor = BirdoWhite60,
                    title = stringResource(R.string.settings_notif_system),
                    description = stringResource(R.string.settings_notif_system_desc),
                    onClick = onOpenNotificationSettings,
                    trailing = Icons.AutoMirrored.Filled.OpenInNew,
                )
            }

            // ── VPN ──────────────────────────────────────────────
            // Unified group: protocol, split tunneling. Kill switch & port-forward live in VPN Settings.
            item { BirdoSectionHeader(stringResource(R.string.settings_section_vpn)) }

            item {
                SettingsLink(
                    icon = Icons.Default.Tune,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.settings_vpn_settings),
                    description = stringResource(R.string.settings_vpn_settings_desc),
                    onClick = onOpenVpnSettings,
                )
            }

            item {
                // Split tunneling is available on every tier (not gated).
                SettingsToggle(
                    icon = Icons.AutoMirrored.Filled.CallSplit,
                    iconColor = BirdoWhite60,
                    title = stringResource(R.string.settings_split_tunnel),
                    description = stringResource(R.string.settings_split_tunnel_desc),
                    checked = state.splitTunnelingEnabled,
                    onCheckedChange = onSplitTunnelingChange,
                )
            }

            if (state.splitTunnelingEnabled) {
                item {
                    SettingsLink(
                        icon = Icons.Default.Apps,
                        iconColor = BirdoWhite60,
                        title = stringResource(R.string.settings_manage_apps),
                        description = stringResource(R.string.settings_apps_bypassing, state.splitTunnelApps.size),
                        onClick = onOpenSplitTunnelApps,
                    )
                }
            }

            // ── About Section ────────────────────────────────────
            item {
                BirdoSectionHeader(stringResource(R.string.settings_section_about))
            }

            item {
                val palette = BirdoColors.current
                BirdoCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    surface = palette.surface,
                    border = app.birdo.vpn.ui.theme.BirdoBrand.GlassStrokeGradient,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        app.birdo.vpn.ui.components.AppIconMark(size = 44.dp, cornerRadius = 12.dp, square = true)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.app_name), color = palette.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.settings_version, BuildConfig.APP_VERSION), color = palette.onSurfaceMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 1.dp))
                        }
                        Icon(Icons.Default.Verified, null, tint = palette.accent, modifier = Modifier.size(20.dp))
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SettingIconChip(
    icon: ImageVector,
    iconColor: Color,
    contentDescription: String? = null,
) {
    val palette = BirdoColors.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surfaceRaised)
            .border(1.dp, palette.hairlineSoft, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = iconColor, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SettingsToggle(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null,
    // When `locked`, the switch is non-interactive and the whole row taps
    // through to `onLockedTap` (the upgrade flow) — mirrors VpnToggle so premium
    // gating looks identical wherever it appears.
    locked: Boolean = false,
    onLockedTap: () -> Unit = {},
) {
    val palette = BirdoColors.current
    BirdoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        val rowModifier = if (locked) {
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onLockedTap)
        } else {
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
        }
        Row(
            modifier = rowModifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingIconChip(icon = icon, iconColor = if (locked) palette.onSurfaceFaint else iconColor, contentDescription = title)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = palette.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = palette.onSurfaceMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
            Spacer(Modifier.width(8.dp))
            if (locked) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = stringResource(R.string.cd_locked_feature),
                    tint = palette.onSurfaceFaint,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                // onCheckedChange = null: the row's toggleable owns the interaction,
                // so TalkBack sees ONE labeled switch instead of a labeled row plus
                // a second, unlabeled "On, switch" stop.
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    modifier = testTag?.let { Modifier.testTag(it) } ?: Modifier,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = palette.accent,
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = palette.onSurfaceMuted,
                        uncheckedTrackColor = palette.surfaceRaised,
                        uncheckedBorderColor = palette.hairlineSoft,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SettingsLink(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    onClick: () -> Unit,
    trailing: ImageVector = Icons.Default.ChevronRight,
) {
    val palette = BirdoColors.current
    BirdoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingIconChip(icon = icon, iconColor = iconColor, contentDescription = title)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = palette.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = palette.onSurfaceMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
            Spacer(Modifier.width(8.dp))
            Icon(trailing, stringResource(R.string.cd_open), tint = palette.onSurfaceFaint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ThemeModeSelector(
    currentMode: String,
    onModeSelected: (String) -> Unit,
) {
    val palette = BirdoColors.current
    val haptics = LocalHapticFeedback.current
    BirdoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingIconChip(icon = Icons.Default.Palette, iconColor = palette.accent)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.settings_theme), color = palette.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settings_theme_desc), color = palette.onSurfaceMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 1.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            BirdoSegmentedControl(
                options = listOf(
                    "dark" to stringResource(R.string.settings_theme_dark),
                    "light" to stringResource(R.string.settings_theme_light),
                    "system" to stringResource(R.string.settings_theme_system),
                ),
                selectedKey = currentMode,
                onSelect = { mode ->
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onModeSelected(mode)
                },
            )
        }
    }
}
