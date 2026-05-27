package app.birdo.vpn.service

import android.util.Log
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
 * Method signatures (from wg-go JNI):
 * - wgTurnOn(ifName: String, tunFd: Int, settings: String): Int
 * - wgTurnOff(handle: Int): Void
 * - wgGetSocketV4(handle: Int): Int
 * - wgGetSocketV6(handle: Int): Int
 * - wgGetConfig(handle: Int): String   (optional — may not exist in all builds)
 */
internal object WgNative {

    private const val TAG = "WgNative"

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
                } catch (_: UnsatisfiedLinkError) {
                    Log.w(TAG, "System.loadLibrary(wg-go) failed, trying class loading")
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
                } catch (_: NoSuchMethodException) {
                    Log.w(TAG, "wgGetConfig not available — traffic stats disabled")
                }

                initialized = true
                Log.i(TAG, "WireGuard native initialized successfully")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize WireGuard native", e)
                initialized = true
                return false
            }
        }
    }

    /** Start a WireGuard tunnel. Returns a handle >= 0 on success, or -1 on failure. */
    fun turnOn(ifName: String, tunFd: Int, settings: String): Int {
        return try {
            turnOnMethod?.invoke(null, ifName, tunFd, settings) as? Int ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "wgTurnOn failed", e)
            -1
        }
    }

    /** Stop a WireGuard tunnel identified by [handle]. */
    fun turnOff(handle: Int) {
        try {
            turnOffMethod?.invoke(null, handle)
        } catch (e: Exception) {
            Log.e(TAG, "wgTurnOff failed", e)
        }
    }

    /** Get the IPv4 UDP socket fd for the tunnel, or -1 if unavailable. */
    fun getSocketV4(handle: Int): Int =
        try { getSocketV4Method?.invoke(null, handle) as? Int ?: -1 }
        catch (_: Exception) { -1 }

    /** Get the IPv6 UDP socket fd for the tunnel, or -1 if unavailable. */
    fun getSocketV6(handle: Int): Int =
        try { getSocketV6Method?.invoke(null, handle) as? Int ?: -1 }
        catch (_: Exception) { -1 }

    /**
     * Get the UAPI config string from a running tunnel.
     * Contains per-peer `rx_bytes` and `tx_bytes` stats.
     * Returns `null` if the method is unavailable or the call fails.
     */
    fun getConfig(handle: Int): String? =
        try { getConfigMethod?.invoke(null, handle) as? String }
        catch (_: Exception) { null }

    fun canReadConfig(): Boolean = getConfigMethod != null
}
