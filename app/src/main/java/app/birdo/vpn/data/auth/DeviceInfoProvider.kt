package app.birdo.vpn.data.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.birdo.vpn.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ClientDeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String = "MOBILE",
    val platform: String = "ANDROID",
    val platformVersion: String,
    val appVersion: String,
)

/**
 * Single source of truth for this client's identity.
 *
 * The [deviceId] is derived from the Android SSAID (`Settings.Secure.ANDROID_ID`)
 * hashed with a static app salt. On Android 8+ the SSAID is scoped to
 * (app-signing key, user, device) and is **stable across app UPDATE and
 * REINSTALL** — which the previous random-UUID-in-SharedPreferences id was NOT
 * (with `allowBackup=false` a reinstall lost it and minted a new id, which is
 * why an update showed "device limit reached"). A stable id maps one physical
 * device to one server-side device row under the backend SSOT `(userId,
 * deviceId)`, and lets connect-time eviction reclaim this device's own slot on
 * reconnect instead of piling up toward the plan cap.
 *
 * The raw SSAID is never sent off-device: only the salted SHA-256 is used.
 */
@Singleton
class DeviceInfoProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("birdo_device_prefs", Context.MODE_PRIVATE)
    private val contentResolver = context.contentResolver

    fun current(): ClientDeviceInfo {
        val deviceId = stableDeviceId()
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val deviceName = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android device" }

        return ClientDeviceInfo(
            deviceId = deviceId,
            deviceName = deviceName,
            platformVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
            appVersion = BuildConfig.APP_VERSION,
        )
    }

    /**
     * Prefer the reinstall-stable SSAID-derived id. Persist it so a later OS/
     * SSAID quirk can't flip identity mid-life. Falls back to a persisted random
     * id only when the SSAID is missing or the known-buggy constant — in that
     * case connect-time eviction still caps the account to one active connection.
     */
    private fun stableDeviceId(): String {
        val ssaid = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Throwable) {
            null
        }

        if (!ssaid.isNullOrBlank() && ssaid != BUGGY_SSAID) {
            val id = "android_" + sha256Hex("$ssaid|$DEVICE_ID_SALT").take(32)
            // Migrate any legacy random id stored by an earlier build to the
            // stable one, so subsequent reinstalls resolve to the same value.
            if (prefs.getString(KEY_DEVICE_ID, null) != id) {
                prefs.edit().putString(KEY_DEVICE_ID, id).commit()
            }
            return id
        }

        return prefs.getString(KEY_DEVICE_ID, null) ?: createFallbackDeviceId()
    }

    private fun createFallbackDeviceId(): String {
        val id = "android_${UUID.randomUUID().toString().replace("-", "")}"
        prefs.edit().putString(KEY_DEVICE_ID, id).commit()
        return id
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        private const val KEY_DEVICE_ID = "device_id"

        // Static per-app salt. Not a secret — it only keeps the raw SSAID from
        // ever leaving the device; the hash is what identifies the install.
        private const val DEVICE_ID_SALT = "birdo-vpn-device-ssot-v1"

        // A firmware bug shipped this exact constant as the SSAID on a batch of
        // early Android devices, so it is NOT unique. Treat it as missing.
        private const val BUGGY_SSAID = "9774d56d682e549c"
    }
}
