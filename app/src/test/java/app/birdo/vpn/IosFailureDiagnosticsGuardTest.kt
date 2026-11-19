package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-2287 (A-M37): iOS had no failure diagnostics, and `BirdoPQManager`
 * swallowed the reason a post-quantum connect failed (every cause returned
 * the nil a classical session returns; the reason reached only a Debug log).
 *
 * The decisions themselves are pinned on the Apple side (PQFailureTests,
 * FailureCountsTests). What those cannot see is the wiring, because
 * BirdoPQManager, VPNManager and VpnViewModel do not compile into the
 * un-hosted iOS test bundle, so this reads them, as the other Ios*GuardTests
 * do. It also pins the privacy claim of the change: nothing in the app turns
 * the opt-in failure counts on or hands them to a sender until the owner
 * decides the consent copy and the destination.
 *
 * The rules live in [FailureDiagnosticsRules] as pure functions of source
 * text, so the second half of this class can prove each one CATCHES the
 * bypass it exists for (review of #644, L1/L4): every mutant below passed the
 * first version of this guard.
 */
class IosFailureDiagnosticsGuardTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    /** Line endings normalised: BirdoPQManager.swift and VPNManager.swift are stored CRLF. */
    private fun source(path: String): String {
        val file = File(repoRoot, path)
        assertTrue("scan target is missing: $path — this test would be vacuous", file.isFile)
        return file.readText().replace("\r\n", "\n")
    }

    private val ios = "iosApp/iosApp"
    private val storePath = "$ios/Services/FailureCounts.swift"
    private val store by lazy { source(storePath) }
    private val manager by lazy { source("$ios/Services/VPNManager.swift") }

    /**
     * Every Swift file under iosApp/ (the app, the PacketTunnel extension, the
     * UI tests and the vendored WireGuardKit) except the unit tests, which
     * build their own stores on throwaway suites. Paths repo-relative.
     */
    private val appSources: Map<String, String> by lazy {
        val root = File(repoRoot, "iosApp")
        root.walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            .map { it.relativeTo(repoRoot).invariantSeparatorsPath }
            .filterNot { it.startsWith("iosApp/BirdoVPNTests/") || "/build/" in it || "/.build/" in it }
            .associateWith { source(it) }
            .also { assertTrue("the iosApp walk found only ${it.size} files", it.size >= 100) }
    }

    @Test
    fun `BirdoPQManager throws the reason instead of returning nil`() {
        val pq = source("$ios/Services/BirdoPQManager.swift")
        val decap = pq.substringAfter("func tryDecapsulate(").substringBefore("\n    }\n")
        assertTrue("tryDecapsulate must throw", ") throws -> String? {" in decap)
        assertTrue("the checks must go through PQDecapsulationPlan", "try PQDecapsulationPlan.make(" in decap)
        assertTrue("a native derivation failure must throw its reason", "throw PQFailure.deriveFailed" in decap)
        assertTrue("a derive plan with no pair must throw, not return the classical nil", "throw PQFailure.keypairUnavailable" in decap)
        assertEquals(
            "tryDecapsulate may return nil in exactly two places: the hand-off match and the classical plan",
            2,
            Regex("""return nil""").findAll(decap).count(),
        )
        assertFalse(
            "a failure path logs and returns nil again — the reason would be lost in Release",
            Regex("""debugLog\([^\n]*\)\s*\n\s*return nil""").containsMatchIn(decap),
        )
    }

    @Test
    fun `every quantum failure carries its reason to the message`() {
        val bare = Regex("""quantumHandshakeFailed\b(?!\()""").findAll(manager).count()
        assertEquals("VPNManager throws or declares quantumHandshakeFailed without a reason", 0, bare)
        assertTrue("case quantumHandshakeFailed(PQFailure)" in manager)
        assertTrue(
            "the message must show the reason's code",
            "\"Quantum-protected handshake failed (\\(reason.code))." in manager,
        )
    }

    /** L4: the block this PR rewrote still fails closed, both ways out. */
    @Test
    fun `resolveEffectivePsk fails closed before any classical key is read`() {
        assertEquals(emptyList<String>(), FailureDiagnosticsRules.pskViolations(manager))
    }

    @Test
    fun `every failed dial and the never-handshook verdict reach the counts`() {
        val vm = source("$ios/ViewModels/VpnViewModel.swift")
        val refusalNotes = Regex(Regex.escape("noteRefusal(after: error)")).findAll(vm).toList()
        assertTrue("found only ${refusalNotes.size} dial catch blocks", refusalNotes.size >= 3)
        for (note in refusalNotes) {
            val rest = vm.substring(note.range.last + 1).substringBefore("self.error = error.localizedDescription")
            assertTrue(
                "the catch at offset ${note.range.first} records the refusal but not the dial failure",
                "noteDialFailure(after: error)" in rest,
            )
        }
        assertTrue("the rebuild's swap failure is not counted", "noteDialFailure(after: swapError)" in vm)

        // Up to the automatic retry, whatever it is called (#645 renamed it).
        val verdict = vm.substringAfter("case .tearDownFailOpen:")
        assertTrue("no automatic retry in .tearDownFailOpen — this check would be vacuous", "if retryOn" in verdict)
        assertTrue(
            "a tunnel that never handshook is not counted (before the automatic retry)",
            "FailureCounts.shared.record(.tunnelNeverHandshook)" in verdict.substringBefore("if retryOn"),
        )
    }

    /**
     * Owner decision pending (MR-2287): the choice is not offered and there is
     * no destination. Whoever adds the switch or the sender updates this test,
     * the consent copy, /privacy and the App Store privacy answers together.
     */
    @Test
    fun `the store is off by default and has no way out of the device`() {
        assertEquals(emptyList<String>(), FailureDiagnosticsRules.storeViolations(store))
    }

    @Test
    fun `nothing outside the store turns the counts on, reads them or sends them`() {
        assertTrue("the store itself was not found by the walk", storePath in appSources)
        assertTrue("the walk misses the PacketTunnel extension", appSources.keys.any { it.startsWith("iosApp/PacketTunnel/") })
        assertEquals(emptyList<String>(), FailureDiagnosticsRules.appViolations(appSources - storePath))
        // The app's only use is recording: one helper and the never-handshook verdict.
        val uses = (appSources - storePath).values.sumOf { Regex("""FailureCounts\.shared\.record\(""").findAll(it).count() }
        assertEquals("FailureCounts.shared.record( call sites", 2, uses)
    }

    // ── The rules catch what they exist for (mutants of the real files) ──────

    /** Replace [old] in [text], failing if it is not there: a mutant that changes nothing proves nothing. */
    private fun mutate(text: String, old: String, new: String): String {
        assertTrue("mutation anchor missing: $old", old in text)
        return text.replace(old, new)
    }

    @Test
    fun `a store that opts itself in is caught`() {
        val selfEnabling = mutate(
            store,
            "static let shared = FailureCounts(defaults: .standard)",
            "static let shared: FailureCounts = { let f = FailureCounts(defaults: .standard); f.setEnabled(true); return f }()",
        )
        assertTrue(FailureDiagnosticsRules.storeViolations(selfEnabling).isNotEmpty())
        val defaultOn = mutate(store, "return defaults.bool(forKey: Self.enabledKey)", "return defaults.object(forKey: Self.enabledKey) as? Bool ?? true")
        assertTrue(FailureDiagnosticsRules.storeViolations(defaultOn).isNotEmpty())
        val registered = mutate(store, "init(defaults: UserDefaults,", "init(defaults: UserDefaults = { UserDefaults.standard.register(defaults: [\"failure_counts_enabled\": true]); return .standard }(),")
        assertTrue(FailureDiagnosticsRules.storeViolations(registered).isNotEmpty())
        val secondSet = mutate(store, "    func record(_ failure: DialFailure) {\n", "    func record(_ failure: DialFailure) {\n        defaults.set(true, forKey: Self.enabledKey)\n")
        assertTrue(FailureDiagnosticsRules.storeViolations(secondSet).isNotEmpty())
    }

    @Test
    fun `a store that sends is caught`() {
        for (call in listOf(
            "URLSession.shared.dataTask(with: URL(string: \"https://example.invalid\")!).resume()",
            "_ = NWConnection(host: \"example.invalid\", port: 443, using: .tls)",
            "_ = socket(AF_INET, SOCK_STREAM, 0)",
            "APIClient.shared.send(held)",
        )) {
            val sending = mutate(store, "        let held = heldCounts()\n", "        let held = heldCounts()\n        $call\n")
            assertTrue("not caught: $call", FailureDiagnosticsRules.storeViolations(sending).isNotEmpty())
        }
        val network = mutate(store, "import Foundation\n", "import Foundation\nimport Network\n")
        assertTrue(FailureDiagnosticsRules.storeViolations(network).isNotEmpty())
    }

    @Test
    fun `an app file that reads, enables or sends the counts is caught`() {
        val api = source("$ios/Services/APIClient.swift")
        val mutants = listOf(
            // M1 of the review: a sender reading `.counts` and posting it.
            "let held = FailureCounts.shared.counts\n        _ = URLSession.shared",
            "let fc = FailureCounts.shared\n        _ = fc.counts",
            "FailureCounts.shared.setEnabled(true)",
            "_ = FailureCounts.shared.takeReport()",
            "_ = FailureCounts(defaults: .standard).takeReport()",
            "UserDefaults.standard.register(defaults: [\"failure_counts_enabled\": true])",
            "_ = UserDefaults.standard.dictionary(forKey: FailureCounts.countsKey)",
        )
        for (line in mutants) {
            val text = mutate(api, "import Foundation\n", "import Foundation\nfunc leak() {\n        $line\n}\n")
            assertTrue("not caught: $line", FailureDiagnosticsRules.appViolations(mapOf("$ios/Services/APIClient.swift" to text)).isNotEmpty())
        }
        // The extension is in scope too (a store moved to the app group would be readable there).
        val tunnel = "iosApp/PacketTunnel/PacketTunnelProvider.swift"
        val inTunnel = mutate(source(tunnel), "import NetworkExtension\n", "import NetworkExtension\nlet leaked = UserDefaults(suiteName: \"group.app.birdo.vpn\")?.dictionary(forKey: \"failure_counts\")\n")
        assertTrue(FailureDiagnosticsRules.appViolations(mapOf(tunnel to inTunnel)).isNotEmpty())
    }

    @Test
    fun `a resolveEffectivePsk that falls back to the classical key is caught`() {
        val catchBody = "            BirdoPQManager.shared.recordDisabled()\n" +
            "            throw VPNManagerError.quantumHandshakeFailed(error as? PQFailure ?? .deriveFailed)\n"
        val swallowed = mutate(manager, catchBody, "            pqPsk = nil\n")
        assertTrue(FailureDiagnosticsRules.pskViolations(swallowed).isNotEmpty())
        val optional = mutate(manager, "pqPsk = try BirdoPQManager.shared.tryDecapsulate(", "pqPsk = try? BirdoPQManager.shared.tryDecapsulate(")
        assertTrue(FailureDiagnosticsRules.pskViolations(optional).isNotEmpty())
        val noBackup = mutate(
            manager,
            "            BirdoPQManager.shared.recordDisabled()\n            throw VPNManagerError.quantumHandshakeFailed(.deriveFailed)\n",
            "            BirdoPQManager.shared.recordDisabled()\n",
        )
        assertTrue(FailureDiagnosticsRules.pskViolations(noBackup).isNotEmpty())
        val earlyRead = mutate(
            manager,
            "        let pqPsk: String?\n",
            "        let pqPsk: String?\n        if let early = config.presharedKey, config.quantumEnabled != true { return early }\n",
        )
        assertTrue(FailureDiagnosticsRules.pskViolations(earlyRead).isNotEmpty())
    }
}

