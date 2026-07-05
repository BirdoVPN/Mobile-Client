package app.birdo.vpn.utils

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * HMAC-SHA256 integrity verification for critical settings.
 *
 * Mirrors the Windows client's settings HMAC via Win Credential Manager.
 * Uses Android Keystore for the HMAC key — hardware-backed on supported devices.
 *
 * Protects against tampering of security-critical preferences
 * (kill switch, stealth mode, quantum protection, split tunneling).
 */
object SettingsHmac {

    private const val TAG = "SettingsHmac"
    private const val KEYSTORE_ALIAS = "birdo_settings_hmac_key"
    private const val HMAC_PREF_KEY = "settings_hmac_sha256"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** Settings keys that are covered by HMAC integrity protection. */
    private val PROTECTED_KEYS = listOf(
        "kill_switch_enabled",
        "stealth_mode_enabled",
        "quantum_protection_enabled",
        "split_tunneling_enabled",
        "split_tunnel_apps",
        "custom_dns_enabled",
        "custom_dns_primary",
        "custom_dns_secondary",
        "wireguard_port",
        "wireguard_mtu",
        "biometric_lock_enabled",
    )

    /**
     * Compute and store HMAC of current protected settings.
     * Call after any protected setting changes.
     */
    fun sign(prefs: SharedPreferences) {
        try {
            val key = getOrCreateHmacKey()
            val data = buildCanonicalData(prefs)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
            val hmac = Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            prefs.edit().putString(HMAC_PREF_KEY, hmac).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign settings", e)
        }
    }

    /**
     * Verify HMAC of current protected settings.
     *
     * Returns true if HMAC matches (settings untampered), or if no HMAC AND no protected
     * settings exist yet (genuine first run).
     *
     * SEC: Returning true when storedHmac == null regardless of settings state would allow
     * an attacker on a rooted device to delete the HMAC file, bypassing tamper detection.
     * We only skip verification on a true first run (no protected settings written yet).
     */
    fun verify(prefs: SharedPreferences): Boolean {
        val storedHmac = prefs.getString(HMAC_PREF_KEY, null)
        if (storedHmac == null) {
            // Only a genuine first run has no protected settings AND no HMAC.
            // If settings are already present without an HMAC, the HMAC was likely deleted.
            val hasProtectedSettings = PROTECTED_KEYS.any { key -> prefs.contains(key) }
            if (hasProtectedSettings) {
                Log.e(TAG, "Protected settings exist without HMAC — treating as tampered")
                return false
            }
            return true // Genuine first run
        }

        return try {
            val key = getOrCreateHmacKey()
            val data = buildCanonicalData(prefs)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
            val expected = Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            val valid = storedHmac == expected
            if (!valid) {
                Log.e(TAG, "Settings HMAC verification FAILED — possible tampering")
            }
            valid
        } catch (e: Exception) {
            Log.e(TAG, "HMAC verification error", e)
            false
        }
    }

    private fun buildCanonicalData(prefs: SharedPreferences): String {
        // Build deterministic string of all protected settings.
        return PROTECTED_KEYS.joinToString("|") { key ->
            val value = when (val raw = prefs.all[key]) {
                null -> "null"
                // StringSets (e.g. split_tunnel_apps) have UNSTABLE iteration
                // order, so the identical content would otherwise hash differently
                // between sign and verify — a false tamper mismatch. Sort them for
                // a deterministic canonical form.
                is Set<*> -> raw.map { it.toString() }.sorted().joinToString(",", "[", "]")
                else -> raw.toString()
            }
            "$key=$value"
        }
    }

    private fun getOrCreateHmacKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_SIGN)
                .build()
        )
        return keyGen.generateKey()
    }
}
