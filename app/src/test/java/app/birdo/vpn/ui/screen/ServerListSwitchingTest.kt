package app.birdo.vpn.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.birdo.vpn.R
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.service.VpnState
import app.birdo.vpn.ui.viewmodel.VpnUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MR-945: Home said "Switching server…" while the Servers list showed no
 * switching state at all: the node being left lost its Connected dot and the
 * target looked merely selected. The real ServerListScreen, fed the markers
 * the nav graph feeds it ([serverRowMarkers]), under Robolectric.
 *
 * SDK 34: Robolectric's SDK 35+ jars need Java 21, and CI runs 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerListSwitchingTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val frankfurt = VpnServer(id = "de-1", name = "Frankfurt 1", country = "Germany", countryCode = "DE", city = "Frankfurt")
    private val amsterdam = VpnServer(id = "nl-1", name = "Amsterdam 1", country = "Netherlands", countryCode = "NL", city = "Amsterdam")

    private val switchingLabel get() = rule.activity.getString(R.string.cta_switching)
    private val connectedLabel get() = rule.activity.getString(R.string.cd_server_connected)

    private fun show(state: VpnUiState) {
        val markers = serverRowMarkers(state, multiHopArmed = false)
        rule.setContent {
            MaterialTheme {
                ServerListScreen(
                    servers = listOf(frankfurt, amsterdam),
                    selectedServer = state.selectedServer,
                    isLoading = false,
                    favoriteServers = emptySet(),
                    onSelectServer = {},
                    onToggleFavorite = {},
                    onRefresh = {},
                    onBack = {},
                    connectedServerId = markers.connectedServerId,
                    switchingServerId = markers.switchingServerId,
                )
            }
        }
    }

    /** A row is one merged node (it is clickable): its name, its location and its markers. */
    private fun row(name: String) = rule.onNode(hasText(name))

    @Test
    fun `during a switch the target row says Switching and the row being left is not Connected`() {
        show(VpnUiState(vpnState = VpnState.Connecting, switching = true, selectedServer = amsterdam, connectedServerId = "de-1"))

        row("Amsterdam 1").assert(hasText(switchingLabel))
        row("Frankfurt 1").assert(!hasText(switchingLabel))
        row("Frankfurt 1").assert(!hasContentDescription(connectedLabel))
        rule.onAllNodesWithText(switchingLabel).assertCountEquals(1)
    }

    @Test
    fun `once connected the dialled row is Connected and nothing says Switching`() {
        show(VpnUiState(vpnState = VpnState.Connected, selectedServer = amsterdam, connectedServerId = "nl-1"))

        row("Amsterdam 1").assert(hasContentDescription(connectedLabel))
        row("Frankfurt 1").assert(!hasContentDescription(connectedLabel))
        rule.onAllNodesWithText(switchingLabel).assertCountEquals(0)
    }
}
