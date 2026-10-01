package app.birdo.vpn.service

import app.birdo.vpn.R
import app.birdo.vpn.service.VpnNotificationManager.Action
import app.birdo.vpn.service.VpnNotificationManager.Tone
import app.birdo.vpn.widget.BirdoWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the notification, the Quick Settings tile and the widget show for each
 * session state. They used to render the SERVICE's copy of the state, which
 * went stale behind VpnManager (A1-002): "Reconnecting…" forever after the
 * retries stopped, a tile reading "Disconnected" over a blocked device, a
 * widget inviting a tap to connect while every packet was dropped (A1-044).
 */
class SessionSurfaceModelsTest {

    // ── Ongoing notification (A1-002, A1-009, A1-030, P1-parity-028) ─────

    private fun status(state: VpnState, blocking: Boolean = false, switching: Boolean = false, multiHop: Boolean = false) =
        VpnNotificationManager.statusModel(state, blocking, switching, multiHop)

    @Test
    fun `titles follow the canonical vocabulary`() {
        assertEquals(R.string.notif_title_protected, status(VpnState.Connected).title)
        assertEquals(R.string.notif_title_protected_multihop, status(VpnState.Connected, multiHop = true).title)
        assertEquals(R.string.connecting, status(VpnState.Connecting).title)
        assertEquals(R.string.connecting, status(VpnState.StealthConnecting).title)
        assertEquals(R.string.status_switching, status(VpnState.Connecting, switching = true).title)
        assertEquals(R.string.status_reconnecting, status(VpnState.Reconnecting(3)).title)
        assertEquals(R.string.disconnecting, status(VpnState.Disconnecting).title)
        assertEquals(R.string.status_error, status(VpnState.Error("x")).title)
        assertEquals(R.string.status_not_connected, status(VpnState.Disconnected).title)
    }

    @Test
    fun `a held block says so, whatever else is true`() {
        val blockedError = status(VpnState.Error("Couldn't reach", FailureKind.DIED_AFTER_HANDSHAKE), blocking = true)
        assertEquals(R.string.kill_switch_blocking, blockedError.title)
        assertEquals(Tone.ERROR, blockedError.tone)
        // "Stop blocking" (A1-030: it used to read "Disable Kill Switch" and only disconnect).
        assertTrue(Action.STOP_BLOCKING in blockedError.actions)
        assertTrue(Action.RECONNECT in blockedError.actions)
        assertEquals(R.string.kill_switch_blocking, status(VpnState.KillSwitchActive, blocking = true).title)
    }

    @Test
    fun `every busy state offers Disconnect as its way out`() {
        listOf(VpnState.Connected, VpnState.Connecting, VpnState.StealthConnecting, VpnState.Reconnecting(1)).forEach {
            assertEquals(it.toString(), listOf(Action.DISCONNECT), status(it).actions)
        }
    }

    @Test
    fun `Reconnect is offered only where it can work without the app`() {
        assertEquals(listOf(Action.RECONNECT), status(VpnState.Error("x", FailureKind.NEVER_ESTABLISHED)).actions)
        assertEquals(listOf(Action.RECONNECT), status(VpnState.Error("x", FailureKind.REVOKED)).actions)
        assertEquals(emptyList<Action>(), status(VpnState.Error("x", FailureKind.SIGN_IN_REQUIRED)).actions)
        assertEquals(emptyList<Action>(), status(VpnState.Error("x", FailureKind.UPDATE_REQUIRED)).actions)
    }

    @Test
    fun `the connected body is via location and IP, each only when shown`() {
        assertEquals("via Frankfurt · 1.2.3.4", VpnNotificationManager.connectedBody("Frankfurt", "1.2.3.4"))
        assertEquals("via Frankfurt", VpnNotificationManager.connectedBody("Frankfurt", null))
        assertEquals("1.2.3.4", VpnNotificationManager.connectedBody(null, "1.2.3.4"))
        assertNull(VpnNotificationManager.connectedBody("", " "))
    }

    // ── Alerts (A1-014 "Action needed", A1-027, A1-029, A1-035) ──────────

