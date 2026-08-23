package app.birdo.vpn.perf

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the frame instrumentation works, as opposed to compiles.
 *
 * `GlobeFrameMonitorTest` (JVM) drives the listener with synthetic FrameData and
 * proves the reduction. What it cannot reach is the one part that would fail
 * SILENTLY: JankStats has no live `PerformanceMetricsState` until it is tracking
 * a Window, and the globe composes BEFORE the HUD attaches. If the
 * flush-on-attach ordering in [trackGlobeFrames] were wrong, everything would
 * still build, every JVM test would still pass, and every frame would be
 * labelled `off` — the HUD would confidently report the globe costing nothing.
 *
 * This is NOT a performance measurement. Frame times from an x86_64 emulator on
 * a desktop GPU say nothing about a 2019 ARM phone and must never be quoted as
 * if they did. The assertions are about labelling and delivery only.
 *
 * The frame driver is deliberately FINITE. An endless `withFrameNanos` loop
 * keeps the main looper permanently busy, and `ActivityScenario` then blocks
 * forever waiting for idle — which is exactly how the first version of this
 * test hung instead of failing.
 *
 * Every read of the monitor goes through [snapshotOnUi]. `GlobeFrameMonitor`
 * documents itself as unsynchronised because JankStats delivers on the UI
 * thread and the HUD reads there too; a test polling it from the
 * instrumentation thread would be racing `counts[]`, `frames`, `sumUs` and
 * `maxUs` against the very frames it is waiting for, and would be asserting
 * against a threading contract the production code does not have.
 */
@RunWith(AndroidJUnit4::class)
class GlobeFrameMonitorInstrumentedTest {

    private companion object {
        const val DRIVEN_FRAMES = 150

        /**
         * The disposal test needs frames BEFORE and AFTER the globe leaves the
         * composition, so its driver has to outlive both waits. Still finite,
         * for the reason in the class kdoc.
         */
        const val LONG_RUN_FRAMES = 600
        const val DEADLINE_MS = 20_000L
        const val POLL_MS = 50L
    }

    /**
     * Redraws for a bounded number of vsyncs. Does not depend on the device's
     * animator duration scale, which a test device may legitimately have at 0.
     */
    @Composable
    private fun FiniteFrameDriver(frames: Int = DRIVEN_FRAMES) {
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            repeat(frames) {
                withFrameNanos { }
                tick++
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(color = Color(0x22FFFFFF), radius = 4f + (tick % 8))
        }
    }

    @Test
    fun activityScenarioCanHostComposeContentAtAll() {
        // Sanity gate. If this hangs or fails, the two tests below tell you
        // nothing about the instrumentation.
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.use { it.onActivity { a -> a.setContent { FiniteFrameDriver() } } }
    }

