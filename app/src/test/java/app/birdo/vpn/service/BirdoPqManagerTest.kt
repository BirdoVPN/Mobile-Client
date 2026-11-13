package app.birdo.vpn.service

import android.content.Context
import app.birdo.vpn.data.model.ConnectResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Pure-JVM unit tests for [BirdoPqManager] (BirdoPQ v1, one keypair per connect:
 * MR-703 / MR-1742).
 *
 * The native lib isn't loaded in the host JVM. The state tests below need
 * nothing else; the key-handling tests stand in a FAKE KEM for
 * [RosenpassNative] whose one property that matters is ML-KEM's: decapsulating
 * with a secret key gives the server's PSK only if the encapsulation key
 * embedded in that secret key (`sk[1536..3104]`) is the public key the server
 * encapsulated to. Anything else derives a different PSK, silently, which is
 * implicit rejection. The real ML-KEM-1024 roundtrip is covered by the Rust
 * `cargo test` suite in `native/rosenpass-jni/`.
 */
class BirdoPqManagerTest {

    private lateinit var filesDir: File
    private lateinit var context: Context
    private var keygens = 0
    private val generated = mutableListOf<RosenpassNative.StaticKeypair>()
    private var purges = 0
    private var clock = 0L

    @Before
    fun setup() {
        BirdoPqManager.stop()
    }

    @After
    fun tearDown() {
        if (::context.isInitialized) filesDir.deleteRecursively()
        BirdoPqManager.stop()
        unmockkAll()
    }

    // ── The fake KEM ─────────────────────────────────────────────────────

