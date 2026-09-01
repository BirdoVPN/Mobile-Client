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
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.birdo.vpn.ui.components.BirdoTextField
import app.birdo.vpn.ui.components.BirdoTopBar
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.ui.viewmodel.AppInfo
import app.birdo.vpn.ui.viewmodel.SettingsUiState
import app.birdo.vpn.utils.InputValidator

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
    onCustomDnsEnabledChange: (Boolean) -> Unit,
    onCustomDnsPrimaryChange: (String) -> Unit,
    onCustomDnsSecondaryChange: (String) -> Unit,
    onOpenPortForward: () -> Unit,
    onQuantumProtectionChange: (Boolean) -> Unit,
    onKillSwitchChange: (Boolean) -> Unit,
    onBiometricLockChange: (Boolean) -> Unit = {},
    onThemeModeChange: (String) -> Unit = {},
    // ── Plan gating ──────────────────────────────────────────────
    // A locked row does not toggle; it taps through to the upgrade flow,
    // the same affordance Stealth Mode uses on the VPN Settings sub-page.
    customDnsUnlocked: Boolean = true,
    portForwardUnlocked: Boolean = true,
    quantumUnlocked: Boolean = true,
    onUpgradeRequired: (feature: String) -> Unit = {},
) {
    // Turning the kill switch OFF weakens leak protection, so it is gated behind
    // an explicit confirmation dialog (enabling it stays immediate).
    var showKillSwitchDisableDialog by rememberSaveable { mutableStateOf(false) }

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
                    checked = state.biometricLockEnabled,
                    onCheckedChange = onBiometricLockChange,
                )
            }

            item {
                SettingsToggle(
                    icon = Icons.Default.Lock,
                    iconColor = BirdoAccent,
                    title = stringResource(R.string.vpn_settings_quantum_title),
                    checked = state.quantumProtectionEnabled && quantumUnlocked,
                    onCheckedChange = onQuantumProtectionChange,
                    locked = !quantumUnlocked,
                    onLockedTap = { onUpgradeRequired("Quantum Protection") },
                )
            }

            item {
                // Kill switch defaults ON (the safe choice) but is user-toggleable.
                // Enabling is immediate; disabling is gated behind a confirmation
                // dialog because it trades leak-proofing for connectivity — if the
                // tunnel drops, apps fall back to the open internet and can briefly
                // expose the real IP. The service gates every activateKillSwitch() on
                // the value behind this toggle, so turning it off genuinely lets
                // traffic through.
                SettingsToggle(
                    icon = Icons.Default.Shield,
                    iconColor = BirdoGreen,
                    title = stringResource(R.string.settings_kill_switch),
                    checked = state.killSwitchEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            onKillSwitchChange(true)
                        } else {
                            // Leave the toggle ON (state unchanged) until the user
                            // confirms in the warning dialog below.
                            showKillSwitchDisableDialog = true
                        }
                    },
                    testTag = TestTags.KILL_SWITCH_TOGGLE,
                )
            }

            // ── Connection ───────────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.settings_section_connection)) }

            item {
                SettingsToggle(
                    icon = Icons.Default.Wifi,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.settings_auto_connect),
                    checked = state.autoConnect,
                    onCheckedChange = onAutoConnectChange,
                    testTag = TestTags.AUTO_CONNECT_TOGGLE,
                )
            }

            item {
                SettingsLink(
                    icon = Icons.Default.NotificationsActive,
                    iconColor = BirdoWhite60,
                    title = stringResource(R.string.settings_notif_system),
                    onClick = onOpenNotificationSettings,
                    trailing = Icons.AutoMirrored.Filled.OpenInNew,
                )
            }

            // ── Display ──────────────────────────────────────────
            // What the app shows about a connection. The two detail rows
            // describe the notification's contents, so they appear only while
            // the notification itself is on.
            item { BirdoSectionHeader(stringResource(R.string.settings_section_display)) }

            item {
                SettingsToggle(
                    icon = Icons.Default.Notifications,
                    iconColor = BirdoYellow,
                    title = stringResource(R.string.settings_notifications),
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
                        checked = state.showIpInNotification,
                        onCheckedChange = onShowIpInNotificationChange,
                    )
                }

                item {
                    SettingsToggle(
                        icon = Icons.Default.LocationOn,
                        iconColor = BirdoWhite60,
                        title = stringResource(R.string.settings_notif_show_location),
                        checked = state.showLocationInNotification,
                        onCheckedChange = onShowLocationInNotificationChange,
                    )
                }
            }

            // ── VPN ──────────────────────────────────────────────
            // Unified group: the VPN Settings sub-page, plus the two controls
            // promoted out of it (custom DNS, port forwarding) and split
            // tunneling, which has always lived here.
            item { BirdoSectionHeader(stringResource(R.string.settings_section_vpn)) }

            item {
                SettingsLink(
                    icon = Icons.Default.Tune,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.settings_vpn_settings),
                    onClick = onOpenVpnSettings,
                )
            }

            item {
                SettingsToggle(
                    icon = Icons.Default.Dns,
                    iconColor = BirdoAccent,
                    title = stringResource(R.string.vpn_settings_custom_dns),
                    checked = state.customDnsEnabled && customDnsUnlocked,
                    onCheckedChange = onCustomDnsEnabledChange,
                    locked = !customDnsUnlocked,
                    onLockedTap = { onUpgradeRequired("Custom DNS") },
                )
            }

            if (state.customDnsEnabled && customDnsUnlocked) {
                item {
                    BirdoTextField(
                        value = state.customDnsPrimary,
                        onValueChange = onCustomDnsPrimaryChange,
                        label = stringResource(R.string.vpn_settings_dns_primary),
                        placeholder = stringResource(R.string.vpn_settings_dns_primary_hint),
                        keyboardType = KeyboardType.Decimal,
                        isError = state.customDnsPrimary.isNotBlank() &&
                            !InputValidator.isValidDnsAddress(state.customDnsPrimary),
                    )
                }
                item {
                    BirdoTextField(
                        value = state.customDnsSecondary,
                        onValueChange = onCustomDnsSecondaryChange,
                        label = stringResource(R.string.vpn_settings_dns_secondary),
                        placeholder = stringResource(R.string.vpn_settings_dns_secondary_hint),
                        keyboardType = KeyboardType.Decimal,
                        isError = state.customDnsSecondary.isNotBlank() &&
                            !InputValidator.isValidDnsAddress(state.customDnsSecondary),
                    )
                }
            }

            item {
                SettingsLink(
                    icon = Icons.Default.SwapHoriz,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.settings_port_forward),
                    onClick = if (portForwardUnlocked) onOpenPortForward
                        else { { onUpgradeRequired("Port Forwarding") } },
                    locked = !portForwardUnlocked,
                )
            }

            item {
                // Split tunneling is available on every tier (not gated).
                SettingsToggle(
                    icon = Icons.AutoMirrored.Filled.CallSplit,
                    iconColor = BirdoWhite60,
                    title = stringResource(R.string.settings_split_tunnel),
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

        if (showKillSwitchDisableDialog) {
            val palette = BirdoColors.current
            AlertDialog(
                onDismissRequest = { showKillSwitchDisableDialog = false },
                containerColor = palette.surfaceElevated,
                titleContentColor = palette.onSurface,
                textContentColor = palette.onSurfaceMuted,
                title = {
                    Text(
                        stringResource(R.string.settings_kill_switch_disable_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = { Text(stringResource(R.string.settings_kill_switch_disable_msg)) },
                confirmButton = {
                    TextButton(onClick = {
                        onKillSwitchChange(false)
                        showKillSwitchDisableDialog = false
                    }) {
                        Text(
                            stringResource(R.string.settings_kill_switch_disable_confirm),
                            color = BirdoRed,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showKillSwitchDisableDialog = false }) {
                        Text(
                            stringResource(R.string.delete_dialog_cancel),
                            color = palette.onSurfaceMuted,
                        )
                    }
                },
            )
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
    // Optional explanatory subtitle. Rendered only when present — most rows
    // carry a self-explanatory title and no description.
    description: String? = null,
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
                if (description != null) {
                    Text(description, color = palette.onSurfaceMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                }
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
    // Optional subtitle — rendered only when present (e.g. a dynamic count).
    description: String? = null,
    onClick: () -> Unit,
    trailing: ImageVector = Icons.Default.ChevronRight,
    // When `locked` the row still taps through (callers route it to the upgrade
    // flow) but reads as gated: dimmed chip, padlock instead of the chevron.
    // Mirrors SettingsToggle's locked state.
    locked: Boolean = false,
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
            SettingIconChip(icon = icon, iconColor = if (locked) palette.onSurfaceFaint else iconColor, contentDescription = title)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = palette.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (description != null) {
                    Text(description, color = palette.onSurfaceMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (locked) Icons.Default.Lock else trailing,
                if (locked) stringResource(R.string.cd_locked_feature) else stringResource(R.string.cd_open),
                tint = palette.onSurfaceFaint,
                modifier = Modifier.size(18.dp),
            )
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
