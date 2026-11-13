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
 * MR-703 / MR-1742). Every pair is held under the public key it uploaded, and
 * [BirdoPqManager.performKeyExchange] and [BirdoPqManager.stop] are given THEIR
 * generation's key.
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
        if (::context.isInitialized) {
            BirdoPqManager.discardKeypair(context)
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
        BirdoPqManager.elapsedMs = { clock }
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

    /** Upload one connect's key, as `VpnManager` does; returns the Base64 public key. */
    private suspend fun upload(): String = BirdoPqManager.getClientPublicKeyB64(context)!!

    // ── State ────────────────────────────────────────────────────────────

    @Test
    fun `initial state is DISABLED`() {
        assertEquals(BirdoPqManager.Mode.DISABLED, BirdoPqManager.modeFlow.value)
    }

    @Test
    fun `stop is idempotent and leaves DISABLED`() {
        repeat(3) { BirdoPqManager.stop() }
        repeat(3) { BirdoPqManager.stop("no-such-key") }
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

        val pk = upload()
        val psk = BirdoPqManager.performKeyExchange(context, response(), pk)

        assertNotNull("a bilateral PSK must be derived", psk)
        assertArrayEquals(
            "the client must derive the PSK the server derived for the UPLOADED key",
            serverPsk(unb64(pk), ciphertext, nonce),
            unb64(psk),
        )
        assertEquals(BirdoPqManager.Mode.BILATERAL, BirdoPqManager.modeFlow.value)
        assertEquals("one keypair for one connect", 1, keygens)
    }

    /** Two connects never share a key: the property this change exists for. */
    @Test
    fun `every connect uploads a different public key`() = runTest {
        installFakeKem()

        val first = upload()
        assertNotNull(BirdoPqManager.performKeyExchange(context, response(), first))
        val second = upload()
        val psk = BirdoPqManager.performKeyExchange(context, response(), second)

        assertNotEquals("a new connect must not reuse the last connect's key", first, second)
        assertEquals("one generation per connect", 2, keygens)
        assertArrayEquals(serverPsk(unb64(second), ciphertext, nonce), unb64(psk))
    }

    /** The secret was consumed by the first decapsulation; a second finds nothing and fails closed. */
    @Test
    fun `a pair decapsulates once`() = runTest {
        installFakeKem()

        val pk = upload()
        assertNotNull(BirdoPqManager.performKeyExchange(context, response(), pk))

        assertNull(BirdoPqManager.performKeyExchange(context, response(), pk))
        assertEquals(BirdoPqManager.Mode.DISABLED, BirdoPqManager.modeFlow.value)
        assertEquals("no key was minted behind the caller's back", 1, keygens)
    }

    @Test
    fun `a decapsulation with no uploaded key fails closed`() = runTest {
        installFakeKem()
        upload()

        assertNull(BirdoPqManager.performKeyExchange(context, response(), null))
        assertEquals(1, keygens)
    }

    /** MR-2123: the handed-off secret key does not outlive the decapsulation. */
    @Test
    fun `the secret key is zeroed once the decapsulation is done`() = runTest {
        installFakeKem()

        val pk = upload()
        val secret = generated.first().secretKey
        assertFalse(secret.all { it == 0.toByte() })

        assertNotNull(BirdoPqManager.performKeyExchange(context, response(), pk))
        assertTrue("the uploaded pair's secret key is still in memory", secret.all { it == 0.toByte() })
    }

    /** A decapsulation that fails (a bad ciphertext) still wipes the secret. */
    @Test
    fun `the secret key is zeroed when the decapsulation is refused`() = runTest {
        installFakeKem()

        val pk = upload()
        val secret = generated.first().secretKey

        val bad = response().copy(rosenpassPublicKey = b64(ByteArray(16)))
        assertNull(BirdoPqManager.performKeyExchange(context, bad, pk))
        assertTrue(secret.all { it == 0.toByte() })
    }

    // ── STRICT binding: a generation touches only its own pair ───────────

    /**
     * THE review race: the new dial (B) uploads its pair, then the OLD
     * generation's teardown runs stop() with ITS key (A). B's pair must
     * survive and decapsulate.
     */
    @Test
    fun `a stale generation's stop does not wipe the newer pair`() = runTest {
        installFakeKem()

        val old = upload() // the previous generation's pair, never consumed
        val newer = upload() // the new dial, uploaded before the old teardown runs
        val newerSecret = generated.last().secretKey

        BirdoPqManager.stop(old) // the stale teardown, naming ITS key

        assertFalse("the newer pair was wiped by a stale stop()", newerSecret.all { it == 0.toByte() })
        val psk = BirdoPqManager.performKeyExchange(context, response(), newer)
        assertNotNull(psk)
        assertArrayEquals(serverPsk(unb64(newer), ciphertext, nonce), unb64(psk))
    }

    /** The task's wording: upload A, a stale stop(A_old), then exchange A succeeds. */
    @Test
    fun `upload A, stale stop of another key, then exchange A succeeds`() = runTest {
        installFakeKem()

        val a = upload()
        BirdoPqManager.stop("A_old-not-a-pending-key")

        assertNotNull(BirdoPqManager.performKeyExchange(context, response(), a))
    }

    @Test
    fun `stop with a pair's own key wipes that pair`() = runTest {
        installFakeKem()

        val a = upload()
        val secret = generated.first().secretKey

        BirdoPqManager.stop(a)

        assertTrue(secret.all { it == 0.toByte() })
        assertNull(BirdoPqManager.performKeyExchange(context, response(), a))
    }

    /**
     * The user disconnects while connecting: the service's teardown of the
     * CURRENT generation names its own key and wipes the pair at once (no
     * grace period), so the decapsulation still to run finds nothing and
     * fails closed.
     */
    @Test
    fun `a disconnect while connecting wipes the pair at once`() = runTest {
        installFakeKem()

        val a = upload()
        val secret = generated.first().secretKey

        BirdoPqManager.stop(a) // the current generation's own teardown, no clock advance

        assertTrue("the connecting generation's secret survived its disconnect", secret.all { it == 0.toByte() })
        assertNull(BirdoPqManager.performKeyExchange(context, response(), a))
        assertEquals(BirdoPqManager.Mode.DISABLED, BirdoPqManager.modeFlow.value)
    }

    @Test
    fun `a mismatched key never wipes`() = runTest {
        installFakeKem()

        val a = upload()
        val secret = generated.first().secretKey

        BirdoPqManager.stop("not-the-key")
        BirdoPqManager.stop(null)
        BirdoPqManager.stop()
        BirdoPqManager.discardPending("not-the-key")
        BirdoPqManager.discardPending(null)

        assertFalse(secret.all { it == 0.toByte() })
        assertNotNull(BirdoPqManager.performKeyExchange(context, response(), a))
    }

    /** A decapsulation under one generation's key cannot consume another's pair. */
    @Test
    fun `each generation decapsulates with its own pair`() = runTest {
        installFakeKem()

        val a = upload()
        val b = upload()
        val pskB = BirdoPqManager.performKeyExchange(context, response(), b)
        val pskA = BirdoPqManager.performKeyExchange(context, response(), a)

        assertArrayEquals(serverPsk(unb64(b), ciphertext, nonce), unb64(pskB))
        assertArrayEquals(serverPsk(unb64(a), ciphertext, nonce), unb64(pskA))
    }

    @Test
    fun `discardPending drops only the named pair`() = runTest {
        installFakeKem()

        val a = upload()
        val b = upload()
        BirdoPqManager.discardPending(a)

        assertNull(BirdoPqManager.performKeyExchange(context, response(), a))
        assertNotNull(BirdoPqManager.performKeyExchange(context, response(), b))
    }

    // ── The backstops: bounded in count and age ──────────────────────────

    @Test
    fun `no more than four pairs wait, the oldest is wiped first`() = runTest {
        installFakeKem()

        val keys = (1..5).map { upload() }
        assertTrue("the oldest pair's secret is still in memory", generated.first().secretKey.all { it == 0.toByte() })
        assertNull(BirdoPqManager.performKeyExchange(context, response(), keys.first()))
        assertNotNull(BirdoPqManager.performKeyExchange(context, response(), keys.last()))
    }

    /** The monotonic clock, not the wall clock: an abandoned pair does not wait for ever. */
    @Test
    fun `a pair nobody consumed is wiped by the backstop once it is old`() = runTest {
        installFakeKem()

        val abandoned = upload()
        val secret = generated.first().secretKey
        clock += 10 * 60_000L
        BirdoPqManager.stop("some-other-generation") // any entry point prunes

        assertTrue(secret.all { it == 0.toByte() })
        assertNull(BirdoPqManager.performKeyExchange(context, response(), abandoned))
    }

    @Test
    fun `a fresh pair is not touched by the backstop`() = runTest {
        installFakeKem()

        val pk = upload()
        clock += 30_000L
        BirdoPqManager.stop("some-other-generation")

        assertNotNull(BirdoPqManager.performKeyExchange(context, response(), pk))
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

        upload()
        val afterFirst = purges
        assertEquals("both Keystore keys", BirdoPqLegacyPurge.KEYSTORE_ALIASES.size, afterFirst)
        upload()
        assertEquals("the second connect does not purge again", afterFirst, purges)
    }

    @Test
    fun `a key file an older build left is deleted and never read`() = runTest {
        installFakeKem()
        val dir = File(filesDir, "rosenpass").apply { mkdirs() }
        val planted = File(dir, "static.sk.v2").apply { writeBytes(ByteArray(3200) { 7 }) }
        File(dir, "static.pk").writeBytes(ByteArray(1568) { 7 })

        val uploaded = unb64(upload())

        assertFalse("the legacy secret key is still on disk", planted.exists())
        assertFalse("the legacy directory is gone", dir.exists())
        assertNotEquals("the legacy public key was adopted", 7.toByte(), uploaded[0])
    }

    @Test
    fun `sign-out purges again and drops every pending pair`() = runTest {
        installFakeKem()
        upload()
        upload()
        val inFlight = generated.map { it.secretKey }
        val before = purges
        // A file written after the once-per-process purge (a downgrade).
        File(filesDir, "rosenpass").apply { mkdirs() }.let { File(it, "static.sk.v2").writeBytes(ByteArray(10)) }

        BirdoPqManager.discardKeypair(context)

        assertTrue("sign-out left a connect's secret key in memory", inFlight.all { s -> s.all { it == 0.toByte() } })
        assertTrue("sign-out left a legacy key file", !File(filesDir, "rosenpass/static.sk.v2").exists())
        assertEquals("the Keystore keys are deleted again too", before + BirdoPqLegacyPurge.KEYSTORE_ALIASES.size, purges)
    }

    private companion object {
        /** FIPS 203 ML-KEM-1024: dk = dk_pke (1536) || ek (1568) || H(ek) (32) || z (32). */
        const val EK_OFFSET = 1536
    }
}
