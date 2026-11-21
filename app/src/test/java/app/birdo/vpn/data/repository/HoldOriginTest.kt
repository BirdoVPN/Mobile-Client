package app.birdo.vpn.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * MR-2378 (review of #616): the pure rules for who filed a deletion hold.
 * The server voids a hold only when the session that FILED it ends, so the
 * app may say "signing out cancels it" only of a hold its own 202 created.
 */
class HoldOriginTest {

    private val at = Instant.parse("2026-11-18T12:00:00Z")
    private val p1 = PendingDeletion("p1", at)
    private val p2 = PendingDeletion("p2", at)

    @Test
    fun `a new hold maps to 202, a hold already waiting to 409`() {
        assertEquals(BirdoRepository.HTTP_ACCEPTED, HoldOrigin.statusFor(DeletionHoldResult.Held(p1, alreadyPending = false)))
        assertEquals(BirdoRepository.HTTP_CONFLICT, HoldOrigin.statusFor(DeletionHoldResult.Held(p1, alreadyPending = true)))
    }

    @Test
    fun `a 202 makes the hold this session's`() {
        assertEquals("p1", HoldOrigin.markerAfterHoldAnswer(null, DeletionHoldResult.Held(p1, alreadyPending = false)))
        assertEquals("p1", HoldOrigin.markerAfterHoldAnswer("p0", DeletionHoldResult.Held(p1, alreadyPending = false)))
    }

    @Test
    fun `a 409 is this session's only if it was already marked`() {
        // This session's own hold, asked for again: still its own.
        assertEquals("p1", HoldOrigin.markerAfterHoldAnswer("p1", DeletionHoldResult.Held(p1, alreadyPending = true)))
        // Filed on the website or another device, or after a restart lost the mark: not claimed.
        assertNull(HoldOrigin.markerAfterHoldAnswer(null, DeletionHoldResult.Held(p1, alreadyPending = true)))
        assertNull(HoldOrigin.markerAfterHoldAnswer("p0", DeletionHoldResult.Held(p1, alreadyPending = true)))
    }

    /** Review of #623: a list read never claims a hold, whatever request came before it (iOS #624). */
    @Test
    fun `a list read keeps the mark for the same hold only, and never claims one`() {
        assertEquals("p1", HoldOrigin.markerAfterRead("p1", p1))
        assertNull("another id is not this session's", HoldOrigin.markerAfterRead("p1", p2))
        assertNull("a listed hold is never claimed by a read", HoldOrigin.markerAfterRead(null, p1))
        assertNull("the hold is gone", HoldOrigin.markerAfterRead("p1", null))
        assertNull(HoldOrigin.markerAfterRead(null, null))
    }
}
