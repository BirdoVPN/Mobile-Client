// MR-958 (owner decision 2026-10-28): androidx.security:security-crypto is
// gone. Builds up to 1.4.27 kept the session tokens (EncryptedSharedPreferences)
// and the BirdoPQ secret key (EncryptedFile) in that library's formats. 1.4.28
// to 1.4.34 carried a one-shot, in-place migration of both into the app's own
// Keystore AES-256-GCM sealing (AesGcmSealer / KeystoreSecureStore); that
// migration, LegacySecurityCrypto.kt, left with the library.
//
// Without the library those formats cannot be read. An install that goes
// straight from 1.4.27 or older to this build signs in again and makes a fresh
// BirdoPQ keypair, the same outcome as a Keystore reset. What it left on disk
// is deleted UNREAD, here: the files and the Keystore master keys that sealed
// them, so a stale sealed refresh token is not kept for nothing. Fresh installs
// and every install that ran 1.4.28 or later find nothing to delete.
package app.birdo.vpn.security

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyStore

/** What a build before 1.4.28 left at rest in security-crypto's formats. Deleted, never read. */
interface LegacyLeftover {
    /** Whether the legacy file is still on disk. A file check only: no Keystore access. */
    fun present(): Boolean

    /** Delete the file and its Keystore master key. Best effort, never throws. */
    fun discard()
}

/** The pre-1.4.28 token store: an EncryptedSharedPreferences file and its master key. */
class LegacyEncryptedPrefsLeftover(
    private val context: Context,
    private val prefsName: String,
    private val masterKeyAlias: String,
) : LegacyLeftover {

    override fun present(): Boolean =
        runCatching { File(context.filesDir.parent, "shared_prefs/$prefsName.xml").exists() }.getOrDefault(false)

    override fun discard() {
        runCatching { context.deleteSharedPreferences(prefsName) }
            .onFailure { Log.w(TAG, "could not delete legacy prefs $prefsName", it) }
        deleteKeystoreAlias(masterKeyAlias)
        // security-crypto's own default alias, in case an older build ever used it.
        deleteKeystoreAlias(ANDROIDX_DEFAULT_ALIAS)
    }

    private companion object {
        const val TAG = "LegacyPrefs"
        const val ANDROIDX_DEFAULT_ALIAS = "_androidx_security_master_key_"
    }
}

/** A pre-1.4.28 EncryptedFile, its master key and the Tink keyset prefs it kept beside them. */
class LegacyEncryptedFileLeftover(
    private val context: Context,
    private val file: File,
    private val masterKeyAlias: String,
) : LegacyLeftover {

    override fun present(): Boolean = runCatching { file.exists() }.getOrDefault(false)

    override fun discard() {
        runCatching { if (file.exists()) file.delete() }
            .onFailure { Log.w(TAG, "could not delete legacy file ${file.name}", it) }
        deleteKeystoreAlias(masterKeyAlias)
        // EncryptedFile kept its Tink keyset in a prefs file of its own,
        // wrapped by the master key just deleted (seen left behind on the S25
        // device test). The app had no other EncryptedFile.
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
        Log.w("LegacyLeftovers", "could not delete legacy master key $alias", e)
    }
}
