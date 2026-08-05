package app.birdo.vpn.shared

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun platformName(): String = "iOS"

/**
 * Pure-string validation of an IPv4 or IPv6 literal.
 *
 * We deliberately avoid C interop (`inet_pton`) here because:
 *  - the function only needs to validate that the user typed a numeric IP
 *    literal that isn't loopback / link-local / multicast / wildcard;
 *  - keeping it pure-Kotlin avoids platform.posix import drift across
 *    Kotlin/Native versions.
 */
actual fun isValidDnsAddress(address: String): Boolean {
    if (address.isBlank()) return false
    if (!address.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '%' }) {
        return false
    }
    return when {
        address.contains('.') && !address.contains(':') ->
            isValidIpv4(address) && !isLoopbackOrSpecialV4(address)
        address.contains(':') ->
            isValidIpv6(address) && !isLoopbackOrSpecialV6(address)
        else -> false
    }
}

private fun isValidIpv4(address: String): Boolean {
    val parts = address.split('.')
    if (parts.size != 4) return false
    return parts.all { p ->
        if (p.isEmpty() || p.length > 3) return@all false
        val n = p.toIntOrNull() ?: return@all false
        n in 0..255
    }
}

/**
 * Lenient IPv6 validator covering full form, "::" compression, and the
 * "v6 + zone-id" form (e.g. "fe80::1%en0"). Mirrors what `inet_pton`
 * would accept in normal usage.
 */
private fun isValidIpv6(addressWithZone: String): Boolean {
    val address = addressWithZone.substringBefore('%')
    if (address.isEmpty()) return false

    // Reject more than one "::"
    val doubleColons = Regex("::").findAll(address).count()
    if (doubleColons > 1) return false

    val hasDoubleColon = doubleColons == 1
    val groups = if (hasDoubleColon) {
        // Split on "::" then split each side on ":"
        val (left, right) = address.split("::", limit = 2)
        val l = if (left.isEmpty()) emptyList() else left.split(':')
        val r = if (right.isEmpty()) emptyList() else right.split(':')
        if (l.size + r.size > 7) return false
        l + r
    } else {
        val parts = address.split(':')
        if (parts.size != 8) return false
        parts
    }

    return groups.all { g ->
        g.length in 1..4 && g.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }
}

private fun isLoopbackOrSpecialV4(address: String): Boolean {
    val parts = address.split('.')
    if (parts.size != 4) return false
    val first = parts[0].toIntOrNull() ?: return false
    return first == 127 || first == 0 ||
        (first == 169 && (parts[1].toIntOrNull() ?: 0) == 254) ||
        first >= 224
}

private fun isLoopbackOrSpecialV6(address: String): Boolean {
    val lower = address.substringBefore('%').lowercase()
    return lower == "::1" || lower == "::" || lower.startsWith("fe80") || lower.startsWith("ff")
}
