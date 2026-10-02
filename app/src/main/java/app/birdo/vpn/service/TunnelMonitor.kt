package app.birdo.vpn.service

import android.os.SystemClock
import android.util.Log
import app.birdo.vpn.utils.FaultReporter

/**
 * Watches a live wg-go tunnel and decides when it is dead.
 *
 * WHY IT HAD TO GET FASTER. The old check was one rule — the last handshake is
 * older than 180 s — read every 30 s. Live on the emulator (2026-09-30) that
 * meant "Protected" for 2m45s–3m20s after the network died, with every packet
 * the user sent going nowhere. Windows had the same problem live and is fixed
 * the same way. Now three rules, read every [CHECK_INTERVAL_MS]:
 *
 *  1. UNANSWERED — the fast rule. WireGuard answers every DATA packet: a
 *     healthy peer that has nothing to send back sends a keepalive within
 *     10 s (KEEPALIVE_TIMEOUT), and when nothing at all comes back for 15 s
 *     after a send the client starts a new handshake (KEEPALIVE_TIMEOUT +
 *     REKEY_TIMEOUT), retried every 5 s. So "we sent more than keepalives and
 *     have received nothing for [FAST_DEAD_MS]" means a handshake has been
 *     attempted and has not completed: the tunnel is dead. Our own persistent
 *     keepalives are NOT answered by WireGuard, so they never count. Since D-6
 *     the heartbeat itself rides the tunnel, so even an idle phone sends data
 *     at least once a minute and is judged within about a minute and a half.
 *  2. NO_NETWORK — no physical network for [NO_NETWORK_DEAD_MS] (airplane
 *     mode, a dead zone). wg-go cannot even send then, so rule 1 never sees
 *     traffic; the network callbacks know at once.
 *  3. STALLED — the old handshake-age rule, kept as the idle backstop. The
 *     keepalive ceiling keeps a healthy idle tunnel's handshake age below it
 *     (A1-038, WireGuardConfigBuilder.MAX_KEEPALIVE_SEC).
 *
 * Phase A's suspend guard stays in front of all three: Thread.sleep counts
 * awake time only, so the first check after a long sleep sees counters and a
 * handshake age that are stale only because the CPU was off (A1-017).
 *
 * It no longer protects sockets (A1-037): that happens once, right after
 * wgTurnOn (BirdoVpnService.protectTunnelSockets).
 *
 * @param handle            The wg-go tunnel handle returned by [WgNative.turnOn]
 * @param keepaliveSec      The persistent keepalive wg-go runs with
 * @param underlyingMissingSince elapsedRealtime when the last physical network
 *   went away, or 0 while one exists
 * @param isAlive           Returns `true` while the tunnel should be monitored
 * @param onUnexpectedExit  Called when the monitor decides the tunnel is dead;
 *   the argument is true when the tunnel never completed a handshake at all
 * @param elapsedRealtime   Monotonic clock that INCLUDES deep sleep
 * @param uptime            Monotonic clock that EXCLUDES deep sleep
 */
