package app.birdo.vpn.perf

import kotlin.math.ceil

/**
 * A fixed-bucket histogram of frame durations, in microseconds.
 *
 * ## Why a histogram and not a list of samples
 *
 * The obvious implementation of "record every frame so we can compute
 * percentiles" is an array of `(timestampNanos, durationNanos)`. On a VPN
 * client that array is not a performance log, it is **a second-by-second record
 * of when the user was looking at their phone** — precisely the kind of
 * timeline this product exists not to build (see `PrivacyBoundaryTest`,
 * P6-CLI-X-01, where an every-60s connection report was deleted rather than
 * gated for the same reason).
 *
 * Bucket counts cannot reconstruct a timeline. There is no ordering, no
 * wall-clock, and no way to tell one session's minute from another's. That is a
 * privacy property first and a memory property second — but it is also why this
 * class costs 868 bytes and zero allocations per frame, which matters when the
 * thing being measured is the frame loop itself.
 *
 * ## Resolution
 *
 * Bucket widths are 250 µs up to 24 ms — a quarter of a millisecond either side
 * of every refresh deadline we care about (8.33 / 11.1 / 16.67 ms) — then
 * coarsen as the numbers stop needing precision. A reported percentile is the UPPER edge
 * of the bucket that contains it, so every figure this class returns is an
 * over-estimate by less than one bucket width, never an under-estimate. A
 * measurement tool that flatters the thing it measures is worse than no tool.
 */
internal class FrameHistogram {

    private val counts = IntArray(BUCKET_COUNT)

    var frames: Long = 0L
        private set

    /** Frames JankStats classified as jank against the display's real deadline. */
    var janky: Long = 0L
        private set

    var sumUs: Long = 0L
        private set

    var maxUs: Int = 0
        private set

    fun record(durationUs: Int, jank: Boolean) {
        val us = if (durationUs < 0) 0 else durationUs
        counts[bucketOf(us)]++
        frames++
        sumUs += us
        if (jank) janky++
        if (us > maxUs) maxUs = us
    }

    fun reset() {
        counts.fill(0)
        frames = 0L
        janky = 0L
        sumUs = 0L
        maxUs = 0
    }

    /**
     * Upper edge, in µs, of the bucket containing the [p]-quantile
     * (`p` in 0..1), or -1 when nothing has been recorded.
     *
     * Nearest-rank, rounding UP: `p99` of 100 frames is the 99th slowest frame,
     * not an interpolation that would quietly discard the tail.
     */
    fun percentileUs(p: Double): Int {
        if (frames == 0L) return -1
        val rank = ceil(p * frames).toLong().coerceIn(1L, frames)
        var seen = 0L
        var i = 0
        while (i < BUCKET_COUNT) {
            seen += counts[i]
            if (seen >= rank) return bucketUpperUs(i)
            i++
        }
        return bucketUpperUs(BUCKET_COUNT - 1)
    }

    /**
     * The number the owner should NOT be shown on its own. Kept because a mean
     * next to p99 makes the gap between them visible, which is the whole point.
     */
    fun meanUs(): Int = if (frames == 0L) -1 else (sumUs / frames).toInt()

    fun jankPercent(): Float = if (frames == 0L) 0f else janky * 100f / frames

    companion object {
        // 0 .. 24 ms      @ 250 µs   -> buckets   0..95
        // 24 .. 64 ms     @ 1 ms     -> buckets  96..135
        // 64 .. 256 ms    @ 4 ms     -> buckets 136..183
        // 256 .. 2048 ms  @ 32 ms    -> buckets 184..239
        // >= 2048 ms                 -> bucket  240 (overflow)
        //
        // The fine region runs to 24 ms, not 16: the 60 Hz deadline is 16.67 ms
        // and it must sit INSIDE the quarter-millisecond region, or "just made
        // 60 fps" and "just missed it" fall in the same bucket — on 60 Hz
        // hardware, which is what the old devices under suspicion are.
        const val BUCKET_COUNT = 241

        private const val T1 = 24_000
        private const val T2 = 64_000
        private const val T3 = 256_000
        private const val T4 = 2_048_000

        fun bucketOf(us: Int): Int = when {
            us < T1 -> us / 250
            us < T2 -> 96 + (us - T1) / 1_000
            us < T3 -> 136 + (us - T2) / 4_000
            us < T4 -> 184 + (us - T3) / 32_000
            else -> 240
        }

        fun bucketUpperUs(i: Int): Int = when {
            i < 96 -> (i + 1) * 250
            i < 136 -> T1 + (i - 95) * 1_000
            i < 184 -> T2 + (i - 135) * 4_000
            i < 240 -> T3 + (i - 183) * 32_000
            else -> T4
        }
    }
}
