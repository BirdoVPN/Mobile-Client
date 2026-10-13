package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

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
 *
 * Failure messages carry counts and file paths only, never a property name,
 * a value or a matched line: the build inputs scanned here sit next to
 * credential keys, and the messages are uploaded with the test results.
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
            .count { "EXTERNAL" in it || "LINK_OUT" in it || "LINKOUT" in it }
        assertEquals(
            "BuildConfig has a link-out switch again. The Play build must not be able to " +
                "steer to an external checkout by a flag (MR-924).",
            0,
            switches,
        )
    }

    /**
     * Every Gradle build script in the repo, found by walking it rather than
     * listed, so an added or moved script cannot drop out of the scan
     * (MR-1871 review). Build outputs and tool caches are not walked.
     */
    private val buildScripts: List<File> by lazy {
        val skipped = setOf("build", ".gradle", ".git", ".idea", ".kotlin", ".cxx", "node_modules", "target")
        repoRoot.walkTopDown()
            .onEnter { it == repoRoot || it.name !in skipped }
            .filter { it.isFile && it.name.endsWith(".gradle.kts") }
            .toList()
    }

    /** A name that sounds like a link-out switch, whatever the casing or separator. */
    private val linkOutName = Regex(
        """external.?offer|link.?out|steer|billing.?choice|alternative.?billing|user.?choice""",
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun `the scan sees every build script`() {
        val paths = buildScripts.map { it.relativeTo(repoRoot).invariantSeparatorsPath }
        // app, baselineprofile, shared, the root build script and settings, as of 2026-10-13.
        assertTrue("found ${paths.size} build scripts, expected at least 5: the walk is broken", paths.size >= 5)
        for (expected in listOf("app/build.gradle.kts", "settings.gradle.kts")) {
            assertTrue("the walk did not find $expected", expected in paths)
        }
    }

    @Test
    fun `playBuild is the only Play property the build reads, by any route`() {
        // Every way a build script reads a value from outside (MR-1871: the
        // first version of this guard matched findProperty("play...") only).
        // Only a string-literal name is seen; a name built at run time is not.
        // A tripwire for the ordinary spelling, not a proof.
        val readPattern = Regex(
            """(?:findProperty|hasProperty|property|gradleProperty|getProperty|getenv|""" +
                """environmentVariable|systemProperty)\(\s*"([^"]+)"|""" +
                """(?:extra|properties)\[\s*"([^"]+)"\s*]""",
        )
        val names = buildScripts
            .flatMap { file ->
                readPattern.findAll(file.readText()).map { m ->
                    m.groupValues[1].ifEmpty { m.groupValues[2] }
                }.toList()
            }
            .toSet()
        assertTrue("the scan missed the playBuild read: it is looking at the wrong files", "playBuild" in names)
        val allowed = setOf("playBuild", "BIRDO_PLAY_BUILD")
        assertEquals(
            "The build reads a Play-named property other than playBuild / BIRDO_PLAY_BUILD (count shown). " +
                "The old -PplayExternalOffers switch is what this pins out (MR-924).",
            0,
            names.count { it.contains("play", ignoreCase = true) && it !in allowed },
        )
        assertEquals(
            "The build reads a property named like a link-out switch (count shown, MR-924).",
            0,
            names.count { linkOutName.containsMatchIn(it) },
        )
    }

    @Test
    fun `no build input defines a link-out switch`() {
        // Loaded as a properties file, so `key value` lines and `\` continuations
        // give the key alone, never a value.
        val keys = Properties().apply {
            File(repoRoot, "gradle.properties").inputStream().use { load(it) }
        }.stringPropertyNames()
        assertTrue("gradle.properties has no keys: the scan is broken", keys.isNotEmpty())
        assertEquals(
            "gradle.properties defines a key named like a link-out switch (count shown, MR-924).",
            0,
            keys.count { linkOutName.containsMatchIn(it) },
        )

        // A BuildConfig field set only on some variants (say, only when
        // playBuild is on) never reaches the BuildConfig the test above
        // reflects over, so the scripts are read for it too.
        val fieldPattern = Regex("""buildConfigField\(\s*"[^"]+"\s*,\s*"([^"]+)"""")
        val fields = buildScripts
            .flatMap { file -> fieldPattern.findAll(file.readText()).map { it.groupValues[1] }.toList() }
        assertTrue("found no buildConfigField: the scan is broken", fields.isNotEmpty())
        assertEquals(
            "A build script declares a BuildConfig field named like a link-out switch (count shown, MR-924).",
            0,
            fields.count { linkOutName.containsMatchIn(it) || "EXTERNAL" in it || "OFFER" in it },
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
            .map { it.relativeTo(repoRoot).invariantSeparatorsPath }
            .toList()
        assertEquals("BirdoBillingChoiceSheet.kt is back (MR-924)", emptyList<String>(), sheets)

        // Play's own APIs for selling outside Play Billing (external offers,
        // the billing-program and external-link calls, alternative and
        // user-choice billing), the sheet under another file name, or its
        // strings: any of them is the link-out path coming back. The message
        // names the files, never the matched text.
        val linkOutCode = Regex(
            """BirdoBillingChoiceSheet|ExternalOffer|BillingProgram|AlternativeBilling|""" +
                """UserChoiceBilling|launchExternalLink|name="billing_choice_""",
        )
        val main = listOf(File(appSources, "main"), File(appSources, "release"))
        assertTrue("found no main sources: the scan is looking at the wrong directory", main.first().isDirectory)
        val hits = main.filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.toList()
            }
            .filter { linkOutCode.containsMatchIn(it.readText()) }
            .map { it.relativeTo(repoRoot).invariantSeparatorsPath }
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
