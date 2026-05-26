package app.birdo.vpn.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.ui.TestTags
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.ui.viewmodel.SettingsUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnSettingsScreen(
    state: SettingsUiState,
    onKillSwitchChange: (Boolean) -> Unit,
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
) {
    var customPortText by remember { mutableStateOf(
        if (state.wireGuardPort != "auto" && state.wireGuardPort != "51820" && state.wireGuardPort != "53")
            state.wireGuardPort else ""
    ) }
    var mtuText by remember { mutableStateOf(
        if (state.wireGuardMtu > 0) state.wireGuardMtu.toString() else ""
    ) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.vpn_settings_title),
                        fontWeight = FontWeight.Bold,
                        color = BirdoWhite80,
                        fontFamily = FontFamily.SansSerif,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = BirdoWhite60,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
            item { VpnSectionHeader("SECURITY") }

            item {
                VpnToggle(
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
                VpnToggle(
                    icon = Icons.Default.VisibilityOff,
                    iconColor = BirdoBlue,
                    title = "Stealth Mode",
                    description = "Route through Xray Reality to bypass deep packet inspection. Makes VPN traffic look like normal HTTPS.",
                    checked = state.stealthModeEnabled,
                    onCheckedChange = onStealthModeChange,
                )
            }

            item {
                VpnToggle(
                    icon = Icons.Default.Lock,
                    iconColor = BirdoPurple,
                    title = "Quantum Protection",
                    description = "Add post-quantum pre-shared key exchange via BirdoPQ v1 (ML-KEM-1024, NIST FIPS 203). Protects against future quantum computer attacks.",
                    checked = state.quantumProtectionEnabled,
                    onCheckedChange = onQuantumProtectionChange,
                )
            }

            // ── Network Section ──────────────────────────────────
            item { VpnSectionHeader(stringResource(R.string.vpn_settings_section_network)) }

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
            item { VpnSectionHeader(stringResource(R.string.vpn_settings_section_dns)) }

            item {
                VpnToggle(
                    icon = Icons.Default.Dns,
                    iconColor = BirdoPurple,
                    title = stringResource(R.string.vpn_settings_custom_dns),
                    description = stringResource(R.string.vpn_settings_custom_dns_desc),
                    checked = state.customDnsEnabled,
                    onCheckedChange = onCustomDnsEnabledChange,
                )
            }

            if (state.customDnsEnabled) {
                item {
                    VpnTextField(
                        value = state.customDnsPrimary,
                        onValueChange = onCustomDnsPrimaryChange,
                        label = stringResource(R.string.vpn_settings_dns_primary),
                        placeholder = stringResource(R.string.vpn_settings_dns_primary_hint),
                        keyboardType = KeyboardType.Decimal,
                    )
                }
                item {
                    VpnTextField(
                        value = state.customDnsSecondary,
                        onValueChange = onCustomDnsSecondaryChange,
                        label = stringResource(R.string.vpn_settings_dns_secondary),
                        placeholder = stringResource(R.string.vpn_settings_dns_secondary_hint),
                        keyboardType = KeyboardType.Decimal,
                    )
                }
            }

            // ── WireGuard Section ────────────────────────────────
            item { VpnSectionHeader(stringResource(R.string.vpn_settings_section_wireguard)) }

            // Port selection
            item {
                VpnCardSurface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Router, stringResource(R.string.vpn_settings_port), tint = BirdoGreen, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(
                                stringResource(R.string.vpn_settings_port),
                                style = MaterialTheme.typography.titleSmall,
                                color = BirdoWhite80,
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
                                        selectedColor = BirdoWhite,
                                        unselectedColor = BirdoWhite40,
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
                                    color = BirdoWhite80,
                                )
                            }
                        }

                        // Custom port text field
                        if (selectedPort == "custom") {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customPortText,
                                onValueChange = { text ->
                                    val filtered = text.filter { it.isDigit() }.take(5)
                                    customPortText = filtered
                                    val port = filtered.toIntOrNull()
                                    if (port != null && port in 1..65535) {
                                        onWireGuardPortChange(filtered)
                                    }
                                },
                                label = { Text(stringResource(R.string.vpn_settings_port_custom_hint)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                colors = vpnTextFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // MTU
            item {
                VpnCardSurface {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, stringResource(R.string.vpn_settings_mtu), tint = BirdoYellow, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text(
                                    stringResource(R.string.vpn_settings_mtu),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = BirdoWhite80,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    stringResource(R.string.vpn_settings_mtu_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BirdoWhite40,
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
                                    checkedColor = BirdoWhite,
                                    checkmarkColor = BirdoBlack,
                                    uncheckedColor = BirdoWhite40,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.vpn_settings_mtu_auto),
                                style = MaterialTheme.typography.bodyMedium,
                                color = BirdoWhite80,
                            )
                        }

                        if (state.wireGuardMtu != 0) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = mtuText,
                                onValueChange = { text ->
                                    val filtered = text.filter { it.isDigit() }.take(4)
                                    mtuText = filtered
                                    val mtu = filtered.toIntOrNull()
                                    if (mtu != null) {
                                        onWireGuardMtuChange(mtu)
                                    }
                                },
                                label = { Text(stringResource(R.string.vpn_settings_mtu_hint)) },
                                supportingText = { Text(stringResource(R.string.vpn_settings_mtu_range), color = BirdoWhite40) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                colors = vpnTextFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
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
                    color = BirdoWhite10,
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Info, null, tint = BirdoWhite40, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.vpn_settings_changes_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = BirdoWhite60,
                        )
                    }
                }
            }

            // ── Features Section ────────────────────────────────────
            item { VpnSectionHeader("FEATURES") }

            item {
                VpnLink(
                    icon = Icons.Default.SwapHoriz,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.settings_port_forward),
                    description = stringResource(R.string.settings_port_forward_desc),
                    onClick = onOpenPortForward,
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ── Reusable Components ──────────────────────────────────────────────────────

@Composable
private fun VpnSectionHeader(title: String) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(BirdoBlue.copy(alpha = 0.7f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = BirdoWhite60,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
    }
}

@Composable
private fun VpnCardSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = BirdoSurface,
    ) {
        content()
    }
}

@Composable
private fun VpnToggle(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null,
) {
    VpnCardSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, title, tint = iconColor, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = BirdoWhite80, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = BirdoWhite40, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = testTag?.let { Modifier.testTag(it) } ?: Modifier,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = Color.White,
                    checkedBorderColor = Color.White,
                    uncheckedThumbColor = BirdoWhite80,
                    uncheckedTrackColor = BirdoWhite20,
                    uncheckedBorderColor = BirdoWhite20,
                ),
            )
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
) {
    VpnCardSurface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, title, tint = iconColor, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = BirdoWhite80, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = BirdoWhite40, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ChevronRight, null, tint = BirdoWhite40, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun VpnTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    VpnCardSurface {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder, color = BirdoWhite20) },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = true,
            colors = vpnTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun vpnTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = BirdoWhite,
    unfocusedTextColor = BirdoWhite80,
    focusedBorderColor = BirdoWhite60,
    unfocusedBorderColor = BirdoWhite20,
    cursorColor = BirdoWhite,
    focusedLabelColor = BirdoWhite60,
    unfocusedLabelColor = BirdoWhite40,
)
