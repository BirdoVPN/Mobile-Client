package app.birdo.vpn.shared

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformName(): String = "Android"

actual fun isValidDnsAddress(address: String): Boolean {
    if (address.isBlank()) return false
    // Guard: only allow numeric IP literals — never resolve hostnames
    if (!address.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '%' }) {
        return false
    }
    return try {
        val addr = InetAddress.getByName(address)
        (addr is Inet4Address || addr is Inet6Address) &&
            !addr.isLoopbackAddress &&
            !addr.isLinkLocalAddress &&
            !addr.isMulticastAddress &&
            !addr.isAnyLocalAddress &&
            // Private/link-scoped resolvers (RFC1918, IPv6 ULA) are never
            // reachable through the tunnel: depending on local network sharing
            // they either leak DNS onto the LAN or blackhole all resolution,
            // so the tunnel-time filter (WireGuardConfigBuilder) drops them.
            // Reject them here too so the settings UI can't accept an address
            // that connect time will silently discard.
            !addr.isSiteLocalAddress &&
            !(addr is Inet6Address && (addr.address[0].toInt() and 0xfe) == 0xfc)
    } catch (_: Exception) {
        false
    }
}
