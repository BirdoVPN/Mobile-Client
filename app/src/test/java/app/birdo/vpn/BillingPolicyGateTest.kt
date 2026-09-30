package app.birdo.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Play policy gate.
 *
 * Google permits linking out to an external checkout only for developers
 * ENROLLED in the billing-choice / external-offers programme. Steering while
 * unenrolled is a policy violation: the app is removed, and per our own release
 * history the package name does not come back — `app.birdo.vpn` would be burned
 * permanently.
 *
 * The protection is that external offers require TWO independent flags, and the
 * second one defaults to false. This test exists so that nobody can quietly
 * flip that default (or collapse the two flags into one) without a red build.
 *
 * When enrolment IS confirmed in the Play Console, the correct way to turn this
 * on is at the build invocation — `-PplayExternalOffers=true` — not by editing
 * the default here. See birdo-web/docs/PLAY-LINK-OUT-BILLING.md.
 *
 * Today enrolment is not enough: the link-out path is unfinished (audit A-32),
 * so :app:validatePlayExternalOffers refuses a Play RELEASE with the flag on.
 * The last test pins that gate's presence and wiring; the gate itself runs in
 * Gradle, which a unit test cannot drive.
 */
class BillingPolicyGateTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    @Test
    fun `external offers are OFF by default`() {
        assertFalse(
            "PLAY_EXTERNAL_OFFERS must default to false. Linking out to an external " +
                "checkout without being enrolled in Google's billing-choice programme " +
                "gets the app removed from Play, and the package name is not recoverable. " +
                "Enable it per-build with -PplayExternalOffers=true, only after enrolment.",
            BuildConfig.PLAY_EXTERNAL_OFFERS,
        )
    }

    @Test
    fun `a Play build alone must never be enough to steer`() {
        // The app may only show the external-purchase choice when it is BOTH a
        // Play build AND enrolled. If someone ever reduces this to a single
        // condition, the unit-test default build (IS_PLAY_BUILD=false,
        // PLAY_EXTERNAL_OFFERS=false) would still pass a naive check — so assert
        // the conjunction explicitly.
        val mayShowExternalChoice =
            BuildConfig.IS_PLAY_BUILD && BuildConfig.PLAY_EXTERNAL_OFFERS
        assertFalse(
            "The default build must not be permitted to steer to an external checkout.",
            mayShowExternalChoice,
        )
    }

    @Test
    fun `a Play release with external offers on is refused at build time`() {
        // RM9 / A-32. The default above protects the build nobody configured;
        // this protects against the one somebody did. Pins that the gate exists,
        // fires on exactly the Play-AND-flag combination, and hangs off
        // preReleaseBuild (so debug builds are untouched by construction).
        val gradle = File(repoRoot, "app/build.gradle.kts").readText()
        val start = gradle.indexOf("tasks.register(\"validatePlayExternalOffers\")")
        assertTrue(
            "A-32 regression: the validatePlayExternalOffers release gate is gone from " +
                "app/build.gradle.kts, so -PplayBuild=true -PplayExternalOffers=true " +
                "would build a Play release that steers to an unfinished link-out path",
            start >= 0,
        )
        // The task block ends at the first column-0 closing brace after it.
        val end = Regex("""(?m)^}""").find(gradle, start)?.range?.first ?: gradle.length
        val body = gradle.substring(start, end)
        assertTrue(
            "validatePlayExternalOffers must throw when BOTH isPlayBuild and " +
                "playExternalOffers are set (and only then)",
            Regex("""if \(isPlayBuild && playExternalOffers\)\s*\{\s*throw GradleException""")
                .containsMatchIn(body),
        )
        assertTrue(
            "validatePlayExternalOffers is no longer wired into preReleaseBuild, so it " +
                "would never run on bundleRelease",
            Regex("""preRelease\.dependsOn\([^)]*validatePlayExternalOffers""")
                .containsMatchIn(gradle),
        )
        assertTrue(
            "the gate and BuildConfig.PLAY_EXTERNAL_OFFERS must read the SAME top-level " +
                "value; a second, local resolution of the property could drift from it",
            Regex("""(?m)^val playExternalOffers = """).containsMatchIn(gradle) &&
                Regex("""(?m)^\s+val playExternalOffers = """).find(gradle) == null,
        )
    }
}
