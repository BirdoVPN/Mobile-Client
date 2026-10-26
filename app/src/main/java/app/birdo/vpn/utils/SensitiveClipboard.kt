package app.birdo.vpn.utils

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

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
 * MR-2129: the copy is also cleared [SensitiveClipboard.CLEAR_AFTER_MS] later,
 * as iOS (an expiring pasteboard item) and Desktop (MR-1723) do, and only if
 * the clipboard still holds exactly this copy. Main thread (a click handler).
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
 * Nothing the user copied since is ever wiped. The clipboard's description is
 * read first: its label and the timestamp the system stamped on this copy say
 * whether the clip is still it, so another app's clip is never read (Android
 * 12+ would tell the user "BirdoVPN pasted from your clipboard"). The text is
 * compared only after that.
 *
 * Android 10+ lets an app read the clipboard only while it has input focus. A
 * minute that ends with the app in the background therefore cannot tell this
 * copy from a later one, and the clear WAITs rather than wipe blindly: it runs
 * the moment the app has focus again ([onWindowFocusGained], from
 * MainActivity). The pending clear lives in this process; a process that dies
 * inside the minute leaves the copy, as before.
 *
 * Main thread only.
 */
internal object SensitiveClipboard {

    /** How long a copy stays on the clipboard: Desktop's and iOS's minute. */
    const val CLEAR_AFTER_MS = 60_000L

    enum class Verdict { CLEAR, LEAVE, WAIT }

    /** A clipboard description as the clear reads it. */
    data class Seen(val label: CharSequence?, val stamp: Long)

    private class Pending(
        val clipboard: ClipboardManager,
        val label: String,
        val text: String,
        /** The system's timestamp on this copy; null if it could not be read. */
        val stamp: Long?,
    ) {
        /** The minute is over; the clear waits only for a readable clipboard. */
        var due = false
    }

    /** The latest copy: each copy replaces the last, so only it can still be there. */
    private var pending: Pending? = null

    fun arm(clipboard: ClipboardManager, label: String, text: String) {
        val copy = Pending(clipboard, label, text, clipboard.primaryClipDescription?.timestamp)
        pending = copy
        Handler(Looper.getMainLooper()).postDelayed({
            // A later copy replaced this one and owes its own clear.
            if (pending === copy) {
                copy.due = true
                settle(copy, hasFocus = false)
            }
        }, CLEAR_AFTER_MS)
    }

    /** The app regained input focus: a clear that fell due while it could not read the clipboard runs now. */
    fun onWindowFocusGained() {
        pending?.takeIf { it.due }?.let { settle(it, hasFocus = true) }
    }

    private fun settle(copy: Pending, hasFocus: Boolean) {
        val seen = copy.clipboard.primaryClipDescription?.let { Seen(it.label, it.timestamp) }
        val verdict = verdict(copy.label, copy.stamp, copy.text, seen, hasFocus) {
            copy.clipboard.primaryClip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text
        }
        when (verdict) {
            Verdict.CLEAR -> {
                copy.clipboard.clearPrimaryClip()
                pending = null
            }
            Verdict.LEAVE -> pending = null
            Verdict.WAIT -> Unit
        }
    }

    /**
     * What the clear does with the clipboard it finds. No description is an
     * empty clipboard, or, unless the app is known to have focus, one it may
     * not read: then it waits. A different label or timestamp is a later copy,
     * left alone without reading its text.
     */
    fun verdict(
        label: String,
        stamp: Long?,
        text: String,
        seen: Seen?,
        hasFocus: Boolean,
        seenText: () -> CharSequence?,
    ): Verdict = when {
        seen == null -> if (hasFocus) Verdict.LEAVE else Verdict.WAIT
        seen.label?.toString() != label -> Verdict.LEAVE
        stamp != null && seen.stamp != stamp -> Verdict.LEAVE
        seenText()?.toString() == text -> Verdict.CLEAR
        else -> Verdict.LEAVE
    }
}
