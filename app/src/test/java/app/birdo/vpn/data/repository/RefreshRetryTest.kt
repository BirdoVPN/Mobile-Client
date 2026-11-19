package app.birdo.vpn.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MR-1771 / MR-2500: the waits after a refresh that kept the session
 * ([RefreshRetry]). BirdoRepositoryTest pins that the repository applies them;
 * the iOS twin is RefreshAnswerTests (RefreshRetry, RefreshHoldOff).
 */
class RefreshRetryTest {

    @Test
    fun `the backoff doubles from 2 s per failure, is capped at 5 minutes, and jitters in the upper half`() {
        val ceilings = listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 128_000L, 256_000L, 300_000L, 300_000L)
        for ((i, ceiling) in ceilings.withIndex()) {
            val failures = i + 1
            assertEquals("failures=$failures, low edge", ceiling / 2, RefreshRetry.backoffMs(failures, 0.0))
            assertEquals("failures=$failures, high edge", ceiling, RefreshRetry.backoffMs(failures, 1.0))
            val mid = RefreshRetry.backoffMs(failures, 0.5)
            assertTrue("failures=$failures, mid $mid", mid in (ceiling / 2)..ceiling)
        }
    }

    @Test
    fun `the backoff is never zero and never past the cap, whatever the count or the draw`() {
        for (failures in listOf(Int.MIN_VALUE, -1, 0, 1, 50, 1_000, Int.MAX_VALUE)) {
            for (draw in listOf(-1.0, 0.0, 0.999, 1.0, 7.0, Double.NaN)) {
                val wait = RefreshRetry.backoffMs(failures, draw)
                assertTrue(
                    "failures=$failures draw=$draw: $wait",
                    wait in (RefreshRetry.BACKOFF_BASE_MS / 2)..RefreshRetry.BACKOFF_CAP_MS,
                )
            }
        }
    }

    @Test
    fun `a 429 waits the Retry-After header, else details retryAfter, else 60 s`() {
        val body = """{"statusCode":429,"error":"Too Many Requests","details":{"code":"rate_limited","retryAfter":42}}"""
        assertEquals(15_000L, RefreshRetry.rateLimitedWaitMs("15", body))
        assertEquals(42_000L, RefreshRetry.rateLimitedWaitMs(null, body))
        // An HTTP date is not a wait this client trusts: the body's then.
        assertEquals(42_000L, RefreshRetry.rateLimitedWaitMs("Wed, 21 Oct 2026 07:28:00 GMT", body))
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs(null, null))
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs(null, """{"statusCode":429,"message":"Too many requests"}"""))
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs("0", """{"details":{"retryAfter":0}}"""))
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs(null, "<html>429</html>"))
        // `retryAfterSeconds` is the connect refusals' key, not this one.
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs(null, """{"details":{"retryAfterSeconds":42}}"""))
        // Clamped like every server wait: ten minutes at most.
        assertEquals(RetryAfter.MAX_MS, RefreshRetry.rateLimitedWaitMs(null, """{"details":{"retryAfter":86400}}"""))
        assertEquals(RetryAfter.MAX_MS, RefreshRetry.rateLimitedWaitMs("86400", body))
    }
}
