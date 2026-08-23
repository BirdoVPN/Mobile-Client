package app.birdo.vpn.perf

import androidx.metrics.performance.FrameData
import androidx.metrics.performance.FrameDataApi24
import androidx.metrics.performance.FrameDataApi31
import androidx.metrics.performance.JankStats

/**
 * Which histogram a frame belongs to. Fixed, small, and derived only from the
 * globe's own quality tier — see [GlobePerf] for why the label set is closed.
 */
internal enum class GlobeTag(val label: String, val stateValue: String) {
    FULL("globe FULL", GlobePerf.STATE_FULL),
    LITE("globe LITE", GlobePerf.STATE_LITE),
    OFF("globe OFF ", GlobePerf.STATE_OFF),
    ;

    companion object {
        fun of(stateValue: String?): GlobeTag = when (stateValue) {
            GlobePerf.STATE_FULL -> FULL
            GlobePerf.STATE_LITE -> LITE
            else -> OFF
        }
    }
}

/** One tier's numbers, already reduced. µs everywhere. */
internal data class TierStats(
    val tag: GlobeTag,
    val frames: Long,
    val jankPercent: Float,
    val meanUs: Int,
    val p50Us: Int,
    val p90Us: Int,
    val p99Us: Int,
    val maxUs: Int,
)

internal data class PerfSnapshot(
    /**
     * True when the platform gave us `FrameMetrics` including GPU work
     * (API 31+). Below that the durations are the CPU half of the frame only,
     * and a fill-rate problem is invisible except through [TierStats.jankPercent].
     * The overlay says which, because a number whose meaning changes with the
     * OS version and does not say so is a trap.
     */
    val includesGpu: Boolean,
    val refreshHz: Float,
    val tiers: List<TierStats>,
) {
    fun tier(tag: GlobeTag): TierStats? = tiers.firstOrNull { it.tag == tag }
}

/**
 * Accumulates JankStats frames into one [FrameHistogram] per globe tier.
 *
 * Everything here runs on the UI thread (JankStats delivers on it) and the
 * overlay reads on the UI thread too, so there is no synchronisation and no
 * copy. [revision] lets the overlay poll on a slow timer instead of
 * recomposing per frame — an overlay that recomposes 90 times a second is
 * measuring itself.
 */
internal class GlobeFrameMonitor {

    private val histograms = Array(GlobeTag.entries.size) { FrameHistogram() }

    /** Bumped on every recorded frame; the overlay uses it as a cheap dirty bit. */
    var revision: Int = 0
        private set

    var includesGpu: Boolean = false
        private set

    val listener = JankStats.OnFrameListener { frame -> record(frame) }

    /**
     * @param frame is JankStats' REUSED volatile instance — read it here, never
     *   retain it.
     */
    private fun record(frame: FrameData) {
        // Prefer the widest window the platform offers:
        //  - API 31+  frameDurationTotalNanos: CPU + GPU, i.e. the real cost.
        //  - API 24+  frameDurationCpuNanos:  everything up to handing off to
        //             the GPU. Blind to fill rate.
        //  - fallback frameDurationUiNanos:   the UI-thread portion only.
        val nanos: Long = when (frame) {
            is FrameDataApi31 -> {
                includesGpu = true
                frame.frameDurationTotalNanos
            }
            is FrameDataApi24 -> frame.frameDurationCpuNanos
            else -> frame.frameDurationUiNanos
        }

        // Indexed loop: `frame.states` is a List and this runs on the UI thread
        // inside the frame we are trying not to disturb.
        var stateValue: String? = null
        val states = frame.states
        var i = 0
        while (i < states.size) {
            val s = states[i]
            if (s.key == GlobePerf.STATE_KEY) {
                stateValue = s.value
                break
            }
            i++
        }

        histograms[GlobeTag.of(stateValue).ordinal]
            .record((nanos / 1_000L).toInt(), frame.isJank)
        revision++
    }

    fun reset() {
        for (h in histograms) h.reset()
        revision++
    }

    fun snapshot(refreshHz: Float): PerfSnapshot = PerfSnapshot(
        includesGpu = includesGpu,
        refreshHz = refreshHz,
        tiers = GlobeTag.entries.map { tag ->
            val h = histograms[tag.ordinal]
            TierStats(
                tag = tag,
                frames = h.frames,
                jankPercent = h.jankPercent(),
                meanUs = h.meanUs(),
                p50Us = h.percentileUs(0.50),
                p90Us = h.percentileUs(0.90),
                p99Us = h.percentileUs(0.99),
                maxUs = h.maxUs,
            )
        },
    )
}
