package app.birdo.vpn.service

import app.birdo.vpn.utils.FaultReporter
import java.lang.reflect.Method

/**
 * Thin JNI bridge to the wg-go native library (libwg-go.so).
 *
 * WireGuard's Android tunnel library ships native C/Go code accessed through
 * private static methods on [com.wireguard.android.backend.GoBackend]. Because
 * the tunnel library does not expose a public Java API for these low-level
 * operations we use reflection to invoke them directly.
 *
 * Thread safety: [init] uses double-checked locking so the native library is
 * loaded and methods are resolved exactly once. All public functions are safe
 * to call from any thread.
 *
 * Reporting: every function here converts a failure into a sentinel return
 * (`false` / `-1` / `null`) so that a broken engine surfaces as a
 * [VpnState.Error] instead of crashing the VPN service. That is correct
 * behaviour and must stay — but it means the exception is the ONLY evidence
 * the failure happened, and `android.util.Log` is stripped from release builds
 * (see app/proguard-rules.pro). Every catch below therefore goes through
 * [app.birdo.vpn.utils.FaultReporter], which is the only channel that survives
 * R8. An anonymous, unbound catch in this file is a blind spot by
 * construction, so DataplaneFaultReportingTest scans this file and fails the
 * build if one reappears or if a catch stops reporting.
 *
 * Method signatures (from wg-go JNI):
 * - wgTurnOn(ifName: String, tunFd: Int, settings: String): Int
 * - wgTurnOff(handle: Int): Void
 * - wgGetSocketV4(handle: Int): Int
 * - wgGetSocketV6(handle: Int): Int
 * - wgGetConfig(handle: Int): String   (optional — may not exist in all builds)
 */
internal object WgNative {

    @Volatile
    private var initialized = false
    private var turnOnMethod: Method? = null
    private var turnOffMethod: Method? = null
    private var getSocketV4Method: Method? = null
    private var getSocketV6Method: Method? = null
    private var getConfigMethod: Method? = null

    /**
     * Load libwg-go.so and resolve method handles via reflection.
     * @return `true` if [turnOnMethod] (the critical path) was resolved.
     */
    fun init(): Boolean {
        if (initialized) return turnOnMethod != null
        synchronized(this) {
            if (initialized) return turnOnMethod != null
            try {
                try {
                    System.loadLibrary("wg-go")
                } catch (e: UnsatisfiedLinkError) {
                    // Not fatal on its own — Class.forName below can still pull
                    // the library in through GoBackend's own static init — but
                    // it is the first symptom of a bad ABI split or a stripped
                    // APK, and it is worth having when the next line fails too.
                    FaultReporter.report(
                        FaultReporter.PATH_CONNECT,
                        "wg_loadlibrary_failed",
                        "System.loadLibrary(wg-go) failed, falling back to class loading",
                        e,
                    )
                }

                val cls = Class.forName("com.wireguard.android.backend.GoBackend")

                turnOnMethod = cls.getDeclaredMethod(
                    "wgTurnOn",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                ).apply { isAccessible = true }

                turnOffMethod = cls.getDeclaredMethod(
                    "wgTurnOff",
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }

                getSocketV4Method = cls.getDeclaredMethod(
                    "wgGetSocketV4",
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }

                getSocketV6Method = cls.getDeclaredMethod(
                    "wgGetSocketV6",
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }

                try {
                    getConfigMethod = cls.getDeclaredMethod(
                        "wgGetConfig",
                        Int::class.javaPrimitiveType,
                    ).apply { isAccessible = true }
                } catch (e: NoSuchMethodException) {
                    // Degraded, not broken: the tunnel still runs, but traffic
                    // stats and handshake-stall detection go dark
                    // (TunnelMonitor gates both on canReadConfig()). Losing
                    // stall detection across a wireguard-android bump is
                    // exactly the silent regression this reporter exists for.
                    FaultReporter.report(
                        FaultReporter.PATH_CONNECT,
                        "wg_get_config_unavailable",
                        "wgGetConfig not available — traffic stats and stall detection disabled",
                        e,
                    )
                }

                initialized = true
                return true
            } catch (e: Exception) {
                // The whole engine is unusable on this device/build — the
                // single loudest failure the client has. It is reported here
                // rather than at the BirdoVpnService call site because THIS is
                // where the throwable still exists; the caller only sees false.
                FaultReporter.report(
                    FaultReporter.PATH_CONNECT,
                    "wg_native_init_failed",
                    "Failed to initialize WireGuard native bridge",
                    e,
                )
                initialized = true
                return false
            }
        }
    }

