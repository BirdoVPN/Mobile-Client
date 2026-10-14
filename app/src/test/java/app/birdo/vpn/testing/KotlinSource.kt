package app.birdo.vpn.testing

/**
 * Kotlin source, lexed just far enough for the source-reading guard tests to
 * tell code, comments and string literals apart. A regex gets one of them
 * wrong: strings hold `//` (URLs) and escaped quotes, templates hold whole
 * expressions with strings of their own, and comments hold quotes.
 */
class KotlinSource(private val src: String) {

    /** A string literal: where it starts, its text with escapes resolved and each template written `#`. */
    class Literal(val offset: Int, val text: String, val hasTemplate: Boolean)

    /** [src] with comments and string literals blanked, offsets kept, so brackets match in code alone. */
    val code: String

    /**
     * [src] with comments and the TEXT of string literals blanked but the
     * expressions of their templates kept, offsets kept: `"a=${f(x)}"` reads
     * `f(x)` and `"a=$x"` reads `x`. Every name a piece of code reads, inside
     * templates too.
     */
    val expressions: String

    /** Every string literal outside comments, in source order. */
    val literals: List<Literal>

    init {
        val lexer = Lexer(src).also { it.run() }
        code = String(lexer.masked)
        expressions = String(lexer.expressions)
        literals = lexer.literals
    }

    fun lineOf(offset: Int): Int = src.substring(0, offset).count { it == '\n' } + 1

    /** The offset of the bracket that closes the `(` / `{` at [open], matched in [code]. */
    fun closing(open: Int): Int {
        val o = code[open]
        val c = if (o == '(') ')' else '}'
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == o) depth++
            if (code[i] == c && --depth == 0) return i
        }
        return code.length - 1
    }

    /** The `{ … }` block right after offset [after], skipping whitespace, or null when there is none. */
    fun trailingBlock(after: Int): IntRange? {
        var next = after + 1
        while (next < code.length && code[next].isWhitespace()) next++
        return if (next < code.length && code[next] == '{') next..closing(next) else null
    }

    private class Lexer(private val src: String) {
        val masked = src.toCharArray()
        val expressions = src.toCharArray()
        val literals = mutableListOf<Literal>()
        private var i = 0

        fun run() = code(untilTemplateEnd = false)

        private fun blank(from: Int, to: Int) {
            blank(masked, from, to)
            blank(expressions, from, to)
        }

        private fun blank(chars: CharArray, from: Int, to: Int) {
            for (k in from until minOf(to, src.length)) if (chars[k] != '\n') chars[k] = ' '
        }

        private fun code(untilTemplateEnd: Boolean) {
            var depth = 0
            while (i < src.length) {
                when {
                    src.startsWith("//", i) -> {
                        val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                        blank(i, end)
                        i = end
                    }
                    src.startsWith("/*", i) -> {
                        val end = blockCommentEnd(i)
                        blank(i, end)
                        i = end
                    }
                    src.startsWith("\"\"\"", i) -> string(raw = true)
                    src[i] == '"' -> string(raw = false)
                    src[i] == '\'' -> char()
                    src[i] == '{' -> { depth++; i++ }
                    src[i] == '}' -> {
                        if (untilTemplateEnd && depth == 0) return
                        depth--
                        i++
                    }
                    else -> i++
                }
            }
        }

        /** Kotlin block comments nest. */
        private fun blockCommentEnd(from: Int): Int {
            var depth = 0
            var j = from
            while (j < src.length) {
                when {
                    src.startsWith("/*", j) -> { depth++; j += 2 }
                    src.startsWith("*/", j) -> { depth--; j += 2; if (depth == 0) return j }
                    else -> j++
                }
            }
            return src.length
        }

        private fun char() {
            val start = i++
            i += when {
                src[i] != '\\' -> 1
                src[i + 1] == 'u' -> 6
                else -> 2
            }
            if (i < src.length && src[i] == '\'') i++
            blank(start, i)
        }

        private fun string(raw: Boolean) {
            val start = i
            val quote = if (raw) "\"\"\"" else "\""
            i += quote.length
            val text = StringBuilder()
            var template = false
            // The template expressions, which [expressions] keeps.
            val kept = mutableListOf<IntRange>()
            while (i < src.length) {
                if (src.startsWith(quote, i)) {
                    i += quote.length
                    break
                }
                val c = src[i]
                when {
                    !raw && c == '\\' -> {
                        val n = src[i + 1]
                        if (n == 'u') {
                            text.append(src.substring(i + 2, i + 6).toInt(16).toChar())
                            i += 6
                        } else {
                            text.append(if (n in "ntr") ' ' else n)
                            i += 2
                        }
                    }
                    c == '$' && src.getOrNull(i + 1) == '{' -> {
                        template = true
                        i += 2
                        val from = i
                        code(untilTemplateEnd = true)
                        kept += from until i
                        i++
                        text.append('#')
                    }
                    c == '$' && src.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true -> {
                        template = true
                        i++
                        val from = i
                        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                        kept += from until i
                        text.append('#')
                    }
                    else -> {
                        text.append(c)
                        i++
                    }
                }
            }
            blank(masked, start, i)
            var k = start
            for (expression in kept) {
                blank(expressions, k, expression.first)
                k = expression.last + 1
            }
            blank(expressions, k, i)
            literals += Literal(start, text.toString(), template)
        }
    }
}
