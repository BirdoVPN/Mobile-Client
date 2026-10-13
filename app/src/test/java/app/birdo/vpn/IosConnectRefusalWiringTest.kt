package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-1948: the iOS wiring of the retryable 503s (`psk_delivery_unavailable`,
 * `quota_check_unavailable`).
 *
 * Why a JVM test reads Swift: `ConnectErrorCodeTests` in the iOS unit bundle
 * pins the pure half (ConnectErrorCode.swift: the code decode, the
 * `details.retryAfterSeconds` clamp, the copy, the auto-connect hold), but the
 * two files that USE it, `APIClient.swift` and `VpnViewModel.swift`, cannot
 * compile into that bundle. This runs on every pull request with the Android
 * unit tests, as [IosVersionFloorWiringTest] and [QuickSelectGuardTest] do.
 *
 * What it pins:
 * - both connect routes classify the refusal from the raw body, through
 *   `performRequest`'s `refusal:` hook, before the generic mapping turns the
 *   503 into `.serverMessage` / `.httpError(503)`;
 * - every view-model function that dials a connect route records the
 *   server's wait in its catch, the hold is cleared only when a tunnel comes
 *   up, and the launch auto-connect sleeps it out and re-reads what the user
 *   may have changed meanwhile;
 * - neither file writes the Quantum Protection preference, so no failure path
 *   can turn a retry into a non-PQ request.
 *
 * Messages report counts and paths only, never source text.
 */
