package app.birdo.vpn.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural pins on the Play Billing dependency itself.
 *
 * Two things can go wrong here that no other test would notice:
 *
 *  1. THE VERSION FLOOR. Play Console enforces a minimum Billing Library
 *     version on UPLOAD and raises it roughly yearly. An old library is not a
 *     review finding — the upload is rejected outright, which blocks the whole
 *     release. A silent downgrade (a bad merge, a Dependabot revert) would only
 *     surface at the worst possible moment.
 *
 *  2. THE TRANSITIVE SURFACE. The billing library drags in
 *     `play-services-location`, which it has done since 7.x and which cannot be
 *     excluded without breaking it. Nothing in Birdo calls it, and no location
 *     permission reaches the merged manifest — verified by
 *     `./gradlew :app:processDebugMainManifest` and reading
 *     `app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`,
 *     whose only new entry is `com.android.vending.BILLING`. What this test can
 *     cheaply pin is that no shipped source ever starts CALLING it, which is
 *     the change that would turn a dormant transitive into a real privacy
 *     problem for a no-logs VPN.
 */
class PlayBillingDependencyTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    /**
     * Google's upload floor has been at major 7 since 2026; 8 gives a year of
     * headroom without pretending to know next year's exact number. Assert a
     * FLOOR, not an exact version, so an upgrade does not need a test edit.
     */
    @Test
    fun `the billing library is at or above the Play Console upload floor`() {
        val build = File(repoRoot, "app/build.gradle.kts").readText()
        val match = Regex("""com\.android\.billingclient:billing:(\d+)\.(\d+)\.(\d+)""")
            .find(build)
        assertTrue(
            "com.android.billingclient:billing is not declared in app/build.gradle.kts",
            match != null,
        )
        val major = match!!.groupValues[1].toInt()
        assertTrue(
            "Play Billing Library ${match.value} is below the upload floor — Play Console " +
                "REJECTS the upload, it does not merely warn. Bump it (and run " +
                "./gradlew :app:dependencies --write-locks).",
            major >= 8,
        )
    }

    /**
     * STRICT dependency locking means an unlocked artifact fails the build, but
     * it would not notice the lock and the build file disagreeing about which
     * version is wanted. Pin that they name the same one.
     */
    @Test
    fun `the locked billing version matches the declared one`() {
        val build = File(repoRoot, "app/build.gradle.kts").readText()
        val declared = Regex("""com\.android\.billingclient:billing:([\d.]+)""")
            .find(build)!!.groupValues[1]
        val lock = File(repoRoot, "app/gradle.lockfile").readText()
        val locked = Regex("""com\.android\.billingclient:billing:([\d.]+)=""")
            .find(lock)?.groupValues?.get(1)
        assertEquals(
            "app/gradle.lockfile is out of step with app/build.gradle.kts — run " +
                "./gradlew :app:dependencies --write-locks",
            declared,
            locked,
        )
    }

    /**
     * The billing library's transitive `play-services-location` must stay
     * dormant. A VPN that advertises no logging has no business linking against
     * a location API, and the day someone imports it is the day this fails.
     */
    @Test
    fun `no shipped source touches the location APIs billing drags in`() {
        val roots = listOf(File(repoRoot, "app/src/main"), File(repoRoot, "shared/src"))
        val sources = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .toList()
        }
        // Vacuity guard: an empty walk would make the scan pass for free.
        assertTrue("source walk found only ${sources.size} files", sources.size >= 50)

        val offenders = sources.filter {
            it.readText().contains("com.google.android.gms.location")
        }
        assertEquals(
            "play-services-location arrives transitively with Play Billing and must stay " +
                "unused. Offending files: ${offenders.map { it.name }}",
            emptyList<String>(),
            offenders.map { it.name },
        )
    }

    /**
     * The rail must stay OFF in the sideload and F-Droid builds. Play Billing
     * cannot work in a build the Play Store did not install, so a rail that ran
     * there would only ever produce BILLING_UNAVAILABLE — and, before the gate
     * existed, a purchase button that dead-ended. That is precisely the
     * Guideline 2.1 shape that got the iOS build rejected.
     */
    @Test
    fun `the rail is gated on the Play distribution flag`() {
        val manager = File(
            repoRoot,
            "app/src/main/java/app/birdo/vpn/billing/PlayBillingManager.kt",
        ).readText()
        assertTrue(
            "PlayBillingManager must gate itself on BuildConfig.IS_PLAY_BUILD",
            manager.contains("BuildConfig.IS_PLAY_BUILD"),
        )
        // The default unit-test build is not a Play build, which is what keeps
        // these tests from ever touching a BillingClient.
        assertTrue(
            "the default build must not be a Play build",
            !app.birdo.vpn.BuildConfig.IS_PLAY_BUILD,
        )
    }
}
