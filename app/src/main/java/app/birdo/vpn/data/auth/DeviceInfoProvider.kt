package app.birdo.vpn.data.auth

import android.content.Context
import android.os.Build
import app.birdo.vpn.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
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

@Singleton
class DeviceInfoProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("birdo_device_prefs", Context.MODE_PRIVATE)

    fun current(): ClientDeviceInfo {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: createDeviceId()
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

    private fun createDeviceId(): String {
        val id = "android_${UUID.randomUUID()}"
        prefs.edit().putString(KEY_DEVICE_ID, id).commit()
        return id
    }

    private companion object {
        private const val KEY_DEVICE_ID = "device_id"
    }
}