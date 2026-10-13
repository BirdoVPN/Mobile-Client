package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Play payments policy: the Play build sells through Google Play
 * Billing and links to no other checkout.
 *
 * Linking out from a Play build is allowed only for developers ENROLLED in
 * Google's billing-choice / external-offers programme, and only with its
 * External Offers APIs and 24-hour transaction reporting in place. Steering
 * without them gets the app removed, and the package name does not come back.
 *
 * This app used to carry a dormant link-out path behind a second build flag,
 * a choice sheet and a release gate that refused to ship it. The owner deleted
 * it on 2026-10-07 (MR-924). These tests pin that it stays deleted, so
 * bringing link-out back is a design change with its own review, not a build
 * property someone can pass.
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
    fun `BuildConfig carries no external-offers or link-out switch`() {
        val switches = BuildConfig::class.java.declaredFields
            .map { it.name }
            .filter { "EXTERNAL" in it || "LINK_OUT" in it || "LINKOUT" in it }
        assertEquals(
            "A link-out switch is back in BuildConfig. The Play build must not be able to " +
                "steer to an external checkout by a flag (MR-924).",
            emptyList<String>(),
            switches,
        )
    }

    @Test
    fun `playBuild is the only Play property the build script reads`() {
        val gradle = File(repoRoot, "app/build.gradle.kts").readText()
        val playProperties = Regex("""findProperty\("(play[A-Za-z]*)"\)""")
            .findAll(gradle)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "app/build.gradle.kts reads a Play property other than playBuild. The old " +
                "-PplayExternalOffers switch is what this pins out (MR-924).",
            setOf("playBuild"),
            playProperties,
        )
    }

    @Test
    fun `the Play build opens the web checkout from nowhere`() {
        val graph = File(
            repoRoot,
            "app/src/main/java/app/birdo/vpn/ui/navigation/BirdoNavGraph.kt",
        ).readText()
        val calls = Regex("""openWebCheckout\(\)""").findAll(graph).count()
        val guarded = Regex(
            """if \(!BuildConfig\.IS_PLAY_BUILD\) \{\s*openWebCheckout\(\)|""" +
                """if \(BuildConfig\.IS_PLAY_BUILD\) \{\s*startPlayPurchase\(planId, period\)\s*\}\s*else\s*\{\s*openWebCheckout\(\)""",
        ).findAll(graph).count()
        assertTrue("found no openWebCheckout() call: the scan is looking at the wrong file", calls > 0)
        assertEquals(
            "every openWebCheckout() call in BirdoNavGraph must sit behind !IS_PLAY_BUILD",
            calls,
            guarded,
        )
    }
}
