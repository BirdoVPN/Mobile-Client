package app.birdo.vpn.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The histogram is the whole measurement. If its percentiles are wrong the tool
 * is worse than nothing — it produces a confident number that sends the owner
 * optimising the wrong thing.
 *
 * These tests check the two properties that matter and are easy to get subtly
 * wrong: the bucket ladder is gapless and monotonic across its whole range, and
 * a reported percentile is never smaller than the true one.
 */
class FrameHistogramTest {

    // ── Bucket ladder ────────────────────────────────────────────────────

    @Test
    fun `bucket ladder is gapless monotonic and covers every duration`() {
        // Every microsecond from 0 to 2.2 s must land in a bucket whose upper
        // edge is >= it, and buckets must never go backwards.
        var previous = -1
        var us = 0
        while (us <= 2_200_000) {
            val b = FrameHistogram.bucketOf(us)
            assertTrue("bucket $b out of range for ${us}us", b in 0 until FrameHistogram.BUCKET_COUNT)
            assertTrue("bucket index went backwards at ${us}us", b >= previous)
            previous = b
            val upper = FrameHistogram.bucketUpperUs(b)
            assertTrue(
                "bucket $b upper edge ${upper}us under-reports a ${us}us frame",
                upper >= us || b == FrameHistogram.BUCKET_COUNT - 1,
            )
            us += 37 // a prime-ish step so boundaries are hit off-grid too
        }
    }

    @Test
    fun `resolution around the refresh deadlines is a quarter of a millisecond`() {
        // 120Hz, 90Hz and 60Hz deadlines. The bucket containing each must be
        // narrow enough that "just made it" and "just missed" are distinct.
        for (deadlineUs in intArrayOf(8_333, 11_111, 16_666)) {
            val b = FrameHistogram.bucketOf(deadlineUs)
            val width = FrameHistogram.bucketUpperUs(b) -
                (if (b == 0) 0 else FrameHistogram.bucketUpperUs(b - 1))
            assertEquals("deadline ${deadlineUs}us sits in a ${width}us bucket", 250, width)
        }
    }

    // ── Percentiles ──────────────────────────────────────────────────────

    @Test
    fun `empty histogram reports no samples rather than zero`() {
        val h = FrameHistogram()
        // Zero would read as "every frame is instant" on the HUD.
        assertEquals(-1, h.percentileUs(0.50))
        assertEquals(-1, h.percentileUs(0.99))
        assertEquals(-1, h.meanUs())
        assertEquals(0f, h.jankPercent(), 0f)
    }

    @Test
    fun `percentiles never under-report the true value`() {
        val rng = Random(7)
        val truth = IntArray(20_000) {
            // A realistic-ish frame distribution: a tight body with a fat tail,
            // which is exactly the shape a mean hides.
            if (rng.nextInt(100) < 3) rng.nextInt(30_000, 400_000) else rng.nextInt(4_000, 14_000)
        }
        val h = FrameHistogram()
        for (v in truth) h.record(v, jank = v > 16_666)
        truth.sort()

        for (p in doubleArrayOf(0.50, 0.90, 0.99)) {
            val exact = truth[(Math.ceil(p * truth.size).toInt() - 1).coerceIn(0, truth.lastIndex)]
            val reported = h.percentileUs(p)
            assertTrue(
                "p$p reported ${reported}us but the true value is ${exact}us — " +
                    "the histogram is flattering the frame loop",
                reported >= exact,
            )
            // ...and not by more than one bucket width.
            val slackUs = if (exact < 24_000) 250 else if (exact < 64_000) 1_000
            else if (exact < 256_000) 4_000 else 32_000
            assertTrue(
                "p$p reported ${reported}us against a true ${exact}us — over one bucket out",
                reported - exact <= slackUs,
            )
        }
    }

