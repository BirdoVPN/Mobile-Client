package app.birdo.vpn.service

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.annotation.VisibleForTesting
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.utils.FaultReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates BirdoPQ v1 post-quantum WireGuard PSK derivation.
 *
 * ## Operating modes
 *
 * The manager picks the strongest mode that's feasible for the current
 * connect attempt and reports it via [modeFlow]:
 *
 * | Mode | When | Provides HNDL resistance? |
 * |------|------|---------------------------|
 * | [Mode.BILATERAL] | Native lib loaded AND server returned a valid ML-KEM ciphertext that decapsulates against our local secret key | Yes (post-quantum) |
 * | [Mode.DISABLED] | Anything else. A connect that asked for BirdoPQ then fails closed | No |
 *
 * BirdoPQ v1 is a LOCAL derivation, not a network exchange: the client
 * uploads its ML-KEM-1024 public key in the `/connect` request and
 * decapsulates the ciphertext the response carries — no extra UDP, no
 * `rosenpass` binary, no libsodium. The wire fields keep their historical
 * names (`rosenpassPublicKey` carries the ciphertext, `rosenpassEndpoint` the
 * nonce), and so does the JNI class ([RosenpassNative], whose symbol names
 * the Rust side exports). See `native/rosenpass-jni/src/lib.rs` for the
 * protocol. There is no server-PSK "partial" mode: a classical PSK the server
 * sends for a non-PQ session is applied by the service directly, and is not
 * post-quantum (A1-039 removed the unreachable SERVER_PROVIDED mode).
 *
 * ## Lifecycle
 *
 * ```
 * connect()  ──▶ getClientPublicKeyB64(ctx)         // NEW keypair; include in /connect request
 *            ──▶ performKeyExchange(ctx, response)  // returns derived PSK, wipes the secret
 * disconnect() ──▶ stop()                            // zeroes secrets in memory
 * ```
 *
 * One ML-KEM-1024 keypair per connect request (MR-703 / MR-1742). The keypair
 * travels from the first call to the second through a one-shot in-memory
 * hand-off, which is its ONLY holder: nothing is written to disk or the
 * Keystore, so the decapsulation uses exactly the pair whose public key was
 * uploaded (MR-2117), and a pair never outlives its connect.
 * [purgeLegacyKeypair] removes what builds before this one persisted.
 *
 * **No rekey loop.** wireguard-android doesn't expose `wg set ... preshared-key`
 * via JNI, so we cannot hot-swap the PSK on the live tunnel. Each `/connect`
 * derives a fresh per-session PQ-PSK from a fresh ML-KEM encapsulation, so
 * one session's PSK says nothing about another's, and breaking Curve25519
 * alone recovers neither. If wireguard-android exposes live PSK swap in
 * future, add a rekey loop here without changing the protocol.
 *
 * ## What it does NOT protect (MR-743, MR-1364)
 *
 * The full analysis is birdo-web `docs/PQ-THREAT-MODEL.md` (§4, §6.2, §7).
 *
 * - **A thief inside the process during a connect.** The ML-KEM secret exists
 *   only between [getClientPublicKeyB64] and the end of [performKeyExchange]
 *   (or [stop]); someone who can read this process's memory in that window
 *   recovers that one session's PSK. Device files and backups hold no key.
 *   Builds before MR-703 kept one per install, which was not forward-secret in
 *   either direction (the threat model's O0e, now built).
 * - **The relay leg is classical.** The server withholds the PSK from this
 *   app's response (`pqClientCanDecapsulate`), but it still sends the PSK to
 *   the VPN server over TLS that is not yet post-quantum, so a recording of
 *   that link plus a future CRQC recovers it (MR-1444).
 * - **Multi-hop's middle leg** (entry relay to exit relay) carries no PQ PSK.
 */
object BirdoPqManager {

    private const val TAG = "BirdoPqManager"

    /** WireGuard PresharedKey is exactly 32 bytes. */
    private const val PSK_LENGTH_BYTES = 32

    /** Upper bound for the server-supplied per-connect nonce (server mints 32 B). */
    private const val MAX_NONCE_BYTES = 64

    enum class Mode { DISABLED, BILATERAL }

    @Volatile
    private var currentPsk: ByteArray? = null

    /** One connect's pair, keyed by the public key it uploaded. */
    private class Pending(val keypair: RosenpassNative.StaticKeypair, val atMs: Long)

    /**
     * The pairs whose public keys were uploaded and whose decapsulation has not
     * happened yet, BY THE UPLOADED PUBLIC KEY (MR-703 / MR-1742). THE ONLY
     * HOLDER of an ML-KEM secret key: there is no stored copy to fall back to,
     * so a decapsulation without its pair fails closed instead of deriving a
     * PSK the server cannot match (MR-2117).
     *
     * Keyed, not a single slot, because two generations overlap: a new dial
     * uploads its pair while the OLD generation's teardown is still to run.
     * [performKeyExchange] and [stop] are handed the public key of THEIR
     * generation and touch only that pair, so a stale generation can never
     * consume or wipe a newer one's. (The key is public; it travels in the
     * service intent's extras.)
     *
     * Whoever ends a pair's life wipes its secret: [performKeyExchange]'s
     * finally, [stop] with its key, [discardPending] (a dial or rebuild that
     * never handed its pair to the service: refused, superseded, a Disconnect
     * while connecting), [discardKeypair] (all of them), and the backstops in
     * [prunePending].
     */
    private val pending = LinkedHashMap<String, Pending>()

    /**
     * The monotonic clock behind the backstop (never the wall clock, which a
     * user or the network can step). Tests move it. Only read while a pair is
     * pending.
     */
    @Volatile
    @VisibleForTesting
    internal var elapsedMs: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * BACKSTOP ONLY. A pair is normally ended by its own generation's
     * decapsulation or [stop], or by its dial's [discardPending] when the
     * service never heard of it. One that slips past all of them (a process
     * path that names its key nowhere) would otherwise sit in memory until the
     * next upload or sign-out. Five minutes is longer than any dial; the cap
     * bounds how many such pairs can pile up.
     */
    private const val PENDING_PAIR_MAX_AGE_MS = 5 * 60_000L
    private const val PENDING_PAIR_MAX_COUNT = 4

    /** Runs the sign-out purge off the caller's thread (Keystore deletes block). Tests run it inline. */
    @Volatile
    @VisibleForTesting
    internal var purgeExecutor: (Runnable) -> Unit = { r -> Thread(r, "pq-legacy-purge").apply { isDaemon = true }.start() }

    /** Legacy key files and Keystore keys are purged once per process. */
    private val legacyPurged = AtomicBoolean(false)

    /** Replaced by tests, which run on a JVM with no Android Keystore. */
    @Volatile
    @VisibleForTesting
    internal var legacyPurgeFactory: (Context) -> BirdoPqLegacyPurge = { BirdoPqLegacyPurge(it) }

    private val _modeFlow = MutableStateFlow(Mode.DISABLED)
    val modeFlow: StateFlow<Mode> = _modeFlow.asStateFlow()

    // ── public API ─────────────────────────────────────────────────────────

    /**
     * Generates a NEW ML-KEM-1024 keypair for one `/connect` request and
     * returns its Base64 public key. The secret half is held only in the
     * one-shot hand-off for the [performKeyExchange] that answers this request.
     *
     * The caller MUST include this in the `/connect` request body (field name
     * `pq_client_public_key`) so the server can encapsulate against it, and call
     * this once per request: a retry of the SAME request re-sends the key it
     * already has (the server's ciphertext still fits it), while a new connect,
     * rebuild or reconnect calls this again. Calling it replaces and zeroes any
     * pair still in the hand-off.
     *
     * Returns `null` if the native lib isn't loaded or keypair generation
     * fails. Callers that requested quantum protection must fail closed rather
     * than pretending the connection is protected.
     */
    suspend fun getClientPublicKeyB64(context: Context): String? = withContext(Dispatchers.IO) {
        purgeLegacyKeypair(context)
        val kp = generateKeypair(context) ?: return@withContext null
        val publicKeyB64 = Base64.encodeToString(kp.publicKey, Base64.NO_WRAP)
        synchronized(pending) {
            prunePending()
            // MR-2123: its secret key is zeroed when it is consumed or cleared,
            // instead of being left to the garbage collector.
            pending.remove(publicKeyB64)?.keypair?.secretKey?.fill(0)
            pending[publicKeyB64] = Pending(kp, elapsedMs())
            // Bounded: the oldest unconsumed pair goes first.
            while (pending.size > PENDING_PAIR_MAX_COUNT) {
                val oldest = pending.keys.first()
                pending.remove(oldest)?.keypair?.secretKey?.fill(0)
            }
        }
        publicKeyB64
    }

    /**
     * Performs the BirdoPQ v1 derivation and returns the PSK as Base64, or
     * `null` when none can be derived.
     *
     * Called only for a session the server enabled BirdoPQ for
     * (`quantumEnabled`): the ciphertext (in the `rosenpassPublicKey` field)
     * and the nonce (in `rosenpassEndpoint`) are decapsulated with the secret
     * key of the pair this connect uploaded, which is wiped on the way out.
     * [uploadedPk] is the public key THIS generation's connect uploaded (the
     * value [getClientPublicKeyB64] returned): only that pair is taken, so a
     * request can never decapsulate with, or consume, another request's pair.
     * Null, or a key with no pending pair, fails closed. Anything less fails
     * closed (`null`): a
     * classical PSK would not match the peer the server configured with the
     * derived one.
     */
    suspend fun performKeyExchange(
        context: Context,
        config: ConnectResponse,
        uploadedPk: String?,
    ): String? = withContext(Dispatchers.IO) {
        val bilateralPsk = tryDecapsulate(context, config, uploadedPk)
        if (bilateralPsk != null) {
            // Zero the prior PSK before swapping (defence-in-depth).
            currentPsk?.fill(0)
            currentPsk = bilateralPsk
            _modeFlow.value = Mode.BILATERAL
            Log.i(TAG, "BirdoPQ v1 BILATERAL — quantum-resistant PSK derived (${bilateralPsk.size} B)")
            return@withContext Base64.encodeToString(bilateralPsk, Base64.NO_WRAP)
        }
        currentPsk?.fill(0)
        currentPsk = null
        _modeFlow.value = Mode.DISABLED
        // tryDecapsulate reported the specific cause (or logged at debug
        // level for the two by-design nulls: lib not loaded, no ciphertext);
        // this is the abort itself. The service then refuses the connect and
        // reports that refusal.
        FaultReporter.report(
            FaultReporter.PATH_QUANTUM,
            "pq_abort_no_bilateral_psk",
            "Server enabled BirdoPQ but no bilateral PSK could be derived — aborting",
        )
        return@withContext null
    }

    /**
     * Stops PQ protection and zeroes the derived PSK, and wipes the ONE pending
     * pair whose public key is [uploadedPk] (the generation being torn down).
     * A null or unknown key wipes no pair: a stale generation's teardown, which
     * runs after a newer dial uploaded its own pair, must never reach it.
     */
    fun stop(uploadedPk: String? = null) {
        Log.i(TAG, "stopping BirdoPQ PSK manager")
        currentPsk?.fill(0)
        currentPsk = null
        synchronized(pending) {
            if (uploadedPk != null) pending.remove(uploadedPk)?.keypair?.secretKey?.fill(0)
            prunePending()
        }
        _modeFlow.value = Mode.DISABLED
    }

    /**
     * Wipes the ONE pending pair named by [uploadedPk], and nothing else: not
     * the derived PSK, not the mode, no other pair. For a request that ends
     * before its decapsulation (a rebuild that is superseded or refused).
     */
    fun discardPending(uploadedPk: String?) {
        if (uploadedPk == null) return
        synchronized(pending) { pending.remove(uploadedPk)?.keypair?.secretKey?.fill(0) }
    }

    /**
     * Sign-out and account deletion: wipe EVERY pending pair and make sure no
     * legacy key file survives (the purge runs again, not only once per
     * process, because this is the hygiene the user asked for).
     */
    fun discardKeypair(context: Context) {
        synchronized(pending) {
            pending.values.forEach { it.keypair.secretKey.fill(0) }
            pending.clear()
        }
        val purge = legacyPurgeFactory(context)
        // The Keystore deletes can block: off the caller's (sign-out) thread.
        purgeExecutor(Runnable { runCatching { purge.purge() } })
    }

    /** Wipes pairs past the backstop. Caller holds the [pending] lock. */
    private fun prunePending() {
        if (pending.isEmpty()) return
        val now = elapsedMs()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.atMs >= PENDING_PAIR_MAX_AGE_MS) {
                e.value.keypair.secretKey.fill(0)
                it.remove()
            }
        }
    }

    /**
     * Deletes the keypair files and Keystore keys older builds persisted
     * (MR-703 / MR-1742: nothing is stored now). Once per process, off the main
     * thread; BirdoApp calls it at start so an install that never turns
     * Quantum Protection on is cleaned too.
     */
    fun purgeLegacyKeypair(context: Context) {
        if (!legacyPurged.compareAndSet(false, true)) return
        runCatching { legacyPurgeFactory(context).purge() }
            .onFailure { legacyPurged.set(false); Log.w(TAG, "legacy PQ key purge failed", it) }
    }

    // ── BirdoPQ v1 decapsulation ───────────────────────────────────────────

    /**
     * Decapsulates the server-supplied ciphertext into the per-session PSK.
     *
     * Returns null when:
     *   - the native lib isn't loaded (built without Rust toolchain),
     *   - the server response doesn't include a ciphertext (`rosenpassPublicKey` field),
     *   - we have no persisted client keypair AND can't generate one,
     *   - the native call rejects the input (wrong-sized, etc.),
     *   - the derived PSK is wrong-sized (defensive).
     *
     * On `null` the connect fails closed; on crypto errors we report and
     * bail out cleanly so a misconfigured peer is never masked by a silent
     * downgrade.
     */
    private suspend fun tryDecapsulate(context: Context, config: ConnectResponse, uploadedPk: String?): ByteArray? {
        // MR-2117: take the hand-off before anything can return, so every
        // exit below (the by-design nulls included) zeroes it.
        val handedOff = uploadedPk?.let { key -> synchronized(pending) { pending.remove(key)?.keypair } }
        try {
            return decapsulate(context, config, handedOff)
        } finally {
            handedOff?.secretKey?.fill(0)
        }
    }

    /**
     * [tryDecapsulate]'s body. [handedOff] is the pair whose public key this
     * generation uploaded, or null when there is none (consumed, or wiped by
     * [stop] or the backstop): with no stored copy to fall back to, that fails
     * closed.
     */
    private suspend fun decapsulate(
        context: Context,
        config: ConnectResponse,
        handedOff: RosenpassNative.StaticKeypair?,
    ): ByteArray? {
        // PFA-H7: integrity-verify FIRST (which also performs the hash-then-load
        // sequence). isLoaded only becomes true after the hash matches.
        if (!RosenpassNative.verifyIntegrity(context)) {
            // Reported at the root, RosenpassNative.verifyIntegrity
            // (pq_disabled_integrity), which all three callers share.
            Log.w(TAG, "rosenpass-jni integrity unverified — bilateral PQ DISABLED")
            return null
        }
        if (!RosenpassNative.isLoaded) {
            Log.d(TAG, "rosenpass-jni not loaded — bilateral PQ unavailable")
            return null
        }
        // Field name re-used: rosenpassPublicKey now carries the ML-KEM ciphertext.
        val ctB64 = config.rosenpassPublicKey
        if (ctB64.isNullOrBlank()) {
            Log.d(TAG, "no PQ ciphertext in response — bilateral PQ skipped")
            return null
        }
        // Field name re-used: rosenpassEndpoint now carries the per-connect nonce.
        // PFA-M5: refuse to derive a PSK against a missing nonce. ML-KEM
        // gives a fresh shared secret per encapsulation so the previous
        // hard-coded fallback constant did NOT cause cryptographic nonce
        // reuse, but it removed per-connect domain separation and let a
        // misconfigured server silently weaken the protocol. Fail closed
        // instead (reported, surfaced via modeFlow).
        val nonceB64 = config.rosenpassEndpoint
        if (nonceB64.isNullOrBlank()) {
            FaultReporter.report(
                FaultReporter.PATH_QUANTUM,
                "pq_nonce_missing",
                "Server omitted the per-connect PQ nonce — bilateral PQ aborted (PFA-M5)",
            )
            return null
        }
        val nonce = try { Base64.decode(nonceB64, Base64.NO_WRAP) }
        catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_QUANTUM,
                "pq_nonce_malformed",
                "Malformed PQ nonce — bilateral PQ aborted",
                e,
            )
            return null
        }
        // Bound the server-supplied nonce before it crosses the JNI boundary —
        // an empty or arbitrarily large buffer must not rely on Rust-side
        // validation alone.
        if (nonce.isEmpty() || nonce.size > MAX_NONCE_BYTES) {
            FaultReporter.report(
                FaultReporter.PATH_QUANTUM,
                "pq_nonce_out_of_bounds",
                "PQ nonce out of bounds (${nonce.size} B) — bilateral PQ aborted",
            )
            return null
        }
        val ciphertext = try {
            Base64.decode(ctB64, Base64.NO_WRAP)
        } catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_QUANTUM,
                "pq_ciphertext_malformed",
                "Malformed PQ ciphertext — bilateral PQ aborted",
                e,
            )
            return null
        }
        if (ciphertext.size != RosenpassNative.CIPHERTEXT_BYTES) {
            FaultReporter.report(
                FaultReporter.PATH_QUANTUM,
                "pq_ciphertext_wrong_size",
                "PQ ciphertext has the wrong size (${ciphertext.size} B) — bilateral PQ aborted",
            )
            return null
        }

        // MR-703: the hand-off is the only holder. The server encapsulated to
        // the public key THIS connect uploaded; any other pair can only derive
        // a PSK the server does not have (implicit rejection), a handshake that
        // never completes. With none, refuse here, where the cause is known.
        val keypair = handedOff ?: run {
            FaultReporter.report(
                FaultReporter.PATH_QUANTUM,
                "pq_keypair_not_the_uploaded_one",
                "No ML-KEM keypair from this connect's upload is available to decapsulate with — bilateral PQ aborted",
            )
            return null
        }
        try {
            // Defensive: the pair came from the native generator, which sizes it.
            if (keypair.secretKey.size != RosenpassNative.SECRET_KEY_BYTES) {
                FaultReporter.report(
                    FaultReporter.PATH_QUANTUM,
                    "pq_secret_key_wrong_size",
                    "ML-KEM secret key has the wrong size (${keypair.secretKey.size} B) — bilateral PQ aborted",
                )
                return null
            }

            val psk = try {
                RosenpassNative.deriveSharedPsk(keypair.secretKey, ciphertext, nonce)
            } catch (e: Throwable) {
                FaultReporter.report(
                    FaultReporter.PATH_QUANTUM,
                    "pq_derive_threw",
                    "Native deriveSharedPsk threw — bilateral PQ aborted",
                    e,
                )
                return null
            }
            if (psk == null) {
                // The native side rejected the input it was given: a server
                // ciphertext that does not decapsulate against our key.
                FaultReporter.report(
                    FaultReporter.PATH_QUANTUM,
                    "pq_derive_no_result",
                    "Native deriveSharedPsk returned null — bilateral PQ aborted",
                )
                return null
            }
            if (psk.size != PSK_LENGTH_BYTES) {
                FaultReporter.report(
                    FaultReporter.PATH_QUANTUM,
                    "pq_derived_psk_wrong_size",
                    "Native deriveSharedPsk returned a wrong-sized PSK (${psk.size} B) — bilateral PQ aborted",
                )
                psk.fill(0)
                return null
            }
            return psk
        } finally {
            // Zero the connect's secret key once the derivation is done. There
            // is no other copy: this was the only one (MR-703).
            keypair.secretKey.fill(0)
        }
    }

    /** A fresh keypair from the native KEM, or null when it is unavailable. */
    private fun generateKeypair(context: Context): RosenpassNative.StaticKeypair? {
        // AUDIT-E1 / PFA-H7: belt-and-braces — verify+load before any keypair work.
        if (!RosenpassNative.verifyIntegrity(context)) return null
        if (!RosenpassNative.isLoaded) return null
        return try {
            RosenpassNative.generateKeypair()
        } catch (e: Throwable) {
            FaultReporter.report(
                FaultReporter.PATH_QUANTUM,
                "pq_generate_keypair_threw",
                "Native generateKeypair threw — no client keypair, BirdoPQ unavailable",
                e,
            )
            null
        }
    }

    // PFA-M5: legacy DEFAULT_NONCE_BYTES constant removed — `tryDecapsulate`
    // now refuses to derive a PSK against a missing/empty per-connect nonce.
}
