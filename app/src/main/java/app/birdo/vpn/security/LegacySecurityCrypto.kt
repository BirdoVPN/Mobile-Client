// The ONLY remaining use of androidx.security:security-crypto. The library is
// deprecated upstream with no successor artifact; the app now encrypts with its
// own Keystore-backed AES-256-GCM (see AesGcmSealer / KeystoreSecureStore), but
// every install that predates that has its tokens and its Rosenpass secret key
// at rest in the old EncryptedSharedPreferences / EncryptedFile formats. This
// file reads those formats exactly once, so an upgrade keeps the user logged in
// and keeps the PQ keypair the servers have pinned, then destroys the legacy
// files and their master keys.
//
// Removal plan: once the release carrying this migration has been the minimum
// installed version for one full release cycle, delete this file, the
// `security-crypto` dependency and the `-dontwarn com.google.errorprone` lines
// in proguard-rules.pro. Fresh installs never touch this code path (both
// sources check for the legacy file before opening anything, so no legacy
// master key is ever created on a new device).
@file:Suppress("DEPRECATION")

package app.birdo.vpn.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

/** Legacy key/value secrets (EncryptedSharedPreferences), read once, then destroyed. */
interface LegacySecretSource {
    /**
     * Every string value in the legacy store, or `null` when there is no legacy
     * store to migrate. Throws when a legacy store exists but cannot be opened
     * (corrupt Tink keyset, missing master key) — the caller treats that as
     * "the data is already gone" and destroys it.
     */
    @Throws(Exception::class)
    fun readAll(): Map<String, String>?

    /** Delete the legacy file and its Keystore master key. Best effort, never throws. */
    fun destroy()
}

/** A single legacy blob (EncryptedFile), read once, then destroyed. */
interface LegacyBlobSource {
    /** The decrypted bytes, or `null` when there is no legacy file. Throws when it cannot be decrypted. */
    @Throws(Exception::class)
    fun read(): ByteArray?

    /** Delete the legacy file and its Keystore master key. Best effort, never throws. */
    fun destroy()
}

class LegacyEncryptedPrefsSource(
    private val context: Context,
    private val prefsName: String,
    private val masterKeyAlias: String,
) : LegacySecretSource {

    private val prefsFile: File
        get() = File(context.filesDir.parent, "shared_prefs/$prefsName.xml")

    override fun readAll(): Map<String, String>? {
        // Existence first: EncryptedSharedPreferences.create() on a missing
        // file would CREATE it, complete with a fresh master key, on every
        // fresh install.
        if (!prefsFile.exists()) return null
        val masterKey = MasterKey.Builder(context, masterKeyAlias)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return prefs.all.entries
            .mapNotNull { (k, v) -> if (v is String) k to v else null }
            .toMap()
    }

    override fun destroy() {
        runCatching { if (prefsFile.exists()) prefsFile.delete() }
            .onFailure { Log.w(TAG, "could not delete legacy prefs $prefsName", it) }
        deleteKeystoreAlias(masterKeyAlias)
        // MasterKey's own default alias, in case an older build ever used it.
        deleteKeystoreAlias(ANDROIDX_DEFAULT_ALIAS)
    }

    private companion object {
        const val TAG = "LegacyPrefs"
        const val ANDROIDX_DEFAULT_ALIAS = "_androidx_security_master_key_"
    }
}

class LegacyEncryptedFileSource(
    private val context: Context,
    private val file: File,
    private val masterKeyAlias: String,
) : LegacyBlobSource {

    override fun read(): ByteArray? {
        if (!file.exists()) return null
        val masterKey = MasterKey.Builder(context, masterKeyAlias)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encFile = EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
        return encFile.openFileInput().use { it.readBytes() }
    }

    override fun destroy() {
        runCatching { if (file.exists()) file.delete() }
            .onFailure { Log.w(TAG, "could not delete legacy file ${file.name}", it) }
        deleteKeystoreAlias(masterKeyAlias)
        // EncryptedFile kept its Tink keyset in a prefs file of its own,
        // wrapped by the master key just deleted — dead bytes now (seen left
        // behind on the S25 device test). The app has no other EncryptedFile.
        runCatching { context.deleteSharedPreferences(KEYSET_PREFS) }
            .onFailure { Log.w(TAG, "could not delete $KEYSET_PREFS", it) }
    }

    private companion object {
        const val TAG = "LegacyFile"
        const val KEYSET_PREFS = "__androidx_security_crypto_encrypted_file_pref__"
    }
}

private fun deleteKeystoreAlias(alias: String) {
    try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    } catch (e: Exception) {
        Log.w("LegacySecurityCrypto", "could not delete legacy master key $alias", e)
    }
}
