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

/**
 * Single source of truth for this client's identity.
 *
 * The [deviceId] is a RANDOM value minted on this install and kept in the app's
 * own SharedPreferences. It is not derived from anything about the handset, so
 * it cannot be recomputed by anyone — including us — and, because the manifest
 * sets `allowBackup=false`, it dies with an uninstall rather than being restored
 * onto the next install.
 *
 * P1-dk-ssaid-device-linkage (owner decision 2026-08-19). This used to be a
 * salted SHA-256 of the Android SSAID (`Settings.Secure.ANDROID_ID`). That was
 * chosen because the SSAID survives UPDATE and REINSTALL, which kept one
 * physical device mapped to one server-side device row and let connect-time
 * eviction reclaim its own slot instead of piling up toward the plan cap. The
 * cost was that the same property runs the other way: the value is a
 * deterministic function of the handset, so it is IDENTICAL for every account
 * ever used on it. Two anonymous accounts a user believes are unrelated arrive
 * carrying the same deviceId and are trivially joinable server-side, and no
 * amount of deleting or abandoning an account changes that — reinstalling did
 * not even help, which was the point of using the SSAID in the first place.
 *
 * An anonymous account is the strongest privacy promise this product makes, so
 * a device fingerprint that quietly undoes it is the wrong trade. The pairing
 * convenience is now bought with a value that the user can actually destroy.
 *
 * KNOWN COST, accepted: a reinstall now mints a new id, so it registers as a
 * new device server-side and the old row stays until the backend reaps it. On a
 * plan at its device cap that can surface as "device limit reached" after a
 * reinstall until the stale row goes. That regression is the same one the SSAID
 * switch was made to fix, and it is being re-accepted deliberately.
 *
 * MIGRATION — existing installs KEEP their current id. Every install that has
 * called [current] already persisted its value under [KEY_DEVICE_ID], so
 * reading that first means nobody's device row changes on upgrade: no new rows,
 * no cap pressure, no re-pairing. Nothing recomputes the SSAID any more, so from
 * this build on the id is only ever read back or freshly randomised; existing
 * installs converge on a random one at their next reinstall or account
 * deletion. The honest residual: until then, an existing install's stored value
 * is still the old SSAID hash and still links accounts registered on it. Fixing
 * that for the installed base means rotating live device ids, which is the cap
 * churn described above for every user at once — the owner's call, not this
 * change's.
 */
@Singleton
class DeviceInfoProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("birdo_device_prefs", Context.MODE_PRIVATE)

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
     * The persisted random id, minted on first use.
     *
     * The only two outcomes are "read back what this install already stored"
     * and "mint a fresh random one" — there is deliberately no third branch
     * that DERIVES an id from device state, because any such branch would
     * regenerate the same value for the next account on this handset and put
     * the linkage straight back.
     */
    private fun stableDeviceId(): String =
        prefs.getString(KEY_DEVICE_ID, null) ?: mintDeviceId()

    /**
     * PRIVACY: throw this install's device identity away and mint a new one.
     *
     * Called on successful account deletion (GDPR Art. 17), so the identity
     * dies with the account it identified and the next account registered on
     * this handset cannot be joined to the erased one. Deliberately NOT called
     * anywhere a live account still exists: the backend's `(userId, deviceId)`
     * SSOT and its connect-time slot reclamation both key on this value, so
     * rotating under a live account strands the old device row against the
     * plan's device cap.
     */
    fun resetDeviceIdentity(): String = mintDeviceId()

    /** Mint and persist a fresh random device id. `commit`, not `apply`: the
     *  caller may be about to make a network call that must carry the new id. */
    private fun mintDeviceId(): String {
        // UUID.randomUUID() is SecureRandom-backed — a v4 UUID, not a hash of
        // anything, so it carries no information about the handset or the user.
        val id = "android_${UUID.randomUUID().toString().replace("-", "")}"
        prefs.edit().putString(KEY_DEVICE_ID, id).commit()
        return id
    }

    private companion object {
        // Also the migration hinge: an install upgrading from the SSAID build
        // already has its value here, so it is read back unchanged rather than
        // recomputed or rotated. See the class kdoc.
        private const val KEY_DEVICE_ID = "device_id"
    }
}
