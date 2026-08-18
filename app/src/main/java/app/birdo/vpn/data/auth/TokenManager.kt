package app.birdo.vpn.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.sentry.Sentry
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure token storage using Android EncryptedSharedPreferences.
 * Equivalent to Windows Credential Manager in the desktop client.
 *
 * Includes recovery logic for Android Keystore corruption — a known issue
 * on Samsung devices (Knox key rotation, OS updates, etc.) that causes
 * EncryptedSharedPreferences to throw on creation and crash the app.
 */
@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_NAME = "birdo_vpn_secure_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_WG_PRIVATE_KEY = "wireguard_private_key"
        private const val KEY_LAST_SERVER = "last_server"
        private const val KEY_LAST_KEY_ID = "last_key_id"
        private const val KEY_PENDING_ANON_ID = "pending_anonymous_id"
        private const val MASTER_KEY_ALIAS = "_birdo_master_key_"
        /** Maximum sane token length — prevents storage of garbage data */
        private const val MAX_TOKEN_LENGTH = 4096
    }

    /** True when Keystore recovery failed and we fell back to unencrypted storage. */
    @Volatile
    private var usingInsecureFallback = false

    /**
     * Lazily create EncryptedSharedPreferences with Keystore corruption recovery.
     *
     * Samsung Galaxy S25 Ultra / One UI 7 (and other Samsung devices) can corrupt
     * the Android Keystore during OS updates or Knox key rotation. When this happens,
     * MasterKey.Builder.build() or EncryptedSharedPreferences.create() throws, which
     * previously crashed the entire app on startup (Hilt singleton init).
     *
     * Recovery strategy:
     * 1. Try to create normally
     * 2. On failure → delete the corrupted Keystore entry + encrypted prefs file
     * 3. Recreate from scratch (user will need to log in again, but the app opens)
     */
    /**
     * Mutable so that tryMigrateToEncrypted() can swap back from the in-memory
     * fallback to real EncryptedSharedPreferences after Keystore recovery.
     */
    @Volatile
    private var prefs: SharedPreferences = try {
        createEncryptedPrefs()
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences failed — recovering from Keystore corruption", e)
        recoverFromKeystoreCorruption()
        try {
            createEncryptedPrefs()
        } catch (e2: Exception) {
            Log.e(TAG, "Recovery failed — using empty in-memory prefs (user must re-login)", e2)
            Sentry.captureException(e2)
            usingInsecureFallback = true
            // F-15 FIX: Do NOT fall back to unencrypted SharedPreferences.
            // Storing WireGuard private keys in plaintext XML is unacceptable.
            // Instead, use an empty in-memory SharedPreferences that stores nothing
            // to disk. The user will need to re-login, which is far preferable
            // to persisting cryptographic keys in plaintext.
            InMemorySharedPreferences()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Nuke the corrupted Keystore entry and encrypted prefs file so we can start fresh.
     * The user will be logged out but the app will open instead of crashing.
     */
    private fun recoverFromKeystoreCorruption() {
        // 1. Remove the corrupted MasterKey from the Android Keystore
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
                Log.i(TAG, "Deleted corrupted Keystore entry: $MASTER_KEY_ALIAS")
            }
            // Also try the default alias used by MasterKey if different
            if (keyStore.containsAlias("_androidx_security_master_key_")) {
                keyStore.deleteEntry("_androidx_security_master_key_")
                Log.i(TAG, "Deleted corrupted default MasterKey entry")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete Keystore entry", e)
        }

        // 2. Delete the corrupted EncryptedSharedPreferences file
        try {
            val prefsFile = File(context.filesDir.parent, "shared_prefs/${PREFS_NAME}.xml")
            if (prefsFile.exists()) {
                prefsFile.delete()
                Log.i(TAG, "Deleted corrupted prefs file: ${prefsFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete prefs file", e)
        }
    }

    // ── Access Token ─────────────────────────────────────────────

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun setAccessToken(token: String) {
        require(token.length <= MAX_TOKEN_LENGTH) { "Access token exceeds max length" }
        prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply()
    }

    // ── Refresh Token ────────────────────────────────────────────

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun setRefreshToken(token: String) {
        require(token.length <= MAX_TOKEN_LENGTH) { "Refresh token exceeds max length" }
        // commit(): a refresh token is single-use server-side — losing this write
        // to a process kill replays the consumed token and trips theft detection.
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).commit()
    }

    // ── Token Pair ───────────────────────────────────────────────

    fun setTokens(accessToken: String, refreshToken: String) {
        require(accessToken.length <= MAX_TOKEN_LENGTH) { "Access token exceeds max length" }
        require(refreshToken.length <= MAX_TOKEN_LENGTH) { "Refresh token exceeds max length" }
        // If we were on the insecure fallback, try to migrate back to encrypted storage
        if (usingInsecureFallback) {
            tryMigrateToEncrypted()
        }
        // commit() (synchronous) instead of apply() — the caller reads tokens
        // immediately after this call (e.g. fetchProfileAfterLogin), so the
        // write must be visible before we return.
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .commit()
    }

    // ── WireGuard Key ────────────────────────────────────────────

    fun getWireGuardPrivateKey(): String? = prefs.getString(KEY_WG_PRIVATE_KEY, null)

    fun setWireGuardPrivateKey(key: String) {
        prefs.edit().putString(KEY_WG_PRIVATE_KEY, key).apply()
    }

    /**
     * FIX-1-8: Clear stored WG private key after disconnect.
     * Prevents key material from persisting in EncryptedSharedPreferences
     * after the VPN session ends.
     */
    fun clearWireGuardPrivateKey() {
        prefs.edit().remove(KEY_WG_PRIVATE_KEY).apply()
    }

    // ── Last Server ──────────────────────────────────────────────

    fun getLastServer(): String? = prefs.getString(KEY_LAST_SERVER, null)

    fun setLastServer(serverId: String) {
        prefs.edit().putString(KEY_LAST_SERVER, serverId).apply()
    }

    // ── Last Key ID (for disconnect) ─────────────────────────────

    fun getLastKeyId(): String? = prefs.getString(KEY_LAST_KEY_ID, null)

    fun setLastKeyId(keyId: String) {
        prefs.edit().putString(KEY_LAST_KEY_ID, keyId).apply()
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
    // Parking the ID here — same EncryptedSharedPreferences as the tokens it was
    // issued alongside, mirroring the iOS Keychain — means a config change or a
    // process kill mid-dialog re-surfaces the "save your ID" step on next launch
    // instead of swallowing it. It is deliberately NOT plaintext prefs: this
    // string authenticates the account, exactly like the refresh token.

    /** The minted ID the user has not yet confirmed saving, or null. */
    fun getPendingAnonymousId(): String? = prefs.getString(KEY_PENDING_ANON_ID, null)

    /**
     * Park a freshly minted ID until the user acknowledges it. commit() rather
     * than apply(): the very next thing that happens is the ID being shown to a
     * user who may kill the app a moment later, so the write has to already be
     * on disk — an async apply() could still be in flight.
     */
    fun setPendingAnonymousId(anonymousId: String) {
        prefs.edit().putString(KEY_PENDING_ANON_ID, anonymousId).commit()
    }

    /** Called once the user has confirmed they saved the ID. */
    fun clearPendingAnonymousId() {
        prefs.edit().remove(KEY_PENDING_ANON_ID).commit()
    }

    // ── Clear All ────────────────────────────────────────────────

    fun clearAll() {
        prefs.edit().clear().apply()
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

    // ── Migration ────────────────────────────────────────────────

    /**
     * Attempt to migrate back from in-memory fallback to EncryptedSharedPreferences.
     * Called automatically on next setTokens() after a fallback event.
     */
    private fun tryMigrateToEncrypted() {
        try {
            val encrypted = createEncryptedPrefs()
            // Swap the backing prefs so subsequent reads/writes use encrypted storage
            prefs = encrypted
            usingInsecureFallback = false
            Log.i(TAG, "EncryptedSharedPreferences recovered — tokens will be encrypted")
        } catch (e: Exception) {
            Log.w(TAG, "Migration back to encrypted storage failed — staying on fallback", e)
        }
    }
}

/**
 * F-15 FIX: No-op SharedPreferences implementation that stores nothing to disk.
 * Used as a safe fallback when the Android Keystore is irrecoverably corrupted.
 * All reads return null/defaults, all writes are silently discarded.
 * The user will see an unauthenticated state and be prompted to re-login.
 */
private class InMemorySharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { key?.let { data[it] = value } }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { key?.let { data[it] = values } }
        override fun putInt(key: String?, value: Int) = apply { key?.let { data[it] = value } }
        override fun putLong(key: String?, value: Long) = apply { key?.let { data[it] = value } }
        override fun putFloat(key: String?, value: Float) = apply { key?.let { data[it] = value } }
        override fun putBoolean(key: String?, value: Boolean) = apply { key?.let { data[it] = value } }
        override fun remove(key: String?) = apply { data.remove(key) }
        override fun clear() = apply { data.clear() }
        override fun commit(): Boolean = true
        override fun apply() {}
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}