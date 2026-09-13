package app.birdo.vpn.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class KeystoreSecureStoreTest {

    private val prefs = FakeSharedPreferences()
    private val sealer = AesGcmSealer(JceKeySource())
    private val store = KeystoreSecureStore(prefs, sealer)

    @Test
    fun `put then get round-trips and nothing readable reaches the prefs file`() {
        store.put("access_token", "eyJ.abc.def")
        assertEquals("eyJ.abc.def", store.get("access_token"))

        val raw = prefs.getString("access_token", null)!!
        assertTrue(raw.startsWith("v1:"))
        assertFalse(raw.contains("eyJ"))
        // The stored value is a valid sealed blob under the key name as AAD.
        val blob = Base64.getDecoder().decode(raw.removePrefix("v1:"))
        assertEquals("eyJ.abc.def", String(sealer.open(blob, "access_token".toByteArray())))
    }

    @Test
    fun `get of a missing key is null`() {
        assertNull(store.get("nope"))
        assertFalse(store.contains("nope"))
    }

    @Test
    fun `a value moved to another key name fails to open and is dropped`() {
        store.put("refresh_token", "rt-1")
        prefs.data["access_token"] = prefs.data["refresh_token"]
        assertNull(store.get("access_token"))
        assertFalse("garbage is removed on read", prefs.contains("access_token"))
        assertEquals("rt-1", store.get("refresh_token"))
    }

    @Test
    fun `an unprefixed or non-base64 value is dropped, not returned`() {
        prefs.data["access_token"] = "plaintext-left-by-something-else"
        assertNull(store.get("access_token"))
        assertFalse(prefs.contains("access_token"))

        prefs.data["refresh_token"] = "v1:***not base64***"
        assertNull(store.get("refresh_token"))
        assertFalse(prefs.contains("refresh_token"))
    }

    @Test
    fun `commit flag selects the synchronous write path`() {
        store.put("a", "1")
        assertEquals(1, prefs.applies)
        assertEquals(0, prefs.commits)
        store.put("b", "2", commit = true)
        assertEquals(1, prefs.commits)
        store.remove("a", commit = true)
        assertEquals(2, prefs.commits)
        store.remove("b")
        assertEquals(2, prefs.applies)
    }

    @Test
    fun `remove and clear`() {
        store.put("a", "1")
        store.put("b", "2")
        store.remove("a")
        assertNull(store.get("a"))
        assertEquals("2", store.get("b"))
        store.clear()
        assertNull(store.get("b"))
        assertTrue(prefs.data.isEmpty())
    }

    @Test
    fun `overwriting a key replaces the blob`() {
        store.put("k", "first")
        val first = prefs.getString("k", null)
        store.put("k", "second")
        assertEquals("second", store.get("k"))
        assertTrue(first != prefs.getString("k", null))
    }

    @Test
    fun `unicode values survive`() {
        store.put("k", "ключ 🔑 مفتاح")
        assertEquals("ключ 🔑 مفتاح", store.get("k"))
    }

    @Test
    fun `in-memory store never touches disk and forgets on clear`() {
        val mem = InMemorySecureStore()
        mem.put("access_token", "x", commit = true)
        assertEquals("x", mem.get("access_token"))
        assertTrue(mem.contains("access_token"))
        mem.remove("access_token")
        assertNull(mem.get("access_token"))
        mem.put("a", "1"); mem.put("b", "2")
        mem.clear()
        assertFalse(mem.contains("a") || mem.contains("b"))
    }
}
