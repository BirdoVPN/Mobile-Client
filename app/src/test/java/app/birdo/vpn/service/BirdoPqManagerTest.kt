package app.birdo.vpn.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM unit tests for [BirdoPqManager] (BirdoPQ v1).
 *
 * The native lib isn't loaded in the host JVM, so these cover the state the
 * manager publishes. The bilateral ML-KEM-1024 decapsulation roundtrip is
 * covered by the Rust `cargo test` suite in `native/rosenpass-jni/`.
 */
class BirdoPqManagerTest {

    @Before
    fun setup() {
        BirdoPqManager.stop()
    }

    @After
    fun tearDown() {
        BirdoPqManager.stop()
    }

    @Test
    fun `initial state is DISABLED`() {
        assertEquals(BirdoPqManager.Mode.DISABLED, BirdoPqManager.modeFlow.value)
    }

    @Test
    fun `stop is idempotent and leaves DISABLED`() {
        repeat(3) { BirdoPqManager.stop() }
        assertEquals(BirdoPqManager.Mode.DISABLED, BirdoPqManager.modeFlow.value)
    }

    @Test
    fun `there is no partial PQ mode, only the bilateral one or none`() {
        // A1-039: SERVER_PROVIDED (a TLS-delivered classical PSK) was
        // unreachable and was never post-quantum.
        assertEquals(
            listOf(BirdoPqManager.Mode.DISABLED, BirdoPqManager.Mode.BILATERAL),
            BirdoPqManager.Mode.entries.toList(),
        )
    }

    @Test
    fun `RosenpassNative exposes correct ML-KEM-1024 constants`() {
        assertEquals(1568, RosenpassNative.PUBLIC_KEY_BYTES)
        assertEquals(3168, RosenpassNative.SECRET_KEY_BYTES)
        assertEquals(1568, RosenpassNative.CIPHERTEXT_BYTES)
        assertEquals(32, RosenpassNative.PSK_BYTES)
    }
}