    @Test
    fun framesArriveLabelledEvenThoughTheTagIsSetBeforeJankStatsAttaches() {
        val monitor = GlobeFrameMonitor()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    // Deliberate ordering, matching the Home screen: the globe's
                    // DisposableEffect tags the state, and only afterwards does
                    // the HUD's effect start JankStats.
                    val view = LocalView.current
                    DisposableEffect(view) {
                        GlobePerfState.set(view, GlobePerf.STATE_FULL)
                        onDispose { GlobePerfState.set(view, GlobePerf.STATE_OFF) }
                    }
                    trackGlobeFrames(monitor)
                    FiniteFrameDriver()
                }
            }
            awaitFrames(monitor, atLeast = 20)
        }

        val snap = snapshotOnUi(monitor)
        val full = snap.tier(GlobeTag.FULL)!!
        val off = snap.tier(GlobeTag.OFF)!!
        assertTrue(
            "no frames reached the monitor — JankStats never attached to the " +
                "window (full=${full.frames} lite=${snap.tier(GlobeTag.LITE)!!.frames} " +
                "off=${off.frames})",
            full.frames + off.frames >= 20,
        )
        assertTrue(
            "frames arrived but were labelled `off` while the state said `full` " +
                "— the flush-on-attach ordering is broken, and the HUD would " +
                "report the globe as free (full=${full.frames}, off=${off.frames})",
            full.frames > off.frames,
        )
        assertTrue(
            "p50 is not a real duration: ${full.p50Us}us",
            full.p50Us in 1..2_000_000,
        )
    }

    @Test
    fun leavingCompositionRetagsToOffSoTheBaselineIsClean() {
        val monitor = GlobeFrameMonitor()
        // The globe really does leave the composition here. The previous
        // version set FULL and then OFF inside one effect whose onDispose was
        // empty, so it proved that of two `set` calls in a row the second one
        // wins, and nothing at all about disposal. The retag in onDispose is
        // the only thing that makes an OFF baseline clean, so it has to be the
        // thing under test.
        val globeComposed = mutableStateOf(true)

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val view = LocalView.current
                    if (globeComposed.value) {
                        DisposableEffect(view) {
                            GlobePerfState.set(view, GlobePerf.STATE_FULL)
                            onDispose { GlobePerfState.set(view, GlobePerf.STATE_OFF) }
                        }
                    }
                    trackGlobeFrames(monitor)
                    FiniteFrameDriver(frames = LONG_RUN_FRAMES)
                }
            }

            // Phase 1: the globe is composed, so frames land under FULL.
            awaitTier(monitor, GlobeTag.FULL, atLeast = 20L)
            val atExit = snapshotOnUi(monitor)
            val fullAtExit = atExit.tier(GlobeTag.FULL)!!.frames
            val offAtExit = atExit.tier(GlobeTag.OFF)!!.frames

            // Phase 2: remove it from the composition, keep driving frames.
            scenario.onActivity { globeComposed.value = false }

            // Let the disposal land first. Frames already in flight when the
            // flag flipped are still legitimately FULL, so they are not part of
            // the measurement window.
            awaitTier(monitor, GlobeTag.OFF, atLeast = offAtExit + 20L)
            val settled = snapshotOnUi(monitor)
            val fullSettled = settled.tier(GlobeTag.FULL)!!.frames
            val offSettled = settled.tier(GlobeTag.OFF)!!.frames

            // Phase 3: a clean window. Nothing may reach FULL any more.
            awaitTier(monitor, GlobeTag.OFF, atLeast = offSettled + 20L)
            val snap = snapshotOnUi(monitor)
            val off = snap.tier(GlobeTag.OFF)!!.frames
            assertTrue(
                "no frames were attributed to `off` after the globe left the " +
                    "composition (off at exit=" + offAtExit + ", off now=" + off +
                    ") — onDispose never retagged",
                off - offAtExit >= 20L,
            )
            assertEquals(
                "frames were still attributed to `full` " + (off - offSettled) +
                    " frames after the globe left the composition (full at " +
                    "exit=" + fullAtExit + ") — the onDispose retag did not " +
                    "stick, so every globe-off baseline is contaminated with " +
                    "globe-on frames",
                fullSettled,
                snap.tier(GlobeTag.FULL)!!.frames,
            )
        }
    }

    /**
     * Reads the monitor ON THE UI THREAD, where JankStats writes it.
     * `runOnMainSync` blocks until the main looper has run the block, which is
     * also what gives the calling thread a happens-before edge onto every frame
     * recorded so far.
     */
    private fun snapshotOnUi(monitor: GlobeFrameMonitor): PerfSnapshot {
        lateinit var snap: PerfSnapshot
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            snap = monitor.snapshot(60f)
        }
        return snap
    }

    /** Waits until [atLeast] frames have landed in total, or the deadline passes. */
    private fun awaitFrames(monitor: GlobeFrameMonitor, atLeast: Int) =
        await(monitor) { snap -> snap.tiers.sumOf { it.frames } >= atLeast }

    /** Waits until [tag] holds [atLeast] frames, or the deadline passes. */
    private fun awaitTier(monitor: GlobeFrameMonitor, tag: GlobeTag, atLeast: Long) =
        await(monitor) { snap -> snap.tier(tag)!!.frames >= atLeast }

    private fun await(monitor: GlobeFrameMonitor, done: (PerfSnapshot) -> Boolean) {
        val deadline = System.nanoTime() + DEADLINE_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (done(snapshotOnUi(monitor))) return
            Thread.sleep(POLL_MS)
        }
    }
}
