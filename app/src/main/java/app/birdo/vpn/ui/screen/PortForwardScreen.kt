package app.birdo.vpn.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.birdo.vpn.R
import app.birdo.vpn.data.model.PortForward
import app.birdo.vpn.ui.components.BirdoButton
import app.birdo.vpn.ui.components.BirdoButtonVariant
import app.birdo.vpn.ui.components.BirdoCard
import app.birdo.vpn.ui.components.BirdoSectionHeader
import app.birdo.vpn.ui.components.BirdoSegmentedControl
import app.birdo.vpn.ui.components.BirdoTextField
import app.birdo.vpn.ui.components.BirdoTopBar
import app.birdo.vpn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortForwardScreen(
    portForwards: List<PortForward>,
    isLoading: Boolean,
    error: String?,
    onCreate: (internalPort: Int, protocol: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onBack: () -> Unit,
) {
    var portText by remember { mutableStateOf("") }
    var selectedProtocol by remember { mutableStateOf("tcp") }
    // Deleting a forwarding rule tears down a live DNAT mapping — confirm it.
    var pendingDelete by remember { mutableStateOf<PortForward?>(null) }
    val palette = BirdoColors.current

    val portValue = portText.toIntOrNull()
    val isPortValid = portValue != null && portValue in 1..65535
    val showPortError = portText.isNotEmpty() && !isPortValid

    Scaffold(
        topBar = {
            BirdoTopBar(
                title = stringResource(R.string.port_forward_title),
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
            // ── Info Card ────────────────────────────────────────
            item {
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
                            stringResource(R.string.port_forward_info),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.onSurfaceMuted,
                        )
                    }
                }
            }

            // ── Error Display ────────────────────────────────────
            if (error != null) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Assertive },
                        shape = RoundedCornerShape(12.dp),
                        color = BirdoRedBg,
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = BirdoRed,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            // ── New Rule Section ─────────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.port_forward_new_rule)) }

            item {
                BirdoCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Column {
                        BirdoTextField(
                            value = portText,
                            onValueChange = { text ->
                                portText = text.filter { it.isDigit() }.take(5)
                            },
                            label = stringResource(R.string.port_forward_internal_port),
                            placeholder = stringResource(R.string.port_forward_port_hint),
                            keyboardType = KeyboardType.Number,
                            isError = showPortError,
                            supportingText = if (showPortError) {
                                stringResource(R.string.port_forward_port_range)
                            } else null,
                        )

                        Spacer(Modifier.height(12.dp))

                        Text(
                            stringResource(R.string.port_forward_protocol),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.onSurfaceMuted,
                        )
                        Spacer(Modifier.height(8.dp))
                        BirdoSegmentedControl(
                            options = listOf("tcp" to "TCP", "udp" to "UDP"),
                            selectedKey = selectedProtocol,
                            onSelect = { selectedProtocol = it },
                        )

                        Spacer(Modifier.height(16.dp))

                        BirdoButton(
                            text = stringResource(R.string.port_forward_add_rule),
                            onClick = {
                                if (isPortValid) {
                                    onCreate(portValue!!, selectedProtocol)
                                    portText = ""
                                }
                            },
                            variant = BirdoButtonVariant.Brand,
                            icon = Icons.Default.Add,
                            enabled = isPortValid && !isLoading,
                            isLoading = isLoading,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ── Active Rules Section ─────────────────────────────
            item { BirdoSectionHeader(stringResource(R.string.port_forward_active_rules)) }

            if (isLoading && portForwards.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = palette.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            if (!isLoading && portForwards.isEmpty()) {
                item {
                    BirdoCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                    ) {
                        Text(
                            text = stringResource(R.string.port_forward_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.onSurfaceMuted,
                        )
                    }
                }
            }

            items(portForwards, key = { it.id }) { pf ->
                BirdoCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 14.dp,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = BirdoGreen,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${pf.externalPort}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.onSurface,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = " → ",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.onSurfaceFaint,
                                )
                                Text(
                                    text = "${pf.internalPort}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.onSurface,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Spacer(Modifier.height(4.dp))

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = palette.surfaceRaised,
                            ) {
                                Text(
                                    text = pf.protocol.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.onSurfaceMuted,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }

                        IconButton(onClick = { pendingDelete = pf }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.port_forward_delete_rule),
                                tint = BirdoRed,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    pendingDelete?.let { pf ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = palette.surfaceElevated,
            titleContentColor = BirdoRed,
            textContentColor = palette.onSurfaceMuted,
            title = { Text(stringResource(R.string.port_forward_delete_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(stringResource(R.string.port_forward_delete_message, pf.externalPort, pf.internalPort))
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(pf.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.port_forward_delete_confirm), color = BirdoRed, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.delete_dialog_cancel), color = palette.onSurfaceMuted)
                }
            },
        )
    }
}
