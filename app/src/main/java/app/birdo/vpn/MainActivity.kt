package app.birdo.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.birdo.vpn.billing.PlayBillingManager
import app.birdo.vpn.data.network.NetworkMonitor
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.service.BirdoVpnService
import app.birdo.vpn.service.VpnState
import app.birdo.vpn.service.isConnectingPhase
import app.birdo.vpn.ui.AppCoverPolicy
import app.birdo.vpn.ui.components.LocalAppObscured
import app.birdo.vpn.ui.navigation.BirdoNavGraph
import app.birdo.vpn.ui.navigation.Screen
import app.birdo.vpn.ui.shouldExplainNotifications
import app.birdo.vpn.ui.theme.BirdoTheme
import app.birdo.vpn.ui.viewmodel.VpnViewModel
import app.birdo.vpn.utils.FaultReporter
import app.birdo.vpn.utils.RootDetector
import app.birdo.vpn.utils.SettingsHmac
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var networkMonitor: NetworkMonitor

    /**
     * The Play rail, reconciled when the app returns to the foreground
     * (A2-035). Lazy: nothing about it is built outside a Play build.
     */
    @Inject lateinit var playBilling: dagger.Lazy<PlayBillingManager>

    /**
     * Whether the Hide App Contents cover is up. It is drawn OVER the app, not
     * instead of it (A2-009), so what is underneath keeps its place.
     */
    private val isLocked = mutableStateOf(false)

    /** True while a BiometricPrompt is on screen. Guards onStop so the
     *  device-credential fallback (which backgrounds this activity) does not
     *  re-arm the lock mid-authentication and cause a prompt loop. */
    private var isAuthenticating = false

    /** Deep link route to navigate to on startup */
    private val deepLinkRoute = mutableStateOf<String?>(null)

    /** Native-SSO redirect payload (code, state) from birdo://auth. */
    private val oauthCallback = mutableStateOf<Pair<String, String>?>(null)

    private var vpnPermissionCallback: (() -> Unit)? = null
    private var vpnPermissionDeniedCallback: (() -> Unit)? = null

    /**
     * The device looks rooted and the warning was never acknowledged. Shown as
     * a Compose dialog once consent is accepted (A2-042), not as a platform
     * dialog over whatever was on screen, the consent screen included.
     */
    private val rootWarningPending = mutableStateOf(false)

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vpnPermissionCallback?.invoke()
        } else {
            vpnPermissionDeniedCallback?.invoke()
        }
    }

    /** Request POST_NOTIFICATIONS permission on Android 13+ (API 33) */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* we don't need to handle denial — notifications just won't show */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // L-1: FLAG_SECURE blocks screenshots, screen recording, and prevents
        // the activity contents from appearing in the recent-apps thumbnail.
        // Set before any UI is drawn so the lock screen / preview also redact.
        //
        // Skipped ONLY when a DEBUG build was assembled explicitly for Play Store
        // screenshot capture (-PallowScreenshots=true → BuildConfig.ALLOW_SCREENSHOTS).
        // The BuildConfig.DEBUG guard means a RELEASE build ALWAYS sets FLAG_SECURE,
        // so this capture bypass can never ship to users.
        if (!(BuildConfig.DEBUG && BuildConfig.ALLOW_SCREENSHOTS)) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        checkRootStatus()
        verifySettingsIntegrity()
        deepLinkRoute.value = parseDeepLink(intent)
        oauthCallback.value = parseOAuthCallback(intent)

        // Covered on a cold start when Hide App Contents is on; a recreation
        // (fold, rotation, theme change) keeps whatever it was (A2-046).
        isLocked.value = AppCoverPolicy.coveredAtCreate(
            enabled = appPreferences.biometricLockEnabled,
            savedCovered = savedInstanceState?.takeIf { it.containsKey(STATE_COVERED) }
                ?.getBoolean(STATE_COVERED),
        )

        setContent {
            val themeMode by appPreferences.themeModeFlow.collectAsState(initial = appPreferences.themeMode)
            BirdoTheme(themeMode = themeMode) {
                val bgColor = MaterialTheme.colorScheme.background
                Surface(
                    // Test tags as resource ids, so the baseline-profile
                    // generator (UIAutomator) can sign in and reach Connect.
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true },
                    color = bgColor,
                ) {
                    val covered = isLocked.value
                    val vpnViewModel: VpnViewModel = hiltViewModel()

                    // Assign the permission callbacks as a side effect rather than
                    // during composition. hiltViewModel() returns a stable instance,
                    // but mutating activity state inside the composable body runs on
                    // every recomposition; SideEffect keeps it to successful frames.
                    androidx.compose.runtime.SideEffect {
                        vpnPermissionCallback = { vpnViewModel.onVpnPermissionGranted() }
                        vpnPermissionDeniedCallback = { vpnViewModel.onVpnPermissionDenied() }
                    }

                    // The app stays COMPOSED under the cover (A2-009). Swapping
                    // the whole nav graph out for the lock screen, as this used
                    // to, created a new NavController on every unlock: each
                    // return from the background landed on the Connect tab and
                    // lost open screens, dialogs and typed text. Underneath the
                    // cover it is hidden from accessibility services, and told it
                    // cannot be seen, so the globe and background stop animating.
                    CompositionLocalProvider(LocalAppObscured provides covered) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (covered) Modifier.clearAndSetSemantics { } else Modifier),
                        ) {
                            BirdoNavGraph(
                                onRequestVpnPermission = { intent ->
                                    vpnPermissionLauncher.launch(intent)
                                },
                                appPreferences = appPreferences,
                                networkMonitor = networkMonitor,
                                deepLinkRoute = deepLinkRoute.value,
                                onDeepLinkConsumed = { deepLinkRoute.value = null },
                                oauthCallback = oauthCallback.value,
                                onOauthConsumed = { oauthCallback.value = null },
                            )
                        }
                    }

                    if (!covered) {
                        NotificationPermissionExplainer(vpnViewModel)
                        RootWarning()
                    }

                    // A window of its own, so it also covers any dialog or
                    // sheet that was open when the app went to the background.
                    // It inherits FLAG_SECURE from this one.
                    if (covered) {
                        Dialog(
                            onDismissRequest = { moveTaskToBack(true) },
                            properties = DialogProperties(
                                dismissOnClickOutside = false,
                                usePlatformDefaultWidth = false,
                                decorFitsSystemWindows = false,
                            ),
                        ) {
                            AppCoverScreen(onUnlock = { promptBiometric() })
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_COVERED, isLocked.value)
    }

    /**
     * Explains, once, why a VPN wants to post notifications, then asks. Shown
     * when the first tunnel starts coming up (see [shouldExplainNotifications]).
     */
    @Composable
    private fun NotificationPermissionExplainer(vpnViewModel: VpnViewModel) {
        val vpnState by vpnViewModel.uiState.collectAsState()
        var explain by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(vpnState.vpnState) {
            val state = vpnState.vpnState
            if (!explain && shouldExplainNotifications(
                    sdkInt = Build.VERSION.SDK_INT,
                    granted = notificationsGranted(),
                    alreadyExplained = appPreferences.notificationPermissionExplained,
                    tunnelStarting = state.isConnectingPhase || state == VpnState.Connected,
                )
            ) {
                explain = true
            }
        }
        if (!explain) return
        val finish = { ask: Boolean ->
            appPreferences.notificationPermissionExplained = true
            explain = false
            if (ask && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        AlertDialog(
            onDismissRequest = { finish(false) },
            title = {
                Text(
                    stringResource(R.string.notif_explainer_title),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            },
            text = { Text(stringResource(R.string.notif_explainer_body)) },
            confirmButton = {
                TextButton(onClick = { finish(true) }) { Text(stringResource(R.string.notif_explainer_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { finish(false) }) { Text(stringResource(R.string.not_now)) }
            },
        )
    }

    /** The root warning, after consent (A2-042). */
    @Composable
    private fun RootWarning() {
        val consented by appPreferences.hasAcceptedCurrentConsentFlow
            .collectAsState(initial = appPreferences.hasAcceptedCurrentConsent)
        if (!rootWarningPending.value || !consented) return
        val acknowledge = {
            getSharedPreferences(SECURITY_PREFS, MODE_PRIVATE).edit {
                putBoolean(KEY_ROOT_WARNING_DISMISSED, true)
            }
            rootWarningPending.value = false
        }
        AlertDialog(
            onDismissRequest = acknowledge,
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.root_warning_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.root_warning_body)) },
            confirmButton = {
                TextButton(onClick = acknowledge) { Text(stringResource(R.string.root_warning_ack)) }
            },
        )
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Replace the activity's stored intent: without setIntent(), an
        // activity recreation (process restore, config change) re-reads the
        // ORIGINAL launch intent via getIntent() and replays an already-consumed
        // deep link / OAuth callback — retrying the code exchange with a dead
        // code and surfacing a spurious sign-in failure.
        setIntent(intent)
        parseDeepLink(intent)?.let { route ->
            deepLinkRoute.value = route
        }
        parseOAuthCallback(intent)?.let { cb ->
            oauthCallback.value = cb
        }
    }

    override fun onResume() {
        super.onResume()
        // POWER: tell the VPN service the UI is foreground so it refreshes traffic
        // stats fast (1s) while the user is watching; it drops to a slow (8s)
        // cadence in the background to cut wg-go getConfig JNI reads / CPU wakeups.
        BirdoVpnService.uiForeground = true
        if (appPreferences.biometricLockEnabled && isLocked.value) {
            promptBiometric()
        }
        // Reconcile Play purchases made or approved elsewhere while the process
        // stayed alive (A2-035). Off the main thread: the first call may still
        // be waiting on the startup warm-up that builds the rail.
        if (BuildConfig.IS_PLAY_BUILD) {
            lifecycleScope.launch(Dispatchers.Default) { playBilling.get().onAppResumed() }
        }
    }

    override fun onStop() {
        super.onStop()
        // POWER: UI is no longer visible — let the service throttle stats reads.
        BirdoVpnService.uiForeground = false
        // Raise the cover when the app leaves the foreground, so returning to
        // it asks again. See AppCoverPolicy for the two exceptions.
        if (AppCoverPolicy.coverOnStop(
                enabled = appPreferences.biometricLockEnabled,
                authenticating = isAuthenticating,
                changingConfigurations = isChangingConfigurations,
            )
        ) {
            isLocked.value = true
        }
    }

    /**
     * The app lock is a PRIVACY SCREEN, not a key-release gate — and that is a
     * decision, not an omission (CodeQL `insecure-local-authentication`, alert
     * #35, dismissed with this rationale):
     *
     * - What it protects against is a person holding the unlocked phone seeing
     *   or changing VPN state. It does not protect stored credentials; those are
     *   sealed under an Android Keystore AES-GCM key regardless of
     *   this screen.
     * - Binding the prompt to a Keystore `CryptoObject` would only defeat a
     *   hooked callback, which needs root/instrumentation on the device — where
     *   the encrypted store is readable anyway, so the extra binding buys
     *   nothing against that attacker.
     * - It would also cost the fail-open behaviour below (no enrolled
     *   credential → unlock, so nobody is locked out of their own VPN), and on
     *   API 29 (minSdk) BiometricPrompt refuses CryptoObject together with
     *   DEVICE_CREDENTIAL, so it would need an API branch and a device test.
     *
     * If the lock ever has to guard a secret rather than the screen, the change
     * is: auth-required Keystore key, decrypt a stored nonce in
     * [onAuthenticationSucceeded] via `result.cryptoObject`, DEVICE_CREDENTIAL
     * only on API 30+.
     */
    private fun promptBiometric() {
        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // Device has no biometric/credential — unlock anyway
            isAuthenticating = false
            isLocked.value = false
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_cover_prompt_title))
            .setSubtitle(getString(R.string.app_cover_prompt_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                isAuthenticating = false
                isLocked.value = false
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                isAuthenticating = false
                when (errorCode) {
                    // User cancelled or pressed the negative button — stay locked.
                    // The BiometricLockScreen stays up with an "Unlock" button, and
                    // the app content remains hidden until they authenticate.
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> {
                        // Stay locked (isLocked remains true → lock screen shown).
                    }
                    // Hardware can't satisfy the request (no enrolled credential,
                    // sensor unavailable/not present). Mirror the canAuthenticate()
                    // fallback above and unlock so the user isn't locked out forever.
                    BiometricPrompt.ERROR_HW_UNAVAILABLE,
                    BiometricPrompt.ERROR_HW_NOT_PRESENT,
                    BiometricPrompt.ERROR_NO_BIOMETRICS,
                    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> {
                        Log.w("BirdoSecurity", "Biometric unavailable ($errorCode: $errString) — unlocking")
                        isLocked.value = false
                    }
                    // Lockout, timeout, and other transient errors: keep locked but
                    // surface the reason so the user knows to retry.
                    else -> {
                        Log.w("BirdoSecurity", "Biometric auth error ($errorCode: $errString)")
                    }
                }
            }
        }

        isAuthenticating = true
        BiometricPrompt(this, executor, callback).authenticate(promptInfo)
    }

    /**
     * Parse incoming deep links (navigation only — none auto-connect the VPN):
     * - birdo://connect  → Home screen
     * - birdo://servers  → Server list
     * - birdo://settings → Settings
     * - https://birdo.app/connect   → Home screen
     * - https://birdo.app/dashboard → Home screen
     */
    private fun parseDeepLink(intent: Intent?): String? {
        val data: Uri = intent?.data ?: return null
        return when {
            data.scheme == "birdo" -> when (data.host) {
                "connect" -> Screen.Home.route
                "servers" -> Screen.ServerList.route
                "settings" -> Screen.Settings.route
                else -> null
            }
            data.host == "birdo.app" -> when {
                data.path?.startsWith("/connect") == true -> Screen.Home.route
                data.path?.startsWith("/dashboard") == true -> Screen.Home.route
                else -> null
            }
            else -> null
        }
    }

    /**
     * Native-SSO redirect: `birdo://auth?code=<handoff>&state=<state>`. Returns
     * the (code, state) pair for the ViewModel to exchange, or null for any other
     * intent. Kept separate from [parseDeepLink] (which returns a nav route) — the
     * SSO callback carries data, not a destination.
     */
    private fun parseOAuthCallback(intent: Intent?): Pair<String, String>? {
        val data: Uri = intent?.data ?: return null
        if (data.scheme != "birdo" || data.host != "auth") return null
        val code = data.getQueryParameter("code") ?: return null
        val state = data.getQueryParameter("state") ?: return null
        return code to state
    }

    /**
     * Verify HMAC of critical settings on startup.
     * If settings were tampered (e.g. kill switch disabled outside the app),
     * reset them to safe defaults and re-sign.
     */
    private fun verifySettingsIntegrity() {
        try {
            // FIX: Must use same prefs file as AppPreferences ("birdo_vpn_prefs")
            // Using wrong file name means HMAC is checked against an empty file —
            // tampered settings in the real file are never detected.
            val prefs = getSharedPreferences("birdo_vpn_prefs", MODE_PRIVATE)
            if (!SettingsHmac.verify(prefs)) {
                // Log.w, not report: verify() reports the CAUSE at its root
                // (settings_hmac_missing / _mismatch / _verify_threw) and
                // returning false is exactly what makes the reset run, so a
                // code here would be a second bucket for one fact.
                Log.w("BirdoSecurity", "Settings HMAC mismatch — resetting ALL protected settings to safe defaults")
                // Reset EVERY protected key, not just the four booleans: a tampered
                // custom_dns_* (DNS hijack) or split_tunnel_apps (VPN bypass) would
                // otherwise survive and be re-signed with a valid HMAC.
                SettingsHmac.resetToSafeDefaults(prefs)
                // And say so, once, on Settings (A2-047): a reset caused by a
                // Keystore hiccup is indistinguishable from tampering, and done
                // silently it reads as the app forgetting the user's settings.
                appPreferences.settingsResetNoticePending = true
            }
        } catch (e: Exception) {
            // Neither verify() nor resetToSafeDefaults() reports this one: it
            // is thrown before or between them, so nothing was verified and
            // nothing was reset. Tampered settings stay live, unnoticed.
            FaultReporter.report(
                FaultReporter.PATH_KILL_SWITCH,
                "settings_integrity_check_threw",
                "Settings integrity check threw — settings were neither verified nor reset",
                e,
            )
        }
    }

    /**
     * C-11 FIX: Check for root/tamper indicators and warn user.
     * A rooted device can compromise VPN security (key extraction,
     * traffic interception). We warn but do not block — users may
     * have legitimate reasons for rooting.
     */
    private fun checkRootStatus() {
        // PERF: the root sweep spawns a process, probes ~25 filesystem paths
        // and does ~18 PackageManager lookups — run it on IO, never on the
        // main thread during onCreate (cold-start jank / ANR path). The dialog
        // hops back to Main. The settings-HMAC verification deliberately STAYS
        // synchronous: moving it async would let one frame of UI read tampered
        // settings before the reset lands.
        lifecycleScope.launch {
            val result = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    RootDetector.check(this@MainActivity)
                }
            } catch (e: Exception) {
                Log.w("BirdoSecurity", "Root detection check failed", e)
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            if (result.isRooted) {
                Log.w("BirdoSecurity", "Root detected: ${result.indicators.joinToString()}")
                // Warn once per install, in Compose (see RootWarning).
                val dismissed = getSharedPreferences(SECURITY_PREFS, MODE_PRIVATE)
                    .getBoolean(KEY_ROOT_WARNING_DISMISSED, false)
                if (!dismissed) rootWarningPending.value = true
            }
        }
    }

    private companion object {
        /** Saved-state key for whether the cover was up. */
        const val STATE_COVERED = "birdo.app_cover.covered"
        const val SECURITY_PREFS = "birdo_security"
        const val KEY_ROOT_WARNING_DISMISSED = "root_warning_dismissed"
    }
}

/**
 * The Hide App Contents cover: full-screen and opaque, so nothing of the app
 * shows or takes input behind the system prompt, with an explicit "Unlock" for
 * when the user dismissed it. It hides the screen and nothing else, and says
 * so (P1-011): the VPN keeps running behind it.
 */
@Composable
private fun AppCoverScreen(onUnlock: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.app_cover_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_cover_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onUnlock) {
                Text(stringResource(R.string.app_cover_unlock))
            }
        }
    }
}
