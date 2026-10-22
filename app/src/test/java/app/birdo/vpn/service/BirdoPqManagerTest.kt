package app.birdo.vpn.service

import android.content.Context
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.security.AesGcmSealer
import app.birdo.vpn.security.FlakyKeySource
import app.birdo.vpn.security.LegacyBlobSource
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Pure-JVM unit tests for [BirdoPqManager] (BirdoPQ v1).
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
    private lateinit var store: BirdoPqKeyStore
    private val keystore = FlakyKeySource()
    private var keygens = 0
    private val generated = mutableListOf<RosenpassNative.StaticKeypair>()

    private object NoLegacy : LegacyBlobSource {
        override fun read(): ByteArray? = null
        override fun destroy() {}
    }

    @Before
    fun setup() {
        BirdoPqManager.stop()
    }

    @After
    fun tearDown() {
        if (::context.isInitialized) {
            BirdoPqManager.resetPersistedKeypair(context)
            useKeyStore(null)
            filesDir.deleteRecursively()
        }
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
        every { RosenpassNative.storedKeyUsable(any()) } returns true
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

        store = BirdoPqKeyStore(context, AesGcmSealer(keystore), NoLegacy)
        useKeyStore(store)
        // Process-wide object: start from nothing whatever ran before.
        BirdoPqManager.resetPersistedKeypair(context)
    }

    /** The manager's store is private and built from a Context; swap it for a JVM one. */
    private fun useKeyStore(store: BirdoPqKeyStore?) {
        BirdoPqManager::class.java.getDeclaredField("keyStore").apply {
            isAccessible = true
            set(null, store)
        }
    }

    private val ciphertext = ByteArray(RosenpassNative.CIPHERTEXT_BYTES) { (it and 0x7f).toByte() }
    private val nonce = ByteArray(32) { 0x2a }

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

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

    // ── MR-2117: the uploaded pair is the decapsulating pair ──────────────

    /**
     * THE regression test. With a store that cannot persist (a broken
     * Keystore), the decapsulation used to re-read the keypair, find none,
     * and GENERATE a second one: the server encapsulated to pk_A, the client
     * decapsulated with sk_B, and the PSKs differed. On a device that is a
     * WireGuard handshake that never completes, on every BirdoPQ connect.
     */
    @Test
    fun `the decapsulation uses the uploaded pair even when the store cannot keep it`() = runTest {
        installFakeKem()
        keystore.failing = true

        val uploaded = java.util.Base64.getDecoder().decode(BirdoPqManager.getClientPublicKeyB64(context))
        val psk = BirdoPqManager.performKeyExchange(context, response())

        assertNotNull("a bilateral PSK must be derived", psk)
        assertArrayEquals(
            "the client must derive the PSK the server derived for the UPLOADED key",
            serverPsk(uploaded, ciphertext, nonce),
            java.util.Base64.getDecoder().decode(psk),
        )
        assertEquals(BirdoPqManager.Mode.BILATERAL, BirdoPqManager.modeFlow.value)
        assertEquals("one keypair for one connect", 1, keygens)
    }

    /** MR-2123: the handed-off secret key does not outlive the decapsulation. */
    @Test
    fun `the handed-off secret key is zeroed once the decapsulation is done`() = runTest {
        installFakeKem()
        keystore.failing = true

        BirdoPqManager.getClientPublicKeyB64(context)
        val uploadedSecret = generated.first().secretKey
        assertFalse(uploadedSecret.all { it == 0.toByte() })

        assertNotNull(BirdoPqManager.performKeyExchange(context, response()))
        assertTrue("the uploaded pair's secret key is still in memory", uploadedSecret.all { it == 0.toByte() })
    }

    /** MR-2123: a connect that never reaches the decapsulation leaves nothing behind either. */
    @Test
    fun `stop zeroes a hand-off that was never consumed`() = runTest {
        installFakeKem()

        BirdoPqManager.getClientPublicKeyB64(context)
        val uploadedSecret = generated.first().secretKey

        BirdoPqManager.stop()
        assertTrue("stop() must zero the unconsumed secret key", uploadedSecret.all { it == 0.toByte() })
    }

    /**
     * With the hand-off gone (here: cleared by stop()), the store is the only
     * source, and an unpersistable store yields a NEW pair. Its PSK cannot be
     * the server's, so the derivation is refused where the cause is known
     * instead of surfacing as a dead tunnel.
     */
    @Test
    fun `a pair other than the uploaded one is refused, not decapsulated`() = runTest {
        installFakeKem()
        keystore.failing = true

        BirdoPqManager.getClientPublicKeyB64(context)
        BirdoPqManager.stop()

        assertNull(BirdoPqManager.performKeyExchange(context, response()))
        assertEquals(BirdoPqManager.Mode.DISABLED, BirdoPqManager.modeFlow.value)
    }

    @Test
    fun `with a working store the uploaded pair is the persisted one and is reused`() = runTest {
        installFakeKem()

        val first = BirdoPqManager.getClientPublicKeyB64(context)
        assertNotNull(BirdoPqManager.performKeyExchange(context, response()))
        val second = BirdoPqManager.getClientPublicKeyB64(context)
        val psk = BirdoPqManager.performKeyExchange(context, response())

        assertEquals("the install keeps one keypair", first, second)
        assertEquals(1, keygens)
        assertArrayEquals(
            serverPsk(java.util.Base64.getDecoder().decode(second), ciphertext, nonce),
            java.util.Base64.getDecoder().decode(psk),
        )
    }

    // ── MR-2121: the stored public key must be the one inside the secret key ──

    /**
     * Port of desktop `read_keypair_rejects_public_key_that_disagrees_with_secret_key`.
     * The secret key is valid and passes FIPS 203 7.3 on its own (the fake
     * says so), so only comparing the two halves can catch this. Before the
     * fix the wrong public key was uploaded on every connect, and every
     * BirdoPQ connect failed until sign-out.
     */
    @Test
    fun `a stored pair whose public key disagrees with its secret key is discarded and re-keyed`() = runTest {
        installFakeKem()
        val good = fakeKeypair()
        val wrongPk = good.publicKey.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        store.save(RosenpassNative.StaticKeypair(wrongPk, good.secretKey.copyOf()))

        val uploaded = java.util.Base64.getDecoder().decode(BirdoPqManager.getClientPublicKeyB64(context))

        assertFalse("the disagreeing public key must not be uploaded", uploaded.contentEquals(wrongPk))
        assertEquals("re-keyed: one more keypair generated", 2, keygens)
        assertArrayEquals(generated.last().publicKey, uploaded)
        // The replacement is on disk and its halves agree.
        val reread = store.load()
        assertNotNull(reread)
        assertArrayEquals(uploaded, reread!!.publicKey)
        assertArrayEquals(
            uploaded,
            reread.secretKey.copyOfRange(EK_OFFSET, EK_OFFSET + RosenpassNative.PUBLIC_KEY_BYTES),
        )
        // And it decapsulates to the server's PSK.
        val psk = BirdoPqManager.performKeyExchange(context, response())
        assertArrayEquals(serverPsk(uploaded, ciphertext, nonce), java.util.Base64.getDecoder().decode(psk))
    }

    @Test
    fun `a stored public key of the wrong length is discarded and re-keyed`() = runTest {
        installFakeKem()
        val good = fakeKeypair()
        store.save(RosenpassNative.StaticKeypair(good.publicKey.copyOf(100), good.secretKey.copyOf()))

        val uploaded = java.util.Base64.getDecoder().decode(BirdoPqManager.getClientPublicKeyB64(context))

        assertEquals(RosenpassNative.PUBLIC_KEY_BYTES, uploaded.size)
        assertEquals(2, keygens)
    }

    @Test
    fun `a stored pair whose halves agree is used as is`() = runTest {
        installFakeKem()
        val good = fakeKeypair()
        store.save(RosenpassNative.StaticKeypair(good.publicKey.copyOf(), good.secretKey.copyOf()))

        val uploaded = java.util.Base64.getDecoder().decode(BirdoPqManager.getClientPublicKeyB64(context))

        assertArrayEquals(good.publicKey, uploaded)
        verify(exactly = 0) { RosenpassNative.generateKeypair() }
    }

    private companion object {
        /** FIPS 203 ML-KEM-1024: dk = dk_pke (1536) || ek (1568) || H(ek) (32) || z (32). */
        const val EK_OFFSET = 1536
    }
}
