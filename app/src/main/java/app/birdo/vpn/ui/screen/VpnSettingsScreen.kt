package app.birdo.vpn.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    onOpenPortForward: () -> Unit,
    onBack: () -> Unit,
    // ── Plan gating ──────────────────────────────────────────────
    // Premium toggles mirror the Multi-Hop pattern on the Connect screen:
    // when the feature is locked, the control shows a lock affordance and
    // tapping it routes the user to the upgrade flow instead of toggling.
    customDnsUnlocked: Boolean = true,
    portForwardUnlocked: Boolean = true,
    quantumUnlocked: Boolean = true,
    onUpgradeRequired: (feature: String) -> Unit = {},
) {
    var customPortText by remember { mutableStateOf(
        if (state.wireGuardPort != "auto" && state.wireGuardPort != "51820" && state.wireGuardPort != "53")
            state.wireGuardPort else ""
    ) }
    var mtuText by remember { mutableStateOf(
        if (state.wireGuardMtu > 0) state.wireGuardMtu.toString() else ""
    ) }
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
                // Kill switch is intentionally an always-on, locked control:
                // for security it can never be disabled (AppPreferences pins the
                // backing pref to `true`). It is rendered non-interactive with no
                // wired callback so there is nothing dangling behind the toggle.
                VpnToggle(
                    icon = Icons.Default.Shield,
                    iconColor = BirdoGreen,
                    title = stringResource(R.string.settings_kill_switch),
                    description = stringResource(R.string.settings_kill_switch_desc),
                    checked = true,
                    onCheckedChange = {},
                    enabled = false,
                    testTag = TestTags.KILL_SWITCH_TOGGLE,
                )
            }

            item {
                VpnToggle(
                    icon = Icons.Default.VisibilityOff,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.vpn_settings_stealth_title),
                    description = stringResource(R.string.vpn_settings_stealth_desc),
                    checked = state.stealthModeEnabled,
                    onCheckedChange = onStealthModeChange,
                )
            }

            item {
                VpnToggle(
                    icon = Icons.Default.Lock,
                    iconColor = BirdoPurple,
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
                    iconColor = BirdoPurple,
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

                        val portOptions = listOf("auto", "51820", "53", "custom")
                        val selectedPort = when (state.wireGuardPort) {
                            "auto", "51820", "53" -> state.wireGuardPort
                            else -> "custom"
                        }

                        portOptions.forEach { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .toggleable(
                                        value = selectedPort == option,
                                        role = Role.RadioButton,
                                        onValueChange = {
                                            if (it) {
                                                when (option) {
                                                    "custom" -> {
                                                        val port = customPortText.toIntOrNull()
                                                        onWireGuardPortChange(
                                                            if (port != null && port in 1..65535) customPortText else "auto"
                                                        )
                                                    }
                                                    else -> onWireGuardPortChange(option)
                                                }
                                            }
                                        },
                                    )
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selectedPort == option,
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
                        if (selectedPort == "custom") {
                            Spacer(Modifier.height(8.dp))
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
