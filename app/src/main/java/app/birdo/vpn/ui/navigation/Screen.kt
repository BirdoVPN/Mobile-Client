package app.birdo.vpn.ui.navigation

sealed class Screen(val route: String) {
    data object Consent : Screen("consent")
    data object Login : Screen("login")
    data object Home : Screen("home")
    data object Profile : Screen("profile")
    data object ServerList : Screen("servers")
    data object Settings : Screen("settings")
    /** Anonymous-only free-tier data usage meter. */
    data object Limit : Screen("limit")
    data object SplitTunnel : Screen("split_tunnel")
    data object VpnSettings : Screen("vpn_settings")
    data object PortForward : Screen("port_forward")
    data object Subscription : Screen("subscription")
}
