package app.birdo.vpn.shared.util

import app.birdo.vpn.shared.currentTimeMillis
import kotlin.math.pow
import kotlin.math.round

/**
 * Cross-platform formatting utilities for bytes and durations.
 */
object FormatUtils {

    /** Format a byte count into a human-readable string (e.g. "1.2 KB", "45.3 MB"). */
    fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> formatDecimal(bytes / 1024.0, 1) + " KB"
        bytes < 1024L * 1024 * 1024 -> formatDecimal(bytes / (1024.0 * 1024), 1) + " MB"
        else -> formatDecimal(bytes / (1024.0 * 1024 * 1024), 2) + " GB"
    }

    /**
     * Format elapsed time since [sinceMillis] as "HH:MM:SS" or "MM:SS".
     * Returns "00:00" if [sinceMillis] is <= 0.
     */
    fun formatDuration(sinceMillis: Long): String {
        if (sinceMillis <= 0) return "00:00"
        val elapsed = (currentTimeMillis() - sinceMillis) / 1000
        val h = elapsed / 3600
        val m = (elapsed % 3600) / 60
        val s = elapsed % 60
        return if (h > 0) "${h}:${m.pad()}:${s.pad()}" else "${m.pad()}:${s.pad()}"
    }

    /** Format a duration from total elapsed seconds. */
    fun formatElapsedSeconds(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "${h}:${m.pad()}:${s.pad()}" else "${m.pad()}:${s.pad()}"
    }

    // ── Private helpers ──────────────────────────────────────────

    private fun Long.pad(): String = toString().padStart(2, '0')

    /** Platform-independent decimal formatting (no String.format dependency). */
    private fun formatDecimal(value: Double, decimals: Int): String {
        val factor = 10.0.pow(decimals)
        val rounded = round(value * factor) / factor
        val str = rounded.toString()
        val dot = str.indexOf('.')
        if (dot == -1) return str + "." + "0".repeat(decimals)
        val fracLen = str.length - dot - 1
        return if (fracLen >= decimals) str.substring(0, dot + decimals + 1)
        else str + "0".repeat(decimals - fracLen)
    }
}
