package app.birdo.vpn.service

import android.content.Context
import android.util.Base64
import android.util.Log
import app.birdo.vpn.data.model.ConnectResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Manages Rosenpass post-quantum pre-shared key exchange for WireGuard.
 *
 * Rosenpass uses Classic McEliece + Kyber (ML-KEM) to establish a shared
 * symmetric key that is injected as WireGuard's PresharedKey field. This
 * provides post-quantum security: even if an attacker records encrypted VPN
 * traffic today and later gains access to a quantum computer capable of
 * breaking Curve25519, the PQ-PSK ensures the traffic remains encrypted.
 *
 * Architecture:
 * ```
 * Client                          Server
 *   |    Rosenpass handshake        |
 *   | (Classic McEliece + Kyber)    |
 *   |──────────────────────────────▶|
 *   |◀──────────────────────────────|
 *   |    Derived 32-byte PSK        |
 *   |                               |
 *   └─▶ WireGuard PresharedKey      |
 * ```
 *
 * The PSK is rotated approximately every 2 minutes via periodic rekeying.
 * Between rekeys, the current PSK remains active in the WireGuard tunnel.
 *
 * Implementation approaches (in priority order):
 * 1. **Native library** — Rosenpass compiled as .so via cargo-ndk with JNI bridge
 * 2. **Binary execution** — Rosenpass binary for Android targets
 * 3. **API-mediated** — Server performs PQ key exchange and returns PSK (fallback)
 *
 * For initial deployment, approach 3 (API-mediated) is used as it doesn't
 * require shipping native Rosenpass binaries with the APK. The server already
 * runs Rosenpass and can derive a PQ-PSK that both sides share.
 */
object RosenpassManager {

    private const val TAG = "RosenpassManager"

    /** Rosenpass rekey interval (approximately 2 minutes) */
    private const val REKEY_INTERVAL_MS = 120_000L

    /** Length of the WireGuard PresharedKey in bytes */
    private const val PSK_LENGTH_BYTES = 32

    @Volatile
    private var isActive = false

    @Volatile
    private var currentPsk: ByteArray? = null

    @Volatile
    private var serverPublicKey: ByteArray? = null

    @Volatile
    private var clientKeyPair: RosenpassKeyPair? = null

    /** Whether quantum protection is currently active */
    fun isQuantumProtected(): Boolean = isActive && currentPsk != null

    /**
     * Get the current post-quantum pre-shared key as a Base64 string.
     * Returns null if PQ protection is not active.
     */
    fun getCurrentPsk(): String? {
        val psk = currentPsk ?: return null
        return Base64.encodeToString(psk, Base64.NO_WRAP)
    }

    /**
     * Perform the initial Rosenpass key exchange and derive a PQ-PSK.
     *
     * @param context   Application context
     * @param config    VPN connect response containing Rosenpass parameters
     * @return Base64-encoded 32-byte PSK, or null if PQ key exchange failed
     */
    suspend fun performKeyExchange(context: Context, config: ConnectResponse): String? = withContext(Dispatchers.IO) {
        val rosenpassPubKey = config.rosenpassPublicKey
        val rosenpassEndpoint = config.rosenpassEndpoint

        if (rosenpassPubKey == null) {
            Log.w(TAG, "No Rosenpass public key provided — PQ protection unavailable")
            return@withContext null
        }

        Log.i(TAG, "Performing post-quantum key exchange (Classic McEliece + Kyber)")

        try {
            // Store server's Rosenpass public key
            serverPublicKey = Base64.decode(rosenpassPubKey, Base64.NO_WRAP)

            // Try native Rosenpass library first
            val nativePsk = performNativeKeyExchange(context, rosenpassPubKey, rosenpassEndpoint)
            if (nativePsk != null) {
                currentPsk = nativePsk
                isActive = true
                val pskB64 = Base64.encodeToString(nativePsk, Base64.NO_WRAP)
                // SEC: never log PSK material — not even truncated/base64. Even partial
                // bytes leak entropy for an attacker who can read logcat.
                Log.i(TAG, "PQ-PSK derived via native Rosenpass")
                return@withContext pskB64
            }

            // Native Rosenpass unavailable — fall back to the server-provided
            // WireGuard PSK that the server already negotiated via its own
            // Rosenpass exchange. Both sides MUST hold the IDENTICAL PSK or
            // every WireGuard packet will fail AEAD authentication on the
            // server (symptom: handshake completes, uploads succeed, but no
            // return packets are ever decrypted → "no IP / no downloads").
            //
            // Historical bug: a previous "hybrid PSK" derivation mixed in
            // local-only SecureRandom entropy that the server could never
            // reproduce, producing a different PSK on each side. That broke
            // Quantum Protection on every connection. Do NOT reintroduce
            // client-only entropy here — true bilateral PQ protection
            // requires a real Rosenpass UDP exchange (TODO).
            if (config.presharedKey != null) {
                currentPsk = Base64.decode(config.presharedKey, Base64.NO_WRAP)
                isActive = true
                Log.i(TAG, "Using server-provided PQ-PSK (server-side Rosenpass active)")
                return@withContext config.presharedKey
            }

            Log.w(TAG, "PQ key exchange failed — no PSK available")
            return@withContext null

        } catch (e: Exception) {
            Log.e(TAG, "Post-quantum key exchange failed", e)
            return@withContext null
        }
    }

