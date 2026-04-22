package app.birdo.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import app.birdo.vpn.data.network.NetworkMonitor
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.ui.navigation.BirdoNavGraph
import app.birdo.vpn.ui.navigation.Screen
import app.birdo.vpn.ui.theme.BirdoTheme
import app.birdo.vpn.ui.viewmodel.VpnViewModel
import app.birdo.vpn.utils.RootDetector
import app.birdo.vpn.utils.SettingsHmac
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var networkMonitor: NetworkMonitor

    /** Whether the UI is locked behind biometric auth */
    private val isLocked = mutableStateOf(false)

    /** Deep link route to navigate to on startup */
    private val deepLinkRoute = mutableStateOf<String?>(null)

    private var vpnPermissionCallback: (() -> Unit)? = null
    private var vpnPermissionDeniedCallback: (() -> Unit)? = null

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

    /** In-app update launcher (replaces deprecated startUpdateFlowForResult) */
    private val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* update result — no action needed for flexible updates */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply theme mode from preferences
        requestNotificationPermission()
        checkForAppUpdate()
        checkRootStatus()
        verifySettingsIntegrity()
        deepLinkRoute.value = parseDeepLink(intent)

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
                    val vpnViewModel: VpnViewModel = hiltViewModel()

                    vpnPermissionCallback = { vpnViewModel.onVpnPermissionGranted() }
                    vpnPermissionDeniedCallback = { vpnViewModel.onVpnPermissionDenied() }

                    BirdoNavGraph(
                        onRequestVpnPermission = { intent ->
                            vpnPermissionLauncher.launch(intent)
                        },
                        appPreferences = appPreferences,
                        networkMonitor = networkMonitor,
                        deepLinkRoute = deepLinkRoute.value,
                        onDeepLinkConsumed = { deepLinkRoute.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseDeepLink(intent)?.let { route ->
            deepLinkRoute.value = route
        }
    }

    override fun onResume() {
        super.onResume()
        if (appPreferences.biometricLockEnabled && isLocked.value) {
            promptBiometric()
        }
    }

    private fun promptBiometric() {
        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            // Device has no biometric/credential — unlock anyway
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
                isLocked.value = false
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // User cancelled or too many attempts — keep locked
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    // Stay locked, they can try again on next resume
                }
            }
        }

        BiometricPrompt(this, executor, callback).authenticate(promptInfo)
    }

    /**
     * Parse incoming deep links:
     * - birdo://connect  → Home screen (auto-connect)
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
     * Check Google Play for app updates and prompt the user with a flexible update flow.
     * For security-critical VPN updates, this ensures users stay on the latest version.
     * Uses the non-deprecated AppUpdateOptions API (required for Play Core 2.1+).
     */
    private fun checkForAppUpdate() {
        try {
            val appUpdateManager = AppUpdateManagerFactory.create(this)
            appUpdateManager.appUpdateInfo.addOnSuccessListener { updateInfo ->
                if (updateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    && updateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    try {
                        appUpdateManager.startUpdateFlowForResult(
                            updateInfo,
                            updateLauncher,
                            AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                        )
                    } catch (e: Exception) {
                        Log.w("BirdoUpdate", "Failed to start update flow", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("BirdoUpdate", "App update check failed", e)
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
                Log.e("BirdoSecurity", "Settings HMAC mismatch — resetting to safe defaults")
                prefs.edit()
                    .putBoolean("kill_switch_enabled", true)
                    .putBoolean("stealth_mode_enabled", false)
                    .putBoolean("quantum_protection_enabled", false)
                    .putBoolean("split_tunneling_enabled", false)
                    .apply()
                SettingsHmac.sign(prefs)
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
        try {
            val result = RootDetector.check(this)
            if (result.isRooted) {
                Log.w("BirdoSecurity", "Root detected: ${result.indicators.joinToString()}")
                // Show warning dialog on first detection per install
                val prefs = getSharedPreferences("birdo_security", MODE_PRIVATE)
                val dismissed = prefs.getBoolean("root_warning_dismissed", false)
                if (!dismissed) {
                    android.app.AlertDialog.Builder(this)
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
        } catch (e: Exception) {
            Log.w("BirdoSecurity", "Root detection check failed", e)
        }
    }

    companion object {
        @Suppress("unused")
        private const val TAG = "MainActivity"
    }
}
