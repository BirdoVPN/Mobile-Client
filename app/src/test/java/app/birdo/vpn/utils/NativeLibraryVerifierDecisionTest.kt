package app.birdo.vpn.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every branch of [NativeLibraryVerifier.decide].
 *
 * WHY THIS EXISTS AS A PURE-FUNCTION TEST
 *
 * `verifyLibrary` short-circuits to `true` under `BuildConfig.DEBUG`, and unit
 * tests run against the DEBUG variant (`testDebugUnitTest`), so the release
 * decision is unreachable from a test of `verifyLibrary`. The decision was
 * therefore extracted into `decide`, which takes the facts as arguments and
 * touches no Android API. That is the only way this table can be asserted at
 * all.
 *
 * THE ONE THAT MATTERS
 *
 * [hashMatchStillRequiresTrustedSignature]. NATIVE_HASH_* shipped BLANK in
 * every release ever built (see the `androidComponents.onVariants` block in
 * app/build.gradle.kts), so `expectedHash` was always null and every release
 * load took the no-hash branch — which required a trusted package signature.
 * Arming the hashes makes the hash-match branch reachable for the first time.
 * If that branch returned a bare `true`, arming a control would have SILENTLY
 * REMOVED the signature check from the path essentially every user takes, and
 * a repackaged APK carrying genuine, unmodified engine binaries would verify
 * clean. A hash proves the .so was not swapped. Only the signature proves the
 * APK around it was not.
 */
class NativeLibraryVerifierDecisionTest {

    private val goodHash = "a".repeat(64)
    private val otherHash = "b".repeat(64)

    // -- The regression this file is really about ---------------------------

    @Test
    fun hashMatchStillRequiresTrustedSignature() {
        assertFalse(
            "A matching native-library hash must NOT by itself grant trust. It says the .so " +
                "was not swapped and nothing about the APK it arrived in; a repackaged APK " +
                "carrying genuine engines would otherwise verify clean.",
            NativeLibraryVerifier.decide(
                isDebugBuild = false,
                libraryPresent = true,
                expectedHash = goodHash,
                actualHash = goodHash,
                signatureTrusted = false,
            ),
        )
    }

    @Test
    fun hashMatchWithTrustedSignatureIsTrusted() {
        assertTrue(
            NativeLibraryVerifier.decide(
                isDebugBuild = false,
                libraryPresent = true,
                expectedHash = goodHash,
                actualHash = goodHash,
                signatureTrusted = true,
            ),
        )
    }

    // -- No registered hash: the branch every release took until now ---------

    @Test
    fun noRegisteredHashRequiresTrustedSignature() {
        assertFalse(
            NativeLibraryVerifier.decide(
                isDebugBuild = false,
                libraryPresent = true,
                expectedHash = null,
                actualHash = null,
                signatureTrusted = false,
            ),
        )
        assertTrue(
            NativeLibraryVerifier.decide(
                isDebugBuild = false,
                libraryPresent = true,
                expectedHash = null,
                actualHash = null,
                signatureTrusted = true,
            ),
        )
    }

    @Test
    fun blankRegisteredHashIsTreatedAsNoHash() {
        assertFalse(
            NativeLibraryVerifier.decide(
                isDebugBuild = false,
                libraryPresent = true,
                expectedHash = "",
                actualHash = null,
                signatureTrusted = false,
            ),
        )
    }

    // -- Mismatch: deliberately NOT fail-closed, but still signature-gated ---

    @Test
    fun hashMismatchFallsBackToSignatureAndNoFurther() {
        assertTrue(
            "A stale baked hash is a build-system regression, not an attack; failing closed " +
                "would brick a correctly signed release for a reason no user can act on.",
            NativeLibraryVerifier.decide(
                isDebugBuild = false,
                libraryPresent = true,
                expectedHash = goodHash,
                actualHash = otherHash,
                signatureTrusted = true,
            ),
        )
        assertFalse(
            NativeLibraryVerifier.decide(
                isDebugBuild = false,
                libraryPresent = true,
                expectedHash = goodHash,
                actualHash = otherHash,
                signatureTrusted = false,
            ),
        )
    }

    @Test
    fun hashComparisonIsCaseInsensitive() {
        assertTrue(
            NativeLibraryVerifier.decide(
                isDebugBuild = false,
                libraryPresent = true,
                expectedHash = goodHash.uppercase(),
                actualHash = goodHash,
                signatureTrusted = true,
            ),
        )
    }

    // -- Failure to read the library at all ----------------------------------

    @Test
    fun missingLibraryIsRejectedEvenWithATrustedSignature() {
        assertFalse(
            NativeLibraryVerifier.decide(
                isDebugBuild = false,
                libraryPresent = false,
                expectedHash = goodHash,
                actualHash = goodHash,
                signatureTrusted = true,
            ),
        )
    }

    @Test
    fun unreadableLibraryWithARegisteredHashIsRejected() {
        assertFalse(
            "An I/O error while hashing must not open the gate — otherwise the easiest way " +
                "past the check is to make the file unreadable.",
            NativeLibraryVerifier.decide(
                isDebugBuild = false,
                libraryPresent = true,
                expectedHash = goodHash,
                actualHash = null,
                signatureTrusted = true,
            ),
        )
    }

    // -- Debug builds ---------------------------------------------------------

    @Test
    fun debugBuildsSkipVerificationEntirely() {
        assertTrue(
            NativeLibraryVerifier.decide(
                isDebugBuild = true,
                libraryPresent = false,
                expectedHash = goodHash,
                actualHash = otherHash,
                signatureTrusted = false,
            ),
        )
    }
}
