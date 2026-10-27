package app.birdo.vpn.utils

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * MR-2129 (AUD-MOB-13): a copied account number stayed on the clipboard until
 * something replaced it, where iOS and Desktop clear it after a minute.
 *
 * The clear is WorkManager work, so that a frozen or killed process still
 * gets it (Android 14+ freezes a cached app seconds after it leaves the
 * screen); WorkManager's TestDriver stands in for the minute passing.
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

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun clipText(): String? = clipboard.primaryClip?.getItemAt(0)?.text?.toString()

    /** The clear waiting for its minute; there must be exactly one. */
    private fun pendingClear(): WorkInfo {
        val pending = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CLEAR_WORK).get()
            .filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals("no clear is scheduled for the copy", 1, pending.size)
        return pending.single()
    }

    /** The copy's minute is over. */
    private fun minutePasses(work: WorkInfo) =
        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(work.id)

    @Test
    fun `the copy is cleared when its minute is over, and not before`() {
        copySensitiveToClipboard(context, LABEL, NUMBER)
        val clear = pendingClear()
        assertEquals(NUMBER, clipText())

        minutePasses(clear)

        assertFalse("the account number is still on the clipboard after a minute", clipboard.hasPrimaryClip())
    }

    @Test
    fun `a second copy replaces the first copy's clear and gets its own full minute`() {
        copySensitiveToClipboard(context, LABEL, NUMBER)
        val first = pendingClear()
        copySensitiveToClipboard(context, LABEL, NUMBER)
        val second = pendingClear()

        // REPLACE cancels the first copy's clear (and WorkManager drops its record).
        assertNotEquals(first.id, second.id)
        val firstNow: WorkInfo? = WorkManager.getInstance(context).getWorkInfoById(first.id).get()
        assertTrue(
            "the first copy's clear is still scheduled",
            firstNow == null || firstNow.state == WorkInfo.State.CANCELLED,
        )
        assertEquals(NUMBER, clipText())

        minutePasses(second)
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test
    fun `with the clipboard in view, a clip the user copied since is left - the copy is gone already`() {
        copySensitiveToClipboard(context, LABEL, NUMBER)
        val clear = pendingClear()
        clipboard.setPrimaryClip(ClipData.newPlainText("note", USERS_OWN))

        minutePasses(clear)

        // Only here: unseen, in the background, it is cleared (owner decision 2026-10-27).
        assertEquals(USERS_OWN, clipText())
    }

    @Test
    fun `the copy is still flagged sensitive`() {
        copySensitiveToClipboard(context, LABEL, NUMBER)

        val extras = clipboard.primaryClipDescription?.extras
        assertTrue(extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
    }

    private companion object {
        /** SensitiveClipboard.WORK_NAME, spelled out so this runs against the old code too. */
        const val CLEAR_WORK = "sensitive-clipboard-clear"
        const val LABEL = "Account number"

        /** Obviously fake, low entropy. */
        const val NUMBER = "0000 0000 0000 0000 0000 0001"
        const val USERS_OWN = "the user's own text"
    }
}
