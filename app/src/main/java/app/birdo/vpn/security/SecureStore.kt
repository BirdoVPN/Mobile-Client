package app.birdo.vpn.security

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import java.util.Base64

/**
 * A string key/value store whose values are unreadable at rest.
 *
 * Deliberately smaller than [SharedPreferences]: the app only ever keeps
 * short secrets (JWTs, a WireGuard private key, an anonymous-account ID)
 * here, so there are no typed getters and no listeners to keep honest.
 *
 * `commit` mirrors the SharedPreferences distinction: `true` blocks until the
 * value is on disk (a refresh token is single-use server-side, so losing the
 * write to a process kill replays the consumed token and trips theft
 * detection); `false` may return before the write lands.
 */
interface SecureStore {
    fun get(key: String): String?
    fun put(key: String, value: String, commit: Boolean = false)
    fun remove(key: String, commit: Boolean = false)
    fun contains(key: String): Boolean
    fun clear()
}

/**
 * [SecureStore] over a plain [SharedPreferences] file where every value is an
 * [AesGcmSealer] blob and the key name is the blob's AAD.
 *
 * On-disk shape: `<key> = "v1:" + base64(seal(utf8(value), aad = utf8(key)))`.
 *
 * - Key NAMES are stored in the clear. They are five fixed identifiers
 *   (`access_token`, `refresh_token`, …) that the source code names anyway,
 *   so encrypting them (as EncryptedSharedPreferences did with AES-SIV) hid
 *   nothing; what mattered — the values — is where the AES-256-GCM goes.
 * - Binding the key name as AAD means a value copied under another name
 *   (`refresh_token` → `access_token`) fails to open rather than decrypting.
 * - A value that fails to open is treated as absent and removed on the next
 *   read, never propagated as garbage: for a credential, "gone" is the only
 *   safe reading of "unverifiable".
 *
 * [java.util.Base64] rather than `android.util.Base64` so the format is
 * byte-for-byte testable on a plain JVM.
 */
class KeystoreSecureStore(
    private val prefs: SharedPreferences,
    private val sealer: AesGcmSealer,
) : SecureStore {

    override fun get(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        if (!raw.startsWith(PREFIX)) {
            Log.w(TAG, "value for '$key' is not a v1 blob — dropping it")
            prefs.edit { remove(key) }
            return null
        }
        return try {
            val blob = Base64.getDecoder().decode(raw.substring(PREFIX.length))
            String(sealer.open(blob, key.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
        } catch (e: AesGcmSealer.SealException) {
            Log.w(TAG, "value for '$key' failed to authenticate — dropping it", e)
            prefs.edit { remove(key) }
            null
        } catch (e: IllegalArgumentException) {
            // Base64 decoder: not a valid encoding.
            Log.w(TAG, "value for '$key' is not valid base64 — dropping it", e)
            prefs.edit { remove(key) }
            null
        }
    }

    override fun put(key: String, value: String, commit: Boolean) {
        val blob = sealer.seal(value.toByteArray(Charsets.UTF_8), key.toByteArray(Charsets.UTF_8))
        val encoded = PREFIX + Base64.getEncoder().encodeToString(blob)
        prefs.edit(commit = commit) { putString(key, encoded) }
    }

    override fun remove(key: String, commit: Boolean) {
        prefs.edit(commit = commit) { remove(key) }
    }

    override fun contains(key: String): Boolean = prefs.contains(key)

    override fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val TAG = "KeystoreSecureStore"
        const val PREFIX = "v1:"
    }
}

/**
 * Stores nothing on disk. The fallback when the Android Keystore is
 * irrecoverably corrupted (Samsung Knox key rotation, some OS updates): the
 * app opens in a logged-out state instead of crashing, and nothing — above
 * all no WireGuard private key — is ever written to plaintext XML.
 */
class InMemorySecureStore : SecureStore {
    private val data = mutableMapOf<String, String>()

    @Synchronized override fun get(key: String): String? = data[key]

    @Synchronized override fun put(key: String, value: String, commit: Boolean) {
        data[key] = value
    }

    @Synchronized override fun remove(key: String, commit: Boolean) {
        data.remove(key)
    }

    @Synchronized override fun contains(key: String): Boolean = data.containsKey(key)

    @Synchronized override fun clear() = data.clear()
}
