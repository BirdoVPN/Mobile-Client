package app.birdo.vpn.service

import android.content.Context
import android.net.VpnService
import android.util.Log
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.utils.NativeLibraryVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramSocket

/**
 * Manages the Xray Reality stealth tunnel lifecycle on Android.
 *
 * When stealth mode is enabled, WireGuard UDP traffic is routed through a local
 * Xray client that wraps it in VLESS + XTLS-Reality over TLS 1.3. To any network
 * observer or DPI system, this appears as a legitimate HTTPS connection to the
 * configured SNI domain (e.g. www.microsoft.com).
 *
 * Architecture:
 * ```
 * WireGuard → 127.0.0.1:LOCAL_PORT (dokodemo-door, UDP)
 *          → Xray VLESS+Reality → server:8443 (TLS 1.3)
 *          → Server Xray decloaks → 127.0.0.1:51820 (WireGuard)
 * ```
 *
 * Uses libXray (xray-core compiled for Android via gomobile) which provides:
 * - `Xray.startXray(configJson)` — start Xray with JSON config
 * - `Xray.stopXray()` — stop the running Xray instance
 * - `Xray.initXrayEnv(dataDir)` — initialize data directory for assets
 *
 * If libXray is not available, falls back to managing xray-core binary directly
 * via ProcessBuilder.
 */
object XrayManager {

    private const val TAG = "XrayManager"

    /** Local UDP port that Xray listens on for WireGuard traffic */
    private const val DEFAULT_LOCAL_PORT = 51821

    /** Xray assets subdirectory name */
    private const val XRAY_DIR = "xray"

    @Volatile
    private var isRunning = false

    @Volatile
    private var localPort = 0

    /** Process handle when running xray as external binary */
    private var xrayProcess: Process? = null

    /** Config file written for the binary-fallback path; deleted in [stop]. */
    private var xrayConfigFile: File? = null

    /** Reference to VpnService for socket protection */
    private var vpnService: VpnService? = null

    /**
     * Get the local port that Xray is listening on.
     * WireGuard should set its endpoint to 127.0.0.1:{localPort}.
     */
    fun getLocalPort(): Int = if (isRunning) localPort else 0

    /**
     * Whether the stealth tunnel is currently active.
     */
    fun isActive(): Boolean = isRunning

    /** True when a libXray binding or packaged executable is available. */
    fun isAvailable(context: Context): Boolean = hasLibXrayBinding() || findXrayBinary(context) != null

    /**
     * Set the VPN service reference for socket protection.
     * Must be called before [start] so Xray's outbound TCP socket can bypass the VPN.
     */
    fun setVpnService(service: VpnService) {
        vpnService = service
    }

