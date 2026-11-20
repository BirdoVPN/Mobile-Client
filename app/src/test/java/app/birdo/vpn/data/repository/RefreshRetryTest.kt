package app.birdo.vpn.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MR-1771 / MR-2500: the waits after a refresh that kept the session
 * ([RefreshRetry]). BirdoRepositoryTest pins that the repository applies them;
 * the iOS twin is RefreshAnswerTests (RefreshRetry, RefreshHoldOff).
 *
 * One timing table with Desktop #467 (lead decision L2, 2026-11-19;
 * `refresh_policy.rs` BACKOFF_BASE / BACKOFF_CAP, `error.rs`
 * RETRY_AFTER_MAX_SECS): the backoff is 1 s doubled per failure, capped at
 * 30 s, with "equal jitter"; a wait the server names is clamped to 1 s .. 60 s.
 */
class RefreshRetryTest {

    @Test
    fun `the backoff doubles from 1 s per failure, is capped at 30 s, and jitters in the upper half`() {
        assertEquals(1_000L, RefreshRetry.BACKOFF_BASE_MS)
        assertEquals(30_000L, RefreshRetry.BACKOFF_CAP_MS)
        val ceilings = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)
        for ((i, ceiling) in ceilings.withIndex()) {
            val failures = i + 1
            assertEquals("failures=$failures, low edge", ceiling / 2, RefreshRetry.backoffMs(failures, 0.0))
            assertEquals("failures=$failures, high edge", ceiling, RefreshRetry.backoffMs(failures, 1.0))
            val mid = RefreshRetry.backoffMs(failures, 0.5)
            assertTrue("failures=$failures, mid $mid", mid in (ceiling / 2)..ceiling)
        }
        // Desktop's own pin, word for word: at a draw of 0, 0.5 1 2 4 8 15 15 s.
        assertEquals(
            listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L),
            (1..7).map { RefreshRetry.backoffMs(it, 0.0) },
        )
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
        assertEquals(15_000L, RefreshRetry.rateLimitedWaitMs(" 15 ", null))
        assertEquals(42_000L, RefreshRetry.rateLimitedWaitMs(null, body))
        // An HTTP date is not a wait this client trusts: the body's then.
        assertEquals(42_000L, RefreshRetry.rateLimitedWaitMs("Wed, 21 Oct 2026 07:28:00 GMT", body))
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs(null, null))
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs(null, """{"statusCode":429,"message":"Too many requests"}"""))
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs(null, "<html>429</html>"))
        // `retryAfterSeconds` is the connect refusals' key, not this one.
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs(null, """{"details":{"retryAfterSeconds":42}}"""))
        assertEquals(RefreshRetry.RATE_LIMITED_DEFAULT_MS, 60_000L)
    }

    /**
     * L2: a named 0 is 1 s, as on Desktop (it was read as absent: 60 s), and a
     * named wait is a minute at most (it was ten). A wait that is no whole
     * number of seconds (junk, NaN, a negative, an HTTP date) is no wait, and
     * a 429 then waits its 60 s default.
     */
    @Test
    fun `a named 0 is 1 s, a named wait is a minute at most, and junk falls back to 60 s`() {
        assertEquals(1_000L, RefreshRetry.rateLimitedWaitMs("0", null))
        assertEquals(1_000L, RefreshRetry.rateLimitedWaitMs(null, """{"details":{"retryAfter":0}}"""))
        assertEquals("the header wins", 1_000L, RefreshRetry.rateLimitedWaitMs("0", """{"details":{"retryAfter":42}}"""))
        assertEquals(1_000L, RefreshRetry.rateLimitedWaitMs("1", null))

        assertEquals(59_000L, RefreshRetry.rateLimitedWaitMs("59", null))
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs("60", null))
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs("61", null))
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs("86400", null))
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs(Long.MAX_VALUE.toString(), null))
        assertEquals(60_000L, RefreshRetry.rateLimitedWaitMs(null, """{"details":{"retryAfter":86400}}"""))
        assertEquals(RefreshRetry.SERVER_WAIT_MAX_MS, 60_000L)
        assertEquals(RefreshRetry.SERVER_WAIT_MIN_MS, 1_000L)

        val noWait = listOf("-5", "-1", "soon", "NaN", "1.5", "Wed, 21 Oct 2026 07:28:00 GMT", "", " ")
        for (header in noWait) {
            assertEquals("Retry-After: '$header'", 60_000L, RefreshRetry.rateLimitedWaitMs(header, null))
            assertNull("Retry-After: '$header'", RefreshRetry.headerWaitMs(header))
        }
        for (details in listOf("-5", "\"soon\"", "NaN", "1.5", "null", "true")) {
            val raw = """{"details":{"retryAfter":$details}}"""
            assertEquals("details.retryAfter: $details", 60_000L, RefreshRetry.rateLimitedWaitMs(null, raw))
        }
    }

    @Test
    fun `a server wait is clamped in seconds before the multiply`() {
        assertNull(RefreshRetry.serverWaitMs(null))
        assertNull(RefreshRetry.serverWaitMs(-1))
        assertNull(RefreshRetry.serverWaitMs(Long.MIN_VALUE))
        assertEquals(1_000L, RefreshRetry.serverWaitMs(0))
        assertEquals(30_000L, RefreshRetry.serverWaitMs(30))
        assertEquals(60_000L, RefreshRetry.serverWaitMs(Long.MAX_VALUE))
        assertEquals(1_000L, RefreshRetry.headerWaitMs("0"))
        assertEquals(10_000L, RefreshRetry.headerWaitMs("10"))
        assertNull(RefreshRetry.headerWaitMs(null))
    }

    /**
     * The connect refusals' clamp is not the refresh's: a 503
     * `psk_delivery_unavailable` still waits up to ten minutes, and its 0 is
     * still no wait (RetryAfterTest, ConnectErrorContractTest).
     */
    @Test
    fun `the refresh's clamp leaves the connect refusals' clamp alone`() {
        assertEquals(10 * 60_000L, RetryAfter.MAX_MS)
        assertEquals(RetryAfter.MAX_MS, RetryAfter.fromHeader("86400"))
        assertNull(RetryAfter.fromHeader("0"))
    }
}
