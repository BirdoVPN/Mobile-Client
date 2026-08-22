package app.birdo.vpn.perf

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.metrics.performance.JankStats
import kotlinx.coroutines.delay

/**
 * Debug frame-timing HUD for the Home-screen globe.
 *
 * Renders nothing, tracks nothing and allocates nothing unless
 * [GlobePerf.ENABLED] — in a stock release build that is a `static final false`
 * and R8 deletes the lot.
 *
 * Read it as a paired comparison, not as absolute numbers:
 *
 *   * `globe FULL` / `globe LITE` are frames rendered with the globe on screen.
 *   * `globe OFF` is the same screen with the globe suppressed (`hide` below).
 *   * `delta` is the globe's own contribution, and is the only figure here that
 *     survives being taken from a debug build.
 *
 * Absolute p99 from a debug build overstates the real cost: debug is
 * unminified, `debuggable=true` disables several ART optimisations, and Compose
 * runs extra bookkeeping. For absolute numbers either build with
 * `-PperfOverlay=true` on top of a real release, or read Play Vitals.
 */
@Composable
fun GlobePerfOverlay(modifier: Modifier = Modifier) {
    if (!GlobePerf.ENABLED) return

    val view = LocalView.current
    val monitor = remember { GlobeFrameMonitor() }
    trackGlobeFrames(monitor)

    val refreshHz = view.display?.refreshRate ?: 60f
    var snapshot by remember { mutableStateOf(monitor.snapshot(refreshHz)) }
    // Which histogram holds "the globe on". Read from the tier the globe
    // actually rendered at, never from the override flag — see
    // [GlobePerfState.lastActive].
    var onTag by remember { mutableStateOf(GlobePerfState.lastActive?.let { GlobeTag.of(it) }) }
    // 2 Hz. Fast enough to watch a change land, slow enough that the HUD is not
    // a meaningful share of the frames it is reporting on. `revision` skips the
    // reduction entirely when no frame has landed since the last poll, so a
    // still screen costs no recomposition at all.
    LaunchedEffect(monitor) {
        var seen = -1
        while (true) {
            delay(500)
            onTag = GlobePerfState.lastActive?.let { GlobeTag.of(it) }
            val revision = monitor.revision
            if (revision != seen) {
                seen = revision
                snapshot = monitor.snapshot(refreshHz)
            }
        }
    }

    val expanded by GlobePerfControls.overlayExpanded
    val forcedLite by GlobePerfControls.forcedLite
    val hidden by GlobePerfControls.globeHidden

    Column(
        modifier = modifier
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xE6000000))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Hud(
            text = "GLOBE PERF  ${refreshHz.toInt()}Hz  " +
                if (snapshot.includesGpu) "cpu+gpu" else "cpu only (<API31)",
            color = Color(0xFF7BB2E6),
            bold = true,
            modifier = Modifier.clickable { GlobePerfControls.overlayExpanded.value = !expanded },
        )

        if (!expanded) return@Column

        Hud("            n     p50    p90    p99   jank", Color(0xFF8B98AD))
        for (tag in GlobeTag.entries) {
            val t = snapshot.tier(tag) ?: continue
            Hud(
                text = tag.label + " " + pad(t.frames.toString(), 6) +
                    ms(t.p50Us) + ms(t.p90Us) + ms(t.p99Us) +
                    pad(String.format("%.1f%%", t.jankPercent), 7),
                color = if (t.frames == 0L) Color(0xFF5A6580) else Color(0xFFE4EAF4),
            )
        }

        val activeTag = onTag?.takeIf { it != GlobeTag.OFF }
        val on = activeTag?.let { snapshot.tier(it) }
        val off = snapshot.tier(GlobeTag.OFF)
        if (activeTag != null && on != null && off != null && on.frames > 0 && off.frames > 0) {
            Hud(
                // Names the tier, because on an auto-tiered low-RAM device the
                // row is LITE-minus-OFF and reading it as FULL is the whole
                // measurement wrong.
                text = ("delta " + activeTag.label.removePrefix("globe ").trim()).padEnd(17) +
                    delta(on.p50Us - off.p50Us) +
                    delta(on.p90Us - off.p90Us) +
                    delta(on.p99Us - off.p99Us),
                color = Color(0xFF34D399),
                bold = true,
            )
        } else {
            Hud("delta: toggle `hide` to collect an OFF baseline", Color(0xFF8B98AD))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (hidden) "[show globe]" else "[hide globe]") {
                GlobePerfControls.globeHidden.value = !hidden
            }
            Btn(
                when (forcedLite) {
                    null -> "[tier auto]"
                    true -> "[tier LITE]"
                    false -> "[tier FULL]"
                },
            ) {
                GlobePerfControls.forcedLite.value = when (forcedLite) {
                    null -> false
                    false -> true
                    else -> null
                }
            }
            Btn("[reset]") { monitor.reset() }
        }
    }
}

/**
 * Starts JankStats on the hosting window and feeds every frame to [monitor] for
 * as long as this stays in composition.
 *
 * Separate from the HUD so `GlobeFrameMonitorInstrumentedTest` exercises the
 * REAL attach path — including the [GlobePerfState.flush] ordering, which is
 * the part that would otherwise fail silently by labelling every frame `off`.
 */
@Composable
internal fun trackGlobeFrames(monitor: GlobeFrameMonitor) {
    val view = LocalView.current
    DisposableEffect(view, monitor) {
        val window = view.context.findWindow()
        val stats: JankStats? = window?.let { JankStats.createAndTrack(it, monitor.listener) }
        // A PerformanceMetricsState holder has no live `state` until JankStats
        // is tracking, and the globe composed before we got here — so its tag
        // has to be re-applied or the whole first session lands in `off`.
        GlobePerfState.flush()
        onDispose { stats?.isTrackingEnabled = false }
    }
}

@Composable
private fun Btn(label: String, onClick: () -> Unit) {
    Hud(label, Color(0xFFFFB454), modifier = Modifier.clickable(onClick = onClick))
}

@Composable
private fun Hud(
    text: String,
    color: Color,
    bold: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier,
    )
}

/** Right-aligned millisecond column, one decimal. `-1 µs` means "no samples". */
private fun ms(us: Int): String =
    if (us < 0) pad("-", 7) else pad(String.format("%.1f", us / 1000f), 7)

/**
 * Right-aligned millisecond column for a DIFFERENCE, always signed.
 *
 * [ms] renders any negative as "-" because in an absolute column a negative is
 * the no-samples sentinel. In the delta row a negative is a real and important
 * result — the globe cost less than the noise floor — and collapsing it into
 * the same "-" as "no data" hides the one outcome that would end the
 * investigation.
 */
private fun delta(us: Int): String =
    pad(String.format("%+.1f", us / 1000f), 7)

private fun pad(s: String, width: Int): String =
    if (s.length >= width) s else " ".repeat(width - s.length) + s

private fun Context.findWindow(): Window? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c.window
        c = c.baseContext
    }
    return null
}
