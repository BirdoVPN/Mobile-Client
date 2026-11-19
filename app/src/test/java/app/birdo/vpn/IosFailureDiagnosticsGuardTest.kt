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
 * the opt-in failure counts on or hands a report to a sender until the owner
 * decides the consent copy and the destination.
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

    @Test
    fun `BirdoPQManager throws the reason instead of returning nil`() {
        val manager = source("$ios/Services/BirdoPQManager.swift")
        val decap = manager.substringAfter("func tryDecapsulate(").substringBefore("\n    }\n")
        assertTrue("tryDecapsulate must throw", ") throws -> String? {" in decap)
        assertTrue("the checks must go through PQDecapsulationPlan", "try PQDecapsulationPlan.make(" in decap)
        assertTrue("a native derivation failure must throw its reason", "throw PQFailure.deriveFailed" in decap)
        assertFalse(
            "a failure path logs and returns nil again — the reason would be lost in Release",
            Regex("""debugLog\([^\n]*\)\s*\n\s*return nil""").containsMatchIn(decap),
        )
    }

    @Test
    fun `every quantum failure carries its reason to the message`() {
        val manager = source("$ios/Services/VPNManager.swift")
        val bare = Regex("""quantumHandshakeFailed\b(?!\()""").findAll(manager).count()
        assertEquals("VPNManager throws or declares quantumHandshakeFailed without a reason", 0, bare)
        assertTrue("case quantumHandshakeFailed(PQFailure)" in manager)
        assertTrue(
            "the message must show the reason's code",
            "\"Quantum-protected handshake failed (\\(reason.code))." in manager,
        )
        assertTrue("resolveEffectivePsk must call the throwing tryDecapsulate", "pqPsk = try BirdoPQManager.shared.tryDecapsulate(" in manager)
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

        // Up to the automatic retry, whatever it is called (#645 renames it).
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
    fun `nothing turns the failure counts on or sends them until the owner decides`() {
        val root = File(repoRoot, ios)
        val files = root.walkTopDown().filter { it.isFile && it.extension == "swift" }.toList()
        assertTrue("the iOS app walk found only ${files.size} files", files.size >= 40)
        val forbidden = listOf("setEnabled(", "takeReport(", "failure_counts_enabled", "enabledKey")
        val hits = files
            .filter { it.name != "FailureCounts.swift" }
            .flatMap { f ->
                val text = f.readText()
                forbidden.filter { needle -> needle in text && (needle != "setEnabled(" || "FailureCounts" in text) }
                    .map { "${f.name}: $it" }
            }
        assertEquals("the app switches failure counts on or sends a report", emptyList<String>(), hits)
    }
}
