package app.birdo.vpn.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * MR-197 / MR-1754 (C1c): the contract version the app sends is the version of
 * the schema it vendors. Four places say it and all four must agree: the
 * schema's `x-contractVersion`, its `_status.contractVersion`, the sibling
 * `contract/CONTRACT_VERSION`, and the constant the interceptor sends. Both
 * the Android constant and the iOS `ClientIdentity.contractVersion` are held
 * to it here, because the iOS test bundle cannot read the repo.
 *
 * (The drift job that compares against birdo-web needs a read token the owner
 * has not created yet; it is NOT part of this change.)
 */
class ContractVersionTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found")
        }
        dir
    }

    private fun text(path: String) = File(repoRoot, path).readText().replace("\r\n", "\n")

    private val schema by lazy { Json.parseToJsonElement(text("contract/vpn-protocol.schema.json")).jsonObject }

    @Test
    fun `the schema is stamped and both stamps agree`() {
        val version = schema["x-contractVersion"]?.jsonPrimitive?.content
        assertTrue("the vendored schema has no x-contractVersion", version != null)
        assertTrue("not MAJOR.MINOR.PATCH: $version", Regex("""\d+\.\d+\.\d+""").matches(version!!))
        assertEquals(version, schema["_status"]!!.jsonObject["contractVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the CONTRACT_VERSION file, the constant and the iOS value are the schema's version`() {
        val version = schema["x-contractVersion"]!!.jsonPrimitive.content
        assertEquals(version, text("contract/CONTRACT_VERSION").trim())
        assertEquals("AuthInterceptor sends the wrong version", version, CONTRACT_VERSION)
        val ios = Regex("""static let contractVersion = "([^"]+)"""")
            .find(text("iosApp/iosApp/Services/ClientIdentity.swift"))?.groupValues?.get(1)
        assertEquals("iOS ClientIdentity.contractVersion", version, ios)
    }

    @Test
    fun `the vendored schema matches its CONTRACT sha256 pin`() {
        val lf = text("contract/vpn-protocol.schema.json")
        val digest = MessageDigest.getInstance("SHA-256").digest(lf.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(text("contract/CONTRACT.sha256").trim().substringBefore(' '), digest)
    }

    @Test
    fun `iOS sends the version on its session headers`() {
        val api = text("iosApp/iosApp/Services/APIClient.swift")
        assertTrue("\"X-Birdo-Contract\": ClientIdentity.contractVersion" in api)
    }
}
