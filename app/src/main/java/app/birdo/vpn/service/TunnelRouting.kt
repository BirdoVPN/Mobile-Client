package app.birdo.vpn.service

/**
 * D-6 (A1-016): which of the device's traffic rides the tunnel, as pure rules.
 *
 * Until the 2026-09-30 overhaul BirdoVPN excluded its OWN app from its own
 * tunnel, unconditionally, because Xray runs as a separate process whose
 * socket the app cannot protect(). Everything the app said went around the
 * tunnel with it: the heartbeat every minute (bearer token + key id), every
 * API call, the DoH lookups and opted-in crash reports, all from the user's
 * real IP — and for a Stealth user, TLS to api.birdo.app outside the Reality
 * disguise. These rules are what replaced that one line, so each is a unit
 * test instead of a device session:
 *
 *  - the app is inside the tunnel, except while Stealth runs Xray as a child
 *    process ([TunnelAppRules]);
 *  - Xray's own server is carved out of the tunnel's routes, so its socket
 *    never loops into the tunnel it carries ([XrayCarveOut]);
 *  - the API uses the tunnel only while a verified tunnel is up and nothing
 *    is tearing it down, and goes around it everywhere else ([ApiRoutePolicy]).
 *
 * The kill-switch BLOCK interface is not a tunnel: the app stays excluded from
 * it, so sign-in, /connect and the reconnect path keep working behind it.
 */
internal object TunnelAppRules {

    /**
     * The packages to pass to `addDisallowedApplication` for the TUNNEL
     * interface.
     *
     * [ownPackage] is excluded ONLY while [stealthActive]: Xray runs as a child
     * process of this app (the packaged libxray.so, started by XrayManager), so
     * it shares the app's UID and its TCP socket cannot be protect()ed from here.
     * Its server route is carved out as well ([XrayCarveOut]); the UID
     * exclusion is kept on top of it until Xray runs in-process with a protect
     * callback, because any socket Xray opens to an address other than the
     * carved one would otherwise loop into the tunnel it is carrying. KNOWN
     * DEGRADATION, documented in README.md: during a Stealth session the app's
     * own API traffic still leaves outside the tunnel (it uses the bypass path,
     * with DoH, as before the overhaul).
     *
     * A persisted split-tunnel list can still name this app (the picker hides
     * it now, an older build did not); it is dropped here, so that list can
     * never take the app back out of its own tunnel.
     */
    fun disallowedPackages(
        ownPackage: String,
        stealthActive: Boolean,
        splitTunnelEnabled: Boolean,
        splitTunnelApps: Collection<String>,
    ): List<String> = buildList {
        if (stealthActive) add(ownPackage)
        if (splitTunnelEnabled) addAll(splitTunnelApps.filter { it != ownPackage && it.isNotBlank() }.distinct())
    }

    /** The split-tunnel picker never offers BirdoVPN itself: excluding it would undo D-6. */
    fun selectableForSplitTunnel(packageName: String, ownPackage: String): Boolean =
        packageName != ownPackage
}

/**
 * Keep Xray's connection to its server OUT of the tunnel by route, so the
 * tunnel can capture everything else.
 *
 * API 33+ has `VpnService.Builder.excludeRoute`. On API 29–32 the same effect
 * is a route table that covers every address except the server's — the
 * technique the service already uses to leave the LAN out when Local Network
 * Sharing is on: a route that contains the server is replaced by the routes
 * that cover it minus that one /32.
 */
internal object XrayCarveOut {

    enum class Method { EXCLUDE_ROUTE, ROUTE_TABLE }

    /** `excludeRoute(IpPrefix)` exists from API 33 (TIRAMISU). */
    fun method(sdkInt: Int): Method = if (sdkInt >= 33) Method.EXCLUDE_ROUTE else Method.ROUTE_TABLE

    /**
     * The IPv4 literal of Xray's server from `host:port`, or null.
     *
     * Literal only, never a lookup: the backend sends `ip:port`
     * (vpn.service.ts), and a hostname would have to be resolved before the
     * tunnel exists — through the very resolver the tunnel replaces. A null
     * here leaves the carve-out out; the stealth UID exclusion still keeps
     * Xray's socket off the tunnel (see [TunnelAppRules]).
     */
    fun serverIpv4(xrayEndpoint: String?): String? {
        val host = xrayEndpoint?.trim()?.substringBeforeLast(':', "") ?: return null
        return host.takeIf { parseIpv4(it) != null }
    }

