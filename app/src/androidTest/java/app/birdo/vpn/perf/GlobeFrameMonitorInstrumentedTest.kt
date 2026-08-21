package app.birdo.vpn.perf

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 */
@RunWith(AndroidJUnit4::class)
class GlobeFrameMonitorInstrumentedTest {

    private companion object {
        const val DRIVEN_FRAMES = 150
        const val DEADLINE_MS = 20_000L
    }

    /**
     * Redraws for a bounded number of vsyncs. Does not depend on the device's
     * animator duration scale, which a test device may legitimately have at 0.
     */
    @Composable
    private fun FiniteFrameDriver() {
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            repeat(DRIVEN_FRAMES) {
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

        val snap = monitor.snapshot(60f)
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
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val view = LocalView.current
                    // Tagged, then immediately disposed — the retag in onDispose
                    // is what makes a globe-off baseline mean anything.
                    DisposableEffect(Unit) {
                        GlobePerfState.set(view, GlobePerf.STATE_FULL)
                        GlobePerfState.set(view, GlobePerf.STATE_OFF)
                        onDispose { }
                    }
                    trackGlobeFrames(monitor)
                    FiniteFrameDriver()
                }
            }
            awaitFrames(monitor, atLeast = 20)
        }

        val snap = monitor.snapshot(60f)
        assertTrue(
            "frames should be attributed to `off` after the retag, got " +
                "full=${snap.tier(GlobeTag.FULL)!!.frames} " +
                "off=${snap.tier(GlobeTag.OFF)!!.frames}",
            snap.tier(GlobeTag.OFF)!!.frames > snap.tier(GlobeTag.FULL)!!.frames,
        )
    }

    /** Polls off the main thread until enough frames land, or the deadline passes. */
    private fun awaitFrames(monitor: GlobeFrameMonitor, atLeast: Int) {
        val deadline = System.nanoTime() + DEADLINE_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            val total = monitor.snapshot(60f).tiers.sumOf { it.frames }
            if (total >= atLeast) return
            Thread.sleep(100)
        }
    }
}
