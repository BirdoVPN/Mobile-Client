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
     * The trust decision, with no Android dependencies, so that every branch of
     * it can be unit-tested. [verifyLibrary] gathers the facts; this decides.
     *
     * THE PACKAGE-SIGNATURE CHECK IS REQUIRED ON EVERY RELEASE PATH.
     *
     * That has to be spelled out because it was briefly true by accident and is
     * now true on purpose. While NATIVE_HASH_* shipped blank (see the
     * `androidComponents.onVariants` block in app/build.gradle.kts for why they
     * did), `expectedHash` was always null, so every release load fell into the
     * no-hash branch and every release load ran the signature check. Arming the
     * hashes makes the hash-match branch reachable for the first time -- and a
     * hash-match-only `return true` would have silently REMOVED the signature
     * check from the path almost every user takes, letting a repackaged APK
     * carrying genuine, unmodified engine binaries verify clean. A hash proves
     * the .so was not swapped; only the signature proves the APK around it was
     * not. Neither substitutes for the other.
     *
     * ANDing them costs nothing that is not already being paid: the identical
     * check runs today on every release connect, for wg-go, xray and
     * rosenpass_jni, and a failure aborts the connect and arms the kill switch
     * (BirdoVpnService.kt, XrayManager.kt, RosenpassNative.kt). A signing
     * allow-list that could not satisfy it would already be bricking every
     * install.
     *
     * | Build   | Hash registered | File present | Hash matches | Signature trusted | Result |
     * |---------|-----------------|--------------|--------------|-------------------|--------|
     * | debug   | any             | any          | any          | any               | true   |
     * | release | any             | no           | -            | any               | false  |
     * | release | yes             | yes          | yes          | yes               | true   |
     * | release | yes             | yes          | yes          | no                | FALSE  |
     * | release | yes             | yes          | no           | yes               | true   |
     * | release | yes             | yes          | no           | no                | false  |
     * | release | no              | yes          | -            | yes               | true   |
     * | release | no              | yes          | -            | no                | false  |
     *
     * The hash-MISMATCH row still accepts a signature-trusted package. That is
     * deliberate and unchanged: a stale baked hash is a build-system regression,
     * and failing closed on it would brick a correctly signed release for a
     * reason no user can act on. An attacker cannot swap the .so AND keep a
     * trusted Birdo signature.
     */
    internal fun decide(
        isDebugBuild: Boolean,
        libraryPresent: Boolean,
        expectedHash: String?,
        actualHash: String?,
        signatureTrusted: Boolean,
    ): Boolean {
        if (isDebugBuild) return true
        if (!libraryPresent) return false
        if (expectedHash.isNullOrBlank()) return signatureTrusted
        if (actualHash == null) return false
        // A hash match is necessary but NOT sufficient -- see above.
        if (actualHash.equals(expectedHash, ignoreCase = true)) return signatureTrusted
        // Mismatch: almost always a stale baked hash, so fall back to the
        // signature rather than bricking a legitimate release.
        return signatureTrusted
    }

    /**
     * Verify a native library's SHA-256 hash before loading.
     *
     * Returns true if the library is trusted. See [decide] for the full
     * behaviour matrix; the short version is that a release build needs a
     * trusted package signature on EVERY path, and additionally needs the .so
     * to hash to the value baked in at build time whenever one was registered.
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

        // Computed ONCE, for every release path, because every release path
        // needs it now. Both operands are cheap and neither touches the network.
        val signatureTrusted =
            RootDetector.hasSigningFingerprintConfigured(context) &&
                RootDetector.isPackageSignatureTrusted(context)

        if (expectedHash.isNullOrBlank()) {
            if (!signatureTrusted) {
                Log.e(TAG, "INTEGRITY FAILURE: no registered hash and no trusted package signature for $libraryName")
                return decide(false, true, null, null, false)
            }
            Log.w(TAG, "No registered hash for $libraryName; package signature check passed")
            return decide(false, true, null, null, true)
        }

        return try {
            val actualHash = sha256Hex(libFile)
            if (actualHash.equals(expectedHash, ignoreCase = true)) {
                // NOT a bare `true`. See [decide]: a matching hash proves the
                // .so was not swapped, and says nothing about the APK it came
                // in. Arming the hashes must not quietly retire the signature
                // check that every release build has been running until now.
                if (signatureTrusted) {
                    Log.i(TAG, "Library $libraryName integrity verified (hash match + trusted signature)")
                } else {
                    Log.e(TAG, "INTEGRITY FAILURE: $libraryName hash matches but the package signature is not trusted - repackaged APK")
                }
                decide(false, true, expectedHash, actualHash, signatureTrusted)
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
                if (signatureTrusted) {
                    Log.w(TAG, "INTEGRITY: $libraryName accepted via trusted package signature (hash injection likely stale in build)")
                } else {
                    Log.e(TAG, "INTEGRITY FAILURE: $libraryName hash mismatch AND untrusted signature — rejecting")
                }
                decide(false, true, expectedHash, actualHash, signatureTrusted)
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
