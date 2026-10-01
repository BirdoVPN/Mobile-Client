package app.birdo.vpn.service

import android.content.Context
import android.util.Log
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.utils.FaultReporter
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
 *          → Server Xray decloaks → <node>:<WireGuard port> (see [wireGuardTarget])
 * ```
 *
 * Xray is the packaged xray-core executable (libxray.so in the native lib
 * directory), run as a CHILD PROCESS with ProcessBuilder. It shares this app's
 * UID, and its socket cannot be protect()ed from here — which is why the
 * tunnel carves its server out of the routes and, while Stealth is on, keeps
 * the app's UID out of the tunnel (TunnelRouting.kt, D-6). (A libXray gomobile
 * binding path used to sit here too; the AAR was never in the build, so it
 * could not succeed and was removed, A1-039.)
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

    /** The config file Xray reads (it carries the VLESS UUID); deleted in [stop]. */
    private var xrayConfigFile: File? = null

    /** Name of [xrayConfigFile] in the app's cache directory. */
    private const val CONFIG_FILE_NAME = "xray_config.json"

    /**
     * Called when the Xray process exits on its own while stealth is active —
     * a crash, an OOM kill. Not called for an exit [stop] caused.
     */
    @Volatile
    private var onUnexpectedExit: (() -> Unit)? = null

    /** Set by [stop] before it kills the process, so that exit is not unexpected. */
    @Volatile
    private var stopping = false

    /**
     * Seams for a unit test of [start] (REVIEW-AND2-014): which local port it
     * takes, and what runs the config it built. The test reads that config,
     * so a start() that stopped forwarding to the node's WireGuard endpoint
     * fails a test, not only a device session.
     */
    internal var pickLocalPort: (Int) -> Int = { findAvailablePort(it) }
    internal var launch: (Context, String) -> Boolean = { context, configJson -> startWithBinary(context, configJson) }

    /**
     * Get the local port that Xray is listening on.
     * WireGuard should set its endpoint to 127.0.0.1:{localPort}.
     */
    fun getLocalPort(): Int = if (isRunning) localPort else 0

    /**
     * Whether the stealth tunnel is currently active.
     */
    fun isActive(): Boolean = isRunning

    /** True when the Xray executable is packaged and runnable. */
    fun isAvailable(context: Context): Boolean = findXrayBinary(context) != null

    /**
     * A1-041: the config file carries the VLESS UUID, a stealth credential.
     * [stop] deletes it, but a process that died with Xray running never got
     * there, and the file stayed in the cache until the next stealth start.
     * The service calls this when it is created.
     */
    fun deleteStaleConfig(context: Context) {
        if (isRunning) return
        try {
            File(context.cacheDir, CONFIG_FILE_NAME).delete()
        } catch (_: Exception) { /* best effort, as in stop() */ }
    }

    /**
     * Where the server's Xray must forward the WireGuard packets: the node
     * itself, at its WireGuard port (LIVE-AND-STEALTH-001).
     *
     * This used to be a hard-coded 127.0.0.1:51820. Since Xray 26 the
     * server's freedom outbound refuses private and reserved destinations,
     * loopback included, for a VLESS inbound — so Stealth on Android passed
     * no traffic on any relay (an on-node canary: udp to 127.0.0.1:51820
     * FAILS, udp to <node ip>:51820 PASSES). Derived exactly as the desktop
     * client does (commands/vpn.rs, P1-dk-xray-wgport-hardcoded): the host of
     * the stealth endpoint, and the port the user overrode, else the port of
     * the server's own WireGuard endpoint. No fallback port: a config with
     * neither cannot say where WireGuard listens, and guessing is how the
     * hard-coded one broke. An override is honoured only when it is the
     * relays' own port (WireGuardConfigBuilder.portOverride, LIVE-PORT53): a
     * stale "53" forwarded nowhere, over Stealth as directly.
     *
     * @param portOverride the user's stored WireGuard port setting.
     * @return host and port, or null when either cannot be derived.
     */
    internal fun wireGuardTarget(xrayEndpoint: String?, wireGuardEndpoint: String?, portOverride: String): Pair<String, Int>? {
        val host = xrayEndpoint?.let { parseEndpoint(it) }?.first ?: return null
        val port = WireGuardConfigBuilder.portOverride(portOverride)
            ?: wireGuardEndpoint?.let { parseEndpoint(it) }?.second
            ?: return null
        return host to port
    }

    /**
     * Start the Xray Reality stealth tunnel.
     *
     * @param context  Application context for accessing files
     * @param config   VPN connect response containing Xray parameters (with
     *   the server's own WireGuard endpoint, not the local relay)
     * @param wireGuardPortOverride the user's stored WireGuard port setting ([wireGuardTarget])
     * @param onExit   called if Xray exits on its own while running (A1-032)
     * @return true if Xray started successfully
     */
    suspend fun start(
        context: Context,
        config: ConnectResponse,
        wireGuardPortOverride: String,
        onExit: () -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
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
            // Everything below that rejects the server's Xray parameters is a
            // backend contract violation or a MitM — fleet-shaped, and the
            // user cannot describe it. All reported; none carries a value.
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "stealth_config_incomplete",
                "Server granted stealth but omitted one or more Xray parameters",
            )
            return@withContext false
        }

        // SEC: Validate Xray parameter formats before using them in config generation.
        // An MitM or compromised server response could send malformed values.
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
        val publicKeyRegex = Regex("^([A-Za-z0-9_-]{43,44}|[0-9a-fA-F]{64})$") // Xray x25519 emits base64url; legacy hex accepted
        val shortIdRegex = Regex("^[0-9a-fA-F]{0,16}$")   // Reality shortId: 0–8 bytes hex
        if (!uuidRegex.matches(xrayUuid)) {
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "stealth_uuid_invalid",
                "Server sent an Xray UUID in an invalid format — rejecting",
            )
            return@withContext false
        }
        if (!publicKeyRegex.matches(xrayPublicKey)) {
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "stealth_public_key_invalid",
                "Server sent an Xray public key in an invalid format — rejecting",
            )
            return@withContext false
        }
        if (!shortIdRegex.matches(xrayShortId)) {
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "stealth_short_id_invalid",
                "Server sent an Xray shortId in an invalid format (expected ≤16 hex chars) — rejecting",
            )
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
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "stealth_sni_invalid",
                "Server sent an Xray SNI that is not a plausible public hostname — rejecting",
            )
            return@withContext false
        }

        // Parse server endpoint
        val endpoint = parseEndpoint(xrayEndpoint)
        if (endpoint == null) {
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "stealth_endpoint_invalid",
                "Server sent an Xray endpoint that does not parse as host:port — rejecting",
            )
            Log.d(TAG, "rejected Xray endpoint: $xrayEndpoint")
            return@withContext false
        }
        val (serverHost, serverPort) = endpoint
        val target = wireGuardTarget(xrayEndpoint, config.endpoint, wireGuardPortOverride)
        if (target == null) {
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "stealth_wireguard_target_unknown",
                "No WireGuard port could be derived for the Xray forward — refusing to guess one",
            )
            return@withContext false
        }

        // Find an available local port
        localPort = pickLocalPort(DEFAULT_LOCAL_PORT)
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
                targetHost = target.first,
                targetPort = target.second,
            )

            stopping = false
            onUnexpectedExit = onExit
            if (launch(context, configJson)) {
                isRunning = true
                Log.i(TAG, "Xray Reality tunnel started — listening on 127.0.0.1:$localPort")
                return@withContext true
            } else {
                // startWithBinary reported its own cause; this is the verdict
                // the service acts on.
                onUnexpectedExit = null
                FaultReporter.report(
                    FaultReporter.PATH_STEALTH,
                    "stealth_start_failed_all_methods",
                    "Failed to start the Xray executable",
                )
                return@withContext false
            }
        } catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "stealth_start_threw",
                "Starting Xray threw",
                e,
            )
            return@withContext false
        }
    }

    /**
     * Stop the Xray Reality tunnel.
     */
    fun stop() {
        Log.i(TAG, "Stopping Xray Reality tunnel")
        // This exit is ours: the stdout thread must not report it.
        stopping = true
        onUnexpectedExit = null
        try {
            // Kill process if running
            xrayProcess?.let {
                it.destroyForcibly()
                it.waitFor()
            }
            xrayProcess = null
        } catch (e: Exception) {
            // A stop that fails can leave a Reality client running after the
            // tunnel is gone.
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "stealth_stop_failed",
                "Stopping Xray threw — the stealth client may still be running",
                e,
            )
        } finally {
            // The binary-fallback config carries the VLESS UUID (a stealth
            // credential) — never leave it on disk past the session.
            try {
                xrayConfigFile?.delete()
            } catch (_: Exception) { /* best effort */ }
            xrayConfigFile = null
            isRunning = false
            localPort = 0
            Log.i(TAG, "Xray stopped")
        }
    }

    // NOTE: an earlier protectXraySocket() "probe" opened and closed up to ten
    // real TCP connections to the Reality port after the tunnel was up — a
    // distinguishable on-wire pattern on exactly the networks stealth targets,
    // while by its own documentation protecting nothing (it could never reach
    // Xray's internal fd). What keeps Xray's own connection off the tunnel is
    // the route carve-out and, while Stealth is on, the app's UID exclusion
    // (BirdoVpnService.buildVpnInterface, TunnelRouting.kt).

    // ── Starting the executable ─────────────────────────────────

    /** Run the packaged xray-core executable with [configJson]. */
    private fun startWithBinary(context: Context, configJson: String): Boolean {
        return try {
            // Look for xray binary in native libs or extracted assets
            val xrayBinary = findXrayBinary(context) ?: run {
                FaultReporter.report(
                    FaultReporter.PATH_STEALTH,
                    "stealth_binary_missing",
                    "No Xray executable is packaged",
                )
                return false
            }
            val verifierName = if (xrayBinary.name == "libXray.so") "Xray" else "xray"
            if (!NativeLibraryVerifier.verifyLibrary(context, verifierName)) {
                // The verifier reports WHY; this is the stealth-side
                // consequence, the twin of connect_refused_integrity.
                FaultReporter.report(
                    FaultReporter.PATH_STEALTH,
                    "stealth_binary_integrity_failed",
                    "Refused to run the Xray binary: integrity verification failed",
                )
                return false
            }

            // Write config to a temp file (deleted in stop(); delete any stale
            // copy from a previous crashed session before writing anew)
            val configFile = File(context.cacheDir, CONFIG_FILE_NAME)
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
                // A1-032: an Xray that died on its own used to be noticed
                // only by the stall detector, minutes later. Tell the service
                // now — unless stop() caused it, or a newer Xray replaced it.
                if (!stopping && xrayProcess === process) onUnexpectedExit?.invoke()
            }, "xray-stdout").apply { isDaemon = true; start() }

            // Give it a moment to start and verify
            Thread.sleep(500)
            if (process.isAlive) {
                Log.i(TAG, "Xray started as external process (pid=${getProcessPid(process)})")
                true
            } else {
                FaultReporter.report(
                    FaultReporter.PATH_STEALTH,
                    "stealth_binary_exited",
                    "Xray process exited immediately (exit code ${process.exitValue()})",
                )
                xrayProcess = null
                configFile.delete()
                xrayConfigFile = null
                false
            }
        } catch (e: Exception) {
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "stealth_binary_start_threw",
                "Starting the Xray binary threw",
                e,
            )
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
    internal fun buildXrayConfig(
        localPort: Int,
        serverHost: String,
        serverPort: Int,
        uuid: String,
        publicKey: String,
        shortId: String,
        sni: String,
        targetHost: String,
        targetPort: Int,
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
                        // Where the SERVER's Xray forwards: the node's own
                        // WireGuard endpoint, never loopback ([wireGuardTarget]).
                        put("address", targetHost)
                        put("port", targetPort)
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
            // The endpoint value is a server address: not sent.
            FaultReporter.report(
                FaultReporter.PATH_STEALTH,
                "stealth_endpoint_parse_threw",
                "Parsing the Xray endpoint threw",
                e,
            )
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
