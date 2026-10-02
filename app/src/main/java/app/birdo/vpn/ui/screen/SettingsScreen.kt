package app.birdo.vpn.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import app.birdo.vpn.ui.components.BirdoCard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
import app.birdo.vpn.ui.viewmodel.SettingsUiState
import app.birdo.vpn.utils.InputValidator

/**
 * Settings tab root, in the canonical section order (P1-parity "Settings names
 * and help text"): Appearance · Privacy & Security · Connection ·
 * Notifications · VPN · About. Every row says what it does (P1-010); iOS
 * explains every row and this screen used to explain three.
 */
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
    onCrashReportsChange: (Boolean) -> Unit = {},
    /** The user has read the settings-reset notice (A2-047). */
    onDismissSettingsResetNotice: () -> Unit = {},
    // ── Plan gating ──────────────────────────────────────────────
    // A locked row does not toggle; it taps through to the upgrade flow,
    // the same affordance Stealth Mode uses on the VPN Settings sub-page.
    // Custom DNS Servers is on every plan (owner decision D6, 2026-10-01).
    portForwardUnlocked: Boolean = true,
    quantumUnlocked: Boolean = true,
    onUpgradeRequired: () -> Unit = {},
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
                .padding(padding)
                // The DNS fields sit low on this list; the keyboard must push
                // them up, not cover them (edge-to-edge: nothing resizes the
                // window for us any more).
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.settingsResetNoticePending) {
                item { SettingsResetNotice(onDismiss = onDismissSettingsResetNotice) }
            }

            // ── Appearance ───────────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.settings_section_appearance)) }

            item {
                ThemeModeSelector(
                    currentMode = state.themeMode,
                    onModeSelected = onThemeModeChange,
                )
            }

            // ── Privacy & Security ───────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.settings_section_privacy_security)) }

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
                    description = stringResource(R.string.settings_kill_switch_desc),
                    // The caveat is the second sentence: never ellipsize it away.
                    descriptionMaxLines = Int.MAX_VALUE,
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

            item {
                SettingsToggle(
                    icon = Icons.Default.Lock,
                    iconColor = BirdoAccent,
                    title = stringResource(R.string.vpn_settings_quantum_title),
                    description = stringResource(R.string.settings_quantum_desc),
                    checked = state.quantumProtectionEnabled && quantumUnlocked,
                    onCheckedChange = onQuantumProtectionChange,
                    locked = !quantumUnlocked,
                    onLockedTap = onUpgradeRequired,
                )
            }

            item {
                // "Hide App Contents", not "Biometric Lock" (P1-011): the gate
                // covers the screen and guards nothing else — no key is released
                // by it, and the VPN (Auto-Connect included) runs behind it. A
                // security name, a Security section and a green icon all implied
                // otherwise. Neutral icon, and copy that says what it does. iOS
                // made the same change after its own audit. The stored key is
                // unchanged.
                SettingsToggle(
                    icon = Icons.Default.VisibilityOff,
                    iconColor = BirdoColors.current.onSurfaceMuted,
                    title = stringResource(R.string.settings_hide_app_contents),
                    description = stringResource(R.string.settings_hide_app_contents_desc),
                    descriptionMaxLines = Int.MAX_VALUE,
                    checked = state.biometricLockEnabled,
                    onCheckedChange = onBiometricLockChange,
                )
            }

            item {
                // Crash reports are OPT-IN (off by default). This is the "change
                // it any time in Settings" the consent screen promises; flipping
                // it starts or closes the SDK immediately (BirdoApp).
                SettingsToggle(
                    icon = Icons.Default.BugReport,
                    iconColor = BirdoWhite60,
                    title = stringResource(R.string.settings_crash_reports),
                    description = stringResource(R.string.settings_crash_reports_desc),
                    // A disclosure: shown in full, never ellipsized.
                    descriptionMaxLines = Int.MAX_VALUE,
                    checked = state.crashReportsEnabled,
                    onCheckedChange = onCrashReportsChange,
                    testTag = TestTags.CRASH_REPORTS_TOGGLE,
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

            // ── Notifications ────────────────────────────────────
            // What the app shows about a connection. The two detail rows
            // describe the notification's contents, so they appear only while
            // the notification itself is on.
            item { BirdoSectionHeader(stringResource(R.string.settings_section_notifications)) }

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
            // Unified group: the VPN Settings sub-page, plus the two controls
            // promoted out of it (custom DNS, port forwarding) and split
            // tunneling, which has always lived here.
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
                SettingsToggle(
                    icon = Icons.Default.Dns,
                    iconColor = BirdoAccent,
                    title = stringResource(R.string.vpn_settings_custom_dns),
                    description = stringResource(R.string.settings_custom_dns_desc),
                    checked = state.customDnsEnabled,
                    onCheckedChange = onCustomDnsEnabledChange,
                )
            }

            if (state.customDnsEnabled) {
                // Keyed: the fields keep their typed text in saved state, and
                // an item that appears above them (the reset notice, the
                // notification detail rows) must not hand it to another row.
                item(key = "dns_primary") {
                    DnsAddressField(
                        persisted = state.customDnsPrimary,
                        onValidChange = onCustomDnsPrimaryChange,
                        label = stringResource(R.string.vpn_settings_dns_primary),
                        placeholder = stringResource(R.string.vpn_settings_dns_primary_hint),
                    )
                }
                item(key = "dns_secondary") {
                    DnsAddressField(
                        persisted = state.customDnsSecondary,
                        onValidChange = onCustomDnsSecondaryChange,
                        label = stringResource(R.string.vpn_settings_dns_secondary),
                        placeholder = stringResource(R.string.vpn_settings_dns_secondary_hint),
                    )
                }
            }

            item {
                SettingsLink(
                    icon = Icons.Default.SwapHoriz,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.settings_port_forward),
                    description = stringResource(R.string.settings_port_forward_desc),
                    onClick = if (portForwardUnlocked) onOpenPortForward else onUpgradeRequired,
                    locked = !portForwardUnlocked,
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
                        description = pluralStringResource(R.plurals.settings_apps_bypassing, state.splitTunnelApps.size, state.splitTunnelApps.size),
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

/**
 * What a custom-DNS field may persist for the text in it: the trimmed address
 * when it is blank (meaning "use the VPN's DNS") or a valid IP, else null,
 * meaning keep typing. The FIELD holds the raw text; only this value reaches
 * the ViewModel.
 *
 * The field used to be bound straight to the persisted value, whose setter
 * rejected every partial address, so "8." snapped back to "8" and a DNS server
 * could not be typed at all, only pasted (A2-002).
 */
internal fun dnsValueToCommit(text: String): String? {
    val trimmed = text.trim()
    return trimmed.takeIf { it.isEmpty() || InputValidator.isValidDnsAddress(it) }
}

@Composable
private fun DnsAddressField(
    persisted: String,
    onValidChange: (String) -> Unit,
    label: String,
    placeholder: String,
) {
    // Seeded from, then independent of, the persisted value: the same
    // local-text pattern VPN Settings uses for the port and MTU fields.
    var text by rememberSaveable { mutableStateOf(persisted) }
    val invalid = dnsValueToCommit(text) == null
    BirdoTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            dnsValueToCommit(typed)?.let(onValidChange)
        },
        label = label,
        placeholder = placeholder,
        keyboardType = KeyboardType.Decimal,
        isError = invalid,
        // Said while the typed address is not yet in effect, rather than
        // letting the field imply it has been applied.
        supportingText = if (invalid) stringResource(R.string.settings_dns_invalid) else null,
    )
}

