package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** The build inputs a link-out switch could come back through. */
    private val buildScripts = listOf(
        "app/build.gradle.kts",
        "build.gradle.kts",
        "shared/build.gradle.kts",
        "settings.gradle.kts",
    )

    /** A name that sounds like a link-out switch, whatever the casing or separator. */
    private val linkOutName = Regex(
        """external.?offer|link.?out|steer|billing.?choice|alternative.?billing|user.?choice""",
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun `playBuild is the only Play property the build reads, by any route`() {
        // Every way a build script reads a value from outside (MR-1871: the
        // first version of this guard matched findProperty("play...") only).
        val readPattern = Regex(
            """(?:findProperty|hasProperty|property|gradleProperty|getProperty|getenv|""" +
                """environmentVariable|systemProperty)\(\s*"([^"]+)"|""" +
                """(?:extra|properties)\[\s*"([^"]+)"\s*]""",
        )
        val names = buildScripts
            .map { File(repoRoot, it) }
            .filter { it.isFile }
            .flatMap { file ->
                readPattern.findAll(file.readText()).map { m ->
                    m.groupValues[1].ifEmpty { m.groupValues[2] }
                }.toList()
            }
            .toSet()
        assertTrue("the scan missed the playBuild read: it is looking at the wrong files", "playBuild" in names)
        assertEquals(
            "The build reads a Play property other than playBuild / BIRDO_PLAY_BUILD. The old " +
                "-PplayExternalOffers switch is what this pins out (MR-924).",
            setOf("playBuild", "BIRDO_PLAY_BUILD"),
            names.filter { it.contains("play", ignoreCase = true) }.toSet(),
        )
        assertEquals(
            "The build reads a property named like a link-out switch (MR-924).",
            emptyList<String>(),
            names.filter { linkOutName.containsMatchIn(it) },
        )
    }

    @Test
    fun `no build input defines a link-out switch`() {
        val gradleProperties = File(repoRoot, "gradle.properties")
        val keys = gradleProperties.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .map { it.substringBefore('=').substringBefore(':').trim() }
        assertTrue("gradle.properties has no keys: the scan is broken", keys.isNotEmpty())
        assertEquals(
            "gradle.properties defines a link-out switch (MR-924).",
            emptyList<String>(),
            keys.filter { linkOutName.containsMatchIn(it) },
        )

        // A BuildConfig field set only on some variants (say, only when
        // playBuild is on) never reaches the BuildConfig the test above
        // reflects over, so the script is read for it too.
        val fieldPattern = Regex("""buildConfigField\(\s*"[^"]+"\s*,\s*"([^"]+)"""")
        val fields = buildScripts
            .map { File(repoRoot, it) }
            .filter { it.isFile }
            .flatMap { file -> fieldPattern.findAll(file.readText()).map { it.groupValues[1] }.toList() }
        assertTrue("found no buildConfigField: the scan is broken", fields.isNotEmpty())
        assertEquals(
            "A build script declares a link-out BuildConfig field (MR-924).",
            emptyList<String>(),
            fields.filter { linkOutName.containsMatchIn(it) || "EXTERNAL" in it || "OFFER" in it },
        )
    }

    @Test
    fun `the choice sheet and Play's link-out billing APIs are gone`() {
        assertFalse(
            "BirdoBillingChoiceSheet.kt is back (MR-924)",
            File(repoRoot, "app/src/main/java/app/birdo/vpn/ui/components/BirdoBillingChoiceSheet.kt").exists(),
        )
        val appSources = File(repoRoot, "app/src")
        val sheets = appSources.walkTopDown()
            .filter { it.isFile && it.name == "BirdoBillingChoiceSheet.kt" }
            .map { it.relativeTo(repoRoot).path }
            .toList()
        assertEquals("BirdoBillingChoiceSheet.kt is back (MR-924)", emptyList<String>(), sheets)

        // Play's own APIs for selling outside Play Billing (external offers,
        // the billing-program and external-link calls, alternative and
        // user-choice billing), the sheet under another file name, or its
        // strings: any of them is the link-out path coming back.
        val linkOutCode = Regex(
            """BirdoBillingChoiceSheet|ExternalOffer|BillingProgram|AlternativeBilling|""" +
                """UserChoiceBilling|launchExternalLink|name="billing_choice_""",
        )
        val main = listOf(File(appSources, "main"), File(appSources, "release"))
        val hits = main.filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.toList()
            }
            .filter { linkOutCode.containsMatchIn(it.readText()) }
            .map { it.relativeTo(repoRoot).path }
        assertTrue("found no main sources: the scan is looking at the wrong directory", main.first().isDirectory)
        assertEquals(
            "These files bring the Play link-out path back. That is a design change with its " +
                "own review (enrolment, Play's APIs, 24-hour reporting), not a code change (MR-924).",
            emptyList<String>(),
            hits,
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
