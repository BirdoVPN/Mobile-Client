package app.birdo.vpn

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * MR-139, the same split: `ConnectRefusalTests` pins ConnectRefusal.swift
 * (the reasons against the contract, the copy, whether the plans are
 * offered), and this pins its wiring: `decodeConnectResult` throws the typed
 * refusal from the envelope's `refusalReason`, every dial records it, Home's
 * "View plans" reads it rather than the wording, and the unit bundle compiles
 * the file.
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

    // ── MR-139: a refusal inside the 200, keyed on refusalReason ─────────

    private val refusalPath = "iosApp/iosApp/Services/ConnectRefusal.swift"
    private val homePath = "iosApp/iosApp/Views/HomeView.swift"
    private val envelopeDecl = Regex("""\bstruct\s+ConnectEnvelope\b""")
    private val noteCall = "noteRefusal(after: error)"

    @Test
    fun `a refusal inside the 200 is thrown as the typed refusal, never as the server's bare words`() {
        val decodeAt = apiClient.indexOf("private func decodeConnectResult(")
        assertTrue("$apiClientPath: decodeConnectResult not found — this test would be vacuous", decodeAt >= 0)
        val decode = apiClient.substring(decodeAt, apiClient.indexOf("\n    }\n", decodeAt))
        val typedAt = decode.indexOf("throw ConnectRefusedError.classify(")
        assertTrue("$apiClientPath: decodeConnectResult does not throw ConnectRefusedError.classify(", typedAt >= 0)
        assertTrue(
            "$apiClientPath: the typed refusal is not built from the envelope's refusalReason and quotaExceeded",
            "refusalReason: envelope.refusalReason," in decode &&
                "quotaExceeded: envelope.quotaExceeded ?? false" in decode,
        )
        assertFalse(
            "$apiClientPath: decodeConnectResult still throws the server's bare words",
            "APIError.serverMessage(" in decode,
        )
        // A refused rebuild keeps its own type and is told apart first.
        val rebuildAt = decode.indexOf("throw RebuildRefusedError(")
        assertTrue(
            "$apiClientPath: the rebuild refusal is no longer thrown ahead of the typed refusal",
            rebuildAt in 0 until typedAt,
        )
        // One envelope, the one the unit bundle decodes in ConnectRefusalTests.
        assertFalse("$apiClientPath declares its own ConnectEnvelope again", envelopeDecl.containsMatchIn(apiClient))
        assertTrue("$refusalPath has no ConnectEnvelope", envelopeDecl.containsMatchIn(source(refusalPath)))
    }

    @Test
    fun `every function that dials a connect route records the typed refusal beside the hold`() {
        val dialChunks = functionChunks(viewModel).filter { dialCall.containsMatchIn(it) }
        assertEquals("$viewModelPath: dial functions", 3, dialChunks.size)
        val recording = dialChunks.count { chunk ->
            val hold = chunk.indexOf(holdCall)
            val note = chunk.indexOf(noteCall, maxOf(hold, 0))
            val banner = chunk.indexOf("self.error = error.localizedDescription", maxOf(note, 0))
            hold >= 0 && note > hold && banner > note
        }
        assertEquals(
            "$viewModelPath: dial functions whose catch calls $noteCall after the hold and before the banner",
            dialChunks.size,
            recording,
        )
        assertEquals(
            "$viewModelPath: $noteCall call count (one per dial function)",
            dialChunks.size,
            Regex(Regex.escape(noteCall)).findAll(viewModel).count(),
        )
    }

    @Test
    fun `Home offers the plans off the refusal's reason, never off its wording`() {
        val home = source(homePath)
        assertTrue("$homePath: no View plans action keyed on vpnVM.refusalOffersPlans", "if vpnVM.refusalOffersPlans {" in home)
        val destinationAt = home.indexOf(".navigationDestination(isPresented: \$showPlans)")
        assertTrue("$homePath: the plans are not pushed off showPlans", destinationAt >= 0)
        assertTrue(
            "$homePath: showPlans does not push SubscriptionView",
            home.indexOf("SubscriptionView()", destinationAt) in destinationAt until destinationAt + 200,
        )
        val offersAt = viewModel.indexOf("var refusalOffersPlans: Bool {")
        assertTrue("$viewModelPath: refusalOffersPlans not found", offersAt >= 0)
        val offers = viewModel.substring(offersAt, viewModel.indexOf("\n    }", offersAt))
        assertTrue("$viewModelPath: refusalOffersPlans does not read the typed refusal", "refusal.offersPlans" in offers)
        assertFalse(
            "$viewModelPath: refusalOffersPlans reads the wording",
            Regex("""\.(?:contains|hasPrefix|lowercased|range)\(""").containsMatchIn(offers),
        )
    }

    @Test
    fun `the unit bundle compiles the refusal rule, and its reasons are the contract's`() {
        val project = source("iosApp/project.yml")
        val unitBundle = project.substringAfter("\n  BirdoVPNTests:\n").substringBefore("\n  BirdoVPNUITests:")
        assertTrue(
            "iosApp/project.yml: BirdoVPNTests does not compile $refusalPath",
            "- path: iosApp/Services/ConnectRefusal.swift" in unitBundle,
        )
        val swift = source(refusalPath)
        val enumBody = swift.substringAfter("enum ConnectRefusalReason").substringBefore("init?(wire:")
        val swiftReasons = Regex("""(?m)^\s*case\s+(\w+)(?:\s*=\s*"([^"]+)")?\s*$""").findAll(enumBody)
            .map { it.groupValues[2].ifEmpty { it.groupValues[1] } }
            .toSet()
        val schema = File(repoRoot, "contract/vpn-protocol.schema.json").readText()
        val contractReasons = kotlinx.serialization.json.Json.parseToJsonElement(schema)
            .jsonObject["\$defs"]!!.jsonObject["ConnectRefusal"]!!.jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
        assertTrue("contract: \$defs.ConnectRefusal.enum is empty", contractReasons.isNotEmpty())
        assertEquals("$refusalPath: ConnectRefusalReason wire values vs \$defs.ConnectRefusal", contractReasons, swiftReasons)
    }

    // ── MR-139 follow-up (#517 review) ───────────────────────────────────

    /** The Swift string a `static let <name> =` declaration builds, its literals joined. */
    private fun swiftStaticString(swift: String, name: String): String {
        val at = swift.indexOf("static let $name =")
        assertTrue("$refusalPath: static let $name not found — this test would be vacuous", at >= 0)
        val declaration = swift.substring(at, swift.indexOf("\n\n", at))
        return Regex(""""((?:[^"\\]|\\.)*)"""").findAll(declaration).joinToString("") { it.groupValues[1] }
    }

    /** An Android string resource's text, with its escaped apostrophes read. */
    private fun androidString(name: String): String {
        val strings = source("app/src/main/res/values/strings.xml")
        val text = Regex("""<string name="$name">([^<]*)</string>""").find(strings)?.groupValues?.get(1)
        assertTrue("strings.xml: $name not found", text != null)
        return text!!.replace("\\'", "'")
    }

    @Test
    fun `the refusal copy is Android's, word for word`() {
        // vpn-d8's parity decision: no per-reason sentences of iOS's own.
        val swift = source(refusalPath)
        assertEquals(
            "$refusalPath: genericMessage vs Android's error_connect_failed",
            androidString("error_connect_failed"),
            swiftStaticString(swift, "genericMessage"),
        )
        assertEquals(
            "$refusalPath: deviceLimitMessage vs Android's error_device_limit",
            androidString("error_device_limit"),
            swiftStaticString(swift, "deviceLimitMessage"),
        )
        assertFalse("$refusalPath still has per-reason fallback copy", "func fallback(for" in swift)
    }

    @Test
    fun `the live rebuild's other failures clear the refusal, so its View plans cannot linger`() {
        val rebuild = chunkNamed("rebuildLive")
        val refusedAt = rebuild.indexOf("catch let refusal as RebuildRefusedError {")
        assertTrue("$viewModelPath: rebuildLive has no RebuildRefusedError catch", refusedAt >= 0)
        val refusedBanner = rebuild.indexOf("self.error = refusal.message", refusedAt)
        assertTrue("$viewModelPath: the rebuild refusal no longer sets the banner", refusedBanner > refusedAt)
        assertTrue(
            "$viewModelPath: a refused rebuild does not clear connectRefusal before its banner",
            "connectRefusal = nil" in rebuild.substring(refusedAt, refusedBanner),
        )
        for (event in listOf(".routeNotConfirmed", ".swapFailed")) {
            assertTrue(
                "$viewModelPath: the $event failure does not clear connectRefusal before its banner",
                Regex(
                    """connectRefusal = nil\s*\n\s*self\.error = \w+\.localizedDescription\s*\n\s*""" +
                        """flagsOwnedElsewhere = await finishRebuild\(${Regex.escape(event)}, ctx\)""",
                ).containsMatchIn(rebuild),
            )
        }
    }

    @Test
    fun `Home's banner comment no longer promises the server's words verbatim`() {
        // The device cap has its own sentence and an unshowable one is dropped.
        assertFalse("$homePath still says refusals are never rephrased", "never rephrase" in source(homePath))
    }
}
