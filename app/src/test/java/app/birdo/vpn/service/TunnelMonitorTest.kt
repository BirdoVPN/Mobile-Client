package app.birdo.vpn.service

import app.birdo.vpn.service.TunnelMonitor.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A1-017: Thread.sleep counts awake time only, so the first stall check after
 * a long screen-off compared a wall-clock handshake age that was old only
 * because the CPU had been off — and condemned a working tunnel (kill switch
 * up, reconnect) before wg-go had been given a chance to re-handshake.
 */
class TunnelMonitorTest {

    @Test
    fun `deep sleep is elapsed time minus awake time`() {
        // Ten minutes passed; the CPU was awake for thirty seconds of them.
        assertEquals(570_000L, TunnelMonitor.suspendGap(0L, 0L, 600_000L, 30_000L))
        // An awake interval has no gap, and clock jitter never goes negative.
        assertEquals(0L, TunnelMonitor.suspendGap(0L, 0L, 30_000L, 30_000L))
        assertEquals(0L, TunnelMonitor.suspendGap(0L, 0L, 30_000L, 30_010L))
    }

    @Test
    fun `the first check after a suspend is deferred, not fatal`() {
        // A 10-minute sleep made the last handshake 600 s old: stalled on paper.
        assertEquals(Verdict.SKIPPED_AFTER_SUSPEND, TunnelMonitor.verdict(handshakeAgeSec = 600, suspendedMs = 570_000))
        assertEquals(Verdict.SKIPPED_AFTER_SUSPEND, TunnelMonitor.verdict(handshakeAgeSec = null, suspendedMs = 570_000))
    }

    @Test
    fun `an awake tunnel is judged as before`() {
        assertEquals(Verdict.ALIVE, TunnelMonitor.verdict(handshakeAgeSec = 25, suspendedMs = 0))
        assertEquals(Verdict.ALIVE, TunnelMonitor.verdict(handshakeAgeSec = 180, suspendedMs = 0))
        assertEquals(Verdict.STALLED, TunnelMonitor.verdict(handshakeAgeSec = 181, suspendedMs = 0))
        assertEquals(Verdict.NEVER_HANDSHOOK, TunnelMonitor.verdict(handshakeAgeSec = null, suspendedMs = 0))
        // A short doze inside the check interval is not a suspend worth deferring for.
        assertEquals(Verdict.STALLED, TunnelMonitor.verdict(handshakeAgeSec = 400, suspendedMs = TunnelMonitor.SUSPEND_GAP_MS))
    }
}
