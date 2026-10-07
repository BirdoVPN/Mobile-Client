package app.birdo.vpn.service

import app.birdo.vpn.service.TunnelMonitor.Liveness
import app.birdo.vpn.service.TunnelMonitor.Sample
import app.birdo.vpn.service.TunnelMonitor.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dead-tunnel decision ([TunnelMonitor.assess]), row by row.
 *
 * Live baseline (emulator, 2026-09-30): "Protected" for 2m45s–3m20s after the
 * network died, because the only rule was "last handshake older than 180 s",
 * read every 30 s. These pin the fast rule, the no-network rule, the idle
 * backstop and phase A's suspend guard (A1-017).
 */
class TunnelMonitorTest {

    private val interval = TunnelMonitor.CHECK_INTERVAL_MS
    private val keepalive = 25

    /** Feed [samples] one check apart, starting at t = 0; return each verdict. */
    private fun run(
        samples: List<Sample?>,
        noNetworkForMs: (Int) -> Long = { 0L },
    ): List<Verdict> {
        var liveness = Liveness()
        return samples.mapIndexed { i, sample ->
            val (next, verdict) = TunnelMonitor.assess(
                prev = liveness,
                sample = sample,
                nowMs = i * interval,
                suspendedMs = 0L,
                noNetworkForMs = noNetworkForMs(i),
                keepaliveSec = keepalive,
            )
            liveness = next
            verdict
        }
    }

    // ── The fast rule ───────────────────────────────────────────────────

    @Test
    fun `sends that go unanswered for 20 s are a dead tunnel`() {
        // Healthy, then the network dies: the user's apps keep sending (tx
        // grows by data), nothing comes back (rx flat).
        val verdicts = run(
            listOf(
                Sample(rxBytes = 10_000, txBytes = 5_000, handshakeAgeSec = 30),
                Sample(rxBytes = 12_000, txBytes = 6_000, handshakeAgeSec = 40), // healthy
                Sample(rxBytes = 12_000, txBytes = 7_500, handshakeAgeSec = 50), // first unanswered (t=20 s)
                Sample(rxBytes = 12_000, txBytes = 8_100, handshakeAgeSec = 60), // 10 s unanswered
                Sample(rxBytes = 12_000, txBytes = 8_600, handshakeAgeSec = 70), // 20 s unanswered
            ),
        )
        assertEquals(
            listOf(Verdict.ALIVE, Verdict.ALIVE, Verdict.ALIVE, Verdict.ALIVE, Verdict.UNANSWERED),
            verdicts,
        )
    }

    @Test
    fun `the fast rule fires within 30 s of the first unanswered send, long before the old 180 s`() {
        val samples = mutableListOf(Sample(0, 0, 5))
        var tx = 0L
        repeat(10) { tx += 1_500; samples += Sample(0, tx, 15L + it * 10) }
        val verdicts = run(samples)
        val firstDead = verdicts.indexOfFirst { it != Verdict.ALIVE }
        // The first unanswered send is seen at check 1 (t = 10 s); dead at t = 30 s.
        assertEquals(Verdict.UNANSWERED, verdicts[firstDead])
        assertTrue("dead at check $firstDead", firstDead * interval <= 30_000L)
    }

    @Test
    fun `an answer resets the clock`() {
        val verdicts = run(
            listOf(
                Sample(1_000, 1_000, 10),
                Sample(1_000, 2_000, 20), // unanswered since t=10
                Sample(1_000, 3_000, 30),
                Sample(1_200, 4_000, 40), // the peer answered: reset
                Sample(1_200, 5_000, 50), // unanswered since t=40
                Sample(1_200, 6_000, 60),
            ),
        )
        assertTrue(verdicts.all { it == Verdict.ALIVE })
    }

    @Test
    fun `our own keepalives are never mistaken for unanswered sends`() {
        // An idle, healthy tunnel: WireGuard does not answer keepalives, so rx
        // stays flat while tx grows by one 32-byte keepalive per interval.
        val samples = (0..30).map { Sample(rxBytes = 500, txBytes = 32L * it, handshakeAgeSec = 5L + it * 4) }
        assertTrue(run(samples).all { it == Verdict.ALIVE })
        // A 10 s keepalive can fit two into one late check; still not a send.
        assertEquals(64L, TunnelMonitor.keepaliveAllowance(10))
        assertEquals(32L, TunnelMonitor.keepaliveAllowance(25))
    }

    @Test
    fun `retried handshake initiations count as unanswered sends`() {
        // Idle and dead: past REKEY_AFTER_TIME the keepalive triggers a 148-byte
        // handshake initiation, retried every 5 s, and nothing answers.
        val verdicts = run(
            listOf(
                Sample(900, 1_000, 119),
                Sample(900, 1_296, 129),
                Sample(900, 1_592, 139),
                Sample(900, 1_888, 149),
            ),
        )
        assertEquals(Verdict.UNANSWERED, verdicts.last())
    }

