package app.birdo.vpn.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * MR-2129, owner decision 2026-10-27: the copy is wiped a minute later even
 * with the app in the background, where Android 10+ lets it read nothing on
 * the clipboard (no description, no clip). Robolectric's clipboard does not
 * model that, so these copy to a mocked one that answers null, as a
 * background app is answered. The clear runs as WorkManager work (TestDriver).
 *
 * SDK 34: Robolectric's SDK 35+ jars need Java 21, and CI runs 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensitiveClipboardBackgroundTest {

    private val app: Context = RuntimeEnvironment.getApplication()
    private val clipboard = mockk<ClipboardManager>(relaxed = true).also {
        every { it.primaryClipDescription } returns null
        every { it.primaryClip } returns null
    }
    private val context = mockk<Context>().also {
        every { it.applicationContext } returns it
        every { it.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() = unmockkAll()

    /** The clear waiting for its minute; there must be exactly one. */
    private fun pendingClear(): WorkInfo {
        val pending = WorkManager.getInstance(app).getWorkInfosForUniqueWork(CLEAR_WORK).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals("no clear is scheduled for the copy", 1, pending.size)
        return pending.single()
    }

    private fun minutePasses(work: WorkInfo) =
        WorkManagerTestInitHelper.getTestDriver(app)!!.setInitialDelayMet(work.id)

    @Test
    fun `a minute that ends in the background clears the clipboard unseen`() {
        copySensitiveToClipboard(context, LABEL, NUMBER)
        val clear = pendingClear()
        verify(exactly = 0) { clipboard.clearPrimaryClip() }

        minutePasses(clear)

        verify(exactly = 1) { clipboard.clearPrimaryClip() }
        // Nothing was read to decide it.
        verify(exactly = 0) { clipboard.primaryClip }
    }

    @Test
    fun `a clipboard that throws is still cleared, and a refused clear fails nothing`() {
        every { clipboard.primaryClipDescription } throws SecurityException("denied")
        every { clipboard.clearPrimaryClip() } throws SecurityException("denied")
        copySensitiveToClipboard(context, LABEL, NUMBER)
        val clear = pendingClear()

        minutePasses(clear)

        verify(exactly = 1) { clipboard.clearPrimaryClip() }
        val done = WorkManager.getInstance(app).getWorkInfoById(clear.id).get()
        assertEquals(WorkInfo.State.SUCCEEDED, done.state)
    }

    @Test
    fun `a clip seen to be another app's is left, and its text is never read`() {
        every { clipboard.primaryClipDescription } returns ClipData.newPlainText("note", "x").description
        copySensitiveToClipboard(context, LABEL, NUMBER)

        minutePasses(pendingClear())

        verify(exactly = 0) { clipboard.clearPrimaryClip() }
        verify(exactly = 0) { clipboard.primaryClip }
    }

    private companion object {
        /** SensitiveClipboard.WORK_NAME, spelled out so this runs against the old code too. */
        const val CLEAR_WORK = "sensitive-clipboard-clear"
        const val LABEL = "Account number"

        /** Obviously fake, low entropy. */
        const val NUMBER = "0000 0000 0000 0000 0000 0001"
    }
}
