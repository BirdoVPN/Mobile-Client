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
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.birdo.vpn.ui.components.BirdoTopBar
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.ui.viewmodel.AppInfo
import app.birdo.vpn.ui.viewmodel.SettingsUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onKillSwitchChange: (Boolean) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onShowIpInNotificationChange: (Boolean) -> Unit,
    onShowLocationInNotificationChange: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onSplitTunnelingChange: (Boolean) -> Unit,
    onToggleAppExclude: (String) -> Unit,
    onOpenSplitTunnelApps: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDeleteAccount: (String) -> Unit = {},
    isDeletingAccount: Boolean = false,
    deleteAccountError: String? = null,
    onClearDeleteError: () -> Unit = {},
    onBiometricLockChange: (Boolean) -> Unit = {},
    onThemeModeChange: (String) -> Unit = {},
    onOpenSubscription: () -> Unit = {},
    onOpenMultiHop: () -> Unit = {},
    onOpenPortForward: () -> Unit = {},
    onOpenSpeedTest: () -> Unit = {},
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            BirdoTopBar(
                title = stringResource(R.string.settings_title),
                subtitle = "App preferences & account",
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── Connection Section ───────────────────────────────
            item {
                SectionHeader(stringResource(R.string.settings_section_connection))
            }

            item {
                SettingsToggle(
                    icon = Icons.Default.Shield,
                    iconColor = BirdoGreen,
                    title = stringResource(R.string.settings_kill_switch),
                    description = stringResource(R.string.settings_kill_switch_desc),
                    checked = state.killSwitchEnabled,
                    onCheckedChange = onKillSwitchChange,
                    testTag = TestTags.KILL_SWITCH_TOGGLE,
                )
            }

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

            // ── VPN Protocol Section ─────────────────────────────
            item {
                SectionHeader(stringResource(R.string.settings_section_vpn))
            }

            item {
                SettingsLink(
                    icon = Icons.Default.Tune,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.settings_vpn_settings),
                    description = stringResource(R.string.settings_vpn_settings_desc),
                    onClick = onOpenVpnSettings,
                )
            }

            // ── Split Tunneling Section ──────────────────────────
            item {
                SectionHeader(stringResource(R.string.settings_section_split_tunnel))
            }

            item {
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

            // ── Advanced Section ─────────────────────────────────
            item {
                SectionHeader(stringResource(R.string.settings_section_advanced))
            }

            item {
                SettingsLink(
                    icon = Icons.AutoMirrored.Filled.AltRoute,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.settings_multi_hop),
                    description = stringResource(R.string.settings_multi_hop_desc),
                    onClick = onOpenMultiHop,
                )
            }

            item {
                SettingsLink(
                    icon = Icons.Default.SwapHoriz,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.settings_port_forward),
                    description = stringResource(R.string.settings_port_forward_desc),
                    onClick = onOpenPortForward,
                )
            }

            item {
                SettingsLink(
                    icon = Icons.Default.Speed,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.settings_speed_test),
                    description = stringResource(R.string.settings_speed_test_desc),
                    onClick = onOpenSpeedTest,
                )
            }

            // ── Security Section ──────────────────────────────────
            item {
                SectionHeader("Security")
            }

            item {
                SettingsToggle(
                    icon = Icons.Default.Fingerprint,
                    iconColor = BirdoGreen,
                    title = "Biometric Lock",
                    description = "Require fingerprint or PIN to open app",
                    checked = state.biometricLockEnabled,
                    onCheckedChange = onBiometricLockChange,
                )
            }

            // ── Appearance Section ───────────────────────────────
            item {
                SectionHeader("Appearance")
            }

            item {
                ThemeModeSelector(
                    currentMode = state.themeMode,
                    onModeSelected = onThemeModeChange,
                )
            }

            // ── About Section ────────────────────────────────────
            item {
                SectionHeader(stringResource(R.string.settings_section_about))
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
                        app.birdo.vpn.ui.components.AppIconMark(size = 44.dp, cornerRadius = 12.dp)
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

    // ── Delete Account Confirmation Dialog ───────────────────
    if (showDeleteDialog) {
        DeleteAccountDialog(
            isDeletingAccount = isDeletingAccount,
            error = deleteAccountError,
            onConfirm = { password -> onDeleteAccount(password) },
            onDismiss = {
                showDeleteDialog = false
                onClearDeleteError()
            },
        )
    }
}

/** Confirmation dialog requiring password re-entry before account deletion. */
@Composable
private fun DeleteAccountDialog(
    isDeletingAccount: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isDeletingAccount) onDismiss() },
        containerColor = BirdoSurface,
        titleContentColor = Color(0xFFEF4444),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.delete_dialog_title), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.delete_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BirdoWhite60,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.delete_dialog_password_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    enabled = !isDeletingAccount,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFEF4444),
                        cursorColor = BirdoWhite80,
                        focusedLabelColor = Color(0xFFEF4444),
                    ),
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF4444))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank() && !isDeletingAccount,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEF4444),
                    contentColor = Color.White,
                ),
            ) {
                if (isDeletingAccount) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isDeletingAccount) stringResource(R.string.delete_dialog_deleting) else stringResource(R.string.delete_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeletingAccount,
            ) {
                Text(stringResource(R.string.delete_dialog_cancel), color = BirdoWhite60)
            }
        },
    )
}

@Composable
private fun SectionHeader(title: String) {
    val palette = BirdoColors.current
    Text(
        text = title.uppercase(),
        color = palette.onSurfaceMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp),
    )
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
) {
    val palette = BirdoColors.current
    BirdoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        surface = palette.surface,
        border = app.birdo.vpn.ui.theme.BirdoBrand.GlassStrokeGradient,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingIconChip(icon = icon, iconColor = iconColor, contentDescription = title)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = palette.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = palette.onSurfaceMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
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
        surface = palette.surface,
        border = app.birdo.vpn.ui.theme.BirdoBrand.GlassStrokeGradient,
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
                Text(description, color = palette.onSurfaceMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
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
    BirdoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        surface = palette.surface,
        border = app.birdo.vpn.ui.theme.BirdoBrand.GlassStrokeGradient,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingIconChip(icon = Icons.Default.Palette, iconColor = palette.accent)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Theme", color = palette.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Dark, light, or follow system", color = palette.onSurfaceMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 1.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.surfaceRaised)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("dark" to "Dark", "light" to "Light", "system" to "System").forEach { (value, label) ->
                    val isSelected = currentMode == value
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(9.dp),
                        color = if (isSelected) palette.accent.copy(alpha = if (palette.isLight) 0.18f else 0.22f) else Color.Transparent,
                        border = if (isSelected) BorderStroke(1.dp, palette.accent.copy(alpha = 0.55f)) else null,
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onModeSelected(value) }
                                .padding(vertical = 9.dp),
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) palette.accent else palette.onSurfaceMuted,
                        )
                    }
                }
            }
        }
    }
}
