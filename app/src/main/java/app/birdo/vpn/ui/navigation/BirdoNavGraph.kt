package app.birdo.vpn.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import android.content.Intent
import android.provider.Settings
import app.birdo.vpn.R
import app.birdo.vpn.data.network.NetworkMonitor
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.ui.components.AdaptiveContainer
import app.birdo.vpn.ui.components.PixelCanvas
import app.birdo.vpn.ui.screen.*
import app.birdo.vpn.ui.TestTags
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.ui.viewmodel.AuthViewModel
import app.birdo.vpn.ui.viewmodel.SettingsViewModel
import app.birdo.vpn.ui.viewmodel.VpnViewModel

/**
 * Bottom nav tabs matching Windows client: Connect / Servers / Settings
 * Windows uses: Power icon (Connect), Server icon (Servers), Settings icon
 */
private data class BottomNavItem(
    val screen: Screen,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val bottomNavItems = listOf(
    BottomNavItem(Screen.Profile, R.string.profile_title, Icons.Outlined.Person),
    BottomNavItem(Screen.Home, R.string.connect, Icons.Default.PowerSettingsNew),
    BottomNavItem(Screen.Settings, R.string.settings_title, Icons.Default.Settings),
)

@Composable
fun BirdoNavGraph(
    onRequestVpnPermission: (android.content.Intent) -> Unit,
    appPreferences: AppPreferences,
    networkMonitor: NetworkMonitor,
    deepLinkRoute: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val vpnViewModel: VpnViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    var hasConsented by remember { mutableStateOf(appPreferences.hasAcceptedPrivacyPolicy) }
    val isOnline by networkMonitor.isOnline.collectAsState(initial = true)

    val authState by authViewModel.uiState.collectAsState()
    val vpnState by vpnViewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()

    // Handle VPN permission requests
    LaunchedEffect(vpnState.needsVpnPermission) {
        if (vpnState.needsVpnPermission) {
            val intent = vpnViewModel.getVpnPermissionIntent()
            if (intent != null) {
                onRequestVpnPermission(intent)
            } else {
                vpnViewModel.onVpnPermissionGranted()
            }
        }
    }

    // Navigate based on auth state + consent + reload servers after login
    LaunchedEffect(authState.isLoggedIn, authState.isLoading, hasConsented) {
        if (!authState.isLoading) {
            val currentRoute = navController.currentDestination?.route
            if (!hasConsented && currentRoute != Screen.Consent.route) {
                navController.navigate(Screen.Consent.route) {
                    popUpTo(0) { inclusive = true }
                }
            } else if (hasConsented && authState.isLoggedIn && currentRoute in listOf(Screen.Login.route, Screen.Consent.route)) {
                vpnViewModel.loadServers()
                navController.navigate(Screen.Home.route) {
                    popUpTo(0) { inclusive = true }
                }
            } else if (hasConsented && !authState.isLoggedIn && currentRoute != Screen.Login.route && currentRoute != Screen.Consent.route) {
                navController.navigate(Screen.Login.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    // FIX: Load servers when the user is already logged in at cold start.
    // The block above only fires on Login/Consent → Home transitions, so a
    // returning user (NavHost startDestination = Home) would never load
    // servers and the list would stay empty until pull-to-refresh.
    LaunchedEffect(authState.isLoggedIn, authState.isLoading, hasConsented) {
        if (!authState.isLoading && hasConsented && authState.isLoggedIn && vpnState.servers.isEmpty() && !vpnState.isLoadingServers) {
            vpnViewModel.loadServers()
        }
    }

    // Handle deep links after auth is resolved
    LaunchedEffect(deepLinkRoute, authState.isLoggedIn, authState.isLoading) {
        if (deepLinkRoute != null && !authState.isLoading && authState.isLoggedIn && hasConsented) {
            navController.navigate(deepLinkRoute) {
                launchSingleTop = true
            }
            onDeepLinkConsumed()
        }
    }

    // Determine if bottom bar should be visible
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = authState.isLoggedIn && currentRoute in listOf(
        Screen.Profile.route,
        Screen.Home.route,
        Screen.ServerList.route,
        Screen.Settings.route,
    )

    val palette = BirdoColors.current
    Scaffold(
        containerColor = palette.background,
        bottomBar = {
            if (showBottomBar) {
                // ── Bottom nav ──────────────────────────────────────
                Column {
                    HorizontalDivider(color = palette.hairlineSoft, thickness = 1.dp)
                    NavigationBar(
                        containerColor = palette.surface,
                        tonalElevation = 0.dp,
                        modifier = Modifier
                            .background(palette.surface)
                            .height(72.dp),
                    ) {
                        bottomNavItems.forEach { item ->
                            val isSelected = navBackStackEntry?.destination?.hierarchy?.any {
                                it.route == item.screen.route
                            } == true

                            NavigationBarItem(
                                selected = isSelected,
                                onClick = {
                                    navController.navigate(item.screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        item.icon,
                                        contentDescription = stringResource(item.labelRes),
                                        modifier = Modifier.size(22.dp),
                                    )
                                },
                                label = {
                                    Text(
                                        stringResource(item.labelRes),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = palette.accent,
                                    selectedTextColor = palette.accent,
                                    unselectedIconColor = palette.onSurfaceMuted,
                                    unselectedTextColor = palette.onSurfaceMuted,
                                    indicatorColor = palette.accent.copy(alpha = if (palette.isLight) 0.10f else 0.18f),
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { scaffoldPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // ── Pixel canvas background (dark theme only) ──
            if (!palette.isLight) {
                PixelCanvas()
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // ── Offline banner ──────────────────────────────────
                AnimatedVisibility(visible = !isOnline) {
                    Surface(
                        color = BirdoRed.copy(alpha = 0.9f),
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.OFFLINE_BANNER),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = BirdoWhite,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.offline_banner),
                                color = BirdoWhite,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                NavHost(
                navController = navController,
                startDestination = when {
                    !hasConsented -> Screen.Consent.route
                    authState.isLoggedIn -> Screen.Home.route
                    else -> Screen.Login.route
                },
                modifier = Modifier.padding(scaffoldPadding),
            ) {
            // ── GDPR Consent ─────────────────────────────────────────
            composable(
                Screen.Consent.route,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
            ) {
                AdaptiveContainer {
                    val consentContext = androidx.compose.ui.platform.LocalContext.current
                    ConsentScreen(
                        onAccept = {
                            appPreferences.hasAcceptedPrivacyPolicy = true
                            appPreferences.privacyConsentTimestamp = System.currentTimeMillis()
                            hasConsented = true
                        },
                        onDecline = {
                            // Close the app if user declines
                            (consentContext as? android.app.Activity)?.finishAffinity()
                        },
                    )
                }
            }

            // ── Login ────────────────────────────────────────────────
            composable(
                Screen.Login.route,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
            ) {
                AdaptiveContainer {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    LoginScreen(
                        isLoading = authState.isLoading,
                        error = authState.error,
                        requiresTwoFactor = authState.requiresTwoFactor,
                        onLogin = { email, password -> authViewModel.login(email, password) },
                        onVerifyTwoFactor = { code -> authViewModel.verifyTwoFactor(code) },
                        onClearError = { authViewModel.clearError() },
                        onCancelTwoFactor = { authViewModel.cancelTwoFactor() },
                        onLoginAnonymous = {
                            val deviceId = android.provider.Settings.Secure.getString(
                                context.contentResolver,
                                android.provider.Settings.Secure.ANDROID_ID,
                            ) ?: java.util.UUID.randomUUID().toString()
                            authViewModel.loginAnonymous(deviceId)
                        },
                        onSignUp = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://birdo.app/login"),
                            )
                            context.startActivity(intent)
                        },
                    )
                }
            }

            // ── Home (Connect tab) ──────────────────────────────────
            composable(
                Screen.Home.route,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
            ) {
                AdaptiveContainer {
                    HomeScreen(
                        state = vpnState,
                        userEmail = authState.user?.email,
                        killSwitchEnabled = settingsState.killSwitchEnabled,
                        favoriteServers = vpnViewModel.favoriteServers.collectAsState().value,
                        onConnect = { vpnViewModel.connect() },
                        onDisconnect = { vpnViewModel.disconnect() },
                        onSelectServer = { vpnViewModel.selectServer(it) },
                        onToggleFavorite = { vpnViewModel.toggleFavorite(it) },
                        onRefreshServers = { vpnViewModel.loadServers(forceRefresh = true) },
                        onOpenServers = {
                            navController.navigate(Screen.ServerList.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onLogout = {
                            vpnViewModel.disconnect()
                            authViewModel.logout()
                        },
                    )
                }
            }

            // ── Profile (Profile tab) ───────────────────────────────
            composable(
                Screen.Profile.route,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
            ) {
                AdaptiveContainer {
                    val context = LocalContext.current
                    ProfileScreen(
                        user = authState.user,
                        subscription = vpnState.subscription,
                        isConnected = vpnState.vpnState is app.birdo.vpn.service.VpnState.Connected,
                        publicIp = vpnState.publicIp,
                        onSubscription = {
                            vpnViewModel.fetchSubscription()
                            navController.navigate(Screen.Subscription.route)
                        },
                        onRedeemVoucher = {
                            vpnViewModel.fetchSubscription()
                            navController.navigate(Screen.Subscription.route)
                        },
                        onManageOnWeb = {
                            settingsViewModel.openUrl("https://birdo.app/dashboard")
                        },
                        onLogout = {
                            vpnViewModel.disconnect()
                            authViewModel.logout()
                        },
                    )
                }
            }

            // ── Server list (Servers tab) ───────────────────────────
            composable(
                Screen.ServerList.route,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
            ) {
                AdaptiveContainer {
                    ServerListScreen(
                        servers = vpnState.servers,
                        selectedServer = vpnState.selectedServer,
                        isLoading = vpnState.isLoadingServers,
                        favoriteServers = vpnViewModel.favoriteServers.collectAsState().value,
                        onSelectServer = { vpnViewModel.selectServer(it) },
                        onToggleFavorite = { vpnViewModel.toggleFavorite(it) },
                        onRefresh = { vpnViewModel.loadServers(forceRefresh = true) },
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // ── Settings (Settings tab) ─────────────────────────────
            composable(
                Screen.Settings.route,
                enterTransition = { fadeIn() },
                exitTransition = { fadeOut() },
            ) {
                AdaptiveContainer {
                    val context = LocalContext.current
                    SettingsScreen(
                        state = settingsState,
                        onKillSwitchChange = { settingsViewModel.setKillSwitch(it) },
                        onAutoConnectChange = { settingsViewModel.setAutoConnect(it) },
                        onNotificationsChange = { settingsViewModel.setNotifications(it) },
                        onShowIpInNotificationChange = { settingsViewModel.setShowIpInNotification(it) },
                        onShowLocationInNotificationChange = { settingsViewModel.setShowLocationInNotification(it) },
                        onOpenNotificationSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                            )
                        },
                        onSplitTunnelingChange = { settingsViewModel.setSplitTunneling(it) },
                        onToggleAppExclude = { settingsViewModel.toggleAppExclusion(it) },
                        onOpenSplitTunnelApps = {
                            navController.navigate(Screen.SplitTunnel.route)
                        },
                        onOpenVpnSettings = {
                            navController.navigate(Screen.VpnSettings.route)
                        },
                        onOpenUrl = { settingsViewModel.openUrl(it) },
                        onDeleteAccount = { password ->
                            vpnViewModel.disconnect()
                            authViewModel.deleteAccount(password)
                        },
                        isDeletingAccount = authState.isDeletingAccount,
                        deleteAccountError = authState.deleteAccountError,
                        onClearDeleteError = { authViewModel.clearDeleteAccountError() },
                        onBiometricLockChange = { settingsViewModel.setBiometricLock(it) },
                        onThemeModeChange = { settingsViewModel.setThemeMode(it) },
                        onOpenSubscription = {
                            vpnViewModel.fetchSubscription()
                            navController.navigate(Screen.Subscription.route)
                        },
                        onOpenMultiHop = {
                            navController.navigate(Screen.MultiHop.route)
                        },
                        onOpenPortForward = {
                            navController.navigate(Screen.PortForward.route)
                        },
                        onOpenSpeedTest = {
                            navController.navigate(Screen.SpeedTest.route)
                        },
                    )
                }
            }

            // ── VPN Settings ───────────────────────────────────────
            composable(
                Screen.VpnSettings.route,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
            ) {
                AdaptiveContainer {
                    VpnSettingsScreen(
                        state = settingsState,
                        onLocalNetworkSharingChange = { settingsViewModel.setLocalNetworkSharing(it) },
                        onCustomDnsEnabledChange = { settingsViewModel.setCustomDnsEnabled(it) },
                        onCustomDnsPrimaryChange = { settingsViewModel.setCustomDnsPrimary(it) },
                        onCustomDnsSecondaryChange = { settingsViewModel.setCustomDnsSecondary(it) },
                        onWireGuardPortChange = { settingsViewModel.setWireGuardPort(it) },
                        onWireGuardMtuChange = { settingsViewModel.setWireGuardMtu(it) },
                        onStealthModeChange = { settingsViewModel.setStealthMode(it) },
                        onQuantumProtectionChange = { settingsViewModel.setQuantumProtection(it) },
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // ── Multi-Hop (Double VPN) ──────────────────────────────
            composable(
                Screen.MultiHop.route,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
            ) {
                AdaptiveContainer {
                    MultiHopScreen(
                        servers = vpnState.servers,
                        isConnecting = vpnState.vpnState is app.birdo.vpn.service.VpnState.Connecting,
                        error = vpnState.error,
                        onConnect = { entry, exit -> vpnViewModel.connectMultiHop(entry, exit) },
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // ── Port Forwarding ─────────────────────────────────────
            composable(
                Screen.PortForward.route,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
            ) {
                LaunchedEffect(Unit) {
                    vpnViewModel.loadPortForwards()
                }
                AdaptiveContainer {
                    PortForwardScreen(
                        portForwards = vpnState.portForwards,
                        isLoading = vpnState.isLoadingPortForwards,
                        error = vpnState.error,
                        onCreate = { port, protocol -> vpnViewModel.createPortForward(port, protocol) },
                        onDelete = { id -> vpnViewModel.deletePortForward(id) },
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // ── Split Tunnel app selection ──────────────────────────
            composable(
                Screen.SplitTunnel.route,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
            ) {
                // Load apps when entering screen
                LaunchedEffect(Unit) {
                    settingsViewModel.loadInstalledApps()
                }

                AdaptiveContainer {
                    SplitTunnelScreen(
                        apps = settingsState.installedApps,
                        isLoading = settingsState.isLoadingApps,
                        onToggleApp = { settingsViewModel.toggleAppExclusion(it) },
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            // ── Subscription ────────────────────────────────────────
            composable(
                Screen.Subscription.route,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
            ) {
                AdaptiveContainer {
                    SubscriptionScreen(
                        currentSubscription = vpnState.subscription,
                        onNavigateBack = { navController.popBackStack() },
                        onSelectPlan = { planId ->
                            settingsViewModel.openUrl("https://birdo.app/dashboard/billing?plan=$planId")
                        },
                        onManageOnWeb = {
                            settingsViewModel.openUrl("https://birdo.app/dashboard/billing")
                        },
                        onRedeemVoucher = { code, onResult ->
                            vpnViewModel.redeemVoucher(code, onResult)
                        },
                    )
                }
            }

            // ── Speed Test ─────────────────────────────────────────
            composable(
                Screen.SpeedTest.route,
                enterTransition = { slideInHorizontally(initialOffsetX = { it }) },
                exitTransition = { slideOutHorizontally(targetOffsetX = { it }) },
            ) {
                AdaptiveContainer {
                    app.birdo.vpn.ui.screen.SpeedTestScreen(
                        onNavigateBack = { navController.popBackStack() },
                    )
                }
            }
        }
        } // end Column
        } // end Box
    }
}
