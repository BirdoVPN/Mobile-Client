package app.birdo.vpn.utils

import app.birdo.vpn.utils.SensitiveClipboard.Seen
import app.birdo.vpn.utils.SensitiveClipboard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * MR-2129: the rule the minute's end applies (owner decision 2026-10-27).
 * Whatever the app cannot see is cleared; only a clipboard it can see holding
 * another clip is left, and that clip's text is never read for it.
 */
class SensitiveClipboardVerdictTest {

    private fun verdict(seen: Seen?, text: CharSequence? = NUMBER) =
        SensitiveClipboard.verdict(LABEL, NUMBER, seen) { text }

    @Test
    fun `whatever the app cannot see is cleared`() {
        // No description: empty, or unreadable without focus (Android 10+).
        assertEquals(Verdict.CLEAR, verdict(null, text = null))
        // Our label with text it cannot read.
        assertEquals(Verdict.CLEAR, verdict(Seen(LABEL), text = null))
    }

    @Test
    fun `our copy is cleared, and a clip seen to be another is left`() {
        assertEquals(Verdict.CLEAR, verdict(Seen(LABEL)))
        assertEquals(Verdict.LEAVE, verdict(Seen("note")))
        assertEquals(Verdict.LEAVE, verdict(Seen(null)))
        assertEquals(Verdict.LEAVE, verdict(Seen(LABEL), text = "something else"))
    }

    @Test
    fun `another app's clip is never read`() {
        var read = false
        SensitiveClipboard.verdict(LABEL, NUMBER, Seen("note")) { read = true; NUMBER }
        // Android 12+ would show "BirdoVPN pasted from your clipboard".
        assertFalse(read)
    }

    private companion object {
        const val LABEL = "Account number"

        /** Obviously fake, low entropy. */
        const val NUMBER = "0000 0000 0000 0000 0000 0001"
    }
}
