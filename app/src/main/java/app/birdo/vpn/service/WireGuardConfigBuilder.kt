package app.birdo.vpn.service

import android.util.Log
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.preferences.AppPreferences
import com.wireguard.config.*
import com.wireguard.crypto.Key
import java.net.InetAddress

/**
 * Builds a wg-go [Config] from a server [ConnectResponse] and user preferences.
 *
 * Extracted from [BirdoVpnService] for testability and readability.
 */
object WireGuardConfigBuilder {

    private const val TAG = "WgConfigBuilder"

    /** Maximum allowed peer addresses / allowedIPs per config (DoS guard). */
    private const val MAX_ADDRESSES = 16
    private const val MAX_ALLOWED_IPS = 32

    /**
     * Build a WireGuard [Config] from the API connect response and user prefs.
     * Zeroes key material from intermediate objects after the config snapshot is taken.
     *
     * SEC: every field from [response] is treated as untrusted input. Each value
     * is structurally validated **before** it touches the wg-go config builder
     * so a malicious or malformed server response cannot inject extra peers,
     * unbounded allowed-IP lists, or invalid endpoints into the kernel tunnel.
     */
    fun build(response: ConnectResponse, prefs: AppPreferences): Config {
        // ---------- Strict pre-validation ----------
        require(!response.privateKey.isNullOrBlank()) { "Missing privateKey" }
        require(!response.serverPublicKey.isNullOrBlank()) { "Missing serverPublicKey" }
        require(!response.assignedIp.isNullOrBlank()) { "Missing assignedIp" }
        require(!response.endpoint.isNullOrBlank()) { "Missing endpoint" }
        require(isValidWireGuardKey(response.privateKey!!)) { "Invalid privateKey format" }
        require(isValidWireGuardKey(response.serverPublicKey!!)) { "Invalid serverPublicKey format" }
        response.presharedKey?.let { require(isValidWireGuardKey(it)) { "Invalid presharedKey format" } }
        require(isValidEndpoint(response.endpoint!!)) { "Invalid endpoint: ${response.endpoint}" }
        require(isValidCidr("${response.assignedIp}/32")) { "Invalid assignedIp" }
        response.allowedIps?.let {
            require(it.size <= MAX_ALLOWED_IPS) { "AllowedIPs list too large (${it.size})" }
            it.forEach { cidr -> require(isValidCidr(cidr)) { "Invalid allowedIp: $cidr" } }
        }
        // ------------------------------------------

        val privateKey = Key.fromBase64(response.privateKey!!)
        val peerPublicKey = Key.fromBase64(response.serverPublicKey!!)

        val interfaceBuilder = Interface.Builder()
            .parsePrivateKey(privateKey.toBase64())
            .addAddress(InetNetwork.parse("${response.assignedIp}/32"))

        for (dns in resolveDnsServers(response, prefs)) {
            try { interfaceBuilder.addDnsServer(InetAddress.getByName(dns)) } catch (_: Exception) {}
        }

        val userMtu = prefs.wireGuardMtu
        val effectiveMtu = (if (userMtu > 0) userMtu else (response.mtu ?: 1420)).coerceIn(1280, 1500)
        try { interfaceBuilder.parseMtu(effectiveMtu.toString()) } catch (_: Exception) {}

        val effectiveEndpoint = applyPortOverride(response.endpoint!!, prefs)

        val peerBuilder = Peer.Builder()
            .parsePublicKey(peerPublicKey.toBase64())
            .parseEndpoint(effectiveEndpoint)
            .parsePersistentKeepalive("${(response.persistentKeepalive ?: 25).coerceIn(1, 300)}")
        for (cidr in response.allowedIps ?: listOf("0.0.0.0/0", "::/0")) {
            try { peerBuilder.addAllowedIp(InetNetwork.parse(cidr)) } catch (_: Exception) {}
        }
        response.presharedKey?.let {
            try { peerBuilder.parsePreSharedKey(it) } catch (_: Exception) {}
        }

        val config = Config.Builder()
            .setInterface(interfaceBuilder.build())
            .addPeer(peerBuilder.build())
            .build()

        // Zero key material after config is built.
        try { privateKey.bytes.fill(0) } catch (_: Exception) {}
        try { peerPublicKey.bytes.fill(0) } catch (_: Exception) {}

        return config
    }

