package app.birdo.vpn

import app.birdo.vpn.data.model.CredentialFields
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
        // The anonymous tab's button too (Android login_anonymous_submit /
        // login_connecting): it said "Sign In" / "Connecting…".
        val anonymousButton = login.substringBefore(".accessibilityIdentifier(\"login_anonymous_submit\")")
            .substringAfterLast("PrimaryButton(")
        assertTrue(
            "the anonymous sign-in button reads Sign in / Signing in…",
            anonymousButton.startsWith("\"Sign in\",\n                          loadingTitle: \"Signing in…\""),
        )
        assertFalse("PrimaryButton(\"Sign In\"" in login)
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
        val textCover = ".accountNumberSnapshotCover()"
        // The sign-in field: what the user typed is the number too.
        val fieldCover = ".accountNumberSnapshotCover(isInput: true)"
        val sites = listOf(
            Triple("$ios/Views/ProfileView.swift", "Text(number)", textCover),
            Triple("$ios/Views/LoginView.swift", "Text(id)", textCover),
            Triple("$ios/Views/HomeView.swift", "Text(identity)", textCover),
            Triple("$ios/Views/LoginView.swift", "BirdoTextField(\"Account number\",", fieldCover),
        )
        sites.forEach { (path, view, cover) ->
            val src = source(path)
            assertTrue("$path no longer renders $view", view in src)
            val modifiers = src.substringAfter(view).substringBefore("\n\n").substringBefore("Image(")
            assertTrue(
                "$path: $view shows the account number without $cover",
                cover in modifiers,
            )
        }
        val cover = source("$ios/Views/Components/AccountNumberCover.swift")
        assertTrue("the cover must key on the scene phase", "scenePhase == .active" in cover)
        // `.redacted` reaches Text, not necessarily the platform text field
        // behind a TextField, so a field is hidden outright.
        assertTrue("the input cover must hide the field", ".opacity(isInput && scenePhase != .active ? 0 : 1)" in cover)
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

    /**
     * `Type.field` names that match the credential words but carry no secret,
     * as in CredentialFieldGuardTest. Keep it short, and say why for each.
     */
    private val swiftNotSecret = mapOf(
        // ISO 3166 country codes, shown in the server lists.
        "PublicLocation.countryCode" to "ISO country code",
        "ServerInfo.countryCode" to "ISO country code",
        // The server's machine-readable refusal codes ("purchase_not_found").
        "Details.code" to "error code",
        "APIError.code" to "error code",
        "RebuildRefusedError.code" to "error code",
    )

    @Test
    fun `every credential-bearing Swift struct or enum adopts the redacting description`() {
        val redaction = source("$ios/Services/CredentialRedaction.swift")
        // Every Swift file of the app and the extension, at any nesting depth:
        // it used to read three files, so a credential-bearing type anywhere
        // else (VPNManager's tunnel snapshot, the resolved account identity)
        // printed its secrets through Swift's default reflection unseen.
        val found = swiftSources.flatMap { (path, src) ->
            SwiftCredentialTypes.scan(src.replace("\r\n", "\n")).map { it to path }
        }
        assertTrue("found only ${found.size} credential-bearing types; the scan is broken", found.size >= 18)

        val bearing = found.filter { (type, _) -> type.fields.any { "${type.name}.$it" !in swiftNotSecret } }
        val missing = bearing
            .filter { (type, _) ->
                !Regex("""(?m)^extension\s+(?:\w+\.)*${type.name}\s*:\s*CredentialRedacting\b""").containsMatchIn(redaction)
            }
            .map { (type, path) -> "$path: ${type.name}" }
        assertEquals("credential-bearing Swift types with Swift's default (printing) description", emptyList<String>(), missing)

        // An exemption that no longer matches a field is a hole for the next one.
        val stale = swiftNotSecret.keys - found.flatMap { (type, _) -> type.fields.map { "${type.name}.$it" } }.toSet()
        assertEquals("stale swiftNotSecret entries", emptySet<String>(), stale)

        val names = bearing.map { (type, _) -> type.name }
        listOf("LoginResult", "VPNConnectionConfig", "TunnelProfileSnapshot", "Resolved").forEach {
            assertTrue("the scan no longer finds $it", it in names)
        }
    }

    // ── The Swift scanner itself, on fixtures, so a broken scan cannot pass ──

    @Test
    fun `the Swift scan finds nested types, enum payloads and every secret type`() {
        val src = """
            enum Outer {
                struct Nested: Sendable {
                    let isAnonymous: Bool
                    let accountNumber: String?
                }
                static let shared = Outer.self
            }

            final class Holder {
                private struct Snapshot {
                    let providerConfiguration: [String: String]
                    let privateKey: String
                    var wgPsk: Data? = nil
                }
            }

            enum Result {
                case ok(Int)
                case challenged(challengeToken: String, attempts: Int)
            }

            struct Clean {
                let countryName: String
                static let fallbackToken: String = "fake-fixture-token"
                var shownCode: String { "computed, not stored" }
                let hasPassword: Bool
            }
        """.trimIndent()
        val found = SwiftCredentialTypes.scan(src).associate { it.name to it.fields }
        assertEquals(
            mapOf("Nested" to listOf("accountNumber"), "Snapshot" to listOf("privateKey", "wgPsk"), "Result" to listOf("challengeToken")),
            found,
        )
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

/**
 * The Swift source scan behind the MR-1949 guard: every `struct` and `enum`, at
 * any nesting depth, with the credential-named properties it STORES (Swift's
 * default description reflects stored properties and enum payloads; a computed
 * or `static` one never prints). Names are matched by
 * [CredentialFields.isCredentialName], so both platforms share one word list.
 * Relies on the 4-space indentation the iOS sources use: a type's own members
 * sit exactly one level inside it.
 */
internal object SwiftCredentialTypes {

    class Type(val name: String, val fields: List<String>)

    /** Types that can hold a secret's text or bytes. */
    private val secretTypes = setOf("String", "Data", "[String]", "[UInt8]")

    private val declaration =
        Regex("""(?m)^( *)(?:(?:public|internal|fileprivate|private|indirect|final)\s+)*(?:struct|enum)\s+(\w+)\b[^{\n]*\{\s*$""")

    /** A stored instance property: no `static`, and no `{` getter after the type. */
    private val stored = Regex(
        """^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|fileprivate|private(?:\(set\))?|weak|unowned|lazy|nonisolated)\s+)*""" +
            """(?:let|var)\s+(\w+)\s*:\s*(\[?\w+]?)[?!]?\s*(?:=.*)?$""",
    )
    private val payloadCase = Regex("""^(?:indirect\s+)?case\s+\w+\((.*)\)$""")
    private val payloadLabel = Regex("""(\w+)\s*:\s*(\[?\w+]?)[?!]?""")

    fun scan(src: String): List<Type> = declaration.findAll(src).mapNotNull { m ->
        val indent = m.groupValues[1]
        val member = "$indent    "
        val body = src.substring(m.range.last + 1).substringBefore("\n$indent}")
        val fields = body.lines()
            .filter { it.startsWith(member) && !it.startsWith("$member ") }
            .map { it.substringBefore("//").trim() }
            .flatMap { line ->
                stored.matchEntire(line)?.let { listOf(it.groupValues[1] to it.groupValues[2]) }
                    ?: payloadCase.matchEntire(line)?.let { case ->
                        payloadLabel.findAll(case.groupValues[1]).map { it.groupValues[1] to it.groupValues[2] }.toList()
                    }
                    ?: emptyList()
            }
            .filter { (name, type) -> type in secretTypes && CredentialFields.isCredentialName(name) }
            .map { (name, _) -> name }
        if (fields.isEmpty()) null else Type(m.groupValues[2], fields)
    }.toList()
}
