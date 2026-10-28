package app.birdo.vpn.data.auth

import android.util.Base64
import app.birdo.vpn.security.AesGcmSealer
import app.birdo.vpn.security.FakeSharedPreferences
import app.birdo.vpn.security.JceKeySource
import app.birdo.vpn.security.KeystoreSecureStore
import app.birdo.vpn.security.LegacyLeftover
import app.birdo.vpn.security.SecureStore
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TokenManager over the real [KeystoreSecureStore] (with a JCE key standing
 * in for the Android Keystore) and a scripted legacy leftover, so every path —
 * CRUD, commit discipline, deleting a pre-1.4.28 EncryptedSharedPreferences
 * file unread (MR-958), and Keystore-corruption recovery — is asserted, not
 * merely "did not crash".
 */
class TokenManagerTest {

    /** Scripted [LegacyLeftover]: whether a pre-1.4.28 EncryptedSharedPreferences file is on disk. */
    private class FakeLegacy(var onDisk: Boolean = false) : LegacyLeftover {
        var discarded = 0
        override fun present(): Boolean = onDisk
        override fun discard() {
            discarded++
            onDisk = false
        }
    }

    private class FakeBackend(
        override val legacy: LegacyLeftover,
        /** Number of open() calls that must fail before one succeeds. */
        var failuresBeforeOpen: Int = 0,
        private val storeFactory: () -> SecureStore = {
            KeystoreSecureStore(FakeSharedPreferences(), AesGcmSealer(JceKeySource()))
        },
    ) : TokenManager.Backend {
        var opens = 0
        var resets = 0
        var lastStore: SecureStore? = null

        override fun open(): SecureStore {
            opens++
            if (failuresBeforeOpen > 0) {
                failuresBeforeOpen--
                throw java.security.KeyStoreException("Keystore corrupted")
            }
            return storeFactory().also { lastStore = it }
        }

        override fun reset() {
            resets++
        }
    }

    private lateinit var legacy: FakeLegacy
    private lateinit var backend: FakeBackend
    private lateinit var tokenManager: TokenManager

    @Before
    fun setup() {
        // android.util.Base64 is a stub on the JVM (returns null); delegate the
        // URL-safe decode TokenManager.isTokenExpired uses to java.util.Base64.
        mockkStatic(Base64::class)
        every { Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getUrlDecoder().decode(firstArg<String>())
        }
        legacy = FakeLegacy()
        backend = FakeBackend(legacy)
        tokenManager = TokenManager(backend)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun jwt(expEpochSeconds: Long): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(JSONObject().put("exp", expEpochSeconds).put("sub", "u1").toString().toByteArray())
        return "$header.$payload.sig"
    }

    private val now get() = System.currentTimeMillis() / 1000

    // ── CRUD ─────────────────────────────────────────────────────

    @Test
    fun `access and refresh tokens round-trip and are persisted sealed`() {
        tokenManager.setTokens("access-1", "refresh-1")
        assertEquals("access-1", tokenManager.getAccessToken())
        assertEquals("refresh-1", tokenManager.getRefreshToken())
        val store = backend.lastStore!!
        assertTrue(store.contains("access_token"))
        assertTrue(store.contains("refresh_token"))
    }

    @Test
    fun `individual setters overwrite only their own key`() {
        tokenManager.setTokens("a1", "r1")
        tokenManager.setAccessToken("a2")
        assertEquals("a2", tokenManager.getAccessToken())
        assertEquals("r1", tokenManager.getRefreshToken())
        tokenManager.setRefreshToken("r2")
        assertEquals("a2", tokenManager.getAccessToken())
        assertEquals("r2", tokenManager.getRefreshToken())
    }

    @Test
    fun `a WireGuard private key an older build left at rest is purged at start-up`() {
        // A1-041: every connect used to write its session key here, and
        // nothing read it back.
        val prefs = FakeSharedPreferences()
        val sealed = KeystoreSecureStore(prefs, AesGcmSealer(JceKeySource()))
        sealed.put(TokenManager.LEGACY_KEY_WG_PRIVATE_KEY, "old-session-key", commit = true)
        sealed.put("refresh_token", "r", commit = true)
        backend = FakeBackend(legacy) { sealed }

        tokenManager = TokenManager(backend)

        assertFalse(tokenManager.holdsWireGuardKey())
        assertFalse(sealed.contains(TokenManager.LEGACY_KEY_WG_PRIVATE_KEY))
        // Nothing else is touched.
        assertEquals("r", tokenManager.getRefreshToken())
    }

