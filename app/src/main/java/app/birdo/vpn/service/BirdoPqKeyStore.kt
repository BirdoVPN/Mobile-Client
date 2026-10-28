package app.birdo.vpn.service

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import app.birdo.vpn.security.AesGcmSealer
import app.birdo.vpn.security.AndroidKeystoreKeySource
import app.birdo.vpn.security.LegacyEncryptedFileLeftover
import app.birdo.vpn.security.LegacyLeftover
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-device persistence for the long-lived BirdoPQ static keypair.
 *
 * BirdoPQ v1 uses **ML-KEM-1024** (FIPS 203, formerly Kyber-1024). Key sizes
 * are 1568 B for the public key and 3168 B for the secret key. We generate
 * the pair **once per install** and store both halves on disk. The secret
 * half is sealed with an Android Keystore-backed AES-256-GCM key
 * ([AesGcmSealer]) so it is protected at rest by hardware-backed encryption;
 * the sealing is bound to [SK_AAD] so the blob cannot be passed off as
 * anything else.
 *
 * Both halves are kept under the app's private files directory, which is
 * inaccessible to other apps without root.
 *
 * ## Layout
 *
 * - `rosenpass/static.pk` — plaintext public key (it IS public)
 * - `rosenpass/static.sk.v2` — sealed secret key
 * - `rosenpass/static.sk.enc` — the pre-1.4.28 androidx EncryptedFile.
 *   1.4.28 to 1.4.34 re-sealed it into `static.sk.v2` once; since MR-958 the
 *   library that could read it is gone, so one still on disk is deleted
 *   unread with its master key and the keypair is regenerated.
 *
 * ## Why two files instead of the token store
 *
 * Only the secret-key write path must be encrypted, and the two halves are
 * written together exactly once per install; a separate sealed file keeps the
 * encrypted write path minimal and independent of the token store's key.
 */
