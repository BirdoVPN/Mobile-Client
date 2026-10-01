package app.birdo.vpn.ui.screen

import app.birdo.vpn.R
import app.birdo.vpn.service.FailureKind
import app.birdo.vpn.service.VpnState
import app.birdo.vpn.ui.components.BadgeTone
import app.birdo.vpn.ui.viewmodel.VpnUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Connect screen's status pill, main button and message banner, in the
 * canonical vocabulary (P1-parity) — as pure functions, so each row is a test.
 */
class HomeConnectionModelTest {

    private fun pill(state: VpnUiState) = pillModel(state)

    private fun cta(state: VpnUiState, armed: Boolean = false, ready: Boolean = false) =
        ctaModel(homeConnection(state), multiHopArmed = armed, multiHopReady = ready)

    @Test
    fun `Reconnecting is its own state, with Disconnect as the way out (A1-008, P1-parity-001)`() {
        val state = VpnUiState(vpnState = VpnState.Reconnecting(2))
        assertEquals(R.string.status_reconnecting, pill(state).text)
        assertEquals(BadgeTone.Warning, pill(state).tone)
        val button = cta(state)
        assertEquals(R.string.disconnect, button.label)
        assertEquals(CtaAction.DISCONNECT, button.action)
        assertFalse(button.busy)
    }

    @Test
    fun `a connect in flight keeps its label and offers Cancel (A1-010)`() {
        listOf(VpnState.Connecting, VpnState.StealthConnecting).forEach { s ->
            val button = cta(VpnUiState(vpnState = s))
            assertEquals(R.string.connecting, button.label)
            assertTrue(button.busy)
            assertTrue("Cancel for $s", button.showCancel)
        }
        assertFalse(cta(VpnUiState(vpnState = VpnState.Disconnecting)).showCancel)
    }

    @Test
    fun `a server switch says so on the pill and the button (P1-parity-016)`() {
        for (s in listOf(VpnState.Disconnecting, VpnState.Disconnected, VpnState.Connecting)) {
            val state = VpnUiState(vpnState = s, switching = true)
            assertEquals("pill for $s", R.string.status_switching, pill(state).text)
            assertEquals("button for $s", R.string.cta_switching, cta(state).label)
            assertTrue(cta(state).showCancel)
        }
    }

    @Test
    fun `Multi-Hop reads Protected · Multi-Hop (P1-parity-017)`() {
        assertEquals(R.string.status_protected, pill(VpnUiState(vpnState = VpnState.Connected)).text)
        assertEquals(
            R.string.status_protected_multihop,
            pill(VpnUiState(vpnState = VpnState.Connected, liveMultiHopEntryId = "de-1")).text,
        )
        assertTrue(pill(VpnUiState(vpnState = VpnState.Connected)).pulse)
    }

    @Test
    fun `a held block offers Disconnect, not a connect nobody can complete`() {
        val state = VpnUiState(vpnState = VpnState.Error("x", FailureKind.UPDATE_REQUIRED), killSwitchActive = true)
        assertTrue(homeConnection(state).isBlocking)
        assertEquals(CtaAction.DISCONNECT, cta(state).action)
        assertEquals(R.string.status_error, pill(state).text)
        // Once the block is released, Connect again.
        assertEquals(CtaAction.CONNECT, cta(state.copy(killSwitchActive = false)).action)
    }

    @Test
    fun `idle, Multi-Hop armed and ready are unchanged`() {
        assertEquals(R.string.status_not_connected, pill(VpnUiState()).text)
        assertEquals(CtaAction.CONNECT, cta(VpnUiState()).action)
        assertEquals(CtaAction.NONE, cta(VpnUiState(), armed = true).action)
        assertEquals(CtaAction.CONNECT_MULTI_HOP, cta(VpnUiState(), armed = true, ready = true).action)
    }

    @Test
    fun `one message banner, never two (A2-011)`() {
        val both = VpnUiState(vpnState = VpnState.Error("from the session"), connectError = "from the screen")
        assertEquals("from the screen", homeMessage(both))
        assertEquals("from the session", homeMessage(both.copy(connectError = null)))
        assertNull(homeMessage(VpnUiState()))
    }

    @Test
    fun `failures offer the action that fixes them (P1-parity-040)`() {
        assertEquals(Remedy.OPEN_SETTINGS, remedyFor(FailureKind.QUANTUM_FAILED))
        assertEquals(Remedy.VIEW_PLANS, remedyFor(FailureKind.PLAN_REQUIRED))
        assertEquals(Remedy.VIEW_PLANS, remedyFor(FailureKind.QUOTA_EXCEEDED))
        assertEquals(Remedy.UPDATE, remedyFor(FailureKind.UPDATE_REQUIRED))
        assertEquals(Remedy.CHOOSE_SERVER, remedyFor(FailureKind.NEVER_ESTABLISHED))
        assertNull(remedyFor(FailureKind.STEALTH_FAILED))
    }

    /**
     * A1-027: the alerts that say the VPN stopped or needs the user are
     * notifications, so with notifications off Home must say so, with the way
     * to turn them on. A source pin: the check needs a Context.
     */
    @Test
    fun `Home warns when notifications are off and links to the setting`() {
        var dir = java.io.File("").absoluteFile
        while (!java.io.File(dir, "settings.gradle.kts").isFile) dir = checkNotNull(dir.parentFile) { "repo root not found" }
        val home = java.io.File(dir, "app/src/main/java/app/birdo/vpn/ui/screen/HomeScreen.kt").readText()
        assertTrue(home.contains("NotificationManagerCompat.from(context).areNotificationsEnabled()"))
        assertTrue(home.contains("R.string.home_notifications_off"))
        assertTrue(home.contains("Settings.ACTION_APP_NOTIFICATION_SETTINGS"))
    }
}
