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
 *   in RefreshAnswer.swift, a source of the iOS unit bundle;
 * - a 2xx whose pair cannot be read holds like no answer (lead decision L2);
 * - a sign-out and every sign-in forget the session's wait (L1 of the #653
 *   review): `invalidateSession()` and `beginSession()` call
 *   `RefreshCoordinator.forgetSession()`, and every sign-in's keychain save
 *   is followed by `beginSession()`.
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
        // Lead, 2026-11-19: a refused refresh surfaces as a transient 503, never
        // the 403 itself (an auth failure is not a server refusing a relay).
        assertTrue("$apiClientPath: a refused refresh is not a 503", branch(".refused:").contains("throw APIError.httpError(503)"))
        assertFalse("$apiClientPath: a refused refresh passes its 403 on", branch(".refused:").contains("httpError(http.statusCode)"))
        // No answer at all: the transport error propagates as it is, after a backoff.
        val transport = refreshTokens.substringAfter("} catch {").substringBefore("\n        }\n")
        assertTrue("$apiClientPath: a transport failure sets no backoff", transport.contains("refreshActor.backOff(serverWait: nil)"))
        assertTrue("$apiClientPath: a transport failure is not rethrown as it is", transport.contains("throw error"))
        assertFalse("$apiClientPath: a transport failure signs out", transport.contains("unauthorized"))
        // A rotation and a 401 start the backoff over.
        assertTrue("$apiClientPath: a rotation does not reset the backoff", branch(".rotated:").contains("refreshActor.reset()"))
        assertTrue("$apiClientPath: a 401 does not reset the backoff", branch(".dead:").contains("refreshActor.reset()"))
    }

    /**
     * Lead decision L2 (2026-11-19): a 2xx whose pair cannot be read keeps the
     * session and HOLDS like no answer at all, and its error surfaces. Before,
     * it reset the backoff and held nothing. Only a readable pair resets it.
     */
    @Test
    fun `a 2xx whose pair cannot be read backs off, and only a readable pair resets the backoff`() {
        val rotated = branch(".rotated:")
        val decode = rotated.indexOf("tokens = try decoder.decode(RefreshTokensResponse.self, from: data)")
        val caught = rotated.indexOf("} catch {")
        val held = rotated.indexOf("refreshActor.backOff(serverWait: nil)")
        val rethrown = rotated.indexOf("throw error")
        val reset = rotated.indexOf("refreshActor.reset()")
        assertTrue("$apiClientPath: `case .rotated:` no longer decodes the pair", decode >= 0)
        assertTrue("$apiClientPath: an unreadable 2xx is not caught", caught > decode)
        assertTrue("$apiClientPath: an unreadable 2xx does not back off", held > caught)
        assertTrue("$apiClientPath: an unreadable 2xx's error does not surface", rethrown > held)
        assertTrue("$apiClientPath: the backoff resets before the pair is read", reset > rethrown)
        assertFalse("$apiClientPath: an unreadable 2xx signs out", rotated.contains("unauthorized"))
        assertEquals(
            "$apiClientPath: decoder.decode(RefreshTokensResponse calls in refreshTokens()",
            1,
            Regex("""decoder\.decode\(RefreshTokensResponse""").findAll(refreshTokens).count(),
        )
    }

    /**
     * L1 (review of #653): the refresh wait, the backoff and the retry owed
     * are the session's. A sign-out (`invalidateSession()`, which
     * AuthViewModel.completeLocalLogout calls) and every sign-in
     * (`beginSession()`, after each keychain save of a new pair) forget them,
     * as Desktop's `forget_session` does. RefreshAnswerTests pins
     * `forgetSession()` itself.
     */
    @Test
    fun `a sign-out and every sign-in forget the refresh wait`() {
        val invalidate = apiClient.substringAfter("func invalidateSession() async {").substringBefore("\n    }\n")
        assertTrue("$apiClientPath: invalidateSession() not found", apiClient.contains("func invalidateSession() async {"))
        assertTrue("$apiClientPath: a sign-out does not bump the generation", invalidate.contains("sessionGeneration.bump()"))
        assertTrue("$apiClientPath: a sign-out keeps the refresh wait", invalidate.contains("refreshActor.forgetSession()"))
        val begin = apiClient.substringAfter("func beginSession() async {").substringBefore("\n    }\n")
        assertTrue("$apiClientPath: beginSession() not found", apiClient.contains("func beginSession() async {"))
        assertTrue("$apiClientPath: a sign-in keeps the refresh wait", begin.contains("refreshActor.forgetSession()"))

        val forget = coordinator.substringAfter("func forgetSession() {").substringBefore("\n    }\n")
        assertTrue("$refreshAnswerPath: forgetSession() does not replace the hold", forget.contains("holdOff = RefreshHoldOff()"))
        for (wait in listOf("func hold(for wait: TimeInterval) {", "func backOff(serverWait: TimeInterval?) {")) {
            val body = coordinator.substringAfter(wait).substringBefore("\n    }\n")
            assertTrue("$refreshAnswerPath: `$wait` records an old session's wait", body.contains("guard waitIsThisSessions else { return }"))
        }

        val vmPath = "iosApp/iosApp/ViewModels/AuthViewModel.swift"
        val vm = source(vmPath)
        val saves = Regex("""keychain\.save\(accessToken:""").findAll(vm).map { it.range.first }.toList()
        val begins = Regex("""api\.beginSession\(\)""").findAll(vm).map { it.range.first }.toList()
        assertEquals("$vmPath: sign-in keychain saves", 2, saves.size)
        assertEquals("$vmPath: a sign-in keychain save is not followed by beginSession()", saves.size, begins.size)
        for ((save, begin) in saves.zip(begins)) {
            assertTrue("$vmPath: beginSession() runs before its keychain save", begin > save)
        }
        // ...and before the session's first request after it.
        for (signIn in listOf("func acknowledgeAnonymousId() {", "private func completeAuthentication(")) {
            val body = vm.substringAfter(signIn)
            val begin = body.indexOf("api.beginSession()")
            val fetch = body.indexOf("api.fetchProfile()")
            assertTrue("$vmPath: `$signIn` has no beginSession()", begin >= 0)
            assertTrue("$vmPath: `$signIn` fetches the profile before beginSession()", fetch > begin)
        }
        val logout = vm.substringAfter("private func completeLocalLogout() {").substringBefore("\n    }\n")
        assertTrue("$vmPath: a local sign-out does not invalidate the API session", logout.contains("api.invalidateSession()"))
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

    /**
     * Lead, 2026-11-19: no background timer. A held or backing-off refresh is
     * retried by the next request or when the app comes to the foreground,
     * through the same single-flight path and only once its wait is over; a
     * 401 from that retry signs out like any other.
     */
    @Test
    fun `coming to the foreground retries a held refresh on both platforms, through the one path`() {
        val scenePhase = source("iosApp/iosApp/ContentView.swift")
            .substringAfter(".onChange(of: scenePhase) {").substringBefore("\n        }\n")
        val active = scenePhase.indexOf("guard phase == .active else { return }")
        assertTrue("ContentView: the scenePhase handler no longer gates on .active", active >= 0)
        assertTrue(
            "ContentView: coming to the foreground does not retry a held refresh",
            scenePhase.indexOf("authVM.retryHeldRefresh()") > active,
        )

        val retry = apiClient.substringAfter("func retryHeldRefresh() async throws {").substringBefore("\n    }\n")
        val due = retry.indexOf("refreshActor.retryIsDue()")
        val sent = retry.indexOf("refreshActor.refresh {")
        assertTrue("$apiClientPath: retryHeldRefresh() does not ask whether a retry is due", due >= 0)
        assertTrue("$apiClientPath: retryHeldRefresh() does not go through the coordinator", sent >= 0)
        assertTrue("$apiClientPath: retryHeldRefresh() sends before asking", due < sent)
        assertTrue("$apiClientPath: retryHeldRefresh() does not refresh", retry.contains("refreshTokens()"))

        val vm = source("iosApp/iosApp/ViewModels/AuthViewModel.swift")
            .substringAfter("func retryHeldRefresh() async {").substringBefore("\n    }\n")
        assertTrue("AuthViewModel.swift: a 401 from the foreground retry does not sign out", vm.contains("logoutIfUnauthorized(error)"))
        assertTrue(
            "$refreshAnswerPath: the coordinator does not check the hold before a foreground retry",
            coordinator.substringAfter("func retryIsDue() -> Bool {").contains("holdOff.retryIsDue(now:"),
        )

        // Android: the activity's ON_START, through the same single-flight refresh.
        val graph = source("app/src/main/java/app/birdo/vpn/ui/navigation/BirdoNavGraph.kt")
        assertTrue(
            "BirdoNavGraph.kt: ON_START does not retry a held refresh",
            Regex("""Lifecycle\.Event\.ON_START\)\s*authViewModel\.onAppForeground\(\)""").containsMatchIn(graph),
        )
        val repo = source("app/src/main/java/app/birdo/vpn/data/repository/BirdoRepository.kt")
            .substringAfter("internal suspend fun retryHeldRefresh(): RefreshOutcome? {").substringBefore("\n    }\n")
        assertTrue("BirdoRepository.kt: retryHeldRefresh() ignores the hold", repo.contains("refreshHoldMs() != null"))
        assertTrue("BirdoRepository.kt: retryHeldRefresh() bypasses the single-flight refresh", repo.contains("refreshToken("))
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
