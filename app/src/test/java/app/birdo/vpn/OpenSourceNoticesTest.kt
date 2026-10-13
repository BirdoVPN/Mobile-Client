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
