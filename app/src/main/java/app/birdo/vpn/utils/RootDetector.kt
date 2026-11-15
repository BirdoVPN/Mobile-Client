package app.birdo.vpn.utils

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
import android.os.Build
import android.os.Debug
import app.birdo.vpn.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * C-11 FIX: Root/tamper/debugger detection for VPN security.
 *
 * Running a VPN on a rooted device has significant security implications:
 * - Root apps can read WireGuard private keys from memory
 * - Traffic can be intercepted before entering the VPN tunnel
 * - Kill switch can be bypassed by root processes
 *
 * This detector uses heuristic checks. It is NOT a replacement for
 * Play Integrity API (which should be added for production hardening),
 * but provides a baseline warning mechanism.
 *
 * Design: Graceful degradation — warns the user but does not block usage.
 */
object RootDetector {

    data class RootCheckResult(
        val isRooted: Boolean,
        val indicators: List<String>,
    )

    /**
     * Run all root detection checks and return a combined result.
     */
    fun check(context: Context): RootCheckResult {
        val indicators = mutableListOf<String>()

        if (checkSuBinary()) indicators.add("su binary found")
        if (checkRootManagementApps(context)) indicators.add("root management app installed")
        if (checkTestKeys()) indicators.add("test-keys build detected")
        if (checkDangerousProps()) indicators.add("dangerous system properties")
        if (checkRootCloaking(context)) indicators.add("root cloaking app detected")
        if (checkMagisk()) indicators.add("Magisk detected")
        if (checkXposed()) indicators.add("Xposed framework detected")
        if (checkBusybox()) indicators.add("busybox found")
        if (checkDebuggerAttached()) indicators.add("debugger attached")
        if (checkTampering(context)) indicators.add("APK signature mismatch")

        return RootCheckResult(
            isRooted = indicators.isNotEmpty(),
            indicators = indicators,
        )
    }

    /**
     * Check if a debugger is currently attached to the process.
     * A debugger can extract WireGuard keys from memory and intercept API tokens.
     */
    fun isDebuggerConnected(): Boolean = checkDebuggerAttached()

    /** True when a release build has an expected signing certificate baked in. */
    fun hasSigningFingerprintConfigured(context: Context): Boolean =
        bakedSigningFingerprints().isNotEmpty()

    /** Verify this APK was signed by the expected release certificate. */
    fun isPackageSignatureTrusted(context: Context): Boolean {
        return isSignatureTrusted(
            currentFingerprint = currentSigningFingerprint(context),
            baked = bakedSigningFingerprints(),
            installedFromPlay = isInstalledFromPlayStore(context),
        )
    }

    /**
     * Gate for native-library loading (see NativeLibraryVerifier). Deliberately
     * stays on the installer-of-record rule: a Play user whose install has a
     * null or foreign initiator (Smart Switch, Play Store removed, pre-Android-11
     * install later upgraded) must not lose the VPN (F-968-03 review).
     */
    internal fun isSignatureTrusted(
        currentFingerprint: String?,
        baked: Set<String>,
        installedFromPlay: Boolean,
    ): Boolean {
        val current = currentFingerprint?.let { normalizeFingerprint(it) } ?: return false
        if (current in baked || current == PLAY_APP_SIGNING_SHA256) return true
        // Google Play App Signing re-signs the app with the app-signing key.
        // The public cert is allow-listed above; the installer rule remains for
        // older installs, as before.
        return installedFromPlay
    }

    /**
     * Public SHA-256 of the Play App Signing (app signing) certificate, from
     * docs/PLAY-APP-SIGNING.md line 30 ("App signing key certificate"), NOT the
     * upload key on line 38.
     */
    internal const val PLAY_APP_SIGNING_SHA256 =
        "05:9F:CD:5A:87:15:7F:29:3A:93:CD:00:B8:8D:8B:AD:30:97:8A:0A:46:41:AA:70:FF:04:88:83:41:E4:59:A0"