    /**
     * Apply the user's WireGuard port override to the endpoint string.
     * "auto" keeps the server-provided port.
     */
    fun applyPortOverride(endpoint: String, prefs: AppPreferences): String {
        val portPref = prefs.wireGuardPort
        if (portPref == "auto") return endpoint
        val overridePort = portPref.toIntOrNull() ?: return endpoint
        if (overridePort !in 1..65535) return endpoint
        val lastColon = endpoint.lastIndexOf(':')
        return if (lastColon > 0) endpoint.substring(0, lastColon + 1) + overridePort
        else "$endpoint:$overridePort"
    }

    /** Resolve DNS servers, preferring user overrides when enabled. */
    private fun resolveDnsServers(config: ConnectResponse, prefs: AppPreferences): List<String> {
        val fallback = listOf("1.1.1.1", "1.0.0.1")
        if (!prefs.customDnsEnabled) {
            val serverDns = config.dns?.filter { isValidDnsAddress(it) } ?: emptyList()
            return serverDns.ifEmpty { fallback }
        }
        val custom = buildList {
            val p = prefs.customDnsPrimary.trim()
            if (p.isNotBlank() && isValidDnsAddress(p)) add(p)
            val s = prefs.customDnsSecondary.trim()
            if (s.isNotBlank() && isValidDnsAddress(s)) add(s)
        }
        if (custom.isEmpty()) {
            Log.w(TAG, "Custom DNS addresses invalid or empty — falling back to defaults")
        }
        return custom.ifEmpty { fallback }
    }

    private fun isValidDnsAddress(address: String): Boolean {
        return try {
            val addr = InetAddress.getByName(address)
            !addr.isLoopbackAddress && !addr.isAnyLocalAddress
        } catch (_: Exception) {
            false
        }
    }

    // ---------- SEC: Server-Response Validators ----------

    /** WireGuard keys are exactly 32 bytes encoded as 44-char base64 with one trailing `=`. */
    private fun isValidWireGuardKey(b64: String): Boolean {
        return try {
            val decoded = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            decoded.size == 32
        } catch (_: Exception) {
            false
        }
    }

    /** Endpoint must be `host:port` (or `[ipv6]:port`) with port in 1–65535. */
    private fun isValidEndpoint(endpoint: String): Boolean {
        if (endpoint.length > 255) return false
        val (host, portStr) = if (endpoint.startsWith("[")) {
            val close = endpoint.indexOf(']')
            if (close < 0 || endpoint.getOrNull(close + 1) != ':') return false
            endpoint.substring(1, close) to endpoint.substring(close + 2)
        } else {
            val colon = endpoint.lastIndexOf(':')
            if (colon <= 0) return false
            endpoint.substring(0, colon) to endpoint.substring(colon + 1)
        }
        if (host.isBlank()) return false
        val port = portStr.toIntOrNull() ?: return false
        if (port !in 1..65535) return false
        // Allow DNS hostnames or IP literals; reject obvious garbage.
        if (host.any { it.isWhitespace() || it == '\n' || it == '\r' }) return false
        return true
    }

    /** CIDR like `10.0.0.1/32`, `0.0.0.0/0`, `::/0`, `fd00::1/64`. */
    private fun isValidCidr(cidr: String): Boolean {
        val slash = cidr.indexOf('/')
        if (slash <= 0) return false
        val host = cidr.substring(0, slash)
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return false
        return try {
            val addr = InetAddress.getByName(host)
            val maxPrefix = if (addr is java.net.Inet4Address) 32 else 128
            prefix in 0..maxPrefix
        } catch (_: Exception) {
            false
        }
    }
}
