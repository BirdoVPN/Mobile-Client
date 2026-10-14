package app.birdo.vpn.data.model

import app.birdo.vpn.testing.KotlinSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-1947: a NEW credential-named field on an API model fails the build until
 * the model has a redacting toString.
 *
 * CredentialToStringTest (MR-944, MR-1870) pins the models that exist today,
 * one by one. It cannot see a field added tomorrow: a data class prints every
 * constructor property through its generated toString, so a new `val
 * someToken: String` on an existing model, or a new model with a password, is
 * a leak the moment it is logged, and nothing fails.
 *
 * This reads the source. Every `@Serializable data class` under the shared
 * models and the app's main sources is an API model, whatever annotations or
 * modifiers sit between the two. A constructor property whose type can hold a
 * secret (String, CharArray, ByteArray) and whose name says it is one (token,
 * password, code, verifier, secret, privateKey, presharedKey, psk, challenge,
 * nonce, accountNumber, anonymousId) requires an `override fun toString` in
 * the class body, and that toString may not read the property except to
 * redact it or test it for null or empty: not as a template (`$name`,
 * `${name}`, `${this.name}`), not concatenated, not passed to a call. A field
 * that matches the names but is not a secret is listed in [notSecret] with the
 * reason.
 */
class CredentialFieldGuardTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    /** The trees whose `@Serializable` data classes are API models. */
    private val scanned = listOf("shared/src/commonMain/kotlin", "app/src/main/java")

    /**
     * `Class.field` names that match the credential words but carry no secret.
     * Keep it short, and say why for each.
     */
    private val notSecret = mapOf(
        // An ISO 3166 country code, shown in the server list.
        "VpnServer.countryCode" to "ISO country code",
        // The server's machine-readable refusal code ("purchase_not_found").
        "StoreErrorDetails.code" to "error code",
    )

    @Test
    fun `no API model can print a credential-named field`() {
        val found = scanned.flatMap { root ->
            File(repoRoot, root).walkTopDown().filter { it.isFile && it.extension == "kt" }
                .flatMap { f -> CredentialFields.scan(f.readText()).map { it to f.relativeTo(repoRoot).invariantSeparatorsPath } }
                .toList()
        }
        // Vacuity guard: today's models hold well over a dozen such fields
        // (the passwords, tokens, 2FA challenge and code, SSO code and
        // verifier, WireGuard keys, voucher and purchase tokens). A scan that
        // finds a handful is a broken scan, not a clean tree.
        assertTrue("found only ${found.size} credential-named fields; the scan is broken", found.size >= 15)

        val leaks = found
            .filter { (field, _) -> "${field.owner}.${field.name}" !in notSecret }
            .filter { (field, _) -> field.problem != null }
            .map { (field, path) -> "$path: ${field.owner}.${field.name} ${field.problem}" }
        assertEquals(
            "API model fields named like a credential, printed by toString. Give the class an " +
                "`override fun toString` that redacts them (see CredentialToStringTest), or add the " +
                "field to notSecret with the reason it is not a secret",
            emptyList<String>(),
            leaks,
        )

        // An exemption that no longer matches a field is a hole for the next one.
        val stale = notSecret.keys - found.map { (field, _) -> "${field.owner}.${field.name}" }.toSet()
        assertEquals("stale notSecret entries", emptySet<String>(), stale)
    }

    // ── The scanner itself, on fixtures, so a broken scan cannot pass ─────

    @Test
    fun `a credential field with no toString is caught`() {
        val src = """
            @Serializable
            data class NewRequest(
                val deviceName: String,
                @SerialName("api_token") val apiToken: String,
            )
        """.trimIndent()
        val fields = CredentialFields.scan(src)
        assertEquals(listOf("apiToken"), fields.map { it.name })
        assertEquals("has no redacting toString", fields.single().problem)
    }

    @Test
    fun `a toString that still prints the field is caught`() {
        val src = """
            @Serializable
            data class NewRequest(val password: String, val platform: String) {
                override fun toString(): String = "NewRequest(password=${'$'}password, platform=${'$'}platform)"
            }
        """.trimIndent()
        assertEquals("prints it in toString", CredentialFields.scan(src).single().problem)
    }

    @Test
    fun `a redacting toString passes, and so do non-secret types and names`() {
        val src = """
            @Serializable
            data class SignIn(
                val password: String,
                val twoFactorCode: String? = null,
                val hasPassword: Boolean = true,
                val tokens: TokenPair? = null,
                val email: String,
            ) {
                override fun toString(): String =
                    "SignIn(password=[REDACTED], twoFactorCode=${'$'}{redacted(twoFactorCode)}, hasPassword=${'$'}hasPassword)"
            }

            data class NotAnApiModel(val secret: String)
        """.trimIndent()
        val fields = CredentialFields.scan(src)
        assertEquals(listOf("password", "twoFactorCode"), fields.map { it.name })
        assertTrue(fields.all { it.problem == null })
    }

    @Test
    fun `the account number, the anonymous id, a nonce and a psk are credential names`() {
        // The Swift guard (IosRequiredRowsGuardTest) already knew the first three.
        listOf("accountNumber", "anonymousId", "pendingAnonymousId", "nonce", "attestationNonce", "psk", "wgPsk", "pskBase64")
            .forEach { assertTrue("$it must read as a credential name", CredentialFields.isCredentialName(it)) }
        // "psk" is a whole camelCase word, like "code": not any name that spells it.
        listOf("encoded", "dropsKey", "accountType", "countryName")
            .forEach { assertTrue("$it is not a credential name", !CredentialFields.isCredentialName(it)) }

        val src = """
            @Serializable
            data class Wire(val accountNumber: String?, val anonymousId: String, val nonce: String, val psk: ByteArray)
        """.trimIndent()
        val fields = CredentialFields.scan(src)
        assertEquals(listOf("accountNumber", "anonymousId", "nonce", "psk"), fields.map { it.name })
        assertTrue(fields.all { it.problem == "has no redacting toString" })
    }

    @Test
    fun `an annotation or a modifier between @Serializable and data class does not hide the class`() {
        val src = """
            @Serializable
            @Immutable
            data class Annotated(val apiToken: String)

            @Serializable
            internal data class Internal(val password: String)

            @Serializable(with = OneLineSerializer::class) @SerialName("one_line")
            public data class OneLine(@SerialName("refresh_token") val refreshToken: String)

            @Serializable
            @OptIn(ExperimentalSerializationApi::class)
            @Deprecated("old", ReplaceWith("New"))
            private data class Stacked(val secret: String)

            @Immutable
            internal data class NotAnApiModel(val password: String)
        """.trimIndent()
        val fields = CredentialFields.scan(src)
        assertEquals(listOf("Annotated", "Internal", "OneLine", "Stacked"), fields.map { it.owner })
        assertTrue(fields.all { it.problem == "has no redacting toString" })
    }

    @Test
    fun `a toString that prints the field through this, concatenation or a call is caught`() {
        val shapes = mapOf(
            "this in a template" to "\"Req(password=${'$'}{this.password})\"",
            "concatenation" to "\"Req(password=\" + password + \")\"",
            "this in a concatenation" to "\"Req(\" + this.password + \")\"",
            "plus" to "\"Req(password=\".plus(password)",
            "a builder" to "buildString { append(\"Req(\"); append(password) }",
            "a prefix of it" to "\"Req(password=${'$'}{password.take(4)}…)\"",
            "a block body" to "{\n        val shown = password\n        return \"Req(${'$'}shown)\"\n    }",
        )
        shapes.forEach { (shape, body) ->
            val separator = if (body.startsWith("{")) " " else " = "
            val src = "@Serializable\ndata class Req(val password: String) {\n    override fun toString(): String$separator$body\n}"
            assertEquals(shape, "prints it in toString", CredentialFields.scan(src).single().problem)
        }
    }

    @Test
    fun `a toString that only tests the field passes, and a member after it is not the toString`() {
        val src = """
            @Serializable
            data class Req(val password: String?, val wgPsk: String, val nonce: String, val apiToken: String) {
                override fun toString(): String =
                    "Req(password=${'$'}{redacted(this.password)}, set=${'$'}{password != null}, " +
                        "psk=${'$'}{if (wgPsk.isNotEmpty()) "[REDACTED]" else "none"}, nonce=[REDACTED])"

                fun bearer(): String = "Bearer ${'$'}apiToken"
            }
        """.trimIndent()
        val fields = CredentialFields.scan(src)
        assertEquals(listOf("password", "wgPsk", "nonce", "apiToken"), fields.map { it.name })
        assertEquals(listOf(null, null, null, null), fields.map { it.problem })
    }
}