    @Test
    fun `a frame past the last bucket is over-reported not truncated`() {
        // The ladder's last bucket is the open-ended `>= 2.048 s` overflow. Its
        // "upper edge" is its lower edge, so reporting it would turn a 3-second
        // freeze into 2.048 s — an UNDER-estimate, which is the one thing the
        // class guarantees it never returns.
        val h = FrameHistogram()
        repeat(98) { h.record(5_000, jank = false) }
        repeat(2) { h.record(3_000_000, jank = true) }
        assertEquals(3_000_000, h.maxUs)
        assertTrue(
            "p99 under-reports a 3.0s frame as ${h.percentileUs(0.99)}us — the " +
                "overflow bucket is being reported at its lower edge",
            h.percentileUs(0.99) >= 3_000_000,
        )
        // And the guarantee still holds in the other direction: it must not
        // invent a number larger than the largest frame actually seen.
        assertTrue(h.percentileUs(0.99) <= h.maxUs)
    }

    @Test
    fun `p99 exposes a tail a mean would hide`() {
        val h = FrameHistogram()
        // 98 smooth frames and 2 frozen ones. p50 says 9ms and looks fine; the
        // mean says 23ms, which is wrong in a different way — it is a number no
        // frame actually took. p99 says 700ms, which is the stutter the user saw.
        repeat(98) { h.record(9_000, jank = false) }
        repeat(2) { h.record(700_000, jank = true) }
        assertTrue("p50 should look healthy", h.percentileUs(0.50) <= 9_250)
        assertTrue("mean is dragged off both real values", h.meanUs() in 22_000..24_000)
        assertTrue(
            "p99 must surface the freeze, got ${h.percentileUs(0.99)}",
            h.percentileUs(0.99) >= 700_000,
        )
        assertEquals(700_000, h.maxUs)
        assertEquals(2f, h.jankPercent(), 1e-3f)
    }

    @Test
    fun `nearest rank rounds up so the slowest frames are never discarded`() {
        val h = FrameHistogram()
        // 10 frames, one slow: p90 is the 9th slowest (fast), p99 -> rank 10 (slow).
        repeat(9) { h.record(5_000, jank = false) }
        h.record(120_000, jank = true)
        assertTrue(h.percentileUs(0.90) <= 5_250)
        assertTrue(h.percentileUs(0.99) >= 120_000)
    }

    // ── Bounded storage (the privacy property, measured) ─────────────────

    @Test
    fun `storage is bounded regardless of how many frames are recorded`() {
        // A million frames is ~5 hours at 60fps. If this class kept samples it
        // would be holding a 5-hour, millisecond-resolution record of when the
        // user was looking at their screen. It keeps BUCKET_COUNT (241) ints.
        val h = FrameHistogram()
        val rng = Random(11)
        repeat(1_000_000) { h.record(rng.nextInt(3_000, 40_000), jank = false) }
        assertEquals(1_000_000L, h.frames)
        assertTrue(h.percentileUs(0.50) in 3_000..40_000)

        val fields = FrameHistogram::class.java.declaredFields
        val arrays = fields.filter { it.type.isArray }
        assertEquals(
            "FrameHistogram must hold exactly one fixed-size array (the bucket " +
                "counts). A second array is how a sample log gets added. Fields: " +
                fields.map { it.name },
            1,
            arrays.size,
        )
        arrays[0].isAccessible = true
        assertEquals(
            "the bucket array grew — storage is no longer O(1) in frames",
            FrameHistogram.BUCKET_COUNT,
            (arrays[0].get(h) as IntArray).size,
        )
    }

    @Test
    fun `reset clears every counter`() {
        val h = FrameHistogram()
        repeat(50) { h.record(20_000, jank = true) }
        h.reset()
        assertEquals(0L, h.frames)
        assertEquals(0L, h.janky)
        assertEquals(0, h.maxUs)
        assertEquals(-1, h.percentileUs(0.99))
    }

    @Test
    fun `negative durations are clamped rather than corrupting the ladder`() {
        val h = FrameHistogram()
        h.record(-5, jank = false)
        assertEquals(1L, h.frames)
        assertEquals(0, h.maxUs)
    }
}
