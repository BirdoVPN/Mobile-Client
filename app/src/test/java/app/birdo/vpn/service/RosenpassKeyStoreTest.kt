package app.birdo.vpn.service

import android.content.Context
import app.birdo.vpn.security.AesGcmSealer
import app.birdo.vpn.security.JceKeySource
import app.birdo.vpn.security.LegacyBlobSource
import io.mockk.every
import io.mockk.mockk
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

/**
 * RosenpassKeyStore against a real temp `filesDir`, a JCE key in place of the
 * Keystore, and a scripted legacy EncryptedFile source: the v2 layout, atomic
 * writes, corruption handling and the one-shot migration of the pre-v2
 * `static.sk.enc`.
 */
class RosenpassKeyStoreTest {

    /** What the old EncryptedFile would decrypt to, or throw. */
    private inner class FakeLegacy(private val bytes: ByteArray?, private val throws: Boolean = false) : LegacyBlobSource {
        var destroyed = 0
        var reads = 0
        override fun read(): ByteArray? {
            reads++
            if (throws) throw IllegalStateException("EncryptedFile: MAC check failed")
            return bytes
        }
        override fun destroy() {
            destroyed++
            legacyFile.delete()
        }
    }

    private lateinit var filesDir: File
    private lateinit var context: Context
    private val keys = JceKeySource()
    private val sealer = AesGcmSealer(keys)

    private val dir get() = File(filesDir, "rosenpass")
    private val pkFile get() = File(dir, "static.pk")
    private val skFile get() = File(dir, "static.sk.v2")
    private val legacyFile get() = File(dir, "static.sk.enc")

    private val pk = ByteArray(1568) { (it and 0xff).toByte() }
    private val sk = ByteArray(3168) { (it * 3 and 0xff).toByte() }
    private val keypair = RosenpassNative.StaticKeypair(pk, sk)

