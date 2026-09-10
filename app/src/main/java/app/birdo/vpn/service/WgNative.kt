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
     * A -1 that wg-go itself returns is NOT reported: callers poll this during
     * bring-up and a not-yet-open socket is the normal answer for the first
     * few hundred milliseconds. Two other things used to produce the SAME -1
     * through `as? Int ?: -1` and were indistinguishable from it: an
     * unresolved method handle and an unexpected return type. Both mean the
     * socket will never be protected — wg-go's own traffic routes back into
     * the tunnel — so both are reported, as is a *throw*. Throttled, because
     * the callers poll. That elvis shape is banned in this file by
     * DataplaneFaultReportingTest.
     */
    fun getSocketV4(handle: Int): Int =
        try {
            val result = getSocketV4Method?.invoke(null, handle) as? Int
            if (result == null) {
                FaultReporter.report(
                    FaultReporter.PATH_TUNNEL,
                    "wg_get_socket_v4_no_result",
                    "wgGetSocketV4 returned no fd (bridge not initialised or unexpected return type) — tunnel socket cannot be protected",
                )
                -1
            } else {
                result
            }
        } catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_TUNNEL,
                "wg_get_socket_v4_failed",
                "wgGetSocketV4 threw — tunnel socket cannot be protected",
                e,
            )
            -1
        }

    /** Get the IPv6 UDP socket fd for the tunnel, or -1 if unavailable. Twin of [getSocketV4]. */
    fun getSocketV6(handle: Int): Int =
        try {
            val result = getSocketV6Method?.invoke(null, handle) as? Int
            if (result == null) {
                FaultReporter.report(
                    FaultReporter.PATH_TUNNEL,
                    "wg_get_socket_v6_no_result",
                    "wgGetSocketV6 returned no fd (bridge not initialised or unexpected return type) — tunnel socket cannot be protected",
                )
                -1
            } else {
                result
            }
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
    fun getConfig(handle: Int): String? {
        // Absent by design on some wireguard-android builds: reported ONCE at
        // init (wg_get_config_unavailable) and gated by canReadConfig() at
        // every caller, so a null here is deliberately not a second event.
        // Explicit, not folded into a `?.` chain, so it cannot be mistaken
        // for the case below.
        val method = getConfigMethod ?: return null
        return try {
            when (val raw = method.invoke(null, handle)) {
                // wg-go answers null for a handle it no longer owns or when
                // its IPC read fails. TunnelMonitor turns that into a stall
                // verdict after the grace period, which publishes a
                // VpnState.Error and so leaves a breadcrumb; a stall is
                // usually the network, not the client, so it is deliberately
                // not an event of its own.
                null -> null
                is String -> raw
                else -> {
                    // The handle resolved but its return type is not what the
                    // bridge was written against (a wireguard-android bump).
                    // `as? String` used to fold this into the null above,
                    // where it read as a stall. Throttled — polling loop.
                    FaultReporter.report(
                        FaultReporter.PATH_TUNNEL,
                        "wg_get_config_unexpected_type",
                        "wgGetConfig returned an unexpected type — stats and stall detection unavailable",
                    )
                    null
                }
            }
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
    }

    fun canReadConfig(): Boolean = getConfigMethod != null
}
