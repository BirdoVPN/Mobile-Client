package app.birdo.vpn.data.preferences

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Before
import org.junit.Test

/**
 * MR-2492 round 3 (review of #652, L1): the last server and who chose it are
 * ONE fact. Written in two edits, a process that dies between the two disk
 * commits can pair a new server the user picked with the previous
 * "the client chose it", which a move off that server would then read as
 * leave-the-country-allowed. One edit, one commit.
 */
class LastServerPrefsTest {

    private lateinit var store: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        store = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        every { store.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        val context = mockk<Context>()
        every { context.getSharedPreferences("birdo_vpn_prefs", Context.MODE_PRIVATE) } returns store
        prefs = AppPreferences(context)
    }

    @Test
    fun `MR-2492 the last server and who chose it are written in one edit`() {
        prefs.rememberLastServer("srv-9", autoPicked = true)

        verify(exactly = 1) { store.edit() }
        verifyOrder {
            editor.putString("last_server_id", "srv-9")
            editor.putBoolean("last_server_auto_picked", true)
            editor.apply()
        }
        verify(exactly = 1) { editor.apply() }
    }

    @Test
    fun `MR-2492 a server the user picked is written as the user's in the same edit`() {
        prefs.rememberLastServer("srv-1", autoPicked = false)

        verify(exactly = 1) { store.edit() }
        verify(exactly = 1) { editor.putString("last_server_id", "srv-1") }
        verify(exactly = 1) { editor.putBoolean("last_server_auto_picked", false) }
    }
}
