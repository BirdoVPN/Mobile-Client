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
        private const val CHECK_INTERVAL_MS = 5_000L
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
    }

    private var thread: Thread? = null

    /** Start the monitor on a background daemon thread. */
    fun start() {
        thread = Thread({
            Log.i(TAG, "Tunnel monitor started for handle=$handle")
            val startTime = System.currentTimeMillis()
            try {
                while (isAlive() && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    val v4 = WgNative.getSocketV4(handle)
                    if (v4 >= 0) service.protect(v4)
                    val v6 = WgNative.getSocketV6(handle)
                    if (v6 >= 0) service.protect(v6)

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
            if (isAlive()) {
                Log.w(TAG, "Tunnel monitor exited while connected — triggering kill switch")
                onUnexpectedExit()
            }
            Log.i(TAG, "Tunnel monitor exiting")
        }, "birdo-tunnel-monitor").apply { isDaemon = true; start() }
    }

    /** Interrupt the monitor thread and release the reference. */
    fun stop() {
        thread?.interrupt()
        thread = null
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