    /**
     * [cidr] with [excludedIpv4] taken out: the route itself when it does not
     * contain the address (or is IPv6), otherwise the sibling of every prefix
     * on the path from the route down to the /32 — at most 32 routes, which
     * together cover exactly the route minus that one address.
     */
    fun split(cidr: String, excludedIpv4: String): List<String> {
        val ip = parseIpv4(excludedIpv4) ?: return listOf(cidr)
        val slash = cidr.indexOf('/')
        if (slash <= 0) return listOf(cidr)
        val network = parseIpv4(cidr.substring(0, slash)) ?: return listOf(cidr)
        val prefix = cidr.substring(slash + 1).toIntOrNull()?.takeIf { it in 0..32 } ?: return listOf(cidr)
        if (!contains(network, prefix, ip)) return listOf(cidr)
        if (prefix == 32) return emptyList()
        return (prefix + 1..32).map { length ->
            // At each step keep the half that does NOT hold the address.
            val bit = 1L shl (32 - length)
            val onPath = ip and mask(length)
            "${format(onPath xor bit)}/$length"
        }
    }

    private fun mask(prefix: Int): Long = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    private fun contains(network: Long, prefix: Int, ip: Long): Boolean {
        val bits = mask(prefix)
        return network.and(bits) == ip.and(bits)
    }

    private fun parseIpv4(s: String): Long? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            val octet = part.toInt()
            if (octet > 255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    private fun format(value: Long): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"
}

/**
 * The verdict on protecting wg-go's own UDP sockets right after wgTurnOn.
 * With the app inside its tunnel (D-6), an unprotected socket sends every
 * WireGuard packet back into the interface it came from.
 */
internal object TunnelSocketProtection {

    /**
     * @param v4Fd wg-go's IPv4 socket, or a negative value when it has none.
     * @param v6Fd the IPv6 twin.
     * @return true when at least one socket exists and EVERY socket that
     *   exists was protected. (The endpoint is IPv4 today; a v6 socket left
     *   unprotected is still refused, because which one wg-go sends on is its
     *   choice, not ours.)
     */
    fun complete(v4Fd: Int, v6Fd: Int, v4Protected: Boolean, v6Protected: Boolean): Boolean {
        if (v4Fd < 0 && v6Fd < 0) return false
        if (v4Fd >= 0 && !v4Protected) return false
        if (v6Fd >= 0 && !v6Protected) return false
        return true
    }
}

/**
 * Which HTTP client the app's own API call uses: the TUNNEL client (ordinary
 * sockets, the system resolver, which inside the tunnel is the tunnel's DNS)
 * or the BYPASS client (sockets protect()ed around any tunnel of ours, names
 * resolved over DoH).
 */
internal object ApiRoutePolicy {

    enum class Path { TUNNEL, BYPASS }

    /**
     * TUNNEL only while the session is [VpnState.Connected] — a handshake was
     * observed, so the tunnel demonstrably carries traffic — no kill-switch
     * block is up, and Stealth is off. Everything else bypasses:
     *  - no tunnel yet (sign-in, the server list, the first /connect): there
     *    is nothing to ride;
     *  - behind the block and on the reconnect path: the tunnel is dead or
     *    gone, and a call into it would hang until its timeout;
     *  - while VpnManager is tearing the tunnel down (a switch, a reap) even
     *    though the service has not caught up yet — the reason [session] is
     *    VpnManager's state and not the service's;
     *  - Stealth: the app is outside the tunnel by UID anyway, and the bypass
     *    client keeps its lookups on DoH instead of the network's resolver.
     */
    fun pathFor(session: VpnState, blockActive: Boolean, stealthActive: Boolean): Path =
        if (session is VpnState.Connected && !blockActive && !stealthActive) Path.TUNNEL else Path.BYPASS

    /** The live path, read by the API's call factory on every call. */
    fun currentPath(): Path = pathFor(
        session = VpnManager.sessionState(),
        blockActive = BirdoVpnService.killSwitchActive,
        stealthActive = BirdoVpnService.stealthActive,
    )
}
