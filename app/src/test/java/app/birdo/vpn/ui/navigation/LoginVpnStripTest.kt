package app.birdo.vpn.ui.navigation

import app.birdo.vpn.R
import app.birdo.vpn.service.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** REVIEW-AND-015: Login says what is actually still up, not "connected" for everything. */
class LoginVpnStripTest {

    @Test
    fun `the strip names what is up`() {
        assertEquals(R.string.login_vpn_still_on, loginVpnStripText(VpnState.Connected, blocking = false))
        assertEquals(R.string.login_vpn_still_reconnecting, loginVpnStripText(VpnState.Reconnecting(2), blocking = true))
        assertEquals(R.string.login_vpn_still_reconnecting, loginVpnStripText(VpnState.Connecting, blocking = false))
        // Only the block is up: it used to read "Your VPN is still connected."
        assertEquals(R.string.login_vpn_still_blocking, loginVpnStripText(VpnState.KillSwitchActive, blocking = true))
        assertEquals(R.string.login_vpn_still_blocking, loginVpnStripText(VpnState.Error("x"), blocking = true))
    }

    @Test
    fun `nothing up, no strip`() {
        assertNull(loginVpnStripText(VpnState.Disconnected, blocking = false))
        assertNull(loginVpnStripText(VpnState.Error("x"), blocking = false))
    }
}
