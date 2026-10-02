package app.birdo.vpn.utils

import app.birdo.vpn.data.model.UserProfile

/** Digits per display group. */
private const val ANON_ID_GROUP_SIZE = 4

/**
 * Group a 24-digit anonymous account ID into space-separated blocks of four:
 * `123456789012345678901234` → `1234 5678 9012 3456 7890 1234`.
 *
 * The grouping is not cosmetic. The ID is the account's only credential and
 * users copy it out by hand; an unbroken 24-digit run is where transcription
 * errors come from. This is the same shape the Anonymous login tab advertises
 * in its field placeholder (`login_anonymous_id_placeholder`,
 * "XXXX XXXX XXXX XXXX XXXX XXXX"), so what a user writes down here reads back
 * identically to what they later type in.
 *
 * Non-digits are stripped first, and a partial/odd-length ID is still grouped
 * rather than rejected — this is display formatting, never validation. The
 * 24-digit length check stays where it already is, in
 * `AuthViewModel.loginAnonymous`.
 */
fun formatAnonymousId(raw: String): String =
    raw.filter { it.isDigit() }
        .chunked(ANON_ID_GROUP_SIZE)
        .joinToString(" ")

/**
 * The account number as Profile shows it until the user asks to see it
 * (REVIEW-AND2-013): every group masked but the last, so the user can tell
 * which account this is without exposing the credential to anyone beside
 * them — `•••• •••• •••• •••• •••• 1234`, the Windows client's form. Copy
 * still takes all 24 digits.
 */
fun maskAnonymousId(raw: String): String {
    val groups = formatAnonymousId(raw).split(' ').filter { it.isNotEmpty() }
    return groups.mapIndexed { i, group ->
        if (i == groups.lastIndex) group else "•".repeat(group.length)
    }.joinToString(" ")
}

private const val ANON_EMAIL_PREFIX = "anon_"
private const val ANON_EMAIL_SUFFIX = "@anonymous.local"

/**
 * Whether [email] is the synthetic address the backend gives an anonymous
 * account: `anon_<24-digit account number>@anonymous.local`.
 *
 * That string must NEVER be rendered. It reads as a bug, and it carries the
 * account's only credential in the clear: the Home top bar used to show it, and
 * a published F-Droid screenshot still shows the first twelve digits (A2-013).
 * iOS draws the same line (APIClient.swift, "Never render it").
 */
fun isAnonymousAccountEmail(email: String?): Boolean =
    email != null && email.startsWith(ANON_EMAIL_PREFIX) && email.endsWith(ANON_EMAIL_SUFFIX)

/** The 24-digit account number inside an anonymous account's synthetic email, or null. */
fun anonymousAccountNumber(email: String?): String? =
    email?.takeIf(::isAnonymousAccountEmail)?.removePrefix(ANON_EMAIL_PREFIX)?.removeSuffix(ANON_EMAIL_SUFFIX)

/**
 * Whether [user] is an anonymous account. `GET /auth/me` says so outright
 * since the 2026-10-01 account API (`isAnonymous`, then `accountType`; item
 * 86); a server older than that only through the synthetic email's shape,
 * which phase 2 of that API stops sending.
 */
fun isAnonymousUser(user: UserProfile?): Boolean {
    if (user == null) return false
    user.isAnonymous?.let { return it }
    user.accountType?.let { return it.equals(ACCOUNT_TYPE_ANONYMOUS, ignoreCase = true) }
    return isAnonymousAccountEmail(user.email)
}

/**
 * An anonymous [user]'s 24-digit account number: the server's `accountNumber`
 * when it sends one (item 86), else the number inside the synthetic email.
 * Null for a standard account. The account's only credential: never log it.
 */
fun accountNumberOf(user: UserProfile?): String? {
    if (user == null || !isAnonymousUser(user)) return null
    return user.accountNumber?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
        ?: anonymousAccountNumber(user.email)
}

private const val ACCOUNT_TYPE_ANONYMOUS = "anonymous"