    @Test
    fun `last key id and pending anonymous id round-trip and clear`() {
        tokenManager.setLastKeyId("key-42")
        tokenManager.setPendingAnonymousId("123456789012345678901234")
        assertEquals("key-42", tokenManager.getLastKeyId())
        assertEquals("123456789012345678901234", tokenManager.getPendingAnonymousId())

        tokenManager.clearLastKeyId()
        tokenManager.clearPendingAnonymousId()
        assertNull(tokenManager.getLastKeyId())
        assertNull(tokenManager.getPendingAnonymousId())
    }

    @Test
    fun `clearAll wipes every value`() {
        tokenManager.setTokens("a", "r")
        tokenManager.setLastKeyId("k")
        tokenManager.setPendingAnonymousId("id")
        tokenManager.clearAll()
        assertNull(tokenManager.getAccessToken())
        assertNull(tokenManager.getRefreshToken())
        assertNull(tokenManager.getLastKeyId())
        assertNull(tokenManager.getPendingAnonymousId())
        assertFalse(tokenManager.isLoggedIn())
    }

    // ── Commit discipline ────────────────────────────────────────

    @Test
    fun `single-use and must-be-on-disk values are committed synchronously`() {
        val prefs = FakeSharedPreferences()
        backend = FakeBackend(legacy) { KeystoreSecureStore(prefs, AesGcmSealer(JceKeySource())) }
        tokenManager = TokenManager(backend)

        tokenManager.setRefreshToken("r")
        assertEquals(1, prefs.commits)
        tokenManager.setTokens("a", "r2")
        assertEquals(3, prefs.commits)
        tokenManager.setPendingAnonymousId("id")
        tokenManager.clearPendingAnonymousId()
        assertEquals(5, prefs.commits)
        assertEquals(0, prefs.applies)

        tokenManager.setAccessToken("a2")
        tokenManager.setLastKeyId("k")
        assertEquals(2, prefs.applies)
        assertEquals(5, prefs.commits)
    }

    // ── Length validation ────────────────────────────────────────

    @Test
    fun `tokens over 4096 chars are rejected before anything is stored`() {
        val oversized = "a".repeat(4097)
        var e = assertThrows(IllegalArgumentException::class.java) { tokenManager.setTokens(oversized, "ok") }
        assertTrue(e.message!!.contains("Access token"))
        e = assertThrows(IllegalArgumentException::class.java) { tokenManager.setTokens("ok", oversized) }
        assertTrue(e.message!!.contains("Refresh token"))
        assertThrows(IllegalArgumentException::class.java) { tokenManager.setAccessToken(oversized) }
        assertThrows(IllegalArgumentException::class.java) { tokenManager.setRefreshToken(oversized) }
        assertNull(tokenManager.getAccessToken())
        assertNull(tokenManager.getRefreshToken())
    }

    @Test
    fun `tokens at exactly 4096 chars are accepted`() {
        val max = "a".repeat(4096)
        tokenManager.setTokens(max, max)
        assertEquals(max, tokenManager.getAccessToken())
        assertEquals(max, tokenManager.getRefreshToken())
    }

    // ── isLoggedIn ───────────────────────────────────────────────

    @Test
    fun `isLoggedIn is true with a live access token`() {
        tokenManager.setTokens(jwt(now + 600), jwt(now - 10))
        assertTrue(tokenManager.isLoggedIn())
    }

    @Test
    fun `isLoggedIn falls through to a live refresh token when the access token expired`() {
        tokenManager.setTokens(jwt(now - 10), jwt(now + 3600))
        assertTrue(tokenManager.isLoggedIn())
    }

