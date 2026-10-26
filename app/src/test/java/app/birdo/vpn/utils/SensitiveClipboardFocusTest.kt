package app.birdo.vpn.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import app.birdo.vpn.utils.SensitiveClipboard.Seen
import app.birdo.vpn.utils.SensitiveClipboard.Verdict
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * MR-2129: the clear never wipes what it cannot see. Android 10+ lets an app
 * read the clipboard only while it has input focus, which Robolectric's
 * clipboard does not model, so these drive a mocked one: no description at the
 * end of the minute is a clipboard the app may not read, and the clear waits
 * for focus (MainActivity → [SensitiveClipboard.onWindowFocusGained]).
 *
 * SDK 34: Robolectric's SDK 35+ jars need Java 21, and CI runs 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensitiveClipboardFocusTest {

    private val ours = ClipData.newPlainText(LABEL, NUMBER)
    private val usersOwn = ClipData.newPlainText("note", "the user's own text")
    private val clipboard = mockk<ClipboardManager>(relaxed = true)
    private val context = mockk<Context>().also {
        every { it.applicationContext } returns it
        every { it.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard
    }

    @After
    fun tearDown() = unmockkAll()

    private fun idleSeconds(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test
    fun `a minute that ends in the background waits for focus, then clears`() {
        // Read at the copy, unreadable when the minute ends, ours again at focus.
        every { clipboard.primaryClipDescription } returnsMany listOf(ours.description, null, ours.description)
        every { clipboard.primaryClip } returns ours
        copySensitiveToClipboard(context, LABEL, NUMBER)

        idleSeconds(60)
        verify(exactly = 0) { clipboard.clearPrimaryClip() }

        SensitiveClipboard.onWindowFocusGained()
        verify(exactly = 1) { clipboard.clearPrimaryClip() }
    }

    @Test
    fun `a later copy found at focus is left alone, and its text is never read`() {
        every { clipboard.primaryClipDescription } returnsMany listOf(ours.description, null, usersOwn.description)
        copySensitiveToClipboard(context, LABEL, NUMBER)
        idleSeconds(60)

        SensitiveClipboard.onWindowFocusGained()
        // Done: a later focus does not look again.
        SensitiveClipboard.onWindowFocusGained()

        verify(exactly = 0) { clipboard.clearPrimaryClip() }
        // Android 12+ would show "BirdoVPN pasted from your clipboard".
        verify(exactly = 0) { clipboard.primaryClip }
        verify(exactly = 3) { clipboard.primaryClipDescription }
    }

    @Test
    fun `focus inside the minute changes nothing`() {
        every { clipboard.primaryClipDescription } returns ours.description
        every { clipboard.primaryClip } returns ours
        copySensitiveToClipboard(context, LABEL, NUMBER)

        idleSeconds(30)
        SensitiveClipboard.onWindowFocusGained()
        verify(exactly = 0) { clipboard.clearPrimaryClip() }

        idleSeconds(30)
        verify(exactly = 1) { clipboard.clearPrimaryClip() }
    }

    @Test
    fun `the rule - only this copy is cleared, and only an unreadable clipboard waits`() {
        fun verdict(seen: Seen?, hasFocus: Boolean = false, text: CharSequence? = NUMBER, stamp: Long? = 7L) =
            SensitiveClipboard.verdict(LABEL, stamp, NUMBER, seen, hasFocus) { text }

        assertEquals(Verdict.CLEAR, verdict(Seen(LABEL, 7L)))
        assertEquals(Verdict.WAIT, verdict(null))
        // With focus, no description is an empty clipboard: nothing to clear.
        assertEquals(Verdict.LEAVE, verdict(null, hasFocus = true))
        assertEquals(Verdict.LEAVE, verdict(Seen("note", 7L)))
        assertEquals(Verdict.LEAVE, verdict(Seen(null, 7L)))
        // Copied again since, even the same text: a later copy (Desktop's sequence number).
        assertEquals(Verdict.LEAVE, verdict(Seen(LABEL, 8L)))
        assertEquals(Verdict.LEAVE, verdict(Seen(LABEL, 7L), text = "something else"))
        // No timestamp read at the copy: the label and the text decide.
        assertEquals(Verdict.CLEAR, verdict(Seen(LABEL, 8L), stamp = null))

        var read = false
        SensitiveClipboard.verdict(LABEL, 7L, NUMBER, Seen("note", 7L), hasFocus = true) { read = true; NUMBER }
        assertFalse("another app's clip was read", read)
    }

    private companion object {
        const val LABEL = "Account number"

        /** Obviously fake, low entropy. */
        const val NUMBER = "0000 0000 0000 0000 0000 0001"
    }
}
