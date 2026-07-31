package app.birdo.vpn.service

import android.util.Log

/**
 * Decides, quickly, whether a freshly-established WireGuard tunnel is actually
 * carrying traffic — or whether the network is silently eating it.
 *
 * WHY THIS EXISTS
 * ---------------
 * [BirdoVpnService] reports [app.birdo.vpn.data.model.VpnState.Connected] as
 * soon as the tun interface is up and wg-go has been configured. That is BEFORE
 * any handshake has completed. On an ordinary network the handshake lands
 * milliseconds later and nobody notices the gap. On a DPI-filtered network
 * (Iran, Russia, Bangladesh) the WireGuard handshake is dropped outright, so the
 * interface comes up, the UI says "Protected", and not one packet ever flows.
 *
 * That is the failure the session data shows: users connect once, see a green
 * screen that does nothing, and never open the app again.
 *
 * This probe closes that gap. It watches for the FIRST handshake and reports
 * whether one arrived inside [WINDOW_MS]. A miss is the signal that the
 * transport is being interfered with, which
 * [app.birdo.vpn.service.VpnManager] turns into an automatic retry over the
 * stealth transport.
 *
 * RELATIONSHIP TO [TunnelMonitor]
 * -------------------------------
 * [TunnelMonitor] watches a LIVE tunnel and declares it dead after
 * ~180s without a handshake. That threshold is correct for detecting a peer
 * that went away mid-session, and far too slow to drive a fallback: a user
 * staring at a dead "Protected" screen for three minutes has already given up.
 * This probe is the complementary short-window check that runs once, at
 * establish time, and then hands over to TunnelMonitor.
 *
 * TUNING
 * ------
 * WireGuard sends a handshake initiation immediately and retries every ~5s.
 * [WINDOW_MS] of 10s therefore spans TWO full attempts, which is enough to
 * separate "blocked" from "slow": a working handshake on a bad 2G link still
 * lands well inside one retry cycle, while a filtered network produces nothing
 * on either. Going shorter risks falling back on a merely-slow network and
 * paying the stealth throughput cost for no reason; going longer leaves the
 * user watching a dead screen.
 */
/**
 * @param isAlive     false once the tunnel this probe belongs to is gone.
 * @param canReadConfig whether wg-go exposes its UAPI config on this build.
 * @param readConfig  the raw wg-go UAPI dump, or null if unavailable.
 * @param sleep       injected so tests do not spend the real probe window.
 *
 * The last three default to the live [WgNative]/[Thread] implementations and
 * exist as parameters purely so the decision logic is testable without a JNI
 * tunnel — the branch that matters (BLOCKED vs HANDSHAKE_OK) decides whether a
 * user in Iran gets a working connection, and it must not be shipped unverified.
 */
class TransportProbe(
    private val handle: Int,
    private val isAlive: () -> Boolean,
    private val canReadConfig: () -> Boolean = { WgNative.canReadConfig() },
    private val readConfig: (Int) -> String? = { WgNative.getConfig(it) },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        private const val TAG = "TransportProbe"

        /**
         * How long to wait for the first handshake before declaring the
         * transport blocked. Spans two WireGuard handshake retries (~5s each).
         */
        const val WINDOW_MS = 10_000L

        /**
         * Poll cadence. Each tick is a wg-go getConfig JNI read, so this trades
         * detection latency against native calls. 500ms gives ~20 reads across
         * the window and detects a working handshake essentially as fast as the
         * user could perceive it.
         */
        const val POLL_INTERVAL_MS = 500L
    }

    /** The verdict for one probe run. */
    enum class Result {
        /** A handshake completed — the fast path works, do nothing. */
        HANDSHAKE_OK,

        /**
         * No handshake inside the window. The peer is unreachable or the
         * transport is being filtered. Caller should try the stealth transport.
         */
        BLOCKED,

        /**
         * The tunnel went away (user disconnect, server switch, kill switch)
         * while probing. NOT a transport verdict — the caller must not trigger a
         * fallback off this, or a user tapping Disconnect during the probe would
         * be answered with a surprise reconnect.
         */
        ABORTED,
    }

    /**
     * Block until a handshake lands, the window expires, or the tunnel goes
     * away. Runs on the caller's thread — call it from a background context.
     */
    fun await(): Result {
        val deadline = System.currentTimeMillis() + WINDOW_MS

        // A build without config-read support cannot be probed. Report
        // HANDSHAKE_OK rather than BLOCKED: with no evidence of failure we must
        // not trigger a fallback, because a false BLOCKED costs every user on
        // that build an unnecessary reconnect onto the slower transport.
        if (!canReadConfig()) {
            Log.i(TAG, "wg config unreadable — skipping transport probe")
            return Result.HANDSHAKE_OK
        }

        while (System.currentTimeMillis() < deadline) {
            if (!isAlive()) return Result.ABORTED
            if (hasHandshake()) {
                Log.i(TAG, "Handshake confirmed — transport is working")
                return Result.HANDSHAKE_OK
            }
            try {
                sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.ABORTED
            }
        }

        // Re-check liveness before condemning the transport: the tunnel may have
        // been torn down in the final sleep, and reporting BLOCKED for a tunnel
        // the user deliberately stopped would trigger an unwanted reconnect.
        if (!isAlive()) return Result.ABORTED

        // One last read. A handshake landing in the final poll gap is a working
        // transport, and treating it as blocked would push a perfectly good
        // connection onto the slow path.
        if (hasHandshake()) return Result.HANDSHAKE_OK

        Log.w(
            TAG,
            "No WireGuard handshake in ${WINDOW_MS}ms — transport appears blocked or filtered",
        )
        return Result.BLOCKED
    }

    /**
     * True once any peer reports a non-zero `last_handshake_time_sec`.
     *
     * Deliberately checks only for PRESENCE, not recency: this runs at establish
     * time, so any non-zero value was necessarily set by this tunnel — wg-go
     * reports 0 for a peer that has never completed one.
     */
    private fun hasHandshake(): Boolean {
        val cfg = readConfig(handle) ?: return false
        return cfg.lineSequence().any { line ->
            line.startsWith("last_handshake_time_sec=") &&
                (line.substringAfter('=').toLongOrNull() ?: 0L) > 0L
        }
    }
}
