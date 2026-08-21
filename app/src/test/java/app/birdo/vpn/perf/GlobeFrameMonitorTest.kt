package app.birdo.vpn.perf

import androidx.metrics.performance.FrameData
import androidx.metrics.performance.FrameDataApi24
import androidx.metrics.performance.FrameDataApi31
import androidx.metrics.performance.StateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attribution is the whole point of this instrumentation: a whole-app frame
 * number cannot answer "is the globe too heavy". These tests drive the real
 * JankStats listener with synthetic [FrameData] (all its constructors are
 * public and none of it touches the Android framework) and check that frames
 * land in the histogram their state label says they should.
 */
class GlobeFrameMonitorTest {

    private fun states(vararg pairs: Pair<String, String>) =
        pairs.map { StateInfo(it.first, it.second) }

    private fun api31(us: Long, jank: Boolean, globe: String?) = FrameDataApi31(
        frameStartNanos = 0L,
        frameDurationUiNanos = us * 1_000 / 2,
        frameDurationCpuNanos = us * 1_000 * 3 / 4,
        frameDurationTotalNanos = us * 1_000,
        frameOverrunNanos = 0L,
        isJank = jank,
        states = if (globe == null) emptyList() else states(GlobePerf.STATE_KEY to globe),
    )

    // ── Which duration the platform gives us ─────────────────────────────

    @Test
    fun `api31 frames are measured on the total duration so gpu work counts`() {
        val m = GlobeFrameMonitor()
        m.listener.onFrame(
            FrameDataApi31(
                frameStartNanos = 0L,
                frameDurationUiNanos = 2_000_000,
                frameDurationCpuNanos = 4_000_000,
                frameDurationTotalNanos = 25_000_000,
                frameOverrunNanos = 8_000_000,
                isJank = true,
                states = states(GlobePerf.STATE_KEY to GlobePerf.STATE_FULL),
            ),
        )
        val snap = m.snapshot(60f)
        assertTrue("api31 must be reported as GPU-inclusive", snap.includesGpu)
        val full = snap.tier(GlobeTag.FULL)!!
        assertEquals(1L, full.frames)
        // 25 ms, reported at bucket resolution (1 ms above 16 ms).
        assertTrue("expected ~25ms, got ${full.p50Us}us", full.p50Us in 25_000..26_000)
    }

    @Test
    fun `pre api31 frames fall back to cpu duration and say so`() {
        val m = GlobeFrameMonitor()
        m.listener.onFrame(
            FrameDataApi24(
                frameStartNanos = 0L,
                frameDurationUiNanos = 2_000_000,
                frameDurationCpuNanos = 9_000_000,
                isJank = false,
                states = states(GlobePerf.STATE_KEY to GlobePerf.STATE_FULL),
            ),
        )
        val snap = m.snapshot(60f)
        // The HUD prints "cpu only (<API31)" off this flag. On API 29/30 — the
        // oldest devices, i.e. exactly the ones under suspicion — GPU fill-rate
        // cost is NOT in this number, and pretending otherwise would be a lie.
        assertFalse(snap.includesGpu)
        assertTrue(snap.tier(GlobeTag.FULL)!!.p50Us in 9_000..9_250)
    }

    @Test
    fun `a plain FrameData falls back to the ui thread duration`() {
        val m = GlobeFrameMonitor()
        m.listener.onFrame(
            FrameData(
                frameStartNanos = 0L,
                frameDurationUiNanos = 7_000_000,
                isJank = false,
                states = states(GlobePerf.STATE_KEY to GlobePerf.STATE_LITE),
            ),
        )
        assertFalse(m.snapshot(60f).includesGpu)
        assertTrue(m.snapshot(60f).tier(GlobeTag.LITE)!!.p50Us in 7_000..7_250)
    }

    // ── Attribution ──────────────────────────────────────────────────────

    @Test
    fun `frames are partitioned by the globe state label`() {
        val m = GlobeFrameMonitor()
        repeat(10) { m.listener.onFrame(api31(20_000, jank = true, globe = GlobePerf.STATE_FULL)) }
        repeat(20) { m.listener.onFrame(api31(11_000, jank = false, globe = GlobePerf.STATE_LITE)) }
        repeat(30) { m.listener.onFrame(api31(5_000, jank = false, globe = GlobePerf.STATE_OFF)) }

        val snap = m.snapshot(60f)
        assertEquals(10L, snap.tier(GlobeTag.FULL)!!.frames)
        assertEquals(20L, snap.tier(GlobeTag.LITE)!!.frames)
        assertEquals(30L, snap.tier(GlobeTag.OFF)!!.frames)
        assertEquals(100f, snap.tier(GlobeTag.FULL)!!.jankPercent, 1e-3f)
        assertEquals(0f, snap.tier(GlobeTag.OFF)!!.jankPercent, 1e-3f)
    }

    @Test
    fun `an unlabelled or foreign-labelled frame counts as globe off`() {
        val m = GlobeFrameMonitor()
        m.listener.onFrame(api31(5_000, jank = false, globe = null))
        m.listener.onFrame(
            FrameDataApi31(
                0L, 1_000_000, 1_000_000, 5_000_000, 0L, false,
                states("someOtherFeature" to "whatever"),
            ),
        )
        val snap = m.snapshot(60f)
        // Defaulting the unknown case to OFF is the conservative choice: it can
        // only ever make the globe look CHEAPER than it is, never dearer.
        assertEquals(2L, snap.tier(GlobeTag.OFF)!!.frames)
        assertEquals(0L, snap.tier(GlobeTag.FULL)!!.frames)
    }

    @Test
    fun `the globe on minus globe off delta is the number the owner reads`() {
        val m = GlobeFrameMonitor()
        // Same device, same screen: globe on costs ~18ms/frame, off ~6ms.
        repeat(200) { m.listener.onFrame(api31(18_000, jank = true, globe = GlobePerf.STATE_FULL)) }
        repeat(200) { m.listener.onFrame(api31(6_000, jank = false, globe = GlobePerf.STATE_OFF)) }
        val snap = m.snapshot(60f)
        val delta = snap.tier(GlobeTag.FULL)!!.p90Us - snap.tier(GlobeTag.OFF)!!.p90Us
        assertTrue("expected a ~12ms attributable delta, got ${delta}us", delta in 11_500..12_500)
    }

    @Test
    fun `reset clears every tier`() {
        val m = GlobeFrameMonitor()
        repeat(5) { m.listener.onFrame(api31(9_000, jank = false, globe = GlobePerf.STATE_FULL)) }
        m.reset()
        for (t in m.snapshot(60f).tiers) {
            assertEquals(0L, t.frames)
            assertEquals(-1, t.p99Us)
        }
    }

    @Test
    fun `revision advances so the hud can poll instead of recomposing per frame`() {
        val m = GlobeFrameMonitor()
        val before = m.revision
        repeat(3) { m.listener.onFrame(api31(9_000, jank = false, globe = GlobePerf.STATE_FULL)) }
        assertEquals(before + 3, m.revision)
    }

    // ── The label set never grows ────────────────────────────────────────

    @Test
    fun `every tag maps to one of the three allowed state values`() {
        assertEquals(listOf("full", "lite", "off"), GlobePerf.STATE_VALUES)
        assertEquals(
            GlobePerf.STATE_VALUES.toSet(),
            GlobeTag.entries.map { it.stateValue }.toSet(),
        )
        for (v in GlobePerf.STATE_VALUES) {
            assertEquals(v, GlobeTag.of(v).stateValue)
        }
    }
}
