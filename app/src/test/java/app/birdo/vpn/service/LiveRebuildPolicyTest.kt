package app.birdo.vpn.service

import app.birdo.vpn.service.LiveRebuildPolicy.Directive
import app.birdo.vpn.service.LiveRebuildPolicy.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1-034: the in-place live rebuild's decision table, the Android twin of
 * iOS LiveRebuildTests. The rule that matters most is the iOS #354 lesson:
 * nothing that fails before the old tunnel is touched may stop it, and
 * nothing that fails after the swap may fail open.
 */
class LiveRebuildPolicyTest {

    @Test
    fun `only the server's inability to defer reaches the teardown path`() {
        val stopping = Event.entries.filter { LiveRebuildPolicy.directive(it) == Directive.LEGACY_TEARDOWN }
        assertEquals(setOf(Event.CANNOT_REBUILD_HERE, Event.DEFERRAL_NOT_HONOURED), stopping.toSet())
    }

    @Test
    fun `everything that fails before the swap keeps the live session`() {
        listOf(Event.REFUSED, Event.REQUEST_FAILED, Event.ROUTE_NOT_CONFIRMED, Event.FAILED_BEFORE_SWAP).forEach {
            assertEquals("$it", Directive.KEEP_OLD_SESSION, LiveRebuildPolicy.directive(it))
            assertFalse("$it must never release the key the live session rides", LiveRebuildPolicy.release(it).oldKey)
        }
    }

    @Test
    fun `a new peer that answers commits, and only then is the old key released`() {
        assertEquals(Directive.COMMIT_NEW, LiveRebuildPolicy.directive(Event.NEW_PEER_HANDSHAKED))
        assertEquals(LiveRebuildPolicy.Release(newKey = false, oldKey = true), LiveRebuildPolicy.release(Event.NEW_PEER_HANDSHAKED))
    }

    @Test
    fun `a failure after the swap fails closed and releases both keys`() {
        assertEquals(Directive.FAILED_CLOSED, LiveRebuildPolicy.directive(Event.FAILED_AFTER_SWAP))
        assertEquals(LiveRebuildPolicy.Release(newKey = true, oldKey = true), LiveRebuildPolicy.release(Event.FAILED_AFTER_SWAP))
    }

    @Test
    fun `a minted key nothing rides is given back`() {
        listOf(Event.DEFERRAL_NOT_HONOURED, Event.ROUTE_NOT_CONFIRMED, Event.FAILED_BEFORE_SWAP, Event.SUPERSEDED).forEach {
            assertTrue("$it", LiveRebuildPolicy.release(it).newKey)
        }
        // Nothing was minted on a refusal or a failed request.
        listOf(Event.CANNOT_REBUILD_HERE, Event.REFUSED, Event.REQUEST_FAILED).forEach {
            assertFalse("$it", LiveRebuildPolicy.release(it).newKey)
        }
    }

    @Test
    fun `refusal codes, and only an exact echo counts as a deferral`() {
        assertEquals(Event.CANNOT_REBUILD_HERE, LiveRebuildPolicy.forRefusal("unknown-current-key"))
        assertEquals(Event.CANNOT_REBUILD_HERE, LiveRebuildPolicy.forRefusal("same-entry-exit-change"))
        listOf("device-identity", "lookup-failed", "key-reuse", "a-code-from-the-future", null).forEach {
            assertEquals("$it keeps the session", Event.REFUSED, LiveRebuildPolicy.forRefusal(it))
        }
        assertTrue(LiveRebuildPolicy.deferralHonoured("key-1", "key-1"))
        assertFalse(LiveRebuildPolicy.deferralHonoured("key-1", "key-2"))
        assertFalse(LiveRebuildPolicy.deferralHonoured("key-1", null))
        assertFalse(LiveRebuildPolicy.deferralHonoured("key-1", ""))
    }

    @Test
    fun `only a live, key-bearing, stealth-free, unblocked session is rebuilt in place`() {
        fun eligible(
            connected: Boolean = true,
            key: String? = "key-1",
            stealthActive: Boolean = false,
            stealthWanted: Boolean = false,
            blocked: Boolean = false,
        ) = LiveRebuildPolicy.eligible(connected, key, stealthActive, stealthWanted, blocked)
        assertTrue(eligible())
        assertFalse(eligible(connected = false))
        assertFalse(eligible(key = null))
        assertFalse(eligible(stealthActive = true))
        assertFalse(eligible(stealthWanted = true))
        assertFalse(eligible(blocked = true))
    }

    @Test
    fun `the probe window fits the server's grace and avoids a rekey boundary`() {
        // wireguard-go re-sends an initiation every 5 s; the server defers the
        // old key for 30 s from the mint.
        assertTrue(LiveRebuildPolicy.PROBE_WINDOW_MS % 5_000L != 0L)
        assertTrue(LiveRebuildPolicy.PROBE_WINDOW_MS + 8_000L < 30_000L)
    }
}
