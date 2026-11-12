package app.birdo.vpn.utils

import app.birdo.vpn.BuildConfig
import app.birdo.vpn.data.model.UserProfile

/**
 * MR-1707 (Android half; Desktop-Client #416 is the reference). With the
 * server's `ANON_NUMBER_MASKING` on (birdo-web #850), `GET /auth/me` sends an
 * anonymous account's number as stars plus the last four digits
 * (`********************1234`). The full number is then shown once, at sign-up
 * or when it is replaced, and never again.
 */

/** Whether the server sent [raw] masked: stars standing in for the leading digits. */
fun isMaskedAccountNumber(raw: String?): Boolean = raw != null && '*' in raw

/**
 * The visible tail of a masked number: the digits after the last star, or null
 * when [raw] is not masked or has no tail.
 */
fun maskedNumberTail(raw: String?): String? {
    if (!isMaskedAccountNumber(raw)) return null
    return raw!!.substringAfterLast('*').filter { it.isDigit() }.takeIf { it.isNotEmpty() }
}

/** `•••• … 1234`: what the card shows for a masked number. No reveal, no copy. */
fun maskedAccountNumberDisplay(tail: String): String = "•••• … $tail"

/** The masked tail of an anonymous [user]'s number, or null when the server sent it whole (or not at all). */
fun maskedAccountTailOf(user: UserProfile?): String? =
    if (user != null && isAnonymousUser(user)) maskedNumberTail(user.accountNumber) else null

/**
 * Whether the "Replace number" entry point shows: the client flag is ON (it
 * ships dark, MR-1707) and the account is an anonymous one. [flag] is a
 * parameter so the rule is testable; callers use the default.
 */
fun replaceNumberEntryVisible(user: UserProfile?, flag: Boolean = BuildConfig.ANON_NUMBER_ROTATION): Boolean =
    flag && isAnonymousUser(user)
