package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-1771 / MR-2500: the iOS wiring of the refresh rule. Only a 401 from
 * `/auth/refresh` signs out; a 429 keeps the stored session and is not re-asked
 * before its wait; a 403, a 5xx or no answer at all keeps it too and backs off;
 * the token in the keychain is touched only by a rotation.
 *
 * Why a JVM test reads Swift: `RefreshAnswerTests` in the iOS unit bundle pins
 * the pure half (RefreshAnswer.swift: what each status means, the waits, the
 * backoff, and the single-flight `RefreshCoordinator`), but `APIClient.swift`,
 * which applies it, cannot compile into that bundle. This runs on every pull
 * request with the Android unit tests, as [IosConnectRefusalWiringTest] does.
 *
 * What it pins:
 * - `refreshTokens()` classifies the answer with `RefreshAnswer.classify`,
 *   reading `Retry-After` and the body, and throws `APIError.unauthorized`
 *   (the one error `AuthViewModel.logoutIfUnauthorized` signs out and wipes
 *   the keychain on) only for no stored token and for `.dead`;
 * - every other answer keeps the session: a 429 records the server's wait,
 *   a 403, a 5xx and a transport failure record a backoff, and none of them
 *   writes or clears the keychain;
 * - `APIClient` refreshes through the one `RefreshCoordinator`, which lives
 *   in RefreshAnswer.swift, a source of the iOS unit bundle.
 *
 * Messages report counts and paths only, never source text.
 */
class IosRefreshWiringTest {

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
    private val apiClient by lazy { source(apiClientPath) }
    private val refreshAnswerPath = "iosApp/iosApp/Services/RefreshAnswer.swift"
    private val refreshAnswer by lazy { source(refreshAnswerPath) }

    /** The body of `refreshTokens()`, up to the next declaration. */
    private val refreshTokens by lazy {
        val start = apiClient.indexOf("private func refreshTokens() async throws {")
        assertTrue("$apiClientPath: refreshTokens() not found", start >= 0)
        apiClient.substring(start, apiClient.indexOf("\n    /// ", start))
    }

    /** One `case` of the switch over the answer in `refreshTokens()`, up to the next case or the switch's end. */
    private fun branch(label: String): String {
        val start = refreshTokens.indexOf("        case $label")
        assertTrue("$apiClientPath: refreshTokens() has no `case $label`", start >= 0)
        val rest = refreshTokens.substring(start)
        val ends = listOf(rest.indexOf("\n        case ", 1), rest.indexOf("\n        }\n")).filter { it >= 0 }
        return rest.substring(0, ends.minOrNull() ?: rest.length)
    }

    /** `RefreshCoordinator`, the actor every 401's refresh goes through. */
    private val coordinator by lazy {
        val start = refreshAnswer.indexOf("actor RefreshCoordinator {")
        assertTrue("$refreshAnswerPath: RefreshCoordinator not found", start >= 0)
        refreshAnswer.substring(start, refreshAnswer.indexOf("\n}\n", start))
    }

    @Test
    fun `refreshTokens classifies every answer with RefreshAnswer and signs out only on dead`() {
        assertEquals(
            "$apiClientPath: RefreshAnswer.classify( calls in refreshTokens()",
            1,
            Regex("""RefreshAnswer\.classify\(""").findAll(refreshTokens).count(),
        )
        assertTrue(
            "$apiClientPath: refreshTokens() does not hand Retry-After to the classifier",
            refreshTokens.contains("""http.value(forHTTPHeaderField: "Retry-After")"""),
        )
        assertTrue(
            "$apiClientPath: refreshTokens() does not hand the body (details.retryAfter) to the classifier",
            refreshTokens.contains("body: data)"),
        )
        // Before MR-1771 the status was compared by hand; a 429 must never be one of those.
        assertFalse(
            "$apiClientPath: refreshTokens() compares statusCode by hand again",
            Regex("""statusCode\s*==""").containsMatchIn(refreshTokens),
        )
        // Two .unauthorized throws: no stored refresh token, and `.dead`.
        assertEquals(
            "$apiClientPath: `throw APIError.unauthorized` in refreshTokens()",
            2,
            Regex("""throw APIError\.unauthorized""").findAll(refreshTokens).count(),
        )
        assertTrue("$apiClientPath: `.dead` no longer signs out", branch(".dead:").contains("throw APIError.unauthorized"))
    }