    /**
     * Start the Xray Reality stealth tunnel.
     *
     * @param context  Application context for accessing files
     * @param config   VPN connect response containing Xray parameters
     * @return true if Xray started successfully
     */
    suspend fun start(context: Context, config: ConnectResponse): Boolean = withContext(Dispatchers.IO) {
        if (isRunning) {
            Log.w(TAG, "Xray already running, stopping first")
            stop()
        }

        val xrayEndpoint = config.xrayEndpoint
        val xrayUuid = config.xrayUuid
        val xrayPublicKey = config.xrayPublicKey
        val xrayShortId = config.xrayShortId
        val xraySni = config.xraySni ?: "www.microsoft.com"

        if (xrayEndpoint == null || xrayUuid == null || xrayPublicKey == null || xrayShortId == null) {
            Log.e(TAG, "Missing Xray configuration parameters")
            return@withContext false
        }

        // SEC: Validate Xray parameter formats before using them in config generation.
        // An MitM or compromised server response could send malformed values.
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        val publicKeyRegex = Regex("^([A-Za-z0-9_-]{43,44}|[0-9a-fA-F]{64})$") // Xray x25519 emits base64url; legacy hex accepted
        val shortIdRegex = Regex("^[0-9a-fA-F]{0,16}$")   // Reality shortId: 0–8 bytes hex
        if (!uuidRegex.matches(xrayUuid)) {
            Log.e(TAG, "Invalid Xray UUID format — rejecting connection")
            return@withContext false
        }
        if (!publicKeyRegex.matches(xrayPublicKey)) {
            Log.e(TAG, "Invalid Xray public key format — rejecting")
            return@withContext false
        }
        if (!shortIdRegex.matches(xrayShortId)) {
            Log.e(TAG, "Invalid Xray shortId format (expected ≤16 hex chars) — rejecting")
            return@withContext false
        }
        // SNI was the one server-supplied Xray field with no validation: a
        // coerced backend could tag individual stealth users with a unique SNI
        // any on-path observer can correlate. Require a plausible public
        // hostname: LDH labels, at least one dot, no IP literal, bounded length.
        val hostnameRegex = Regex(
            "^(?=.{4,253}\$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}\$",
        )
        if (!hostnameRegex.matches(xraySni)) {
            Log.e(TAG, "Invalid Xray SNI (not a plausible public hostname) — rejecting")
            return@withContext false
        }

        // Parse server endpoint
        val endpoint = parseEndpoint(xrayEndpoint)
        if (endpoint == null) {
            Log.e(TAG, "Invalid Xray endpoint: $xrayEndpoint")
            return@withContext false
        }
        val (serverHost, serverPort) = endpoint

        // Find an available local port
        localPort = findAvailablePort(DEFAULT_LOCAL_PORT)
        Log.i(TAG, "Using local port $localPort for Xray dokodemo-door inbound")

        try {
            // Generate Xray configuration JSON
            val configJson = buildXrayConfig(
                localPort = localPort,
                serverHost = serverHost,
                serverPort = serverPort,
                uuid = xrayUuid,
                publicKey = xrayPublicKey,
                shortId = xrayShortId,
                sni = xraySni,
            )

            // Try libXray first (gomobile binding), then fall back to binary
            val started = startWithLibXray(context, configJson)
                || startWithBinary(context, configJson)

            if (started) {
                isRunning = true
                Log.i(TAG, "Xray Reality tunnel started — listening on 127.0.0.1:$localPort")
                return@withContext true
            } else {
                Log.e(TAG, "Failed to start Xray — both libXray and binary methods failed")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Xray", e)
            return@withContext false
        }
    }

    /**
     * Stop the Xray Reality tunnel.
     */
    fun stop() {
        Log.i(TAG, "Stopping Xray Reality tunnel")
        try {
            // Try libXray stop
            try {
                val xrayClass = Class.forName("libXray.Libxray")
                val stopMethod = xrayClass.getDeclaredMethod("stopXray")
                stopMethod.invoke(null)
            } catch (_: Exception) {
                // libXray not available, try process
            }

            // Kill process if running
            xrayProcess?.let {
                it.destroyForcibly()
                it.waitFor()
            }
            xrayProcess = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Xray", e)
        } finally {
            // The binary-fallback config carries the VLESS UUID (a stealth
            // credential) — never leave it on disk past the session.
            try {
                xrayConfigFile?.delete()
            } catch (_: Exception) { /* best effort */ }
            xrayConfigFile = null
            isRunning = false
            localPort = 0
            vpnService = null
            Log.i(TAG, "Xray stopped")
        }
    }

    // NOTE: an earlier protectXraySocket() "probe" opened and closed up to ten
    // real TCP connections to the Reality port after the tunnel was up — a
    // distinguishable on-wire pattern on exactly the networks stealth targets,
    // while by its own documentation protecting nothing (it could never reach
    // Xray's internal fd). The *actual* off-tunnel guarantee is
    // VpnService.Builder.addDisallowedApplication(packageName) in
    // BirdoVpnService, which excludes the whole app process (incl. the
    // in-process/subprocess Xray) from the tunnel by UID. The probe was removed.

    // ── Xray Startup Methods ────────────────────────────────────

    /**
     * Try to start Xray via the libXray gomobile binding.
     * This is the preferred method as it runs in-process.
     */
    private fun startWithLibXray(context: Context, configJson: String): Boolean {
        return try {
            val xrayClass = Class.forName("libXray.Libxray")

            // Initialize Xray environment (asset directory for geoip/geosite)
            val dataDir = File(context.filesDir, XRAY_DIR).apply { mkdirs() }
            try {
                val initMethod = xrayClass.getDeclaredMethod("initXrayEnv", String::class.java)
                initMethod.invoke(null, dataDir.absolutePath)
            } catch (_: NoSuchMethodException) {
                Log.w(TAG, "initXrayEnv not available, continuing without init")
            }

            // Start Xray with config
            val startMethod = xrayClass.getDeclaredMethod("startXray", String::class.java)
            val result = startMethod.invoke(null, configJson)

            // Check result — libXray returns empty string on success
            val resultStr = result?.toString() ?: ""
            if (resultStr.isEmpty() || resultStr == "ok" || resultStr == "true") {
                Log.i(TAG, "Xray started via libXray")
                // Wait briefly for Xray to bind
                Thread.sleep(300)
                true
            } else {
                Log.e(TAG, "libXray.startXray returned: $resultStr")
                false
            }
        } catch (e: ClassNotFoundException) {
            Log.i(TAG, "libXray not available, will try binary fallback")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Xray via libXray", e)
            false
        }
    }

    private fun hasLibXrayBinding(): Boolean = try {
        Class.forName("libXray.Libxray")
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: Throwable) {
        false
    }

    /**
     * Fall back to running xray-core as an external binary.
     * The binary should be placed in the app's native lib directory or assets.
     */
    private fun startWithBinary(context: Context, configJson: String): Boolean {
        return try {
            // Look for xray binary in native libs or extracted assets
            val xrayBinary = findXrayBinary(context) ?: run {
                Log.e(TAG, "Xray binary not found")
                return false
            }
            val verifierName = if (xrayBinary.name == "libXray.so") "Xray" else "xray"
            if (!NativeLibraryVerifier.verifyLibrary(context, verifierName)) {
                Log.e(TAG, "Xray binary integrity check failed")
                return false
            }

            // Write config to a temp file (deleted in stop(); delete any stale
            // copy from a previous crashed session before writing anew)
            val configFile = File(context.cacheDir, "xray_config.json")
            configFile.delete()
            configFile.writeText(configJson)
            xrayConfigFile = configFile

            // Make binary executable
            xrayBinary.setExecutable(true)

            val process = ProcessBuilder(xrayBinary.absolutePath, "run", "-config", configFile.absolutePath)
                .redirectErrorStream(true)
                .start()

            xrayProcess = process

            // Read output on a background thread for logging
            Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (BuildConfig.DEBUG) Log.d(TAG, "Xray: $line")
                    }
                } catch (_: Exception) { /* process ended */ }
                // EOF on stdout means the process has exited (or is exiting). Reap it
                // so a self-terminated/crashed Xray does not linger as a zombie across
                // reconnects. stop() already reaps on the explicit-teardown path; this
                // covers the case where the process dies on its own.
                try {
                    process.waitFor()
                } catch (_: InterruptedException) { /* shutting down */ }
            }, "xray-stdout").apply { isDaemon = true; start() }

            // Give it a moment to start and verify
            Thread.sleep(500)
            if (process.isAlive) {
                Log.i(TAG, "Xray started as external process (pid=${getProcessPid(process)})")
                true
            } else {
                Log.e(TAG, "Xray process exited immediately with code: ${process.exitValue()}")
                xrayProcess = null
                configFile.delete()
                xrayConfigFile = null
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Xray binary", e)
            false
        }
    }

    // ── Configuration Builder ───────────────────────────────────

    /**
     * Build the Xray JSON configuration for VLESS + Reality with dokodemo-door inbound.
     *
     * Inbound: dokodemo-door on 127.0.0.1:{localPort} accepting WireGuard UDP
     * Outbound: VLESS + XTLS-Reality to server:{serverPort} over TLS 1.3
     */
    private fun buildXrayConfig(
        localPort: Int,
        serverHost: String,
        serverPort: Int,
        uuid: String,
        publicKey: String,
        shortId: String,
        sni: String,
    ): String {
        return JSONObject().apply {
            // Log configuration
            put("log", JSONObject().apply {
                put("loglevel", "warning")
            })

            // Inbound: capture WireGuard UDP on localhost
            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "wireguard-in")
                    put("listen", "127.0.0.1")
                    put("port", localPort)
                    put("protocol", "dokodemo-door")
                    put("settings", JSONObject().apply {
                        put("address", "127.0.0.1")
                        put("port", 51820) // Server-side WireGuard port
                        put("network", "udp")
                    })
                })
            })

            // Outbound: VLESS + XTLS-Reality to server
            put("outbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "vless-reality")
                    put("protocol", "vless")
                    put("settings", JSONObject().apply {
                        put("vnext", JSONArray().apply {
                            put(JSONObject().apply {
                                put("address", serverHost)
                                put("port", serverPort)
                                put("users", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("id", uuid)
                                        put("encryption", "none")
                                        // ZERO-DOWNLOAD FIX: this tunnel carries
                                        // WireGuard UDP. XTLS Vision flow
                                        // ("xtls-rprx-vision") is TCP-ONLY — with
                                        // it set, upstream trickles but UDP return
                                        // traffic is dropped (upload works, download
                                        // ~0). VLESS users carrying UDP MUST use an
                                        // empty flow. (Reality security stays on via
                                        // streamSettings; only the XTLS flow is
                                        // omitted.) Server inbound clients must match.
                                        put("flow", "")
                                    })
                                })
                            })
                        })
                    })
                    put("streamSettings", JSONObject().apply {
                        put("network", "tcp")
                        put("security", "reality")
                        put("realitySettings", JSONObject().apply {
                            put("fingerprint", "chrome")
                            put("serverName", sni)
                            put("publicKey", publicKey)
                            put("shortId", shortId)
                        })
                    })
                })
            })
        }.toString(2)
    }

    // ── Utilities ───────────────────────────────────────────────

    /**
     * Parse an endpoint string "host:port" into (host, port).
     */
    private fun parseEndpoint(endpoint: String): Pair<String, Int>? {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return null

        return try {
            if (trimmed.startsWith("[")) {
                // IPv6: [::1]:8443
                val closingBracket = trimmed.indexOf(']')
                if (closingBracket <= 1 || closingBracket + 2 > trimmed.lastIndex || trimmed[closingBracket + 1] != ':') {
                    return null
                }
                val host = trimmed.substring(1, closingBracket)
                val port = trimmed.substring(closingBracket + 2).toIntOrNull() ?: return null
                if (host.isBlank() || port !in 1..65535) return null
                Pair(host, port)
            } else {
                val lastColon = trimmed.lastIndexOf(':')
                if (lastColon <= 0 || lastColon == trimmed.lastIndex || trimmed.indexOf(':') != lastColon) return null
                val host = trimmed.substring(0, lastColon)
                val port = trimmed.substring(lastColon + 1).toIntOrNull() ?: return null
                if (host.isBlank() || port !in 1..65535) return null
                Pair(host, port)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse endpoint: $endpoint", e)
            null
        }
    }

    /**
     * Find an available local port, starting from [preferred].
     */
    private fun findAvailablePort(preferred: Int): Int {
        // Try preferred port first
        if (isPortAvailable(preferred)) return preferred
        // Try nearby ports
        for (offset in 1..100) {
            if (isPortAvailable(preferred + offset)) return preferred + offset
        }
        // Last resort: let OS pick
        return try {
            val socket = DatagramSocket(0)
            val port = socket.localPort
            socket.close()
            port
        } catch (_: Exception) {
            preferred // Fallback to preferred and hope for the best
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            val socket = DatagramSocket(port)
            socket.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Locate the xray binary in the app's native lib directory.
     */
    private fun findXrayBinary(context: Context): File? {
        // Check native libs directory (${nativeLibraryDir}/libxray.so)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val candidates = listOf(
            File(nativeDir, "libxray.so"),
            File(nativeDir, "libXray.so"),
            File(context.filesDir, "$XRAY_DIR/xray"),
        )
        return candidates.find { it.exists() && it.canExecute() }
    }

    private fun getProcessPid(process: Process): Long {
        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getLong(process)
        } catch (_: Exception) {
            -1L
        }
    }
}
