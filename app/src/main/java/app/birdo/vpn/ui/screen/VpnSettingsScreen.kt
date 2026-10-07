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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.birdo.vpn.R
import app.birdo.vpn.service.StealthPolicy
import app.birdo.vpn.ui.components.BirdoCard
import app.birdo.vpn.ui.components.BirdoSectionHeader
import app.birdo.vpn.ui.components.BirdoTextField
import app.birdo.vpn.ui.components.BirdoTopBar
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.ui.viewmodel.SettingsUiState

/**
 * Everything the BirdoShield row renders, derived in ONE place.
 *
 * The switch position, the interactivity and the subtitle all have to agree
 * about whether the feature can actually do anything; deriving them separately
 * at three call sites is how a row ends up reading ON while disabled. Pure and
 * `internal` so the rule is unit-testable without an instrumented Compose run.
 */
internal data class BirdoShieldRowState(
    val checked: Boolean,
    val enabled: Boolean,
    val unavailable: Boolean,
    /** Custom DNS replaces the filtering resolver, so the switch would do nothing (A1-024). */
    val overriddenByCustomDns: Boolean = false,
)

/**
 * @param dnsFilteringEnabled the user's persisted per-device preference.
 * @param dnsFilteringAvailable the fleet gate — `true` on, `false` off,
 *   `null` NOT KNOWN YET (cold start, failed fetch, or a web deploy older than
 *   birdo-web#465).
 *
 * `== false` rather than `!available` is the whole point: unknown counts as
 * AVAILABLE. Hiding a working feature because the client could not reach the
 * web app is worse than showing it a moment before the gate is confirmed, and
 * the backend refuses the flag anyway while the gate is off.
 *
 * Note what this does NOT do: it never writes. An unavailable gate makes the
 * row read OFF, but [dnsFilteringEnabled] is left untouched in preferences, so
 * the user's own choice comes back by itself when the gate does.
 */
internal fun birdoShieldRowState(
    dnsFilteringEnabled: Boolean,
    dnsFilteringAvailable: Boolean?,
    customDnsEnabled: Boolean = false,
): BirdoShieldRowState {
    val unavailable = dnsFilteringAvailable == false
    // A1-024, as Windows does it: with Custom DNS on, the tunnel never asks
    // the filtering resolver, so the row reads OFF, cannot be switched, and
    // says why. The stored preference is kept for when Custom DNS goes off.
    val overridden = !unavailable && customDnsEnabled
    return BirdoShieldRowState(
        checked = dnsFilteringEnabled && !unavailable && !overridden,
        enabled = !unavailable && !overridden,
        unavailable = unavailable,
        overriddenByCustomDns = overridden,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnSettingsScreen(
    state: SettingsUiState,
    onLocalNetworkSharingChange: (Boolean) -> Unit,
    onWireGuardMtuChange: (Int) -> Unit,
    onStealthModeChange: (Boolean) -> Unit,
    onDnsFilteringChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    // ── Plan gating ──────────────────────────────────────────────
    // Premium toggles mirror the Multi-Hop pattern on the Connect screen:
    // when the feature is locked, the control shows a lock affordance and
    // tapping it routes the user to the upgrade flow instead of toggling.
    stealthUnlocked: Boolean = true,
    onUpgradeRequired: () -> Unit = {},
    // ── BirdoShield fleet gate ───────────────────────────────────
    // NOT a plan gate: BirdoShield is on every plan. This is whether the fleet
    // this account dials has DNS filtering switched on at all. Defaults to
    // `null` (unknown), which reads as available — see [birdoShieldRowState].
    dnsFilteringAvailable: Boolean? = null,
) {
    var mtuText by rememberSaveable {
        mutableStateOf(if (state.wireGuardMtu > 0) state.wireGuardMtu.toString() else "")
    }
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
                .padding(padding)
                // The port and MTU fields are at the bottom of the list.
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── Security Section ─────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.vpn_settings_section_security)) }

            item {
                val stealthRow = StealthPolicy.toggleRow(state.stealthModeEnabled, stealthUnlocked)
                // Stealth mode is OPERATIVE+. Turning it ON while locked routes
                // to the upgrade flow (matches the Quantum / Custom-DNS /
                // Port-forward gating on the Settings page); a downgrade's stored
                // ON is shown as it is, so the user can turn it off.
                // The title no longer says "· Premium" (P1-032): a paying user
                // saw an upsell word on a setting they own, and the lock icon
                // already marks it for everyone else.
                VpnToggle(
                    icon = Icons.Default.VisibilityOff,
                    iconColor = BirdoBlue,
                    title = stringResource(R.string.vpn_settings_stealth_title),
                    description = stringResource(R.string.vpn_settings_stealth_desc),
                    // Locked only in the ON direction (StealthPolicy.toggleRow):
                    // a downgrade's stored ON can still be turned off.
                    checked = stealthRow.checked,
                    onCheckedChange = onStealthModeChange,
                    locked = stealthRow.locked,
                    onLockedTap = onUpgradeRequired,
                )
            }

            item {
                // BirdoShield (D18): per-device DNS filtering, OFF by default and
                // available on every plan (no lock affordance — it is not a paid
                // feature, so no upgrade route). The flag only travels on the
                // connect body; a flip while connected goes through the same
                // apply-on-change reconnect as Stealth (SettingsViewModel).
                //
                // Disabled, not locked, when the FLEET gate is off: there is
                // nothing for the user to buy or change, so the row simply
                // states why. Their stored preference is untouched and returns
                // on its own when the gate comes back.
                val shield = birdoShieldRowState(state.dnsFilteringEnabled, dnsFilteringAvailable, state.customDnsEnabled)
                VpnToggle(
                    icon = Icons.Default.Shield,
                    iconColor = if (shield.enabled) BirdoGreen else palette.onSurfaceFaint,
                    title = stringResource(R.string.vpn_settings_birdoshield_title),
                    description = stringResource(
                        when {
                            shield.unavailable -> R.string.vpn_settings_birdoshield_unavailable
                            shield.overriddenByCustomDns -> R.string.vpn_settings_birdoshield_custom_dns
                            else -> R.string.vpn_settings_birdoshield_desc
                        },
                    ),
                    checked = shield.checked,
                    onCheckedChange = onDnsFilteringChange,
                    enabled = shield.enabled,
                    testTag = "vpn_settings_birdoshield",
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

            // ── WireGuard Section ────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.vpn_settings_section_wireguard)) }

            // The WireGuard port: not a choice (LIVE-PORT53). The relays accept
            // WireGuard on 51820 only, so the port-53 preset and a custom port could
            // never connect, and "51820" was the same as automatic. The row says
            // so instead of offering controls that only break the connection.
            item {
                BirdoCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        // Decorative: the title beside it names the card (A2-032).
                        Icon(Icons.Default.Router, contentDescription = null, tint = BirdoGreen, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                stringResource(R.string.vpn_settings_port),
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.onSurface,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(R.string.vpn_settings_port_auto),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.onSurface.copy(alpha = 0.85f),
                            )
                            Text(
                                stringResource(R.string.vpn_settings_port_fixed),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.onSurfaceMuted,
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
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = BirdoYellow, modifier = Modifier.size(22.dp))
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
    // Optional explanatory subtitle. Rendered only when present.
    description: String? = null,
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
            Icon(icon, contentDescription = null, tint = if (locked) palette.onSurfaceFaint else iconColor, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = palette.onSurface, fontWeight = FontWeight.Medium)
                if (description != null) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceMuted)
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
