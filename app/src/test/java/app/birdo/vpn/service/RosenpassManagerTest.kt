package app.birdo.vpn.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM unit tests for [RosenpassManager] (BirdoPQ v1).
 *
 * The native lib isn't loaded in the host JVM, so all tests exercise the
 * fall-through paths (DISABLED / SERVER_PROVIDED modes + crypto helpers).
 * The bilateral ML-KEM-1024 decapsulation roundtrip is covered by the Rust
 * `cargo test` suite in `native/rosenpass-jni/`. End-to-end on-device tests
 * are tracked in `native/ROADMAP.md`.
 */
class RosenpassManagerTest {

    @Before
    fun setup() {
        RosenpassManager.stop()
    }

    @After
    fun tearDown() {
        RosenpassManager.stop()
    }

    // ── State machine ──────────────────────────────────────────────────────

    @Test
    fun `initial state is DISABLED with null PSK`() {
        assertFalse(RosenpassManager.isQuantumProtected())
        assertFalse(RosenpassManager.isBilateral())
        assertNull(RosenpassManager.getCurrentPsk())
        assertEquals(RosenpassManager.Mode.DISABLED, RosenpassManager.modeFlow.value)
    }

    @Test
    fun `stop clears PSK and resets mode`() {
        RosenpassManager.stop()
        assertEquals(RosenpassManager.Mode.DISABLED, RosenpassManager.modeFlow.value)
        assertNull(RosenpassManager.getCurrentPsk())
    }

    @Test
    fun `stop is idempotent`() {
        repeat(3) { RosenpassManager.stop() }
        assertEquals(RosenpassManager.Mode.DISABLED, RosenpassManager.modeFlow.value)
    }

    @Test
    fun `nativeLibVersion returns placeholder when lib not loaded`() {
        val v = RosenpassManager.nativeLibVersion()
        assertNotNull(v)
        assertTrue("got: $v", v == "<not loaded>" || v.startsWith("rosenpass-jni"))
    }

    // ── Mode enum ──────────────────────────────────────────────────────────

    @Test
    fun `Mode enum has exactly three values`() {
        assertEquals(3, RosenpassManager.Mode.values().size)
        assertNotNull(RosenpassManager.Mode.valueOf("DISABLED"))
        assertNotNull(RosenpassManager.Mode.valueOf("SERVER_PROVIDED"))
        assertNotNull(RosenpassManager.Mode.valueOf("BILATERAL"))
    }

    // ── HKDF / HMAC helpers ────────────────────────────────────────────────

    @Test
    fun `hmacSha256 matches Java reference impl`() {
        val key = "test-key".toByteArray()
        val data = "test-data".toByteArray()
        val ours = RosenpassManager.hmacSha256(key, data)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val expected = mac.doFinal(data)

        assertEquals(32, ours.size)
        assertEquals(expected.toList(), ours.toList())
    }

    @Test
    fun `hkdfExpand produces requested length`() {
        val prk = ByteArray(32) { (it + 1).toByte() }
        val out = RosenpassManager.hkdfExpand(prk, "wg psk".toByteArray(), 32)
        assertEquals(32, out.size)
        assertFalse(out.all { it == 0.toByte() })
    }

    @Test
    fun `hkdfExpand is deterministic`() {
        val prk = ByteArray(32) { 0x42.toByte() }
        val a = RosenpassManager.hkdfExpand(prk, "ctx".toByteArray(), 32)
        val b = RosenpassManager.hkdfExpand(prk, "ctx".toByteArray(), 32)
        assertEquals(a.toList(), b.toList())
    }

    @Test
    fun `hkdfExpand different info produces different output`() {
        val prk = ByteArray(32) { 0x42.toByte() }
        val a = RosenpassManager.hkdfExpand(prk, "info-A".toByteArray(), 32)
        val b = RosenpassManager.hkdfExpand(prk, "info-B".toByteArray(), 32)
        assertFalse(a.contentEquals(b))
    }

    // ── ML-KEM constants are exposed ──────────────────────────────────────

    @Test
    fun `RosenpassNative exposes correct ML-KEM-1024 constants`() {
        assertEquals(1568, RosenpassNative.PUBLIC_KEY_BYTES)
        assertEquals(3168, RosenpassNative.SECRET_KEY_BYTES)
        assertEquals(1568, RosenpassNative.CIPHERTEXT_BYTES)
        assertEquals(32, RosenpassNative.PSK_BYTES)
    }
}