/**
 * The rules of [IosFailureDiagnosticsGuardTest], as functions of source text.
 * Each returns what it found wrong; empty means the source keeps the rule.
 * `//` comments are dropped first, so prose can name what code may not do.
 */
internal object FailureDiagnosticsRules {

    private fun code(src: String): String = src.lines().joinToString("\n") { it.substringBefore("//") }

    /** Anything in FailureCounts.swift that could take a count off the device. */
    private val networkTokens = listOf(
        "URLSession", "URLRequest", "NSURLConnection", "NWConnection", "NWEndpoint", "NWPathMonitor",
        "CFSocket", "CFStream", "Stream(", "socket(", "getaddrinfo", "APIClient", "URL(", "http",
    )

    /** FailureCounts.swift: off by default, opted in only by `setEnabled`, Foundation only, no network. */
    fun storeViolations(store: String): List<String> {
        val src = code(store)
        val out = mutableListOf<String>()
        if (Regex("""static (let|var) shared\b""").findAll(src).count() != 1 ||
            "static let shared = FailureCounts(defaults: .standard)\n" !in src
        ) {
            out += "the shared store is no longer `static let shared = FailureCounts(defaults: .standard)`"
        }
        val isEnabled = src.substringAfter("var isEnabled: Bool {", "").substringBefore("\n    }\n")
        if (isEnabled.trim() != "lock.lock(); defer { lock.unlock() }\n        return defaults.bool(forKey: Self.enabledKey)") {
            out += "isEnabled is no longer exactly `return defaults.bool(forKey: Self.enabledKey)` (absent = off)"
        }
        val setters = Regex("""defaults\.set\(true""").findAll(src).toList()
        val setEnabled = src.substringAfter("func setEnabled(_ enabled: Bool) {", "").substringBefore("\n    }\n")
        if (setters.size != 1 || "defaults.set(true, forKey: Self.enabledKey)" !in setEnabled) {
            out += "the choice is written true somewhere other than setEnabled"
        }
        for (needle in listOf("setEnabled(true", "register(")) if (needle in src) out += "FailureCounts.swift calls $needle"
        for (needle in networkTokens) if (needle in src) out += "FailureCounts.swift uses $needle"
        val imports = Regex("""(?m)^\s*(?:@\w+\s+)?import\s+(\w+)""").findAll(src).map { it.groupValues[1] }.toList()
        if (imports != listOf("Foundation")) out += "FailureCounts.swift imports $imports, not Foundation only"
        return out
    }

