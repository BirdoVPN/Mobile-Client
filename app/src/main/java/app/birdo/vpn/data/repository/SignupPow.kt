package app.birdo.vpn.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Solver of the server's sign-up proof-of-work (MR-2152; birdo-web
 * `backend/src/auth/signup-pow.ts`, docs/SIGNUP-PROOF-OF-WORK.md).
 *
 * Hashcash over SHA-256: find a nonce such that
 * `SHA-256(UTF-8(challenge + ":" + nonce))` starts with at least `bits` zero
 * bits. The nonce is 1 to 32 characters of `[0-9A-Za-z]`; counting up in
 * lower-case base 36 from 0 is the server's own reference (`solvePow`), so this
 * finds the same first nonce. Only `java.security` is used.
 */
internal object SignupPow {

    /** The server never configures more than 28 bits (POW_MAX_CONFIG_BITS); anything above is not attempted. */
    const val MAX_SOLVABLE_BITS = 28

    /** The server refuses a token below this (POW_MIN_BITS). */
    const val MIN_BITS = 8

    /** Server limit on a token (POW_TOKEN_MAX_LENGTH). */
    const val MAX_CHALLENGE_LENGTH = 128

    /** How often the loop looks at its coroutine for cancellation. */
    private const val CANCEL_CHECK_MASK = 0x3FFL

    private val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz".toByteArray(Charsets.US_ASCII)

    /** Number of leading zero bits in [digest]. */
    fun leadingZeroBits(digest: ByteArray): Int {
        var bits = 0
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v == 0) {
                bits += 8
                continue
            }
            return bits + Integer.numberOfLeadingZeros(v) - 24
        }
        return bits
    }

    /** The nonce for counter [i]: lower-case base 36, "0" for 0. Same as `i.toString(36)` on the server. */
    fun nonceFor(i: Long): String = i.toString(36)

    /** Whether [nonce] solves [challenge] at [bits]: one SHA-256 (what the server's `meetsDifficulty` does). */
    fun meets(challenge: String, nonce: String, bits: Int): Boolean =
        leadingZeroBits(
            MessageDigest.getInstance("SHA-256").digest("$challenge:$nonce".toByteArray(Charsets.UTF_8)),
        ) >= bits

    /**
     * Search for a nonce on [dispatcher] (never the caller's thread). Cancellable:
     * the coroutine is checked every 1024 hashes, so cancelling it (a screen
     * that goes away, a timeout) stops the CPU within microseconds.
     */
    suspend fun solve(
        challenge: String,
        bits: Int,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): String {
        require(bits in 0..MAX_SOLVABLE_BITS) { "difficulty out of range" }
        return withContext(dispatcher) { search(challenge, bits) }
    }

    private suspend fun search(challenge: String, bits: Int): String {
        val prefix = "$challenge:".toByteArray(Charsets.UTF_8)
        // The buffer holds "challenge:" and, after it, room for the digits (a Long needs at most 13 in base 36).
        val buf = ByteArray(prefix.size + 13)
        System.arraycopy(prefix, 0, buf, 0, prefix.size)
        val md = MessageDigest.getInstance("SHA-256")
        var i = 0L
        while (true) {
            if ((i and CANCEL_CHECK_MASK) == 0L) currentCoroutineContext().ensureActive()
            // Write i in base 36 at the end of buf, then hash the used part.
            var n = i
            var end = buf.size
            if (n == 0L) {
                buf[--end] = DIGITS[0]
            } else {
                while (n > 0) {
                    buf[--end] = DIGITS[(n % 36).toInt()]
                    n /= 36
                }
            }
            val len = buf.size - end
            // The prefix and the digits are fed as two updates: no copy per round.
            md.update(buf, 0, prefix.size)
            md.update(buf, end, len)
            if (leadingZeroBits(md.digest()) >= bits) {
                return String(buf, end, len, Charsets.US_ASCII)
            }
            i++
        }
    }
}
