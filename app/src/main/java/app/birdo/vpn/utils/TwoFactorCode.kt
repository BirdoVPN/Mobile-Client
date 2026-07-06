package app.birdo.vpn.utils

/**
 * Backup code: hex in complete 4-char groups, dashes optional. Matches the
 * legacy 8-hex "XXXX-XXXX" and the current 16-hex "XXXX-XXXX-XXXX-XXXX". Kept
 * byte-for-byte in step with the desktop client and the web/backend validators.
 */
private val BACKUP_CODE_SHAPE = Regex("^[0-9A-Fa-f]{4}(?:-?[0-9A-Fa-f]{4})+$")

/**
 * True when [code] is a complete 2FA response the backend can verify: either a
 * 6-digit TOTP, or a backup code in the shape above.
 *
 * This only gates the client (enable Verify / pre-submit check); the backend
 * `auth/2fa/verify` endpoint does the authoritative TOTP/hash check. Shared by
 * the login screen and AuthViewModel so both agree on what "complete" means.
 */
fun is2faCodeComplete(code: String): Boolean =
    Regex("^\\d{6}$").matches(code) || BACKUP_CODE_SHAPE.matches(code)
