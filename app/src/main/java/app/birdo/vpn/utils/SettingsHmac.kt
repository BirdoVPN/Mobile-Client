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
    internal val PROTECTED_KEYS = listOf(
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
        // Multi-Hop route. Unsigned until now, which made the pair the ONE
        // security-critical setting an attacker on a rooted device could rewrite
        // freely — and rewriting the exit node redirects where the user's traffic
        // leaves the network, the exact property they bought Multi-Hop for. They
        // cannot observe their own egress country, so the change is invisible.
        "multi_hop_enabled",
        "multi_hop_entry_node",
        "multi_hop_exit_node",
    )

    /**
     * Key sets this app has previously signed with, newest first.
     *
     * Adding a key to [PROTECTED_KEYS] changes the canonical string, so every
     * already-installed app would fail [verify] exactly once and get its
     * settings wiped by [resetToSafeDefaults] — kill switch, DNS and split
     * tunnel reset on upgrade, for every user, looking exactly like the tamper
     * response it is not.
     *
     * So a mismatch is re-checked against each historical set before being
     * called tampering. A match means "signed by an older version of us", which
     * is an upgrade, not an attack: we re-sign under the current set and carry
     * on. Only a payload that matches NO set we have ever used is tampering.
     *
     * This is not a weakening. Every candidate is still verified with the same
     * Keystore-backed HMAC, so an attacker must still forge a signature; the
     * legacy sets only widen WHICH honest payloads we recognise.
     */
    internal val LEGACY_PROTECTED_KEY_SETS: List<List<String>> = listOf(
        // v1 — before the Multi-Hop route was covered.
        listOf(
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
        ),
    )

    /**
     * Reset EVERY protected setting to a hardened, safe default, then re-sign.
     *
     * Called when [verify] fails (tamper detected). Resetting only a SUBSET of
     * the protected keys — as the old call site did (kill switch / stealth /
     * quantum / split-tunnel-enabled only) — leaves attacker-controlled values in
     * place for the rest, and the subsequent [sign] blesses them with a valid
     * HMAC. The dangerous survivors are `custom_dns_*` (redirect all DNS to a
     * hostile resolver) and `split_tunnel_apps` (add apps that bypass the VPN).
     *
     * Strategy: remove ALL protected keys first (so any key later added to
     * [PROTECTED_KEYS] reverts to its safe AppPreferences code default
     * automatically), then explicitly pin the security-critical values for
     * defense-in-depth and clarity.
     */
    fun resetToSafeDefaults(prefs: SharedPreferences) {
        try {
            val editor = prefs.edit()
            PROTECTED_KEYS.forEach { editor.remove(it) }
            editor
                .putBoolean("kill_switch_enabled", true)
                .putBoolean("stealth_mode_enabled", true)
                .putBoolean("quantum_protection_enabled", true)
                .putBoolean("split_tunneling_enabled", false)
                .putStringSet("split_tunnel_apps", emptySet())
                .putBoolean("custom_dns_enabled", false)
                .putString("custom_dns_primary", "")
                .putString("custom_dns_secondary", "")
                .putString("wireguard_port", "auto")
                .putInt("wireguard_mtu", 0)
                .putBoolean("biometric_lock_enabled", false)
                // Multi-Hop disarms to an EMPTY route rather than keeping a pair
                // we can no longer vouch for. A tampered exit node silently
                // relocates the user's egress; making them re-pick is the safe
                // default, and the removal loop above already cleared the ids.
                .putBoolean("multi_hop_enabled", false)
                .commit()
            sign(prefs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset settings to safe defaults", e)
        }
    }

    /**
     * Compute and store HMAC of current protected settings.
     * Call after any protected setting changes.
     */
    fun sign(prefs: SharedPreferences) {
        try {
            val key = getOrCreateHmacKey()
            val hmac = computeHmac(key, buildCanonicalData(prefs, PROTECTED_KEYS))
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
            if (storedHmac == computeHmac(key, buildCanonicalData(prefs, PROTECTED_KEYS))) {
                return true
            }

            // Not a match under the CURRENT key set. Before calling this
            // tampering — which wipes the user's kill switch, DNS and split
            // tunnel — check whether it was signed by an older version of us.
            // Adding a protected key changes the canonical string, so every
            // existing install lands here exactly once on upgrade.
            for ((index, legacy) in LEGACY_PROTECTED_KEY_SETS.withIndex()) {
                if (storedHmac == computeHmac(key, buildCanonicalData(prefs, legacy))) {
                    Log.i(TAG, "Settings signed under legacy key set v${LEGACY_PROTECTED_KEY_SETS.size - index} — re-signing")
                    sign(prefs)
                    return true
                }
            }

            Log.e(TAG, "Settings HMAC verification FAILED — possible tampering")
            false
        } catch (e: Exception) {
            Log.e(TAG, "HMAC verification error", e)
            false
        }
    }

    private fun computeHmac(key: SecretKey, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return Base64.encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun buildCanonicalData(
        prefs: SharedPreferences,
        keys: List<String> = PROTECTED_KEYS,
    ): String {
        // Build deterministic string of all protected settings.
        return keys.joinToString("|") { key ->
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
