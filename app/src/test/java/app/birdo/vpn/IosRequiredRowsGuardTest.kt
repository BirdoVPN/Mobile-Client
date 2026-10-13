package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The iOS halves of a batch of register rows, pinned from the Android unit
 * tests so every PR checks them, the way IosVersionFloorWiringTest and
 * ReleaseLogGuardTest do: the Swift files involved (views, APIClient,
 * BirdoPQManager) cannot compile into the un-hosted iOS test bundle, and Apple
 * CI only runs when iosApp/ changes. The pure logic is XCTested there
 * (PQNonceTests, CredentialDescriptionTests, VersionFloorAdvisoryTests); this
 * pins the wiring and the resources.
 */
class IosRequiredRowsGuardTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun source(path: String): String {
        val file = File(repoRoot, path)
        assertTrue("scan target is missing: $path — this test would be vacuous", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private val ios = "iosApp/iosApp"
    private val settings by lazy { source("$ios/Views/SettingsView.swift") }

    /** Every Swift source of the app and its extension, as (path, text). */
    private val swiftSources by lazy {
        listOf("$ios", "iosApp/PacketTunnel").flatMap { dir ->
            File(repoRoot, dir).walkTopDown().filter { it.isFile && it.extension == "swift" }
                .map { it.relativeTo(repoRoot).invariantSeparatorsPath to it.readText() }.toList()
        }.also { assertTrue("found only ${it.size} Swift files", it.size > 40) }
    }

    // ── MR-1615: the Notifications toggle switched nothing ───────────────────

    @Test
    fun `there is no Notifications toggle while the app posts no notifications`() {
        val postsNotifications = swiftSources.any { (_, src) ->
            "UNUserNotificationCenter" in src || Regex("""(?m)^\s*import\s+UserNotifications\b""").containsMatchIn(src)
        }
        if (!postsNotifications) {
            assertFalse("a notifications_enabled preference nothing reads", "notifications_enabled" in settings)
            assertFalse(
                "a Notifications toggle with no notification code behind it",
                Regex("""title:\s*"Notifications",""").containsMatchIn(settings),
            )
        }
    }

    // ── MR-1616: Light and System did nothing on a dark-only design ──────────

    @Test
    fun `no theme choice is offered while the app is dark-only`() {
        val darkOnly = source("$ios/Theme/BirdoTheme.swift").contains("dark-only") &&
            source("$ios/BirdoVPNApp.swift").contains(".preferredColorScheme(.dark)")
        assertTrue("the design is no longer dark-only: build the theme control for real", darkOnly)
        assertFalse("an app_theme preference nothing reads", "app_theme" in settings)
        assertFalse("Light / System options that change nothing", "\"Light\"" in settings || "\"System\"" in settings)
    }

    // ── MR-1617: the canonical sign-in / sign-out copy ───────────────────────

    @Test
    fun `sign-in and sign-out use the canonical words`() {
        val login = source("$ios/Views/LoginView.swift")
        val home = source("$ios/Views/HomeView.swift")
        assertFalse("Initialize Uplink" in login)
        assertTrue("the email sign-in button reads Sign in", "PrimaryButton(\"Sign in\",\n                          loadingTitle: \"Signing in…\"" in login)
        listOf("Log Out", "log out?", "Logging out", "\"Logout\"").forEach {
            assertFalse("HomeView still says $it", it in home)
        }
        assertTrue("title: \"Sign out?\"" in home)
        assertTrue("confirmLabel: \"Sign out\"" in home)
    }

    // ── MR-1689: the privacy manifest declares the account identifier ────────

    @Test
    fun `the privacy manifest declares User ID, linked, not tracking, for app functionality`() {
        val manifest = source("$ios/PrivacyInfo.xcprivacy")
            .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        val userId = Regex(
            """<string>NSPrivacyCollectedDataTypeUserID</string>\s*""" +
                """<key>NSPrivacyCollectedDataTypeLinked</key>\s*<true/>\s*""" +
                """<key>NSPrivacyCollectedDataTypeTracking</key>\s*<false/>\s*""" +
                """<key>NSPrivacyCollectedDataTypePurposes</key>\s*<array>\s*""" +
                """<string>NSPrivacyCollectedDataTypePurposeAppFunctionality</string>\s*</array>""",
        )
        assertTrue("NSPrivacyCollectedDataTypeUserID is missing or not linked/app-functionality", userId.containsMatchIn(manifest))
        assertFalse(
            "Other User Content is declared but the app sends no user-generated content",
            "NSPrivacyCollectedDataTypeOtherUserContent" in manifest,
        )
    }

    // ── MR-1727: the account number never reaches the app-switcher snapshot ─

    @Test
    fun `every view of the account number is covered in the app switcher`() {
        val sites = mapOf(
            "$ios/Views/ProfileView.swift" to "Text(number)",
            "$ios/Views/LoginView.swift" to "Text(id)",
            "$ios/Views/HomeView.swift" to "Text(identity)",
        )
        sites.forEach { (path, view) ->
            val src = source(path)
            assertTrue("$path no longer renders $view", view in src)
            val modifiers = src.substringAfter(view).substringBefore("\n\n").substringBefore("Image(")
            assertTrue(
                "$path: $view shows the account number without .accountNumberSnapshotCover()",
                ".accountNumberSnapshotCover()" in modifiers,
            )
        }
        val cover = source("$ios/Views/Components/AccountNumberCover.swift")
        assertTrue("the cover must key on the scene phase", "scenePhase == .active" in cover)
        assertFalse(
            "the cover must not depend on Hide App Contents",
            "biometric_lock" in cover || "biometricLockEnabled" in cover,
        )
    }

    // ── MR-745: the PQ nonce is bounded like Android's ───────────────────────

    @Test
    fun `the iOS PQ nonce has Android's bound and is the only way in`() {
        val androidMax = Regex("""MAX_NONCE_BYTES = (\d+)""")
            .find(source("app/src/main/java/app/birdo/vpn/service/BirdoPqManager.kt"))!!.groupValues[1]
        val nonce = source("$ios/Services/PQNonce.swift")
        assertTrue("iOS must bound the nonce to Android's $androidMax bytes", "static let maxBytes = $androidMax" in nonce)
        val manager = source("$ios/Services/BirdoPQManager.swift")
        assertTrue("BirdoPQManager must decode the nonce through BirdoPQNonce", "BirdoPQNonce.decode(rosenpassEndpointBase64)" in manager)
        assertFalse("an unbounded decode of the nonce is back", "Data(base64Encoded: nonceB64)" in manager)
        val native = source("native/birdo-pq-ios/src/lib.rs")
        assertTrue("the native library must bound it too", "pub const BIRDO_PQ_MAX_NONCE_LEN: usize = $androidMax;" in native)
    }

    // ── MR-1949: no credential-bearing Swift model prints a secret ───────────

    @Test
    fun `every credential-bearing Swift wire model adopts the redacting description`() {
        val redaction = source("$ios/Services/CredentialRedaction.swift")
        val credential = Regex(
            """(?i)\b(let|var)\s+\w*(token|password|code|verifier|secret|privatekey|presharedkey|challenge|nonce|anonymousid|accountnumber|signedtransaction)\w*\s*:\s*String""",
        )
        val files = listOf("$ios/Services/AuthWire.swift", "$ios/Services/ConnectWire.swift", "$ios/Services/AccountDeletion.swift")
        val bearing = files.flatMap { path ->
            val src = source(path)
            Regex("""(?m)^(?:struct|enum) (\w+)\b[^{]*\{""").findAll(src).mapNotNull { m ->
                // The type's own stored properties: up to the next top-level declaration.
                val body = src.substring(m.range.last).substringBefore("\n}\n")
                m.groupValues[1].takeIf { credential.containsMatchIn(body) }
            }.toList()
        }
        assertTrue("found only ${bearing.size} credential-bearing models; the scan is broken", bearing.size >= 12)
        val missing = bearing.filter { "extension $it: CredentialRedacting" !in redaction }
        assertEquals("credential-bearing Swift models with Swift's default (printing) description", emptyList<String>(), missing)
        assertTrue("LoginResult carries the 2FA challenge", "extension LoginResult: CredentialRedacting" in redaction)
    }

    // ── MR-1688: the App Store app carries its open-source notices ──────────

    @Test
    fun `the acknowledgements carry the vendored WireGuardKit notice and are reachable from Settings`() {
        fun words(s: String) = s.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
        val acknowledgements = words(source("$ios/Acknowledgements.txt"))
        val copying = words(source("iosApp/Vendor/wireguard-apple/COPYING"))
        assertTrue("Acknowledgements.txt no longer carries iosApp/Vendor/wireguard-apple/COPYING verbatim", copying in acknowledgements)
        listOf("wireguard-go", "Copyright (C) 2017-2025 WireGuard LLC", "The Go Authors", "Apache License").forEach {
            assertTrue("the acknowledgements lost: $it", it in acknowledgements)
        }
        assertTrue("Settings no longer opens the acknowledgements", "AcknowledgementsView()" in settings)
        assertTrue(
            "the screen must read the bundled file",
            "forResource: \"Acknowledgements\", withExtension: \"txt\"" in source("$ios/Views/AcknowledgementsView.swift"),
        )
        val license = source("LICENSE")
        assertTrue("LICENSE must carve vendored code out of CC BY-NC", "iosApp/Vendor/" in license && "NOT covered by this licence" in license)
    }
}
