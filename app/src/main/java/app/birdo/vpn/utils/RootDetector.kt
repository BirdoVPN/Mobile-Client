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
        expectedSigningFingerprints().isNotEmpty()

    /** Verify this APK was signed by the expected release certificate. */
    fun isPackageSignatureTrusted(context: Context): Boolean {
        val current = currentSigningFingerprint(context)?.let { normalizeFingerprint(it) }
            ?: return false
        if (current in expectedSigningFingerprints()) return true
        // Google Play App Signing re-signs the app with the app-signing key
        // (different from our baked upload-key fingerprint), so on a genuine Play
        // install the cert legitimately won't match the allow-list. Trust it —
        // Google's signature is the guarantee; only a sideloaded repackage lacks it.
        return isInstalledFromPlayStore(context)
    }

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
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            result == "1"
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
        val expected = expectedSigningFingerprints()
        if (expected.isEmpty()) return false
        val current = currentSigningFingerprint(context) ?: return false
        if (normalizeFingerprint(current) in expected) return false
        // A genuine Google Play install is re-signed by Play App Signing with a
        // cert we didn't bake — that is NOT tampering. Only flag an unrecognised
        // cert as a repackage when it did not come from the Play Store.
        return !isInstalledFromPlayStore(context)
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

    private fun expectedSigningFingerprints(): Set<String> {
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

    private fun currentSigningFingerprint(context: Context): String? {
        return try {
            val signingInfo = context.packageManager
                .getPackageInfo(context.packageName, GET_SIGNING_CERTIFICATES)
                .signingInfo ?: return null

            val signatures = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }

            if (signatures.isNullOrEmpty()) return null

            val md = MessageDigest.getInstance("SHA-256")
            signatures[0].toByteArray()
                .let { md.digest(it) }
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
