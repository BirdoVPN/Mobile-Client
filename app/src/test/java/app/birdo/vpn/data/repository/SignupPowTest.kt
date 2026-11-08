package app.birdo.vpn.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * MR-2152. The vectors below were produced by the SERVER's own code, not by
 * this solver: birdo-web `backend/src/auth/signup-pow.ts` at origin/main
 * (01fbd9a1), run under node with `powKeyFromSecret("unit-test-fixture-not-a-real-key")`,
 * `mintPowToken(key, "signup", bits, 1767225600000, 300, id)` and the server's
 * `solvePow(token, bits)` (first nonce in base-36 counting order), then
 * `powDigest` / `leadingZeroBits` / `meetsDifficulty` on the result.
 */
class SignupPowTest {

    private data class Vector(val bits: Int, val token: String, val nonce: String, val digestHex: String, val zeroBits: Int)

    private val vectors = listOf(
        Vector(
            12,
            "p1.70c42181.signup.12.1767225900.00112233445566778899aabbccddeeff.FTFqEjbzmcjhEPSJfhOucNw2mAsGJfEwszbNogxzKXE",
            "4q",
            "000c62912e9e0f0930c26f38adfdee013fa6f19ab882eb089bd7d5d4ab29e38f",
            12,
        ),
        Vector(
            16,
            "p1.70c42181.signup.16.1767225900.0f1e2d3c4b5a69788796a5b4c3d2e1f0.7D8KY3uaVUM_wSXlvO-FGOBUwVWLoPWJxUG04Jq2RhU",
            "9g9",
            "00003f9631bf9e92d5857b8e8391c7b270ff0f9b86746c8f720d751eb1f46545",
            18,
        ),
        Vector(
            20,
            "p1.70c42181.signup.20.1767225900.a1b2c3d4e5f60718293a4b5c6d7e8f90.KDwfk6hm_h0tz-fkVqxIYsmJpyafRwzXXjiCrqCTlnc",
            "uzs8",
            "00000fcc2990cae169300c5c0e8ff3132117becce2c288cbd5b8c8f8597f8c33",
            20,
        ),
    )

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `server digest and leading zero bits match for every server vector`() {
        for (v in vectors) {
            val digest = sha256("${v.token}:${v.nonce}")
            assertEquals(v.digestHex, digest.joinToString("") { "%02x".format(it) })
            assertEquals(v.zeroBits, SignupPow.leadingZeroBits(digest))
            assertTrue(SignupPow.meets(v.token, v.nonce, v.bits))
        }
    }

    @Test
    fun `solve finds exactly the first nonce the servers own solver finds`() = runBlocking {
        for (v in vectors) {
            assertEquals("bits=${v.bits}", v.nonce, SignupPow.solve(v.token, v.bits, Dispatchers.Default))
        }
    }

    @Test
    fun `a nonce below the target is not accepted`() {
        // "4q" has 12 zero bits, not 13.
        assertFalse(SignupPow.meets(vectors[0].token, vectors[0].nonce, 13))
    }

    @Test
    fun `nonce is base 36 lower case from zero matching toString 36 on the server`() {
        assertEquals("0", SignupPow.nonceFor(0))
        assertEquals("z", SignupPow.nonceFor(35))
        assertEquals("10", SignupPow.nonceFor(36))
        assertEquals("uzs8", SignupPow.nonceFor("uzs8".toLong(36)))
        assertTrue(Regex("^[0-9A-Za-z]{1,32}$").matches(SignupPow.nonceFor(Long.MAX_VALUE)))
    }

    @Test
    fun `leadingZeroBits counts whole and partial bytes`() {
        assertEquals(0, SignupPow.leadingZeroBits(byteArrayOf(0x80.toByte())))
        assertEquals(7, SignupPow.leadingZeroBits(byteArrayOf(0x01)))
        assertEquals(8, SignupPow.leadingZeroBits(byteArrayOf(0x00, 0xFF.toByte())))
        assertEquals(12, SignupPow.leadingZeroBits(byteArrayOf(0x00, 0x0F)))
        assertEquals(16, SignupPow.leadingZeroBits(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun `a difficulty the app will not attempt is refused up front`() = runBlocking {
        try {
            SignupPow.solve(vectors[0].token, SignupPow.MAX_SOLVABLE_BITS + 1)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `cancelling the coroutine stops a solve that could not finish`() = runBlocking {
        // 28 bits is about 268M hashes: many seconds even on a fast core.
        val started = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            started.complete(Unit)
            SignupPow.solve(vectors[0].token, SignupPow.MAX_SOLVABLE_BITS)
        }
        started.await()
        val t0 = System.nanoTime()
        // Uncancelled this runs for tens of seconds; the bound is loose on purpose (a cold JVM on a
        // slow disk took 8 s to load the cancellation classes once), the point is that it STOPS.
        withTimeout(25_000) { job.cancelAndJoin() }
        println("SIGNUP_POW_CANCEL stopped_after_ms=${(System.nanoTime() - t0) / 1_000_000}")
        assertTrue(job.isCancelled)
        try {
            job.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    /** Prints hashes per second for this JVM; asserts only that it is not absurdly slow. */
    @Test
    fun `benchmark hashes per second`() = runBlocking {
        val token = vectors[2].token
        SignupPow.solve(token, 18) // warm up the JIT
        var total = 0L
        var round = 0
        val t0 = System.nanoTime()
        // Solve distinct tokens at 20 bits until about 2 s have passed; hashes = nonce counter + 1 each.
        while (System.nanoTime() - t0 < 2_000_000_000L) {
            val nonce = SignupPow.solve(token + "x" + round, 20)
            total += nonce.toLong(36) + 1
            round++
        }
        val secs = (System.nanoTime() - t0) / 1e9
        val rate = total / secs
        println("SIGNUP_POW_BENCH hashes=$total seconds=${"%.2f".format(secs)} hashes_per_s=${rate.toLong()} solves=$round")
        assertTrue("only ${rate.toLong()} hashes/s", rate > 100_000)
    }
}
