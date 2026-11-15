package app.birdo.vpn.service

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import app.birdo.vpn.security.AndroidKeystoreKeySource
import java.io.File

/**
 * Deletes what builds before per-connect PQ keys (MR-703 / MR-1742) kept at
 * rest: the ML-KEM-1024 keypair that `BirdoPqKeyStore` persisted once per
 * install, and the Android Keystore keys that sealed it.
 *
 * Nothing writes any of it any more. A BirdoPQ keypair now exists for ONE
 * connect, in memory ([BirdoPqManager]'s hand-off), so a stolen backup or a
 * rooted read of `filesDir` yields no key for any session. This class exists
 * only to remove the old item, once per process, on every install that has it
 * (and quietly find nothing on the rest).
 *
 * ## What it deletes
 *
 * - `filesDir/rosenpass/static.pk`, `static.sk.v2` (the Keystore-sealed secret
 *   key), `static.sk.enc` (the pre-1.4.28 androidx EncryptedFile, which MR-958
 *   already stopped reading), and any `*.tmp` an interrupted atomic write left;
 *   then the directory itself when nothing else is in it.
 * - The Keystore keys `birdo_rosenpass_sk_v2` (sealed the v2 file) and
 *   `birdo_rosenpass_master` (the pre-1.4.28 master key).
 *
 * Best effort and idempotent: a failure is logged, never thrown, and the next
 * process tries again.
 */
internal class BirdoPqLegacyPurge @VisibleForTesting internal constructor(
    private val dir: File,
    // Before the alias deleter, so a trailing lambda at a call site still
    // binds to deleteKeystoreAlias (as the last parameter it captured them).
    private val deleteKeysetPrefs: () -> Unit = {},
    private val deleteKeystoreAlias: (String) -> Unit,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIR_NAME),
        { context.applicationContext.deleteSharedPreferences(LEGACY_KEYSET_PREFS) },
        { alias -> AndroidKeystoreKeySource(alias).destroy() },
    )

    /** Returns how many files were deleted. */
    fun purge(): Int {
        var deleted = 0
        val entries = runCatching { dir.listFiles() }.getOrNull()
        if (entries != null) {
            for (f in entries) {
                if (!f.isFile || !(f.name.startsWith(KEY_FILE_PREFIX) || f.name.endsWith(".tmp"))) continue
                if (runCatching { f.delete() }.getOrDefault(false)) deleted++
                else Log.w(TAG, "legacy PQ key file ${f.name} was not deleted")
            }
            // Only an empty directory goes: never someone else's files.
            runCatching { if (dir.list()?.isEmpty() == true) dir.delete() }
        }
        for (alias in KEYSTORE_ALIASES) {
            runCatching { deleteKeystoreAlias(alias) }
                .onFailure { Log.w(TAG, "legacy PQ Keystore key $alias was not deleted", it) }
        }
        // The Tink keyset prefs the pre-1.4.28 EncryptedFile kept beside its
        // master key (wrapped by the key just deleted). The app had no other
        // EncryptedFile; an upgrade from 1.4.27 or older would leave it behind.
        runCatching { deleteKeysetPrefs() }
            .onFailure { Log.w(TAG, "legacy PQ keyset prefs were not deleted", it) }
        if (deleted > 0) Log.i(TAG, "deleted $deleted legacy BirdoPQ key file(s) (per-connect keys)")
        return deleted
    }

    companion object {
        private const val TAG = "BirdoPqLegacyPurge"
        internal const val DIR_NAME = "rosenpass"
        private const val KEY_FILE_PREFIX = "static."
        private const val LEGACY_KEYSET_PREFS = "__androidx_security_crypto_encrypted_file_pref__"
        internal val KEYSTORE_ALIASES = listOf("birdo_rosenpass_sk_v2", "birdo_rosenpass_master")
    }
}
