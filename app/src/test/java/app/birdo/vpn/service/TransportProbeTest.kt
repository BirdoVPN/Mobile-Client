package app.birdo.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TransportProbe decides whether a user on a filtered network gets a working
 * connection or a green screen that does nothing, so both directions of the
 * verdict are pinned here.
 *
 * A false BLOCKED costs every user an unnecessary reconnect onto the slower
 * transport; a missed BLOCKED leaves a censored user exactly where they were
 * before this feature existed. Neither is acceptable, so the tests assert the
 * boundary conditions rather than just the happy path.
 */
class TransportProbeTest {

    /** A wg-go UAPI dump for a peer that has completed a handshake. */
    private fun dumpWithHandshake(epochSec: Long = 1_900_000_000L) = """
        public_key=abc
        endpoint=203.0.113.1:51820
        last_handshake_time_sec=$epochSec
        last_handshake_time_nsec=0
        rx_bytes=1024
    """.trimIndent()

    /** wg-go reports 0 for a peer that has never completed a handshake. */
    private fun dumpWithoutHandshake() = """
        public_key=abc
        endpoint=203.0.113.1:51820
        last_handshake_time_sec=0
        last_handshake_time_nsec=0
        rx_bytes=0
    """.trimIndent()

    private fun probe(
        alive: () -> Boolean = { true },
        canRead: () -> Boolean = { true },
        config: (Int) -> String?,
    ) = TransportProbe(
        handle = 1,
        isAlive = alive,
        canReadConfig = canRead,
        readConfig = config,
        sleep = { /* no real waiting in tests */ },
    )

    @Test
    fun `reports HANDSHAKE_OK as soon as a handshake is present`() {
        val result = probe { dumpWithHandshake() }.await()
        assertEquals(TransportProbe.Result.HANDSHAKE_OK, result)
    }

    @Test
    fun `reports BLOCKED when no handshake ever lands`() {
        val result = probe { dumpWithoutHandshake() }.await()
        assertEquals(TransportProbe.Result.BLOCKED, result)
    }

    @Test
    fun `treats a zero handshake timestamp as no handshake, not as a valid one`() {
        // The whole detection hinges on this: wg-go writes
        // last_handshake_time_sec=0 for a peer that has never handshaken, so
        // parsing presence-of-line instead of value would report every blocked
        // tunnel as working and the feature would never fire.
        val result = probe { dumpWithoutHandshake() }.await()
        assertEquals(TransportProbe.Result.BLOCKED, result)
    }

    @Test
    fun `picks up a handshake that arrives partway through the window`() {
        var polls = 0
        val result = probe {
            polls++
            if (polls >= 3) dumpWithHandshake() else dumpWithoutHandshake()
        }.await()
        assertEquals(TransportProbe.Result.HANDSHAKE_OK, result)
    }

    @Test
    fun `aborts instead of reporting BLOCKED when the tunnel goes away mid-probe`() {
        // A user tapping Disconnect during the probe must not be answered with
        // a surprise stealth reconnect.
        var polls = 0
        val result = probe(
            alive = { polls < 2 },
            config = { polls++; dumpWithoutHandshake() },
        ).await()
        assertEquals(TransportProbe.Result.ABORTED, result)
    }

    @Test
    fun `reports HANDSHAKE_OK when the config cannot be read at all`() {
        // No evidence of failure must never be treated as evidence of failure:
        // a build that cannot read wg config would otherwise force EVERY user
        // onto the slow transport.
        val result = probe(canRead = { false }) { null }.await()
        assertEquals(TransportProbe.Result.HANDSHAKE_OK, result)
    }

    @Test
    fun `reports BLOCKED when the config reads as null throughout`() {
        // Distinct from the canReadConfig case above: here the build CAN read
        // config, but wg-go returns nothing — a real failure, not an unknown.
        val result = probe { null }.await()
        assertEquals(TransportProbe.Result.BLOCKED, result)
    }

    @Test
    fun `does not report BLOCKED for a handshake landing in the final poll gap`() {
        // The post-window re-read exists for exactly this: a working connection
        // must not be pushed onto the slow path by a few milliseconds.
        var elapsed = 0L
        val result = TransportProbe(
            handle = 1,
            isAlive = { true },
            canReadConfig = { true },
            readConfig = {
                if (elapsed >= TransportProbe.WINDOW_MS) dumpWithHandshake()
                else dumpWithoutHandshake()
            },
            sleep = { elapsed += TransportProbe.POLL_INTERVAL_MS },
        ).await()
        assertEquals(TransportProbe.Result.HANDSHAKE_OK, result)
    }
}
