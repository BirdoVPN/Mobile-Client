package app.birdo.vpn.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The sealed-blob format that replaced androidx.security's EncryptedFile /
 * EncryptedSharedPreferences. `[0x01][iv 12][ct || tag 16]`, AAD bound but
 * not stored. Runs on a JCE AES key; the Keystore only changes where the key
 * lives.
 */
class AesGcmSealerTest {

    private val keys = JceKeySource()
    private val sealer = AesGcmSealer(keys)
    private val aad = "purpose".toByteArray()

    @Test
    fun `round-trips plaintext`() {
        val plain = "eyJhbGciOiJIUzI1NiJ9.refresh-token".toByteArray()
        assertArrayEquals(plain, sealer.open(sealer.seal(plain, aad), aad))
    }

    @Test
    fun `round-trips the empty plaintext and large binary plaintext`() {
        assertArrayEquals(ByteArray(0), sealer.open(sealer.seal(ByteArray(0), aad), aad))
        val sk = ByteArray(3168) { (it * 7).toByte() } // ML-KEM-1024 secret key size
        assertArrayEquals(sk, sealer.open(sealer.seal(sk, aad), aad))
    }

    @Test
    fun `blob layout is version, 12-byte IV, ciphertext plus 16-byte tag`() {
        val plain = ByteArray(40) { it.toByte() }
        val blob = sealer.seal(plain, aad)
        assertEquals(1 + 12 + 40 + 16, blob.size)
        assertEquals(0x01.toByte(), blob[0])
    }

    @Test
    fun `every seal uses a fresh IV so equal plaintexts give different blobs`() {
        val plain = "same".toByteArray()
        val a = sealer.seal(plain, aad)
        val b = sealer.seal(plain, aad)
        assertFalse(a.contentEquals(b))
        assertNotEquals(a.copyOfRange(1, 13).toList(), b.copyOfRange(1, 13).toList())
    }

    @Test
    fun `AAD is bound - opening under a different AAD fails`() {
        val blob = sealer.seal("v".toByteArray(), "access_token".toByteArray())
        assertThrows(AesGcmSealer.SealException::class.java) {
            sealer.open(blob, "refresh_token".toByteArray())
        }
    }

    @Test
    fun `any flipped byte in IV, ciphertext or tag fails to open`() {
        val blob = sealer.seal("payload-bytes".toByteArray(), aad)
        for (i in 1 until blob.size) {
            val tampered = blob.copyOf().also { it[i] = (it[i].toInt() xor 0x40).toByte() }
            assertThrows("byte $i", AesGcmSealer.SealException::class.java) { sealer.open(tampered, aad) }
        }
    }

    @Test
    fun `unknown version byte is refused before any crypto`() {
        val blob = sealer.seal("x".toByteArray(), aad).also { it[0] = 0x02 }
        val e = assertThrows(AesGcmSealer.SealException::class.java) { sealer.open(blob, aad) }
        assertEquals("unknown blob version 2", e.message)
    }

    @Test
    fun `truncated blob is refused`() {
        val blob = sealer.seal("x".toByteArray(), aad)
        assertThrows(AesGcmSealer.SealException::class.java) { sealer.open(blob.copyOf(28), aad) }
        assertThrows(AesGcmSealer.SealException::class.java) { sealer.open(ByteArray(0), aad) }
    }

    @Test
    fun `a blob sealed under one key does not open under another`() {
        val blob = sealer.seal("secret".toByteArray(), aad)
        keys.destroy() // next key() generates a fresh key, as after a Keystore reset
        assertThrows(AesGcmSealer.SealException::class.java) { sealer.open(blob, aad) }
        assertEquals(2, keys.generated)
    }
}
