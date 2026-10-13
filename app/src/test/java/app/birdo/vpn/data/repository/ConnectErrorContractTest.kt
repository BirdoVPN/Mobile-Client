package app.birdo.vpn.data.repository

import app.birdo.vpn.R
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.di.NetworkModule
import app.birdo.vpn.service.FailureKind
import app.birdo.vpn.testing.StringsXml
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MR-1639: the connect routes' error vocabulary, held to the vendored contract.
 *
 * birdo-web answers a connect it cannot complete safely RIGHT NOW with a 503
 * whose `error` names why and whose `details.retryAfterSeconds` says when to
 * come back (protocol-schema.ts CONNECT_ERROR_CODES; VpnController
 * rethrowPskDeliveryRefusal for psk_delivery_unavailable, the FREE-TIER CAP
 * catch for quota_check_unavailable; GlobalExceptionFilter copies `details`
 * to the wire). The bodies below are that envelope verbatim in shape, and the
 * contract-backed ones are validated against the schema's own definitions
 * first, so a pass here is a pass against what the server sends.
 *
 * The client must: map both 503s as retryable and honour the wait (clamped),
 * fall back to a sane wait when a retryable body lost it, and decode any code
 * it does not know to [ConnectErrorCode.UNKNOWN] rather than fail.
 */
class ConnectErrorContractTest {

    private companion object {
        val repoRoot: File by lazy {
            var dir = File("").absoluteFile
            while (!File(dir, "settings.gradle.kts").isFile) {
                dir = dir.parentFile
                    ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
            }
            dir
        }

        const val SCHEMA_ID = "https://birdo.app/contract/vpn-protocol.schema.json"

        val schemaText: String by lazy { File(repoRoot, "contract/vpn-protocol.schema.json").readText() }
        val defs: JsonObject by lazy { Json.parseToJsonElement(schemaText).jsonObject["\$defs"]!!.jsonObject }

        val registry: SchemaRegistry by lazy {
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
                builder.schemas(mapOf(SCHEMA_ID to schemaText))
            }
        }

        fun validate(definition: String, body: String) =
            registry.getSchema(SchemaLocation.of("$SCHEMA_ID#/\$defs/$definition")).validate(body, InputFormat.JSON)

        // ── Bodies, GlobalExceptionFilter's envelope ─────────────────────

        /** vpn.controller.ts rethrowPskDeliveryRefusal, with PskOverSshRefusedError.RETRY_AFTER_SECONDS = 30. */
        const val PSK = """{"statusCode":503,""" +
            """"message":"We could not set up a secure connection to this server just now. Please try again in a moment.",""" +
            """"error":"psk_delivery_unavailable","timestamp":"2026-10-13T09:00:00.000Z","path":"/vpn/connect",""" +
            """"requestId":"req-1","details":{"retryable":true,"retryAfterSeconds":30}}"""

        /** The same refusal with the wait missing (a proxy that rewrote it, a server off-contract). */
        const val PSK_NO_WAIT = """{"statusCode":503,""" +
            """"message":"We could not set up a secure connection to this server just now. Please try again in a moment.",""" +
            """"error":"psk_delivery_unavailable","timestamp":"2026-10-13T09:00:00.000Z","path":"/vpn/multi-hop/connect",""" +
            """"details":{"retryable":true}}"""

        /** vpn.controller.ts FREE-TIER CAP catch, with QuotaCheckUnavailableError.RETRY_AFTER_SECONDS = 30. */
        const val QUOTA = """{"statusCode":503,""" +
            """"message":"We could not check your free data allowance just now. Please try again in a moment.",""" +
            """"error":"quota_check_unavailable","timestamp":"2026-10-13T09:00:00.000Z","path":"/vpn/connect",""" +
            """"details":{"retryable":true,"retryAfterSeconds":30}}"""

        /** A code newer than this build, with details it has never seen. */
        const val UNKNOWN_503 = """{"statusCode":503,"message":"Relay is warming up.",""" +
            """"error":"relay_warming_up","timestamp":"2026-10-13T09:00:00.000Z","path":"/vpn/connect",""" +
            """"details":{"retryable":true,"retryAfterSeconds":45,"phase":{"step":2}}}"""

