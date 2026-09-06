package app.birdo.vpn.utils

import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.protocol.Message
import java.util.concurrent.ConcurrentHashMap

/**
 * The reporting channel for failures on the VPN data plane.
 *
 * WHY THIS EXISTS — do not "simplify" it back into a `Log.e` call.
 *
 * `app/proguard-rules.pro` strips every `android.util.Log` call from release
 * builds via `-assumenosideeffects` (see the comment there; issue #357). That
 * is deliberate and must stay: nothing about a user's traffic belongs in
 * logcat. The consequence is that in the artifact users actually run, a
 * `Log.e` is not a quiet signal — it is *no* signal. A DEX walk of a shipped
 * release APK found ZERO surviving `android.util.Log` call sites in
 * `BirdoVpnService`, `WgNative`, `VpnManager`, `TunnelMonitor` or
 * `NativeLibraryVerifier`.
 *
 * At the same time those paths swallow their exceptions on purpose — a failing
 * `wgTurnOn` must surface as a `VpnState.Error` and not as a crash — so the
 * crash reporter never saw them either. Net effect: wg-go failing to load on a
 * whole class of devices, or the kill switch failing to arm (traffic in the
 * clear while the UI says "protected"), reached the operator only if a user
 * wrote in, and even then there was no way to tell one broken handset from a
 * fleet-wide regression.
 *
 * So: failures on the connect, kill-switch and native-integrity paths go
 * through [report], which logs (useful in a debug build, stripped in release)
 * *and* raises a Sentry event — the part that survives R8.
 *
 * PRIVACY. Sentry is configured in `BirdoApp.initSentry` with PII off, no
 * screenshots, no view hierarchy, no APM, and a `beforeSend`/`beforeBreadcrumb`
 * scrubber that redacts IPs, hostnames, emails, URLs and key material from the
 * event message, exception values, breadcrumbs, extras and tags. Everything
 * emitted here passes through that scrubber. Call sites must still pass short
 * structural messages — never a UAPI config string, an endpoint or a key. The
 * scrubber is the backstop, not the plan.
 *
 * QUOTA. The tunnel retries, and the socket-protect and stall monitors run in
 * polling loops, so an unthrottled capture inside one of those catch blocks
 * would emit an event every couple of seconds for as long as the fault lasts.
 * [report] therefore sends the FIRST occurrence of a given `code` immediately
 * and then at most one more per [THROTTLE_WINDOW_MS]. The window is
 * per-process: a restart re-reports, which is what you want — a restart is
 * itself a new data point.
 */
internal object FaultReporter {

    private const val TAG = "BirdoFault"

    /** Tunnel bring-up: engine load, interface establish, wgTurnOn. */
    const val PATH_CONNECT = "connect"

    /** The kill switch — a failure here means traffic is NOT blocked. */
    const val PATH_KILL_SWITCH = "kill-switch"

    /** Native-library / package-signature integrity verification. */
    const val PATH_INTEGRITY = "integrity"

    /** A live tunnel's socket protection, stats and teardown. */
    const val PATH_TUNNEL = "tunnel"

    /** One event per fault code immediately, then at most one per window. */
    private const val THROTTLE_WINDOW_MS = 5L * 60L * 1000L

    private const val NANOS_PER_MS = 1_000_000L

    /**
     * Last send time per fault code, in [System.nanoTime] units.
     *
     * nanoTime, not currentTimeMillis: this is an interval measurement and a
     * handset's wall clock moves (NITZ, NTP, the user). A backwards jump under
     * currentTimeMillis would mute the reporter for the size of the jump.
     */
    private val lastSentNanos = ConcurrentHashMap<String, Long>()

    /**
     * Report a data-plane failure.
     *
     * @param path    one of the `PATH_*` constants — the coarse area, sent as a
     *                Sentry tag so a fleet-wide regression is one filter away.
     * @param code    a stable, low-cardinality identifier for THIS failure,
     *                e.g. `wg_turn_on_failed`. It is both the throttle key and
     *                a Sentry tag, so it must be a literal: never interpolate a
     *                device- or session-specific value into it. Codes must also
     *                be unique across the app — two sites sharing a code share
     *                a throttle bucket and hide each other. Pinned by
     *                `DataplaneFaultReportingTest`.
     * @param message a short structural description. Must not contain an
     *                endpoint, an address or key material.
     * @param error   the throwable, when there is one. Passing it gives Sentry
     *                a stack to group on; without it the event groups on the
     *                message.
     */
    fun report(path: String, code: String, message: String, error: Throwable? = null) {
        // Kept for debug builds and `adb logcat` during development. R8 REMOVES
        // this line from the release artifact — it is not the signal, it is the
        // convenience. Everything below it is the signal.
        Log.e(TAG, "[$path/$code] $message", error)

        if (!shouldSend(code)) return

        // A reporter must never break the thing it reports on. This runs inside
        // catch blocks on the tunnel executor, and Sentry may be uninitialised
        // (initSentry returns early in debug builds and unit tests never call
        // it), shutting down, or out of quota.
        try {
            // SentryEvent + captureEvent rather than captureException/withScope:
            // it is the one shape that carries level, both tags, the detail and
            // the throwable in a single call with no scope juggling, and its
            // fields are exactly the ones BirdoApp's beforeSend scrubber walks.
            val event = if (error != null) SentryEvent(error) else SentryEvent()
            event.level = SentryLevel.ERROR
            event.setTag("birdo.path", path)
            event.setTag("birdo.fault", code)
            event.message = Message().apply { formatted = "[$path/$code] $message" }
            Sentry.captureEvent(event)
        } catch (_: Throwable) {
            // Deliberately swallowed and deliberately not logged: we are already
            // on a failure path and there is nowhere left to report to.
        }
    }

    /**
     * Leave a breadcrumb without raising an event.
     *
     * For transitions that are individually unremarkable (a connection timing
     * out, a network refusing the handshake) but are the context you want
     * attached to the event that IS raised. A breadcrumb costs a ring-buffer
     * slot and never a network request, so this is not throttled — and it means
     * a data-plane error state added in the future leaves a trace even if
     * whoever adds it never calls [report].
     */
    fun trail(path: String, message: String) {
        try {
            Sentry.addBreadcrumb(
                Breadcrumb().apply {
                    category = "birdo.$path"
                    this.message = message
                    level = SentryLevel.WARNING
                },
            )
        } catch (_: Throwable) {
            // See report(): the reporter never throws into the data plane.
        }
    }

    /**
     * First occurrence of [code] always sends; after that, once per window.
     *
     * `internal`, not `private`, only so `DataplaneFaultReportingTest` can pin
     * the behaviour: a throttle that silently degenerated into "send once, ever"
     * would turn a fleet-wide regression back into a single mystery event.
     */
    internal fun shouldSend(code: String): Boolean {
        val now = System.nanoTime()
        val windowNanos = THROTTLE_WINDOW_MS * NANOS_PER_MS
        while (true) {
            val previous = lastSentNanos[code]
            if (previous == null) {
                if (lastSentNanos.putIfAbsent(code, now) == null) return true
                continue
            }
            // Subtraction, not `<`: nanoTime is allowed to wrap, and the
            // difference stays correct across a wrap while a comparison does not.
            if (now - previous < windowNanos) return false
            if (lastSentNanos.replace(code, previous, now)) return true
        }
    }

    /** Test seam: forget every throttle window. Not called by shipped code. */
    internal fun resetThrottleForTest() = lastSentNanos.clear()
}
