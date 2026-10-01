package app.birdo.vpn.ui

import app.birdo.vpn.testing.KotlinSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A2-031: the words a user reads live in strings.xml, not in Kotlin.
 *
 * Lint's HardcodedText check cannot see Compose, so this reads the source, in
 * the style of ReleaseLogGuardTest. Every string literal under `ui/` and
 * `billing/` that reads as English prose fails the build, wherever it sits: a
 * `Text(…)`, a `contentDescription =`, a `label =`, a `when` branch, a
 * ViewModel error, a Toast. Literals inside developer-only calls (logs,
 * preconditions, regexes, date patterns, annotations) are not copy and are
 * skipped. Comments are skipped too: they quote retired copy to explain why
 * it went.
 *
 * "Reads as prose" is a heuristic, deliberately biased towards what copy looks
 * like and keys do not: two or more words, a capitalised word ("Account"), or
 * a template with words around it ("$n devices"). Slugs ("SOVEREIGN"), keys
 * ("monthly"), brand-shaped names ("BirdoVPN", "WireGuard"), units ("GB") and
 * symbols ("%", "·") pass on their own, so the explicit allow-list stays
 * empty unless something genuinely needs it.
 */
class HardcodedCopyGuardTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private val main by lazy { File(repoRoot, "app/src/main/java/app/birdo/vpn") }

    /** The trees whose copy must come from strings.xml. */
    private val scannedDirs = listOf("ui", "billing")

    /**
     * Literal texts that may stay in code. Keep it short, and say why for
     * each: a brand, a unit, a format sample a translator must not touch.
     */
    private val allowed: Set<String> = emptySet()

    @Test
    fun `no hard-coded user-facing copy under ui and billing`() {
        var files = 0
        var literals = 0
        val hits = mutableListOf<String>()
        for (dir in scannedDirs) {
            File(main, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                files++
                val path = f.relativeTo(main).invariantSeparatorsPath
                val found = SourceCopy.scan(f.readText())
                literals += found.size
                found.filter { it.isProse && it.text !in allowed }
                    .forEach { hits += "$path:${it.line}: \"${it.text}\"" }
            }
        }
        // A walker that found nothing would pass vacuously.
        assertTrue("scanned only $files files", files > 40)
        assertTrue("scanned only $literals literals", literals > 200)
        assertEquals(
            "user-facing text belongs in res/values/strings.xml (A2-031). Move it there and " +
                "use stringResource / StringLookup:\n" + hits.joinToString("\n"),
            emptyList<String>(),
            hits,
        )
    }

    /** The scanner's own contract, so a regression in it cannot pass silently. */
    @Test
    fun `the scanner flags copy in every UI position and skips what is not copy`() {
        val src = """
            package x
            // Text("A comment that quotes old copy")
            /* contentDescription = "Also a comment" /* nested */ still comment */
            @Composable
            fun Screen(n: Int, label: String) {
                Text("Your Plan")
                Icon(icon, contentDescription = "Copy account number")
                OutlinedTextField(label = { Text("Email") })
                val chip = "${'$'}n devices"
                val status = when { n > 0 -> "Up to date"; else -> "just now" }
                Toast.makeText(context, "Account number copied", Toast.LENGTH_SHORT)
                SectionLabel("Session")
                Text("ACCOUNT NUMBER")

                Log.d(TAG, "Auto-connect failed: ${'$'}{result.message}")
                tracing("Auto-connecting to last server")
                check(ok) {
                    "a refusal must never be acknowledged"
                }
                val unreachable = error("no plan for this state")
                StoreNotice.error("Purchase could not be completed")
                notice.check("Subscription not verified")
                val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy")
                val url = "${'$'}{BuildConfig.WEB_BASE_URL}/native/oauth/start?provider=${'$'}p&state=${'$'}s"
                val keys = listOf("monthly", "SOVEREIGN", "tcp" to "TCP", "BirdoVPN", "WireGuard", "GB")
                val pct = "${'$'}{n}%"
                val quote = '"'
                val tag = Modifier.testTag("login_anonymous_id")
            }
        """.trimIndent()
        val prose = SourceCopy.scan(src).filter { it.isProse }.map { it.text }
        assertEquals(
            listOf(
                "Your Plan",
                "Copy account number",
                "Email",
                "# devices",
                "Up to date",
                "just now",
                "Account number copied",
                "Session",
                "ACCOUNT NUMBER",
                "Purchase could not be completed",
                "Subscription not verified",
            ),
            prose,
        )
    }
}

/**
 * The string literals of one Kotlin source that are not inside a
 * developer-only call, each marked with whether it reads as prose.
 */
internal object SourceCopy {

    data class Literal(val line: Int, val text: String, val isProse: Boolean)

    /**
     * Calls whose literals are for developers, never for users.
     *
     * The stdlib's top-level functions (`error`, `require`, `check`, `TODO`,
     * `println`) and a local `log` or `tracing` helper count only as bare
     * calls. `\b` also matches after a dot, so `StoreNotice.error("…")` — a
     * notice the user reads — used to pass as developer text
     * (REVIEW-AND2-007). A member call of those names is checked like any
     * other position.
     */
    private val developerCall = Regex(
        """(?:\bLog\.[vdiwe]""" +
            """|(?<![.\w])(?:log|tracing|error|require|requireNotNull|check|checkNotNull|TODO|println)""" +
            """|\bRegex|\btoRegex|\bofPattern|\btestTag|\bthrow\s+\w+|\b\w*Exception""" +
            """|@[\w.:]+)\s*\(""",
    )

    /** A word: lower case or capitalised, or a short all-caps word ("ACCOUNT"). */
    private val word = Regex("""[A-Za-z][a-z'’\-]*|[A-Z]{2,15}""")

    fun scan(src: String): List<Literal> {
        val source = KotlinSource(src)
        // The argument list, and any trailing lambda (`check(ok) { "…" }`).
        val developer = developerCall.findAll(source.code).map { m ->
            val close = source.closing(m.range.last)
            m.range.first..(source.trailingBlock(close)?.last ?: close)
        }.toList()
        return source.literals
            .filter { lit -> developer.none { lit.offset in it } }
            .map { Literal(source.lineOf(it.offset), it.text, isProse(it.text, it.hasTemplate)) }
    }

    /**
     * Copy, not a key: two or more words that make up at least half of the
     * text (a base64 blob has the odd letter run too), a single capitalised
     * word, or a template with words beside it. A template is written `#`.
     */
    fun isProse(text: String, hasTemplate: Boolean): Boolean {
        val tokens = text.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val words = tokens
            .map { it.trim { c -> !c.isLetter() } }
            .filter { it.length >= 2 && word.matches(it) }
        return when {
            words.size >= 2 && words.size * 2 >= tokens.size -> true
            words.size == 1 && Regex("""[A-Z][a-z]+""").matches(words[0]) -> true
            else -> hasTemplate && text.any { it.isWhitespace() } &&
                words.any { w -> w.any { it.isLowerCase() } }
        }
    }
}