    @Test
    fun `a session that needs the user raises an alert when the app is not on screen`() {
        val signIn = VpnNotificationManager.alertFor(
            VpnState.Error(SessionCopy.SESSION_EXPIRED, FailureKind.SIGN_IN_REQUIRED),
            killSwitchActive = true,
            sessionExpired = false,
            uiForeground = false,
        )
        assertNotNull(signIn)
        assertEquals(R.string.notif_alert_sign_in, signIn!!.title)
        assertTrue("says the block is held", signIn.body.endsWith(SessionCopy.STILL_BLOCKED))
        assertFalse(signIn.reconnect)

        val takenOver = VpnNotificationManager.alertFor(
            VpnState.Error(SessionCopy.VPN_TAKEN_OVER, FailureKind.VPN_TAKEN_OVER), false, false, false,
        )
        assertEquals(R.string.notif_alert_turned_off, takenOver!!.title)

        val gaveUp = VpnNotificationManager.alertFor(
            VpnState.Error("BirdoVPN stopped reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE), false, false, false,
        )
        assertTrue(gaveUp!!.reconnect)
    }

    @Test
    fun `a give-up rendered before its release lands does not also say the block holds`() {
        // REVIEW-AND-014: the verdict renders while RELEASE_BLOCK is still
        // queued, so the block flag is still true for that first render.
        val message = SessionCopy.giveUp(FailureKind.DIED_AFTER_HANDSHAKE, 8, lockdown = false)
        val alert = VpnNotificationManager.alertFor(
            VpnState.Error(message, FailureKind.DIED_AFTER_HANDSHAKE),
            killSwitchActive = true,
            sessionExpired = false,
            uiForeground = false,
        )!!
        assertEquals(message, alert.body)
        assertFalse(alert.body.contains(SessionCopy.STILL_BLOCKED))
    }

    @Test
    fun `an expired session with the tunnel still up is an alert too`() {
        val alert = VpnNotificationManager.alertFor(VpnState.Connected, false, sessionExpired = true, uiForeground = false)
        assertEquals(R.string.notif_alert_sign_in, alert!!.title)
        assertNull(VpnNotificationManager.alertFor(VpnState.Connected, false, sessionExpired = false, uiForeground = false))
    }

    @Test
    fun `no alert while the app is on screen — Home already says it`() {
        assertNull(
            VpnNotificationManager.alertFor(VpnState.Error("x", FailureKind.REFUSED), false, false, uiForeground = true),
        )
    }

    // ── Service switches (A1-028, A1-036) ────────────────────────────────

    @Test
    fun `the Notifications setting governs the post-stop notice, and a user stop never gets one`() {
        assertTrue(BirdoVpnService.shouldPostDisconnectedNotice(userInitiated = false, notificationsEnabled = true))
        assertFalse(BirdoVpnService.shouldPostDisconnectedNotice(userInitiated = false, notificationsEnabled = false))
        assertFalse(BirdoVpnService.shouldPostDisconnectedNotice(userInitiated = true, notificationsEnabled = true))
    }

    @Test
    fun `the stats ticker sleeps with the screen`() {
        assertTrue(BirdoVpnService.shouldTickNotification(VpnState.Connected, screenInteractive = true))
        assertFalse(BirdoVpnService.shouldTickNotification(VpnState.Connected, screenInteractive = false))
        assertFalse(BirdoVpnService.shouldTickNotification(VpnState.Connecting, screenInteractive = true))
    }

    // ── Quick Settings tile (A1-002, A1-021) ─────────────────────────────

    @Test
    fun `the tile shows blocked as blocked, and the server only when allowed`() {
        val blocked = BirdoTileService.tileModel(VpnState.Error("x"), killSwitchActive = true, serverLabel = null, showLocation = true)
        assertFalse(blocked.active)
        assertEquals(R.string.tile_traffic_blocked, blocked.subtitle)

        val connected = BirdoTileService.tileModel(VpnState.Connected, false, "Frankfurt → Amsterdam", showLocation = true)
        assertTrue(connected.active)
        assertEquals("Frankfurt → Amsterdam", connected.serverLabel)
        assertNull(BirdoTileService.tileModel(VpnState.Connected, false, "Frankfurt", showLocation = false).serverLabel)

        assertEquals(R.string.status_reconnecting, BirdoTileService.tileModel(VpnState.Reconnecting(1), false, null, true).subtitle)
        assertEquals(R.string.status_not_connected, BirdoTileService.tileModel(VpnState.Disconnected, false, null, true).subtitle)
    }

    // ── Widget (A1-044, A2-014) ──────────────────────────────────────────

    @Test
    fun `the widget has a blocked state and says what a tap does`() {
        val blocked = BirdoWidget.widgetModel(VpnState.KillSwitchActive, killSwitchActive = true)
        assertEquals(BirdoWidget.Look.BLOCKED, blocked.look)
        assertEquals(R.string.widget_tap_to_stop_blocking, blocked.hint)

        val connecting = BirdoWidget.widgetModel(VpnState.Connecting, killSwitchActive = true)
        assertEquals(BirdoWidget.Look.BUSY, connecting.look)
        assertEquals(R.string.widget_tap_to_cancel, connecting.hint)

        assertEquals(R.string.widget_tap_to_disconnect, BirdoWidget.widgetModel(VpnState.Connected, false).hint)
        assertEquals(BirdoWidget.Look.IDLE, BirdoWidget.widgetModel(VpnState.Disconnected, false).look)
    }
}
