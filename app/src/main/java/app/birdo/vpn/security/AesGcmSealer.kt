package app.birdo.vpn.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the AES-256 key comes from. The app uses [AndroidKeystoreKeySource];
 * unit tests hand [AesGcmSealer] a plain JCE key, which exercises every byte
 * of the sealing format without an Android Keystore.
 */
interface SecretKeySource {
    /** The key, created on first use. Throws if the key cannot be obtained. */
    fun key(): SecretKey

    /** Forget the key so the next [key] call creates a fresh one. */
    fun destroy()
}

/**
 * An AES-256-GCM key that lives in the Android Keystore under [alias]:
 * hardware-backed where the device has a StrongBox/TEE, never exportable, and
 * generated with a randomised IV per operation (the Keystore refuses a
 * caller-supplied IV for such keys, which is exactly what we want).
 *
 * This is the replacement for androidx.security's `MasterKey` — same key
 * shape (AES256_GCM in the Keystore), without the deprecated wrapper library.
 * No user-authentication requirement, matching the old MasterKey behaviour:
 * the app lock is a separate, UI-level control (see MainActivity).
 */
class AndroidKeystoreKeySource(private val alias: String) : SecretKeySource {

    override fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Randomised encryption is the default and is required for GCM
                // keys in the Keystore; stated so nobody "optimises" it away.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    override fun destroy() {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        } catch (_: Exception) {
            // KeyStore.load() can also throw IOException; either way a key we
            // cannot enumerate cannot be used, so there is nothing to do.
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * AES-256-GCM sealing with a versioned, self-describing blob:
 *
 * ```
 * byte 0      : format version (0x01)
 * bytes 1..12 : 12-byte IV (random, from the cipher)
 * bytes 13..  : ciphertext || 16-byte GCM tag
 * ```
 *
 * The `aad` (additional authenticated data) is bound into the tag but not
 * stored: the caller supplies it again on [open]. Both stores use it to bind
 * a value to its own name / purpose, so a sealed value copied under another
 * key does not decrypt.
 *
 * Every failure to open — wrong key, wrong AAD, flipped byte, truncated blob,
 * unknown version — surfaces as [SealException]; callers treat that as
 * "this value is gone", never as "try harder".
 */
class AesGcmSealer(private val keys: SecretKeySource) {

    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        // No IV passed: the Keystore (and any well-behaved provider) generates a
        // fresh random one, which we read back and prefix to the blob.
        cipher.init(Cipher.ENCRYPT_MODE, keys.key())
        val iv = cipher.iv
        check(iv.size == IV_LEN) { "unexpected GCM IV length ${iv.size}" }
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plaintext)
        return ByteArray(1 + IV_LEN + ct.size).also {
            it[0] = VERSION
            System.arraycopy(iv, 0, it, 1, IV_LEN)
            System.arraycopy(ct, 0, it, 1 + IV_LEN, ct.size)
        }
    }

    @Throws(SealException::class)
    fun open(blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < 1 + IV_LEN + TAG_LEN) throw SealException("blob too short (${blob.size} B)")
        if (blob[0] != VERSION) throw SealException("unknown blob version ${blob[0]}")
        val iv = blob.copyOfRange(1, 1 + IV_LEN)
        val ct = blob.copyOfRange(1 + IV_LEN, blob.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_LEN * 8, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ct)
        } catch (e: GeneralSecurityException) {
            throw SealException("authentication failed", e)
        }
    }

    class SealException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val VERSION: Byte = 0x01
        const val IV_LEN = 12
        const val TAG_LEN = 16
    }
}
