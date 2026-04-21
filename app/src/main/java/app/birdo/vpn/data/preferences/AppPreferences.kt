package app.birdo.vpn.data.preferences

import android.content.Context
import android.content.SharedPreferences
import app.birdo.vpn.utils.SettingsHmac
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted user preferences matching the Windows client's settings.
 * Stored in SharedPreferences (non-sensitive settings).
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("birdo_vpn_prefs", Context.MODE_PRIVATE)

    // ── Kill Switch ──────────────────────────────────────────────
    var killSwitchEnabled: Boolean
        get() = prefs.getBoolean(KEY_KILL_SWITCH, true) // Default ON like Windows
        set(value) { prefs.edit().putBoolean(KEY_KILL_SWITCH, value).apply(); signSettings() }

    // ── Privacy / GDPR Consent ───────────────────────────────────
    var hasAcceptedPrivacyPolicy: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, value).apply()

    var privacyConsentTimestamp: Long
        get() = prefs.getLong(KEY_PRIVACY_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_PRIVACY_TIMESTAMP, value).apply()

    // ── Auto-Connect ─────────────────────────────────────────────
    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()

    // ── Notifications ────────────────────────────────────────────
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    var showIpInNotification: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_SHOW_IP, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_SHOW_IP, value).apply()

    var showLocationInNotification: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_SHOW_LOCATION, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_SHOW_LOCATION, value).apply()

    // ── VPN Protocol Settings ────────────────────────────────────
    var localNetworkSharing: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_NETWORK_SHARING, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCAL_NETWORK_SHARING, value).apply()

    // ── Stealth Mode (Xray Reality) ──────────────────────────────
    /** When enabled, WireGuard traffic is wrapped in Xray VLESS+Reality TLS tunnel
     *  to bypass DPI and appear as regular HTTPS traffic to microsoft.com */
    var stealthModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_STEALTH_MODE, true) // Default ON — maximum protection
        set(value) { prefs.edit().putBoolean(KEY_STEALTH_MODE, value).apply(); signSettings() }

    // ── Quantum Protection (Rosenpass PQ-PSK) ────────────────────
    /** When enabled, adds post-quantum pre-shared key exchange via Rosenpass
     *  (Classic McEliece + Kyber) on top of WireGuard's Curve25519.
     *  Default ON — rosenpass is now deployed on every production VPN node and
     *  the backend serves a per-node Rosenpass public key from the database. */
    var quantumProtectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_QUANTUM_PROTECTION, true)
        set(value) { prefs.edit().putBoolean(KEY_QUANTUM_PROTECTION, value).apply(); signSettings() }

    var customDnsEnabled: Boolean
        get() = prefs.getBoolean(KEY_CUSTOM_DNS_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_CUSTOM_DNS_ENABLED, value).apply(); signSettings() }

    var customDnsPrimary: String
        get() = prefs.getString(KEY_CUSTOM_DNS_PRIMARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_DNS_PRIMARY, value).apply()

    var customDnsSecondary: String
        get() = prefs.getString(KEY_CUSTOM_DNS_SECONDARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_DNS_SECONDARY, value).apply()

    /** "auto", "51820", "53", or a custom port number as string */
    var wireGuardPort: String
        get() = prefs.getString(KEY_WG_PORT, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_WG_PORT, value).apply()

    /** 0 = automatic (use server default) */
    var wireGuardMtu: Int
        get() = prefs.getInt(KEY_WG_MTU, 0)
        set(value) = prefs.edit().putInt(KEY_WG_MTU, value).apply()

    // ── Split Tunneling ──────────────────────────────────────────
    var splitTunnelingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPLIT_TUNNELING, false)
        set(value) { prefs.edit().putBoolean(KEY_SPLIT_TUNNELING, value).apply(); signSettings() }
    // ── Biometric Lock ──────────────────────────────────────
    var biometricLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_LOCK, false)
        set(value) { prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, value).apply(); signSettings() }
    // ── Multi-Hop (Double VPN) ───────────────────────────────────
    var multiHopEnabled: Boolean
        get() = prefs.getBoolean(KEY_MULTI_HOP, false)
        set(value) = prefs.edit().putBoolean(KEY_MULTI_HOP, value).apply()

    var multiHopEntryNodeId: String?
        get() = prefs.getString(KEY_MULTI_HOP_ENTRY, null)
        set(value) = prefs.edit().putString(KEY_MULTI_HOP_ENTRY, value).apply()

    var multiHopExitNodeId: String?
        get() = prefs.getString(KEY_MULTI_HOP_EXIT, null)
        set(value) = prefs.edit().putString(KEY_MULTI_HOP_EXIT, value).apply()

    /** Package names excluded from VPN (bypass VPN) */
    var splitTunnelApps: Set<String>
        get() = prefs.getStringSet(KEY_SPLIT_TUNNEL_APPS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SPLIT_TUNNEL_APPS, value).apply()

    // ── Favorite Servers ─────────────────────────────────────────
    var favoriteServers: Set<String>
        get() = prefs.getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FAVORITES, value).apply()

    // ── Theme Mode (dark / light / system) ───────────────────────
    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    fun toggleFavorite(serverId: String) {
        val current = favoriteServers.toMutableSet()
        if (current.contains(serverId)) current.remove(serverId) else current.add(serverId)
        favoriteServers = current
    }

    fun isFavorite(serverId: String): Boolean = favoriteServers.contains(serverId)

    /** Verify settings HMAC integrity. Returns false if tampering detected. */
    fun verifyIntegrity(): Boolean = SettingsHmac.verify(prefs)

    /** Re-sign settings HMAC after a protected setting changes. */
    private fun signSettings() = SettingsHmac.sign(prefs)

    // ── Last Connected Server ────────────────────────────────────
    var lastServerId: String?
        get() = prefs.getString(KEY_LAST_SERVER, null)
        set(value) = prefs.edit().putString(KEY_LAST_SERVER, value).apply()

    companion object {
        private const val KEY_KILL_SWITCH = "kill_switch_enabled"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_NOTIF_SHOW_IP = "notif_show_ip"
        private const val KEY_NOTIF_SHOW_LOCATION = "notif_show_location"
        private const val KEY_SPLIT_TUNNELING = "split_tunneling_enabled"
        private const val KEY_SPLIT_TUNNEL_APPS = "split_tunnel_apps"
        private const val KEY_FAVORITES = "favorite_servers"
        private const val KEY_LAST_SERVER = "last_server_id"
        private const val KEY_PRIVACY_ACCEPTED = "privacy_policy_accepted"
        private const val KEY_PRIVACY_TIMESTAMP = "privacy_consent_timestamp"
        private const val KEY_LOCAL_NETWORK_SHARING = "local_network_sharing"
        private const val KEY_STEALTH_MODE = "stealth_mode_enabled"
        private const val KEY_QUANTUM_PROTECTION = "quantum_protection_enabled"
        private const val KEY_CUSTOM_DNS_ENABLED = "custom_dns_enabled"
        private const val KEY_CUSTOM_DNS_PRIMARY = "custom_dns_primary"
        private const val KEY_CUSTOM_DNS_SECONDARY = "custom_dns_secondary"
        private const val KEY_WG_PORT = "wireguard_port"
        private const val KEY_WG_MTU = "wireguard_mtu"
        private const val KEY_BIOMETRIC_LOCK = "biometric_lock_enabled"
        private const val KEY_MULTI_HOP = "multi_hop_enabled"
        private const val KEY_MULTI_HOP_ENTRY = "multi_hop_entry_node"
        private const val KEY_MULTI_HOP_EXIT = "multi_hop_exit_node"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
