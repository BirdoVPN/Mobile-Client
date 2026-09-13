package app.birdo.vpn.data.auth

import android.util.Base64
import app.birdo.vpn.security.AesGcmSealer
import app.birdo.vpn.security.FakeSharedPreferences
import app.birdo.vpn.security.JceKeySource
import app.birdo.vpn.security.KeystoreSecureStore
import app.birdo.vpn.security.LegacySecretSource
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
 * in for the Android Keystore) and a scripted legacy source, so every path —
 * CRUD, commit discipline, the EncryptedSharedPreferences → v2 migration,
 * and Keystore-corruption recovery — is asserted, not merely "did not crash".
 */
class TokenManagerTest {

    /** Scripted [LegacySecretSource]: what the old EncryptedSharedPreferences would yield. */
    private class FakeLegacy(var values: Map<String, String>?, var throws: Boolean = false) : LegacySecretSource {
        var destroyed = 0
        var reads = 0
        override fun readAll(): Map<String, String>? {
            reads++
            if (throws) throw IllegalStateException("Tink keyset unreadable")
            return values
        }
        override fun destroy() {
            destroyed++
            values = null
        }
    }

    private class FakeBackend(
        override val legacy: LegacySecretSource,
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
        legacy = FakeLegacy(values = null)
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
    fun `WireGuard private key, last key id and pending anonymous id round-trip and clear`() {
        tokenManager.setWireGuardPrivateKey("wg-priv")
        tokenManager.setLastKeyId("key-42")
        tokenManager.setPendingAnonymousId("123456789012345678901234")
        assertEquals("wg-priv", tokenManager.getWireGuardPrivateKey())
        assertEquals("key-42", tokenManager.getLastKeyId())
        assertEquals("123456789012345678901234", tokenManager.getPendingAnonymousId())

        tokenManager.clearWireGuardPrivateKey()
        tokenManager.clearLastKeyId()
        tokenManager.clearPendingAnonymousId()
        assertNull(tokenManager.getWireGuardPrivateKey())
        assertNull(tokenManager.getLastKeyId())
        assertNull(tokenManager.getPendingAnonymousId())
    }

    @Test
    fun `clearAll wipes every value`() {
        tokenManager.setTokens("a", "r")
        tokenManager.setWireGuardPrivateKey("wg")
        tokenManager.setLastKeyId("k")
        tokenManager.setPendingAnonymousId("id")
        tokenManager.clearAll()
        assertNull(tokenManager.getAccessToken())
        assertNull(tokenManager.getRefreshToken())
        assertNull(tokenManager.getWireGuardPrivateKey())
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
        tokenManager.setWireGuardPrivateKey("wg")
        tokenManager.setLastKeyId("k")
        assertEquals(3, prefs.applies)
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

    // ── Legacy migration ─────────────────────────────────────────

    @Test
    fun `legacy EncryptedSharedPreferences values are copied in, verified and the legacy store destroyed`() {
        legacy = FakeLegacy(
            mapOf(
                "access_token" to "legacy-access",
                "refresh_token" to "legacy-refresh",
                "wireguard_private_key" to "legacy-wg",
                "pending_anonymous_id" to "legacy-anon",
            ),
        )
        backend = FakeBackend(legacy)
        tokenManager = TokenManager(backend)

        assertEquals("legacy-access", tokenManager.getAccessToken())
        assertEquals("legacy-refresh", tokenManager.getRefreshToken())
        assertEquals("legacy-wg", tokenManager.getWireGuardPrivateKey())
        assertEquals("legacy-anon", tokenManager.getPendingAnonymousId())
        assertEquals(1, legacy.destroyed)
        assertEquals(1, legacy.reads)
    }

    @Test
    fun `no legacy store means nothing is read or destroyed`() {
        assertEquals(1, legacy.reads)
        assertEquals(0, legacy.destroyed)
        assertNull(tokenManager.getAccessToken())
    }

    @Test
    fun `an unreadable legacy store is destroyed and the user starts logged out`() {
        legacy = FakeLegacy(values = mapOf("access_token" to "x"), throws = true)
        backend = FakeBackend(legacy)
        tokenManager = TokenManager(backend)
        assertNull(tokenManager.getAccessToken())
        assertEquals(1, legacy.destroyed)
    }

    @Test
    fun `a populated v2 store is never overwritten by a lingering legacy copy`() {
        val prefs = FakeSharedPreferences()
        val sealer = AesGcmSealer(JceKeySource())
        KeystoreSecureStore(prefs, sealer).put("refresh_token", "fresh-rotated", commit = true)
        legacy = FakeLegacy(mapOf("access_token" to "stale-access", "refresh_token" to "stale-consumed"))
        backend = FakeBackend(legacy) { KeystoreSecureStore(prefs, sealer) }
        tokenManager = TokenManager(backend)

        assertEquals("fresh-rotated", tokenManager.getRefreshToken())
        assertNull("nothing from the stale copy leaks in", tokenManager.getAccessToken())
        assertEquals(1, legacy.destroyed)
    }

    @Test
    fun `a copy that does not read back is rolled back and the legacy store kept`() {
        // A store that silently loses one key — the migration must notice.
        val lossy = object : SecureStore {
            val inner = KeystoreSecureStore(FakeSharedPreferences(), AesGcmSealer(JceKeySource()))
            override fun get(key: String) = if (key == "refresh_token") null else inner.get(key)
            override fun put(key: String, value: String, commit: Boolean) = inner.put(key, value, commit)
            override fun remove(key: String, commit: Boolean) = inner.remove(key, commit)
            override fun contains(key: String) = inner.contains(key)
            override fun clear() = inner.clear()
        }
        legacy = FakeLegacy(mapOf("access_token" to "a", "refresh_token" to "r"))
        val result = TokenManager.migrateLegacyStore(lossy, legacy)
        assertEquals(TokenManager.Migration.VERIFY_FAILED, result)
        assertEquals(0, legacy.destroyed)
        assertFalse("copies undone", lossy.inner.contains("access_token"))
        assertFalse(lossy.inner.contains("refresh_token"))
    }

    @Test
    fun `migration outcomes are reported`() {
        val store = KeystoreSecureStore(FakeSharedPreferences(), AesGcmSealer(JceKeySource()))
        assertEquals(TokenManager.Migration.NOTHING_TO_DO, TokenManager.migrateLegacyStore(store, FakeLegacy(null)))
        assertEquals(TokenManager.Migration.MIGRATED, TokenManager.migrateLegacyStore(store, FakeLegacy(mapOf("k" to "v"))))
        assertEquals("v", store.get("k"))
        assertEquals(TokenManager.Migration.ALREADY_MIGRATED, TokenManager.migrateLegacyStore(store, FakeLegacy(mapOf("k" to "old"))))
        assertEquals("v", store.get("k"))
        assertEquals(TokenManager.Migration.LEGACY_UNREADABLE, TokenManager.migrateLegacyStore(store, FakeLegacy(mapOf("k" to "x"), throws = true)))
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
    fun `legacy migration is skipped while on the in-memory fallback`() {
        legacy = FakeLegacy(mapOf("access_token" to "x"))
        backend = FakeBackend(legacy, failuresBeforeOpen = 2)
        tokenManager = TokenManager(backend)
        assertEquals(0, legacy.reads)
        assertEquals(0, legacy.destroyed)
    }
}
