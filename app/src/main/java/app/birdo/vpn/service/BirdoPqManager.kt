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
import java.util.concurrent.atomic.AtomicReference

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
 * The keypair travels from the first call to the second through a one-shot
 * in-memory hand-off (MR-2117), so the decapsulation uses exactly the pair
 * whose public key was uploaded, even when the store could not keep it.
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
 * - **The keypair is static per install** ([BirdoPqKeyStore]), and ML-KEM
 *   decapsulation is deterministic in (sk, ct). Whoever obtains this
 *   device's secret key and has recorded the ciphertexts recovers every PSK
 *   derived for that key: past sessions AND future ones, until the key is
 *   replaced. A fresh encapsulation per session does not change that. Making
 *   the keypair ephemeral (one per connect) is the threat model's O0e, not
 *   built.
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

    /**
     * Where the encapsulation key sits inside the 3168-byte expanded ML-KEM-1024
     * decapsulation key. FIPS 203 lays it out as
     * `dk_pke (1536) || ek (1568) || H(ek) (32) || z (32)`; the native
     * `embedded_ek_sits_at_1536_in_the_expanded_dk` test pins it.
     */
    private const val EMBEDDED_PUBLIC_KEY_OFFSET =
        RosenpassNative.SECRET_KEY_BYTES - RosenpassNative.PUBLIC_KEY_BYTES - 64

    enum class Mode { DISABLED, BILATERAL }

    @Volatile
    private var currentPsk: ByteArray? = null

    /**
     * MR-2117: the exact keypair whose public key [getClientPublicKeyB64] last
     * uploaded, handed to the next [tryDecapsulate] exactly once.
     *
     * Without it the decapsulation re-read the keypair, and whenever the store
     * could not keep the pair (a failing save, a transient read error on the
     * public key, a Keystore failure) that re-read GENERATED A DIFFERENT ONE:
     * the server encapsulated to pk_A, the client decapsulated with sk_B,
     * implicit rejection gave a wrong PSK, and the tunnel never came up. Desktop
     * (`CACHED_KEYPAIR`) and iOS hand the pair over the same way.
     *
     * It holds the secret key, so whoever clears it zeroes it:
     * [tryDecapsulate]'s finally, [stop], [resetPersistedKeypair], and the next
     * upload replacing it.
     */
    private val handOff = AtomicReference<RosenpassNative.StaticKeypair?>(null)

    /**
     * The public key [getClientPublicKeyB64] last uploaded. Public, so unlike
     * [handOff] it survives [stop]: a decapsulation that has to fall back to
     * the store (the hand-off already consumed or cleared) is still checked
     * against it, and refuses loudly instead of deriving a PSK the server
     * cannot match. Cleared by [resetPersistedKeypair].
     */
    @Volatile
    private var uploadedPublicKey: ByteArray? = null

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
        // MR-2117: the decapsulation for this /connect uses exactly this pair.
        // MR-2123: its secret key is zeroed when the hand-off is consumed or
        // cleared, instead of being left to the garbage collector.
        // Updated together, so two racing uploads cannot leave the record of
        // the uploaded key naming a different pair from the hand-off.
        synchronized(handOff) {
            uploadedPublicKey = kp.publicKey.copyOf()
            replaceHandOff(kp)
        }
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
        // An unconsumed hand-off still holds the ML-KEM secret key.
        replaceHandOff(null)
        _modeFlow.value = Mode.DISABLED
    }

    /** Permanently deletes the persisted ML-KEM keypair. Use on logout. */
    fun resetPersistedKeypair(context: Context) {
        synchronized(handOff) {
            uploadedPublicKey = null
            replaceHandOff(null)
        }
        ensureKeyStore(context).clear()
    }

    /** Swaps the hand-off and zeroes the secret key of the pair it replaced. */
    private fun replaceHandOff(next: RosenpassNative.StaticKeypair?) {
        val previous = handOff.getAndSet(next)
        if (previous != null && previous !== next) previous.secretKey.fill(0)
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
        // MR-2117: take the hand-off before anything can return, so every
        // exit below (the by-design nulls included) zeroes it.
        val handedOff = handOff.getAndSet(null)
        try {
            return decapsulate(context, config, handedOff)
        } finally {
            handedOff?.secretKey?.fill(0)
        }
    }

    /**
     * [tryDecapsulate]'s body. [handedOff] is the pair whose public key this
     * connect uploaded, or null when there is none (consumed, cleared by
     * [stop], or never set in this process); only then is the store read.
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

        val keypair = handedOff ?: loadOrGenerateKeypair(context) ?: return null
        try {
            // MR-2117: the server encapsulated to the UPLOADED public key. A
            // different pair can only derive a PSK the server does not have
            // (implicit rejection), which shows up as a handshake that never
            // completes; refuse here instead, where the cause is known.
            val uploaded = uploadedPublicKey
            if (uploaded != null && !keypair.publicKey.contentEquals(uploaded)) {
                FaultReporter.report(
                    FaultReporter.PATH_QUANTUM,
                    "pq_keypair_not_the_uploaded_one",
                    "The ML-KEM keypair available to decapsulate is not the one whose public key was uploaded — bilateral PQ aborted",
                )
                return null
            }
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
            // shipped up to 1.4.28 checked only the length. A key that fails
            // that check is NOT recoverable, so retrying it would fail every
            // connect forever; discard it and re-key instead. The server
            // re-pins the new public key on the next handshake, so the only
            // cost is one extra keygen.
            val usable = RosenpassNative.storedKeyUsable(existing.secretKey)
            // MR-2121: FIPS 203 7.3 binds the encapsulation key EMBEDDED in the
            // secret key to H(ek); nothing bound the separate public-key file to it.
            // The two halves are written as two files, so a save that dies
            // between them leaves one pair's pk beside another pair's sk. That
            // sk passes 7.3, the wrong pk is uploaded on every connect, and
            // every BirdoPQ connect fails until sign-out. Desktop rejects such
            // a pair (birdo_pq.rs read_keypair); so does this.
            if (usable && storedPublicKeyMatchesSecretKey(existing.publicKey, existing.secretKey)) {
                return existing
            }

            if (usable) {
                FaultReporter.report(
                    FaultReporter.PATH_QUANTUM,
                    "pq_stored_public_key_mismatch_rekeyed",
                    "Persisted ML-KEM public key differs from the one embedded in the secret key — discarded and re-keying",
                )
            } else {
                FaultReporter.report(
                    FaultReporter.PATH_QUANTUM,
                    "pq_stored_key_unusable_rekeyed",
                    "Persisted ML-KEM secret key failed FIPS 203 validation — discarded and re-keying",
                )
            }
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

    /**
     * MR-2121: is [publicKey] the encapsulation key embedded in [secretKey] at
     * [EMBEDDED_PUBLIC_KEY_OFFSET]? Both are public key material, so a plain
     * comparison is enough. A half of the wrong length never matches.
     */
    private fun storedPublicKeyMatchesSecretKey(publicKey: ByteArray, secretKey: ByteArray): Boolean {
        if (publicKey.size != RosenpassNative.PUBLIC_KEY_BYTES) return false
        if (secretKey.size != RosenpassNative.SECRET_KEY_BYTES) return false
        for (i in publicKey.indices) {
            if (publicKey[i] != secretKey[EMBEDDED_PUBLIC_KEY_OFFSET + i]) return false
        }
        return true
    }

    private fun ensureKeyStore(context: Context): BirdoPqKeyStore {
        return keyStore ?: synchronized(this) {
            keyStore ?: BirdoPqKeyStore(context.applicationContext).also { keyStore = it }
        }
    }

    // PFA-M5: legacy DEFAULT_NONCE_BYTES constant removed — `tryDecapsulate`
    // now refuses to derive a PSK against a missing/empty per-connect nonce.
}