    /** Start a WireGuard tunnel. Returns a handle >= 0 on success, or -1 on failure. */
    fun turnOn(ifName: String, tunFd: Int, settings: String): Int {
        return try {
            val result = turnOnMethod?.invoke(null, ifName, tunFd, settings) as? Int
            if (result == null) {
                // The silent branch the elvis operator used to hide: either
                // init() never resolved the method, or wg-go returned something
                // that is not an Int. Both produce a -1 that is indistinguishable
                // from a genuine wg-go refusal, and neither throws — so without
                // this there is nothing to report at all. NOTE: `settings` is
                // the WireGuard UAPI config (keys, endpoint) and must never be
                // put in a report.
                FaultReporter.report(
                    FaultReporter.PATH_CONNECT,
                    "wg_turn_on_no_result",
                    "wgTurnOn returned no handle (bridge not initialised or unexpected return type)",
                )
                return -1
            }
            result
        } catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "wg_turn_on_failed",
                "wgTurnOn threw for interface $ifName",
                e,
            )
            -1
        }
    }

    /** Stop a WireGuard tunnel identified by [handle]. */
    fun turnOff(handle: Int) {
        try {
            turnOffMethod?.invoke(null, handle)
        } catch (e: Exception) {
            // A tunnel that will not stop is a leak of the previous session's
            // route, not a cosmetic teardown warning.
            FaultReporter.report(
                FaultReporter.PATH_TUNNEL,
                "wg_turn_off_failed",
                "wgTurnOff threw — the tunnel may still be up",
                e,
            )
        }
    }

    /**
     * Get the IPv4 UDP socket fd for the tunnel, or -1 if unavailable.
     *
     * A -1 return is NOT reported: callers poll this during bring-up and a
     * not-yet-open socket is the normal answer for the first few hundred
     * milliseconds. A *throw* is reported, because it means the reflection
     * handle itself is wrong and the socket will never be protected — which
     * routes wg-go's own traffic back into the tunnel.
     */
    fun getSocketV4(handle: Int): Int =
        try {
            getSocketV4Method?.invoke(null, handle) as? Int ?: -1
        } catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_TUNNEL,
                "wg_get_socket_v4_failed",
                "wgGetSocketV4 threw — tunnel socket cannot be protected",
                e,
            )
            -1
        }

    /** Get the IPv6 UDP socket fd for the tunnel, or -1 if unavailable. */
    fun getSocketV6(handle: Int): Int =
        try {
            getSocketV6Method?.invoke(null, handle) as? Int ?: -1
        } catch (e: Exception) {
            // Same reasoning as getSocketV4 — kept as a twin on purpose: the v6
            // half going unreported is how a v6-only leak stays invisible.
            FaultReporter.report(
                FaultReporter.PATH_TUNNEL,
                "wg_get_socket_v6_failed",
                "wgGetSocketV6 threw — tunnel socket cannot be protected",
                e,
            )
            -1
        }

    /**
     * Get the UAPI config string from a running tunnel.
     * Contains per-peer `rx_bytes` and `tx_bytes` stats.
     * Returns `null` if the method is unavailable or the call fails.
     */
    fun getConfig(handle: Int): String? =
        try {
            getConfigMethod?.invoke(null, handle) as? String
        } catch (e: Exception) {
            // Throttled in FaultReporter — this is called from a polling loop.
            // Worth reporting because stall detection silently stops working
            // when it fails, and the tunnel then looks healthy while dead.
            FaultReporter.report(
                FaultReporter.PATH_TUNNEL,
                "wg_get_config_failed",
                "wgGetConfig threw — stats and stall detection unavailable",
                e,
            )
            null
        }

    fun canReadConfig(): Boolean = getConfigMethod != null
}