class TunnelMonitor(
    private val handle: Int,
    private val keepaliveSec: Int,
    private val underlyingMissingSince: () -> Long,
    private val isAlive: () -> Boolean,
    private val onUnexpectedExit: (neverHandshook: Boolean) -> Unit,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val uptime: () -> Long = { SystemClock.uptimeMillis() },
) {
    /** What one check concluded; see [assess]. */
    enum class Verdict { ALIVE, SKIPPED_AFTER_SUSPEND, NEVER_HANDSHOOK, UNANSWERED, NO_NETWORK, STALLED }

    /** One reading of wg-go's peer counters. [handshakeAgeSec] is null for "never". */
    data class Sample(val rxBytes: Long, val txBytes: Long, val handshakeAgeSec: Long?)

    /**
     * What the previous checks established: the counters last seen, and since
     * when (elapsedRealtime) sends that expect an answer have gone unanswered.
     */
    data class Liveness(
        val rxBytes: Long = -1L,
        val txBytes: Long = -1L,
        val unansweredSinceMs: Long? = null,
    )

    companion object {
        private const val TAG = "TunnelMonitor"

        /**
         * Check cadence. Each check is one wg-go getConfig JNI read. 30 s (the
         * old value, chosen for power) put detection at 180–210 s; 10 s puts
         * the fast rule's verdict 20–30 s after the first unanswered send, for
         * six small reads a minute.
         */
        internal const val CHECK_INTERVAL_MS = 10_000L

        /**
         * How long sends may go unanswered. WireGuard starts a handshake 15 s
         * after an unanswered send and retries it every 5 s, so at 20 s one
         * handshake attempt has had a full retry window and failed.
         */
        internal const val FAST_DEAD_MS = 20_000L

        /** No physical network for this long: dead (a roam brings the next network within a second or two). */
        internal const val NO_NETWORK_DEAD_MS = 10_000L

        /**
         * The idle backstop: the last handshake older than this. A healthy idle
         * tunnel re-handshakes at 120 s + one keepalive at most (175 s).
         */
        internal const val STALL_THRESHOLD_SEC = 180L

        /**
         * Grace period (ms) after tunnel start before any check engages —
         * initial handshakes can take several seconds on slow networks, and the
         * transport probe owns the first verdict.
         */
        private const val STALL_GRACE_MS = 30_000L

        /**
         * Bounded wait (ms) for the monitor thread to exit during [stop] so a
         * new tunnel's monitor cannot race with a stale one still using the old
         * handle. Capped to avoid blocking the caller if a native call stalls.
         */
        private const val STOP_JOIN_TIMEOUT_MS = 2_000L

        /**
         * Deep sleep between two checks longer than this makes the next verdict
         * untrustworthy. After a phone slept for minutes the first check lands
         * on a handshake that is old only because the CPU was off — wg-go
         * re-handshakes on the next packet it sends, which it has not had the
         * chance to do yet.
         */
        internal const val SUSPEND_GAP_MS = 10_000L

        /** A WireGuard keepalive on the wire: a 16-byte transport header and a 16-byte tag, no payload. */
        internal const val KEEPALIVE_WIRE_BYTES = 32L

        /**
         * The most our own keepalives can add to tx between two checks: one per
         * keepalive interval, plus one for a check that runs a little late.
         * Anything above it is data or a handshake initiation, both of which a
         * live peer answers.
         */
        internal fun keepaliveAllowance(keepaliveSec: Int): Long =
            KEEPALIVE_WIRE_BYTES * (1 + CHECK_INTERVAL_MS / (keepaliveSec.coerceAtLeast(1) * 1000L))

        /**
         * One check, pure: fold [sample] into [prev] and judge it.
         *
         * @param sample null when wg-go's config could not be read: a handle
         *   wg-go no longer owns, or a failed read — the same verdict as a
         *   tunnel that never handshook, as before.
         * @param suspendedMs deep sleep since the previous check ([suspendGap]).
         * @param noNetworkForMs how long no physical network has existed (0 while one does).
         */
        internal fun assess(
            prev: Liveness,
            sample: Sample?,
            nowMs: Long,
            suspendedMs: Long,
            noNetworkForMs: Long,
            keepaliveSec: Int,
        ): Pair<Liveness, Verdict> {
            if (suspendedMs > SUSPEND_GAP_MS) {
                // Start over from this reading: deltas across a sleep say nothing.
                val baseline = sample?.let { Liveness(it.rxBytes, it.txBytes) } ?: Liveness()
                return baseline to Verdict.SKIPPED_AFTER_SUSPEND
            }
            if (noNetworkForMs >= NO_NETWORK_DEAD_MS) return prev to Verdict.NO_NETWORK
            if (sample?.handshakeAgeSec == null) return prev to Verdict.NEVER_HANDSHOOK

            val first = prev.rxBytes < 0
            val rxAdvanced = !first && sample.rxBytes > prev.rxBytes
            val txGrowth = if (first) 0L else sample.txBytes - prev.txBytes
            val unansweredSince = when {
                first || rxAdvanced -> null
                txGrowth > keepaliveAllowance(keepaliveSec) -> prev.unansweredSinceMs ?: nowMs
                else -> prev.unansweredSinceMs
            }
            val next = Liveness(sample.rxBytes, sample.txBytes, unansweredSince)
            val verdict = when {
                unansweredSince != null && nowMs - unansweredSince >= FAST_DEAD_MS -> Verdict.UNANSWERED
                sample.handshakeAgeSec > STALL_THRESHOLD_SEC -> Verdict.STALLED
                else -> Verdict.ALIVE
            }
            return next to verdict
        }

        /** Deep-sleep time between two readings: elapsed time minus awake time. */
        internal fun suspendGap(prevElapsed: Long, prevUptime: Long, nowElapsed: Long, nowUptime: Long): Long =
            ((nowElapsed - prevElapsed) - (nowUptime - prevUptime)).coerceAtLeast(0L)

        /**
         * Parse wg-go's UAPI dump: rx/tx summed over peers, and the age of the
         * newest `last_handshake_time_sec` (null when none is non-zero).
         */
        internal fun parseSample(config: String, nowEpochSec: Long): Sample {
            var rx = 0L
            var tx = 0L
            var newest = 0L
            for (line in config.lineSequence()) {
                when {
                    line.startsWith("rx_bytes=") -> rx += line.substringAfter('=').toLongOrNull() ?: 0L
                    line.startsWith("tx_bytes=") -> tx += line.substringAfter('=').toLongOrNull() ?: 0L
                    line.startsWith("last_handshake_time_sec=") -> {
                        val v = line.substringAfter('=').toLongOrNull() ?: 0L
                        if (v > newest) newest = v
                    }
                }
            }
            val age = if (newest > 0L) (nowEpochSec - newest).coerceAtLeast(0L) else null
            return Sample(rx, tx, age)
        }
    }

    private var thread: Thread? = null

    /**
     * Set by [stop] so a DELIBERATE teardown can never be mistaken for a drop.
     * [isAlive] is necessarily coarse — it cannot observe the moment a caller
     * decides to tear the tunnel down, and every teardown path calls [stop]
     * BEFORE it clears the handle or publishes the new state. Without this the
     * exit check below could still see a live tunnel and fire
     * [onUnexpectedExit], arming the kill switch and an auto-reconnect over a
     * server switch or a settings reapply the user asked for.
     */
    @Volatile private var stopped = false

    /** Start the monitor on a background daemon thread. */
    fun start() {
        thread = Thread({
            Log.i(TAG, "Tunnel monitor started for handle=$handle")
            val startTime = elapsedRealtime()
            var lastElapsed = startTime
            var lastUptime = uptime()
            var liveness = Liveness()
            var neverHandshook = false
            try {
                while (!stopped && isAlive() && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    val nowElapsed = elapsedRealtime()
                    val nowUptime = uptime()
                    val suspendedMs = suspendGap(lastElapsed, lastUptime, nowElapsed, nowUptime)
                    lastElapsed = nowElapsed
                    lastUptime = nowUptime

                    if (!WgNative.canReadConfig() || nowElapsed - startTime <= STALL_GRACE_MS) continue
                    val missingSince = underlyingMissingSince()
                    val noNetworkForMs = if (missingSince > 0L) nowElapsed - missingSince else 0L
                    val sample = WgNative.getConfig(handle)
                        ?.let { parseSample(it, System.currentTimeMillis() / 1000L) }
                    val (next, verdict) =
                        assess(liveness, sample, nowElapsed, suspendedMs, noNetworkForMs, keepaliveSec)
                    liveness = next
                    // Stalls are breadcrumbs, not events: usually the network,
                    // not the client. onUnexpectedExit publishes the Error;
                    // these record WHICH rule fired.
                    when (verdict) {
                        Verdict.ALIVE -> Unit
                        Verdict.SKIPPED_AFTER_SUSPEND -> {
                            Log.i(TAG, "Device was suspended ${suspendedMs}ms — deferring the check one cycle")
                            FaultReporter.trail(FaultReporter.PATH_TUNNEL, "stall check deferred after a suspend")
                        }
                        Verdict.NEVER_HANDSHOOK -> {
                            Log.w(TAG, "Tunnel stalled — no WireGuard handshake after grace period")
                            FaultReporter.trail(FaultReporter.PATH_TUNNEL, "stall: no handshake after grace period")
                            neverHandshook = true
                            break
                        }
                        Verdict.UNANSWERED -> {
                            Log.w(TAG, "Tunnel dead — sends unanswered for ${FAST_DEAD_MS}ms")
                            FaultReporter.trail(FaultReporter.PATH_TUNNEL, "dead: sends unanswered")
                            break
                        }
                        Verdict.NO_NETWORK -> {
                            Log.w(TAG, "Tunnel dead — no physical network for ${noNetworkForMs}ms")
                            FaultReporter.trail(FaultReporter.PATH_TUNNEL, "dead: no physical network")
                            break
                        }
                        Verdict.STALLED -> {
                            Log.w(TAG, "Tunnel stalled — last handshake older than ${STALL_THRESHOLD_SEC}s")
                            FaultReporter.trail(FaultReporter.PATH_TUNNEL, "stall: last handshake older than threshold")
                            break
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Log.i(TAG, "Tunnel monitor interrupted")
            }
            if (!stopped && isAlive()) {
                Log.w(TAG, "Tunnel monitor exited while connected — triggering kill switch")
                onUnexpectedExit(neverHandshook)
            }
            Log.i(TAG, "Tunnel monitor exiting")
        }, "birdo-tunnel-monitor").apply { isDaemon = true; start() }
    }

    /** Interrupt the monitor thread and release the reference. */
    @Synchronized
    fun stop() {
        stopped = true
        val t = thread
        thread = null
        // stop() is reachable FROM the monitor thread itself: onUnexpectedExit
        // runs on it, and the service's handler calls activateKillSwitch() →
        // cleanupTunnelDataPlane() → stop(). Interrupting and joining yourself
        // sets the caller's own interrupt flag and makes join() throw
        // immediately, leaking an interrupt into unrelated blocking calls made
        // later on that thread and turning the barrier below into a no-op.
        // Nothing to wait for in that case — the thread is already exiting.
        if (t === Thread.currentThread()) return
        t?.interrupt()
        // Wait (bounded) for the monitor thread to actually exit so a new
        // tunnel's monitor can't race with a stale one still holding the old
        // handle. The thread spends almost all its time in Thread.sleep, which
        // unblocks immediately on interrupt, so this returns near-instantly.
        try {
            t?.join(STOP_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