    /** A keypair laid out like ML-KEM-1024's: the pk is embedded in the sk at 1536. */
    private fun fakeKeypair(): RosenpassNative.StaticKeypair {
        val n = ++keygens
        val pk = ByteArray(RosenpassNative.PUBLIC_KEY_BYTES) { ((it * 7 + n * 31) and 0xff).toByte() }
        val sk = ByteArray(RosenpassNative.SECRET_KEY_BYTES) { ((it * 13 + n) and 0xff).toByte() }
        pk.copyInto(sk, destinationOffset = EK_OFFSET)
        return RosenpassNative.StaticKeypair(pk, sk).also { generated += it }
    }

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach { update(it) }
            digest()
        }

    /** The PSK the server derives for the public key it was sent. */
    private fun serverPsk(pk: ByteArray, ct: ByteArray, nonce: ByteArray) = sha256(pk, ct, nonce)

    /** The PSK the client derives: a function of the ek inside ITS secret key. */
    private fun clientPsk(sk: ByteArray, ct: ByteArray, nonce: ByteArray) =
        sha256(sk.copyOfRange(EK_OFFSET, EK_OFFSET + RosenpassNative.PUBLIC_KEY_BYTES), ct, nonce)

    private fun installFakeKem() {
        filesDir = Files.createTempDirectory("pq-manager").toFile()
        context = mockk()
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir

        mockkObject(RosenpassNative)
        every { RosenpassNative.verifyIntegrity(any()) } returns true
        every { RosenpassNative.isLoaded } returns true
        every { RosenpassNative.generateKeypair() } answers { fakeKeypair() }
        every { RosenpassNative.deriveSharedPsk(any(), any(), any()) } answers {
            clientPsk(firstArg(), secondArg(), thirdArg())
        }

        // android.util.Base64 is a stub on the JVM (returns null).
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.encodeToString(any<ByteArray>(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { android.util.Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }

        // The Keystore does not exist on the JVM; count the purges instead. Every
        // test starts with the once-per-process latch open.
        BirdoPqManager.legacyPurgeFactory = {
            BirdoPqLegacyPurge(File(filesDir, BirdoPqLegacyPurge.DIR_NAME)) { purges++ }
        }
        BirdoPqManager.purgeExecutor = { it.run() }
        clock = 1_000L
        BirdoPqManager.nowMs = { clock }
        BirdoPqManager::class.java.getDeclaredField("legacyPurged").apply {
            isAccessible = true
            (get(BirdoPqManager) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        }
    }

    private val ciphertext = ByteArray(RosenpassNative.CIPHERTEXT_BYTES) { (it and 0x7f).toByte() }
    private val nonce = ByteArray(32) { 0x2a }

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String?) = java.util.Base64.getDecoder().decode(s)

    private fun response() = ConnectResponse(
        success = true,
        quantumEnabled = true,
        rosenpassPublicKey = b64(ciphertext),
        rosenpassEndpoint = b64(nonce),
    )

    // ── State ────────────────────────────────────────────────────────────

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

    // ── One keypair per connect (MR-703 / MR-1742) ───────────────────────

    /** The decapsulation uses exactly the pair whose public key was uploaded (MR-2117). */
    @Test
    fun `the decapsulation uses the uploaded pair`() = runTest {
        installFakeKem()

        val uploaded = unb64(BirdoPqManager.getClientPublicKeyB64(context))
        val psk = BirdoPqManager.performKeyExchange(context, response())

        assertNotNull("a bilateral PSK must be derived", psk)
        assertArrayEquals(
            "the client must derive the PSK the server derived for the UPLOADED key",
            serverPsk(uploaded, ciphertext, nonce),
            unb64(psk),
        )
        assertEquals(BirdoPqManager.Mode.BILATERAL, BirdoPqManager.modeFlow.value)
        assertEquals("one keypair for one connect", 1, keygens)
    }

    /** Two connects never share a key: the property this change exists for. */
    @Test
    fun `every connect uploads a different public key`() = runTest {
        installFakeKem()

        val first = BirdoPqManager.getClientPublicKeyB64(context)
        assertNotNull(BirdoPqManager.performKeyExchange(context, response()))
        val second = BirdoPqManager.getClientPublicKeyB64(context)
        val psk = BirdoPqManager.performKeyExchange(context, response())

        assertNotEquals("a new connect must not reuse the last connect's key", first, second)
        assertEquals("one generation per connect", 2, keygens)
        assertArrayEquals(serverPsk(unb64(second), ciphertext, nonce), unb64(psk))
    }

    /**
     * No second decapsulation: the secret was consumed by the first. Without a
     * stored copy the second call has nothing to decapsulate with and fails
     * closed instead of deriving some other pair's PSK.
     */
    @Test
    fun `a decapsulation without a fresh upload fails closed`() = runTest {
        installFakeKem()

        BirdoPqManager.getClientPublicKeyB64(context)
        assertNotNull(BirdoPqManager.performKeyExchange(context, response()))

        assertNull(BirdoPqManager.performKeyExchange(context, response()))
        assertEquals(BirdoPqManager.Mode.DISABLED, BirdoPqManager.modeFlow.value)
        assertEquals("no key was minted behind the caller's back", 1, keygens)
    }

    /** MR-2123: the handed-off secret key does not outlive the decapsulation. */
    @Test
    fun `the handed-off secret key is zeroed once the decapsulation is done`() = runTest {
        installFakeKem()

        BirdoPqManager.getClientPublicKeyB64(context)
        val uploadedSecret = generated.first().secretKey
        assertFalse(uploadedSecret.all { it == 0.toByte() })

        assertNotNull(BirdoPqManager.performKeyExchange(context, response()))
        assertTrue("the uploaded pair's secret key is still in memory", uploadedSecret.all { it == 0.toByte() })
    }

    /** A decapsulation that fails (a bad ciphertext) still wipes the secret. */
    @Test
    fun `the secret key is zeroed when the decapsulation is refused`() = runTest {
        installFakeKem()

        BirdoPqManager.getClientPublicKeyB64(context)
        val uploadedSecret = generated.first().secretKey

        val bad = response().copy(rosenpassPublicKey = b64(ByteArray(16)))
        assertNull(BirdoPqManager.performKeyExchange(context, bad))
        assertTrue(uploadedSecret.all { it == 0.toByte() })
    }

    /** MR-2123: a connect that never reaches the decapsulation leaves nothing behind either. */
    @Test
    fun `stop zeroes a hand-off that was never consumed once it is stale`() = runTest {
        installFakeKem()

        BirdoPqManager.getClientPublicKeyB64(context)
        val uploadedSecret = generated.first().secretKey

        clock += 120_000L
        BirdoPqManager.stop()
        assertTrue("stop() must zero the unconsumed secret key", uploadedSecret.all { it == 0.toByte() })
    }

    /** A second upload replaces the first, which is wiped: only the newest pair is ever live. */
    @Test
    fun `a new upload zeroes the pair it replaces`() = runTest {
        installFakeKem()

        BirdoPqManager.getClientPublicKeyB64(context)
        val abandoned = generated.first().secretKey
        val second = unb64(BirdoPqManager.getClientPublicKeyB64(context))

        assertTrue("the abandoned connect's secret is still in memory", abandoned.all { it == 0.toByte() })
        assertArrayEquals(serverPsk(second, ciphertext, nonce), unb64(BirdoPqManager.performKeyExchange(context, response())))
    }

    /**
     * THE review race: the new dial uploads its pair, then the OLD generation's
     * teardown calls stop(). The fresh pair must survive and decapsulate.
     */
    @Test
    fun `a stale teardown's stop does not wipe the pair a newer dial just uploaded`() = runTest {
        installFakeKem()

        val uploadedA = unb64(BirdoPqManager.getClientPublicKeyB64(context))
        clock += 5_000L // the old teardown arrives seconds later
        BirdoPqManager.stop()

        val psk = BirdoPqManager.performKeyExchange(context, response())
        assertNotNull("the newer pair was wiped by a stale stop()", psk)
        assertArrayEquals(serverPsk(uploadedA, ciphertext, nonce), unb64(psk))
    }

    /** An abandoned connect's pair does not sit in memory for ever: stop() wipes it once it is old. */
    @Test
    fun `stop wipes a pair nobody consumed once it is stale`() = runTest {
        installFakeKem()

        BirdoPqManager.getClientPublicKeyB64(context)
        val abandoned = generated.first().secretKey
        clock += 120_000L
        BirdoPqManager.stop()

        assertTrue(abandoned.all { it == 0.toByte() })
        assertNull(BirdoPqManager.performKeyExchange(context, response()))
    }

    /** With a stale hand-off cleared by stop() there is nothing to derive with. */
    @Test
    fun `after a stale stop the connect is refused, not decapsulated with another pair`() = runTest {
        installFakeKem()

        BirdoPqManager.getClientPublicKeyB64(context)
        clock += 120_000L
        BirdoPqManager.stop()

        assertNull(BirdoPqManager.performKeyExchange(context, response()))
        assertEquals(BirdoPqManager.Mode.DISABLED, BirdoPqManager.modeFlow.value)
        assertEquals(1, keygens)
    }

    @Test
    fun `a PQ engine that is not loaded supplies no key`() = runTest {
        installFakeKem()
        every { RosenpassNative.isLoaded } returns false
        assertNull(BirdoPqManager.getClientPublicKeyB64(context))
        verify(exactly = 0) { RosenpassNative.generateKeypair() }
    }

    // ── The legacy item is deleted, not adopted ──────────────────────────

    @Test
    fun `the first connect purges the legacy key once per process`() = runTest {
        installFakeKem()

        BirdoPqManager.getClientPublicKeyB64(context)
        val afterFirst = purges
        assertEquals("both Keystore keys", BirdoPqLegacyPurge.KEYSTORE_ALIASES.size, afterFirst)
        BirdoPqManager.getClientPublicKeyB64(context)
        assertEquals("the second connect does not purge again", afterFirst, purges)
    }

    @Test
    fun `a key file an older build left is deleted and never read`() = runTest {
        installFakeKem()
        val dir = File(filesDir, "rosenpass").apply { mkdirs() }
        val planted = File(dir, "static.sk.v2").apply { writeBytes(ByteArray(3200) { 7 }) }
        File(dir, "static.pk").writeBytes(ByteArray(1568) { 7 })

        val uploaded = unb64(BirdoPqManager.getClientPublicKeyB64(context))

        assertFalse("the legacy secret key is still on disk", planted.exists())
        assertFalse("the legacy directory is gone", dir.exists())
        assertNotEquals("the legacy public key was adopted", 7.toByte(), uploaded[0])
    }

    @Test
    fun `sign-out purges again and drops an in-flight pair`() = runTest {
        installFakeKem()
        BirdoPqManager.getClientPublicKeyB64(context)
        val inFlight = generated.first().secretKey
        val before = purges
        // A file written after the once-per-process purge (a downgrade).
        File(filesDir, "rosenpass").apply { mkdirs() }.let { File(it, "static.sk.v2").writeBytes(ByteArray(10)) }

        BirdoPqManager.discardKeypair(context)

        assertTrue("sign-out left the connect's secret key in memory", inFlight.all { it == 0.toByte() })
        assertTrue("sign-out left a legacy key file", !File(filesDir, "rosenpass/static.sk.v2").exists())
        assertEquals("the Keystore keys are deleted again too", before + BirdoPqLegacyPurge.KEYSTORE_ALIASES.size, purges)
    }

    private companion object {
        /** FIPS 203 ML-KEM-1024: dk = dk_pke (1536) || ek (1568) || H(ek) (32) || z (32). */
        const val EK_OFFSET = 1536
    }
}
