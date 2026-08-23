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
 * P6-CLI-X-01: the unconditional ~60s connection-quality telemetry is GONE
 *              (owner decision 2026-08-19) and must not come back.
 * P1-dk-ssaid: the device id is random and install-scoped — no shipped
 *              source derives an identifier from the hardware.
 * P6-CLI-PERF-01: the globe frame-timing instrumentation (app.birdo.vpn.perf)
 *              is on-device and aggregate ONLY — no network sink, no
 *              persistence, no wall-clock, no per-frame sample log, and the
 *              only per-frame label is a closed set of three quality-tier
 *              strings, passed as a constant at every call site. Its types are
 *              `internal`, i.e. module-visible, so the scans cover the perf
 *              package AND every other shipped file that names them — plus a
 *              containment pin that keeps that second set empty. Performance telemetry that leaked identity, location or
 *              connection metadata would be a far worse defect than a slow
 *              globe, so the boundary is pinned rather than reviewed.
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

    // ── P6-CLI-X-01 ────────────────────────────────────────────────

    @Test
    fun `no periodic connection telemetry survives in the client`() {
        // The client used to POST vpn/quality-report every other heartbeat
        // (~60s) for the whole session, unconditionally and with no user
        // control: a per-minute, server-side timeline of exactly when each
        // account was online, from a product whose promise is that we do not
        // build one. Owner decision 2026-08-19 (same call as desktop): delete
        // it, not gate it. This pin fails if the endpoint, its request model
        // or a caller comes back.
        val offenders = shippedSources().filter { f ->
            val text = f.readText()
            text.contains("quality-report") ||
                text.contains("QualityReport") ||
                text.contains("reportQuality") ||
                text.contains("sendQualityReport")
        }
        assertEquals(
            "P6-CLI-X-01 broken: periodic connection telemetry is back. It was " +
                "deleted by owner decision, not disabled — re-adding any reporter " +
                "needs that decision reversed first. Offenders: " +
                "${offenders.map { it.name }}",
            emptyList<File>(),
            offenders,
        )
    }

    // ── P6-CLI-PERF-01 ───────────────────────────────────────────────────

    /** Every shipped file in the frame-timing package. */
    private fun perfSources(): List<File> {
        val dir = File(repoRoot, "app/src/main/java/app/birdo/vpn/perf")
        assertTrue(
            "the perf package is missing at ${dir.absolutePath} — if the frame " +
                "instrumentation moved, re-pin these scans against its new home",
            dir.isDirectory,
        )
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            "perf package walk found ${files.size} files — these scans would be vacuous",
            files.size >= 4,
        )
        return files
    }

    /**
     * The measurement API: the types that hold, reduce or expose frame
     * durations. Every one of them is `internal`, which in Kotlin means
     * module-wide — the compiler is perfectly happy for a file in `ui/` or
     * `service/` to construct a [app.birdo.vpn.perf.GlobeFrameMonitor], take a
     * snapshot and do anything at all with it.
     */
    private val measurementApi = listOf(
        "GlobeFrameMonitor", "PerfSnapshot", "TierStats", "FrameHistogram",
        "GlobeTag", "percentileUs", "androidx.metrics.performance",
    )

    /**
     * The REAL surface the scans below have to cover: the perf package plus
     * every other shipped file that names any part of the measurement API.
     *
     * Scanning only the package would pin nothing, because the leak can simply
     * be written one directory over — a privacy test that does not constrain
     * the surface it claims to is worse than no test, since it manufactures
     * confidence. Today the containment pin below holds this to exactly the
     * package; if that ever stops being true, these scans follow the data.
     */
    private fun perfSurfaceSources(): List<File> {
        val perf = perfSources()
        val inPackage = perf.map { it.canonicalPath }.toSet()
        val consumers = shippedSources()
            .filter { it.canonicalPath !in inPackage }
            .filter { f -> f.readText().let { t -> measurementApi.any { n -> t.contains(n) } } }
        return perf + consumers
    }

    @Test
    fun `frame timing has no network sink`() {
        // The single most damaging way to get this wrong: a well-meaning
        // "anonymous perf beacon". P6-CLI-X-01 already deleted a per-minute
        // connection report for this reason; a per-session frame report would
        // rebuild the same online-timeline from a different direction.
        val banned = listOf(
            "retrofit", "Retrofit", "okhttp", "OkHttp", "io.ktor", "HttpClient",
            "java.net.URL", "HttpURLConnection", "Socket", "sentry", "Sentry",
            "firebase", "Firebase", "analytics", "Analytics",
        )
        val offenders = perfSurfaceSources().mapNotNull { f ->
            val text = f.readText()
            val hits = banned.filter { text.contains(it) }
            if (hits.isEmpty()) null else "${f.name}: $hits"
        }
        assertEquals(
            "P6-CLI-PERF-01 broken: the frame-timing package can now reach the " +
                "network. It must stay on-device. Offenders: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `frame timing never persists anything`() {
        // Nothing survives the process. An in-memory histogram cannot be seized
        // with the handset or read out of a backup; a file of frame timings can.
        val banned = listOf(
            "DataStore", "dataStore", "SharedPreferences", "getSharedPreferences",
            "AppPreferences", "java.io.File", "FileOutputStream", "openFileOutput",
            "Room", "SQLite",
        )
        val offenders = perfSurfaceSources().mapNotNull { f ->
            val text = f.readText()
            val hits = banned.filter { text.contains(it) }
            if (hits.isEmpty()) null else "${f.name}: $hits"
        }
        assertEquals(
            "P6-CLI-PERF-01 broken: the frame-timing package now writes to " +
                "storage. Offenders: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `frame timing keeps no clock and no per frame sample log`() {
        // A histogram of durations is not a timeline. A list of
        // (timestamp, duration) IS a timeline of when the user was looking at
        // their phone — and JankStats hands us `frameStartNanos` on every single
        // frame, so the only thing keeping it out is that we never read it.
        val banned = listOf(
            "frameStartNanos", "currentTimeMillis", "nanoTime", "SystemClock",
            "Instant", "LocalDate", "LocalTime", "java.util.Date", "Calendar",
        )
        val offenders = perfSurfaceSources().mapNotNull { f ->
            val code = f.readLines().filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            val hits = banned.filter { needle -> code.any { it.contains(needle) } }
            if (hits.isEmpty()) null else "${f.name}: $hits"
        }
        assertEquals(
            "P6-CLI-PERF-01 broken: the frame-timing package now reads a clock " +
                "or retains a per-frame record. It must stay a bucketed " +
                "histogram. Offenders: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the only per frame label is the globe quality tier`() {
        // JankStats attaches arbitrary key/value state to each frame. The
        // tempting next step — "label frames with the selected server so we can
        // see whether the arc is slow" — would put a country, and therefore a
        // location claim, into the measurement surface.
        assertEquals(
            "P6-CLI-PERF-01 broken: the per-frame label set changed. It must stay " +
                "three fixed, user-independent strings.",
            listOf("full", "lite", "off"),
            app.birdo.vpn.perf.GlobePerf.STATE_VALUES,
        )

        val banned = listOf(
            "VpnServer", "countryCode", "selectedServer", "userId", "email",
            "deviceId", "accessToken", "publicKey", "ipAddress", "endpoint",
            "subscription",
        )
        val offenders = perfSurfaceSources().mapNotNull { f ->
            val text = f.readText()
            val hits = banned.filter { text.contains(it) }
            if (hits.isEmpty()) null else "${f.name}: $hits"
        }
        assertEquals(
            "P6-CLI-PERF-01 broken: the frame-timing package now references " +
                "account, server or connection data. Offenders: $offenders",
            emptyList<String>(),
            offenders,
        )

        // The putState call sites are the ONLY way a label reaches a frame, and
        // this looks at EVERY shipped file, not just the perf package —
        // PerformanceMetricsState is public API that any file could call.
        val callSites = shippedSources().filter { it.readText().contains("putState(") }
        assertEquals(
            "P6-CLI-PERF-01 broken: a putState call site outside GlobePerfState",
            listOf("GlobePerf.kt"),
            callSites.map { it.name },
        )
        val putStates = callSites.sumOf { f ->
            Regex("putState\\(").findAll(f.readText()).count()
        }
        assertEquals(
            "P6-CLI-PERF-01 broken: expected exactly the two putState call sites " +
                "in GlobePerfState (set + flush); a third is a new label",
            2,
            putStates,
        )
    }

    @Test
    fun `the frame timing measurement API is confined to its own package`() {
        // This is what makes every scan above mean something. The scans read a
        // directory; the types are `internal`, so the compiler does not. If a
        // file in ui/ or service/ can hold a PerfSnapshot, the scans are
        // pinning a directory rather than a boundary.
        val perfDir = File(repoRoot, "app/src/main/java/app/birdo/vpn/perf").canonicalPath

        // Vacuity guard: a needle that matches nothing anywhere would make this
        // scan pass by spelling mistake.
        val inPackage = perfSources().joinToString(separator = "\n") { it.readText() }
        assertEquals(
            "a measurement-API needle matches nothing in the perf package — this " +
                "scan would be vacuous",
            emptyList<String>(),
            measurementApi.filterNot { inPackage.contains(it) },
        )

        val offenders = shippedSources()
            .filterNot { it.canonicalPath.startsWith(perfDir) }
            .mapNotNull { f ->
                val text = f.readText()
                val hits = measurementApi.filter { text.contains(it) }
                if (hits.isEmpty()) null else "${f.name}: $hits"
            }
        assertEquals(
            "P6-CLI-PERF-01 broken: frame-timing measurement types are reachable " +
                "outside app/.../perf. Everything a file there does with them is " +
                "invisible to the on-device/no-clock/no-sink scans above. Only " +
                "the control surface (GlobePerf, GlobePerfState.set, " +
                "GlobePerfControls, GlobePerfOverlay) may cross the package line. " +
                "Offenders: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every frame label call site passes a fixed GlobePerf constant`() {
        // GlobePerfState.set takes a String and is `internal`, so any file in
        // the module can stamp an arbitrary label onto every frame —
        // `set(view, "server:" + server.countryCode)` compiles today and the
        // closed STATE_VALUES list above does not stop it, because that list
        // pins the constants, not the call sites.
        val calls = shippedSources().flatMap { f ->
            callArgs(f.readText(), "GlobePerfState.set(").map { f.name to it }
        }
        assertTrue(
            "no GlobePerfState.set call site found at all — this scan is vacuous",
            calls.isNotEmpty(),
        )
        val offenders = calls
            .filterNot { (_, args) ->
                args.contains("GlobePerf.STATE_") &&
                    !args.contains('"') && !args.contains('$')
            }
            .map { (name, args) -> "$name: ${args.trim()}" }
        assertEquals(
            "P6-CLI-PERF-01 broken: a frame label is built at the call site " +
                "instead of being one of the three GlobePerf.STATE_* constants. " +
                "That is how a country, a server or an account id gets attached " +
                "to every frame. Offenders: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The argument text of every `needle(...)` call in [text], with nested
     * parentheses balanced so a multi-line call is read whole.
     */
    private fun callArgs(text: String, needle: String): List<String> {
        require(needle.endsWith("("))
        val out = mutableListOf<String>()
        var from = text.indexOf(needle)
        while (from >= 0) {
            var i = from + needle.length - 1
            val start = i + 1
            var depth = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '(') depth++
                if (c == ')') {
                    depth--
                    if (depth == 0) break
                }
                i++
            }
            out += text.substring(start, i.coerceAtMost(text.length))
            from = text.indexOf(needle, from + needle.length)
        }
        return out
    }

    @Test
    fun `the frame timing package never reaches the data layer`() {
        val offenders = shippedSources()
            .filter { it.path.replace('\\', '/').contains("/data/") }
            .filter { it.readText().contains("app.birdo.vpn.perf") }
        assertEquals(
            "P6-CLI-PERF-01 broken: the data (networking) layer now imports the " +
                "frame-timing package — the only road off the device. " +
                "Offenders: ${offenders.map { it.name }}",
            emptyList<File>(),
            offenders,
        )
    }

    @Test
    fun `jankstats is confined to the frame timing package`() {
        val users = shippedSources()
            .filter { it.readText().contains("androidx.metrics.performance") }
            .map { it.name }
            .sorted()
        assertEquals(
            "P6-CLI-PERF-01 broken: JankStats is used outside app/.../perf. Every " +
                "frame-state label must go through GlobePerf, or the closed label " +
                "set pinned above stops meaning anything. Users: $users",
            listOf("GlobeFrameMonitor.kt", "GlobePerf.kt", "GlobePerfOverlay.kt"),
            users,
        )
    }

    @Test
    fun `the perf overlay is off by default in every build`() {
        val gradle = File(repoRoot, "app/build.gradle.kts").readText()
        assertTrue(
            "P6-CLI-PERF-01 broken: the PERF_OVERLAY BuildConfig field is gone",
            gradle.contains("buildConfigField(\"boolean\", \"PERF_OVERLAY\""),
        )
        assertTrue(
            "P6-CLI-PERF-01 broken: perfOverlay no longer defaults to false — a " +
                "shipped release would carry a debug HUD over the Home screen",
            gradle.contains("System.getenv(\"BIRDO_PERF_OVERLAY\"))?.toBoolean() ?: false"),
        )

        // And the runtime gate is a build constant, not something a user, a
        // server response or a remote flag can flip.
        val perf = File(
            repoRoot,
            "app/src/main/java/app/birdo/vpn/perf/GlobePerf.kt",
        ).readText()
        assertTrue(
            "P6-CLI-PERF-01 broken: the HUD gate is no longer a compile-time " +
                "constant, so R8 can no longer remove it from a stock release",
            perf.contains("BuildConfig.DEBUG || BuildConfig.PERF_OVERLAY"),
        )
    }

    // ── P1-dk-ssaid-device-linkage ────────────────────────────────────────

    @Test
    fun `no shipped source reads a hardware-derived device identifier`() {
        // The deviceId must be a random value this install minted, not a
        // function of the handset. Anything derived from device state is the
        // same for every account ever used on it, so two anonymous accounts the
        // user believes are unrelated arrive joinable — and, unlike a stored
        // random id, the user cannot destroy it by uninstalling. SSAID was the
        // one that shipped; the others are the obvious replacements someone
        // would reach for next.
        val banned = listOf(
            "ANDROID_ID",
            "Settings.Secure",
            "Build.getSerial",
            "getImei",
            "getSubscriberId",
            "getMacAddress",
            "AdvertisingIdClient",
        )
        val offenders = shippedSources().mapNotNull { f ->
            // CODE lines only. The kdoc on DeviceInfoProvider names the SSAID
            // API on purpose — it explains why the id is no longer derived from
            // it — and a scan that cannot tell an explanation from a call site
            // would force that explanation to be deleted.
            val code = f.readLines().filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            val hits = banned.filter { needle -> code.any { it.contains(needle) } }
            if (hits.isEmpty()) null else "${f.name}: $hits"
        }
        assertEquals(
            "P1-dk-ssaid-device-linkage broken: a hardware-derived identifier is " +
                "back in shipped source. The device id is deliberately random and " +
                "install-scoped so it dies with an uninstall. Offenders: $offenders",
            emptyList<String>(),
            offenders,
        )

        // Vacuity guard: the provider still exists and still persists an id.
        val provider = File(
            repoRoot,
            "app/src/main/java/app/birdo/vpn/data/auth/DeviceInfoProvider.kt",
        )
        val text = provider.readText()
        assertTrue(
            "DeviceInfoProvider no longer mints a random UUID device id — the " +
                "identity moved; re-pin this scan against its new home",
            text.contains("UUID.randomUUID()") && text.contains("KEY_DEVICE_ID"),
        )
    }
}
