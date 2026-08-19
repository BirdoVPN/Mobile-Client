package app.birdo.vpn.service

import android.net.VpnService
import android.util.Log

/**
 * Monitors a wg-go WireGuard tunnel.
 *
 * Two responsibilities:
 *  1. Periodically re-[VpnService.protect] the underlying UDP sockets so the
 *     tunnel keeps bypassing its own tun interface across network changes.
 *  2. Watch the wg userspace `last_handshake_time_sec` counter and fail fast
 *     if we haven't completed a handshake in [STALL_THRESHOLD_SEC] seconds
 *     while supposedly connected — that indicates the peer is gone (NAT
 *     rebind, server crash, blocked UDP) and we should kill-switch + let the
 *     auto-reconnect logic take over instead of silently leaking traffic.
 *
 * Extracted from [BirdoVpnService] for testability and readability.
 *
 * @param handle           The wg-go tunnel handle returned by [WgNative.turnOn]
 * @param service          The [VpnService] instance providing [VpnService.protect]
 * @param isAlive          Returns `true` while the tunnel should be monitored
 * @param onUnexpectedExit Called when the monitor decides the tunnel is dead
 */
class TunnelMonitor(
    private val handle: Int,
    private val service: VpnService,
    private val isAlive: () -> Boolean,
    private val onUnexpectedExit: () -> Unit,
) {
    companion object {
        private const val TAG = "TunnelMonitor"
        /**
         * Monitor cadence. The socket re-protect this loop performs is already
         * handled reactively by [registerDefaultNetworkCallback] at the exact
         * moment the underlying transport changes (the only time a re-protect is
         * needed), so this periodic pass is a rare-case backstop, not the primary
         * mechanism. The stall check only needs to fire well inside
         * [STALL_THRESHOLD_SEC]. A 5s cadence therefore ran a blocking wg-go
         * getConfig JNI read ~36x more often than required and, in the
         * background, undercut the notification ticker's deliberate 8s floor. 30s
         * still detects a dead tunnel within ~180-210s while cutting this loop's
         * CPU wakeups and getConfig serializations ~6x.
         */
        private const val CHECK_INTERVAL_MS = 30_000L
        /**
         * Maximum age (seconds) for the last successful WireGuard handshake
         * before we declare the tunnel dead. WireGuard rekeys every ~120s under
         * load; we give it a 60s grace window before giving up.
         */
        private const val STALL_THRESHOLD_SEC = 180L
        /**
         * Grace period (ms) after tunnel start before the stall check engages
         * — initial handshakes can take several seconds on slow networks.
         */
        private const val STALL_GRACE_MS = 30_000L
        /**
         * Bounded wait (ms) for the monitor thread to exit during [stop] so a
         * new tunnel's monitor cannot race with a stale one still using the old
         * handle. Capped to avoid blocking the caller if a native call stalls.
         */
        private const val STOP_JOIN_TIMEOUT_MS = 2_000L
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
            val startTime = System.currentTimeMillis()
            try {
                while (!stopped && isAlive() && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    try {
                        val v4 = WgNative.getSocketV4(handle)
                        if (v4 >= 0) service.protect(v4)
                        val v6 = WgNative.getSocketV6(handle)
                        if (v6 >= 0) service.protect(v6)
                    } catch (e: Exception) {
                        // protect() can throw if the socket/service is torn down
                        // mid-cycle. Log for visibility and keep monitoring — a
                        // genuine dead tunnel is caught by stall detection below.
                        Log.w(TAG, "Socket protect failed this cycle", e)
                    }

                    // Handshake-stall detection (after grace period)
                    if (WgNative.canReadConfig() && System.currentTimeMillis() - startTime > STALL_GRACE_MS) {
                        val ageSec = lastHandshakeAgeSeconds()
                        if (ageSec == null) {
                            Log.w(TAG, "Tunnel stalled — no WireGuard handshake after grace period")
                            break
                        }
                        if (ageSec > STALL_THRESHOLD_SEC) {
                            Log.w(TAG, "Tunnel stalled — last handshake ${ageSec}s ago, declaring dead")
                            // Break out so onUnexpectedExit fires below
                            break
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Log.i(TAG, "Tunnel monitor interrupted")
            }
            if (!stopped && isAlive()) {
                Log.w(TAG, "Tunnel monitor exited while connected — triggering kill switch")
                onUnexpectedExit()
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

    /**
     * Parse the wg-go UAPI dump for the most recent peer's
     * `last_handshake_time_sec=<unix-seconds>` line.
     * Returns the age in seconds, or `null` if unavailable / never handshook.
     */
    private fun lastHandshakeAgeSeconds(): Long? {
        val cfg = WgNative.getConfig(handle) ?: return null
        var newest = 0L
        cfg.lineSequence().forEach { line ->
            if (line.startsWith("last_handshake_time_sec=")) {
                val v = line.substringAfter('=').toLongOrNull() ?: return@forEach
                if (v > newest) newest = v
            }
        }
        if (newest <= 0L) return null
        val nowSec = System.currentTimeMillis() / 1000L
        return (nowSec - newest).coerceAtLeast(0L)
    }
}
