package app.birdo.vpn.data.network

import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-2092 (AUD-MOB-02): the app compiles against the OkHttp it runs, and
 * okhttp, okhttp-dnsoverhttps and logging-interceptor are one version.
 *
 * Before this the app declared all three at 4.12.0, but an unused Ktor client
 * in :shared (ktor-client-okhttp) pulled the core up to 5.2.1 on every runtime
 * classpath. The app compiled against 4.12.0, ran 5.2.1, and the DoH resolver
 * (DohResolver, the censorship-resistant lookup path) and the logging
 * interceptor ran their 4.12.0 builds on the 5.2.1 core. Nothing in the build
 * noticed: the next core bump could have broken DoH with no compile error.
 */
class OkHttpVersionAlignmentTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private val artifacts = listOf("okhttp", "okhttp-dnsoverhttps", "logging-interceptor")

    /** Every com.squareup.okhttp3 line of app/gradle.lockfile, as (artifact, version, configurations). */
    private val locked: List<Triple<String, String, List<String>>> by lazy {
        File(repoRoot, "app/gradle.lockfile").readLines()
            .filter { it.startsWith("com.squareup.okhttp3:") && "=" in it }
            .map { line ->
                val (_, artifact, version) = line.substringBefore('=').split(':')
                Triple(artifact, version, line.substringAfter('=').split(','))
            }
            .also { assertTrue("found no OkHttp in app/gradle.lockfile; the scan is broken", it.size >= 3) }
    }

    private val declared: Map<String, String> by lazy {
        val build = File(repoRoot, "app/build.gradle.kts").readText()
        artifacts.associateWith { artifact ->
            Regex("""implementation\("com\.squareup\.okhttp3:${Regex.escape(artifact)}:([^"]+)"\)""")
                .find(build)?.groupValues?.get(1)
                ?: error("com.squareup.okhttp3:$artifact is not declared in app/build.gradle.kts")
        }
    }

    /**
     * `OkHttp.VERSION` written in source is what the compiler saw: on 4.x it is
     * a `const` and is inlined into this class. Read through reflection it is
     * the class that actually loaded. Two different strings is the split itself.
     */
    @Test
    fun `the OkHttp the app compiles against is the one it runs`() {
        val runtime = OkHttp::class.java.getField("VERSION").get(null) as String
        assertEquals(
            "compiled against OkHttp ${OkHttp.VERSION} but OkHttp $runtime is on the runtime " +
                "classpath: something other than app/build.gradle.kts decides the version",
            runtime,
            OkHttp.VERSION,
        )
    }

    @Test
    fun `every OkHttp artifact is locked at one version`() {
        val versions = locked.map { it.second }.toSortedSet()
        assertEquals(
            "app/gradle.lockfile holds more than one OkHttp version ($versions): " +
                locked.map { "${it.first}:${it.second}" },
            1,
            versions.size,
        )
    }

    @Test
    fun `okhttp, DoH and logging are declared at one version and locked at it on the compile and runtime classpaths`() {
        assertEquals(
            "okhttp, okhttp-dnsoverhttps and logging-interceptor must be declared at one version: $declared",
            1,
            declared.values.toSet().size,
        )
        val version = declared.getValue("okhttp")
        val classpaths = listOf(
            "releaseCompileClasspath",
            "releaseRuntimeClasspath",
            "debugCompileClasspath",
            "debugRuntimeClasspath",
            "debugUnitTestRuntimeClasspath",
        )
        val missing = artifacts.flatMap { artifact ->
            classpaths.filter { cfg ->
                locked.none { (a, v, cfgs) -> a == artifact && v == version && cfg in cfgs }
            }.map { cfg -> "$artifact:$version on $cfg" }
        }
        assertEquals(
            "app/gradle.lockfile does not resolve the declared OkHttp $version everywhere; " +
                "regenerate it as the dependencyLocking note in app/build.gradle.kts says",
            emptyList<String>(),
            missing,
        )
    }
}
