package app.birdo.vpn.data.preferences

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BirdoShield (D18) — the persisted pref behind the toggle.
 *
 * The review of #403 flipped `getBoolean(KEY_DNS_FILTERING_ENABLED, true)` and
 * the whole suite stayed green: no test read the pref through
 * [AppPreferences], so "OFF by default" rested on the wire model's default
 * alone. The store is a mocked [SharedPreferences] that answers every read
 * with the default the caller passed — which is exactly what a fresh install
 * (no key written) does — so these pin what a device that never touched the
 * toggle asks for, and the key name it is persisted under.
 */
class AppPreferencesTest {

    private lateinit var store: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        store = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        // A fresh install: nothing written, every read returns its default.
        every { store.getBoolean(any(), any()) } answers { secondArg() }
        every { store.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.commit() } returns true
        val context = mockk<Context>()
        every { context.getSharedPreferences("birdo_vpn_prefs", Context.MODE_PRIVATE) } returns store
        prefs = AppPreferences(context)
    }

    @Test
    fun `BirdoShield is OFF on a device that never touched the toggle`() {
        assertFalse(prefs.dnsFilteringEnabled)
        // The default is asked for under the key the setter writes — a
        // renamed key on either side would read every installed user as OFF.
        verify(exactly = 1) { store.getBoolean("dns_filtering_enabled", false) }
    }

    @Test
    fun `BirdoShield is persisted synchronously under its key`() {
        prefs.dnsFilteringEnabled = true

        // commit(), not apply(): the connect that reads the flag may start
        // immediately after the flip, so the write must be durable first.
        verify(exactly = 1) { editor.putBoolean("dns_filtering_enabled", true) }
        verify(exactly = 1) { editor.commit() }
        verify(exactly = 0) { editor.apply() }
    }

    @Test
    fun `a stored ON is read back as ON`() {
        every { store.getBoolean("dns_filtering_enabled", any()) } returns true
        assertTrue(prefs.dnsFilteringEnabled)
    }
}
