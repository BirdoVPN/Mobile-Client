package app.birdo.vpn.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.ui.TestTags
import app.birdo.vpn.ui.components.BirdoCard
import app.birdo.vpn.ui.components.BirdoSectionHeader
import app.birdo.vpn.ui.components.BirdoTextField
import app.birdo.vpn.ui.components.BirdoTopBar
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.ui.viewmodel.SettingsUiState
import app.birdo.vpn.utils.InputValidator

/** Ports offered as presets. Anything else means the user chose a custom port. */
private val PORT_PRESETS = listOf("auto", "51820", "53")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnSettingsScreen(
    state: SettingsUiState,
    onLocalNetworkSharingChange: (Boolean) -> Unit,
    onCustomDnsEnabledChange: (Boolean) -> Unit,
    onCustomDnsPrimaryChange: (String) -> Unit,
    onCustomDnsSecondaryChange: (String) -> Unit,
    onWireGuardPortChange: (String) -> Unit,
    onWireGuardMtuChange: (Int) -> Unit,
    onStealthModeChange: (Boolean) -> Unit,
    onQuantumProtectionChange: (Boolean) -> Unit,
    onKillSwitchChange: (Boolean) -> Unit,
    onOpenPortForward: () -> Unit,
    onBack: () -> Unit,
    // ── Plan gating ──────────────────────────────────────────────
    // Premium toggles mirror the Multi-Hop pattern on the Connect screen:
    // when the feature is locked, the control shows a lock affordance and
    // tapping it routes the user to the upgrade flow instead of toggling.
    stealthUnlocked: Boolean = true,
    customDnsUnlocked: Boolean = true,
    portForwardUnlocked: Boolean = true,
    quantumUnlocked: Boolean = true,
    onUpgradeRequired: (feature: String) -> Unit = {},
) {
    // The port radio's selection is LOCAL UI state, deliberately not derived from
    // the persisted port. Deriving it meant tapping "Custom" with an empty field
    // wrote "auto" straight back, which re-derived the selection as "auto", so the
    // custom input never rendered and the radio snapped back — the control was
    // impossible to reach. Now "custom" is a mode the user is in, and the port is
    // persisted only once they type a valid one.
    var portMode by rememberSaveable {
        mutableStateOf(if (state.wireGuardPort in PORT_PRESETS) state.wireGuardPort else "custom")
    }
    var customPortText by rememberSaveable {
        mutableStateOf(if (state.wireGuardPort in PORT_PRESETS) "" else state.wireGuardPort)
    }
    var mtuText by rememberSaveable {
        mutableStateOf(if (state.wireGuardMtu > 0) state.wireGuardMtu.toString() else "")
    }
    // Turning the kill switch OFF weakens leak protection, so it is gated behind
    // an explicit confirmation dialog (enabling it stays immediate).
    var showKillSwitchDisableDialog by rememberSaveable { mutableStateOf(false) }
    val palette = BirdoColors.current

    Scaffold(
        topBar = {
            BirdoTopBar(
                title = stringResource(R.string.vpn_settings_title),
                onBack = onBack,
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── Security Section ─────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.vpn_settings_section_security)) }

            item {
                // Kill switch defaults ON (the safe choice) but is user-toggleable.
                // Enabling is immediate; disabling is gated behind a confirmation
                // dialog because it trades leak-proofing for connectivity — if the
                // tunnel drops, apps fall back to the open internet and can briefly
                // expose the real IP. The service gates every activateKillSwitch() on
                // the value behind this toggle, so turning it off genuinely lets
                // traffic through.
                VpnToggle(
                    icon = Icons.Default.Shield,
                    iconColor = BirdoGreen,
                    title = stringResource(R.string.settings_kill_switch),
                    description = stringResource(R.string.settings_kill_switch_desc),
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
                // Stealth mode is OPERATIVE+. `&& stealthUnlocked` on `checked`
                // means a persisted-on state can't resurface after a downgrade;
                // the lock affordance routes to the upgrade flow (matches Quantum
                // / Custom-DNS / Port-forward gating).
                VpnToggle(
                    icon = Icons.Default.VisibilityOff,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.vpn_settings_stealth_title),
                    description = stringResource(R.string.vpn_settings_stealth_desc),
                    checked = state.stealthModeEnabled && stealthUnlocked,
                    onCheckedChange = onStealthModeChange,
                    locked = !stealthUnlocked,
                    onLockedTap = { onUpgradeRequired("Stealth Mode") },
                )
            }

            item {
                VpnToggle(
                    icon = Icons.Default.Lock,
                    iconColor = BirdoAccent,
                    title = stringResource(R.string.vpn_settings_quantum_title),
                    description = stringResource(R.string.vpn_settings_quantum_desc),
                    checked = state.quantumProtectionEnabled && quantumUnlocked,
                    onCheckedChange = onQuantumProtectionChange,
                    locked = !quantumUnlocked,
                    onLockedTap = { onUpgradeRequired("Quantum Protection") },
                )
            }

            // ── Network Section ──────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.vpn_settings_section_network)) }

            item {
                VpnToggle(
                    icon = Icons.Default.Lan,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.vpn_settings_local_network),
                    description = stringResource(R.string.vpn_settings_local_network_desc),
                    checked = state.localNetworkSharing,
                    onCheckedChange = onLocalNetworkSharingChange,
                )
            }

            // ── DNS Section ──────────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.vpn_settings_section_dns)) }

            item {
                VpnToggle(
                    icon = Icons.Default.Dns,
                    iconColor = BirdoAccent,
                    title = stringResource(R.string.vpn_settings_custom_dns),
                    description = stringResource(R.string.vpn_settings_custom_dns_desc),
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
                        modifier = Modifier.padding(vertical = 4.dp),
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
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            // ── WireGuard Section ────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.vpn_settings_section_wireguard)) }

            // Port selection
            item {
                BirdoCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Router, stringResource(R.string.vpn_settings_port), tint = BirdoGreen, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(
                                stringResource(R.string.vpn_settings_port),
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.onSurface,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        (PORT_PRESETS + "custom").forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    // selectable, not toggleable: these are radios,
                                    // and re-tapping the selected one must be a no-op
                                    // rather than an onValueChange(false).
                                    .selectable(
                                        selected = portMode == option,
                                        role = Role.RadioButton,
                                        onClick = {
                                            portMode = option
                                            if (option == "custom") {
                                                // Persist only once a VALID port is typed.
                                                // Writing "auto" here (the old behaviour)
                                                // both clobbered the user's previous choice
                                                // and made this radio unreachable.
                                                val port = customPortText.toIntOrNull()
                                                if (port != null && port in 1..65535) {
                                                    onWireGuardPortChange(customPortText)
                                                }
                                            } else {
                                                onWireGuardPortChange(option)
                                            }
                                        },
                                    )
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = portMode == option,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = palette.accent,
                                        unselectedColor = palette.onSurfaceFaint,
                                    ),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = when (option) {
                                        "auto" -> stringResource(R.string.vpn_settings_port_auto)
                                        "51820" -> "51820"
                                        "53" -> "53"
                                        else -> stringResource(R.string.vpn_settings_port_custom)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.onSurface.copy(alpha = 0.85f),
                                )
                            }
                        }

                        // Custom port text field
                        if (portMode == "custom") {
                            Spacer(Modifier.height(8.dp))
                            val customPortValid =
                                customPortText.toIntOrNull()?.let { it in 1..65535 } == true
                            BirdoTextField(
                                value = customPortText,
                                onValueChange = { text ->
                                    val filtered = text.filter { it.isDigit() }.take(5)
                                    customPortText = filtered
                                    val port = filtered.toIntOrNull()
                                    if (port != null && port in 1..65535) {
                                        onWireGuardPortChange(filtered)
                                    }
                                },
                                label = stringResource(R.string.vpn_settings_port_custom_hint),
                                keyboardType = KeyboardType.Number,
                                isError = customPortText.isNotBlank() && !customPortValid,
                                // Say so while the typed port is not yet in effect,
                                // rather than letting the UI imply it has been applied.
                                supportingText = if (customPortValid) {
                                    null
                                } else {
                                    stringResource(R.string.vpn_settings_port_range)
                                },
                            )
                        }
                    }
                }
            }

            // MTU
            item {
                BirdoCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, stringResource(R.string.vpn_settings_mtu), tint = BirdoYellow, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    stringResource(R.string.vpn_settings_mtu),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.onSurface,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    stringResource(R.string.vpn_settings_mtu_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.onSurfaceMuted,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // Auto toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .toggleable(
                                    value = state.wireGuardMtu == 0,
                                    role = Role.Switch,
                                    onValueChange = { isAuto ->
                                        if (isAuto) {
                                            mtuText = ""
                                            onWireGuardMtuChange(0)
                                        } else {
                                            onWireGuardMtuChange(1420)
                                            mtuText = "1420"
                                        }
                                    },
                                )
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = state.wireGuardMtu == 0,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = palette.accent,
                                    checkmarkColor = Color.White,
                                    uncheckedColor = palette.onSurfaceFaint,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.vpn_settings_mtu_auto),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.onSurface.copy(alpha = 0.85f),
                            )
                        }

                        if (state.wireGuardMtu != 0) {
                            Spacer(Modifier.height(8.dp))
                            BirdoTextField(
                                value = mtuText,
                                onValueChange = { text ->
                                    val filtered = text.filter { it.isDigit() }.take(4)
                                    mtuText = filtered
                                    val mtu = filtered.toIntOrNull()
                                    if (mtu != null) {
                                        onWireGuardMtuChange(mtu)
                                    }
                                },
                                label = stringResource(R.string.vpn_settings_mtu_hint),
                                supportingText = stringResource(R.string.vpn_settings_mtu_range),
                                keyboardType = KeyboardType.Number,
                            )
                        }
                    }
                }
            }

            // ── Info note ────────────────────────────────────────
            item {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = palette.surfaceRaised,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Info, null, tint = palette.onSurfaceFaint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.vpn_settings_changes_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onSurfaceMuted,
                        )
                    }
                }
            }

            // ── Features Section ────────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.vpn_settings_section_features)) }

            item {
                VpnLink(
                    icon = Icons.Default.SwapHoriz,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.settings_port_forward),
                    description = stringResource(R.string.settings_port_forward_desc),
                    onClick = if (portForwardUnlocked) onOpenPortForward
                        else { { onUpgradeRequired("Port Forwarding") } },
                    locked = !portForwardUnlocked,
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }

        if (showKillSwitchDisableDialog) {
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

// ── Reusable Components ──────────────────────────────────────────────────────

@Composable
private fun VpnToggle(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    testTag: String? = null,
    // When `locked`, the switch is non-interactive and the whole row taps
    // through to `onLockedTap` (the upgrade flow), mirroring the Multi-Hop
    // gating affordance on the Connect screen.
    locked: Boolean = false,
    onLockedTap: () -> Unit = {},
) {
    val palette = BirdoColors.current
    BirdoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        val rowModifier = if (locked) {
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClick = onLockedTap)
        } else {
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        }
        Row(
            modifier = rowModifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, title, tint = if (locked) palette.onSurfaceFaint else iconColor, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = palette.onSurface, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
                // onCheckedChange = null: the row's toggleable owns the
                // interaction so TalkBack sees ONE labeled switch, not two.
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
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
private fun VpnLink(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    onClick: () -> Unit,
    locked: Boolean = false,
) {
    val palette = BirdoColors.current
    BirdoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, title, tint = if (locked) palette.onSurfaceFaint else iconColor, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = palette.onSurface, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (locked) Icons.Default.Lock else Icons.Default.ChevronRight,
                contentDescription = if (locked) stringResource(R.string.cd_locked_feature) else null,
                tint = palette.onSurfaceFaint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