    @Before
    fun setup() {
        filesDir = Files.createTempDirectory("rp-keystore").toFile()
        context = mockk()
        every { context.applicationContext } returns context
        every { context.filesDir } returns filesDir
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun store(legacy: LegacyBlobSource = FakeLegacy(null)) = RosenpassKeyStore(context, sealer, legacy)

    // ── v2 layout ────────────────────────────────────────────────

    @Test
    fun `save then load round-trips through the sealed v2 file`() {
        val s = store()
        assertFalse(s.hasPersistedKeypair())
        assertNull(s.load())

        s.save(keypair)
        assertTrue(s.hasPersistedKeypair())
        assertArrayEquals(pk, pkFile.readBytes())
        assertEquals(1 + 12 + sk.size + 16, skFile.length().toInt())
        val onDisk = String(skFile.readBytes(), Charsets.ISO_8859_1)
        assertFalse("secret key is not on disk in the clear", onDisk.contains(String(sk.copyOf(64), Charsets.ISO_8859_1)))
        assertFalse(File(dir, "static.pk.tmp").exists())
        assertFalse(File(dir, "static.sk.v2.tmp").exists())

        assertEquals(keypair, s.load())
        // A second store over the same directory (next process) reads it too.
        assertEquals(keypair, store().load())
    }

    @Test
    fun `save overwrites an existing pair atomically`() {
        val s = store()
        s.save(keypair)
        val second = RosenpassNative.StaticKeypair(ByteArray(1568) { 9 }, ByteArray(3168) { 8 })
        s.save(second)
        assertEquals(second, s.load())
    }

    @Test
    fun `a tampered sealed secret key deletes both halves so the caller regenerates`() {
        val s = store()
        s.save(keypair)
        val bytes = skFile.readBytes()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
        skFile.writeBytes(bytes)

        assertNull(s.load())
        assertFalse(pkFile.exists())
        assertFalse(skFile.exists())
        assertFalse(s.hasPersistedKeypair())
    }

    @Test
    fun `a secret key sealed under another Keystore key deletes both halves`() {
        store().save(keypair)
        keys.destroy() // as after a factory reset / Keystore wipe
        assertNull(store().load())
        assertFalse(pkFile.exists() || skFile.exists())
    }

    @Test
    fun `a missing half is null without deleting the other`() {
        val s = store()
        s.save(keypair)
        skFile.delete()
        assertNull(s.load())
        assertTrue(pkFile.exists())
    }

    @Test
    fun `clear removes everything`() {
        val s = store()
        s.save(keypair)
        legacyFile.writeBytes(byteArrayOf(1, 2, 3))
        s.clear()
        assertFalse(pkFile.exists())
        assertFalse(skFile.exists())
        assertFalse(legacyFile.exists())
        assertFalse(s.hasPersistedKeypair())
    }

    // ── Legacy EncryptedFile migration ───────────────────────────

    private fun plantLegacy(): FakeLegacy {
        dir.mkdirs()
        pkFile.writeBytes(pk)
        legacyFile.writeBytes(ByteArray(3300) { 0x55 }) // opaque Tink ciphertext
        return FakeLegacy(sk)
    }

    @Test
    fun `legacy secret key is re-sealed into v2 and the legacy file destroyed on first load`() {
        val legacy = plantLegacy()
        val s = store(legacy)
        assertTrue("legacy pair counts as persisted", s.hasPersistedKeypair())

        assertEquals(keypair, s.load())
        assertEquals(1, legacy.reads)
        assertEquals(1, legacy.destroyed)
        assertFalse(legacyFile.exists())
        assertTrue(skFile.exists())
        assertArrayEquals(sk, sealer.open(skFile.readBytes(), "birdo-rosenpass-static-sk-v1".toByteArray()))

        // Second load is pure v2: the legacy source is not consulted again.
        assertEquals(keypair, s.load())
        assertEquals(1, legacy.reads)
    }

    @Test
    fun `an undecryptable legacy secret key deletes the pair so the caller regenerates`() {
        dir.mkdirs()
        pkFile.writeBytes(pk)
        legacyFile.writeBytes(ByteArray(64))
        val legacy = FakeLegacy(null, throws = true)
        val s = store(legacy)

        assertNull(s.load())
        assertEquals(1, legacy.destroyed)
        assertFalse(pkFile.exists())
        assertFalse(skFile.exists())
        assertFalse(legacyFile.exists())

        // …and the regenerated pair persists normally afterwards.
        s.save(keypair)
        assertEquals(keypair, s.load())
    }

    @Test
    fun `when v2 already exists a lingering legacy file is dropped, not re-read`() {
        val s0 = store()
        s0.save(keypair)
        legacyFile.writeBytes(ByteArray(64))
        val legacy = FakeLegacy(ByteArray(3168) { 1 }) // would be a DIFFERENT key
        val s = store(legacy)

        assertEquals(keypair, s.load())
        assertEquals(0, legacy.reads)
        assertEquals(1, legacy.destroyed)
        assertFalse(legacyFile.exists())
    }

    @Test
    fun `save over a legacy install replaces it and removes the legacy file`() {
        val legacy = plantLegacy()
        val s = store(legacy)
        val fresh = RosenpassNative.StaticKeypair(ByteArray(1568) { 2 }, ByteArray(3168) { 4 })
        s.save(fresh)
        assertEquals(1, legacy.destroyed)
        assertFalse(legacyFile.exists())
        assertEquals(fresh, s.load())
    }

    @Test
    fun `legacy migration without a public key still yields null and cleans up`() {
        dir.mkdirs()
        legacyFile.writeBytes(ByteArray(64))
        val legacy = FakeLegacy(sk)
        val s = store(legacy)
        assertNull(s.load())
        // The sk was re-sealed (nothing wrong with it) but the pair is incomplete.
        assertNotNull(skFile.takeIf { it.exists() })
        assertEquals(1, legacy.destroyed)
        assertFalse(s.hasPersistedKeypair())
    }
}
