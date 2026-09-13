package app.birdo.vpn.data.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.annotation.VisibleForTesting
import app.birdo.vpn.security.AesGcmSealer
import app.birdo.vpn.security.AndroidKeystoreKeySource
import app.birdo.vpn.security.InMemorySecureStore
import app.birdo.vpn.security.KeystoreSecureStore
import app.birdo.vpn.security.LegacyEncryptedPrefsSource
import app.birdo.vpn.security.LegacySecretSource
import app.birdo.vpn.security.SecureStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Sentry
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure token storage — the Android twin of the desktop client's Windows
 * Credential Manager and the iOS Keychain.
 *
 * Values live in a plain SharedPreferences file (`birdo_vpn_secure_v2`) where
 * every value is sealed with an Android Keystore AES-256-GCM key
 * ([KeystoreSecureStore]); the key never leaves the Keystore. This replaced
 * androidx.security's deprecated EncryptedSharedPreferences; the legacy file
 * is migrated in place, once, by [migrateLegacyStore].
 *
 * Includes recovery logic for Android Keystore corruption — a known issue on
 * Samsung devices (Knox key rotation, OS updates) where the key cannot be used
 * at all. Without it the Hilt singleton init threw and the app crashed on
 * launch; with it the user is logged out and the app opens.
 */
