package app.birdo.vpn.utils

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Copies [text] to the system clipboard flagged as SENSITIVE, so Android 13+
 * suppresses the clipboard-preview overlay and keyboards/system UI treat it
 * like a password. Pre-33 devices honour the same extra where the OEM keyboard
 * supports it (the string key is identical); worst case the copy behaves
 * exactly as the old Compose `ClipboardManager.setText` did.
 *
 * Used for the anonymous-account number — the sole credential for anonymous
 * accounts — which must never be shown in the clipboard-preview overlay that
 * renders OUTSIDE this app's FLAG_SECURE window.
 *
 * MR-2129: the clipboard is also cleared [SensitiveClipboard.CLEAR_AFTER_MS]
 * later, as iOS (an expiring pasteboard item) and Desktop (MR-1723) do. Main
 * thread (a click handler).
 */
fun copySensitiveToClipboard(context: Context, label: String, text: String) {
    // The application's clipboard: the pending clear outlives the screen, and
    // one taken from an Activity would hold on to the Activity.
    val app = context.applicationContext
    val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        } else {
            // Same key ClipDescription.EXTRA_IS_SENSITIVE resolves to on 33+.
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    cm.setPrimaryClip(clip)
    SensitiveClipboard.arm(app, cm, label, text)
}

/**
 * The clear a sensitive copy is owed (MR-2129, AUD-MOB-13).
 *
 * Owner decision 2026-10-27: the copy is wiped a minute later ALWAYS, with the
 * app in the background too, accepting that a clip the user copied elsewhere
 * inside that minute is wiped with it. Android 10+ lets an app read the
 * clipboard only while it has input focus, so in the background the clear
 * cannot see what is there and clears it unseen. Writing, clearing included,
 * needs no focus.
 *
 * The clear is WorkManager work ([ClearClipboardWorker]), not a timer of this
 * process: Android 14+ freezes a cached app seconds after it leaves the
 * screen, and a frozen process's timer does not fire until the app runs
 * again. Typically that is a copy on the sign-up screen, then straight off to
 * a password manager. The work is a job the SYSTEM runs: about
 * [CLEAR_AFTER_MS] after the copy, with this process frozen or killed (it is
 * started for it). While the device dozes the system may hold it back. The
 * phone is then idle and nothing is pasted; the work runs when it wakes.
 * Each copy REPLACEs the last copy's work ([WORK_NAME]), so the latest copy's
 * minute is the one that counts.
 *
 * The work carries NO data. The copied text stays in this process's memory
 * only, for the foreground check, and never reaches WorkManager's database.
 *
 * Only when the app CAN see the clipboard, and it visibly holds something
 * else, is it left: the copy is gone already. The label is checked before the
 * text, so another app's clip is never read (Android 12+ would tell the user
 * "BirdoVPN pasted from your clipboard"). In a process started for the work,
 * the copy is not known, and the clipboard is cleared unseen as well.
 */
internal object SensitiveClipboard {

    private const val TAG = "SensitiveClipboard"

    /** How long a copy stays on the clipboard: Desktop's and iOS's minute. */
    const val CLEAR_AFTER_MS = 60_000L

    /** The clear's unique work: one at a time, the latest copy's. */
    const val WORK_NAME = "sensitive-clipboard-clear"

    enum class Verdict { CLEAR, LEAVE }

    private class Copy(val clipboard: ClipboardManager, val label: String, val text: String)

    /** The latest copy, in this process only; taken by the clear that runs for it. */
    private val latest = AtomicReference<Copy?>(null)

    fun arm(context: Context, clipboard: ClipboardManager, label: String, text: String) {
        val copy = Copy(clipboard, label, text)
        latest.set(copy)
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, clearRequest())
        } catch (e: RuntimeException) {
            // WorkManager not initialised (it is, by androidx.startup, in the
            // app's process): fall back to this process's own timer, which a
            // frozen process runs late. The main looper counts awake time:
            // deep sleep pauses it and the remaining seconds run after wake.
            Log.w(TAG, "Clipboard clear not scheduled; using the in-process timer", e)
            Handler(Looper.getMainLooper()).postDelayed({
                if (latest.get() === copy) clear(context)
            }, CLEAR_AFTER_MS)
        }
    }

    /** The clear's work: a delay and nothing else, no input data. */
    fun clearRequest(): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(ClearClipboardWorker::class.java)
            .setInitialDelay(CLEAR_AFTER_MS, TimeUnit.MILLISECONDS)
            .build()

    /** The minute is over. Any thread; never throws. */
    fun clear(context: Context) {
        val copy = latest.getAndSet(null)
        val clipboard = copy?.clipboard ?: try {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        } catch (e: RuntimeException) {
            Log.w(TAG, "Clipboard unavailable", e)
            return
        }
        if (copy != null) {
            // A clipboard that throws is one this app cannot see: clear it.
            val seen = try {
                clipboard.primaryClipDescription?.let { Seen(it.label) }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Clipboard description unreadable", e)
                null
            }
            val verdict = verdict(copy.label, copy.text, seen) {
                try {
                    clipboard.primaryClip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Clipboard text unreadable", e)
                    null
                }
            }
            if (verdict == Verdict.LEAVE) return
        }
        try {
            clipboard.clearPrimaryClip()
        } catch (e: RuntimeException) {
            // Neither the work nor a timer may take the app down.
            Log.w(TAG, "Clipboard clear refused", e)
        }
    }

    /** The clipboard's label, as far as the app can read it. */
    data class Seen(val label: CharSequence?)

    /**
     * What the clear does with the clipboard it finds. [seen] is null when
     * there is no description to read: an empty clipboard, or one the app may
     * not read without focus. Either way it is cleared. A clipboard that
     * visibly holds another clip, by its label or then its text, is left.
     */
    fun verdict(label: String, text: String, seen: Seen?, seenText: () -> CharSequence?): Verdict {
        if (seen == null) return Verdict.CLEAR
        if (seen.label?.toString() != label) return Verdict.LEAVE
        // Our label with text it cannot read: it may be ours, and the minute is over.
        val current = seenText() ?: return Verdict.CLEAR
        return if (current.toString() == text) Verdict.CLEAR else Verdict.LEAVE
    }
}

/**
 * Runs [SensitiveClipboard.clear] when the copy's minute is over (MR-2129).
 * It needs nothing injected: in a process started only for it, the
 * Application has run its normal start-up (as for a widget refresh or the
 * tile) and this reads nothing but the clipboard.
 */
class ClearClipboardWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        SensitiveClipboard.clear(applicationContext)
        return Result.success()
    }
}
