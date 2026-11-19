package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.internal.dependency.DependencyJar
import org.robolectric.internal.dependency.DependencyResolver
import org.robolectric.internal.dependency.LocalDependencyResolver
import org.robolectric.plugins.DefaultSdkProvider
import java.io.File
import java.nio.file.Files

/**
 * MR-2490: Robolectric runs on SDK jars Gradle resolved and verified, and never
 * downloads one while the tests run.
 *
 * app/build.gradle.kts declares the android-all-instrumented jar as the
 * `robolectricAndroidAll` dependency (version in app/gradle.lockfile, SHA-256
 * in gradle/verification-metadata.xml), syncs it into one directory and hands
 * that to every test JVM as robolectric.offline + robolectric.dependency.dir.
 * Without those two properties Robolectric falls back to its Maven resolver
 * and fetches the jar from Maven Central inside the test run, which is what
 * failed run 44430249006. The Robolectric tests themselves would keep passing
 * whenever that fetch happened to work, so only this class notices the wiring
 * is gone.
 *
 * Plain JUnit, not Robolectric. It asks Robolectric's own DefaultSdkProvider
 * which coordinate each SDK level the tests pin needs, through the same
 * LocalDependencyResolver the offline properties select, so it cannot reach
 * the network itself.
 */
class RobolectricSdkJarsTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private val offlineDir: File?
        get() = System.getProperty("robolectric.dependency.dir")?.let(::File)

    @Test
    fun `the test JVM tells Robolectric to stay offline`() {
        assertEquals(
            "robolectric.offline is not set for the test JVM: Robolectric downloads its SDK " +
                "jars from Maven Central during the test run again. See robolectricAndroidAll " +
                "in app/build.gradle.kts.",
            "true",
            System.getProperty("robolectric.offline"),
        )
        val dir = offlineDir
        assertTrue(
            "robolectric.dependency.dir is not set, or not a directory ($dir). It must be " +
                "the directory syncRobolectricAndroidAll fills (app/build.gradle.kts).",
            dir != null && dir.isDirectory,
        )
    }

    @Test
    fun `every SDK level a test pins has its jar in the offline directory`() {
        val levels = pinnedSdkLevels()
        assertTrue("found no Robolectric SDK level pinned under app/src/test", levels.isNotEmpty())
        val local = LocalDependencyResolver(offlineDir ?: error("robolectric.dependency.dir is not set"))

        val missing = mutableListOf<String>()
        for (level in levels) {
            var wanted: DependencyJar? = null
            val provider = DefaultSdkProvider(
                DependencyResolver { jar ->
                    wanted = jar
                    local.getLocalArtifactUrl(jar)
                },
            )
            val sdk = provider.sdks.singleOrNull { it.apiLevel == level }
            if (sdk == null) {
                missing += "SDK $level (this Robolectric does not know it)"
                continue
            }
            try {
                assertTrue(Files.isRegularFile(sdk.jarPath))
            } catch (e: IllegalArgumentException) {
                missing += "SDK $level: ${wanted?.shortName}"
            }
        }
        assertTrue(
            "Robolectric needs SDK jars that robolectricAndroidAll does not provide: $missing. " +
                "Declare each coordinate as robolectricAndroidAll(\"<coordinate>\") in " +
                "app/build.gradle.kts, add its line to app/gradle.lockfile and its entries to " +
                "gradle/verification-metadata.xml (gradle-verification-metadata.yml writes them).",
            missing.isEmpty(),
        )
    }

    /** The levels in every `sdk = [...]` of a Robolectric Config annotation under app/src/test. */
    private fun pinnedSdkLevels(): Set<Int> {
        val config = Regex("""@Config\(([^)]*)\)""")
        val sdk = Regex("""\bsdk\s*=\s*\[([^\]]*)]""")
        val levels = sortedSetOf<Int>()
        File(repoRoot, "app/src/test").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                config.findAll(file.readText()).forEach { annotation ->
                    sdk.find(annotation.groupValues[1])?.groupValues?.get(1)
                        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                        ?.forEach { value ->
                            levels += value.toIntOrNull() ?: error(
                                "${file.relativeTo(repoRoot)} pins SDK level '$value'; write it " +
                                    "as a number so this test can check its jar.",
                            )
                        }
                }
            }
        return levels
    }
}
