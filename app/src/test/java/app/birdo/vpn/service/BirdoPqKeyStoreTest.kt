package app.birdo.vpn.service

import android.content.Context
import app.birdo.vpn.security.AesGcmSealer
import app.birdo.vpn.security.FlakyKeySource
import app.birdo.vpn.security.JceKeySource
import app.birdo.vpn.security.LegacyLeftover
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.ProviderException

/**
 * BirdoPqKeyStore against a real temp `filesDir`, a JCE key in place of the
 * Keystore, and a scripted legacy leftover: the v2 layout, atomic writes,
 * corruption handling, and deleting a pre-1.4.28 `static.sk.enc` unread
 * (MR-958).
 */
class BirdoPqKeyStoreTest {

    /** The pre-1.4.28 EncryptedFile: present while `static.sk.enc` is on disk. */
    private inner class FakeLegacy : LegacyLeftover {
        var discarded = 0
        override fun present(): Boolean = legacyFile.exists()
        override fun discard() {
            discarded++
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

    private fun store(legacy: LegacyLeftover = FakeLegacy()) = BirdoPqKeyStore(context, sealer, legacy)

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

    /**
     * MR-2121: the halves are two separate atomic renames. A save whose
     * Keystore seal fails must not have replaced the public key already: a new
     * public key beside the old secret key is a pair that passes FIPS 203 7.3
     * and uploads a key this client cannot decapsulate for. Secret key first.
     */
    @Test
    fun `a save whose secret-key write fails leaves the previous public key in place`() {
        val keystore = FlakyKeySource()
        val s = BirdoPqKeyStore(context, AesGcmSealer(keystore), FakeLegacy())
        s.save(keypair)

        keystore.failing = true
        val next = RosenpassNative.StaticKeypair(ByteArray(1568) { 9 }, ByteArray(3168) { 8 })
        assertThrows(ProviderException::class.java) { s.save(next) }

        assertArrayEquals("the public key of the pair still on disk", pk, pkFile.readBytes())
        keystore.failing = false
        assertEquals(keypair, s.load())
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

    // ── Pre-1.4.28 EncryptedFile (MR-958) ────────────────────────

    private fun plantLegacy(): FakeLegacy {
        dir.mkdirs()
        pkFile.writeBytes(pk)
        legacyFile.writeBytes(ByteArray(3300) { 0x55 }) // opaque Tink ciphertext
        return FakeLegacy()
    }

    @Test
    fun `a pre-1_4_28 secret key is deleted unread with its public key so the caller regenerates`() {
        val legacy = plantLegacy()
        val s = store(legacy)
        assertFalse("a pair whose secret half is unreadable is not persisted", s.hasPersistedKeypair())

        assertNull(s.load())
        assertEquals(1, legacy.discarded)
        assertFalse(legacyFile.exists())
        assertFalse("the orphaned public key goes too", pkFile.exists())
        assertFalse(skFile.exists())

        // …and the regenerated pair persists normally afterwards.
        s.save(keypair)
        assertEquals(keypair, s.load())
        assertEquals(1, legacy.discarded)
    }

    @Test
    fun `when v2 already exists a lingering legacy file is dropped and the pair kept`() {
        store().save(keypair)
        legacyFile.writeBytes(ByteArray(64))
        val legacy = FakeLegacy()

        assertEquals(keypair, store(legacy).load())
        assertEquals(1, legacy.discarded)
        assertFalse(legacyFile.exists())
        assertTrue(pkFile.exists() && skFile.exists())
    }

    @Test
    fun `save over a legacy install replaces it and removes the legacy file`() {
        val legacy = plantLegacy()
        val s = store(legacy)
        val fresh = RosenpassNative.StaticKeypair(ByteArray(1568) { 2 }, ByteArray(3168) { 4 })
        s.save(fresh)
        assertEquals(1, legacy.discarded)
        assertFalse(legacyFile.exists())
        assertEquals(fresh, s.load())
    }

    @Test
    fun `a legacy file alone is deleted and loads nothing`() {
        dir.mkdirs()
        legacyFile.writeBytes(ByteArray(64))
        val legacy = FakeLegacy()
        val s = store(legacy)
        assertNull(s.load())
        assertEquals(1, legacy.discarded)
        assertFalse(legacyFile.exists())
        assertFalse(skFile.exists())
        assertFalse(s.hasPersistedKeypair())
    }
}