internal class BirdoPqKeyStore @VisibleForTesting internal constructor(
    context: Context,
    private val sealer: AesGcmSealer,
    legacyLeftover: LegacyLeftover?,
) {
    constructor(context: Context) : this(
        context,
        AesGcmSealer(AndroidKeystoreKeySource(KEY_ALIAS_V2)),
        null,
    )

    private val ctx = context.applicationContext

    private val dir: File
        get() = File(ctx.filesDir, DIR_NAME)

    private val publicKeyFile: File
        get() = File(dir, PUBLIC_KEY_FILENAME)

    private val secretKeyFile: File
        get() = File(dir, SECRET_KEY_FILENAME)

    private val legacySecretKeyFile: File
        get() = File(dir, LEGACY_SECRET_KEY_FILENAME)

    private val legacy: LegacyLeftover =
        legacyLeftover ?: LegacyEncryptedFileLeftover(ctx, legacySecretKeyFile, LEGACY_MASTER_KEY_ALIAS)

    /** Returns the persisted keypair if both files exist and load successfully, else null. */
    fun load(): RosenpassNative.StaticKeypair? {
        discardLegacySecretKey()

        val pkFile = publicKeyFile
        val skFile = secretKeyFile
        if (!pkFile.exists() || !skFile.exists()) {
            Log.d(TAG, "no persisted BirdoPQ keypair on disk")
            return null
        }

        // Step 1: read the (plaintext) public key. A failure here is almost
        // always a transient I/O condition (file briefly locked, disk busy) on
        // a file that has no cryptographic state to corrupt, so do NOT delete —
        // a valid keypair must survive a transient read error. Returning null
        // makes the caller regenerate for this session; the on-disk pair is left
        // intact for the next attempt. (A subsequent save() overwrites both
        // files anyway, so self-healing is not lost.)
        val pkBytes = try {
            pkFile.readBytes()
        } catch (e: IOException) {
            Log.w(TAG, "transient read error on public key — keeping on-disk state", e)
            return null
        }

        // Step 2: open the Keystore-sealed secret key. A failure here signals
        // genuine corruption or a key mismatch (e.g. device factory reset), so
        // DELETE the partial state and return null; the caller regenerates a
        // fresh keypair and the server re-pins the new public key on the next
        // handshake.
        return try {
            val skBytes = sealer.open(skFile.readBytes(), SK_AAD)
            Log.i(TAG, "loaded persisted BirdoPQ keypair (pk=${pkBytes.size}B, sk=${skBytes.size}B)")
            RosenpassNative.StaticKeypair(publicKey = pkBytes, secretKey = skBytes)
        } catch (e: Exception) {
            Log.w(TAG, "failed to open persisted secret key — deleting partial state", e)
            runCatching { publicKeyFile.delete() }
            runCatching { secretKeyFile.delete() }
            null
        }
    }

    /**
     * MR-958: a pre-1.4.28 `static.sk.enc` cannot be read any more (the
     * library that wrote it is gone), so it is deleted unread with its master
     * key.
     *
     * - No v2 secret key beside it: the public key on disk is half of a pair
     *   whose secret half is lost, so it goes too and the caller regenerates;
     *   the server re-pins the new public key on the next handshake. The same
     *   outcome an undecryptable legacy file always had.
     * - A v2 secret key beside it (1.4.28 to 1.4.34 re-sealed it but failed
     *   to delete the legacy file): the pair is intact; only the legacy file
     *   goes.
     */
    private fun discardLegacySecretKey() {
        if (!legacy.present()) return
        if (!secretKeyFile.exists()) {
            Log.w(TAG, "pre-1.4.28 BirdoPQ secret key cannot be read any more — regenerating keypair")
            runCatching { publicKeyFile.delete() }
        } else {
            Log.i(TAG, "v2 secret key already present — dropping legacy file")
        }
        legacy.discard()
    }

    /** Atomically persists the keypair. Throws on I/O failure. */
    @Throws(IOException::class)
    fun save(keypair: RosenpassNative.StaticKeypair) {
        val dir = dir
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("could not create $DIR_NAME directory")
        }

        // MR-2121: the secret key FIRST. The halves are two separate atomic
        // renames, so a save that fails or dies between them leaves one pair's
        // half beside the other pair's. BirdoPqManager rejects any pair whose
        // public key is not the one embedded in the secret key, and re-keys.
        writeSecretKey(keypair.secretKey)

        // Then the public key in plaintext — it IS public.
        atomicWrite(publicKeyFile, keypair.publicKey)

        // A fresh pair supersedes any legacy file still lying around.
        if (legacy.present()) legacy.discard()

        Log.i(TAG, "persisted BirdoPQ keypair (pk=${keypair.publicKey.size}B, sk sealed=${secretKeyFile.length()}B)")
    }

    @Throws(IOException::class)
    private fun writeSecretKey(secretKey: ByteArray) {
        atomicWrite(secretKeyFile, sealer.seal(secretKey, SK_AAD))
    }

    /** tmp + atomic rename so a crash mid-write never leaves a torn file behind. */
    @Throws(IOException::class)
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("could not commit ${target.name}", e)
        }
    }

    /** Deletes the persisted keypair (e.g. on logout / "reset all data"). */
    fun clear() {
        runCatching { publicKeyFile.delete() }
        runCatching { secretKeyFile.delete() }
        if (legacy.present()) legacy.discard()
        Log.i(TAG, "cleared persisted BirdoPQ keypair")
    }

    fun hasPersistedKeypair(): Boolean =
        publicKeyFile.exists() && secretKeyFile.exists()

    companion object {
        private const val TAG = "BirdoPqKeyStore"
        private const val DIR_NAME = "rosenpass"
        private const val PUBLIC_KEY_FILENAME = "static.pk"
        private const val SECRET_KEY_FILENAME = "static.sk.v2"
        private const val LEGACY_SECRET_KEY_FILENAME = "static.sk.enc"
        private const val KEY_ALIAS_V2 = "birdo_rosenpass_sk_v2"
        private const val LEGACY_MASTER_KEY_ALIAS = "birdo_rosenpass_master"
        private val SK_AAD = "birdo-rosenpass-static-sk-v1".toByteArray()
    }
}