@Singleton
class TokenManager @VisibleForTesting internal constructor(
    private val backend: Backend,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(AndroidBackend(context))

    /**
     * What the manager needs from the platform: a way to open the sealed store
     * (throwing if the Keystore cannot be used), a way to wipe it after a
     * corruption, and the legacy store to migrate from.
     */
    interface Backend {
        @Throws(Exception::class)
        fun open(): SecureStore

        /** Delete the Keystore key and the v2 file so [open] starts from scratch. */
        fun reset()

        val legacy: LegacySecretSource
    }

    companion object {
        private const val TAG = "TokenManager"
        internal const val PREFS_NAME_V2 = "birdo_vpn_secure_v2"
        internal const val KEY_ALIAS_V2 = "birdo_secure_store_v2"
        internal const val LEGACY_PREFS_NAME = "birdo_vpn_secure_prefs"
        internal const val LEGACY_MASTER_KEY_ALIAS = "_birdo_master_key_"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_WG_PRIVATE_KEY = "wireguard_private_key"
        private const val KEY_LAST_KEY_ID = "last_key_id"
        private const val KEY_PENDING_ANON_ID = "pending_anonymous_id"
        /** Maximum sane token length — prevents storage of garbage data */
        private const val MAX_TOKEN_LENGTH = 4096

        /**
         * Copy every value of the legacy EncryptedSharedPreferences file into
         * [store], verify each one reads back, then destroy the legacy file
         * and its master key. Idempotent: with no legacy file it does nothing.
         *
         * - Legacy exists but cannot be opened → the data was already
         *   unrecoverable (the old code path deleted it and re-logged the user
         *   in); destroy it and move on.
         * - [store] already holds any of the legacy keys → a previous run
         *   copied but failed to destroy; do NOT overwrite (the user may have
         *   logged in since, and a stale single-use refresh token written over
         *   a fresh one trips the server's theft detection). Just destroy.
         * - A copied value does not read back → undo the copies and leave the
         *   legacy file for the next launch; nothing is destroyed.
         */
        @VisibleForTesting
        internal fun migrateLegacyStore(store: SecureStore, legacy: LegacySecretSource): Migration {
            val values = try {
                legacy.readAll() ?: return Migration.NOTHING_TO_DO
            } catch (e: Exception) {
                Log.w(TAG, "legacy secure prefs exist but cannot be opened — discarding them", e)
                legacy.destroy()
                return Migration.LEGACY_UNREADABLE
            }
            if (values.keys.any { store.contains(it) }) {
                Log.i(TAG, "v2 store already populated — dropping the legacy copy")
                legacy.destroy()
                return Migration.ALREADY_MIGRATED
            }
            for ((k, v) in values) store.put(k, v, commit = true)
            val bad = values.entries.firstOrNull { (k, v) -> store.get(k) != v }
            if (bad != null) {
                Log.e(TAG, "migrated value '${bad.key}' did not read back — keeping the legacy store")
                for (k in values.keys) store.remove(k, commit = true)
                return Migration.VERIFY_FAILED
            }
            legacy.destroy()
            Log.i(TAG, "migrated ${values.size} legacy secure value(s) to the v2 store")
            return Migration.MIGRATED
        }
    }

    internal enum class Migration { NOTHING_TO_DO, MIGRATED, ALREADY_MIGRATED, LEGACY_UNREADABLE, VERIFY_FAILED }

    /** True when Keystore recovery failed and we fell back to non-persistent storage. */
    @Volatile
    private var usingInsecureFallback = false

    /**
     * Mutable so that tryMigrateToEncrypted() can swap back from the in-memory
     * fallback to the Keystore-backed store after a recovery.
     */
    @Volatile
    private var store: SecureStore = openWithRecovery()

    private fun openWithRecovery(): SecureStore {
        val opened = try {
            backend.open()
        } catch (e: Exception) {
            Log.e(TAG, "secure store failed to open — recovering from Keystore corruption", e)
            backend.reset()
            try {
                backend.open()
            } catch (e2: Exception) {
                Log.e(TAG, "Recovery failed — using empty in-memory store (user must re-login)", e2)
                Sentry.captureException(e2)
                usingInsecureFallback = true
                // F-15: never fall back to plaintext SharedPreferences. A
                // WireGuard private key in plaintext XML is unacceptable; an
                // empty in-memory store that forces a re-login is not.
                return InMemorySecureStore()
            }
        }
        try {
            migrateLegacyStore(opened, backend.legacy)
        } catch (e: Exception) {
            // Migration must never take the app down with it.
            Log.e(TAG, "legacy migration threw — continuing with whatever is in the v2 store", e)
            Sentry.captureException(e)
        }
        return opened
    }

    // ── Access Token ─────────────────────────────────────────────

    fun getAccessToken(): String? = store.get(KEY_ACCESS_TOKEN)

    fun setAccessToken(token: String) {
        require(token.length <= MAX_TOKEN_LENGTH) { "Access token exceeds max length" }
        store.put(KEY_ACCESS_TOKEN, token)
    }

    // ── Refresh Token ────────────────────────────────────────────

    fun getRefreshToken(): String? = store.get(KEY_REFRESH_TOKEN)

    fun setRefreshToken(token: String) {
        require(token.length <= MAX_TOKEN_LENGTH) { "Refresh token exceeds max length" }
        // commit: a refresh token is single-use server-side — losing this write
        // to a process kill replays the consumed token and trips theft detection.
        store.put(KEY_REFRESH_TOKEN, token, commit = true)
    }

    // ── Token Pair ───────────────────────────────────────────────

    fun setTokens(accessToken: String, refreshToken: String) {
        require(accessToken.length <= MAX_TOKEN_LENGTH) { "Access token exceeds max length" }
        require(refreshToken.length <= MAX_TOKEN_LENGTH) { "Refresh token exceeds max length" }
        // If we were on the insecure fallback, try to migrate back to encrypted storage
        if (usingInsecureFallback) {
            tryMigrateToEncrypted()
        }
        // commit (synchronous) instead of apply — the caller reads tokens
        // immediately after this call (e.g. fetchProfileAfterLogin), so the
        // write must be visible before we return.
        store.put(KEY_ACCESS_TOKEN, accessToken, commit = true)
        store.put(KEY_REFRESH_TOKEN, refreshToken, commit = true)
    }

    // ── WireGuard Key ────────────────────────────────────────────

    fun getWireGuardPrivateKey(): String? = store.get(KEY_WG_PRIVATE_KEY)

    fun setWireGuardPrivateKey(key: String) {
        store.put(KEY_WG_PRIVATE_KEY, key)
    }

    /**
     * FIX-1-8: Clear stored WG private key after disconnect.
     * Prevents key material from persisting at rest after the VPN session ends.
     */
    fun clearWireGuardPrivateKey() {
        store.remove(KEY_WG_PRIVATE_KEY)
    }

    // NOTE: the "last server" concept lives in AppPreferences.lastServerId
    // (key "last_server_id") — the getLastServer/setLastServer pair that used
    // to sit here was a second, never-read store of the same idea (multi-hop
    // connects never wrote it), removed as a single-source-of-truth trap.

    // ── Last Key ID (for disconnect) ─────────────────────────────

    fun getLastKeyId(): String? = store.get(KEY_LAST_KEY_ID)

    fun setLastKeyId(keyId: String) {
        store.put(KEY_LAST_KEY_ID, keyId)
    }

    /** Clear after disconnect so heartbeats stop carrying a stale key id. */
    fun clearLastKeyId() {
        store.remove(KEY_LAST_KEY_ID)
    }

    // ── Pending anonymous ID (created, not yet acknowledged) ─────
    //
    // A freshly minted anonymous account's 24-digit ID is the account's ONLY
    // credential — there is no email and no reset path — and the server returns
    // it exactly once, in the POST /auth/anonymous/register response. The
    // register call also stores tokens, so a process death between "account
    // created" and "user has written the ID down" used to leave the app silently
    // logged in to an account whose ID nobody had ever seen: the next token
    // clear (reinstall, storage wipe, Keystore corruption) destroyed the account
    // permanently.
    //
    // Parking the ID here — the same sealed store as the tokens it was issued
    // alongside, mirroring the iOS Keychain — means a config change or a
    // process kill mid-dialog re-surfaces the "save your ID" step on next launch
    // instead of swallowing it. It is deliberately NOT plaintext prefs: this
    // string authenticates the account, exactly like the refresh token.

    /** The minted ID the user has not yet confirmed saving, or null. */
    fun getPendingAnonymousId(): String? = store.get(KEY_PENDING_ANON_ID)

    /**
     * Park a freshly minted ID until the user acknowledges it. commit rather
     * than apply: the very next thing that happens is the ID being shown to a
     * user who may kill the app a moment later, so the write has to already be
     * on disk — an async apply could still be in flight.
     */
    fun setPendingAnonymousId(anonymousId: String) {
        store.put(KEY_PENDING_ANON_ID, anonymousId, commit = true)
    }

    /** Called once the user has confirmed they saved the ID. */
    fun clearPendingAnonymousId() {
        store.remove(KEY_PENDING_ANON_ID, commit = true)
    }

    // ── Clear All ────────────────────────────────────────────────

    fun clearAll() {
        store.clear()
    }

    fun isLoggedIn(): Boolean {
        val accessToken = getAccessToken()
        if (accessToken != null && !isTokenExpired(accessToken)) return true

        val refreshToken = getRefreshToken()
        return refreshToken != null && !isTokenExpired(refreshToken)
    }

    /**
     * Check whether a JWT has expired by decoding the payload and comparing
     * the `exp` claim against the current system time.
     * Returns true if expired or unparseable (fail-safe).
     */
    private fun isTokenExpired(jwt: String): Boolean {
        return try {
            val parts = jwt.split(".")
            if (parts.size != 3) return true
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
            val exp = JSONObject(payload).optLong("exp", 0L)
            if (exp == 0L) return true
            System.currentTimeMillis() / 1000 >= exp
        } catch (e: Exception) {
            // Fail-safe: treat unparseable tokens as expired. Log for observability
            // so a server returning malformed tokens (or repeated forced refreshes)
            // is visible rather than silently swallowed.
            Log.w(TAG, "Token expiry check failed; treating as expired: ${e.message}")
            true
        }
    }

    // ── Recovery ─────────────────────────────────────────────────

    /**
     * Attempt to move back from the in-memory fallback to the Keystore-backed
     * store. Called automatically on the next setTokens() after a fallback.
     */
    private fun tryMigrateToEncrypted() {
        try {
            val persistent = backend.open()
            // Swap the backing store so subsequent reads/writes are persisted
            store = persistent
            usingInsecureFallback = false
            Log.i(TAG, "Keystore-backed store recovered — tokens will be persisted")
        } catch (e: Exception) {
            Log.w(TAG, "Migration back to persistent storage failed — staying on fallback", e)
        }
    }
}

