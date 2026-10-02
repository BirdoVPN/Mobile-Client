package app.birdo.vpn.service

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.utils.FaultReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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
 * connect()  ──▶ getClientPublicKeyB64(ctx)         // include in /connect request
 *            ──▶ performKeyExchange(ctx, response)  // returns derived PSK
 * disconnect() ──▶ stop()                            // zeroes secrets in memory
 * ```
 *
 * **No rekey loop.** wireguard-android doesn't expose `wg set ... preshared-key`
 * via JNI, so we cannot hot-swap the PSK on the live tunnel. Each `/connect`
 * derives a fresh per-session PQ-PSK, which provides identical HNDL
 * resistance — an attacker who later breaks the underlying classic
 * Curve25519 still doesn't recover any prior session's traffic, because each
 * session's PSK was independently derived from a fresh ML-KEM encapsulation.
 * If wireguard-android exposes live PSK swap in future, add a rekey loop
 * here without changing the protocol.
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

    // BirdoPqKeyStore keeps context.applicationContext only (see its
    // constructor), which lives as long as the process: nothing to leak.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var keyStore: BirdoPqKeyStore? = null

    private val _modeFlow = MutableStateFlow(Mode.DISABLED)
    val modeFlow: StateFlow<Mode> = _modeFlow.asStateFlow()

    // ── public API ─────────────────────────────────────────────────────────

    /**
     * Returns the Base64 ML-KEM-1024 public key for this client install,
     * generating + persisting one on first call.
     *
     * The caller MUST include this in the `/connect` request body (field name
     * `pq_client_public_key`) so the server can encapsulate against it.
     *
    * Returns `null` if the native lib isn't loaded or keypair generation
    * fails. Callers that requested quantum protection must fail closed rather
    * than pretending the connection is protected.
     */
    suspend fun getClientPublicKeyB64(context: Context): String? = withContext(Dispatchers.IO) {
        val kp = loadOrGenerateKeypair(context) ?: return@withContext null
        Base64.encodeToString(kp.publicKey, Base64.NO_WRAP)
    }

    /**
     * Performs the BirdoPQ v1 derivation and returns the PSK as Base64, or
     * `null` when none can be derived.
     *
     * Called only for a session the server enabled BirdoPQ for
     * (`quantumEnabled`): the ciphertext (in the `rosenpassPublicKey` field)
     * and the nonce (in `rosenpassEndpoint`) are decapsulated with the
     * persisted ML-KEM secret key. Anything less fails closed (`null`): a
     * classical PSK would not match the peer the server configured with the
     * derived one.
     */
    suspend fun performKeyExchange(context: Context, config: ConnectResponse): String? = withContext(Dispatchers.IO) {
        val bilateralPsk = tryDecapsulate(context, config)
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
     * Stops PQ protection and zeroes all sensitive key material in process
     * memory. Persistent keys on disk are NOT touched — call
     * [resetPersistedKeypair] for that (e.g. on user-initiated logout).
     */
    fun stop() {
        Log.i(TAG, "stopping BirdoPQ PSK manager")
        currentPsk?.fill(0)
        currentPsk = null
        _modeFlow.value = Mode.DISABLED
    }

    /** Permanently deletes the persisted ML-KEM keypair. Use on logout. */
    fun resetPersistedKeypair(context: Context) {
        ensureKeyStore(context).clear()
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
    private suspend fun tryDecapsulate(context: Context, config: ConnectResponse): ByteArray? {
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

        val keypair = loadOrGenerateKeypair(context) ?: return null
        try {
            // The generate path validates sizes via StaticKeypair.fromRaw; the
            // LOAD path reads raw bytes from disk with no length check, so a
            // truncated/corrupted file would reach the JNI boundary unvalidated.
            // Enforce the same invariant on both paths.
            if (keypair.secretKey.size != RosenpassNative.SECRET_KEY_BYTES) {
                FaultReporter.report(
                    FaultReporter.PATH_QUANTUM,
                    "pq_secret_key_wrong_size",
                    "Persisted ML-KEM secret key has the wrong size (${keypair.secretKey.size} B) — bilateral PQ aborted",
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
            // Zero the in-memory copy of the long-lived PQ secret key once the
            // derivation is done — the canonical copy lives Keystore-encrypted
            // on disk (BirdoPqKeyStore) and is re-read per connect, so this
            // only shortens the window key material sits in process memory.
            keypair.secretKey.fill(0)
        }
    }

    private fun loadOrGenerateKeypair(context: Context): RosenpassNative.StaticKeypair? {
        // AUDIT-E1 / PFA-H7: belt-and-braces — verify+load before any keypair work.
        if (!RosenpassNative.verifyIntegrity(context)) return null
        if (!RosenpassNative.isLoaded) return null
        val store = ensureKeyStore(context)
        val existing = store.load()
        if (existing != null) {
            // The KEM validates stored keys now. `ml-kem` enforces FIPS 203
            // 7.3 -- the 3168-byte expanded decapsulation key embeds H(ek),
            // recomputed on load and compared -- where the implementation that
            // shipped up to 1.4.29 checked only the length. A key that fails
            // that check is NOT recoverable, so retrying it would fail every
            // connect forever; discard it and re-key instead. The server
            // re-pins the new public key on the next handshake, so the only
            // cost is one extra keygen.
            if (RosenpassNative.storedKeyUsable(existing.secretKey)) return existing

            FaultReporter.report(
                FaultReporter.PATH_QUANTUM,
                "pq_stored_key_unusable_rekeyed",
                "Persisted ML-KEM secret key failed FIPS 203 validation — discarded and re-keying",
            )
            existing.secretKey.fill(0)
            store.clear()
        }

        Log.i(TAG, "no persisted ML-KEM keypair — generating new (~10–50 ms)")
        val fresh = try {
            RosenpassNative.generateKeypair()
        } catch (e: Throwable) {
            FaultReporter.report(
                FaultReporter.PATH_QUANTUM,
                "pq_generate_keypair_threw",
                "Native generateKeypair threw — no client keypair, BirdoPQ unavailable",
                e,
            )
            return null
        }
        try {
            store.save(fresh)
        } catch (e: Exception) {
            // Not fatal for this connect, but every connect will pay the
            // generation cost and the server sees a new client key each time.
            FaultReporter.report(
                FaultReporter.PATH_QUANTUM,
                "pq_keypair_persist_failed",
                "Failed to persist the new ML-KEM keypair — regenerating on every connect",
                e,
            )
        }
        return fresh
    }

    private fun ensureKeyStore(context: Context): BirdoPqKeyStore {
        return keyStore ?: synchronized(this) {
            keyStore ?: BirdoPqKeyStore(context.applicationContext).also { keyStore = it }
        }
    }

    // PFA-M5: legacy DEFAULT_NONCE_BYTES constant removed — `tryDecapsulate`
    // now refuses to derive a PSK against a missing/empty per-connect nonce.
}
