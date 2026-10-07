package app.birdo.vpn.shared.util

import app.birdo.vpn.shared.isValidDnsAddress as platformValidateDns

/**
 * Cross-platform input validation for all user-supplied data.
 *
 * Pure-Kotlin validators live here directly; anything that requires
 * platform socket APIs delegates to expect/actual declarations.
 */
object InputValidator {

    // ── Email ────────────────────────────────────────────────────

    private val EMAIL_REGEX = Regex(
        "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,64}$"
    )
    private const val EMAIL_MAX_LENGTH = 254

    fun isValidEmail(email: String): Boolean {
        val trimmed = email.trim()
        return trimmed.length in 1..EMAIL_MAX_LENGTH && EMAIL_REGEX.matches(trimmed)
    }

    // ── Password ─────────────────────────────────────────────────

    private const val PASSWORD_MIN_LENGTH = 6
    private const val PASSWORD_MAX_LENGTH = 256

    fun isValidPassword(password: String): Boolean =
        password.length in PASSWORD_MIN_LENGTH..PASSWORD_MAX_LENGTH

    // ── DNS / IP ─────────────────────────────────────────────────

    /**
     * Validate a DNS server address. Delegates to the platform-specific
     * implementation which checks for loopback, link-local, multicast, etc.
     */
    fun isValidDnsAddress(address: String): Boolean = platformValidateDns(address.trim())

    private val IPV4_REGEX = Regex(
        "^(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\." +
        "(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\." +
        "(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\." +
        "(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
    )

    fun isValidIpv4(address: String): Boolean = IPV4_REGEX.matches(address.trim())

    /** Check if string looks like an IP literal (not a hostname). */
    fun looksLikeIpLiteral(address: String): Boolean =
        address.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '%' }

    // ── MTU ──────────────────────────────────────────────────────

    private const val MTU_MIN = 1280
    private const val MTU_MAX = 1500

    /** 0 = auto (server default), otherwise clamp to [1280, 1500]. */
    fun clampMtu(mtu: Int): Int = if (mtu == 0) 0 else mtu.coerceIn(MTU_MIN, MTU_MAX)

    fun isValidMtu(mtu: Int): Boolean = mtu == 0 || mtu in MTU_MIN..MTU_MAX

    // ── Sanitization ─────────────────────────────────────────────

    /** Sanitize a server error body before showing it in the UI. */
    fun sanitizeErrorMessage(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        val trimmed = raw.trim()
        if (trimmed.length > 200 ||
            trimmed.contains("<html", ignoreCase = true) ||
            trimmed.contains("Exception") ||
            trimmed.contains("at ") ||
            trimmed.contains("stackTrace", ignoreCase = true)
        ) return fallback
        return trimmed
    }

    /** Server IDs should be alphanumeric + hyphens only, max 64 chars. */
    private val SERVER_ID_REGEX = Regex("^[A-Za-z0-9_\\-]{1,64}$")

    fun isValidServerId(id: String): Boolean = SERVER_ID_REGEX.matches(id)
}
