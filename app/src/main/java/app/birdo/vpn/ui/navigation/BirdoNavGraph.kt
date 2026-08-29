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
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.birdo.vpn.BuildConfig
import app.birdo.vpn.R
import app.birdo.vpn.data.network.NetworkMonitor
import app.birdo.vpn.service.VpnState
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.ui.components.AdaptiveContainer
import app.birdo.vpn.billing.BirdoBillingPeriod
import app.birdo.vpn.billing.PurchasableOffer
import app.birdo.vpn.billing.StorefrontState
import app.birdo.vpn.ui.components.BillingChoice
import app.birdo.vpn.ui.components.BirdoBillingChoiceSheet
import app.birdo.vpn.ui.viewmodel.BillingViewModel
import app.birdo.vpn.ui.components.PixelCanvas
import app.birdo.vpn.ui.screen.*
import app.birdo.vpn.ui.TestTags
import app.birdo.vpn.ui.theme.*
import app.birdo.vpn.ui.viewmodel.AuthViewModel
import app.birdo.vpn.ui.viewmodel.SettingsViewModel
import app.birdo.vpn.ui.viewmodel.UpdateViewModel
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
    // Data usage / plan — shown for ALL user types (anon, SSO, email). The screen
    // renders the free-tier cap gauge for RECON and an "Unlimited" state for paid.
    BottomNavItem(Screen.Limit, R.string.limit, Icons.Default.Speed),
    BottomNavItem(Screen.Settings, R.string.settings_title, Icons.Default.Settings),
)

