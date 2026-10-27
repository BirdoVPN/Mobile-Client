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
    val cm = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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
    SensitiveClipboard.arm(cm, label, text)
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
 * Only when the app CAN see the clipboard, and it visibly holds something
 * else, is it left: the copy is gone already. The label is checked before the
 * text, so another app's clip is never read (Android 12+ would tell the user
 * "BirdoVPN pasted from your clipboard").
 *
 * The minute is the main looper's, which is awake time: a phone asleep inside
 * it finishes it once awake, before anyone can paste. The pending clear lives
 * in this process; a process killed inside the minute leaves the copy.
 *
 * Main thread only.
 */
internal object SensitiveClipboard {

    private const val TAG = "SensitiveClipboard"

    /** How long a copy stays on the clipboard: Desktop's and iOS's minute. */
    const val CLEAR_AFTER_MS = 60_000L

    enum class Verdict { CLEAR, LEAVE }

    private class Pending(val clipboard: ClipboardManager, val label: String, val text: String)

    /** The latest copy: each copy replaces the last, and only its minute counts. */
    private var pending: Pending? = null

    fun arm(clipboard: ClipboardManager, label: String, text: String) {
        val copy = Pending(clipboard, label, text)
        pending = copy
        Handler(Looper.getMainLooper()).postDelayed({
            // A later copy replaced this one and owes its own clear.
            if (pending === copy) {
                pending = null
                settle(copy)
            }
        }, CLEAR_AFTER_MS)
    }

    private fun settle(copy: Pending) {
        // A clipboard that throws is one this app cannot see: clear it.
        val seen = try {
            copy.clipboard.primaryClipDescription?.let { Seen(it.label) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Clipboard description unreadable", e)
            null
        }
        val verdict = verdict(copy.label, copy.text, seen) {
            try {
                copy.clipboard.primaryClip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text
            } catch (e: RuntimeException) {
                Log.w(TAG, "Clipboard text unreadable", e)
                null
            }
        }
        if (verdict == Verdict.LEAVE) return
        try {
            copy.clipboard.clearPrimaryClip()
        } catch (e: RuntimeException) {
            // A timer must never take the app down, least of all in the background.
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