/** The source scan behind [CredentialFieldGuardTest]. */
internal object CredentialFields {

    class Field(val owner: String, val name: String, val problem: String?)

    /** Name fragments that mark a credential, matched case-insensitively. */
    private val secretWords = listOf(
        "token", "password", "verifier", "secret", "privatekey", "presharedkey", "challenge",
        "nonce", "accountnumber", "anonymousid",
    )

    /** Short words that mark a credential only as a whole camelCase word: twoFactorCode, wgPsk; not "encoded", "dropsKey". */
    private val secretWholeWords = setOf("code", "psk")

    /** Types that can hold a secret's text. */
    private val secretTypes = setOf("String", "CharArray", "ByteArray")

    private val dataClass = Regex("""\bdata\s+class\s+(\w+)\s*\(""")
    private val property = Regex("""\bva[lr]\s+(\w+)\s*:\s*([\w.]+)\??""")

    /** An annotation's arguments, one level of nested parentheses deep. */
    private const val ARGS = """(?:\s*\((?:[^()]|\([^()]*\))*\))"""

    /**
     * `@Serializable` at the end of the code before `data class`, followed by
     * any other annotations and modifiers: `@Serializable @Immutable internal
     * data class` is an API model as much as `@Serializable data class`.
     */
    private val serializable = Regex(
        """@(?:kotlinx\.serialization\.)?Serializable\b$ARGS?""" +
            """(?:\s*(?:@[\w.]+$ARGS?|\b(?:public|internal|protected|private|open|sealed|abstract|inner|expect|actual)\b))*\s*$""",
    )

