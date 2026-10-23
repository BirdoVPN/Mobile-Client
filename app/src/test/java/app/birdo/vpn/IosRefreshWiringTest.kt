package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-1771: the iOS wiring of the refresh rule — a 429 from `/auth/refresh`
 * keeps the stored session and is not re-asked before its `Retry-After`; only
 * a 401 or a 403 signs out.
 *
 * Why a JVM test reads Swift: `RefreshAnswerTests` in the iOS unit bundle pins
 * the pure half (RefreshAnswer.swift: what each status means, the clamp, the
 * hold), but `APIClient.swift`, which applies it, cannot compile into that
 * bundle. This runs on every pull request with the Android unit tests, as
 * [IosConnectRefusalWiringTest] does.
 *
 * What it pins:
 * - `refreshTokens()` classifies the answer with `RefreshAnswer.classify`,
 *   reading `Retry-After`, and throws `APIError.unauthorized` (the one error
 *   `AuthViewModel.logoutIfUnauthorized` signs out and wipes the keychain on)
 *   only for no stored token and for `.dead`;
 * - a kept session records the server's wait, and `RefreshCoordinator` sends
 *   nothing while it runs;
 * - RefreshAnswer.swift is compiled into the iOS unit bundle, so its tests run.
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

    /** The body of `refreshTokens()`, up to the next declaration. */
    private val refreshTokens by lazy {
        val start = apiClient.indexOf("private func refreshTokens() async throws {")
        assertTrue("$apiClientPath: refreshTokens() not found", start >= 0)
        apiClient.substring(start, apiClient.indexOf("\n    /// ", start))
    }

    /** `RefreshCoordinator`, the actor every 401's refresh goes through. */
    private val coordinator by lazy {
        val start = apiClient.indexOf("private actor RefreshCoordinator {")
        assertTrue("$apiClientPath: RefreshCoordinator not found", start >= 0)
        apiClient.substring(start, apiClient.indexOf("\n}\n", start))
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
        val dead = refreshTokens.substringAfter("case .dead:").substringBefore("case ")
        assertTrue("$apiClientPath: `.dead` no longer signs out", dead.contains("throw APIError.unauthorized"))
        val kept = refreshTokens.substringAfter("case .keepSession(").substringBefore("\n        }\n")
        assertFalse("$apiClientPath: a kept session signs out", kept.contains("unauthorized"))
        assertTrue("$apiClientPath: a kept session drops the server's wait", kept.contains("refreshActor.hold(for:"))
    }

    @Test
    fun `no refresh is sent while the server's wait runs`() {
        val refresh = coordinator.substringAfter("func refresh(").substringBefore("func hold(")
        val held = refresh.indexOf("holdOff.isHeld(now:")
        val sent = refresh.indexOf("Task<Void, Error> {")
        assertTrue("$apiClientPath: RefreshCoordinator.refresh no longer checks the hold", held >= 0)
        assertTrue("$apiClientPath: RefreshCoordinator.refresh starts no refresh task", sent >= 0)
        assertTrue("$apiClientPath: the hold is checked after the refresh is already sent", held < sent)
        assertTrue(
            "$apiClientPath: RefreshCoordinator.hold(for:) does not set the hold",
            coordinator.substringAfter("func hold(for wait: TimeInterval)").contains("holdOff.hold(for: wait"),
        )
    }

    @Test
    fun `RefreshAnswer is compiled into the iOS unit bundle`() {
        val tests = source("iosApp/project.yml").substringAfter("  BirdoVPNTests:").substringBefore("\n  BirdoVPNUITests:")
        assertTrue("project.yml: RefreshAnswer.swift is not a BirdoVPNTests source", tests.contains("- path: iosApp/Services/RefreshAnswer.swift"))
        assertTrue(File(repoRoot, "iosApp/BirdoVPNTests/RefreshAnswerTests.swift").isFile)
    }
}