class IosConnectRefusalWiringTest {

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
        val text = file.readText().replace("\r\n", "\n")
        assertTrue("scan target $path is empty", text.length > 200)
        return text
    }

    private val apiClientPath = "iosApp/iosApp/Services/APIClient.swift"
    private val viewModelPath = "iosApp/iosApp/ViewModels/VpnViewModel.swift"
    private val apiClient by lazy { source(apiClientPath) }
    private val viewModel by lazy { source(viewModelPath) }

    private val classifyCall = Regex("""RetryableConnectRefusal\.classify\(""")
    private val dialCall = Regex("""api\.get(?:Connect|MultiHop)Config\(""")
    private val holdCall = "holdAutomaticDials(after: error)"

    /**
     * The view model's top-level functions (four-space indent), each with its
     * body up to the next one. Nested functions and closures stay inside their
     * parent's chunk.
     */
    private fun functionChunks(text: String): List<String> {
        val decl = Regex(
            """^ {4}(?:(?:private|fileprivate|static|nonisolated|override)\s+)*func\s+\w+""",
            RegexOption.MULTILINE,
        )
        val starts = decl.findAll(text).map { it.range.first }.toList()
        return starts.mapIndexed { i, start ->
            text.substring(start, if (i + 1 < starts.size) starts[i + 1] else text.length)
        }
    }

    private fun chunkNamed(name: String): String {
        val chunk = functionChunks(viewModel).firstOrNull {
            Regex("""^ {4}(?:(?:private|fileprivate|static|nonisolated|override)\s+)*func\s+$name\(""")
                .containsMatchIn(it)
        }
        assertTrue("$viewModelPath: function $name not found — this test would be vacuous", chunk != null)
        return chunk!!
    }

    /** From the `performRequest(` that carries `path: "<route>"` to the decode that follows it. */
    private fun requestWindow(route: String): String? {
        val pathAt = apiClient.indexOf("path: \"$route\",")
        if (pathAt < 0) return null
        val start = apiClient.lastIndexOf("performRequest(", pathAt)
        val end = apiClient.indexOf("decodeConnectResult(", pathAt)
        if (start < 0 || end < 0) return null
        // One call only: no other route may sit between the call and its path.
        if (apiClient.substring(start, pathAt).contains("path: \"")) return null
        return apiClient.substring(start, end)
    }

    @Test
    fun `both connect routes classify the retryable refusal through the refusal hook`() {
        assertEquals(
            "$apiClientPath: RetryableConnectRefusal.classify( call count (one per connect route)",
            2,
            classifyCall.findAll(apiClient).count(),
        )
        for (route in listOf("/vpn/connect", "/vpn/multi-hop/connect")) {
            assertEquals(
                "$apiClientPath: occurrences of the path literal for $route",
                1,
                Regex(Regex.escape("path: \"$route\",")).findAll(apiClient).count(),
            )
            val window = requestWindow(route)
            assertTrue("$apiClientPath: no performRequest( call carries the path for $route", window != null)
            assertEquals(
                "$apiClientPath: RetryableConnectRefusal.classify( calls inside the $route performRequest block",
                1,
                classifyCall.findAll(window!!).count(),
            )
            assertTrue(
                "$apiClientPath: the $route performRequest block has no refusal: hook",
                window.contains("refusal: {"),
            )
            assertFalse(
                "$apiClientPath: $route still goes through post(path:), which drops the body before the code is read",
                apiClient.contains("post(path: \"$route\""),
            )
        }
    }

    @Test
    fun `every function that dials a connect route records the hold in its catch`() {
        val chunks = functionChunks(viewModel)
        assertTrue("$viewModelPath: found only ${chunks.size} functions — the splitter is broken", chunks.size > 20)
        val dialChunks = chunks.filter { dialCall.containsMatchIn(it) }
        assertEquals(
            "$viewModelPath: functions that call api.getConnectConfig( / api.getMultiHopConfig( " +
                "(expected 3) — a new one must record the hold too",
            3,
            dialChunks.size,
        )
        val recording = dialChunks.count { chunk ->
            val firstDial = dialCall.find(chunk)!!.range.first
            val hold = chunk.indexOf(holdCall, firstDial)
            val catchAt = chunk.indexOf("catch", firstDial)
            hold >= 0 && catchAt in 0 until hold
        }
        assertEquals(
            "$viewModelPath: dial functions whose catch after the dial calls $holdCall",
            dialChunks.size,
            recording,
        )
        assertEquals(
            "$viewModelPath: $holdCall call count (one per dial function)",
            dialChunks.size,
            Regex(Regex.escape(holdCall)).findAll(viewModel).count(),
        )
    }

    @Test
    fun `the hold is cleared only when a tunnel comes up`() {
        val clears = Regex("""autoDialNotBefore\s*=\s*nil""")
        assertEquals("$viewModelPath: autoDialNotBefore = nil count", 1, clears.findAll(viewModel).count())
        val handler = chunkNamed("handleStatusChange")
        val connectedAt = handler.indexOf("case .connected:")
        assertTrue("$viewModelPath: handleStatusChange has no .connected branch", connectedAt >= 0)
        val nextCase = handler.indexOf("\n        case ", connectedAt + 1).let { if (it < 0) handler.length else it }
        assertTrue(
            "$viewModelPath: autoDialNotBefore = nil is not inside handleStatusChange's .connected branch",
            clears.containsMatchIn(handler.substring(connectedAt, nextCase)),
        )
    }

    @Test
    fun `the launch auto-connect sleeps out the hold and re-reads the user's choices on waking`() {
        val auto = chunkNamed("autoConnectIfEnabled")
        val delayAt = auto.indexOf("AutoDialHold.delay(")
        assertTrue("$viewModelPath: autoConnectIfEnabled does not compute AutoDialHold.delay(", delayAt >= 0)
        val sleepAt = auto.indexOf("Task.sleep(for: .seconds(delay))")
        assertTrue("$viewModelPath: autoConnectIfEnabled does not sleep for the computed delay", sleepAt > delayAt)
        val recheckAt = Regex(
            """guard\s+UserDefaults\.standard\.bool\(forKey:\s*"auto_connect"\)\s*,\s*self\.hasSession\s+else""",
        ).find(auto, sleepAt)?.range?.first ?: -1
        assertTrue(
            "$viewModelPath: autoConnectIfEnabled does not re-check auto_connect and hasSession after its sleep",
            recheckAt > sleepAt,
        )
        val standDownAt = Regex("""if\s+self\.autoDialNotBefore\s*!=\s*scheduledHold\s*\{\s*return\s*}""")
            .find(auto, sleepAt)?.range?.first ?: -1
        assertTrue(
            "$viewModelPath: autoConnectIfEnabled does not stand down when the hold moved during its sleep",
            standDownAt > sleepAt,
        )
        val dialAt = auto.indexOf("self.connect(userInitiated: false)")
        assertTrue(
            "$viewModelPath: the auto-connect dial is not after both post-sleep checks",
            dialAt > recheckAt && dialAt > standDownAt,
        )
    }

    @Test
    fun `neither file writes the Quantum Protection preference`() {
        val reads = Regex("""\.(?:object|bool)\(forKey:\s*"quantum_protection"\)""")
        for ((path, text) in listOf(apiClientPath to apiClient, viewModelPath to viewModel)) {
            val all = Regex(Regex.escape("\"quantum_protection\"")).findAll(text).count()
            assertEquals(
                "$path: \"quantum_protection\" occurrences that are not reads (object/bool(forKey:))",
                0,
                all - reads.findAll(text).count(),
            )
        }
        // The two connect requests read it afresh on every dial, which is what
        // makes a re-dial after the 503 ask for exactly what the first did.
        assertEquals(
            "$apiClientPath: per-dial reads of quantum_protection (one per connect route)",
            2,
            reads.findAll(apiClient).count(),
        )
    }
}
