package app.birdo.vpn.utils

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * MR-2129 (AUD-MOB-13): a copied account number stayed on the clipboard until
 * something replaced it, where iOS and Desktop clear it after a minute. Run on
 * Robolectric's clipboard and main looper, so the minute is the real delay.
 * Robolectric's clipboard is always readable, as the app's is while it has
 * focus; the background, where nothing is readable, is
 * SensitiveClipboardBackgroundTest.
 *
 * SDK 34: Robolectric's SDK 35+ jars need Java 21, and CI runs 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensitiveClipboardTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun idleSeconds(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun clipText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    @Test
    fun `the copy is cleared a minute later, and not before`() {
        copySensitiveToClipboard(context, LABEL, NUMBER)

        idleSeconds(59)
        assertEquals(NUMBER, clipText())

        idleSeconds(1)
        assertFalse("the account number is still on the clipboard after a minute", clipboard.hasPrimaryClip())
    }

    @Test
    fun `with the clipboard in view, a clip the user copied since is left - the copy is gone already`() {
        copySensitiveToClipboard(context, LABEL, NUMBER)
        idleSeconds(10)
        clipboard.setPrimaryClip(ClipData.newPlainText("note", USERS_OWN))

        idleSeconds(60)

        // Only here: unseen, in the background, it is cleared (owner decision 2026-10-27).
        assertEquals(USERS_OWN, clipText())
    }

    @Test
    fun `a second copy gets its own full minute`() {
        copySensitiveToClipboard(context, LABEL, NUMBER)
        idleSeconds(30)
        copySensitiveToClipboard(context, LABEL, NUMBER)

        idleSeconds(30)
        assertEquals("the first copy's clear took the second copy early", NUMBER, clipText())

        idleSeconds(30)
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun `the copy is still flagged sensitive`() {
        copySensitiveToClipboard(context, LABEL, NUMBER)

        val extras = clipboard.primaryClipDescription?.extras
        assertTrue(extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
    }

    private companion object {
        const val LABEL = "Account number"

        /** Obviously fake, low entropy. */
        const val NUMBER = "0000 0000 0000 0000 0000 0001"
        const val USERS_OWN = "the user's own text"
    }
}