    /**
     * Stop PQ protection and zero sensitive key material.
     */
    fun stop() {
        Log.i(TAG, "Stopping Rosenpass PQ protection")
        isActive = false
        // Zero key material
        currentPsk?.fill(0)
        currentPsk = null
        serverPublicKey?.fill(0)
        serverPublicKey = null
        clientKeyPair?.let {
            it.secretKey.fill(0)
            it.publicKey.fill(0)
        }
        clientKeyPair = null
    }

    // ── Native Rosenpass ────────────────────────────────────────

    /**
     * Attempt to perform key exchange via the native Rosenpass library (JNI).
     *
     * The native lib exposes:
     * - `RosenpassNative.generateKeyPair()` → (publicKey, secretKey)
     * - `RosenpassNative.handshake(serverPubKey, clientSecretKey)` → 32-byte PSK
     */
    private fun performNativeKeyExchange(context: Context, serverPubKeyB64: String, endpoint: String?): ByteArray? {
        return try {
            val nativeClass = Class.forName("app.birdo.vpn.service.RosenpassNative")

            // Generate client keypair
            val genMethod = nativeClass.getDeclaredMethod("generateKeyPair")
            val keyPairResult = genMethod.invoke(null) as? Array<ByteArray>
            if (keyPairResult == null || keyPairResult.size != 2) {
                Log.e(TAG, "Native generateKeyPair returned invalid result")
                return null
            }

            clientKeyPair = RosenpassKeyPair(
                publicKey = keyPairResult[0],
                secretKey = keyPairResult[1],
            )

            // Perform handshake with server's public key
            val serverPubKey = Base64.decode(serverPubKeyB64, Base64.NO_WRAP)
            val handshakeMethod = nativeClass.getDeclaredMethod(
                "handshake",
                ByteArray::class.java,
                ByteArray::class.java,
                String::class.java,
            )
            val psk = handshakeMethod.invoke(null, serverPubKey, keyPairResult[1], endpoint ?: "") as? ByteArray
            if (psk != null && psk.size == PSK_LENGTH_BYTES) {
                Log.i(TAG, "Native Rosenpass handshake succeeded")
                psk
            } else {
                Log.w(TAG, "Native handshake returned invalid PSK (size=${psk?.size})")
                null
            }
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "RosenpassNative not available — using hybrid fallback")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Native Rosenpass handshake failed", e)
            null
        }
    }

    // ── Hybrid PQ-PSK Derivation (DEPRECATED — DO NOT USE) ─────
    //
    // This routine mixes local SecureRandom entropy into the PSK, which the
    // server cannot reproduce. The result is a PSK mismatch that silently
    // breaks WireGuard AEAD on every packet (handshake OK, no return traffic).
    // Kept only as a reference for the HKDF helpers; replace with a real
    // Rosenpass UDP key exchange before re-enabling.
    @Suppress("unused")
    @Deprecated("Causes PSK mismatch — use server-provided PSK or a real Rosenpass exchange")
    private fun deriveHybridPsk(serverRosenpassKey: ByteArray, serverWgPsk: String?): ByteArray? {
        return try {
            // Local entropy
            val localEntropy = ByteArray(32).also { SecureRandom().nextBytes(it) }

            // Initial keying material from server's PQ public key + local entropy
            val ikm = ByteArray(serverRosenpassKey.size + localEntropy.size + (serverWgPsk?.length ?: 0))
            System.arraycopy(serverRosenpassKey, 0, ikm, 0, serverRosenpassKey.size)
            System.arraycopy(localEntropy, 0, ikm, serverRosenpassKey.size, localEntropy.size)

            // Mix in server-provided PSK if available (already PQ-derived by server's Rosenpass)
            serverWgPsk?.let {
                val pskBytes = Base64.decode(it, Base64.NO_WRAP)
                System.arraycopy(pskBytes, 0, ikm, serverRosenpassKey.size + localEntropy.size, pskBytes.size)
            }

            // HKDF-Extract
            val salt = "BirdoVPN-PQ-PSK-v1".toByteArray()
            val prk = hmacSha256(salt, ikm)

            // HKDF-Expand to 32 bytes
            val info = "wireguard-preshared-key".toByteArray()
            val psk = hkdfExpand(prk, info, PSK_LENGTH_BYTES)

            // Zero intermediate material
            ikm.fill(0)
            prk.fill(0)
            localEntropy.fill(0)

            psk
        } catch (e: Exception) {
            Log.e(TAG, "Hybrid PSK derivation failed", e)
            null
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
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

    // ── Data Classes ────────────────────────────────────────────

    private data class RosenpassKeyPair(
        val publicKey: ByteArray,
        val secretKey: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}
