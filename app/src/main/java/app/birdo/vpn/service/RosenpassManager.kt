package app.birdo.vpn.service

import android.content.Context
import android.util.Base64
import android.util.Log
import app.birdo.vpn.data.model.ConnectResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
 * | [Mode.SERVER_PROVIDED] | Server returned a WireGuard-format PSK in [ConnectResponse.presharedKey] but no ciphertext | Partial — relies on TLS for PSK delivery |
 * | [Mode.DISABLED] | Neither bilateral exchange nor server PSK available | No |
 *
 * **Honest disclaimer for marketing copy:** Only [Mode.BILATERAL] provides
 * genuine post-quantum protection against Harvest-Now-Decrypt-Later. The
 * BILATERAL path is achieved by uploading our ML-KEM-1024 client public key
 * in the `/connect` request and decapsulating the server's response — no
 * extra UDP, no `rosenpass` binary, no libsodium. See
 * `native/rosenpass-jni/src/lib.rs` for the full protocol spec.
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
object RosenpassManager {

    private const val TAG = "RosenpassManager"

    /** WireGuard PresharedKey is exactly 32 bytes. */
    private const val PSK_LENGTH_BYTES = 32

    /** Upper bound for the server-supplied per-connect nonce (server mints 32 B). */
    private const val MAX_NONCE_BYTES = 64

    enum class Mode { DISABLED, SERVER_PROVIDED, BILATERAL }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentPsk: ByteArray? = null

    @Volatile
    private var keyStore: RosenpassKeyStore? = null

    private val _modeFlow = MutableStateFlow(Mode.DISABLED)
    val modeFlow: StateFlow<Mode> = _modeFlow.asStateFlow()

    /** True when we're currently providing some form of PQ-flavoured protection. */
    fun isQuantumProtected(): Boolean = currentPsk != null && _modeFlow.value != Mode.DISABLED

    /** True iff we're running the genuine bilateral PQ exchange. */
    fun isBilateral(): Boolean = _modeFlow.value == Mode.BILATERAL

    fun getCurrentPsk(): String? {
        val psk = currentPsk ?: return null
        return Base64.encodeToString(psk, Base64.NO_WRAP)
    }

