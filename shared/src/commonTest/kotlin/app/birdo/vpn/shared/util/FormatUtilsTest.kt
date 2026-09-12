package app.birdo.vpn.shared.util

import app.birdo.vpn.shared.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FormatUtils is the ONE formatter behind the Android notification, the
 * Android home-screen clock and the iOS/macOS UI. These tests pin the exact
 * strings, because a change here changes every screen on every platform.
 */
class FormatUtilsTest {

    // ── formatBytes ──────────────────────────────────────────────

    @Test
    fun zeroAndNegativeBytesAreZero() {
        assertEquals("0 B", FormatUtils.formatBytes(0))
        assertEquals("0 B", FormatUtils.formatBytes(-1))
        assertEquals("0 B", FormatUtils.formatBytes(Long.MIN_VALUE))
    }

    @Test
    fun bytesBelowOneKilobyteAreWhole() {
        assertEquals("1 B", FormatUtils.formatBytes(1))
        assertEquals("1023 B", FormatUtils.formatBytes(1023))
    }

    @Test
    fun kilobytesAndMegabytesCarryOneDecimal() {
        assertEquals("1.0 KB", FormatUtils.formatBytes(1024))
        assertEquals("1.5 KB", FormatUtils.formatBytes(1536))
        assertEquals("1.0 MB", FormatUtils.formatBytes(1024L * 1024))
        assertEquals("45.3 MB", FormatUtils.formatBytes((45.3 * 1024 * 1024).toLong() + 1))
    }

    @Test
    fun gigabytesCarryTwoDecimals() {
        assertEquals("1.00 GB", FormatUtils.formatBytes(1024L * 1024 * 1024))
        assertEquals("1.50 GB", FormatUtils.formatBytes(1024L * 1024 * 1024 * 3 / 2))
        assertEquals("1024.00 GB", FormatUtils.formatBytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun unitBoundariesDoNotPromoteEarly() {
        // one byte under a megabyte is still a KB figure (it rounds to 1024.0),
        // it does not become "1.0 MB" before the threshold
        assertEquals("1024.0 KB", FormatUtils.formatBytes(1024L * 1024 - 1))
        assertEquals("1024.0 MB", FormatUtils.formatBytes(1024L * 1024 * 1024 - 1))
    }

    // ── formatElapsedSeconds ─────────────────────────────────────

    @Test
    fun underAnHourIsMinutesAndSeconds() {
        assertEquals("00:00", FormatUtils.formatElapsedSeconds(0))
        assertEquals("00:05", FormatUtils.formatElapsedSeconds(5))
        assertEquals("00:59", FormatUtils.formatElapsedSeconds(59))
        assertEquals("01:00", FormatUtils.formatElapsedSeconds(60))
        assertEquals("59:59", FormatUtils.formatElapsedSeconds(3599))
    }

    @Test
    fun anHourOrMoreAddsAnUnpaddedHourField() {
        assertEquals("1:00:00", FormatUtils.formatElapsedSeconds(3600))
        assertEquals("1:01:01", FormatUtils.formatElapsedSeconds(3661))
        assertEquals("23:59:59", FormatUtils.formatElapsedSeconds(86399))
        // a tunnel up for more than a day keeps counting hours; nothing wraps
        assertEquals("25:00:00", FormatUtils.formatElapsedSeconds(90000))
        assertEquals("100:00:00", FormatUtils.formatElapsedSeconds(360000))
    }

    @Test
    fun digitsAreAlwaysAscii() {
        // Kotlin templates never localise digits, which is what keeps the
        // notification and the iOS UI byte-identical across locales.
        val s = FormatUtils.formatElapsedSeconds(3661)
        assertTrue(s.all { it in '0'..'9' || it == ':' }, s)
    }

    // ── formatDuration ───────────────────────────────────────────

    @Test
    fun durationSinceZeroOrNegativeIsTheIdleClock() {
        assertEquals("00:00", FormatUtils.formatDuration(0))
        assertEquals("00:00", FormatUtils.formatDuration(-5))
    }

    @Test
    fun durationSinceAPastInstantCountsElapsedSeconds() {
        val since = currentTimeMillis() - 5_000
        val s = FormatUtils.formatDuration(since)
        // 5 s ago; allow the second to tick over while the test runs
        assertTrue(s == "00:05" || s == "00:06", s)
    }

    @Test
    fun durationSinceOverAnHourAgoUsesTheHourForm() {
        val since = currentTimeMillis() - 3_600_000 - 61_000
        val s = FormatUtils.formatDuration(since)
        assertTrue(s == "1:01:01" || s == "1:01:02", s)
    }
}