    private val toStringDeclaration = Regex("""\boverride\s+fun\s+toString\s*\(\s*\)\s*(?::\s*String\s*)?""")

    /** A line that starts the next member of a class body, ending an expression-bodied toString. */
    private val nextMember = Regex(
        """\n\s*(?:@\w|(?:override|public|internal|protected|private|open|abstract|suspend|inline|operator|const|lateinit)\s|""" +
            """(?:fun|val|var|init|companion|object|class|interface|enum|data|sealed|inner)\b)""",
    )

    fun isCredentialName(name: String): Boolean {
        val lower = name.lowercase()
        val words = name.split(Regex("(?<=[a-z0-9])(?=[A-Z])")).map { it.lowercase() }
        return secretWords.any { it in lower } || words.any { it in secretWholeWords }
    }

    /** Every credential-named, secret-typed property of every `@Serializable` data class in [src]. */
    fun scan(src: String): List<Field> {
        val kt = KotlinSource(src)
        return dataClass.findAll(kt.code).flatMap { m ->
            val before = kt.code.substring(0, m.range.first).trimEnd()
            if (!serializable.containsMatchIn(before)) return@flatMap emptySequence()
            val owner = m.groupValues[1]
            val open = m.range.last
            val close = kt.closing(open)
            val params = kt.code.substring(open + 1, close)
            val toString = kt.trailingBlock(close)?.let { toStringRange(kt, it) }
                ?.let { kt.expressions.substring(it.first, it.last + 1) }
            property.findAll(params)
                .filter { p -> p.groupValues[2] in secretTypes && isCredentialName(p.groupValues[1]) }
                .map { p ->
                    val name = p.groupValues[1]
                    val problem = when {
                        toString == null -> "has no redacting toString"
                        reads(toString, name) -> "prints it in toString"
                        else -> null
                    }
                    Field(owner, name, problem)
                }
        }.toList()
    }

    /**
     * The `override fun toString` in the class [body] (the `{ … }` range in
     * [KotlinSource.code]), or null when it has none: a block body up to its
     * closing brace, an expression body up to the next member or the end of
     * the class. Not the rest of the class: a member after toString is not
     * what toString prints.
     */
    private fun toStringRange(kt: KotlinSource, body: IntRange): IntRange? {
        val code = kt.code
        val declaration = toStringDeclaration.find(code.substring(0, body.last), body.first) ?: return null
        var start = declaration.range.last + 1
        while (start < body.last && code[start].isWhitespace()) start++
        if (code[start] == '{') return start..kt.closing(start)
        var depth = 0
        var end = start
        while (end < body.last) {
            val c = code[end]
            if (c in "([{") depth++
            if (c in ")]}") depth--
            if (depth == 0 && c == '\n' && nextMember.matchAt(code, end) != null) break
            end++
        }
        return start until end
    }

    /**
     * True when the toString text [region] (template expressions included)
     * reads the property [name] other than to redact it (`redacted(name)`) or
     * to test it (`name != null`, `name.isNotEmpty()`).
     */
    private fun reads(region: String, name: String): Boolean =
        Regex("""\b$name\b""").findAll(region).any { m ->
            var head = region.substring(0, m.range.first)
            // `other.name` is someone else's property; `this.name` is this one.
            val self = Regex("""\bthis\s*\.\s*$""").find(head)
            if (self == null && Regex("""\.\s*$""").containsMatchIn(head)) return@any false
            if (self != null) head = head.substring(0, self.range.first)
            val tail = region.substring(m.range.last + 1)
            val redacted = Regex("""\w*[Rr]edact\w*\s*\(\s*$""").containsMatchIn(head) && Regex("""^\s*\)""").containsMatchIn(tail)
            val nullTest = Regex("""^\s*[!=]==?\s*null\b""").containsMatchIn(tail) || Regex("""\bnull\s*[!=]==?\s*$""").containsMatchIn(head)
            val emptyTest = Regex("""^\s*\??\.\s*(?:isNullOrEmpty|isNullOrBlank|isEmpty|isNotEmpty|isBlank|isNotBlank)\s*\(\s*\)""")
                .containsMatchIn(tail)
            !(redacted || nullTest || emptyTest)
        }
}