        /** A plain Nest 503: `error` is the reason phrase, not a code. */
        const val PLAIN_503 = """{"statusCode":503,"message":"Service Unavailable","error":"Service Unavailable"}"""
    }

    private val mapper = ApiErrorMapper(StringsXml)

    /** What BirdoRepository.errorFrom returns for a connect that answered [status] with [body]. */
    private fun connectFailure(status: Int, body: String?, retryAfterHeader: String? = null): ApiResult.Error {
        val error = mapper.fromResponse(status, body, ErrorContext.GENERAL, R.string.error_connect_failed)
        return error.copy(retryAfterMs = RetryAfter.forFailure(body, retryAfterHeader, error.serverCode))
    }

    // ── The vocabulary is the contract's ─────────────────────────────────

    @Test
    fun `every error code the vendored contract defines decodes to a known code, and no other`() {
        val contract = defs["ErrorCode"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        val client = ConnectErrorCode.entries.mapNotNull { it.wire }.toSet()
        assertEquals("re-vendored contract and ConnectErrorCode disagree", contract, client)
        contract.forEach { assertTrue(it, ConnectErrorCode.fromWire(it) != ConnectErrorCode.UNKNOWN) }
    }

    @Test
    fun `a code is retryable exactly when the contract makes it a 503 that must say when to come back`() {
        for (code in ConnectErrorCode.entries.filter { it.wire != null }) {
            val def = defs[code.wire!!]!!.jsonObject
            val props = def["properties"]!!.jsonObject
            val status = props["statusCode"]!!.jsonObject["const"]!!.jsonPrimitive.content.toInt()
            val detailsRequired = props["details"]?.jsonObject?.get("required")?.jsonArray
                ?.map { it.jsonPrimitive.content }.orEmpty()
            val contractRetryable = status == 503 && "retryAfterSeconds" in detailsRequired
            assertEquals("${code.wire}: status $status, details $detailsRequired", contractRetryable, code.retryable)
        }
        assertTrue(ConnectErrorCode.PSK_DELIVERY_UNAVAILABLE.retryable)
        assertTrue(ConnectErrorCode.QUOTA_CHECK_UNAVAILABLE.retryable)
        assertFalse(ConnectErrorCode.UNKNOWN.retryable)
    }

    @Test
    fun `the sample bodies are the server's shape, and the one without a wait is off-contract`() {
        assertTrue(validate("psk_delivery_unavailable", PSK).toString(), validate("psk_delivery_unavailable", PSK).isEmpty())
        assertTrue(validate("quota_check_unavailable", QUOTA).toString(), validate("quota_check_unavailable", QUOTA).isEmpty())
        val missing = validate("psk_delivery_unavailable", PSK_NO_WAIT)
        assertTrue("expected a 'required' refusal, got $missing", missing.any { it.keyword == "required" })
    }

    // ── psk_delivery_unavailable ─────────────────────────────────────────

    @Test
    fun `psk_delivery_unavailable is a retryable 503 that waits the server's 30 s`() {
        val e = connectFailure(503, PSK)
        assertEquals(ConnectErrorCode.PSK_DELIVERY_UNAVAILABLE, e.serverCode)
        assertEquals(503, e.code)
        assertEquals(FailureReason.SERVER_UNAVAILABLE, e.reason)
        assertEquals(StringsXml.text("error_secure_setup_unavailable"), e.message)
        assertEquals(30_000L, e.retryAfterMs)
        assertEquals(FailureKind.TRANSIENT, FailureKind.forApiError(e.code, e.serverCode))
    }

    @Test
    fun `psk_delivery_unavailable without retryAfterSeconds still waits, never the 2 s backoff alone`() {
        val e = connectFailure(503, PSK_NO_WAIT)
        assertEquals(ConnectErrorCode.PSK_DELIVERY_UNAVAILABLE, e.serverCode)
        assertEquals(RetryAfter.RETRYABLE_DEFAULT_MS, e.retryAfterMs)
        assertEquals(30_000L, RetryAfter.RETRYABLE_DEFAULT_MS)
        assertEquals(FailureKind.TRANSIENT, FailureKind.forApiError(e.code, e.serverCode))
        // A Retry-After header, when there is one, beats the default.
        assertEquals(12_000L, connectFailure(503, PSK_NO_WAIT, retryAfterHeader = "12").retryAfterMs)
    }

    @Test
    fun `the server's wait is honoured as sent, and clamped at both ends`() {
        fun pskWaiting(seconds: String) = PSK.replace("\"retryAfterSeconds\":30", "\"retryAfterSeconds\":$seconds")
        assertEquals(120_000L, connectFailure(503, pskWaiting("120")).retryAfterMs)
        assertEquals(RetryAfter.MAX_MS, connectFailure(503, pskWaiting("86400")).retryAfterMs)
        // Used to overflow into a negative and come out as the 1 s floor.
        assertEquals(RetryAfter.MAX_MS, connectFailure(503, pskWaiting(Long.MAX_VALUE.toString())).retryAfterMs)
        // Unusable: the retryable code's default, never "retry now".
        assertEquals(RetryAfter.RETRYABLE_DEFAULT_MS, connectFailure(503, pskWaiting("0")).retryAfterMs)
        assertEquals(RetryAfter.RETRYABLE_DEFAULT_MS, connectFailure(503, pskWaiting("\"soon\"")).retryAfterMs)
    }

    @Test
    fun `a retryable code is retryable whatever status it rides in on`() {
        val e = connectFailure(409, PSK.replace("\"statusCode\":503", "\"statusCode\":409"))
        assertEquals(ConnectErrorCode.PSK_DELIVERY_UNAVAILABLE, e.serverCode)
        assertEquals(FailureReason.SERVER_UNAVAILABLE, e.reason)
        assertEquals(FailureKind.TRANSIENT, FailureKind.forApiError(e.code, e.serverCode))
        // The same 409 without the code is a refusal, as it always was.
        assertEquals(FailureKind.REFUSED, FailureKind.forApiError(409, ConnectErrorCode.UNKNOWN))
    }

    // ── quota_check_unavailable (unchanged behaviour, now by code) ───────

    @Test
    fun `quota_check_unavailable is a retryable 503 that waits the server's 30 s`() {
        val e = connectFailure(503, QUOTA)
        assertEquals(ConnectErrorCode.QUOTA_CHECK_UNAVAILABLE, e.serverCode)
        assertEquals(FailureReason.SERVER_UNAVAILABLE, e.reason)
        assertEquals(StringsXml.text("error_server_unavailable"), e.message)
        assertEquals(30_000L, e.retryAfterMs)
        assertEquals(FailureKind.TRANSIENT, FailureKind.forApiError(e.code, e.serverCode))
    }

    // ── Unknown codes fall back, never fail ──────────────────────────────

    @Test
    fun `an unknown code decodes to the fallback and is classified by its status`() {
        val e = connectFailure(503, UNKNOWN_503)
        assertEquals(ConnectErrorCode.UNKNOWN, e.serverCode)
        assertEquals(FailureReason.SERVER_UNAVAILABLE, e.reason)
        assertEquals(StringsXml.text("error_server_unavailable"), e.message)
        assertEquals(FailureKind.TRANSIENT, FailureKind.forApiError(e.code, e.serverCode))
        // A wait the server named is still a wait, whatever the code.
        assertEquals(45_000L, e.retryAfterMs)

        val refusal = connectFailure(409, """{"statusCode":409,"message":"This route is closed.","error":"route_closed"}""")
        assertEquals(ConnectErrorCode.UNKNOWN, refusal.serverCode)
        assertEquals(FailureReason.REFUSED, refusal.reason)
        assertEquals("This route is closed.", refusal.message)
        assertNull(refusal.retryAfterMs)
        assertEquals(FailureKind.REFUSED, FailureKind.forApiError(refusal.code, refusal.serverCode))
    }

    @Test
    fun `no code, a reason phrase, or no body at all is the fallback too, with no invented wait`() {
        for (body in listOf(PLAIN_503, """{"statusCode":503}""", "<html>503</html>", "", null, """{"error":42}""")) {
            val e = connectFailure(503, body)
            assertEquals("body: $body", ConnectErrorCode.UNKNOWN, e.serverCode)
            assertEquals("body: $body", FailureReason.SERVER_UNAVAILABLE, e.reason)
            assertNull("body: $body", e.retryAfterMs)
        }
        assertEquals(ConnectErrorCode.UNKNOWN, ConnectErrorCode.fromWire(null))
        assertEquals(ConnectErrorCode.UNKNOWN, ConnectErrorCode.fromWire("PSK_DELIVERY_UNAVAILABLE"))
    }

    @Test
    fun `a ConnectResponse with keys and values this build does not know still decodes, quotaExceeded included`() {
        val body = """{"success":false,"message":"You've used your free data for this month.","quotaExceeded":true,""" +
            """"rebuildRefused":"a-refusal-from-the-future","someNewBlock":{"a":[1,2]},"stealthUnavailableReason":"new"}"""
        val decoded = NetworkModule.json.decodeFromString(serializer<ConnectResponse>(), body)
        assertFalse(decoded.success)
        assertTrue(decoded.quotaExceeded)
        assertEquals("a-refusal-from-the-future", decoded.rebuildRefused)
    }
}