/**
 * The one-time notice after the settings-integrity check reset the protected
 * settings (A2-047). Its absence made a Keystore hiccup look like the app
 * forgetting the user's kill switch, DNS and split-tunnel choices.
 */
@Composable
private fun SettingsResetNotice(onDismiss: () -> Unit) {
    val palette = BirdoColors.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(14.dp),
        color = BirdoYellowBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, BirdoYellow.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = BirdoYellow, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.settings_reset_notice),
                color = palette.onSurface,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.dismiss),
                    tint = palette.onSurfaceMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The row's icon. Decorative: the row's title already names it (A2-032). */
@Composable
private fun SettingIconChip(
    icon: ImageVector,
    iconColor: Color,
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
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(18.dp))
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
    // Two lines suit a hint. A row whose description is a DISCLOSURE (the kill
    // switch's caveat, what crash reports send) passes Int.MAX_VALUE, so the
    // part that matters is never cut to an ellipsis (second-pass #5 / #7).
    descriptionMaxLines: Int = 2,
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
            SettingIconChip(icon = icon, iconColor = if (locked) palette.onSurfaceFaint else iconColor)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = palette.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (description != null) {
                    Text(description, color = palette.onSurfaceMuted, fontSize = 12.sp, maxLines = descriptionMaxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
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
            SettingIconChip(icon = icon, iconColor = if (locked) palette.onSurfaceFaint else iconColor)
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
