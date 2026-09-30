package app.birdo.vpn.utils

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