/**
 * The production [TokenManager.Backend]: a Keystore AES-256-GCM key under
 * [TokenManager.KEY_ALIAS_V2] sealing values into the plain prefs file
 * [TokenManager.PREFS_NAME_V2], with the pre-v2 EncryptedSharedPreferences
 * file as the legacy source.
 */
private class AndroidBackend(private val context: Context) : TokenManager.Backend {

    override val legacy: LegacySecretSource =
        LegacyEncryptedPrefsSource(context, TokenManager.LEGACY_PREFS_NAME, TokenManager.LEGACY_MASTER_KEY_ALIAS)

    override fun open(): SecureStore {
        val keys = AndroidKeystoreKeySource(TokenManager.KEY_ALIAS_V2)
        val sealer = AesGcmSealer(keys)
        // Probe: force key generation and one seal/open round trip NOW, so a
        // Keystore that cannot be used fails here — where the caller can
        // recover — rather than on the first read after launch.
        val aad = "probe".toByteArray()
        val probe = sealer.open(sealer.seal(PROBE, aad), aad)
        check(probe.contentEquals(PROBE)) { "Keystore AES-GCM round trip did not reproduce the plaintext" }
        return KeystoreSecureStore(context.getSharedPreferences(TokenManager.PREFS_NAME_V2, Context.MODE_PRIVATE), sealer)
    }

    override fun reset() {
        try {
            AndroidKeystoreKeySource(TokenManager.KEY_ALIAS_V2).destroy()
            Log.i(TAG, "Deleted Keystore entry ${TokenManager.KEY_ALIAS_V2}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Keystore entry", e)
        }
        try {
            // Cannot go through SharedPreferences.edit().clear() — the goal is
            // to also discard whatever cached, possibly-corrupt state the
            // framework holds for the file, so remove the file itself.
            if (context.deleteSharedPreferences(TokenManager.PREFS_NAME_V2)) {
                Log.i(TAG, "Deleted prefs file ${TokenManager.PREFS_NAME_V2}")
            } else {
                val file = File(context.filesDir.parent, "shared_prefs/${TokenManager.PREFS_NAME_V2}.xml")
                if (file.exists() && file.delete()) Log.i(TAG, "Deleted prefs file ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete prefs file", e)
        }
    }

    private companion object {
        const val TAG = "TokenManager"
        val PROBE = "birdo-secure-store-probe".toByteArray()
    }
}
