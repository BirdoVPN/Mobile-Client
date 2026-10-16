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
            Triple("$ios/Views/ProfileView.swift", "Text(shownAccountNumber(number))", textCover),
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

    // ── #485 review: account calls follow the signed-in state ───────────────

    @Test
    fun `account calls follow AuthViewModel's signed-in state, not the keychain alone`() {
        // GuestShellTests pins AccountSession itself; this pins that it is
        // what hasSession asks, and that the app root wires the signed-in
        // state in. A keychain-only hasSession sent a previous install's
        // tokens after a fresh install's clean-up could not finish.
        val vpn = source("$ios/ViewModels/VpnViewModel.swift")
        val hasSession = vpn.substringAfter("var hasSession: Bool {").substringBefore("\n    }")
        assertTrue(
            "hasSession must ask AccountSession with the signed-in state",
            "AccountSession.allowsAccountCalls(" in hasSession && "signedIn: isSignedIn()" in hasSession,
        )
        assertTrue("isSignedIn must default to signed out", "var isSignedIn: @MainActor () -> Bool = { false }" in vpn)
        val wiring = "vpnVM.isSignedIn = { [weak authVM] in authVM?.isLoggedIn ?? false }"
        val root = source("$ios/ContentView.swift")
        assertTrue(
            "the app root must wire hasSession to AuthViewModel.isLoggedIn in onAppear",
            wiring in root.substringAfter(".onAppear {").substringBefore("\n        }\n"),
        )
        // #486 review: onAppear running before the root .task(id:) is not a
        // SwiftUI guarantee, and that task's load makes account calls. The
        // task wires it too, before anything else it does. #487 review: the
        // same for StoreKitService, whose link / resume-purchase calls in that
        // task check its own isSignedIn.
        val storeWiring = "storeVM.isSignedIn = { [weak auth] in auth?.isLoggedIn ?? false }"
        assertTrue(
            "the app root must wire StoreKitService's signed-in state in onAppear",
            storeWiring in root.substringAfter(".onAppear {").substringBefore("\n        }\n"),
        )
        val launchLoad = root.substringAfter(".task(id: launchLoadKey) {")
        assertTrue("the root launch load is gone", launchLoad.length < root.length)
        val statements = launchLoad.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals(
            "the launch load must wire both signed-in checks before anything else",
            listOf(wiring, "storeVM.isSignedIn = { [weak authVM] in authVM?.isLoggedIn ?? false }"),
            statements.take(2),
        )
        assertTrue(
            "the launch load still links / resumes App Store purchases",
            "await storeVM.linkExistingEntitlementsAfterSignIn()" in launchLoad &&
                "await storeVM.resumePendingPurchase()" in launchLoad,
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

    /**
     * The Swift files the PacketTunnel extension compiles, from its `sources:`
     * in project.yml (paths there are relative to iosApp/).
     */
    private val packetTunnelSwiftFiles: Set<String> by lazy {
        val yml = source("iosApp/project.yml")
        val start = yml.indexOf("\n  PacketTunnel:")
        assertTrue("project.yml has no PacketTunnel target", start >= 0)
        val block = yml.substring(start, yml.indexOf("\n    settings:", start))
        Regex("""(?m)^\s+- path: (\S+)""").findAll(block).flatMap { m ->
            val path = "iosApp/${m.groupValues[1]}"
            val file = File(repoRoot, path)
            if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile && it.extension == "swift" }
                    .map { it.relativeTo(repoRoot).invariantSeparatorsPath }
            } else {
                sequenceOf(path)
            }
        }.filter { it.endsWith(".swift") }.toSet()
            .also { assertTrue("PacketTunnel compiles no PacketTunnelProvider.swift; the parse is broken", "iosApp/PacketTunnel/PacketTunnelProvider.swift" in it) }
    }

    /** The targets that compile the Swift file at [path]: the app takes all of iosApp/iosApp. */
    private fun targetsOf(path: String): Set<String> =
        setOfNotNull("app".takeIf { path.startsWith("$ios/") }, "tunnel".takeIf { path in packetTunnelSwiftFiles })

    @Test
    fun `every credential-bearing Swift struct or enum adopts the redacting description`() {
        // Every Swift file of the app and the extension, at any nesting depth:
        // it used to read three files, so a credential-bearing type anywhere
        // else (VPNManager's tunnel snapshot, the resolved account identity)
        // printed its secrets through Swift's default reflection unseen.
        val sources = swiftSources.map { (path, src) -> path to src.replace("\r\n", "\n") }
        val found = sources.flatMap { (path, src) -> SwiftCredentialTypes.scan(src).map { it to path } }
        assertTrue("found only ${found.size} credential-bearing types; the scan is broken", found.size >= 18)

        val bearing = found.filter { (type, _) -> type.fields.any { "${type.name}.$it" !in swiftNotSecret } }
        // MR-2044: the conformance must name the type by its qualified name (a
        // nested `Resolved` is not any other `Resolved`), and must be compiled
        // into every target that compiles the type: a type in the extension
        // needs its conformance in a file the extension compiles too.
        val missing = bearing
            .filter { (type, path) ->
                val needed = targetsOf(path)
                val candidates = sources.filter { (other, _) -> targetsOf(other).containsAll(needed) }.map { it.second }
                !SwiftCredentialTypes.conforms(type, candidates)
            }
            .map { (type, path) -> "$path: ${type.qualifiedName}" }
        assertEquals("credential-bearing Swift types with Swift's default (printing) description", emptyList<String>(), missing)

        // An exemption that no longer matches a field is a hole for the next one.
        val stale = swiftNotSecret.keys - found.flatMap { (type, _) -> type.fields.map { "${type.name}.$it" } }.toSet()
        assertEquals("stale swiftNotSecret entries", emptySet<String>(), stale)

        val names = bearing.map { (type, _) -> type.qualifiedName }
        listOf("LoginResult", "VPNConnectionConfig", "TunnelProfileSnapshot", "AccountIdentity.Resolved").forEach {
            assertTrue("the scan no longer finds $it", it in names)
        }
    }

    // ── MR-2044: the extension can redact too ────────────────────────────────

    @Test
    fun `the redaction mechanism compiles into the PacketTunnel extension and names no model`() {
        val core = "$ios/Services/CredentialRedaction.swift"
        val models = "$ios/Services/CredentialRedactingModels.swift"
        assertTrue("project.yml must compile $core into PacketTunnel", core in packetTunnelSwiftFiles)
        val src = source(core)
        assertTrue("enum Redaction" in src && "protocol CredentialRedacting" in src && "extension CredentialRedacting {" in src)
        // It compiles into the extension, so it may not name an app model.
        assertFalse(
            "$core declares a model conformance: those belong in $models",
            Regex("""(?m)^\s*extension\s+[\w.]+\s*:[^{]*\bCredentialRedacting\b""").containsMatchIn(src),
        )
        assertEquals("Foundation only", listOf("Foundation"), Regex("""(?m)^import\s+(\w+)""").findAll(src).map { it.groupValues[1] }.toList())
        // The models' conformances name app types: never in the extension, always in the unit tests.
        assertFalse("$models must stay out of PacketTunnel", models in packetTunnelSwiftFiles)
        assertTrue("extension TokenPairData: CredentialRedacting" in source(models))
        val projectYml = source("iosApp/project.yml")
        val unitTests = projectYml.substring(projectYml.indexOf("\n  BirdoVPNTests:"), projectYml.indexOf("\n  BirdoVPNUITests:"))
        listOf(core, models).forEach {
            assertTrue("BirdoVPNTests must compile $it", "- path: ${it.removePrefix("iosApp/")}" in unitTests)
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

    @Test
    fun `the Swift scan finds the shapes the first version missed`() {
        // MR-2044: each of these printed its secret through Swift's default
        // description, and the first scanner found none of the five types.
        val src = """
            enum Grant {
                case refreshToken(String)
                case challenge(
                    challengeToken: String,
                    attempts: Int
                )
                case none
            }

            struct Pair {
                let accessToken, refreshToken: String
                let platform: String, apiToken: String?
            }

            struct OneLine { let password: String; let platform: String }

            @MainActor struct Attributed {
                let wgPsk: Data
            }

            @available(iOS 17, *) public struct Available: Sendable {
                var sessionToken: String = ""
                var shownToken: String { "computed, not stored" }
                static let fixtureToken = "fake-fixture-token"
            }
        """.trimIndent()
        val found = SwiftCredentialTypes.scan(src).associate { it.name to it.fields }
        assertEquals(
            mapOf(
                "Grant" to listOf("refreshToken", "challengeToken"),
                "Pair" to listOf("accessToken", "refreshToken", "apiToken"),
                "OneLine" to listOf("password"),
                "Attributed" to listOf("wgPsk"),
                "Available" to listOf("sessionToken"),
            ),
            found,
        )
    }

    @Test
    fun `a conformance counts only for the type it names`() {
        // MR-2044: matched by bare name, `extension AccountIdentity.Resolved`
        // also "covered" every other type called Resolved.
        val src = """
            enum AccountIdentity {
                struct Resolved {
                    let accountNumber: String?
                }
            }

            extension Session {
                struct Resolved {
                    let refreshToken: String
                }
            }

            struct Resolved {
                let password: String
            }
        """.trimIndent()
        val types = SwiftCredentialTypes.scan(src)
        assertEquals(listOf("AccountIdentity.Resolved", "Session.Resolved", "Resolved"), types.map { it.qualifiedName })
        val conformance = listOf("extension AccountIdentity.Resolved: CredentialRedacting {\n}")
        assertEquals(listOf(true, false, false), types.map { SwiftCredentialTypes.conforms(it, conformance) })
        assertTrue(SwiftCredentialTypes.conforms(types[2], listOf("extension Resolved: Equatable, CredentialRedacting {}")))
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
 *
 * MR-2044 widened it to the shapes the first version missed: an unlabelled
 * enum payload (named by its case: `case refreshToken(String)`), a case whose
 * payload spans lines, `let a, b: String`, a one-line `struct X { let a: String }`,
 * and an attribute on the declaration line (`@MainActor struct X {`). Each
 * type also carries its QUALIFIED name, so a conformance is matched to the
 * type it is for, not to any type of the same bare name.
 */
internal object SwiftCredentialTypes {

    /** [name] as declared; [qualifiedName] with every enclosing type or extension (`AccountIdentity.Resolved`). */
    class Type(val name: String, val qualifiedName: String, val fields: List<String>)

    /** Types that can hold a secret's text or bytes. */
    private val secretTypes = setOf("String", "Data", "[String]", "[UInt8]")

    /**
     * A type or extension whose body opens on its declaration line, with any
     * attributes and modifiers in front. Group 4 is the rest of that line: a
     * one-line type's whole body.
     */
    private val declaration = Regex(
        """(?m)^( *)(?:@\w+(?:\([^)\n]*\))?[ \t]+)*(?:(?:public|internal|fileprivate|private|package|open|indirect|final)[ \t]+)*""" +
            """(struct|enum|class|actor|extension)[ \t]+(?!(?:func|var|let)\b)([\w.]+)\b[^{\n]*\{([^\n]*)$""",
    )

    /** A stored instance property's bindings (group 1): no `static`; attributes and modifiers allowed. */
    private val stored = Regex(
        """^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|internal|fileprivate|private(?:\(set\))?|weak|unowned|lazy|nonisolated|final)\s+)*""" +
            """(?:let|var)\s+(.+)$""",
    )
    private val case = Regex("""^(?:indirect\s+)?case\s+(.+)$""")
    private val caseElement = Regex("""^(\w+)\s*\((.*)\)\s*(?:=.*)?$""")
    private val labelled = Regex("""^(?:_\s+)?(\w+)\s*:\s*(.+)$""")
    private val binding = Regex("""^(\w+)\s*(?::\s*([^={]+?))?\s*(=.*|\{.*)?$""")

    private class Decl(
        val start: Int,
        val indent: Int,
        val kind: String,
        val name: String,
        val bodyEnd: Int,
        val members: List<String>,
    )

    fun scan(src: String): List<Type> {
        val decls = declaration.findAll(src).map { m ->
            val indent = m.groupValues[1]
            val rest = m.groupValues[4].substringBefore("//")
            val oneLine = closingBrace(rest)
            if (oneLine != null) {
                val body = rest.substring(0, oneLine)
                Decl(m.range.first, indent.length, m.groupValues[2], m.groupValues[3], m.range.last, splitTopLevel(body, ';'))
            } else {
                val bodyStart = m.range.last + 1
                val close = src.indexOf("\n$indent}", bodyStart).let { if (it < 0) src.length else it }
                Decl(
                    m.range.first, indent.length, m.groupValues[2], m.groupValues[3], close,
                    members(src.substring(bodyStart, close), "$indent    "),
                )
            }
        }.toList()
        return decls.filter { it.kind == "struct" || it.kind == "enum" }.mapNotNull { d ->
            val fields = d.members.flatMap(::fieldsOf)
                .filter { (name, type) -> type in secretTypes && CredentialFields.isCredentialName(name) }
                .map { (name, _) -> name }
            if (fields.isEmpty()) return@mapNotNull null
            val outer = decls.filter { e -> e.indent < d.indent && e.start < d.start && d.start < e.bodyEnd }
                .sortedBy { it.indent }
            Type(d.name, (outer.map { it.name } + d.name).joinToString("."), fields)
        }
    }

    /** Whether one of [sources] declares `extension <qualified name>: … CredentialRedacting`. */
    fun conforms(type: Type, sources: Collection<String>): Boolean {
        val ext = Regex("""(?m)^\s*extension\s+${Regex.escape(type.qualifiedName)}\s*:[^{]*\bCredentialRedacting\b""")
        return sources.any { ext.containsMatchIn(it) }
    }

    /**
     * The member statements of a multi-line body: the lines exactly one level
     * in, each joined with the lines that close the brackets it opens.
     */
    private fun members(body: String, member: String): List<String> {
        val out = mutableListOf<String>()
        val lines = body.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i++]
            if (!line.startsWith(member) || line.startsWith("$member ")) continue
            var text = line.substringBefore("//").trim()
            while (depth(text) > 0 && i < lines.size) text += " " + lines[i++].substringBefore("//").trim()
            out += text
        }
        return out
    }

    /** (name, type) of every stored property or enum payload [member] declares. */
    private fun fieldsOf(member: String): List<Pair<String, String>> {
        val text = member.trim()
        case.matchEntire(text)?.let { c ->
            return splitTopLevel(c.groupValues[1], ',').flatMap { element ->
                val payload = caseElement.matchEntire(element.trim()) ?: return@flatMap emptyList()
                val caseName = payload.groupValues[1]
                splitTopLevel(payload.groupValues[2], ',').map { it.trim() }.filter { it.isNotEmpty() }.map { param ->
                    // Unlabelled: the case names the payload (`case refreshToken(String)`).
                    labelled.matchEntire(param)?.let { it.groupValues[1] to typeOf(it.groupValues[2]) }
                        ?: (caseName to typeOf(param))
                }
            }
        }
        val decl = stored.matchEntire(text) ?: return emptyList()
        val fields = mutableListOf<Pair<String, String>>()
        val pending = mutableListOf<String>()
        for (piece in splitTopLevel(decl.groupValues[1], ',')) {
            val b = binding.matchEntire(piece.trim()) ?: return emptyList()
            // A `{` with no `=` before it is a getter: computed, never printed.
            if (b.groupValues[3].startsWith("{")) return emptyList()
            val type = b.groupValues[2].trim()
            if (type.isEmpty()) {
                // `let a, b: String`: a takes the type written after b.
                if (b.groupValues[3].isEmpty()) pending += b.groupValues[1]
                continue
            }
            (pending + b.groupValues[1]).forEach { fields += it to typeOf(type) }
            pending.clear()
        }
        return fields
    }

    private fun typeOf(declared: String): String = declared.substringBefore("=").trim().trimEnd('?', '!').trim()

    /** Open brackets minus closed ones, outside string literals. */
    private fun depth(s: String): Int {
        var d = 0
        var quoted = false
        for (c in s) {
            if (c == '"') quoted = !quoted
            if (quoted) continue
            if (c in "([{") d++
            if (c in ")]}") d--
        }
        return d
    }

    /** [s] split at every [separator] outside brackets, generics and string literals. */
    private fun splitTopLevel(s: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        var d = 0
        var quoted = false
        var from = 0
        s.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted
            if (!quoted) {
                if (c in "([{<") d++
                if (c in ")]}>") d--
                if (c == separator && d == 0) {
                    parts += s.substring(from, i)
                    from = i + 1
                }
            }
        }
        parts += s.substring(from)
        return parts.filter { it.isNotBlank() }
    }

    /** The offset in [rest] of the `}` that closes a body opened just before it, or null when it stays open. */
    private fun closingBrace(rest: String): Int? {
        if (rest.isBlank()) return null
        var d = 1
        var quoted = false
        rest.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted
            if (!quoted) {
                if (c == '{') d++
                if (c == '}' && --d == 0) return i
            }
        }
        return null
    }
}
