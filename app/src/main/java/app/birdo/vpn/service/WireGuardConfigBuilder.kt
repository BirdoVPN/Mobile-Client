package app.birdo.vpn.service

import android.util.Log
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.utils.FaultReporter
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
        // IPv6 dual-stack (optional): only present for IPv6-enabled nodes. Accept
        // either a bare address ("fd00:b1d0::5") or CIDR ("fd00:b1d0::5/128").
        val clientIpv6Cidr = response.clientIpv6?.takeIf { it.isNotBlank() }?.let { v6 ->
            val cidr = if (v6.contains('/')) v6 else "$v6/128"
            require(isValidCidr(cidr)) { "Invalid clientIpv6: ${response.clientIpv6}" }
            cidr
        }
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

        // Dual-stack: add the tunnel IPv6 address so wg-go owns IPv6 on tun0.
        // Routing of ::/0 into the tunnel is already handled via allowedIps below.
        clientIpv6Cidr?.let {
            try {
                interfaceBuilder.addAddress(InetNetwork.parse(it))
                Log.i(TAG, "Dual-stack: assigned tunnel IPv6 address")
            } catch (e: Exception) {
                // The tunnel comes up v4-only while the server still believes
                // this client is dual-stack. Address text deliberately not sent.
                FaultReporter.report(
                    FaultReporter.PATH_CONNECT,
                    "config_ipv6_address_rejected",
                    "wg-go rejected the assigned tunnel IPv6 address — the tunnel is v4-only",
                    e,
                )
            }
        }

        // A DNS server that fails to parse is DROPPED. Silently, until now: if
        // every one of them fails the interface carries no DNS at all and the
        // handset falls back to its network-provided resolver, so the user's
        // queries leave outside the tunnel — the single thing a custom DNS
        // setting exists to prevent. Neither report names a server.
        val requestedDns = resolveDnsServers(response, prefs)
        var dnsAdded = 0
        for (dns in requestedDns) {
            try {
                interfaceBuilder.addDnsServer(InetAddress.getByName(dns))
                dnsAdded++
            } catch (e: Exception) {
                FaultReporter.report(
                    FaultReporter.PATH_CONNECT,
                    "config_dns_server_rejected",
                    "A resolved DNS server was rejected and dropped from the tunnel config",
                    e,
                )
            }
        }
        if (requestedDns.isNotEmpty() && dnsAdded == 0) {
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "config_dns_all_rejected",
                "Every DNS server was rejected — the tunnel carries none and the system " +
                    "resolver will be used outside it",
            )
        }

        val userMtu = prefs.wireGuardMtu
        val effectiveMtu = (if (userMtu > 0) userMtu else (response.mtu ?: 1420)).coerceIn(1280, 1500)
        try {
            interfaceBuilder.parseMtu(effectiveMtu.toString())
        } catch (e: Exception) {
            // Falls back to wg-go's default MTU: a silent cause of the
            // "connects but nothing loads" reports that look like a dead node.
            FaultReporter.report(
                FaultReporter.PATH_CONNECT,
                "config_mtu_rejected",
                "wg-go rejected the computed MTU — the tunnel keeps the engine default",
                e,
            )
        }

        val effectiveEndpoint = applyPortOverride(response.endpoint!!, prefs)

        val peerBuilder = Peer.Builder()
            .parsePublicKey(peerPublicKey.toBase64())
            .parseEndpoint(effectiveEndpoint)
            // Floor the keepalive at 10s: a keepalive of 1-2s wakes the radio
            // every second or two (heavy battery drain) for no benefit, and the
            // value is server-supplied — clamp defensively so a misconfigured or
            // compromised backend can never drive the client's radio that hard.
            // Default WireGuard keepalive is 25s; 10s honours any sane override.
            .parsePersistentKeepalive("${(response.persistentKeepalive ?: 25).coerceIn(10, 300)}")
        var allowedIpCount = 0
        for (cidr in response.allowedIps ?: listOf("0.0.0.0/0", "::/0")) {
            try {
                peerBuilder.addAllowedIp(InetNetwork.parse(cidr))
                allowedIpCount++
            } catch (e: Exception) {
                // Each cidr is pre-validated by require(isValidCidr) above, so
                // this only fires on a validator/parser divergence. The comment
                // here used to say a Log.w made that divergence "observable".
                // It does not: R8 strips android.util.Log from the release
                // artifact. A PARTIAL skip is the dangerous one — check() below
                // catches a total one — because dropping ::/0 while keeping
                // 0.0.0.0/0 leaves IPv6 routing outside the tunnel. The cidr
                // itself is not sent.
                FaultReporter.report(
                    FaultReporter.PATH_CONNECT,
                    "config_allowed_ip_skipped",
                    "An allowed-IP failed to parse after validation and was dropped from the route set",
                    e,
                )
            }
        }
        // A peer with no allowed-IPs routes no traffic — a broken tunnel. Fail
        // loudly instead of returning a config that silently carries nothing.
        check(allowedIpCount > 0) { "No allowedIPs parsed — tunnel would route no traffic" }

        response.presharedKey?.let {
            try {
                peerBuilder.parsePreSharedKey(it)
            } catch (e: Exception) {
                // The server sent a PSK (pre-validated above). Silently omitting it
                // would downgrade the connection's cryptographic protection, so fail
                // the connect rather than proceed without the intended PSK.
                throw IllegalStateException("Server-provided preshared key failed to parse", e)
            }
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
        // Never rewrite the port of a loopback endpoint. When stealth (Xray
        // Reality) is active the WireGuard endpoint is the LOCAL relay
        // (127.0.0.1:<xrayPort>); rewriting that port would point wg-go at a dead
        // local port and silently break the tunnel. The port override is meant for
        // the real server endpoint only (to dodge port-based blocking).
        if (isLoopbackEndpointHost(endpoint)) return endpoint
        val lastColon = endpoint.lastIndexOf(':')
        return if (lastColon > 0) endpoint.substring(0, lastColon + 1) + overridePort
        else "$endpoint:$overridePort"
    }

    /**
     * True if the endpoint's host is loopback (127.0.0.0/8, ::1, localhost).
     * String-only check — deliberately avoids DNS resolution.
     */
    private fun isLoopbackEndpointHost(endpoint: String): Boolean {
        val host = if (endpoint.startsWith("[")) {
            val close = endpoint.indexOf(']')
            if (close < 0) return false
            endpoint.substring(1, close)
        } else {
            val colon = endpoint.lastIndexOf(':')
            if (colon <= 0) return false
            endpoint.substring(0, colon)
        }
        return host.equals("localhost", ignoreCase = true) ||
            host == "::1" ||
            host.startsWith("127.")
    }

    /**
     * Resolve DNS servers, preferring user overrides when enabled.
     *
     * SINGLE SOURCE OF TRUTH for tunnel-time DNS selection: [BirdoVpnService]
     * (the Android `Builder.addDnsServer` path) calls this too, so the servers
     * the OS resolver uses and the servers baked into the wg-go config can
     * never diverge. A private near-copy used to live in the service, which is
     * exactly how a validation fix lands in one of the two paths and not the
     * other.
     */
    fun resolveDnsServers(config: ConnectResponse, prefs: AppPreferences): List<String> {
        val fallback = listOf("1.1.1.1", "1.0.0.1")
        if (!prefs.customDnsEnabled) {
            // BirdoShield (D18): the filtering resolver the server hands out is
            // the node's own tunnel-gateway address (10.13.13.1), which the
            // RFC1918 rejection below used to throw away — so a device with
            // BirdoShield ON resolved through 1.1.1.1 exactly like OFF. Only a
            // SERVER-provided entry can be admitted this way; the gateway rule
            // is never applied to user-typed custom DNS.
            val serverDns = config.dns?.filter {
                isValidDnsAddress(it) || isTunnelGatewayResolver(it, config.assignedIp)
            } ?: emptyList()
            if (serverDns.isEmpty()) {
                Log.w(TAG, "Server provided no valid DNS servers — falling back to defaults (1.1.1.1)")
            }
            return serverDns.ifEmpty { fallback }
        }
        val custom = buildList {
            val p = prefs.customDnsPrimary.trim()
            if (p.isNotBlank() && isValidDnsAddress(p)) add(p)
            val s = prefs.customDnsSecondary.trim()
            if (s.isNotBlank() && isValidDnsAddress(s)) add(s)
        }
        if (custom.isEmpty()) {
            Log.w(TAG, "Custom DNS addresses invalid or unreachable through the tunnel — falling back to defaults")
        }
        return custom.ifEmpty { fallback }
    }

    /**
     * A DNS server is only usable if it is reachable THROUGH the tunnel.
     *
     * Beyond loopback/wildcard, this rejects the private and link-scoped
     * ranges (RFC1918 via [InetAddress.isSiteLocalAddress], 169.254.0.0/16,
     * IPv6 ULA fc00::/7 and fe80::/10):
     *  - with local network sharing ON those ranges are excluded from the VPN
     *    routes, so queries to such a resolver would egress on the LAN in
     *    cleartext while the UI says Protected — a DNS leak;
     *  - with it OFF the 0.0.0.0/0 route captures them into the tunnel, whose
     *    egress is the public internet — the resolver is unreachable and the
     *    whole device silently loses name resolution.
     * Either way the address cannot serve DNS for this tunnel. Callers fall
     * back to 1.1.1.1/1.0.0.1 when nothing survives.
     *
     * The ONE private address that IS reachable through the tunnel — the
     * node's own gateway resolver (BirdoShield) — is admitted separately by
     * [isTunnelGatewayResolver], for server-provided entries only.
     */
    private fun isValidDnsAddress(address: String): Boolean {
        return try {
            val addr = InetAddress.getByName(address)
            !addr.isLoopbackAddress &&
                !addr.isAnyLocalAddress &&
                !addr.isLinkLocalAddress &&
                !addr.isSiteLocalAddress &&
                !isUniqueLocalV6(addr)
        } catch (_: Exception) {
            false
        }
    }

    /** IPv6 ULA fc00::/7 — NOT covered by isSiteLocalAddress (fec0::/10 only). */
    private fun isUniqueLocalV6(addr: InetAddress): Boolean =
        addr is java.net.Inet6Address && (addr.address[0].toInt() and 0xfe) == 0xfc

    /**
     * BirdoShield (D18): is [address] the tunnel-gateway resolver for a client
     * whose tunnel address is [assignedIp]?
     *
     * Every node allocates client addresses from one /24 (the fleet's
     * BIRDO_AGENT_WG_SUBNET, 10.13.13.0/24) and runs the blocky filtering
     * resolver on its own wg0 address in that /24 (10.13.13.1). That address is
     * RFC1918, so [isValidDnsAddress] rejects it — correctly for a LAN
     * resolver, wrongly for this one: it is reachable ONLY through the tunnel,
     * by construction, because it shares the subnet the tunnel interface owns.
     *
     * The rule is therefore narrow: IPv4 literals only (no DNS lookups), the
     * same /24 as the assigned address, not the assigned address itself and
     * not the network/broadcast host. A LAN resolver (10.0.0.53, 192.168.1.1)
     * never satisfies it, so the leak/blackhole reasoning in
     * [isValidDnsAddress] still holds for everything else.
     *
     * With local network sharing ON the service's route set leaves 10.0.0.0/8
     * to the LAN, so an address admitted here MUST also be pinned back into
     * the tunnel — see [pinnedResolverRoutes].
     */
    fun isTunnelGatewayResolver(address: String, assignedIp: String?): Boolean {
        val resolver = parseIpv4Literal(address) ?: return false
        val assigned = parseIpv4Literal(assignedIp ?: return false) ?: return false
        if (resolver == assigned) return false
        if ((resolver ushr 8) != (assigned ushr 8)) return false
        val host = resolver and 0xff
        return host != 0 && host != 0xff
    }

    /**
     * BirdoShield (D18): the `/32` routes [BirdoVpnService] must add on top of
     * the local-network-sharing route set so the tunnel-gateway resolver is
     * captured into the tunnel. That route set deliberately skips 10.0.0.0/8,
     * 172.16.0.0/12 and 192.168.0.0/16; without a more specific route every
     * query to 10.13.13.1 would leave on the physical network in cleartext —
     * a DNS leak — and never reach the node. Longest prefix wins, so the /32
     * pulls back just the resolver and the rest of the LAN range stays local.
     *
     * Empty whenever [resolvedDns] holds only public resolvers (the OFF case,
     * custom DNS, or the fallback), so a device that never turned BirdoShield
     * on gets exactly the pre-D18 route set.
     */
    fun pinnedResolverRoutes(resolvedDns: List<String>, assignedIp: String?): List<String> =
        resolvedDns.filter { isTunnelGatewayResolver(it, assignedIp) }.map { "$it/32" }

    /** Dotted-quad only — never a hostname, so no resolver is consulted. */
    private fun parseIpv4Literal(s: String): Int? {
        val parts = s.trim().split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            val octet = part.toInt()
            if (octet > 255) return null
            value = (value shl 8) or octet
        }
        return value
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