    @Test
    fun `every answer but a 401 keeps the session and sets its wait`() {
        val waits = mapOf(
            ".refused:" to "refreshActor.backOff(serverWait: nil)",
            ".rateLimited(" to "refreshActor.hold(for: retryAfter)",
            ".unavailable(" to "refreshActor.backOff(serverWait: retryAfter)",
        )
        for ((label, wait) in waits) {
            val kept = branch(label)
            assertFalse("$apiClientPath: `case $label` signs out", kept.contains("unauthorized"))
            assertTrue("$apiClientPath: `case $label` drops its wait", kept.contains(wait))
            assertTrue("$apiClientPath: `case $label` does not throw", kept.contains("throw APIError.httpError("))
        }
        // No answer at all: the transport error propagates as it is, after a backoff.
        val transport = refreshTokens.substringAfter("} catch {").substringBefore("\n        }\n")
        assertTrue("$apiClientPath: a transport failure sets no backoff", transport.contains("refreshActor.backOff(serverWait: nil)"))
        assertTrue("$apiClientPath: a transport failure is not rethrown as it is", transport.contains("throw error"))
        assertFalse("$apiClientPath: a transport failure signs out", transport.contains("unauthorized"))
        // A rotation and a 401 start the backoff over.
        assertTrue("$apiClientPath: a rotation does not reset the backoff", branch(".rotated:").contains("refreshActor.reset()"))
        assertTrue("$apiClientPath: a 401 does not reset the backoff", branch(".dead:").contains("refreshActor.reset()"))
    }

    @Test
    fun `refreshTokens never clears the keychain and writes it only after a rotation`() {
        assertFalse("$apiClientPath: refreshTokens() clears the keychain", refreshTokens.contains("keychain.clear"))
        assertFalse("$apiClientPath: refreshTokens() signs out locally", refreshTokens.contains("LocalSignOut"))
        assertEquals(
            "$apiClientPath: keychain.save( calls in refreshTokens()",
            1,
            Regex("""keychain\.save\(""").findAll(refreshTokens).count(),
        )
        val save = refreshTokens.indexOf("keychain.save(")
        val switchEnd = refreshTokens.indexOf("\n        }\n", refreshTokens.indexOf("switch RefreshAnswer.classify("))
        assertTrue("$apiClientPath: the keychain is written before the answer is classified", switchEnd in 0 until save)
    }

    @Test
    fun `no refresh is sent while a wait runs`() {
        val refresh = coordinator.substringAfter("func refresh(").substringBefore("func hold(")
        val joined = refresh.indexOf("if let task = inFlight {")
        val held = refresh.indexOf("holdOff.isHeld(now:")
        val sent = refresh.indexOf("Task<Void, any Error> {")
        assertTrue("$refreshAnswerPath: RefreshCoordinator.refresh no longer joins the refresh in flight", joined >= 0)
        assertTrue("$refreshAnswerPath: RefreshCoordinator.refresh no longer checks the hold", held >= 0)
        assertTrue("$refreshAnswerPath: RefreshCoordinator.refresh starts no refresh task", sent >= 0)
        assertTrue("$refreshAnswerPath: the hold is checked after the refresh is already sent", held < sent)
        assertTrue(
            "$refreshAnswerPath: RefreshCoordinator.hold(for:) does not set the hold",
            coordinator.substringAfter("func hold(for wait: TimeInterval)").contains("holdOff.hold(for: wait"),
        )
        assertTrue(
            "$refreshAnswerPath: RefreshCoordinator.backOff(serverWait:) does not back off",
            coordinator.substringAfter("func backOff(serverWait: TimeInterval?)").contains("holdOff.backOff(serverWait: serverWait"),
        )
    }

    @Test
    fun `APIClient refreshes through the one coordinator, and its source is in the iOS unit bundle`() {
        assertFalse("$apiClientPath: a second RefreshCoordinator is declared", apiClient.contains("actor RefreshCoordinator"))
        assertEquals(
            "$apiClientPath: RefreshCoordinator( constructions",
            1,
            Regex("""RefreshCoordinator\(""").findAll(apiClient).count(),
        )
        val tests = source("iosApp/project.yml").substringAfter("  BirdoVPNTests:").substringBefore("\n  BirdoVPNUITests:")
        assertTrue("project.yml: RefreshAnswer.swift is not a BirdoVPNTests source", tests.contains("- path: iosApp/Services/RefreshAnswer.swift"))
        assertTrue(File(repoRoot, "iosApp/BirdoVPNTests/RefreshAnswerTests.swift").isFile)
    }
}
