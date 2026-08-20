package app.birdo.vpn.utils

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
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
 *
 * ## P6-CLI-A-03 — attestation is RATE-LIMITED, not per-connect
 *
 * This used to run on EVERY connect. Each run is an authenticated round trip to
 * Google, from the user's real IP, at the exact moment they start a VPN session
 * — so Google was handed a per-connection timestamp for every user of the Play
 * build: a complete "when does this person turn their VPN on" timeline,
 * assembled by the VPN client, for a third party. Nothing about the control
 * needed that frequency; an attestation says "this install is the genuine app
 * on a genuine device", which is a property of the INSTALL, not of the session.
 *
 * So it now runs at most once per [SUCCESS_INTERVAL_MS] (and, when Play
 * Integrity is failing, at most once per [FAILURE_BACKOFF_MS] — a failed call
 * still tells Google the device pinged them, so failures must be bounded too).
 * Connects in between attach no token, which is byte-identical to what every
 * F-Droid / direct-APK install already sends today, and those connect fine.
 *
 * The token is deliberately NOT cached and replayed: it is bound to a
 * single-use server nonce, so re-sending it would either be rejected outright
 * or quietly turn attestation into theatre. One fresh token per window, used
 * once.
 *
 * WHAT THIS COSTS: between windows a Play install is, to the backend,
 * indistinguishable from an unattested one. If ATTESTATION_POLICY is ever moved
 * to hard-enforce, that has to be reconciled first — attaching a token to every
 * connect again is not the answer, a backend endpoint that accepts an
 * attestation once at signup is.
 */
object PlayIntegrityManager {
    private const val TAG = "PlayIntegrity"

    private const val PREFS_NAME = "birdo_attestation"
    private const val KEY_LAST_ATTEMPT_MS = "last_attempt_ms"
    private const val KEY_LAST_OK = "last_ok"

    /** ~30 days between successful attestations. */
    @VisibleForTesting
    internal const val SUCCESS_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * ~24 hours after a FAILED attestation. Shorter than the success interval so
     * a device whose Play services were merely having a bad day re-attests
     * soon-ish, but still a hard bound: a permanently broken Play Integrity must
     * not degrade back into one Google round trip per connect.
     */
    @VisibleForTesting
    internal const val FAILURE_BACKOFF_MS = 24L * 60 * 60 * 1000

    /**
     * Whether an attestation is due, so callers can skip the nonce round trip
     * entirely when it is not. Non-Play builds are never due — they cannot
     * produce a valid token at all.
     */
    fun isAttestationDue(context: Context): Boolean {
        if (!BuildConfig.IS_PLAY_BUILD) return false
        val prefs = prefs(context)
        return isDue(
            lastAttemptMs = prefs.getLong(KEY_LAST_ATTEMPT_MS, 0L),
            lastOk = prefs.getBoolean(KEY_LAST_OK, false),
            nowMs = System.currentTimeMillis(),
        )
    }

    /**
     * @param nonce server-issued single-use nonce the token is bound to (anti-replay).
     * @return the integrity token, or null on non-Play builds / any failure.
     *
     * Records the attempt (and its outcome) so [isAttestationDue] can bound how
     * often this runs. Recorded on FAILURE too, on purpose: an unbounded retry
     * loop against a device where Play Integrity always fails would rebuild the
     * per-connect timeline out of failures.
     */
    suspend fun requestToken(context: Context, nonce: String): String? {
        if (!BuildConfig.IS_PLAY_BUILD) return null
        val token: String? = try {
            val manager = IntegrityManagerFactory.create(context.applicationContext)
            val request = IntegrityTokenRequest.builder().setNonce(nonce).build()
            suspendCancellableCoroutine<String?> { cont ->
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
        recordAttempt(context, ok = token != null)
        return token
    }

    private fun recordAttempt(context: Context, ok: Boolean) {
        try {
            prefs(context).edit()
                .putLong(KEY_LAST_ATTEMPT_MS, System.currentTimeMillis())
                .putBoolean(KEY_LAST_OK, ok)
                .apply()
        } catch (e: Throwable) {
            // Never fail a connect over bookkeeping. Worst case the next connect
            // attests again — the pre-P6-CLI-A-03 behaviour, not a new failure.
            Log.w(TAG, "Could not record attestation attempt: ${e.message}")
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Pure scheduling rule, split out so it is testable without Android.
     *
     * A `nowMs` BEFORE the recorded attempt means the clock moved backwards (the
     * user changed the date, or an NTP correction landed). Treat that as due:
     * trusting a timestamp from the future would suppress attestation until real
     * time caught up with it, which for a date set years ahead is forever.
     */
    @VisibleForTesting
    internal fun isDue(lastAttemptMs: Long, lastOk: Boolean, nowMs: Long): Boolean {
        if (lastAttemptMs <= 0L) return true
        if (nowMs < lastAttemptMs) return true
        val interval = if (lastOk) SUCCESS_INTERVAL_MS else FAILURE_BACKOFF_MS
        return nowMs - lastAttemptMs >= interval
    }
}
