package app.birdo.vpn.ui

/**
 * Centralized test tag constants for Compose UI testing.
 * Use with Modifier.testTag(TestTags.XXX) and
 * composeTestRule.onNodeWithTag(TestTags.XXX) in tests.
 */
object TestTags {
    // Login screen
    const val LOGIN_EMAIL_FIELD = "login_email_field"
    const val LOGIN_PASSWORD_FIELD = "login_password_field"
    const val LOGIN_BUTTON = "login_button"
    const val LOGIN_ERROR = "login_error"
    const val LOGIN_SIGN_UP = "login_sign_up"
    const val LOGIN_2FA_CODE_FIELD = "login_2fa_code_field"
    const val LOGIN_2FA_VERIFY_BUTTON = "login_2fa_verify_button"
    const val LOGIN_2FA_BACK_BUTTON = "login_2fa_back_button"
    const val LOGIN_SSO_GOOGLE = "login_sso_google"
    const val LOGIN_SSO_GITHUB = "login_sso_github"
    const val LOGIN_TAB_EMAIL = "login_tab_email"
    const val LOGIN_TAB_ANONYMOUS = "login_tab_anonymous"
    const val LOGIN_TAB_SSO = "login_tab_sso"
    const val LOGIN_ANONYMOUS_CREATE = "login_anonymous_create"
    const val LOGIN_ANONYMOUS_ID_FIELD = "login_anonymous_id_field"
    const val LOGIN_ANONYMOUS_PASSWORD_FIELD = "login_anonymous_password_field"
    const val LOGIN_ANONYMOUS_SUBMIT = "login_anonymous_submit"

    // Save-your-ID step after creating an anonymous account in-app. Same tag
    // name as the iOS accessibility identifier (`login_anonymous_ack`) so the
    // two platforms' UI suites talk about this gate in the same terms.
    const val LOGIN_ANONYMOUS_CREATED_ID = "login_anonymous_created_id"
    const val LOGIN_ANONYMOUS_ACK = "login_anonymous_ack"

    // Home screen
    const val CONNECT_BUTTON = "connect_button"
    const val VPN_STATUS = "vpn_status"
    const val SERVER_SELECTOR = "server_selector"

    // Settings
    const val KILL_SWITCH_TOGGLE = "kill_switch_toggle"
    const val AUTO_CONNECT_TOGGLE = "auto_connect_toggle"
    const val NOTIFICATIONS_TOGGLE = "notifications_toggle"

    // Consent screen
    const val CONSENT_ACCEPT = "consent_accept"
    const val CONSENT_DECLINE = "consent_decline"

    // Navigation
    const val OFFLINE_BANNER = "offline_banner"
}