    /**
     * Every other Swift file: the store's only use is `FailureCounts.shared.record(`.
     * No other handle on it, no second instance, no report, no switch, no
     * direct read of its keys.
     */
    fun appViolations(files: Map<String, String>): List<String> = files.flatMap { (path, text) ->
        val src = code(text)
        val names = src.contains("FailureCounts")
        listOfNotNull(
            "a handle on the shared store other than .record(".takeIf { Regex("""FailureCounts\.shared(?!\.record\()""").containsMatchIn(src) },
            "a second store".takeIf { Regex("""\bFailureCounts\s*\(""").containsMatchIn(src) },
            "takeReport(".takeIf { "takeReport(" in src },
            "its keys".takeIf { "\"failure_counts" in src || Regex("""FailureCounts\.(enabledKey|countsKey|versionKey)""").containsMatchIn(src) },
            "setEnabled(".takeIf { names && "setEnabled(" in src },
            ".counts".takeIf { names && Regex("""\.counts\b""").containsMatchIn(src) },
        ).map { "$path: $it" }
    }

    /**
     * VPNManager.resolveEffectivePsk (review of #644, L4): the throwing
     * decapsulation, a catch that records Disabled and rethrows the reason,
     * the backup branch for a nil from a post-quantum session, and no read of
     * the classical `presharedKey` before both.
     */
    fun pskViolations(manager: String): List<String> {
        val fn = code(manager).substringAfter("private func resolveEffectivePsk(", "").substringBefore("\n    }\n")
        if (fn.isEmpty()) return listOf("resolveEffectivePsk not found")
        val out = mutableListOf<String>()
        if ("pqPsk = try BirdoPQManager.shared.tryDecapsulate(" !in fn) out += "tryDecapsulate is not called with a plain try"
        val catchAt = fn.indexOf("} catch {")
        val catchBody = if (catchAt < 0) "" else fn.substring(catchAt).substringBefore("\n        }\n")
        if ("BirdoPQManager.shared.recordDisabled()" !in catchBody ||
            "throw VPNManagerError.quantumHandshakeFailed(" !in catchBody ||
            "pqPsk =" in catchBody || "return" in catchBody
        ) {
            out += "the catch does not record Disabled and rethrow quantumHandshakeFailed"
        }
        val backupAt = fn.indexOf("if config.quantumEnabled == true {")
        val backup = if (backupAt < 0) "" else fn.substring(backupAt).substringBefore("\n        }\n")
        if ("throw VPNManagerError.quantumHandshakeFailed(" !in backup) out += "the post-quantum backup branch does not throw"
        val classical = fn.indexOf("config.presharedKey")
        val lastThrow = maxOf(catchAt, backupAt)
        if (classical < 0 || catchAt < 0 || backupAt < 0 || classical < lastThrow) {
            out += "config.presharedKey is read before both fail-closed exits"
        }
        return out
    }
}
