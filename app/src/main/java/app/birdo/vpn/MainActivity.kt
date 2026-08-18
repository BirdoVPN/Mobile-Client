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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.hilt.navigation.compose.hiltViewModel
import app.birdo.vpn.data.network.NetworkMonitor
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.service.BirdoVpnService
import app.birdo.vpn.ui.navigation.BirdoNavGraph
import app.birdo.vpn.ui.navigation.Screen
import app.birdo.vpn.ui.theme.BirdoTheme
import app.birdo.vpn.ui.viewmodel.VpnViewModel
import app.birdo.vpn.utils.RootDetector
import app.birdo.vpn.utils.SettingsHmac
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var networkMonitor: NetworkMonitor

    /** Whether the UI is locked behind biometric auth */
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

    /** Root-warning dialog; tracked so it can be dismissed before the
     *  activity is destroyed to avoid a WindowLeaked error. */
    private var rootWarningDialog: android.app.AlertDialog? = null

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

        // Apply theme mode from preferences
        requestNotificationPermission()
        checkRootStatus()
        verifySettingsIntegrity()
        deepLinkRoute.value = parseDeepLink(intent)
        oauthCallback.value = parseOAuthCallback(intent)

        // Lock on cold start if biometric enabled
        if (appPreferences.biometricLockEnabled) {
            isLocked.value = true
        }

        setContent {
            val themeMode by appPreferences.themeModeFlow.collectAsState(initial = appPreferences.themeMode)
            // Keep the AppCompatDelegate night mode in sync so dialogs / WebViews
            // honour the same choice as the Compose theme.
            androidx.compose.runtime.LaunchedEffect(themeMode) {
                when (themeMode) {
                    "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
            }
            BirdoTheme(themeMode = themeMode) {
                val bgColor = MaterialTheme.colorScheme.background
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = bgColor,
                ) {
                    // Biometric app lock: while locked, render an opaque lock
                    // screen INSTEAD of the app content so nothing is visible or
                    // interactive behind the prompt. Reading isLocked.value here
                    // subscribes the composition, so a successful auth (which sets
                    // isLocked = false) recomposes and reveals the app. The VPN
                    // view-model / nav graph are only created once unlocked, so no
                    // account data or network activity happens behind the lock.
                    if (isLocked.value) {
                        BiometricLockScreen(onUnlock = { promptBiometric() })
                    } else {
                        val vpnViewModel: VpnViewModel = hiltViewModel()

                        // Assign the permission callbacks as a side effect rather than
                        // during composition. hiltViewModel() returns a stable instance,
                        // but mutating activity state inside the composable body runs on
                        // every recomposition; SideEffect keeps it to successful frames.
                        androidx.compose.runtime.SideEffect {
                            vpnPermissionCallback = { vpnViewModel.onVpnPermissionGranted() }
                            vpnPermissionDeniedCallback = { vpnViewModel.onVpnPermissionDenied() }
                        }

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
            }
        }
    }

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
    }

    override fun onStop() {
        super.onStop()
        // POWER: UI is no longer visible — let the service throttle stats reads.
        BirdoVpnService.uiForeground = false
        // Re-arm the lock when the app leaves the foreground so returning to it
        // requires re-authentication (a real app-lock, not just cold-start).
        // Skip while a prompt is up: the device-credential fallback backgrounds
        // us, and re-locking there would loop the prompt.
        if (appPreferences.biometricLockEnabled && !isAuthenticating) {
            isLocked.value = true
        }
    }

    override fun onDestroy() {
        // Dismiss the root-warning dialog if it is still showing so its window
        // is not leaked when the activity is destroyed (e.g. config change).
        rootWarningDialog?.takeIf { it.isShowing }?.dismiss()
        rootWarningDialog = null
        super.onDestroy()
    }

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
            .setTitle("Unlock Birdo VPN")
            .setSubtitle("Verify your identity to continue")
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
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
                Log.e("BirdoSecurity", "Settings HMAC mismatch — resetting ALL protected settings to safe defaults")
                // Reset EVERY protected key, not just the four booleans: a tampered
                // custom_dns_* (DNS hijack) or split_tunnel_apps (VPN bypass) would
                // otherwise survive and be re-signed with a valid HMAC.
                SettingsHmac.resetToSafeDefaults(prefs)
            }
        } catch (e: Exception) {
            Log.e("BirdoSecurity", "Settings integrity check failed", e)
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
                // Show warning dialog on first detection per install
                val prefs = getSharedPreferences("birdo_security", MODE_PRIVATE)
                val dismissed = prefs.getBoolean("root_warning_dismissed", false)
                if (!dismissed) {
                    rootWarningDialog = android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Security Warning")
                        .setMessage(
                            "This device appears to be rooted. Running a VPN on a rooted device " +
                            "may compromise your privacy and security.\n\n" +
                            "Root processes can potentially:\n" +
                            "• Read encryption keys from memory\n" +
                            "• Intercept network traffic\n" +
                            "• Bypass the VPN kill switch\n\n" +
                            "Proceed with caution."
                        )
                        .setPositiveButton("I Understand") { dialog, _ ->
                            prefs.edit().putBoolean("root_warning_dismissed", true).apply()
                            dialog.dismiss()
                        }
                        .setCancelable(false)
                        .show()
                }
            }
        }
    }

    companion object {
        @Suppress("unused")
        private const val TAG = "MainActivity"
    }
}

/**
 * Full-screen opaque lock shown while the biometric app-lock is engaged. It
 * covers the entire app so no content is visible or interactive behind the
 * system BiometricPrompt, and offers an explicit "Unlock" action for when the
 * user dismissed the prompt.
 */
@Composable
private fun BiometricLockScreen(onUnlock: () -> Unit) {
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
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Birdo VPN is locked",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Verify your identity to continue.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = onUnlock) {
                Text("Unlock")
            }
        }
    }
}
