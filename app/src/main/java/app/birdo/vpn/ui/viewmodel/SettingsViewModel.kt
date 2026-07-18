package app.birdo.vpn.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.service.VpnManager
import app.birdo.vpn.utils.InputValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isExcluded: Boolean,
)

data class SettingsUiState(
    val killSwitchEnabled: Boolean = true,
    val autoConnect: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val showIpInNotification: Boolean = true,
    val showLocationInNotification: Boolean = true,
    val splitTunnelingEnabled: Boolean = false,
    val splitTunnelApps: Set<String> = emptySet(),
    val installedApps: List<AppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
    // VPN protocol settings
    val localNetworkSharing: Boolean = false,
    val customDnsEnabled: Boolean = false,
    val customDnsPrimary: String = "",
    val customDnsSecondary: String = "",
    val wireGuardPort: String = "auto",
    val wireGuardMtu: Int = 0,
    // Stealth & Quantum settings (post-quantum is ON by default for all users)
    val stealthModeEnabled: Boolean = false,
    val quantumProtectionEnabled: Boolean = true,
    // Biometric lock
    val biometricLockEnabled: Boolean = false,
    // Theme mode: "dark", "light", "system"
    val themeMode: String = "system",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    @ApplicationContext private val context: Context,
    // Apply-on-change: tunnel-affecting settings rebuild the LIVE tunnel
    // (debounced brief reconnect) instead of silently waiting for the next
    // connect; the kill switch is pushed into the running service directly.
    private val vpnManager: VpnManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            killSwitchEnabled = prefs.killSwitchEnabled,
            autoConnect = prefs.autoConnect,
            notificationsEnabled = prefs.notificationsEnabled,
            showIpInNotification = prefs.showIpInNotification,
            showLocationInNotification = prefs.showLocationInNotification,
            splitTunnelingEnabled = prefs.splitTunnelingEnabled,
            splitTunnelApps = prefs.splitTunnelApps,
            localNetworkSharing = prefs.localNetworkSharing,
            customDnsEnabled = prefs.customDnsEnabled,
            customDnsPrimary = prefs.customDnsPrimary,
            customDnsSecondary = prefs.customDnsSecondary,
            wireGuardPort = prefs.wireGuardPort,
            wireGuardMtu = prefs.wireGuardMtu,
            stealthModeEnabled = prefs.stealthModeEnabled,
            quantumProtectionEnabled = prefs.quantumProtectionEnabled,
            biometricLockEnabled = prefs.biometricLockEnabled,
            themeMode = prefs.themeMode,
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    // Set by the text-field setters (MTU / port / DNS); flushed once when the
    // VPN Settings screen is left, so a live tunnel rebuilds with the FINAL
    // committed value rather than every half-typed intermediate.
    private var pendingReapplyOnExit = false

    /**
     * Called when the VPN Settings screen is left. Applies any pending
     * text-field edits with a single reapply blip. Toggles reapply immediately
     * on tap, so they don't route through here.
     */
    fun commitPendingReapply() {
        if (pendingReapplyOnExit) {
            pendingReapplyOnExit = false
            vpnManager.requestSettingsReapply()
        }
    }

    fun setKillSwitch(enabled: Boolean) {
        prefs.killSwitchEnabled = enabled
        _uiState.value = _uiState.value.copy(killSwitchEnabled = enabled)
        // Live push, no tunnel rebuild: the running service consults this flag
        // when the tunnel drops, so it must not keep a stale captured value.
        vpnManager.pushRuntimeSettings()
    }

    fun setAutoConnect(enabled: Boolean) {
        prefs.autoConnect = enabled
        _uiState.value = _uiState.value.copy(autoConnect = enabled)
    }

    fun setNotifications(enabled: Boolean) {
        prefs.notificationsEnabled = enabled
        _uiState.value = _uiState.value.copy(notificationsEnabled = enabled)
    }

    fun setShowIpInNotification(enabled: Boolean) {
        prefs.showIpInNotification = enabled
        _uiState.value = _uiState.value.copy(showIpInNotification = enabled)
    }

    fun setShowLocationInNotification(enabled: Boolean) {
        prefs.showLocationInNotification = enabled
        _uiState.value = _uiState.value.copy(showLocationInNotification = enabled)
    }

    fun setSplitTunneling(enabled: Boolean) {
        prefs.splitTunnelingEnabled = enabled
        _uiState.value = _uiState.value.copy(splitTunnelingEnabled = enabled)
        vpnManager.requestSettingsReapply()
    }

    fun toggleAppExclusion(packageName: String) {
        val current = prefs.splitTunnelApps.toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        prefs.splitTunnelApps = current
        // Commit the whole app-selection edit as ONE blip when the picker is
        // left, not on every app tap while the user is still choosing.
        pendingReapplyOnExit = true
        _uiState.value = _uiState.value.copy(
            splitTunnelApps = current,
            installedApps = _uiState.value.installedApps.map {
                if (it.packageName == packageName) it.copy(isExcluded = current.contains(packageName))
                else it
            },
        )
    }

    fun loadInstalledApps() {
        _uiState.value = _uiState.value.copy(isLoadingApps = true)

        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val pm = context.packageManager
                    val ownPackage = context.packageName
                    val excluded = prefs.splitTunnelApps

                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { app ->
                            app.packageName != ownPackage &&
                                pm.getLaunchIntentForPackage(app.packageName) != null
                        }
                        .map { app ->
                            AppInfo(
                                packageName = app.packageName,
                                label = pm.getApplicationLabel(app).toString(),
                                icon = try { pm.getApplicationIcon(app) } catch (_: Exception) { null },
                                isExcluded = excluded.contains(app.packageName),
                            )
                        }
                        .sortedWith(
                            compareByDescending<AppInfo> { it.isExcluded }
                                .thenBy { it.label.lowercase() }
                        )
                }

                _uiState.value = _uiState.value.copy(
                    installedApps = apps,
                    isLoadingApps = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingApps = false)
            }
        }
    }

    // ── Stealth & Quantum Settings ────────────────────────────────

    fun setStealthMode(enabled: Boolean) {
        prefs.stealthModeEnabled = enabled
        _uiState.value = _uiState.value.copy(stealthModeEnabled = enabled)
        vpnManager.requestSettingsReapply()
    }

    fun setQuantumProtection(enabled: Boolean) {
        prefs.quantumProtectionEnabled = enabled
        _uiState.value = _uiState.value.copy(quantumProtectionEnabled = enabled)
        vpnManager.requestSettingsReapply()
    }

    fun setBiometricLock(enabled: Boolean) {
        prefs.biometricLockEnabled = enabled
        _uiState.value = _uiState.value.copy(biometricLockEnabled = enabled)
    }

    fun setThemeMode(mode: String) {
        if (mode !in listOf("dark", "light", "system")) return
        prefs.themeMode = mode
        _uiState.value = _uiState.value.copy(themeMode = mode)
    }

    // ── VPN Protocol Settings ───────────────────────────────────

    fun setLocalNetworkSharing(enabled: Boolean) {
        prefs.localNetworkSharing = enabled
        _uiState.value = _uiState.value.copy(localNetworkSharing = enabled)
        vpnManager.requestSettingsReapply()
    }

    fun setCustomDnsEnabled(enabled: Boolean) {
        prefs.customDnsEnabled = enabled
        _uiState.value = _uiState.value.copy(customDnsEnabled = enabled)
        vpnManager.requestSettingsReapply()
    }

    fun setCustomDnsPrimary(dns: String) {
        val trimmed = dns.trim()
        // Allow empty (clears the field) or a valid IP address
        if (trimmed.isNotBlank() && !InputValidator.isValidDnsAddress(trimmed)) return
        prefs.customDnsPrimary = trimmed
        _uiState.value = _uiState.value.copy(customDnsPrimary = trimmed)
        pendingReapplyOnExit = true
    }

    fun setCustomDnsSecondary(dns: String) {
        val trimmed = dns.trim()
        if (trimmed.isNotBlank() && !InputValidator.isValidDnsAddress(trimmed)) return
        prefs.customDnsSecondary = trimmed
        _uiState.value = _uiState.value.copy(customDnsSecondary = trimmed)
        pendingReapplyOnExit = true
    }

    fun setWireGuardPort(port: String) {
        if (!InputValidator.isValidPort(port)) return
        prefs.wireGuardPort = port
        _uiState.value = _uiState.value.copy(wireGuardPort = port)
        // Text fields commit on screen exit, not per keystroke — otherwise a
        // >debounce typing pause rebuilds the live tunnel with a half-typed value.
        pendingReapplyOnExit = true
    }

    fun setWireGuardMtu(mtu: Int) {
        val clamped = InputValidator.clampMtu(mtu)
        prefs.wireGuardMtu = clamped
        _uiState.value = _uiState.value.copy(wireGuardMtu = clamped)
        pendingReapplyOnExit = true
    }

    fun openUrl(url: String) {
        // Only allow HTTPS URLs to prevent intent redirection attacks
        if (!url.startsWith("https://")) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w("Settings", "Failed to open URL", e)
        }
    }
}
