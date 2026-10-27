package app.birdo.vpn.data.repository

import app.birdo.vpn.R
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.model.MultiHopConnectResponse
import app.birdo.vpn.di.NetworkModule
import app.birdo.vpn.service.FailureKind
import app.birdo.vpn.testing.StringsXml
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 *
 * MR-139 (birdo-web #739): a connect refused inside its 200 carries a typed
 * `refusalReason` (`$defs.ConnectRefusal`). The client keys its message and
 * its [FailureKind] on it, and handles a refusal with no reason, or one newer
 * than this build, by its wording as before.
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

        /**
         * [schema] with a local `$ref` (`#/$defs/<name>`) followed. Since
         * MR-1637 every retryable code's `details` is a `$ref` to
         * `$defs.RetryableDetails` instead of an inline object.
         */
        fun resolved(schema: JsonObject?): JsonObject? {
            val ref = schema?.get("\$ref")?.jsonPrimitive?.content ?: return schema
            return defs[ref.removePrefix("#/\$defs/")]?.jsonObject
        }

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

        /**
         * vpn.controller.ts rethrowRetryableConnectRefusal for
         * TunnelAddressUnavailableError (birdo-web #711), RETRY_AFTER_SECONDS = 15.
         */
        const val TUNNEL = """{"statusCode":503,""" +
            """"message":"We could not assign your connection an address on this server just now. Please try again in a moment.",""" +
            """"error":"tunnel_address_unavailable","timestamp":"2026-10-15T09:00:00.000Z","path":"/vpn/multi-hop/connect",""" +
            """"details":{"retryable":true,"retryAfterSeconds":15}}"""

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
            val detailsRequired = resolved(props["details"]?.jsonObject)?.get("required")?.jsonArray
                ?.map { it.jsonPrimitive.content }.orEmpty()
            val contractRetryable = status == 503 && "retryAfterSeconds" in detailsRequired
            assertEquals("${code.wire}: status $status, details $detailsRequired", contractRetryable, code.retryable)
        }
        assertTrue(ConnectErrorCode.PSK_DELIVERY_UNAVAILABLE.retryable)
        assertTrue(ConnectErrorCode.QUOTA_CHECK_UNAVAILABLE.retryable)
        assertTrue(ConnectErrorCode.fromWire("tunnel_address_unavailable").retryable)
        assertFalse(ConnectErrorCode.UNKNOWN.retryable)
    }

    @Test
    fun `the sample bodies are the server's shape, and the one without a wait is off-contract`() {
        assertTrue(validate("psk_delivery_unavailable", PSK).toString(), validate("psk_delivery_unavailable", PSK).isEmpty())
        assertTrue(validate("quota_check_unavailable", QUOTA).toString(), validate("quota_check_unavailable", QUOTA).isEmpty())
        assertTrue(validate("tunnel_address_unavailable", TUNNEL).toString(), validate("tunnel_address_unavailable", TUNNEL).isEmpty())
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

    // ── tunnel_address_unavailable (MR-2058, birdo-web #711) ─────────────

    @Test
    fun `tunnel_address_unavailable is a retryable 503 that waits the server's 15 s and says try again`() {
        val e = connectFailure(503, TUNNEL)
        // Decoded as the contract's code, not UNKNOWN classified by its status.
        assertEquals("tunnel_address_unavailable", e.serverCode.wire)
        assertTrue(e.serverCode.retryable)
        assertEquals(FailureReason.SERVER_UNAVAILABLE, e.reason)
        val copy = StringsXml.text("error_server_unavailable")
        assertEquals(copy, e.message)
        assertTrue(copy, copy.contains("try again", ignoreCase = true))
        assertEquals(15_000L, e.retryAfterMs)
        assertEquals(FailureKind.TRANSIENT, FailureKind.forApiError(e.code, e.serverCode))
    }

    @Test
    fun `tunnel_address_unavailable without its wait, or on another status, is still a retry after a wait`() {
        // A body that lost retryAfterSeconds on the way waits the retryable default,
        // never the supervisor's bare 2 s backoff.
        val noWait = connectFailure(503, TUNNEL.replace(""","retryAfterSeconds":15""", ""))
        assertEquals("tunnel_address_unavailable", noWait.serverCode.wire)
        assertEquals(RetryAfter.RETRYABLE_DEFAULT_MS, noWait.retryAfterMs)
        // The code decides, not the status it rode in on.
        val on409 = connectFailure(409, TUNNEL.replace("\"statusCode\":503", "\"statusCode\":409"))
        assertEquals(FailureReason.SERVER_UNAVAILABLE, on409.reason)
        assertEquals(FailureKind.TRANSIENT, FailureKind.forApiError(on409.code, on409.serverCode))
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

    // ── Connect refusal reason (MR-139, birdo-web #739) ──────────────────

    /** `$defs.ConnectRefusal`: what a refused connect's `refusalReason` may say. */
    private val contractRefusals: List<String>
        get() = defs["ConnectRefusal"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

    /** A connect refused inside its 200, as vpn.service.ts connect() writes it. */
    private fun refusedConnect(reason: String?, message: String, quotaExceeded: Boolean = false): String =
        JsonObject(
            buildMap {
                put("success", JsonPrimitive(false))
                put("message", JsonPrimitive(message))
                if (reason != null) put("refusalReason", JsonPrimitive(reason))
                if (quotaExceeded) put("quotaExceeded", JsonPrimitive(true))
            },
        ).toString()

    @Test
    fun `ConnectRefusalReason is exactly the contract's ConnectRefusal, which is what refusalReason carries`() {
        assertTrue("\$defs.ConnectRefusal has no values", contractRefusals.isNotEmpty())
        val client = ConnectRefusalReason.entries.map { it.wire }
        assertEquals("re-vendored contract and ConnectRefusalReason disagree", contractRefusals.toSet(), client.toSet())
        assertEquals("two values share a wire spelling", client.size, client.toSet().size)
        val property = defs["ConnectResponse"]!!.jsonObject["properties"]!!.jsonObject["refusalReason"]!!.jsonObject
        assertEquals(contractRefusals, property["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        contractRefusals.forEach { assertEquals(it, ConnectRefusalReason.fromWire(it)?.wire) }
    }

    @Test
    fun `a refused connect for every contract reason validates, decodes on both routes and gets its handling`() {
        // Not the device cap's usual "Device limit reached …": the code decides, never the words.
        val message = "The server refused this connection."
        val expected = mapOf(
            "device_limit" to (StringsXml.text("error_device_limit") to FailureKind.REFUSED),
            "plan" to (message to FailureKind.PLAN_REQUIRED),
            "quota_exceeded" to (message to FailureKind.QUOTA_EXCEEDED),
            "server_capacity" to (message to FailureKind.REFUSED),
            "server_unavailable" to (message to FailureKind.REFUSED),
        )
        assertEquals("a contract reason with no decided handling here", contractRefusals.toSet(), expected.keys)
        for (reason in contractRefusals) {
            // quota_exceeded always rides with quotaExceeded: true (the contract says so).
            val body = refusedConnect(reason, message, quotaExceeded = reason == "quota_exceeded")
            val invalid = validate("ConnectResponse", body)
            assertTrue("$reason: $invalid", invalid.isEmpty())
            val single = NetworkModule.json.decodeFromString(serializer<ConnectResponse>(), body)
            val multi = NetworkModule.json.decodeFromString(serializer<MultiHopConnectResponse>(), body)
            assertEquals(reason, single.refusalReason)
            assertEquals(reason, multi.refusalReason)
            val (copy, kind) = expected.getValue(reason)
            assertEquals(reason, copy, mapper.connectRefusal(single.message, single.refusalReason).second)
            assertEquals(reason, copy, mapper.connectRefusal(multi.message, multi.refusalReason).second)
            assertEquals(reason, kind, FailureKind.forRefusal(single.quotaExceeded, single.refusalReason))
            assertEquals(reason, kind, FailureKind.forRefusal(multi.quotaExceeded, multi.refusalReason))
        }
        // A reason other than the cap keeps the server's words, even words that look like the cap's.
        val capWords = "Device limit reached (5 devices for OPERATIVE plan)"
        assertEquals(FailureReason.REFUSED to capWords, mapper.connectRefusal(capWords, "server_capacity"))
        // quotaExceeded still ends the session whatever the reason says.
        assertEquals(FailureKind.QUOTA_EXCEEDED, FailureKind.forRefusal(true, "device_limit"))
    }

    @Test
    fun `a refusal with no reason, or one newer than this build, is handled by its wording as before`() {
        val cap = "Device limit reached (5 devices for OPERATIVE plan)"
        val full = "This server is at capacity. Try another location."
        for (reason in listOf(null, "device_cap_v2")) {
            val decoded = NetworkModule.json.decodeFromString(serializer<ConnectResponse>(), refusedConnect(reason, cap))
            assertEquals(reason, decoded.refusalReason)
            assertNull(ConnectRefusalReason.fromWire(decoded.refusalReason))
            assertEquals(
                "$reason",
                FailureReason.DEVICE_LIMIT to StringsXml.text("error_device_limit"),
                mapper.connectRefusal(decoded.message, decoded.refusalReason),
            )
            assertEquals("$reason", FailureReason.REFUSED to full, mapper.connectRefusal(full, reason))
            assertEquals("$reason", FailureKind.REFUSED, FailureKind.forRefusal(decoded.quotaExceeded, decoded.refusalReason))
            assertEquals("$reason", FailureKind.QUOTA_EXCEEDED, FailureKind.forRefusal(true, reason))
        }
        // The validator bites on a value outside the contract, yet the app still decodes it (above).
        val newer = validate("ConnectResponse", refusedConnect("device_cap_v2", cap))
        assertTrue("expected an 'enum' refusal, got $newer", newer.any { it.keyword == "enum" })
    }
}
