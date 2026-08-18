package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural regression pins for the mobile client's verified-clean privacy
 * boundaries. These read the REAL shipped sources and build config at test
 * runtime — never a local copy — so the refactor that breaks a promise
 * breaks the build.
 *
 * P6-CLAIM-25: "This list is only retrieved inside the split tunneling view
 *              and is never sent from the device." (PRIVACY.md) — the ONLY
 *              getInstalledApplications call site is SettingsViewModel, and
 *              the AppInfo type / installedApps list never reaches the data
 *              (networking) layer.
 * P6-CLI-A-08: ProGuard strips all android.util.Log calls in release, and no
 *              println/printStackTrace exists in Android or shared source.
 * P6-CLI-A-09: OkHttp logging is DEBUG-only, HEADERS-level, with credential
 *              headers redacted; backups are disabled in the manifest.
 */
class PrivacyBoundaryTest {

    /** Repo root, found by walking up from the test working dir. */
    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    /** Every shipped Kotlin/Java file under app/src/main and shared/src. */
    private fun shippedSources(): List<File> {
        val roots = listOf(File(repoRoot, "app/src/main"), File(repoRoot, "shared/src"))
        val files = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .toList()
        }
        // Vacuity guard: an empty walk would make every scan below pass for free.
        assertTrue(
            "source walk found only ${files.size} files — the walker is broken " +
                "and these scans would be vacuous",
            files.size >= 50,
        )
        return files
    }

    // ── P6-CLAIM-25 ──────────────────────────────────────────────────────

    @Test
    fun `installed apps list is read only in the settings viewmodel`() {
        val callers = shippedSources().filter { it.readText().contains("getInstalledApplications") }
        assertEquals(
            "P6-CLAIM-25 broken: getInstalledApplications must be called ONLY from " +
                "SettingsViewModel (the split-tunneling view). New call sites: " +
                callers.map { it.name },
            listOf("SettingsViewModel.kt"),
            callers.map { it.name },
        )
    }

    @Test
    fun `installed apps list never reaches the data layer`() {
        // The data layer holds every DTO, Retrofit service and repository —
        // the only road off the device. If AppInfo or the installedApps list
        // ever appears there, the PRIVACY.md promise is in question.
        val appInfoRegex = Regex("""\bAppInfo\b""")
        val offenders = shippedSources()
            .filter { it.path.replace('\\', '/').contains("/data/") }
            .filter { f ->
                val text = f.readText()
                appInfoRegex.containsMatchIn(text) ||
                    text.contains("installedApps") ||
                    text.contains("getInstalledApplications")
            }
        assertEquals(
            "P6-CLAIM-25 broken: the data (networking) layer now references the " +
                "installed-apps list — PRIVACY.md promises it is never sent from " +
                "the device. Offenders: ${offenders.map { it.name }}",
            emptyList<File>(),
            offenders,
        )
        // Vacuity guard: the viewmodel still holds the list under these names.
        val viewModel =
            File(repoRoot, "app/src/main/java/app/birdo/vpn/ui/viewmodel/SettingsViewModel.kt")
        val vmText = viewModel.readText()
        assertTrue(
            "SettingsViewModel no longer references AppInfo/installedApps — the " +
                "feature moved; re-pin this scan against its new home",
            appInfoRegex.containsMatchIn(vmText) && vmText.contains("installedApps"),
        )
    }

    // ── P6-CLI-A-08 ──────────────────────────────────────────────────────

    @Test
    fun `release proguard strips every android util Log method`() {
        val rules = File(repoRoot, "app/proguard-rules.pro").readText()
        val start = rules.indexOf("-assumenosideeffects class android.util.Log")
        assertTrue(
            "P6-CLI-A-08 broken: proguard-rules.pro no longer strips " +
                "android.util.Log — release builds would log to logcat again",
            start >= 0,
        )
        val block = rules.substring(start, rules.indexOf('}', start) + 1)
        for (method in listOf("v", "d", "i", "w", "e", "wtf")) {
            assertTrue(
                "P6-CLI-A-08 broken: Log.$method(...) is no longer stripped by the " +
                    "-assumenosideeffects block",
                block.contains("public static int $method(...);"),
            )
        }

        val gradle = File(repoRoot, "app/build.gradle.kts").readText()
        val releaseStart = gradle.indexOf("release {")
        assertTrue("release block missing from app/build.gradle.kts", releaseStart >= 0)
        val releaseBlock = gradle.substring(releaseStart, gradle.indexOf("proguardFiles", releaseStart))
        assertTrue(
            "P6-CLI-A-08 broken: release no longer sets isMinifyEnabled = true — " +
                "without minification the Log-stripping rules never run",
            releaseBlock.contains("isMinifyEnabled = true"),
        )
        assertTrue(
            "P6-CLI-A-08 broken: release no longer applies proguard-rules.pro",
            gradle.substring(releaseStart).contains("\"proguard-rules.pro\""),
        )
    }

    @Test
    fun `no println or printStackTrace in shipped android or shared source`() {
        val offenders = shippedSources().filter { f ->
            val text = f.readText()
            text.contains("println(") || text.contains("printStackTrace(")
        }
        assertEquals(
            "P6-CLI-A-08 broken: println/printStackTrace bypass the ProGuard " +
                "Log-stripping entirely and reach logcat in release. Offenders: " +
                "${offenders.map { it.name }}",
            emptyList<File>(),
            offenders,
        )
    }

    // ── P6-CLI-A-09 ──────────────────────────────────────────────────────

    @Test
    fun `okhttp logging is debug gated headers only and redacts credentials`() {
        val file = File(repoRoot, "app/src/main/java/app/birdo/vpn/di/NetworkModule.kt")
        val text = file.readText()

        val construction = text.indexOf("HttpLoggingInterceptor()")
        assertTrue(
            "NetworkModule no longer builds an HttpLoggingInterceptor — if logging " +
                "moved, re-pin this scan against its new home",
            construction >= 0,
        )
        assertEquals(
            "P6-CLI-A-09 broken: more than one HttpLoggingInterceptor construction — " +
                "every one must be inside the DEBUG gate below",
            text.lastIndexOf("HttpLoggingInterceptor()"),
            construction,
        )

        // The construction must sit INSIDE an `if (BuildConfig.DEBUG)` block:
        // find the gate before it and brace-match to the block's end.
        val gate = text.lastIndexOf("if (BuildConfig.DEBUG)", construction)
        assertTrue(
            "P6-CLI-A-09 broken: the logging interceptor is no longer gated on " +
                "BuildConfig.DEBUG — release builds would log request lines",
            gate >= 0,
        )
        val open = text.indexOf('{', gate)
        var depth = 0
        var blockEnd = -1
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) { blockEnd = i; break }
            }
        }
        assertTrue(
            "P6-CLI-A-09 broken: HttpLoggingInterceptor is constructed OUTSIDE the " +
                "if (BuildConfig.DEBUG) block",
            construction in (open + 1) until blockEnd,
        )

        assertTrue(
            "P6-CLI-A-09 broken: logging level is no longer HEADERS",
            text.contains("HttpLoggingInterceptor.Level.HEADERS"),
        )
        assertTrue(
            "P6-CLI-A-09 broken: Level.BODY would log WireGuard private keys, " +
                "passwords and tokens to logcat",
            !text.contains("Level.BODY"),
        )
        for (header in listOf(
            "Authorization", "Cookie", "Set-Cookie",
            "X-CSRF-Token", "X-Service-Auth", "X-Refresh-Token",
        )) {
            assertTrue(
                "P6-CLI-A-09 broken: credential header $header is no longer redacted " +
                    "from debug HTTP logs",
                text.contains("redactHeader(\"$header\")"),
            )
        }
    }

    @Test
    fun `backups are disabled in the manifest`() {
        val manifest = File(repoRoot, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "P6-CLI-A-09 broken: android:allowBackup is no longer \"false\" — ADB/cloud " +
                "backup would exfiltrate the auth tokens and WireGuard keys",
            manifest.contains("android:allowBackup=\"false\""),
        )
        assertTrue(
            "P6-CLI-A-09 broken: android:fullBackupContent is no longer \"false\"",
            manifest.contains("android:fullBackupContent=\"false\""),
        )
    }
}