    // ── No physical network ─────────────────────────────────────────────

    @Test
    fun `no physical network for 10 s is a dead tunnel even though nothing could be sent`() {
        // Airplane mode: wg-go's sends fail, so tx does not grow and the fast
        // rule cannot see it. The network callbacks can.
        val flat = Sample(4_000, 4_000, 20)
        val verdicts = run(
            listOf(flat, flat, flat),
            noNetworkForMs = { i -> if (i == 0) 0L else (i - 1) * interval + 1 },
        )
        assertEquals(listOf(Verdict.ALIVE, Verdict.ALIVE, Verdict.NO_NETWORK), verdicts)
    }

    @Test
    fun `a roam shorter than the grace is not a death`() {
        val (_, verdict) = TunnelMonitor.assess(
            Liveness(), Sample(1, 1, 5), nowMs = 0, suspendedMs = 0,
            noNetworkForMs = TunnelMonitor.NO_NETWORK_DEAD_MS - 1, keepaliveSec = keepalive,
        )
        assertEquals(Verdict.ALIVE, verdict)
    }

    // ── The idle backstop and the old rules ─────────────────────────────

    @Test
    fun `the handshake-age backstop still catches a silent idle tunnel`() {
        val verdicts = run(listOf(Sample(5, 5, 170), Sample(5, 5, 180), Sample(5, 5, 181)))
        assertEquals(listOf(Verdict.ALIVE, Verdict.ALIVE, Verdict.STALLED), verdicts)
    }

    @Test
    fun `a tunnel that never handshook, or whose config cannot be read, is judged as before`() {
        assertEquals(listOf(Verdict.NEVER_HANDSHOOK), run(listOf(Sample(0, 148, null))))
        assertEquals(listOf(Verdict.NEVER_HANDSHOOK), run(listOf(null)))
    }

    @Test
    fun `the keepalive ceiling keeps a healthy idle tunnel below the backstop (A1-038)`() {
        assertTrue(
            "an idle tunnel re-handshakes at 120 s + one keepalive; that must stay under the backstop",
            WireGuardConfigBuilder.IDLE_HANDSHAKE_AGE_PEAK_SEC < TunnelMonitor.STALL_THRESHOLD_SEC,
        )
    }

    // ── Phase A's suspend guard (A1-017) ────────────────────────────────

    @Test
    fun `deep sleep is elapsed time minus awake time`() {
        // Ten minutes passed; the CPU was awake for thirty seconds of them.
        assertEquals(570_000L, TunnelMonitor.suspendGap(0L, 0L, 600_000L, 30_000L))
        // An awake interval has no gap, and clock jitter never goes negative.
        assertEquals(0L, TunnelMonitor.suspendGap(0L, 0L, 30_000L, 30_000L))
        assertEquals(0L, TunnelMonitor.suspendGap(0L, 0L, 30_000L, 30_010L))
    }

    @Test
    fun `the first check after a suspend is deferred, not fatal, and starts a fresh baseline`() {
        // Before the sleep a send was already unanswered; across the sleep the
        // handshake is 600 s old and tx grew: all stale.
        val (afterSleep, verdict) = TunnelMonitor.assess(
            Liveness(rxBytes = 100, txBytes = 100, unansweredSinceMs = 0L),
            Sample(100, 9_000, 600), nowMs = 600_000, suspendedMs = 570_000,
            noNetworkForMs = 0, keepaliveSec = keepalive,
        )
        assertEquals(Verdict.SKIPPED_AFTER_SUSPEND, verdict)
        assertNull(afterSleep.unansweredSinceMs)
        // wg-go re-handshakes on its next send and the peer answers.
        val (_, next) = TunnelMonitor.assess(
            afterSleep, Sample(400, 9_400, 2), nowMs = 610_000, suspendedMs = 0,
            noNetworkForMs = 0, keepaliveSec = keepalive,
        )
        assertEquals(Verdict.ALIVE, next)
        // Even a no-network reading across a sleep is deferred one cycle.
        assertEquals(
            Verdict.SKIPPED_AFTER_SUSPEND,
            TunnelMonitor.assess(Liveness(), null, 0, 570_000, 600_000, keepalive).second,
        )
    }

    // ── Parsing ─────────────────────────────────────────────────────────

    @Test
    fun `the UAPI dump yields the counters and the newest handshake age`() {
        val dump = """
            private_key=aa
            public_key=bb
            last_handshake_time_sec=1700000000
            last_handshake_time_nsec=12
            rx_bytes=1234
            tx_bytes=5678
            persistent_keepalive_interval=25
        """.trimIndent()
        assertEquals(Sample(1234, 5678, 30), TunnelMonitor.parseSample(dump, nowEpochSec = 1_700_000_030))
        assertEquals(Sample(0, 0, null), TunnelMonitor.parseSample("last_handshake_time_sec=0", 1_700_000_030))
    }
}
