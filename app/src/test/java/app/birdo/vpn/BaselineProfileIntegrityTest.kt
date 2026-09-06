package app.birdo.vpn

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the one property of the baseline profile that nothing else can check:
 * that it was RECORDED rather than written.
 *
 * Issue #358 exists because a baseline profile is uniquely un-falsifiable by the
 * normal build. Android AOT-compiles and pre-pages exactly the classes and
 * methods the profile names, so a guessed profile spends the startup budget on
 * code the app does not run at launch and makes cold start SLOWER. A guess
 * compiles, packages, installs, ships and shows up green everywhere. Two
 * hand-written profiles reached review on this repository before this test
 * existed.
 *
 * A test cannot prove provenance. What it can do is make the cheap forgeries
 * fail: a real recording of this app is tens of thousands of ART descriptors,
 * dominated by fully-qualified method signatures, spanning the app's own classes
 * AND the frameworks its startup path drags in. Nobody types that by hand.
 *
 * Regenerate with `scripts/generate-baseline-profile.sh`. Never edit the file.
 */
class BaselineProfileIntegrityTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private val profileDir: File
        get() = File(repoRoot, "app/src/release/generated/baselineProfiles")

    private val profile: File
        get() = File(profileDir, "baseline-prof.txt")

    @Test
    fun `the release variant ships a baseline profile`() {
        assertTrue(
            "${profile.relativeTo(repoRoot)} is missing. The release build then ships no " +
                "profile at all and every cold start is interpreted from scratch. " +
                "Record one with scripts/generate-baseline-profile.sh — do NOT write one.",
            profile.isFile,
        )
        assertTrue(
            "A startup profile should sit beside the baseline profile; AGP uses it for " +
                "dex layout, which is a separate win from AOT compilation.",
            File(profileDir, "startup-prof.txt").isFile,
        )
    }

    @Test
    fun `the profile is a recording, not a hand-written guess`() {
        val lines = profile.readLines().filter { it.isNotBlank() }

        // A recorded cold start of a Compose + Hilt + OkHttp + Sentry app is tens
        // of thousands of entries. Both hand-written attempts were under a
        // thousand. 5000 is far below a genuine recording of THIS app (the one
        // committed with this test is ~22,700 lines) and far above anything a
        // person would produce by hand, so it fails forgeries without failing on
        // ordinary startup-path drift.
        assertTrue(
            "The baseline profile has only ${lines.size} entries. A recorded cold start of " +
                "this app produces tens of thousands. This looks hand-written or truncated; " +
                "re-record it with scripts/generate-baseline-profile.sh.",
            lines.size >= 5_000,
        )

        // ART profile syntax: `[flags]Lpkg/Class;` for a class and
        // `[flags]Lpkg/Class;->method(params)ret` for a method. A recording is
        // overwhelmingly METHODS, because that is what the profiler observes. A
        // guess is overwhelmingly bare class names, because that is what a person
        // can plausibly write down.
        val methodEntries = lines.count { it.contains(";->") }
        val methodRatio = methodEntries.toDouble() / lines.size
        assertTrue(
            "Only ${"%.1f".format(methodRatio * 100)}% of the profile's entries are method " +
                "descriptors ($methodEntries of ${lines.size}). A real recording is mostly " +
                "methods with full signatures; a list of bare class names is the signature of " +
                "a hand-written profile.",
            methodRatio >= 0.7,
        )

        // Every line must be a syntactically valid ART profile entry. A stray
        // comment, a heading or an editor artefact means the file has been
        // touched by hand.
        val malformed = lines.filterNot { line ->
            line.trimStart('H', 'S', 'P').startsWith("L") && line.contains(";")
        }
        assertTrue(
            "The profile contains ${malformed.size} entries that are not ART descriptors, " +
                "starting with: ${malformed.take(3)}. Generated profiles contain nothing else, " +
                "so this file has been edited by hand.",
            malformed.isEmpty(),
        )
    }

    @Test
    fun `the profile covers this app and the frameworks its startup path uses`() {
        val lines = profile.readLines()

        val ownClasses = lines
            .mapNotNull { Regex("""L(app/birdo/vpn/[A-Za-z0-9/_${'$'}]+);""").find(it)?.groupValues?.get(1) }
            .toSet()
        assertTrue(
            "Only ${ownClasses.size} of the app's own classes appear in the profile. A cold " +
                "start that reaches the first screen touches far more than that, so this was " +
                "recorded against something other than this app — or was not recorded at all.",
            ownClasses.size >= 100,
        )

        // The startup path is not just app code. If the frameworks the
        // Application and first Activity pull in are absent, whatever produced
        // this file did not observe a real launch.
        for (prefix in listOf(
            "Landroidx/compose/runtime/",   // the UI is Compose
            "Ldagger/hilt/",                // the Application builds a Hilt graph
            "Landroidx/lifecycle/",         // ComponentActivity/ViewModel machinery
        )) {
            assertTrue(
                "No entries under $prefix. That code runs on every cold start of this app, " +
                    "so a recording cannot omit it.",
                lines.any { it.contains(prefix) },
            )
        }
    }

    @Test
    fun `profileinstaller stays on the release runtime classpath`() {
        // Play delivers cloud profiles for most installs, but the FIRST launch
        // after a sideload, an F-Droid install, or an install Play has not yet
        // profiled relies on androidx.profileinstaller to apply the bundled one.
        // It reached the classpath transitively for a long time, which meant the
        // whole feature could have been disarmed by an unrelated dependency bump
        // with nothing failing. app/build.gradle.kts now declares it directly;
        // this asserts the result rather than the declaration.
        val lock = File(repoRoot, "app/gradle.lockfile").readLines()
        val entry = lock.firstOrNull { it.startsWith("androidx.profileinstaller:profileinstaller:") }
        assertTrue(
            "androidx.profileinstaller is absent from app/gradle.lockfile, so the shipped " +
                "baseline profile is never installed on devices that do not get a cloud " +
                "profile from Play.",
            entry != null,
        )
        assertTrue(
            "androidx.profileinstaller is locked but not on releaseRuntimeClasspath: $entry",
            entry!!.substringAfter("=").split(",").contains("releaseRuntimeClasspath"),
        )
    }
}
