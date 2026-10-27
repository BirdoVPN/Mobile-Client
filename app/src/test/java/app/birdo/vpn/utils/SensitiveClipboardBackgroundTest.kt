package app.birdo.vpn.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * MR-2129, owner decision 2026-10-27: the copy is wiped a minute later even
 * with the app in the background, where Android 10+ lets it read nothing on
 * the clipboard (no description, no clip). Robolectric's clipboard does not
 * model that, so these drive a mocked one that answers null, as a background
 * app is answered.
 *
 * SDK 34: Robolectric's SDK 35+ jars need Java 21, and CI runs 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensitiveClipboardBackgroundTest {

    private val clipboard = mockk<ClipboardManager>(relaxed = true).also {
        every { it.primaryClipDescription } returns null
        every { it.primaryClip } returns null
    }
    private val context = mockk<Context>().also {
        every { it.applicationContext } returns it
        every { it.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard
    }

    @After
    fun tearDown() = unmockkAll()

    private fun idleSeconds(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    @Test
    fun `a minute that ends in the background clears the clipboard unseen`() {
        copySensitiveToClipboard(context, LABEL, NUMBER)

        idleSeconds(59)
        verify(exactly = 0) { clipboard.clearPrimaryClip() }

        idleSeconds(1)
        verify(exactly = 1) { clipboard.clearPrimaryClip() }
        // Nothing was read to decide it.
        verify(exactly = 0) { clipboard.primaryClip }
    }

    @Test
    fun `in the background the latest copy's minute is the one that counts`() {
        copySensitiveToClipboard(context, LABEL, NUMBER)
        idleSeconds(30)
        copySensitiveToClipboard(context, LABEL, NUMBER)

        idleSeconds(30)
        verify(exactly = 0) { clipboard.clearPrimaryClip() }

        idleSeconds(30)
        verify(exactly = 1) { clipboard.clearPrimaryClip() }
    }

    @Test
    fun `a clipboard that throws is still cleared, and a refused clear does not crash the app`() {
        every { clipboard.primaryClipDescription } throws SecurityException("denied")
        every { clipboard.clearPrimaryClip() } throws SecurityException("denied")
        copySensitiveToClipboard(context, LABEL, NUMBER)

        // Throws out of the looper if the timer lets the exception escape.
        idleSeconds(60)

        verify(exactly = 1) { clipboard.clearPrimaryClip() }
    }

    @Test
    fun `a clip seen to be another app's is left, and its text is never read`() {
        every { clipboard.primaryClipDescription } returns ClipData.newPlainText("note", "x").description
        copySensitiveToClipboard(context, LABEL, NUMBER)

        idleSeconds(60)

        verify(exactly = 0) { clipboard.clearPrimaryClip() }
        verify(exactly = 0) { clipboard.primaryClip }
    }

    private companion object {
        const val LABEL = "Account number"

        /** Obviously fake, low entropy. */
        const val NUMBER = "0000 0000 0000 0000 0000 0001"
    }
}
