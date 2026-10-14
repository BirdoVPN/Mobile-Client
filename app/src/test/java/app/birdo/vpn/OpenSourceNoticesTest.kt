package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-1686: the Android app ships the open-source notices its licences require,
 * reachable from Settings, and the list cannot fall behind what is shipped.
 *
 * Before this the APK had no licences screen and no notice file, although it
 * ships Xray-core (MPL-2.0, whose source must be pointed to), the WireGuard
 * tunnel library with wireguard-go, a Rust library linking ~40 crates, and
 * ~250 Maven artifacts. The notices are a hand-written asset (no licence
 * plugin, no new dependency), so this test is what keeps it honest:
 *
 *  - every artifact on the release runtime classpath (app/gradle.lockfile)
 *    matches one entry's `Maven:` pattern, and every pattern still matches a
 *    shipped artifact;
 *  - the Xray-core entry names the exact version and commit
 *    scripts/provision_xray_engines.sh pins, its licence and a source link;
 *  - every crate librosenpass_jni.so declares is named;
 *  - the licence texts the entries point to are present;
 *  - the asset ships (src/main/assets) and Settings links to the screen.
 */
class OpenSourceNoticesTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun text(path: String): String {
        val file = File(repoRoot, path)
        assertTrue("$path is missing", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private val notices by lazy { text("app/src/main/assets/third_party_notices.txt") }

    /** The artifacts the release APK/AAB actually packages, as group:artifact. */
    private val shipped: List<String> by lazy {
        text("app/gradle.lockfile").lines()
            .filter { !it.startsWith("#") && "=" in it }
            .filter { line -> "releaseRuntimeClasspath" in line.substringAfter('=').split(',') }
            .map { line -> line.substringBefore('=').split(':').take(2).joinToString(":") }
            .also { assertTrue("found only ${it.size} shipped artifacts; the lockfile scan is broken", it.size > 100) }
    }

    /** Every `Maven:` pattern in the notices: `group:artifact`, `group:*` or `prefix.*:*`. */
    private val patterns: List<String> by lazy {
        Regex("""(?m)^ {2}Maven: (.+)$""").findAll(notices)
            .flatMap { m -> m.groupValues[1].split(',').map { it.trim() } }
            .toList()
            .also { assertTrue("found only ${it.size} Maven patterns", it.size > 20) }
    }

    private fun matches(pattern: String, coordinate: String): Boolean {
        val (pg, pa) = pattern.split(':', limit = 2)
        val (g, a) = coordinate.split(':', limit = 2)
        val groupOk = if (pg.endsWith(".*")) g.startsWith(pg.removeSuffix("*")) else g == pg
        return groupOk && (pa == "*" || pa == a)
    }

    @Test
    fun `every shipped artifact has a notice entry`() {
        val missing = shipped.filter { c -> patterns.none { p -> matches(p, c) } }
        assertEquals(
            "release runtime artifacts with no entry in third_party_notices.txt; add the component, " +
                "its licence and a Maven: pattern",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `every notice entry still names something shipped`() {
        val stale = patterns.filter { p -> shipped.none { c -> matches(p, c) } }
        assertEquals("Maven: patterns that match no shipped artifact", emptyList<String>(), stale)
    }

    @Test
    fun `Xray-core is listed at the pinned version and commit, with its licence and source`() {
        val script = text("scripts/provision_xray_engines.sh")
        val version = Regex("""(?m)^XRAY_VERSION="v([^"]+)"""").find(script)!!.groupValues[1]
        val commit = Regex("""(?m)^XRAY_COMMIT="([0-9a-f]{40})"""").find(script)!!.groupValues[1]
        val entry = notices.substringAfter("Xray-core $version (libxray.so", "")
        assertTrue("no Xray-core $version entry: the engine was bumped without its notice", entry.isNotEmpty())
        val head = entry.substringBefore("\n\n")
        assertTrue("the Xray-core entry must name MPL-2.0", "MPL-2.0" in head)
        assertTrue(
            "the Xray-core entry must link the source of the exact tag",
            "https://github.com/XTLS/Xray-core/tree/v$version" in head,
        )
        assertTrue("the Xray-core entry must name the pinned commit", commit in head)
    }

    // ── L1 (owner decision 2026-10-14): the corresponding source ships ──────

    private val xrayVersion by lazy {
        Regex("""(?m)^XRAY_VERSION="(v[^"]+)"""").find(text("scripts/provision_xray_engines.sh"))!!.groupValues[1]
    }
    private val sourceAsset by lazy { "xray-core-$xrayVersion-corresponding-source.tar.gz" }

    /** (module, licence) of every GPL/LGPL/MPL row of scripts/xray-go-modules.txt. */
    private val copyleftModules by lazy {
        text("scripts/xray-go-modules.txt").lines()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .map { it.trim().split(Regex("""\s{2,}""")) }
            .filter { cols -> Regex("""\b(?:L?GPL|MPL)-""").containsMatchIn(cols[2]) }
            .map { cols -> cols[0] to cols[2] }
            .also { assertTrue("found only ${it.size} copyleft modules in the table", it.size >= 3) }
    }

    @Test
    fun `the Xray entry points at the release's source asset first, upstream second`() {
        val entry = notices.substringAfter("Xray-core ${xrayVersion.removePrefix("v")} (libxray.so").substringBefore("\n\n")
        val source = entry.indexOf("  Source: $sourceAsset")
        val upstream = entry.indexOf("  Upstream: https://github.com/XTLS/Xray-core/tree/$xrayVersion")
        assertTrue("the Xray entry does not name the release asset $sourceAsset as its source", source >= 0)
        assertTrue("the Xray entry lost its upstream link", upstream >= 0)
        assertTrue("the release asset must come first, upstream second", source < upstream)
        assertTrue(
            "the Xray entry must say where the asset is",
            "https://github.com/BirdoVPN/Mobile-Client/releases/download/android-v<version>/$sourceAsset" in entry,
        )
        assertTrue(
            "the script must write the asset under the name the notices give",
            "prefix = f\"xray-core-{version}-corresponding-source\"" in text("scripts/xray_corresponding_source.py"),
        )
        copyleftModules.forEach { (module, licence) ->
            assertTrue("$module ($licence) is in libxray.so but not named in the Xray entry", module in entry)
        }
    }

    @Test
    fun `the LGPL module's licence, exception and all, is included`() {
        assertTrue(copyleftModules.any { (module, licence) -> module == "github.com/juju/ratelimit" && "LGPL-3.0" in licence })
        val section = notices.substringAfter("LGPL-3.0 with a linking exception: github.com/juju/ratelimit (in libxray.so)\n", "")
            .substringBefore("\n--------------------------------------------------------------------------------\n")
        assertTrue("juju/ratelimit's licence is not in the notices", section.isNotEmpty())
        listOf(
            "As a special exception to the GNU Lesser General Public License version 3",
            "                   GNU LESSER GENERAL PUBLIC LICENSE",
            "  4. Combined Works.",
            "  6. Revised Versions of the GNU Lesser General Public License.",
        ).forEach { assertTrue("juju/ratelimit's licence lost: $it", it in section) }
    }

    @Test
    fun `every release builds, checks and publishes the source asset`() {
        val script = text("scripts/provision_xray_engines.sh")
        assertTrue("the tag archive is not pinned", Regex("""(?m)^XRAY_SOURCE_SHA256="[0-9a-f]{64}"$""").containsMatchIn(script))
        val release = text(".github/workflows/android.yml")
        val build = release.substringAfter("- name: Build the Xray corresponding-source asset", "")
        assertTrue("the release job no longer builds the source asset", build.isNotEmpty())
        assertTrue(
            "the release must check the asset against the engines it ships",
            "--binaries app/src/main/jniLibs/*/libxray.so" in build.substringBefore("- name:"),
        )
        assertTrue(
            "the asset must be built before the APK is",
            release.indexOf("- name: Build the Xray corresponding-source asset") <
                release.indexOf("- name: Build Release APK and AAB"),
        )
        assertTrue("the asset is not in SHA256SUMS.txt", "sha256sum xray-core-*-corresponding-source.tar.gz" in release)
        assertTrue(
            "the draft release does not attach the asset",
            "release-apk/xray-core-*-corresponding-source.tar.gz" in release.substringAfter("draft-release:"),
        )
        val ci = text(".github/workflows/android-ci.yml")
        assertTrue(
            "pull-request CI must build the asset against the provisioned engines",
            "python3 scripts/xray_corresponding_source.py --out \"\$RUNNER_TEMP/xray-source\" \\\n" +
                "            --binaries app/src/main/jniLibs/*/libxray.so" in ci,
        )
    }

    @Test
    fun `every native engine is named`() {
        listOf("libxray.so", "libwg-go.so", "librosenpass_jni.so", "wireguard-go").forEach {
            assertTrue("$it is shipped but not named in the notices", it in notices)
        }
    }

    @Test
    fun `every crate the PQ library declares is named`() {
        val cargo = text("native/rosenpass-jni/Cargo.toml")
        val sections = Regex("""(?m)^\[(.+)]\s*$""").findAll(cargo).toList()
        val crates = sections.withIndex()
            .filter { (_, m) -> m.groupValues[1].endsWith("dependencies") && "dev-" !in m.groupValues[1] }
            .flatMap { (i, m) ->
                val end = sections.getOrNull(i + 1)?.range?.first ?: cargo.length
                Regex("""(?m)^([A-Za-z0-9_-]+)\s*=""").findAll(cargo.substring(m.range.last + 1, end))
                    .map { it.groupValues[1] }
            }
        assertTrue("found only ${crates.size} crates in rosenpass-jni", crates.size >= 6)
        val rust = notices.substringAfter("BirdoPQ native library (librosenpass_jni.so)").substringBefore("\n\n")
        val missing = crates.filter { !Regex("""(?<![\w-])${Regex.escape(it)}(?![\w-])""").containsMatchIn(rust) }
        assertEquals("rosenpass-jni dependencies with no notice entry", emptyList<String>(), missing)
    }

    @Test
    fun `the licence texts the entries rely on are included`() {
        listOf(
            "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", // Apache-2.0
            "Mozilla Public License Version 2.0",
            "Permission is hereby granted, free of charge", // MIT
            "Redistribution and use in source and binary forms", // BSD 3-Clause
            "Copyright (C) 2017-2025 WireGuard LLC",
            "Copyright (c) 2019 Sentry",
        ).forEach { assertTrue("the notices lost: $it", it in notices) }
    }

    /**
     * libxray.so contains github.com/sagernet/sing and sing-shadowsocks, which
     * are GPL-3.0-or-later. GPL-3.0 (sections 4 and 6) requires a copy of the
     * licence to accompany the program; a link to it is not a copy. Owner
     * ruling 2026-10-13: comply through the notices. So while libxray.so is
     * listed, the complete text must be there, verbatim: the gnu.org
     * gpl-3.0.txt is 674 lines and 35,149 characters, with sections 0 to 17.
     */
    @Test
    fun `the full GPL-3 text ships while libxray is listed`() {
        if ("libxray.so" !in notices) return
        val first = "                    GNU GENERAL PUBLIC LICENSE\n                       Version 3, 29 June 2007\n"
        val last = "<https://www.gnu.org/licenses/why-not-lgpl.html>.\n"
        val start = notices.indexOf(first)
        assertTrue("libxray.so is listed but the GPL-3.0 text is not in the notices", start >= 0)
        val end = notices.indexOf(last, start)
        assertTrue("the GPL-3.0 text is cut short (no closing paragraph)", end > start)
        val gpl = notices.substring(start, end + last.length)
        assertEquals("the GPL-3.0 text is not the verbatim gnu.org text (line count)", 674, gpl.lines().size - 1)
        assertEquals("the GPL-3.0 text is not the verbatim gnu.org text (length)", 35_149, gpl.length)
        listOf(
            "0. Definitions.", "1. Source Code.", "2. Basic Permissions.",
            "3. Protecting Users' Legal Rights From Anti-Circumvention Law.",
            "4. Conveying Verbatim Copies.", "5. Conveying Modified Source Versions.",
            "6. Conveying Non-Source Forms.", "7. Additional Terms.", "8. Termination.",
            "9. Acceptance Not Required for Having Copies.",
            "10. Automatic Licensing of Downstream Recipients.", "11. Patents.",
            "12. No Surrender of Others' Freedom.",
            "13. Use with the GNU Affero General Public License.",
            "14. Revised Versions of this License.", "15. Disclaimer of Warranty.",
            "16. Limitation of Liability.", "17. Interpretation of Sections 15 and 16.",
            "END OF TERMS AND CONDITIONS",
        ).forEach { heading ->
            assertTrue(
                "the GPL-3.0 text lost: $heading",
                Regex("""(?m)^ +${Regex.escape(heading)}$""").containsMatchIn(gpl),
            )
        }
        val xray = notices.substringAfter("Xray-core ").substringBefore("\n\n")
        assertTrue(
            "the Xray-core entry must say the GPL modules' licence text is included",
            "sing-shadowsocks (GNU General Public License v3.0 or\n    later; full text below)" in xray,
        )
    }

    @Test
    fun `the notices ship in the app and Settings opens them`() {
        val screen = text("app/src/main/java/app/birdo/vpn/ui/screen/LicensesScreen.kt")
        assertTrue(
            "LicensesScreen must read the asset this test checks",
            "NOTICES_ASSET = \"third_party_notices.txt\"" in screen,
        )
        val settings = text("app/src/main/java/app/birdo/vpn/ui/screen/SettingsScreen.kt")
        assertTrue("Settings has no licences row", "onClick = onOpenLicenses" in settings)
        val nav = text("app/src/main/java/app/birdo/vpn/ui/navigation/BirdoNavGraph.kt")
        assertTrue("the licences row goes nowhere", "onOpenLicenses = { navController.navigate(Screen.Licenses.route) }" in nav)
        assertTrue("the licences route has no screen", "LicensesScreen(" in nav)
    }
}