    // ── Individual Checks ────────────────────────────────────────

    /**
     * Check for su binary in common locations.
     */
    private fun checkSuBinary(): Boolean {
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su-backup",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/su/bin/su",
            "/system/app/Superuser.apk",
        )
        return paths.any { File(it).exists() }
    }

    /**
     * Check for known root management apps.
     */
    private fun checkRootManagementApps(context: Context): Boolean {
        val packages = arrayOf(
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.kingroot.kinguser",
            "com.kingo.root",
            "com.smedialink.onecleanpro",
        )
        return packages.any { isPackageInstalled(context, it) }
    }

    /**
     * Check for root cloaking apps (e.g., Hide My Root).
     */
    private fun checkRootCloaking(context: Context): Boolean {
        val packages = arrayOf(
            "com.devadvance.rootcloak",
            "com.devadvance.rootcloakplus",
            "de.robv.android.xposed.installer",
            "com.saurik.substrate",
            "com.zachspong.temprootremovejb",
            "com.amphoras.hidemyroot",
            "com.amphoras.hidemyrootadfree",
            "com.formyhm.hiderootPremium",
            "com.formyhm.hideroot",
        )
        return packages.any { isPackageInstalled(context, it) }
    }

    /**
     * Check if build tags indicate a test/debug build (common on custom ROMs).
     */
    private fun checkTestKeys(): Boolean {
        return Build.TAGS?.contains("test-keys") == true
    }

    /**
     * Check for dangerous system properties indicating root.
     */
    private fun checkDangerousProps(): Boolean {
        return try {
            // Use absolute path to /system/bin/getprop to avoid PATH-based hijacking
            // on rooted devices that may have placed a shim earlier in PATH.
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", "ro.debuggable"))
            try {
                // use{} closes the stream (was leaked per call), and the bounded
                // waitFor prevents a blocked/slow getprop from hanging the sweep.
                val result = process.inputStream.bufferedReader().use { it.readText() }.trim()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                result == "1"
            } finally {
                process.destroy()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Check for Magisk-specific indicators.
     */
    private fun checkMagisk(): Boolean {
        val magiskPaths = arrayOf(
            "/sbin/.magisk",
            "/cache/.disable_magisk",
            "/dev/.magisk.unblock",
            "/data/adb/magisk",
            "/data/adb/magisk.img",
        )
        return magiskPaths.any { File(it).exists() }
    }

    /**
     * Check for Xposed framework.
     */
    private fun checkXposed(): Boolean {
        return try {
            // Check if Xposed bridge class is loadable
            Class.forName("de.robv.android.xposed.XposedBridge")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
    }

    /**
     * Check for busybox binary (common on rooted devices).
     */
    private fun checkBusybox(): Boolean {
        val paths = arrayOf(
            "/system/xbin/busybox",
            "/system/bin/busybox",
            "/sbin/busybox",
            "/data/local/bin/busybox",
        )
        return paths.any { File(it).exists() }
    }

    /**
     * H-06 FIX: Detect attached debugger.
     * A debugger on a release build can read decrypted WG private keys,
     * intercept TLS-decrypted API responses, and bypass certificate pinning.
     */
    private fun checkDebuggerAttached(): Boolean {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    /**
     * H-07 FIX: Verify APK signing certificate hasn't been tampered with.
     * A repackaged APK (e.g. with pinning removed or logging added) will
     * have a different signing certificate. This catches that at runtime.
     *
     * The expected SHA-256 fingerprint is set at build time via BuildConfig.
     * If the app was signed with a different key (repackaged), this returns true.
     */
    private fun checkTampering(context: Context): Boolean {
        return isTampered(
            baked = bakedSigningFingerprints(),
            currentFingerprint = currentSigningFingerprint(context),
            installedFromPlayStrict = isInstalledFromPlayStoreStrict(context),
        )
    }

    /**
     * Advisory tamper signal only. A cert we do not recognise is flagged unless
     * the install provably came from Play (installer AND initiator). A wrong
     * answer here only shows a warning; it never gates the VPN.
     */
    internal fun isTampered(
        baked: Set<String>,
        currentFingerprint: String?,
        installedFromPlayStrict: Boolean,
    ): Boolean {
        if (baked.isEmpty()) return false
        val current = currentFingerprint?.let { normalizeFingerprint(it) } ?: return false
        if (current in baked || current == PLAY_APP_SIGNING_SHA256) return false
        return !installedFromPlayStrict
    }

    /**
     * True if this package was installed by the Google Play Store (or the
     * Play-adjacent Market Feedback Agent). Play App Signing re-signs uploaded
     * apps with the app-signing key, so a Play-installed package's signing cert
     * legitimately differs from the upload-key fingerprint we bake in — and is
     * trustworthy. A repackaged/tampered APK is sideloaded, so its installer is
     * NOT the Play Store, and it stays flagged.
     */
    private fun isInstalledFromPlayStore(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
            installer == "com.android.vending" || installer == "com.google.android.feedback"
        } catch (_: Exception) {
            false
        }
    }

    /** Installer AND initiator are Play (see [isPlayInstall]); advisory warning only. */
    private fun isInstalledFromPlayStoreStrict(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val source = pm.getInstallSourceInfo(context.packageName)
                isPlayInstall(source.installingPackageName, source.initiatingPackageName, Build.VERSION.SDK_INT)
            } else {
                @Suppress("DEPRECATION")
                isPlayInstall(pm.getInstallerPackageName(context.packageName), null, Build.VERSION.SDK_INT)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun bakedSigningFingerprints(): Set<String> {
        return BuildConfig.SIGNING_CERT_FINGERPRINT
            .split(',', ';', '\n', '\r', '\t', ' ')
            .map { normalizeFingerprint(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun normalizeFingerprint(value: String): String =
        value
            .trim()
            .uppercase()
            .filter { it in '0'..'9' || it in 'A'..'F' }
            .chunked(2)
            .joinToString(":")

    /**
     * F-968-04: which certificate stands for the signer NOW. The history is
     * ordered oldest first, so after a key rotation element 0 is the retired
     * key and the current signer is the LAST entry. With several signers the
     * existing behaviour (first of apkContentsSigners) is unchanged.
     */
    internal fun selectCurrentSignerCert(
        multipleSigners: Boolean,
        contentsSigners: List<ByteArray>?,
        history: List<ByteArray>?,
    ): ByteArray? =
        if (multipleSigners) contentsSigners?.firstOrNull() else history?.lastOrNull()

    /**
     * F-968-03: installer-of-record alone is spoofable
     * (`adb install -i com.android.vending`). From API 30 the OS also records
     * WHO ran the install (initiatingPackageName); for an adb/shell
     * install AOSP leaves it null, never Play. Both must name Play. Below API 30 only the
     * installer is available.
     */
    internal fun isPlayInstall(installer: String?, initiator: String?, sdkInt: Int): Boolean {
        val play = setOf("com.android.vending", "com.google.android.feedback")
        if (installer !in play) return false
        return sdkInt < Build.VERSION_CODES.R || initiator in play
    }

    private fun currentSigningFingerprint(context: Context): String? {
        return try {
            val signingInfo = context.packageManager
                .getPackageInfo(context.packageName, GET_SIGNING_CERTIFICATES)
                .signingInfo ?: return null

            val certBytes = selectCurrentSignerCert(
                multipleSigners = signingInfo.hasMultipleSigners(),
                contentsSigners = signingInfo.apkContentsSigners?.map { it.toByteArray() },
                history = signingInfo.signingCertificateHistory?.map { it.toByteArray() },
            ) ?: return null

            MessageDigest.getInstance("SHA-256")
                .digest(certBytes)
                .joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
