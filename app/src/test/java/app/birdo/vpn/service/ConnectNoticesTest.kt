package app.birdo.vpn.service

import app.birdo.vpn.data.model.AccountRestriction
import app.birdo.vpn.data.model.DeviceNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MR-2221 / MR-2220: the connect reply's two optional objects, read without ever throwing. */
class ConnectNoticesTest {

    @Test
    fun `the end of a restriction is its ISO instant, and anything else holds nothing back`() {
        assertEquals(
            1_793_957_400_000L,
            ConnectNotices.restrictionEndMs(AccountRestriction("2026-11-06T09:30:00.000Z", "/dashboard/devices")),
        )
        assertEquals(0L, ConnectNotices.restrictionEndMs(null))
        assertEquals(0L, ConnectNotices.restrictionEndMs(AccountRestriction(null, "/x")))
        assertEquals(0L, ConnectNotices.restrictionEndMs(AccountRestriction("tomorrow-ish", "/x")))
        assertEquals(0L, ConnectNotices.restrictionEndMs(AccountRestriction("", "/x")))
    }

    @Test
    fun `a connect is held only before the end, never after, never without one`() {
        assertTrue(ConnectNotices.held(nowMs = 999, untilMs = 1_000))
        assertFalse(ConnectNotices.held(nowMs = 1_000, untilMs = 1_000))
        assertFalse(ConnectNotices.held(nowMs = 5_000, untilMs = 1_000))
        assertFalse(ConnectNotices.held(nowMs = 5, untilMs = 0))
    }

    @Test
    fun `only a plain path of this site becomes a link`() {
        assertEquals("https://birdo.app/dashboard/devices", ConnectNotices.siteUrl("/dashboard/devices"))
        assertNull(ConnectNotices.siteUrl(null))
        assertNull(ConnectNotices.siteUrl(""))
        assertNull(ConnectNotices.siteUrl("dashboard/devices"))
        assertNull(ConnectNotices.siteUrl("//evil.example/x"))
        assertNull(ConnectNotices.siteUrl("https://evil.example/x"))
        assertNull(ConnectNotices.siteUrl("/\\evil.example"))
        assertNull(ConnectNotices.siteUrl("/a b"))
    }

    @Test
    fun `a device notice is shown only as the server wrote it`() {
        val ok = DeviceNotice("device_limit_conflict", " Other devices kept taking this connection. ", "/dashboard/devices")
        assertEquals("Other devices kept taking this connection.", ConnectNotices.noticeText(ok))
        // A code from the future is still shown: the message is what the user reads.
        assertEquals("Hello.", ConnectNotices.noticeText(DeviceNotice("something_new", "Hello.", null)))
        assertNull(ConnectNotices.noticeText(null))
        assertNull(ConnectNotices.noticeText(DeviceNotice("c", null, "/x")))
        assertNull(ConnectNotices.noticeText(DeviceNotice("c", "   ", "/x")))
        assertNull(ConnectNotices.noticeText(DeviceNotice("c", "<html>x</html>", "/x")))
        assertNull(ConnectNotices.noticeText(DeviceNotice("c", "x".repeat(1_001), "/x")))
    }
}
