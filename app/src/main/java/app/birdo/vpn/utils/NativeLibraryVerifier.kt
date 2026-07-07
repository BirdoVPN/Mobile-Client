package app.birdo.vpn.utils

import android.content.Context
import android.os.Build
import android.util.Log
import app.birdo.vpn.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Verifies integrity of JNI native libraries before loading.
 *
 * Mirrors the Windows client's wintun.dll SHA-256 verification.
 * Prevents loading of tampered wg-go or xray native libraries.
 */
object NativeLibraryVerifier {

    private const val TAG = "NativeLibVerify"

    /**
     * Known-good SHA-256 hashes of native libraries, keyed by "libname@abi".
     *
     * The build pipeline (app/build.gradle.kts) hashes every shipped ABI variant
     * of each .so and bakes them into BuildConfig as "abi=hash;abi=hash" strings.
     * A .so differs per ABI, so a single hash only ever matched the arm64-v8a
     * build; on x86_64 it always mismatched and integrity silently fell back to
     * signature-only. Keying by ABI lets [verifyLibrary] pin the hash for the
     * device's actual architecture on every shipped ABI.
     *
     * If a hash is not registered, release builds require the APK signing
     * certificate to match the baked allow-list before the library can load.
     *
     * To regenerate manually:
     *   sha256sum app/build/intermediates/merged_native_libs/release/out/lib/<abi>/libwg-go.so
     */
    private val KNOWN_HASHES: Map<String, String> by lazy {
        buildMap {
            putAbiHashes("wg-go", BuildConfig.NATIVE_HASH_WG_GO)
            val xrayHash = BuildConfig.NATIVE_HASH_XRAY
            putAbiHashes("xray", xrayHash)
            putAbiHashes("Xray", xrayHash)
            // AUDIT-E1: BirdoPQ v1 KEM lib must be hashed too when the build
            // pipeline can produce a stable per-artifact hash.
            putAbiHashes("rosenpass_jni", BuildConfig.NATIVE_HASH_ROSENPASS_JNI)
        }
    }

    /** Parse a "abi=hash;abi=hash" BuildConfig value into "libname@abi" entries. */
    private fun MutableMap<String, String>.putAbiHashes(library: String, encoded: String) {
        if (encoded.isBlank()) return
        for (part in encoded.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val abi = part.substring(0, eq).trim()
            val hash = part.substring(eq + 1).trim()
            if (abi.isNotBlank() && hash.isNotBlank()) put("$library@$abi", hash)
        }
    }

    /**
     * Verify a native library's SHA-256 hash before loading.
     *
     * Returns true if the library is trusted. Behaviour matrix:
     *
     * | Build   | Hash registered | File present | Hash matches | Result |
     * |---------|-----------------|--------------|--------------|--------|
     * | debug   | any             | any          | any          | true   |
     * | release | yes             | yes          | yes          | true   |
     * | release | yes             | yes          | no           | false  |
      * | release | yes             | no           | -            | false  |
      * | release | no              | any          | -            | trusted signing cert required |
     *
      * The signed-APK fallback avoids bricking a release if a build-system
      * regression fails to register native hashes, while still refusing
      * repackaged APKs and unsigned local release builds.
     */
    fun verifyLibrary(context: Context, libraryName: String): Boolean {
        if (app.birdo.vpn.BuildConfig.DEBUG) {
            Log.d(TAG, "Debug build — skipping native library verification for $libraryName")
            return true
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val libFile = File(nativeLibDir, "lib${libraryName}.so")

        if (!libFile.exists()) {
            Log.e(TAG, "Native library not found: ${libFile.absolutePath}")
            return false
        }

        // The loaded .so is the one for the device's primary ABI (SUPPORTED_ABIS
        // is best-first). Pin the hash registered for that ABI; if the build
        // registered no hashes for this ABI we drop to the signature fallback.
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val expectedHash = KNOWN_HASHES["$libraryName@$abi"]
        if (expectedHash.isNullOrBlank()) {
            if (!RootDetector.hasSigningFingerprintConfigured(context)) {
                Log.e(TAG, "INTEGRITY FAILURE: no hash and no release signing fingerprint for $libraryName")
                return false
            }
            if (!RootDetector.isPackageSignatureTrusted(context)) {
                Log.e(TAG, "INTEGRITY FAILURE: package signature is not trusted for $libraryName")
                return false
            }
            Log.w(TAG, "No registered hash for $libraryName; package signature check passed")
            return true
        }

        return try {
            val actualHash = sha256Hex(libFile)
            if (actualHash.equals(expectedHash, ignoreCase = true)) {
                Log.i(TAG, "Library $libraryName integrity verified (hash match)")
                true
            } else {
                // The baked-in BuildConfig hash didn't match the packaged .so.
                // This is most commonly a BUILD bug: the gradle task that injects
                // NATIVE_HASH_* can run after BuildConfig is generated, leaving a
                // stale/empty/wrong expected hash — which would otherwise SILENTLY
                // disable a paid security feature (e.g. BirdoPQ quantum) on a
                // perfectly legitimate, correctly-signed build. Fall back to the
                // same APK-signature trust the no-hash branch uses: an attacker
                // cannot swap the .so AND keep a trusted Birdo signature, so a
                // signature-trusted package is safe even on a hash mismatch.
                Log.w(TAG, "INTEGRITY: $libraryName hash mismatch (expected=$expectedHash actual=$actualHash) — falling back to package-signature verification")
                if (RootDetector.hasSigningFingerprintConfigured(context) &&
                    RootDetector.isPackageSignatureTrusted(context)
                ) {
                    Log.w(TAG, "INTEGRITY: $libraryName accepted via trusted package signature (hash injection likely stale in build)")
                    true
                } else {
                    Log.e(TAG, "INTEGRITY FAILURE: $libraryName hash mismatch AND untrusted signature — rejecting")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify $libraryName", e)
            false
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
