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
    const val LOGIN_ANONYMOUS_BUTTON = "login_anonymous_button"
    const val LOGIN_ANONYMOUS_ID_FIELD = "login_anonymous_id_field"
    const val LOGIN_ANONYMOUS_PASSWORD_FIELD = "login_anonymous_password_field"
    const val LOGIN_ANONYMOUS_SUBMIT = "login_anonymous_submit"

    // Home screen
    const val CONNECT_BUTTON = "connect_button"
    const val VPN_STATUS = "vpn_status"
    const val SERVER_SELECTOR = "server_selector"
    const val TRAFFIC_STATS = "traffic_stats"
    const val CONNECTION_TIMER = "connection_timer"
    const val PUBLIC_IP = "public_ip"

    // Server list
    const val SERVER_LIST = "server_list"
    const val SERVER_SEARCH = "server_search"
    const val SERVER_ITEM_PREFIX = "server_item_"
    const val SERVER_REFRESH = "server_refresh"

    // Settings
    const val KILL_SWITCH_TOGGLE = "kill_switch_toggle"
    const val AUTO_CONNECT_TOGGLE = "auto_connect_toggle"
    const val NOTIFICATIONS_TOGGLE = "notifications_toggle"
    const val SPLIT_TUNNEL_TOGGLE = "split_tunnel_toggle"

    // Split tunnel
    const val SPLIT_TUNNEL_LIST = "split_tunnel_list"
    const val SPLIT_TUNNEL_APP_PREFIX = "split_tunnel_app_"

    // Consent screen
    const val CONSENT_ACCEPT = "consent_accept"
    const val CONSENT_DECLINE = "consent_decline"

    // Navigation
    const val BOTTOM_NAV = "bottom_nav"
    const val OFFLINE_BANNER = "offline_banner"
}
