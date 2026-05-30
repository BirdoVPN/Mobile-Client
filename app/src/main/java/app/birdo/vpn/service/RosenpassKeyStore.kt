package app.birdo.vpn.service

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException

/**
 * On-device persistence for the long-lived Rosenpass static keypair.
 *
 * BirdoPQ v1 uses **ML-KEM-1024** (FIPS 203, formerly Kyber-1024). Key sizes
 * are 1568 B for the public key and 3168 B for the secret key. We generate
 * the pair **once per install** and store both halves on disk. The secret
 * half is wrapped in an Android Keystore-backed [EncryptedFile] so it is
 * protected at rest by hardware-backed AES-256-GCM.
 *
 * Both halves are kept under the app's private files directory, which is
 * inaccessible to other apps without root.
 *
 * ## Why two files instead of [EncryptedSharedPreferences]
 *
 * EncryptedSharedPreferences re-encrypts the ENTIRE preferences blob on
 * every commit and reads the whole thing into memory. The secret-key write
 * path is the only one that must be encrypted (the public key is, by
 * definition, public), so splitting into two files keeps the encrypted
 * write path minimal and avoids re-encrypting the public key on every save.
 */
internal class RosenpassKeyStore(context: Context) {

    private val ctx = context.applicationContext

    private val publicKeyFile: File
        get() = File(ctx.filesDir, "$DIR_NAME/$PUBLIC_KEY_FILENAME")

    private val secretKeyFile: File
        get() = File(ctx.filesDir, "$DIR_NAME/$SECRET_KEY_FILENAME")

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(ctx, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /** Returns the persisted keypair if both files exist and load successfully, else null. */
    fun load(): RosenpassNative.StaticKeypair? {
        return try {
            val pkFile = publicKeyFile
            val skFile = secretKeyFile
            if (!pkFile.exists() || !skFile.exists()) {
                Log.d(TAG, "no persisted Rosenpass keypair on disk")
                return null
            }

            val pkBytes = pkFile.readBytes()

            val encFile = EncryptedFile.Builder(
                ctx,
                skFile,
                masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
            ).build()
            val skBytes = encFile.openFileInput().use { it.readBytes() }

            Log.i(TAG, "loaded persisted Rosenpass keypair (pk=${pkBytes.size}B, sk=${skBytes.size}B)")
            RosenpassNative.StaticKeypair(publicKey = pkBytes, secretKey = skBytes)
        } catch (e: Exception) {
            // On any persistence failure (corrupt file, MasterKey mismatch from
            // device factory reset, etc.) we DELETE the partial state and return
            // null so the caller regenerates a fresh keypair. The server side
            // will then re-pin the new public key on next handshake.
            Log.w(TAG, "failed to load persisted keypair — deleting partial state", e)
            runCatching { publicKeyFile.delete() }
            runCatching { secretKeyFile.delete() }
            null
        }
    }

    /** Atomically persists the keypair. Throws on I/O failure. */
    @Throws(IOException::class)
    fun save(keypair: RosenpassNative.StaticKeypair) {
        val dir = File(ctx.filesDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("could not create $DIR_NAME directory")
        }

        // Write public key in plaintext — it IS public.
        val pkTmp = File(dir, "$PUBLIC_KEY_FILENAME.tmp")
        pkTmp.writeBytes(keypair.publicKey)
        if (!pkTmp.renameTo(publicKeyFile)) {
            pkTmp.delete()
            throw IOException("could not commit public key file")
        }

        // Write secret key through Keystore-backed encryption.
        // EncryptedFile won't overwrite, so delete any prior file first.
        val skFile = secretKeyFile
        if (skFile.exists() && !skFile.delete()) {
            throw IOException("could not remove prior secret key file")
        }
        val encFile = EncryptedFile.Builder(
            ctx,
            skFile,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
        encFile.openFileOutput().use { it.write(keypair.secretKey) }

        Log.i(TAG, "persisted Rosenpass keypair (pk=${keypair.publicKey.size}B, sk encrypted=${skFile.length()}B)")
    }

    /** Deletes the persisted keypair (e.g. on logout / "reset all data"). */
    fun clear() {
        runCatching { publicKeyFile.delete() }
        runCatching { secretKeyFile.delete() }
        Log.i(TAG, "cleared persisted Rosenpass keypair")
    }

    fun hasPersistedKeypair(): Boolean = publicKeyFile.exists() && secretKeyFile.exists()

    companion object {
        private const val TAG = "RosenpassKeyStore"
        private const val DIR_NAME = "rosenpass"
        private const val PUBLIC_KEY_FILENAME = "static.pk"
        private const val SECRET_KEY_FILENAME = "static.sk.enc"
        private const val MASTER_KEY_ALIAS = "birdo_rosenpass_master"
    }
}
