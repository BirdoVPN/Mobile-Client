package app.birdo.vpn.service

import app.birdo.vpn.data.model.AccountRestriction
import app.birdo.vpn.data.model.DeviceNotice
import app.birdo.vpn.data.repository.ApiErrorMapper
import java.time.Instant

/**
 * MR-2221 / MR-2220: the two objects a connect reply may carry beside its
 * message, read defensively. Nothing here throws, and a field that is absent,
 * malformed or not what this build expects is ignored, never shown half-read.
 */
internal object ConnectNotices {

    private const val SITE = "https://birdo.app"
    private const val MAX_NOTICE_LENGTH = 1000

    /**
     * When the restriction ends, in epoch milliseconds; 0 when `until` is
     * missing or is not an ISO-8601 instant (then nothing is held back and the
     * server decides on the next connect, as before).
     */
    fun restrictionEndMs(restriction: AccountRestriction?): Long =
        restriction?.until?.let { runCatching { Instant.parse(it.trim()).toEpochMilli() }.getOrNull() } ?: 0L

    /** A website path the app may open, or null: it must be a plain `/path` of this site. */
    fun sitePath(path: String?): String? {
        val p = path?.trim() ?: return null
        // Printable ASCII only, no space and no control character (Desktop's rule).
        if (p.any { it.code !in 0x21..0x7E }) return null
        if (!p.startsWith("/") || p.startsWith("//") || '\\' in p || p.length > 200) return null
        return p
    }

    /** The full URL of [path] on the site, or null when [path] is not a safe site path. */
    fun siteUrl(path: String?): String? = sitePath(path)?.let { SITE + it }

    /** The notice's sentence when it can be shown as it is, else null (never a partial). */
    fun noticeText(notice: DeviceNotice?): String? =
        notice?.message?.trim()?.takeIf { ApiErrorMapper.isPresentable(it, MAX_NOTICE_LENGTH) }
}
