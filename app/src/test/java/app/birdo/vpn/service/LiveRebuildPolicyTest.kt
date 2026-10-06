package app.birdo.vpn.service

import app.birdo.vpn.data.repository.FailureReason
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
    fun `only a server that cannot defer, or a tunnel that cannot carry the request, reaches the teardown path`() {
        val stopping = Event.entries.filter { LiveRebuildPolicy.directive(it) == Directive.LEGACY_TEARDOWN }
        assertEquals(
            setOf(Event.CANNOT_REBUILD_HERE, Event.NO_LIVE_SESSION, Event.DEFERRAL_NOT_HONOURED, Event.REQUEST_UNANSWERED),
            stopping.toSet(),
        )
    }

    /**
     * REVIEW-AND2-003: a rebuild whose /connect got no answer through the live
     * tunnel cannot tell a busy server from a tunnel that carries no TLS (an
     * MTU black hole). Keeping that session kept the user on it for good.
     */
    @Test
    fun `no answer through the live tunnel is the tunnel's fault, any answer is not`() {
        listOf(FailureReason.UNREACHABLE, FailureReason.OFFLINE, FailureReason.SECURE_CONNECTION).forEach {
            assertEquals("$it", Event.REQUEST_UNANSWERED, LiveRebuildPolicy.forRequestFailure(0, it))
        }
        // A 2xx body that did not decode came through the tunnel.
        assertEquals(Event.REQUEST_FAILED, LiveRebuildPolicy.forRequestFailure(0, FailureReason.UNEXPECTED))
        // Any HTTP status proves the tunnel carries the API: keep the session.
        listOf(400, 403, 429, 500, 502, 503).forEach {
            assertEquals("$it", Event.REQUEST_FAILED, LiveRebuildPolicy.forRequestFailure(it, FailureReason.SERVER_UNAVAILABLE))
        }
        assertFalse("no key was known to be minted", LiveRebuildPolicy.release(Event.REQUEST_UNANSWERED).newKey)
        assertFalse(LiveRebuildPolicy.release(Event.REQUEST_UNANSWERED).oldKey)
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
        listOf(
            Event.NO_LIVE_SESSION,
            Event.DEFERRAL_NOT_HONOURED,
            Event.ROUTE_NOT_CONFIRMED,
            Event.FAILED_BEFORE_SWAP,
            Event.SUPERSEDED,
        ).forEach {
            assertTrue("$it", LiveRebuildPolicy.release(it).newKey)
        }
        // The service found no session after the server minted one: the old
        // key goes through today's teardown, never through the release list.
        assertFalse(LiveRebuildPolicy.release(Event.NO_LIVE_SESSION).oldKey)
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
