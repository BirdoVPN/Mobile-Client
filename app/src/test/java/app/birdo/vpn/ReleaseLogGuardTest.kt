package app.birdo.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CLI-I-05 — the Apple host app must not log in Release builds.
 *
 * What was measured on origin/main (2026-09-14): 36 raw `NSLog(` calls in the
 * host app (BirdoPQManager 9, VPNManager 9, VpnViewModel 18) and exactly ONE
 * `#if DEBUG` in the whole app — StoreKitService's private `log(_:)`. `NSLog`
 * writes to the unified log at `.default` level with every argument public,
 * in every configuration, so a shipped build persisted the tunnel disconnect
 * error text, the live-rebuild verdicts and the PQ key-material failure
 * reasons on the user's device. The packet-tunnel extension already used
 * `os_log` with `%{private}@`; the app never caught up.
 *
 * The fix is a single `debugLog(_:_:)` in `iosApp/iosApp/Platform/DebugLog.swift`
 * whose body is `#if DEBUG` — nothing in Release — with `privacy: .private`
 * on the one interpolation. Every former call site was renamed, message text
 * untouched.
 *
 * Why a JVM test reads Swift: iOS is never built in PR CI (ios.yml and
 * macos.yml run on dispatch and on tags), and the regression this guards —
 * one new `NSLog(` in a file that compiles perfectly — is invisible to a
 * compiler anyway. This runs with the Android unit tests on every PR, the same
 * way QuickSelectGuardTest and MultiHopPolicyTest pin their Swift twins. It
 * reads the RAW files: a comment that spells `NSLog(` is the next copy-paste.
 */
class ReleaseLogGuardTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private val helperPath = "iosApp/iosApp/Platform/DebugLog.swift"

    /** The three files the finding counted — each must now go through the helper. */
    private val formerNSLogFiles = listOf(
        "iosApp/iosApp/Services/BirdoPQManager.swift",
        "iosApp/iosApp/Services/VPNManager.swift",
        "iosApp/iosApp/ViewModels/VpnViewModel.swift",
    )

    private fun source(path: String): String {
        val file = File(repoRoot, path)
        assertTrue("scan target is missing: $path — this test would be vacuous", file.isFile)
        val text = file.readText()
        assertTrue("scan target $path is empty", text.length > 200)
        return text
    }

    private fun relative(file: File): String =
        file.absolutePath.replace(File.separatorChar, '/')
            .removePrefix(repoRoot.absolutePath.replace(File.separatorChar, '/') + "/")

    private fun swiftSources(): List<File> {
        val root = File(repoRoot, "iosApp")
        assertTrue("iosApp/ is missing — the walk below would be vacuous", root.isDirectory)
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "swift" }
            // XcodeGen writes DerivedData/build products under iosApp/ on a Mac
            // checkout; those are compiler output, not sources.
            .filterNot { relative(it).contains("/build/") || relative(it).contains("/.build") }
            .toList()
        // Vacuity guard: the app, the extension, the tests and the vendored
        // WireGuardKit are ~60 files. A walk that finds fewer than the three
        // former offenders plus the helper is a broken walker, not a clean tree.
        assertTrue(
            "swift source walk found only ${files.size} files — the walker is broken",
            files.size >= 40,
        )
        return files
    }

    private fun lineOf(text: String, offset: Int) = text.substring(0, offset).count { it == '\n' } + 1

    @Test
    fun `no raw NSLog anywhere under iosApp outside the helper`() {
        val nslog = Regex("""\bNSLog\(""")
        val hits = swiftSources()
            .filter { relative(it) != helperPath }
            .flatMap { f ->
                val text = f.readText()
                nslog.findAll(text).map { m -> "${relative(f)}:${lineOf(text, m.range.first)}" }.toList()
            }
        assertEquals(
            "raw NSLog( logs in every build configuration with public arguments (CLI-I-05). " +
                "Call debugLog(...) from iosApp/iosApp/Platform/DebugLog.swift instead — same " +
                "printf signature, compiled out under Release. Sites: $hits",
            emptyList<String>(),
            hits,
        )
    }

    @Test
    fun `the three former NSLog files now log through debugLog`() {
        // Not an exact count on purpose: adding a diagnostic must not fail this
        // test, only routing one around the helper does (the test above).
        // A file with ZERO calls would mean the rename was reverted wholesale
        // or the file was rewritten to log some third way — look at it.
        for (path in formerNSLogFiles) {
            val calls = Regex("""\bdebugLog\(""").findAll(source(path)).count()
            assertTrue(
                "$path has no debugLog( call — the finding counted 9/9/18 NSLog sites here; " +
                    "if they were removed rather than renamed, say so in this test",
                calls >= 1,
            )
        }
    }

    @Test
    fun `the helper is compiled out under Release and redacts under Debug`() {
        val text = source(helperPath)
        val decl = text.indexOf("func debugLog(")
        assertTrue("$helperPath no longer declares func debugLog(", decl >= 0)
        val body = text.substring(decl)

        // The `#if DEBUG` must come BEFORE the first logging statement inside
        // the function, and the `#endif` after it — a guard placed around
        // something else (or dropped) leaves the log live in Release.
        val ifDebug = body.indexOf("#if DEBUG")
        val logCall = Regex("""\b(Logger\(|os_log\()""").find(body)?.range?.first ?: -1
        val endif = body.indexOf("#endif")
        assertTrue("$helperPath: debugLog body has no logging statement at all", logCall >= 0)
        assertTrue(
            "$helperPath: debugLog's logging statement is not inside `#if DEBUG` … `#endif` — " +
                "it would log in Release, which is the finding",
            ifDebug in 0 until logCall && endif > logCall,
        )
        // A `#if !DEBUG` or `#if DEBUG || …` spelling would invert or widen the guard.
        val guardLine = body.substring(ifDebug, body.indexOf('\n', ifDebug).let { if (it < 0) body.length else it })
        assertEquals("$helperPath: the guard must be exactly `#if DEBUG`", "#if DEBUG", guardLine.trim())

        // Even in Debug the message is private: a developer build on a device
        // must not persist tunnel/PQ diagnostics readable from Console.app.
        assertTrue(
            "$helperPath: the interpolated message must carry `privacy: .private` " +
                "(the extension's %{private}@ convention)",
            Regex("""privacy:\s*\.private""").containsMatchIn(body.substring(ifDebug, endif)),
        )
    }

    @Test
    fun `the helper lives where the app target's source walk picks it up`() {
        // project.yml compiles the BirdoVPN target from `path: iosApp` (the
        // whole directory), so a file under iosApp/iosApp/ needs no manifest
        // entry — but the PacketTunnel and BirdoVPNTests targets list files
        // one by one and do not see it. The 36 sites are all in the app target;
        // if a future NSLog lands in the extension, the extension's own
        // os_log(…%{private}@…) is the right tool, not this helper.
        assertTrue("$helperPath is missing", File(repoRoot, helperPath).isFile)
        val projectYml = source("iosApp/project.yml")
        assertTrue(
            "iosApp/project.yml no longer compiles the BirdoVPN target from `path: iosApp`; " +
                "DebugLog.swift must then be listed explicitly or every debugLog( call fails to link",
            Regex("""^\s*-\s*path:\s*iosApp\s*$""", RegexOption.MULTILINE).containsMatchIn(projectYml),
        )
    }
}
