package app.birdo.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-1707: neither anonymous number is ever logged, and there is no analytics.
 * The new files are scanned (CRLF normalised first) for any logging call or
 * analytics hook at all: they have no business making one.
 */
class AnonNumberNoLogGuardTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("settings.gradle.kts not found")
        dir
    }

    private val main = "app/src/main/java/app/birdo/vpn"
    private val files = listOf(
        "$main/data/repository/AnonNumberRotation.kt",
        "$main/ui/viewmodel/ReplaceNumberViewModel.kt",
        "$main/ui/screen/ReplaceNumberSheet.kt",
        "$main/utils/AnonymousNumberDisplay.kt",
    )

    private fun source(path: String) =
        File(repoRoot, path).also { assertTrue("missing: $path", it.isFile) }.readText().replace("\r\n", "\n")

    private val forbidden = Regex(
        """\b(Log\.[a-z]\(|Timber\.|println\(|print\(|Sentry\.|Crashlytics|FirebaseAnalytics|logEvent\(|CrashReporting\.|Analytics)""",
    )

    private fun code(text: String) = text.lines()
        .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the rotation files make no logging or analytics call`() {
        for (f in files) {
            val hit = forbidden.find(code(source(f)))
            assertEquals("a log or analytics call near the account number in $f", null, hit?.value)
        }
    }

    @Test
    fun `the repository rotation methods log nothing and put no number in a string template`() {
        val repo = source("$main/data/repository/BirdoRepository.kt")
        val block = repo.substringAfter("MR-1707: replace the anonymous account number", "")
            .substringBefore("// ── Google Play store rail")
        assertTrue("rotation block not found", block.contains("rotateNumberPrepare"))
        assertEquals(null, forbidden.find(code(block))?.value)
        assertFalse(
            "a number is interpolated into a string",
            Regex("\"[^\"]*\\$\\{?(currentNumber|newNumber)").containsMatchIn(block),
        )
    }

    @Test
    fun `the state class carries no number`() {
        val vm = source("$main/ui/viewmodel/ReplaceNumberViewModel.kt")
        val state = code(vm.substringAfter("data class ReplaceNumberState(").substringBefore("\n)\n"))
        assertFalse(Regex("(?i)number").containsMatchIn(state))
    }
}
