package app.birdo.vpn.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * MR-2129: the clear's work itself. It is written to WorkManager's database,
 * so it must carry nothing of the clip, and it may run in a process started
 * only for it, which knows nothing of the copy.
 *
 * SDK 34: Robolectric's SDK 35+ jars need Java 21, and CI runs 17.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SensitiveClipboardWorkTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Test
    fun `the work waits a minute and carries nothing of the clip`() {
        val spec = SensitiveClipboard.clearRequest().workSpec

        assertEquals(SensitiveClipboard.CLEAR_AFTER_MS, spec.initialDelay)
        assertEquals(Data.EMPTY, spec.input)
        assertEquals(ClearClipboardWorker::class.java.name, spec.workerClassName)
    }

    @Test
    fun `in a process started for the work, the clipboard is cleared unseen and the work succeeds`() {
        // Nothing of an earlier copy left in memory, as in a new process.
        SensitiveClipboard.clear(context)
        // Readable here, and not the copy: a fresh process cannot tell.
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "the user's own text"))
        val worker = TestWorkerBuilder.from(context, ClearClipboardWorker::class.java, Executor { it.run() }).build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun `the worker is constructible the way WorkManager builds it`() {
        // WorkManager instantiates workers by reflection on (Context, WorkerParameters).
        val constructor = ClearClipboardWorker::class.java.getConstructor(
            Context::class.java,
            androidx.work.WorkerParameters::class.java,
        )
        assertTrue(java.lang.reflect.Modifier.isPublic(constructor.modifiers))
    }
}