    /** Diagnostic: returns the loaded native lib version, or `<not loaded>`. */
    fun nativeLibVersion(): String = RosenpassNative.getNativeVersion()

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
     * Performs the BirdoPQ v1 derivation and returns the PSK as Base64,
     * or `null` if no PQ-flavoured PSK is available.
     *
     * Implementation order:
     *   1. If the response contains a Rosenpass ciphertext (re-using the
     *      `rosenpassPublicKey` field) AND a nonce (re-using
     *      `rosenpassEndpoint`), and our native lib is loaded, decapsulate
     *      with our persisted ML-KEM secret key.
    *   2. If the server explicitly enabled quantum mode, fail closed by
    *      returning `null`; the classical PSK will not match the server-side
    *      peer that was reconfigured with the derived BirdoPQ PSK.
    *   3. Fall back to the server-supplied classic PSK if present.
    *   4. Otherwise return `null` and the caller MUST use plain WireGuard.
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
        if (config.quantumEnabled) {
            currentPsk?.fill(0)
            currentPsk = null
            _modeFlow.value = Mode.DISABLED
            Log.e(TAG, "BirdoPQ was enabled by server but client could not decapsulate; aborting")
            return@withContext null
        }
        return@withContext fallbackToServerPsk(config)
    }

    private fun fallbackToServerPsk(config: ConnectResponse): String? {
        val serverPsk = config.presharedKey
        if (serverPsk == null) {
            currentPsk?.fill(0)
            currentPsk = null
            _modeFlow.value = Mode.DISABLED
            Log.w(TAG, "no server-provided PSK either — quantum protection unavailable")
            return null
        }
        currentPsk?.fill(0)
        currentPsk = Base64.decode(serverPsk, Base64.NO_WRAP)
        _modeFlow.value = Mode.SERVER_PROVIDED
        Log.i(TAG, "using server-provided PSK (TLS-delivered, NOT HNDL-safe)")
        return serverPsk
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
     * On `null` the caller falls back to the server-provided classic PSK; on
     * crypto errors we log and bail out cleanly so a misconfigured peer
     * doesn't get masked by a silent downgrade.
     */
    private suspend fun tryDecapsulate(context: Context, config: ConnectResponse): ByteArray? {
        // PFA-H7: integrity-verify FIRST (which also performs the hash-then-load
        // sequence). isLoaded only becomes true after the hash matches.
        if (!RosenpassNative.verifyIntegrity(context)) {
            Log.e(TAG, "rosenpass-jni integrity unverified — bilateral PQ DISABLED")
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
        // to the server-provided classical PSK path (logged, surfaced via
        // modeFlow) instead.
        val nonceB64 = config.rosenpassEndpoint
        if (nonceB64.isNullOrBlank()) {
            Log.e(TAG, "server omitted per-connect PQ nonce — bilateral PQ aborted (PFA-M5)")
            return null
        }
        val nonce = try { Base64.decode(nonceB64, Base64.NO_WRAP) }
        catch (e: Exception) {
            Log.e(TAG, "malformed PQ nonce — bilateral PQ aborted", e)
            return null
        }
        // Bound the server-supplied nonce before it crosses the JNI boundary —
        // an empty or arbitrarily large buffer must not rely on Rust-side
        // validation alone.
        if (nonce.isEmpty() || nonce.size > MAX_NONCE_BYTES) {
            Log.e(TAG, "PQ nonce out of bounds (${nonce.size} B) — bilateral PQ aborted")
            return null
        }
        val ciphertext = try {
            Base64.decode(ctB64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "malformed PQ ciphertext", e)
            return null
        }
        if (ciphertext.size != RosenpassNative.CIPHERTEXT_BYTES) {
            Log.e(TAG, "PQ ciphertext has wrong size: ${ciphertext.size} != ${RosenpassNative.CIPHERTEXT_BYTES}")
            return null
        }

        val keypair = loadOrGenerateKeypair(context) ?: return null
        try {
            // The generate path validates sizes via StaticKeypair.fromRaw; the
            // LOAD path reads raw bytes from disk with no length check, so a
            // truncated/corrupted file would reach the JNI boundary unvalidated.
            // Enforce the same invariant on both paths.
            if (keypair.secretKey.size != RosenpassNative.SECRET_KEY_BYTES) {
                Log.e(TAG, "persisted ML-KEM secret key has wrong size (${keypair.secretKey.size} B) — bilateral PQ aborted")
                return null
            }

            val psk = try {
                RosenpassNative.deriveSharedPsk(keypair.secretKey, ciphertext, nonce)
            } catch (e: Throwable) {
                Log.e(TAG, "native deriveSharedPsk threw", e)
                return null
            }
            if (psk == null) {
                Log.w(TAG, "deriveSharedPsk returned null — falling back")
                return null
            }
            if (psk.size != PSK_LENGTH_BYTES) {
                Log.e(TAG, "deriveSharedPsk returned wrong-sized PSK (${psk.size} != $PSK_LENGTH_BYTES)")
                psk.fill(0)
                return null
            }
            return psk
        } finally {
            // Zero the in-memory copy of the long-lived PQ secret key once the
            // derivation is done — the canonical copy lives Keystore-encrypted
            // on disk (RosenpassKeyStore) and is re-read per connect, so this
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
        if (existing != null) return existing

        Log.i(TAG, "no persisted ML-KEM keypair — generating new (~10–50 ms)")
        val fresh = try {
            RosenpassNative.generateKeypair()
        } catch (e: Throwable) {
            Log.e(TAG, "native generateKeypair failed", e)
            return null
        }
        try {
            store.save(fresh)
        } catch (e: Exception) {
            Log.e(TAG, "failed to persist new keypair — not caching, will regenerate next time", e)
        }
        return fresh
    }

    private fun ensureKeyStore(context: Context): RosenpassKeyStore {
        return keyStore ?: synchronized(this) {
            keyStore ?: RosenpassKeyStore(context.applicationContext).also { keyStore = it }
        }
    }

    // PFA-M5: legacy DEFAULT_NONCE_BYTES constant removed — `tryDecapsulate`
    // now refuses to derive a PSK against a missing/empty per-connect nonce.

    // ── HKDF helpers (kept for tests + future on-wire derivations) ─────────

    @Suppress("unused")
    internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    @Suppress("unused")
    internal fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1
        while (offset < length) {
            val input = ByteArray(t.size + info.size + 1)
            System.arraycopy(t, 0, input, 0, t.size)
            System.arraycopy(info, 0, input, t.size, info.size)
            input[input.size - 1] = counter
            t = hmacSha256(prk, input)
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            counter++
        }
        return result
    }

    /** For test teardown only. NOT for runtime use. */
    internal fun resetForTesting() {
        scope.coroutineContext.cancel()
        currentPsk?.fill(0)
        currentPsk = null
        keyStore = null
        _modeFlow.value = Mode.DISABLED
    }
}
