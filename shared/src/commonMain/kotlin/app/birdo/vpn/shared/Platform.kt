package app.birdo.vpn.shared

/**
 * Platform-specific declarations for Kotlin Multiplatform.
 *
 * Each target (androidMain, appleMain) provides `actual` implementations.
 */

/** Current wall-clock time in milliseconds since Unix epoch. */
expect fun currentTimeMillis(): Long

/**
 * Validate whether [address] is a well-formed IP literal (IPv4 or IPv6)
 * that is not loopback, link-local, multicast, or wildcard.
 *
 * Platform-specific because it relies on socket-level address parsing.
 */
expect fun isValidDnsAddress(address: String): Boolean
