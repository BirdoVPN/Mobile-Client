package app.birdo.vpn.ui.screen

import app.birdo.vpn.data.model.TermsNotice
import app.birdo.vpn.service.ConnectNotices
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.OffsetDateTime

/**
 * MR-2199 (birdo-web #808): which of the profile's terms-change notices Home
 * shows, as pure functions. The web banner's rule, and Desktop's (#393): one
 * line per entry in the server's order, `text` exactly as sent, gone once the
 * time reaches `effectiveAt` with the app left open and no new fetch.
 *
 * The iOS and macOS twin is `TermsNotice` in AuthWire.swift.
 */
internal object TermsNotices {

    /**
     * The longest [inForceOver] waits before reading the clock again. `delay`
     * does not count while the device sleeps, so one wait set for the date
     * would leave a notice up after it has ended.
     */
    const val TICK_MS = 60_000L

    /** Stricter than the parser alone: a full timestamp with a zone, which is what `toISOString()` sends. */
    private val ISO_INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})""")

    /** When [notice] ends, in epoch milliseconds, or null for a date this cannot read. */
    fun endMs(notice: TermsNotice): Long? =
        notice.effectiveAt.takeIf(ISO_INSTANT::matches)?.let {
            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
        }

    /** The entries to show at [nowMs], in the order sent. A bad date or a blank text is not shown. */
    fun inForce(notices: List<TermsNotice>, nowMs: Long): List<TermsNotice> = notices.filter { notice ->
        notice.text.isNotBlank() && (endMs(notice) ?: Long.MIN_VALUE) > nowMs
    }

    /** How long to wait before the next look at the clock; null when no notice is left to end. */
    fun nextCheckMs(notices: List<TermsNotice>, nowMs: Long): Long? =
        inForce(notices, nowMs).mapNotNull(::endMs).minOrNull()?.let { minOf(it - nowMs, TICK_MS) }

    /** [inForce] as the clock moves, ending when the last notice has. */
    fun inForceOver(notices: List<TermsNotice>, clockMs: () -> Long = System::currentTimeMillis): Flow<List<TermsNotice>> =
        flow {
            while (true) {
                val now = clockMs()
                emit(inForce(notices, now))
                delay(nextCheckMs(notices, now) ?: break)
            }
        }

    /** The site page behind "See every change", or null, which shows the text with no link. */
    fun link(notice: TermsNotice): String? = ConnectNotices.siteUrl(notice.path)
}
