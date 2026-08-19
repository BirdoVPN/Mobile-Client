package app.birdo.vpn.data.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short-lived, on-disk store for the in-flight native-SSO PKCE state
 * (verifier + anti-CSRF state).
 *
 * The SSO flow opens the system browser, which backgrounds the app; when the
 * browser redirects to birdo://auth, Android may deliver it to a freshly
 * created Activity/ViewModel (or a restarted process). In-memory state on the
 * ViewModel does NOT survive that, which made every sign-in fail with
 * "session expired". Persisting to app-private prefs makes the pending state
 * survive the round-trip regardless of which instance handles the callback.
 *
 * App-private (MODE_PRIVATE); the verifier is only useful within the ~60s
 * handoff window and is cleared as soon as the exchange runs (or is abandoned).
 */
@Singleton
class OAuthStateStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("birdo_oauth_prefs", Context.MODE_PRIVATE)

    /** Persist the verifier + state for the in-flight attempt. */
    fun save(verifier: String, state: String) {
        prefs.edit()
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_STATE, state)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .commit()
    }

    /**
     * The pending (verifier, state), or null if none is in flight.
     *
     * An entry older than [MAX_AGE_MS] is treated as absent AND deleted: the
     * server-side handoff code it belonged to expires in ~60s, so anything
     * older is an abandoned attempt whose PKCE secret should not sit in
     * plaintext prefs indefinitely.
     */
    fun load(): Pair<String, String>? {
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: return null
        val state = prefs.getString(KEY_STATE, null) ?: return null
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        val age = System.currentTimeMillis() - savedAt
        // A clock jumped backwards (age < 0) is also treated as expired —
        // fail toward clearing the residue, never toward keeping it.
        if (age !in 0..MAX_AGE_MS) {
            clear()
            return null
        }
        return verifier to state
    }

    /** Clear after the exchange completes or the attempt is abandoned. */
    fun clear() {
        prefs.edit().remove(KEY_VERIFIER).remove(KEY_STATE).remove(KEY_SAVED_AT).commit()
    }

    private companion object {
        const val KEY_VERIFIER = "pkce_verifier"
        const val KEY_STATE = "oauth_state"
        const val KEY_SAVED_AT = "pkce_saved_at"

        /**
         * Generous multiple of the server's ~60s handoff-code expiry: covers a
         * slow browser round-trip / process restart without leaving abandoned
         * PKCE residue on disk indefinitely.
         */
        const val MAX_AGE_MS = 10 * 60 * 1000L
    }
}
