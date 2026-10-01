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
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import app.birdo.vpn.billing.PlaySubscriptionLinks
import app.birdo.vpn.billing.StorePurchaseGate
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
import app.birdo.vpn.utils.isAnonymousAccountEmail

/**
 * A2-004: the VPN as seen from Login — "Your VPN is still connected." and a
 * Disconnect — for a session that outlived the sign-in behind it.
 */
@Composable
private fun LoginVpnStatus(onDisconnect: () -> Unit) {
    val palette = BirdoColors.current
    Surface(
        color = palette.surface,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.login_vpn_still_on),
                color = palette.onSurface,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDisconnect) {
                Text(stringResource(R.string.disconnect), color = palette.accent)
            }
        }
    }
}

/** The four tabs, in the iOS order: Profile · Connect · Limit · Settings. */
private data class BottomNavItem(
    val screen: Screen,
    @param:StringRes val labelRes: Int,
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
    // The CURRENT consent text, not any version of it: a user who accepted the
    // pre-2026-09-29 screen (which misdescribed the servers and never asked
    // for the Terms) sees the corrected one once. See AppPreferences.
    var hasConsented by remember { mutableStateOf(appPreferences.hasAcceptedCurrentConsent) }
    val isOnline by networkMonitor.isOnline.collectAsState(initial = true)

    val authState by authViewModel.uiState.collectAsState()
    val vpnState by vpnViewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val updateState by updateViewModel.state.collectAsState()
    val billingState by billingViewModel.state.collectAsState()

    // A session has appeared. Re-present anything Play still considers current
    // so a subscription bought before signing in (or on another device, or on a
    // previous install) binds itself with no user action. Not before the
    // current consent: linking is a request to birdo.app (audit D-12).
    LaunchedEffect(authState.isLoggedIn, hasConsented) {
        if (authState.isLoggedIn && hasConsented) billingViewModel.onSignedIn()
    }

    // Signed out, for any reason (the button, a deletion, a dead session):
    // drop the previous account's servers, plan and selection so the next
    // account on this device never sees them (A2-003).
    LaunchedEffect(authState.isLoggedIn) {
        if (!authState.isLoggedIn) vpnViewModel.resetForSignOut()
    }

    // The server accepted an entitlement: re-read the plan snapshot AND the
    // servers, whose `accessible` flags are computed from the plan, so every
    // plan gate in the app opens without a restart (A2-003). Graph-scoped
    // because a deferred approval can land while the user is anywhere.
    LaunchedEffect(Unit) {
        billingViewModel.entitlementChanged.collect {
            vpnViewModel.onEntitlementChanged()
        }
    }

    // ── Session lifecycle ↔ VPN (A2-004, A2-005, A1-035) ────────
    // The account session ended on its own (a 401 the refresh could not fix,
    // seen by the profile check or by the VPN heartbeat): each side tells the
    // other, so Login explains itself and the VPN stops trying to recover a
    // session nobody can re-authorise. Neither call loops: both are no-ops
    // the second time.
    LaunchedEffect(authState.sessionExpired) {
        if (authState.sessionExpired) vpnViewModel.onSessionExpired()
    }
    LaunchedEffect(vpnState.sessionExpired) {
        if (vpnState.sessionExpired) authViewModel.onSessionExpired()
    }
    LaunchedEffect(authState.isLoggedIn) {
        if (authState.isLoggedIn) vpnViewModel.onSignedIn()
    }
    // Deletion tears the tunnel down only once the server has CONFIRMED it
    // (A2-005): a mistyped password used to disconnect the VPN and then fail.
    LaunchedEffect(authState.accountDeleted) {
        if (authState.accountDeleted) vpnViewModel.onAccountDeleted()
    }

    // Handle VPN permission requests. The ViewModel replays the exact dial
    // that asked (A1-007), including when no prompt is needed after all.
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
    // Held back until the current consent is accepted (A2-028).
    LaunchedEffect(authState.isLoggedIn, hasConsented) {
        if (authState.isLoggedIn && hasConsented && vpnState.subscription == null) {
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

    // The offline banner owns the status-bar inset while it is up; the screens
    // below it must not add a second one.
    val vpnActive = vpnState.vpnState == VpnState.Connected ||
        vpnState.vpnState is VpnState.Reconnecting ||
        vpnState.vpnState == VpnState.Connecting
    val showOfflineBanner = !isOnline && !vpnActive

    val palette = BirdoColors.current
    Scaffold(
        containerColor = palette.background,
        // INSETS ARE OWNED BY THE SCREENS (A2-010). The default here (the
        // system bars) padded the NavHost by the status-bar height, and then
        // every top bar added `statusBarsPadding()` again: a blank band above
        // every header, visible in the Play screenshot. Now this Scaffold pads
        // only for the bottom bar, and that padding is CONSUMED below, so a
        // tab's own Scaffold does not add the navigation-bar inset a second
        // time either. Top bars (BirdoTopBar, the Home bar) take the status bar;
        // screens without one (Profile, Limit, Login, Consent) inset themselves.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                                    // The label below already names the tab;
                                    // a description here made TalkBack say it twice.
                                    Icon(
                                        item.icon,
                                        contentDescription = null,
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
            // Pixel canvas background — enabled on both themes (light is now
            // "dim"). Not on the Connect tab: the globe paints over every pixel
            // of it there, and it kept redrawing underneath at 15 Hz (A2-019).
            // Faded rather than cut so a tab switch does not pop.
            AnimatedVisibility(
                visible = currentRoute != Screen.Home.route,
                enter = fadeIn(androidx.compose.animation.core.tween(BirdoMotion.Standard)),
                exit = fadeOut(androidx.compose.animation.core.tween(BirdoMotion.Standard)),
            ) {
                PixelCanvas()
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // ── Offline banner ──────────────────────────────────
                // Suppress while the tunnel is up/coming up: if the VPN is
                // connected (or mid-handover during a switch) there is a working
                // path by definition, so a transient network re-evaluation must
                // never surface a false "No Internet" banner.
                AnimatedVisibility(visible = showOfflineBanner) {
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
                modifier = Modifier
                    .padding(scaffoldPadding)
                    .consumeWindowInsets(scaffoldPadding)
                    .then(
                        if (showOfflineBanner) Modifier.consumeWindowInsets(WindowInsets.statusBars)
                        else Modifier,
                    ),
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
                        onAccept = { crashReportsEnabled ->
                            appPreferences.hasAcceptedPrivacyPolicy = true
                            appPreferences.acceptedConsentVersion = AppPreferences.CURRENT_CONSENT_VERSION
                            appPreferences.privacyConsentTimestamp = System.currentTimeMillis()
                            // The optional crash-report choice. Persisted, then
                            // applied by BirdoApp: the SDK starts only if the
                            // user switched it on, and stays off otherwise.
                            settingsViewModel.setCrashReports(crashReportsEnabled)
                            hasConsented = true
                            // Everything the view models hold back until
                            // consent (audit D-12, A2-028): the two public
                            // calls, the session check, auto-connect and the
                            // plan fetch.
                            updateViewModel.check()
                            vpnViewModel.fetchClientConfig()
                            authViewModel.onConsentAccepted()
                            vpnViewModel.onConsentAccepted()
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
                        // Same page the Windows client opens (P1-023), through
                        // the https-only, crash-guarded opener.
                        onForgotPassword = { settingsViewModel.openUrl(PASSWORD_RESET_URL) },
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
                // A2-004: a session that expired can leave the tunnel (or the
                // kill-switch block) up, and Login has no bottom bar to reach
                // Home from. Say so here, with the way to stop it.
                val vpnStillOn = (vpnState.vpnState !is VpnState.Disconnected &&
                    vpnState.vpnState !is VpnState.Error) || vpnState.killSwitchActive
                if (vpnStillOn) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        LoginVpnStatus(onDisconnect = { vpnViewModel.disconnect() })
                    }
                }
            }

            // ── Home (Connect tab) ──────────────────────────────────
            composable(
                Screen.Home.route,
                enterTransition = tabEnter,
                exitTransition = tabExit,
            ) {
                val context = LocalContext.current
                val openPlans = {
                    vpnViewModel.fetchSubscription()
                    navController.navigate(Screen.Subscription.route)
                }
                // Full-bleed: the globe fills a tablet window too, and
                // HomeScreen narrows only its controls (A2-048).
                AdaptiveContainer(fullBleed = true) {
                    HomeScreen(
                        state = vpnState,
                        trafficStats = vpnViewModel.trafficStats.collectAsState().value,
                        accountLabel = accountLabel(authState.user?.email),
                        isAnonymousAccount = isAnonymousAccountEmail(authState.user?.email),
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
                            vpnViewModel.disconnectForSignOut { authViewModel.logout() }
                        },
                        onOpenSettings = {
                            navController.navigate(Screen.Settings.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onDismissMessage = { vpnViewModel.dismissConnectError() },
                        updateInfo = updateState.info,
                        showUpdateBanner = updateState.showBanner,
                        onUpdateApp = {
                            // Play installs go to the store listing; direct and
                            // F-Droid installs to the release APK.
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                updateViewModel.updateTargetUrl().toUri(),
                            )
                            runCatching { context.startActivity(intent) }
                        },
                        onDismissUpdate = { updateViewModel.dismiss() },
                        onViewPlans = openPlans,
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
                            vpnViewModel.disconnectForSignOut { authViewModel.logout() }
                        },
                        onOpenUrl = { settingsViewModel.openUrl(it) },
                        onDeleteAccount = { password -> authViewModel.deleteAccount(password) },
                        isDeletingAccount = authState.isDeletingAccount,
                        deleteAccountError = authState.deleteAccountError,
                        isAnonymousAccount = isAnonymousAccountEmail(authState.user?.email),
                        onClearDeleteError = { authViewModel.clearDeleteAccountError() },
                        deletionPreflight = authState.deletionPreflight,
                        onDeleteDialogOpened = { authViewModel.loadDeletionPreflight() },
                    )
                }
            }

            // ── Data limit (every account type) ─────────────────────
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
                        subscription = vpnState.subscription,
                        isLoading = vpnState.isLoadingSubscription,
                        error = vpnState.subscriptionError,
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
                val multiHopArmed = vpnViewModel.multiHop.collectAsState().value.enabled
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
                        // The list's own load error first; otherwise a refusal
                        // from selectServer (live Multi-Hop downgrade), which
                        // HomeScreen also renders — without it here the refusal
                        // was silent on the surface that triggers it.
                        errorMessage = vpnState.serversError ?: vpnState.connectError,
                        // A live switch re-dials the selected node, so while a
                        // single-hop tunnel is up they are the same node.
                        connectedServerId = vpnState.selectedServer?.id?.takeIf {
                            vpnState.vpnState == VpnState.Connected && !multiHopArmed
                        },
                        onViewPlans = {
                            vpnViewModel.fetchSubscription()
                            navController.navigate(Screen.Subscription.route)
                        },
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
                    // Custom DNS moved onto this page, so its commit-on-exit
                    // moved with it: pending text-field edits apply as ONE
                    // reapply blip when the tab is left, not per keystroke.
                    DisposableEffect(Unit) {
                        onDispose { settingsViewModel.commitPendingReapply() }
                    }
                    // Port Forwarding is SOVEREIGN-only. Custom DNS Servers is
                    // on every plan (owner decision D6, 2026-10-01), and so is
                    // post-quantum protection (per the pricing/feature lists).
                    val settingsPlan = vpnState.subscription?.plan?.uppercase()
                    val settingsIsSovereign = settingsPlan == "SOVEREIGN"
                    SettingsScreen(
                        state = settingsState,
                        onDismissSettingsResetNotice = { settingsViewModel.dismissSettingsResetNotice() },
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
                        onCustomDnsEnabledChange = { settingsViewModel.setCustomDnsEnabled(it) },
                        onCustomDnsPrimaryChange = { settingsViewModel.setCustomDnsPrimary(it) },
                        onCustomDnsSecondaryChange = { settingsViewModel.setCustomDnsSecondary(it) },
                        onOpenPortForward = { navController.navigate(Screen.PortForward.route) },
                        onQuantumProtectionChange = { settingsViewModel.setQuantumProtection(it) },
                        onKillSwitchChange = { settingsViewModel.setKillSwitch(it) },
                        onBiometricLockChange = { settingsViewModel.setBiometricLock(it) },
                        onThemeModeChange = { settingsViewModel.setThemeMode(it) },
                        onCrashReportsChange = { settingsViewModel.setCrashReports(it) },
                        portForwardUnlocked = settingsIsSovereign,
                        quantumUnlocked = true,
                        onUpgradeRequired = {
                            vpnViewModel.fetchSubscription()
                            navController.navigate(Screen.Subscription.route)
                        },
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
                    // Commit pending text-field edits (MTU/port) as ONE reapply
                    // blip when this screen is left, so the live tunnel rebuilds
                    // with the final value, not every half-typed one.
                    //
                    // DNS moved to the Settings root and is committed by the
                    // effect there. There are now TWO callers of
                    // commitPendingReapply(); a field added to either screen
                    // needs the one on ITS screen, not this one.
                    DisposableEffect(Unit) {
                        onDispose { settingsViewModel.commitPendingReapply() }
                    }
                    // Refresh the BirdoShield fleet gate every time this screen
                    // opens, not once per process. VpnViewModel.init fetches it
                    // at cold start; if that fetch failed (offline launch, web
                    // deploy mid-flight) the row would stay ungated for the
                    // whole process lifetime — fail-open, but stale. This is the
                    // one screen that renders the gate, so it is the one place
                    // it has to be current. Twin of iOS's
                    // `.task { await settingsVM.refreshClientConfig() }`.
                    LaunchedEffect(Unit) { vpnViewModel.fetchClientConfig() }
                    // Plan gating mirrors the Multi-Hop pattern on the Connect
                    // screen. Stealth + Split tunnel are OPERATIVE-and-above,
                    // keyed on the plan string only — an anonymous account on
                    // RECON is gated exactly like an email/SSO account on RECON.
                    // A locked toggle routes to the upgrade flow instead of toggling.
                    val plan = vpnState.subscription?.plan?.uppercase()
                    val isOperativeOrAbove = plan == "OPERATIVE" || plan == "SOVEREIGN"
                    VpnSettingsScreen(
                        state = settingsState,
                        onLocalNetworkSharingChange = { settingsViewModel.setLocalNetworkSharing(it) },
                        onWireGuardPortChange = { settingsViewModel.setWireGuardPort(it) },
                        onWireGuardMtuChange = { settingsViewModel.setWireGuardMtu(it) },
                        onStealthModeChange = { settingsViewModel.setStealthMode(it) },
                        onDnsFilteringChange = { settingsViewModel.setDnsFiltering(it) },
                        onBack = { navController.popBackStack() },
                        stealthUnlocked = isOperativeOrAbove,
                        // BirdoShield fleet gate (see VpnUiState). Same shape as
                        // the plan gate above — server-side state, resolved
                        // here, passed down. `null` = not known yet = available.
                        dnsFilteringAvailable = vpnState.dnsFilteringAvailable,
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
                        error = vpnState.portForwardError,
                        onCreate = { port, protocol -> vpnViewModel.createPortForward(port, protocol) },
                        onDelete = { id -> vpnViewModel.deletePortForward(id) },
                        onBack = { navController.popBackStack() },
                        onRefresh = { vpnViewModel.loadPortForwards() },
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
                // Apply the whole app-selection edit as one blip on exit, and
                // let the icon bitmaps go with the screen (A2-040).
                DisposableEffect(Unit) {
                    onDispose {
                        settingsViewModel.commitPendingReapply()
                        settingsViewModel.clearInstalledApps()
                    }
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
                        // The gate again, at the one place a purchase starts:
                        // hiding the button is the UI half, this is the rule.
                        val paidElsewhere = BuildConfig.IS_PLAY_BUILD &&
                            StorePurchaseGate.paidElsewhere(
                                plan = vpnState.subscription?.plan,
                                source = vpnState.subscription?.source,
                                liveSources = vpnState.subscription?.liveSources,
                                thisStore = StorePurchaseGate.SOURCE_GOOGLE_PLAY,
                                thisStoreOwnsSubscription = billingState.ownsBirdoSubscription,
                            )
                        if (offer != null && activity != null && !paidElsewhere) {
                            billingViewModel.purchase(activity, offer) {
                                navController.navigate(Screen.Login.route)
                            }
                        }
                    }

                    val storefront = billingState.storefront

                    // One subscription per account (audit 2026-09-29, A-9 /
                    // A-16): a paid plan bought outside Google Play is managed
                    // where it was bought, never re-sold here.
                    val currentSub = vpnState.subscription
                    val purchaseManagedElsewhere = BuildConfig.IS_PLAY_BUILD &&
                        StorePurchaseGate.paidElsewhere(
                            plan = currentSub?.plan,
                            source = currentSub?.source,
                            liveSources = currentSub?.liveSources,
                            thisStore = StorePurchaseGate.SOURCE_GOOGLE_PLAY,
                            thisStoreOwnsSubscription = billingState.ownsBirdoSubscription,
                        )

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
                            (storefront as? StorefrontState.Unavailable)?.let { stringResource(it.messageRes) },
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
                        purchaseManagedElsewhere = purchaseManagedElsewhere,
                        onOpenUrl = { settingsViewModel.openUrl(it) },
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

            // After a deletion: store subscriptions the server says are STILL
            // BILLING. Deleting a Birdo account cannot cancel a Google Play or
            // App Store subscription (audit 2026-09-29, A-8 / C-9), so say so
            // and link to where it can be cancelled. Never shown when the
            // backend does not send the list.
            val stillBilling = authState.storeSubscriptionsStillBilling
            if (authState.accountDeleted && stillBilling.isEmpty()) {
                // The plain confirmation (A2-029). With a store subscription
                // still billing, the dialog below says the account is deleted.
                AlertDialog(
                    onDismissRequest = { authViewModel.dismissAccountDeletedNotice() },
                    title = { Text(stringResource(R.string.account_deleted_title), fontWeight = FontWeight.Bold) },
                    text = { Text(stringResource(R.string.account_deleted_body)) },
                    confirmButton = {
                        TextButton(onClick = { authViewModel.dismissAccountDeletedNotice() }) {
                            Text(stringResource(R.string.store_still_billing_ok))
                        }
                    },
                )
            }
            if (stillBilling.isNotEmpty()) {
                val billingContext = LocalContext.current
                val stores = stillBilling.map { sub ->
                    when {
                        sub.isGooglePlay -> stringResource(R.string.store_still_billing_google_play)
                        sub.isAppStore -> stringResource(R.string.store_still_billing_app_store)
                        else -> stringResource(R.string.store_still_billing_unknown_store)
                    }
                }.distinct().joinToString("; ")
                AlertDialog(
                    onDismissRequest = { authViewModel.dismissAccountDeletedNotice() },
                    title = { Text(stringResource(R.string.store_still_billing_title), fontWeight = FontWeight.Bold) },
                    text = { Text(stringResource(R.string.store_still_billing_body, stores)) },
                    confirmButton = {
                        TextButton(onClick = { authViewModel.dismissAccountDeletedNotice() }) {
                            Text(stringResource(R.string.store_still_billing_ok))
                        }
                    },
                    dismissButton = {
                        if (stillBilling.any { it.isGooglePlay }) {
                            TextButton(onClick = {
                                runCatching {
                                    billingContext.startActivity(
                                        Intent(Intent.ACTION_VIEW, PlaySubscriptionLinks.MANAGE.toUri()),
                                    )
                                }
                            }) {
                                Text(stringResource(R.string.store_still_billing_manage))
                            }
                        }
                    },
                )
            }
        } // end Box
    }
}

/** The password-reset page, the one the Windows client opens (P1-023). */
private const val PASSWORD_RESET_URL = "https://auth.birdo.app/reset-password"

/**
 * What the Home top bar shows for the signed-in account: the email, or the
 * canonical "Anonymous account" label. Never the synthetic
 * `anon_…@anonymous.local` address, which carries the account number (A2-013).
 */
@Composable
private fun accountLabel(email: String?): String? = when {
    email.isNullOrBlank() -> null
    isAnonymousAccountEmail(email) -> stringResource(R.string.account_anonymous)
    else -> email
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
