package app.birdo.vpn.utils

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
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
 */
fun copySensitiveToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
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
}
