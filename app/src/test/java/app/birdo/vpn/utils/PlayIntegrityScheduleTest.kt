package app.birdo.vpn.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6-CLI-A-03: Play Integrity must not run once per connect.
 *
 * Attestation used to fire on EVERY connect, handing Google a request from the
 * user's real IP at the exact moment each VPN session started — a per-connection
 * timeline of every Play-build user, produced by the VPN client itself. The fix
 * is a hard rate limit, so these tests pin the scheduling rule: how often it may
 * run, that failures are bounded too, and that a moved clock cannot silently
 * switch attestation off.
 */
class PlayIntegrityScheduleTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `a fresh install is due`() {
        assertTrue(
            "An install that has never attested must attest",
            PlayIntegrityManager.isDue(lastAttemptMs = 0L, lastOk = false, nowMs = 1_000_000L),
        )
    }

    @Test
    fun `a recent success suppresses attestation`() {
        val last = 1_000_000_000L
        assertFalse(
            "A connect one day after a successful attestation must NOT attest again — " +
                "that is the per-connect timeline this fix removes",
            PlayIntegrityManager.isDue(lastAttemptMs = last, lastOk = true, nowMs = last + day),
        )
    }

    @Test
    fun `success expires after the success interval`() {
        val last = 1_000_000_000L
        assertFalse(
            PlayIntegrityManager.isDue(
                lastAttemptMs = last,
                lastOk = true,
                nowMs = last + PlayIntegrityManager.SUCCESS_INTERVAL_MS - 1,
            ),
        )
        assertTrue(
            "Attestation must renew once the window elapses — it is a periodic " +
                "check on the install, not a one-off",
            PlayIntegrityManager.isDue(
                lastAttemptMs = last,
                lastOk = true,
                nowMs = last + PlayIntegrityManager.SUCCESS_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun `a failure retries sooner but is still bounded`() {
        val last = 1_000_000_000L
        assertFalse(
            "A FAILED attestation must not retry on the next connect: a failed " +
                "request still tells Google the device pinged them, so the " +
                "timeline would be rebuilt out of failures",
            PlayIntegrityManager.isDue(
                lastAttemptMs = last,
                lastOk = false,
                nowMs = last + PlayIntegrityManager.FAILURE_BACKOFF_MS - 1,
            ),
        )
        assertTrue(
            PlayIntegrityManager.isDue(
                lastAttemptMs = last,
                lastOk = false,
                nowMs = last + PlayIntegrityManager.FAILURE_BACKOFF_MS,
            ),
        )
        assertTrue(
            "The failure backoff must be shorter than the success interval",
            PlayIntegrityManager.FAILURE_BACKOFF_MS < PlayIntegrityManager.SUCCESS_INTERVAL_MS,
        )
    }

    @Test
    fun `a clock moved backwards does not disable attestation forever`() {
        val last = 2_000_000_000L
        assertTrue(
            "A stored timestamp in the future (user changed the date, NTP " +
                "correction) must not suppress attestation until real time " +
                "catches up — for a date set years ahead that is never",
            PlayIntegrityManager.isDue(lastAttemptMs = last, lastOk = true, nowMs = last - day),
        )
    }
}
