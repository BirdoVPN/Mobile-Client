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
 * models and the app's main sources is an API model. A constructor property
 * whose type can hold a secret (String, CharArray, ByteArray) and whose name
 * says it is one (token, password, code, verifier, secret, privateKey,
 * presharedKey, challenge) requires an `override fun toString` in the class
 * body, and that toString may not print the property as a plain template
 * (`$name` / `${name}`). A field that matches the names but is not a secret is
 * listed in [notSecret] with the reason.
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
}

/** The source scan behind [CredentialFieldGuardTest]. */
internal object CredentialFields {

    class Field(val owner: String, val name: String, val problem: String?)

    /** Name fragments that mark a credential, matched case-insensitively. */
    private val secretWords = listOf("token", "password", "verifier", "secret", "privatekey", "presharedkey", "challenge")

    /** Types that can hold a secret's text. */
    private val secretTypes = setOf("String", "CharArray", "ByteArray")

    private val dataClass = Regex("""\bdata\s+class\s+(\w+)\s*\(""")
    private val property = Regex("""\bva[lr]\s+(\w+)\s*:\s*([\w.]+)\??""")
    private val serializable = Regex("""@Serializable(\s*\([^)]*\))?\s*$""")

    fun isCredentialName(name: String): Boolean {
        val lower = name.lowercase()
        // "code" only as a whole camelCase word: twoFactorCode, code; not "encoded".
        val words = name.split(Regex("(?<=[a-z0-9])(?=[A-Z])")).map { it.lowercase() }
        return secretWords.any { it in lower } || "code" in words
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
            val body = kt.trailingBlock(close)?.let { src.substring(it.first, it.last + 1) }.orEmpty()
            val toString = body.substringAfter("override fun toString", "")
            property.findAll(params)
                .filter { p -> p.groupValues[2] in secretTypes && isCredentialName(p.groupValues[1]) }
                .map { p ->
                    val name = p.groupValues[1]
                    // `$name` or `${name}` prints the value; `${redacted(name)}` does not.
                    val problem = when {
                        toString.isEmpty() -> "has no redacting toString"
                        Regex("""\$$name\b|\$\{\s*$name\s*}""").containsMatchIn(toString) -> "prints it in toString"
                        else -> null
                    }
                    Field(owner, name, problem)
                }
        }.toList()
    }
}