    @Test
    fun `isLoggedIn is false when both expired, absent, malformed or without exp`() {
        assertFalse(tokenManager.isLoggedIn())
        tokenManager.setTokens(jwt(now - 10), jwt(now - 10))
        assertFalse(tokenManager.isLoggedIn())
        tokenManager.setTokens("not.a", "jwt")
        assertFalse(tokenManager.isLoggedIn())
        val noExp = "h." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"x"}""".toByteArray()) + ".s"
        tokenManager.setTokens(noExp, noExp)
        assertFalse(tokenManager.isLoggedIn())
    }

    // ── Pre-1.4.28 store (MR-958) ────────────────────────────────

    @Test
    fun `a pre-1_4_28 EncryptedSharedPreferences file is deleted unread and the user signs in again`() {
        legacy = FakeLegacy(onDisk = true)
        backend = FakeBackend(legacy)
        tokenManager = TokenManager(backend)

        assertEquals(1, legacy.discarded)
        assertFalse(legacy.onDisk)
        assertNull(tokenManager.getAccessToken())
        assertNull(tokenManager.getRefreshToken())
        assertFalse(tokenManager.isLoggedIn())
    }

    @Test
    fun `no legacy file means nothing is deleted`() {
        assertEquals(0, legacy.discarded)
        assertNull(tokenManager.getAccessToken())
    }

    @Test
    fun `a session that already migrated survives a lingering legacy file`() {
        val prefs = FakeSharedPreferences()
        val sealer = AesGcmSealer(JceKeySource())
        KeystoreSecureStore(prefs, sealer).put("refresh_token", "fresh-rotated", commit = true)
        legacy = FakeLegacy(onDisk = true)
        backend = FakeBackend(legacy) { KeystoreSecureStore(prefs, sealer) }
        tokenManager = TokenManager(backend)

        assertEquals("fresh-rotated", tokenManager.getRefreshToken())
        assertEquals(1, legacy.discarded)
    }

    @Test
    fun `a legacy file that cannot be deleted does not take the app down`() {
        val stuck = object : LegacyLeftover {
            override fun present(): Boolean = true
            override fun discard() {
                throw SecurityException("no")
            }
        }
        backend = FakeBackend(stuck)
        tokenManager = TokenManager(backend)
        tokenManager.setTokens("a", "r")
        assertEquals("a", tokenManager.getAccessToken())
    }

    // ── Keystore corruption recovery ─────────────────────────────

    @Test
    fun `a Keystore that fails once is reset and reopened`() {
        backend = FakeBackend(legacy, failuresBeforeOpen = 1)
        tokenManager = TokenManager(backend)
        assertEquals(2, backend.opens)
        assertEquals(1, backend.resets)
        tokenManager.setTokens("a", "r")
        assertEquals("a", tokenManager.getAccessToken())
        assertTrue("persisted in the reopened store", backend.lastStore!!.contains("access_token"))
    }

    @Test
    fun `a Keystore that fails twice falls back to memory and recovers on the next setTokens`() {
        backend = FakeBackend(legacy, failuresBeforeOpen = 3)
        tokenManager = TokenManager(backend)
        assertEquals(2, backend.opens)
        assertEquals(1, backend.resets)
        assertNull(backend.lastStore)
        assertNull(tokenManager.getAccessToken())

        // Still broken on the third attempt: tokens are held in memory only.
        tokenManager.setTokens("a1", "r1")
        assertEquals(3, backend.opens)
        assertNull(backend.lastStore)
        assertEquals("a1", tokenManager.getAccessToken())

        // Keystore healthy again: the next login lands in the persistent store.
        tokenManager.setTokens("a2", "r2")
        assertEquals(4, backend.opens)
        assertEquals("a2", tokenManager.getAccessToken())
        assertTrue(backend.lastStore!!.contains("refresh_token"))
        assertEquals("r2", backend.lastStore!!.get("refresh_token"))
    }

    @Test
    fun `a legacy file is deleted even while on the in-memory fallback`() {
        legacy = FakeLegacy(onDisk = true)
        backend = FakeBackend(legacy, failuresBeforeOpen = 2)
        tokenManager = TokenManager(backend)
        assertEquals(1, legacy.discarded)
        assertNull(tokenManager.getAccessToken())
    }
}
