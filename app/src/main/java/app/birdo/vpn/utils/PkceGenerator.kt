package app.birdo.vpn.utils

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * RFC 7636 PKCE (Proof Key for Code Exchange) for native SSO.
 *
 * The app is a public OAuth client of Birdo. The verifier never leaves the
 * device, so a handoff code intercepted from the birdo:// redirect is useless
 * without it. base64url-no-pad matches the backend's encoding exactly.
 */
object PkceGenerator {
    private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    data class Pkce(val verifier: String, val challenge: String)

    /**
     * verifier = base64url(32 random bytes) (43 chars);
     * challenge = base64url(SHA-256(ASCII(verifier))).
     */
    fun generate(): Pkce {
        val verifierBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.encodeToString(verifierBytes, B64_FLAGS)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(digest, B64_FLAGS)
        return Pkce(verifier, challenge)
    }

    /** Random anti-CSRF state token (base64url, 16 bytes). */
    fun randomState(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, B64_FLAGS)
    }
}