// ── Shared navigation transitions (BirdoMotion rhythm) ──────────────────
// Tabs crossfade with a whisper of scale; pushed sub-screens slide in from
// the right and slide back out on pop, while the screen beneath dims.
private val tabEnter: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = androidx.compose.animation.core.tween(BirdoMotion.Standard, easing = BirdoMotion.Decel)) +
        scaleIn(
            initialScale = 0.98f,
            animationSpec = androidx.compose.animation.core.tween(BirdoMotion.Standard, easing = BirdoMotion.Decel),
        )
}
private val tabExit: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = androidx.compose.animation.core.tween(BirdoMotion.Quick, easing = BirdoMotion.Accel))
}
private val pushEnter: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(
        animationSpec = androidx.compose.animation.core.tween(BirdoMotion.Emphasis, easing = BirdoMotion.Decel),
        initialOffsetX = { it },
    ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(BirdoMotion.Standard, easing = BirdoMotion.Decel))
}
private val pushPopExit: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(
        animationSpec = androidx.compose.animation.core.tween(BirdoMotion.Emphasis, easing = BirdoMotion.Accel),
        targetOffsetX = { it },
    ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(BirdoMotion.Standard, easing = BirdoMotion.Accel))
}
private val underExit: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(animationSpec = androidx.compose.animation.core.tween(BirdoMotion.Quick, easing = BirdoMotion.Accel))
}
private val underPopEnter: AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(animationSpec = androidx.compose.animation.core.tween(BirdoMotion.Standard, easing = BirdoMotion.Decel))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirdoNavGraph(
    onRequestVpnPermission: (android.content.Intent) -> Unit,
    appPreferences: AppPreferences,
    networkMonitor: NetworkMonitor,
    deepLinkRoute: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    /** Native-SSO redirect payload (code, state) from birdo://auth. */
    oauthCallback: Pair<String, String>? = null,
    onOauthConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val vpnViewModel: VpnViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val updateViewModel: UpdateViewModel = hiltViewModel()
    // Hoisted to the graph, not the Subscription route: a purchase made while
    // signed out can only be bound once a session exists, and that moment is
    // here, not on a screen the user may never open.
    val billingViewModel: BillingViewModel = hiltViewModel()
    var hasConsented by remember { mutableStateOf(appPreferences.hasAcceptedPrivacyPolicy) }
    val isOnline by networkMonitor.isOnline.collectAsState(initial = true)

    val authState by authViewModel.uiState.collectAsState()
    val vpnState by vpnViewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val updateState by updateViewModel.state.collectAsState()
    val billingState by billingViewModel.state.collectAsState()

    // A session has appeared. Re-present anything Play still considers current
    // so a subscription bought before signing in (or on another device, or on a
    // previous install) binds itself with no user action.
    LaunchedEffect(authState.isLoggedIn) {
        if (authState.isLoggedIn) billingViewModel.onSignedIn()
    }

    // The server accepted an entitlement: re-read the plan snapshot so every
    // plan gate in the app opens without a restart. Graph-scoped because a
    // deferred approval can land while the user is anywhere in the app.
    LaunchedEffect(Unit) {
        billingViewModel.entitlementChanged.collect {
            vpnViewModel.fetchSubscription(forceRefresh = true)
        }
    }

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
                // Idempotency guard: the cold-start effect below shares the same
                // keys and may also trigger a load. Skip the load if servers are
                // already present or a load is in flight so the two effects don't
                // both fire a redundant GET /vpn/servers on the login → Home flip.
                if (vpnState.servers.isEmpty() && !vpnState.isLoadingServers) {
                    vpnViewModel.loadServers()
                }
                vpnViewModel.fetchSubscription()
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

    // Pre-fetch subscription on cold start so the Profile tab never shows the
    // "RECON" placeholder before the real plan loads. Cheap (cached for 30s).
    LaunchedEffect(authState.isLoggedIn) {
        if (authState.isLoggedIn && vpnState.subscription == null) {
            vpnViewModel.fetchSubscription()
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

    // Native SSO: the birdo://auth redirect brings back (code, state) — hand it
    // to the ViewModel to exchange for tokens. Fires regardless of login state
    // (the whole point is to log in). Consumed once so a recomposition can't
    // replay the single-use code.
    LaunchedEffect(oauthCallback) {
        val cb = oauthCallback
        if (cb != null) {
            authViewModel.completeSso(cb.first, cb.second)
            onOauthConsumed()
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
        Screen.Limit.route,
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
                        modifier = Modifier.background(palette.surface),
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
            // Pixel canvas background — enabled on both themes (light is now "dim")
            PixelCanvas()

            Column(modifier = Modifier.fillMaxSize()) {
                // ── Offline banner ──────────────────────────────────
                // Suppress while the tunnel is up/coming up: if the VPN is
                // connected (or mid-handover during a switch) there is a working
                // path by definition, so a transient network re-evaluation must
                // never surface a false "No Internet" banner.
                val vpnActive = vpnState.vpnState == VpnState.Connected ||
                    vpnState.vpnState is VpnState.Reconnecting ||
                    vpnState.vpnState == VpnState.Connecting
                AnimatedVisibility(visible = !isOnline && !vpnActive) {
                    // Full-bleed red surface; content is inset below the status bar
                    // so the text is never hidden behind the notch/notification area.
                    Surface(
                        color = BirdoRed.copy(alpha = 0.95f),
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.OFFLINE_BANNER),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = BirdoWhite,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.offline_banner),
                                color = BirdoWhite,
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.weight(1f),
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
                enterTransition = tabEnter,
                exitTransition = tabExit,
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
                enterTransition = tabEnter,
                exitTransition = tabExit,
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
                        onLoginAnonymous = { anonymousId, password ->
                            authViewModel.loginAnonymous(anonymousId, password)
                        },
                        onSignUp = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://birdo.app/login"),
                            )
                            context.startActivity(intent)
                        },
                        onSsoLogin = { provider -> authViewModel.startSso(provider, context) },
                        onCreateAnonymous = { authViewModel.registerAnonymous() },
                        // Creating an anonymous account deliberately does NOT flip
                        // isLoggedIn, so this route stays put and shows the minted
                        // 24-digit ID until the user confirms saving it. It is the
                        // account's only credential and the server returns it once.
                        pendingAnonymousId = authState.pendingAnonymousId,
                        onAcknowledgeAnonymousId = { authViewModel.acknowledgeAnonymousId() },
                    )
                }
            }

            // ── Home (Connect tab) ──────────────────────────────────
            composable(
                Screen.Home.route,
                enterTransition = tabEnter,
                exitTransition = tabExit,
            ) {
                val context = LocalContext.current
                AdaptiveContainer {
                    HomeScreen(
                        state = vpnState,
                        trafficStats = vpnViewModel.trafficStats.collectAsState().value,
                        userEmail = authState.user?.email,
                        killSwitchEnabled = settingsState.killSwitchEnabled,
                        favoriteServers = vpnViewModel.favoriteServers.collectAsState().value,
                        multiHop = vpnViewModel.multiHop.collectAsState().value,
                        onMultiHopChange = { enabled, entryId, exitId ->
                            vpnViewModel.setMultiHopSelection(enabled, entryId, exitId)
                        },
                        onConnect = { vpnViewModel.connect() },
                        onConnectMultiHop = { entry, exit -> vpnViewModel.connectMultiHop(entry, exit) },
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
                        updateInfo = updateState.info,
                        showUpdateBanner = updateState.showBanner,
                        onUpdateApp = {
                            // Play installs go to the store listing; direct and
                            // F-Droid installs to the release APK.
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(updateViewModel.updateTargetUrl()),
                            )
                            runCatching { context.startActivity(intent) }
                        },
                        onDismissUpdate = { updateViewModel.dismiss() },
                    )
                }
            }

            // ── Profile (Profile tab) ───────────────────────────────
            composable(
                Screen.Profile.route,
                enterTransition = tabEnter,
                exitTransition = tabExit,
            ) {
                AdaptiveContainer {
                    val context = LocalContext.current
                    // Refresh subscription whenever the Profile tab gains focus so the
                    // displayed plan is always current (no stale RECON → SOVEREIGN flicker).
                    LaunchedEffect(Unit) { vpnViewModel.fetchSubscription() }
                    ProfileScreen(
                        user = authState.user,
                        subscription = vpnState.subscription,
                        isConnected = vpnState.vpnState is app.birdo.vpn.service.VpnState.Connected,
                        publicIp = vpnState.publicIp,
                        onSubscription = {
                            vpnViewModel.fetchSubscription()
                            navController.navigate(Screen.Subscription.route)
                        },
                        onRedeemVoucher = { code, onResult ->
                            vpnViewModel.redeemVoucher(code, onResult)
                        },
                        onManageOnWeb = {
                            // Play build: the row is hidden (isPlayBuild), so this
                            // is a defensive no-op — no external billing steering.
                            if (!BuildConfig.IS_PLAY_BUILD) {
                                settingsViewModel.openUrl("https://dashboard.birdo.app/")
                            }
                        },
                        onLogout = {
                            vpnViewModel.disconnect()
                            authViewModel.logout()
                        },
                        onOpenUrl = { settingsViewModel.openUrl(it) },
                        onDeleteAccount = { password ->
                            vpnViewModel.disconnect()
                            authViewModel.deleteAccount(password)
                        },
                        isDeletingAccount = authState.isDeletingAccount,
                        deleteAccountError = authState.deleteAccountError,
                        onClearDeleteError = { authViewModel.clearDeleteAccountError() },
                    )
                }
            }

            // ── Data limit (anon-only tab) ──────────────────────────
            composable(
                Screen.Limit.route,
                enterTransition = tabEnter,
                exitTransition = tabExit,
            ) {
                AdaptiveContainer {
                    // Force a live read on focus so the meter isn't a 30s-stale
                    // cached figure when the user deliberately opens their usage.
                    LaunchedEffect(Unit) { vpnViewModel.fetchSubscription(forceRefresh = true) }
                    LimitScreen(
                        user = authState.user,
                        subscription = vpnState.subscription,
                        onRefresh = { vpnViewModel.fetchSubscription(forceRefresh = true) },
                        onUpgrade = {
                            vpnViewModel.fetchSubscription()
                            navController.navigate(Screen.Subscription.route)
                        },
                    )
                }
            }

            // ── Server list (Servers tab) ───────────────────────────
            composable(
                Screen.ServerList.route,
                enterTransition = tabEnter,
                exitTransition = tabExit,
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
                        // selectServer can REFUSE (live Multi-Hop downgrade).
                        // HomeScreen already renders this; without it here the
                        // refusal was silent on the surface that triggers it.
                        errorMessage = vpnState.error,
                    )
                }
            }

            // ── Settings (Settings tab) ─────────────────────────────
            composable(
                Screen.Settings.route,
                enterTransition = tabEnter,
                exitTransition = tabExit,
            ) {
                AdaptiveContainer {
                    val context = LocalContext.current
                    SettingsScreen(
                        state = settingsState,
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
                        onOpenSplitTunnelApps = {
                            navController.navigate(Screen.SplitTunnel.route)
                        },
                        onOpenVpnSettings = {
                            navController.navigate(Screen.VpnSettings.route)
                        },
                        onBiometricLockChange = { settingsViewModel.setBiometricLock(it) },
                        onThemeModeChange = { settingsViewModel.setThemeMode(it) },
                    )
                }
            }

            // ── VPN Settings ───────────────────────────────────────
            composable(
                Screen.VpnSettings.route,
                enterTransition = pushEnter,
                exitTransition = underExit,
                popEnterTransition = underPopEnter,
                popExitTransition = pushPopExit,
            ) {
                AdaptiveContainer {
                    // Commit pending text-field edits (MTU/port/DNS) as ONE
                    // reapply blip when this screen is left, so the live tunnel
                    // rebuilds with the final value, not every half-typed one.
                    DisposableEffect(Unit) {
                        onDispose { settingsViewModel.commitPendingReapply() }
                    }
                    // Plan gating mirrors the Multi-Hop pattern on the Connect
                    // screen: Custom DNS and Port Forwarding are SOVEREIGN-only.
                    // Post-quantum protection is a FREE-tier feature (per the
                    // pricing/feature lists) so it stays ungated for everyone.
                    // Locked toggles route to the upgrade flow instead of toggling.
                    val plan = vpnState.subscription?.plan?.uppercase()
                    val isSovereign = plan == "SOVEREIGN"
                    // Stealth + Split tunnel are OPERATIVE-and-above. Keyed on the
                    // plan string only — an anonymous account on RECON is gated
                    // exactly like an email/SSO account on RECON.
                    val isOperativeOrAbove = plan == "OPERATIVE" || plan == "SOVEREIGN"
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
                        onKillSwitchChange = { settingsViewModel.setKillSwitch(it) },
                        onOpenPortForward = { navController.navigate(Screen.PortForward.route) },
                        onBack = { navController.popBackStack() },
                        stealthUnlocked = isOperativeOrAbove,
                        customDnsUnlocked = isSovereign,
                        portForwardUnlocked = isSovereign,
                        quantumUnlocked = true,
                        onUpgradeRequired = {
                            vpnViewModel.fetchSubscription()
                            navController.navigate(Screen.Subscription.route)
                        },
                    )
                }
            }

            // ── Port Forwarding ─────────────────────────────────────
            composable(
                Screen.PortForward.route,
                enterTransition = pushEnter,
                exitTransition = underExit,
                popEnterTransition = underPopEnter,
                popExitTransition = pushPopExit,
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
                enterTransition = pushEnter,
                exitTransition = underExit,
                popEnterTransition = underPopEnter,
                popExitTransition = pushPopExit,
            ) {
                // Load apps when entering screen
                LaunchedEffect(Unit) {
                    settingsViewModel.loadInstalledApps()
                }
                // Apply the whole app-selection edit as one blip on exit.
                DisposableEffect(Unit) {
                    onDispose { settingsViewModel.commitPendingReapply() }
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
                enterTransition = pushEnter,
                exitTransition = underExit,
                popEnterTransition = underPopEnter,
                popExitTransition = pushPopExit,
            ) {
                AdaptiveContainer {
                    // Purchase routing has THREE distinct cases, and conflating
                    // them is how an app gets pulled from the store:
                    //
                    //  1. Not a Play build (direct APK / F-Droid) — always free to
                    //     open the web checkout. Unchanged behaviour.
                    //  2. Play build, NOT enrolled in external offers — Google Play
                    //     Billing is the only purchase route, and there is no
                    //     steering anywhere else.
                    //  3. Play build, ENROLLED — may offer a side-by-side choice
                    //     between Play Billing and the web checkout.
                    //
                    // Case 3 requires BOTH flags. Being a Play build is deliberately
                    // not sufficient on its own: unenrolled steering is a policy
                    // violation and the package name does not survive a removal.
                    val externalOffersAllowed =
                        BuildConfig.IS_PLAY_BUILD && BuildConfig.PLAY_EXTERNAL_OFFERS
                    val openWebCheckout = {
                        settingsViewModel.openUrl("https://dashboard.birdo.app/dashboard/billing")
                    }
                    var showBillingChoice by rememberSaveable { mutableStateOf(false) }
                    val billingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                    // launchBillingFlow needs a real Activity. LocalActivity
                    // would be tidier but arrived in activity-compose 1.10 and
                    // this module is pinned to 1.9.3, so unwrap the context.
                    val activity = LocalContext.current.findActivity()

                    // Refresh the catalogue when the screen opens. The LISTENER is
                    // not started here — it lives in BirdoApp so that a deferred
                    // approval landing minutes later is still linked.
                    LaunchedEffect(Unit) { billingViewModel.refresh() }

                    // Which period the sheet is offering, so a Play-Billing pick
                    // knows what the user actually chose.
                    var pendingPurchase by remember { mutableStateOf<Pair<String, String>?>(null) }

                    // Resolve a plan card + period toggle to a REAL offer, read
                    // from the collected state so the UI recomposes when the
                    // catalogue arrives. Null means not purchasable, and null is
                    // what suppresses the CTA.
                    val offerFor: (String, String) -> PurchasableOffer? = { planId, period ->
                        BirdoBillingPeriod.fromKey(period)
                            ?.let { billingState.offerFor(planId, it) }
                    }

                    // The ONE place a Play purchase is started from the UI. It
                    // takes a resolved offer, so it cannot be reached for
                    // something Play never returned.
                    val startPlayPurchase: (String, String) -> Unit = { planId, period ->
                        val offer = offerFor(planId, period)
                        if (offer != null && activity != null) {
                            billingViewModel.purchase(activity, offer) {
                                navController.navigate(Screen.Login.route)
                            }
                        }
                    }

                    val storefront = billingState.storefront

                    SubscriptionScreen(
                        currentSubscription = vpnState.subscription,
                        onNavigateBack = { navController.popBackStack() },
                        onSelectPlan = { planId, period ->
                            when {
                                externalOffersAllowed -> {
                                    pendingPurchase = planId to period
                                    showBillingChoice = true
                                }
                                BuildConfig.IS_PLAY_BUILD -> startPlayPurchase(planId, period)
                                else -> openWebCheckout()
                            }
                        },
                        // "Manage on web" is an account action, not a
                        // purchase, so it goes straight to the billing page
                        // rather than through the choice sheet — routing it
                        // through the sheet left a GooglePlay branch with no
                        // plan selected, which silently did nothing.
                        onManageOnWeb = {
                            if (!BuildConfig.IS_PLAY_BUILD || externalOffersAllowed) {
                                openWebCheckout()
                            }
                        },
                        isPlayBuild = BuildConfig.IS_PLAY_BUILD,
                        // An ENROLLED build must keep a route to the web
                        // checkout even when Play has nothing to sell —
                        // otherwise an empty storefront leaves an enrolled user
                        // with no purchase route at all. An UNENROLLED Play
                        // build must never show it: that is the steering that
                        // gets an app removed.
                        showWebManageAction =
                            !BuildConfig.IS_PLAY_BUILD || externalOffersAllowed,
                        // NULL when that plan+period is not purchasable, which is
                        // what suppresses the CTA. See SubscriptionScreen.
                        // Play returns a bare localised amount ("£3.99"); the
                        // period it recurs on comes from the base plan, so the
                        // two are joined here rather than hardcoded anywhere.
                        playPriceFor = { planId, period ->
                            offerFor(planId, period)?.let { offer ->
                                offer.formattedPrice + offer.period.priceSuffix
                            }
                        },
                        storefrontMessage =
                            (storefront as? StorefrontState.Unavailable)?.message,
                        storefrontLoading = storefront is StorefrontState.Loading,
                        storefrontCanRetry =
                            (storefront as? StorefrontState.Unavailable)?.canRetry == true,
                        onRetryStorefront = { billingViewModel.refresh() },
                        onRestorePurchases = { billingViewModel.restorePurchases() },
                        isRestoring = billingState.isRestoring,
                        billingMessage = billingState.notice?.text,
                        billingIsError = billingState.notice?.isError == true,
                        billingIsPurchasing = billingState.purchasingProductId != null,
                        onClearBillingMessage = { billingViewModel.dismissNotice() },
                        duplicateBillingMessage = billingState.duplicateBilling?.message,
                        onDismissDuplicateBilling = {
                            billingViewModel.dismissDuplicateBilling()
                        },
                    )

                    if (showBillingChoice) {
                        BirdoBillingChoiceSheet(
                            sheetState = billingSheetState,
                            onChoose = { choice ->
                                showBillingChoice = false
                                val target = pendingPurchase
                                pendingPurchase = null
                                when (choice) {
                                    BillingChoice.Web -> openWebCheckout()
                                    // Google Play Billing is now implemented. This
                                    // branch is only ever reachable in an enrolled
                                    // external-offers build, where the user is being
                                    // given a genuine choice between the two.
                                    BillingChoice.GooglePlay ->
                                        target?.let { startPlayPurchase(it.first, it.second) }
                                }
                            },
                            onDismiss = { showBillingChoice = false },
                        )
                    }
                }
            }

        }
        } // end Column
        } // end Box
    }
}

/**
 * The Activity behind a Compose [android.content.Context], or null.
 *
 * `LocalContext.current` is usually the Activity, but not always — a dialog or
 * a themed subtree hands out a ContextWrapper — so the chain is unwrapped
 * rather than cast. Returning null instead of throwing keeps a missing Activity
 * from crashing the screen; the purchase simply does not start.
 */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
