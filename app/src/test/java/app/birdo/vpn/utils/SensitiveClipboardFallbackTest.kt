package app.birdo.vpn.utils

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.Futures
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * MR-2129 hardening: a clear that WorkManager could not schedule falls back to
 * this process's own timer, and leaves a breadcrumb that survives a release
 * build (which strips every Log call). WorkManager reports an enqueue that
 * fails on its own thread only through the returned Operation, so that is
 * where the failure must be heard, not just a throw from getInstance().
 *
 * SDK 34: Robolectric's SDK 35+ jars need Java 21, and CI runs 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensitiveClipboardFallbackTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        mockkObject(FaultReporter)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun idleSeconds(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun clipText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    /** The fallback's breadcrumb, matched on its words so this also runs against the old code. */
    private fun fallbackTrails(times: Int) =
        verify(exactly = times) { FaultReporter.trail(any(), match { "fell back to the in-process timer" in it }) }

    @Test
    fun `an enqueue that fails on WorkManager's thread falls back to the timer, and says so`() {
        val workManager = mockk<WorkManager>()
        val failed = mockk<Operation>()
        every { failed.result } returns Futures.immediateFailedFuture(IllegalStateException("work database unavailable"))
        every { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) } returns failed
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager

        copySensitiveToClipboard(context, LABEL, NUMBER)
        shadowOf(Looper.getMainLooper()).idle()

        idleSeconds(59)
        assertEquals(NUMBER, clipText())
        idleSeconds(1)
        assertFalse("a failed enqueue left the account number on the clipboard", clipboard.hasPrimaryClip())
        fallbackTrails(1)
    }

    @Test
    fun `no WorkManager at all falls back to the timer, and says so`() {
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } throws IllegalStateException("WorkManager is not initialized properly")

        copySensitiveToClipboard(context, LABEL, NUMBER)
        shadowOf(Looper.getMainLooper()).idle()

        idleSeconds(60)
        assertFalse(clipboard.hasPrimaryClip())
        // Release builds strip the Log.w: without this nothing would show.
        fallbackTrails(1)
    }

    @Test
    fun `a clear that is scheduled leaves no breadcrumb and no timer`() {
        copySensitiveToClipboard(context, LABEL, NUMBER)
        shadowOf(Looper.getMainLooper()).idle()

        // The work clears it (SensitiveClipboardTest); no timer of this process does.
        idleSeconds(60)
        assertEquals(NUMBER, clipText())
        fallbackTrails(0)
    }

    private companion object {
        const val LABEL = "Account number"

        /** Obviously fake, low entropy. */
        const val NUMBER = "0000 0000 0000 0000 0000 0001"
    }
}
