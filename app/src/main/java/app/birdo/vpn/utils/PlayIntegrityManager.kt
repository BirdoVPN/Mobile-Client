package app.birdo.vpn.utils

import android.content.Context
import android.util.Log
import app.birdo.vpn.BuildConfig
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Requests a Google Play Integrity token so the backend can confirm the running
 * app is the genuine, unmodified Play build on a genuine device before it hands
 * out a WireGuard peer (the "only the official client can connect" control).
 *
 * A valid token is only produced for Play-distributed builds, so this is gated
 * on [BuildConfig.IS_PLAY_BUILD]; non-Play builds (direct APK / F-Droid) return
 * null and connect without a token — the backend attestation policy decides what
 * happens then. Any failure also returns null (fail-open at the client; the
 * server owns the enforcement decision) so a transient Play-services issue never
 * hard-blocks a connect the server would otherwise allow.
 */
object PlayIntegrityManager {
    private const val TAG = "PlayIntegrity"

    /**
     * @param nonce server-issued single-use nonce the token is bound to (anti-replay).
     * @return the integrity token, or null on non-Play builds / any failure.
     */
    suspend fun requestToken(context: Context, nonce: String): String? {
        if (!BuildConfig.IS_PLAY_BUILD) return null
        return try {
            val manager = IntegrityManagerFactory.create(context.applicationContext)
            val request = IntegrityTokenRequest.builder().setNonce(nonce).build()
            suspendCancellableCoroutine { cont ->
                manager.requestIntegrityToken(request)
                    .addOnSuccessListener { resp ->
                        if (cont.isActive) cont.resume(resp.token())
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Integrity token request failed: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Integrity unavailable: ${e.message}")
            null
        }
    }
}
