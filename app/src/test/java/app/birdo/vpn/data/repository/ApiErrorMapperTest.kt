package app.birdo.vpn.data.repository

import app.birdo.vpn.R
import app.birdo.vpn.testing.StringsXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The one place a failed request becomes words on the screen (A2-001).
 *
 * Assertions are against the SHIPPED copy (strings.xml via [StringsXml]), so a
 * pass means the user reads exactly this. The bodies are the backend's real
 * ones (GlobalExceptionFilter envelope; auth.controller validateLoginAttempt,
 * lockout.service, auth.service validateUser, two-factor.controller,
 * gdpr.controller), pinned verbatim.
 *
 * Moved here from AuthViewModelTest, where the same rules used to run as
 * substring matches on the raw body inside the ViewModel.
 */
class ApiErrorMapperTest {

    private val mapper = ApiErrorMapper(StringsXml)
    private fun text(name: String) = StringsXml.text(name)

    private fun signIn(status: Int, body: String?) =
        mapper.fromResponse(status, body, ErrorContext.SIGN_IN, R.string.error_sign_in_failed)

    private fun general(status: Int, body: String?) =
        mapper.fromResponse(status, body, ErrorContext.GENERAL, R.string.error_load_servers)

    /** Nothing a user is shown may be a body, a stack or a resolver's text. */
    private fun assertPresentable(error: ApiResult.Error) {
        val m = error.message
        assertFalse("raw JSON reached the user: $m", m.trimStart().startsWith("{") || "statusCode" in m)
        assertFalse("HTML reached the user: $m", '<' in m)
        assertFalse("exception text reached the user: $m", "Exception" in m || "resolve host" in m)
        assertFalse("hostname reached the user: $m", "api.birdo.app" in m)
    }

    // ── The envelope ────────────────────────────────────────────────────

    @Test
    fun `a 5xx JSON body becomes the canonical unavailable sentence`() {
        val e = general(500, """{"statusCode":500,"message":"Internal server error"}""")
        assertEquals(FailureReason.SERVER_UNAVAILABLE, e.reason)
        assertEquals(text("error_server_unavailable"), e.message)
        assertEquals(500, e.code)
        assertPresentable(e)
    }

    @Test
    fun `an HTML 502 from the edge is never shown`() {
        val e = general(502, "<html><body><h1>502 Bad Gateway</h1></body></html>")
        assertEquals(text("error_server_unavailable"), e.message)
        assertPresentable(e)
    }

    @Test
    fun `a validation array is read as the server's sentences`() {
        val e = mapper.fromResponse(
            400,
            """{"statusCode":400,"message":["internalPort must be at least 1","protocol is invalid"],"error":"Bad Request"}""",
            ErrorContext.GENERAL,
            R.string.error_create_port_forward,
        )
        assertEquals("internalPort must be at least 1; protocol is invalid", e.message)
        assertEquals(FailureReason.REFUSED, e.reason)
    }

    @Test
    fun `a bare reason phrase falls back to the call's own sentence`() {
        val e = mapper.fromResponse(
            404, """{"statusCode":404,"message":"Not Found"}""", ErrorContext.GENERAL, R.string.error_delete_port_forward,
        )
        assertEquals(text("error_delete_port_forward"), e.message)
    }

    @Test
    fun `a refusal the server explained keeps the server's words`() {
        val e = general(409, """{"statusCode":409,"message":"Port 8080 is already forwarded.","details":{"code":"PORT_IN_USE"}}""")
        assertEquals("Port 8080 is already forwarded.", e.message)
    }

    @Test
    fun `the envelope parser reads the message and details code, and nothing from non-JSON`() {
        val env = ApiErrorMapper.parseEnvelope("""{"statusCode":401,"message":"x","details":{"code":"GOOGLE_PLAY_PURCHASE_NOT_FOUND"}}""")
        assertEquals("x", env?.message)
        assertEquals("GOOGLE_PLAY_PURCHASE_NOT_FOUND", env?.code)
        assertNull(ApiErrorMapper.parseEnvelope("Internal error"))
        assertNull(ApiErrorMapper.parseEnvelope(null))
    }

    @Test
    fun `a 401 anywhere but sign-in is a dead session, and 426 and 429 have their canonical copy`() {
        assertEquals(text("error_session_expired"), general(401, """{"statusCode":401,"message":"Unauthorized"}""").message)
        assertEquals(FailureReason.UPDATE_REQUIRED, general(426, """{"statusCode":426,"error":"update_required","message":"x"}""").reason)
        assertEquals(text("error_update_required"), general(426, null).message)
        assertEquals(text("error_rate_limited"), general(429, null).message)
    }

    // ── Exceptions: by type, never by message ───────────────────────────

    @Test
    fun `transport failures are classified by type and never shown verbatim`() {
        val offline = mapper.fromException(UnknownHostException("api.birdo.app"))
        assertEquals(FailureReason.OFFLINE, offline.reason)
        assertEquals(text("error_offline"), offline.message)

        val resolver = mapper.fromException(UnknownHostException("Unable to resolve host \"api.birdo.app\": No address"))
        assertEquals(text("error_offline"), resolver.message)

        for (e in listOf(SocketTimeoutException("timeout"), InterruptedIOException("timeout"), ConnectException("refused"), IOException("reset"))) {
            val mapped = mapper.fromException(e)
            assertEquals(e.toString(), FailureReason.UNREACHABLE, mapped.reason)
            assertEquals(text("error_unreachable"), mapped.message)
            assertPresentable(mapped)
        }

        for (e in listOf(SSLPeerUnverifiedException("Certificate pinning failure!"), SSLHandshakeException("Chain validation failed"))) {
            val mapped = mapper.fromException(e)
            assertEquals(FailureReason.SECURE_CONNECTION, mapped.reason)
            assertEquals(text("error_secure_connection"), mapped.message)
        }

        assertEquals(FailureReason.UNEXPECTED, mapper.fromException(IllegalStateException("boom")).reason)
    }

    // ── Sign-in: 401 is not one situation ───────────────────────────────

    private val lockedMessage get() = text("error_account_locked")

    @Test
    fun `a lockout says locked, in every wording the backend uses, never wrong password`() {
        val bodies = listOf(
            401 to """{"message":"Too many failed login attempts","error":"Unauthorized","statusCode":401}""",
            401 to """{"message":"Account locked due to multiple failed login attempts","statusCode":401}""",
            403 to """{"message":"Account locked. Try again in 12 minutes.","statusCode":403}""",
            403 to """{"message":"Account is locked","error":"Forbidden","statusCode":403}""",
        )
        for ((status, body) in bodies) {
            val e = signIn(status, body)
            assertEquals(body, FailureReason.ACCOUNT_LOCKED, e.reason)
            assertEquals(body, lockedMessage, e.message)
            assertPresentable(e)
        }
    }

    @Test
    fun `a ban or suspension points at support, not the password`() {
        val e = signIn(401, """{"message":"Unable to sign in. Please contact support.","error":"Unauthorized","statusCode":401}""")
        assertEquals(FailureReason.ACCOUNT_BLOCKED, e.reason)
        assertEquals("Unable to sign in. Please contact support.", e.message)
    }

    @Test
    fun `a wrong password says so, and an unrecognised 401 falls back to it last`() {
        val wrong = signIn(401, """{"message":"Invalid credentials","error":"Unauthorized","statusCode":401}""")
        assertEquals(FailureReason.INVALID_CREDENTIALS, wrong.reason)
        assertEquals("Invalid email or password", wrong.message)
        assertEquals("Invalid email or password", signIn(401, "Error 401 unauthorized").message)
    }

    @Test
    fun `an anonymous account is told about its account number, not an email`() {
        val e = mapper.fromResponse(
            401, """{"message":"Invalid credentials","statusCode":401}""", ErrorContext.ANONYMOUS_SIGN_IN, R.string.error_sign_in_failed,
        )
        assertEquals(text("error_invalid_account_number"), e.message)
        assertFalse("email" in e.message.lowercase())
    }

    /** "Too many login attempts, please try later" is the IP bucket, not the account lockout. */
    @Test
    fun `the IP rate limit is not reported as an account lockout`() {
        val e = signIn(429, """{"message":"Too many login attempts, please try later","statusCode":429}""")
        assertEquals(FailureReason.RATE_LIMITED, e.reason)
        assertEquals("Too many attempts. Please wait a moment.", e.message)
    }

    @Test
    fun `a 5xx during sign-in never renders as Login failed plus the body`() {
        val e = signIn(500, """{"statusCode":500,"message":"Internal server error"}""")
        assertEquals(text("error_server_unavailable"), e.message)
        assertFalse(e.message.startsWith("Login failed"))
    }

    // ── Two-factor (A2-008) ─────────────────────────────────────────────

    private fun twoFactor(status: Int, body: String?) =
        mapper.fromResponse(status, body, ErrorContext.TWO_FACTOR, R.string.error_two_factor_failed)

    @Test
    fun `two-factor tells a wrong code from a dead challenge, and both from a rate limit`() {
        val wrong = twoFactor(401, """{"message":"Invalid verification code","statusCode":401}""")
        assertEquals(FailureReason.INVALID_CODE, wrong.reason)
        assertEquals(text("error_invalid_code"), wrong.message)

        val expired = twoFactor(401, """{"message":"Invalid or expired verification session","statusCode":401}""")
        assertEquals(FailureReason.CHALLENGE_EXPIRED, expired.reason)
        assertEquals(text("error_challenge_expired"), expired.message)

        val malformed = twoFactor(400, """{"message":["Invalid challenge token"],"statusCode":400}""")
        assertEquals(FailureReason.CHALLENGE_EXPIRED, malformed.reason)

        val limited = twoFactor(429, null)
        assertEquals(FailureReason.RATE_LIMITED, limited.reason)

        // Offline is offline, not "Invalid verification code" (the old copy for everything).
        assertEquals(FailureReason.OFFLINE, mapper.fromException(UnknownHostException("api.birdo.app")).reason)
    }

    // ── Anonymous registration: two limits behind one 429 ───────────────

    private fun register(status: Int, body: String?) =
        mapper.fromResponse(status, body, ErrorContext.ANONYMOUS_REGISTER, R.string.error_anon_register_failed)

    @Test
    fun `the DEVICE cap names the device and says networks will not help`() {
        val e = register(429, """{"message":"Too many accounts created from this device, please try later","statusCode":429}""").message
        assertTrue(e, "device" in e.lowercase())
        assertTrue(e, "24 hours" in e)
        assertTrue(e, "will not help" in e.lowercase())
        assertFalse(e, "this network" in e.lowercase())
        assertFalse(e, "moment" in e.lowercase())
        // Android has no guest mode: never offer "keep using the app without an account".
        assertFalse(e, "without an account" in e.lowercase())
    }

    @Test
    fun `the NETWORK cap names the network and the hour`() {
        val e = register(429, """{"message":"Too many accounts created from this network, please try later","statusCode":429}""").message
        assertTrue(e, "network" in e.lowercase())
        assertTrue(e, "hour" in e.lowercase())
        assertFalse(e, "moment" in e.lowercase())
    }

    // ── Deletion ────────────────────────────────────────────────────────

    @Test
    fun `deletion tells a wrong password from a dead session`() {
        val wrong = mapper.fromResponse(
            401, """{"message":"Incorrect password","statusCode":401}""", ErrorContext.DELETE_ACCOUNT, R.string.error_delete_failed,
        )
        assertEquals(FailureReason.INVALID_CREDENTIALS, wrong.reason)
        assertEquals("Incorrect password", wrong.message)

        val session = mapper.fromResponse(
            401, """{"message":"Unauthorized","statusCode":401}""", ErrorContext.DELETE_ACCOUNT, R.string.error_delete_failed,
        )
        assertEquals(FailureReason.SESSION_EXPIRED, session.reason)
    }

    private fun delete(status: Int, body: String) =
        mapper.fromResponse(status, body, ErrorContext.DELETE_ACCOUNT, R.string.error_delete_failed)

    /** ACCOUNT-API-2026-10-01, item 85: classified by `error`, never by the message. */
    @Test
    fun `deletion asks for the 2FA code, says a wrong one is wrong, and keeps the rate limit`() {
        val required = delete(
            403,
            """{"statusCode":403,"error":"two_factor_required","message":"Enter your two-factor code to delete your account."}""",
        )
        assertEquals(FailureReason.TWO_FACTOR_REQUIRED, required.reason)
        assertEquals(text("delete_dialog_2fa_required"), required.message)

        // Whatever the message says: the field decides.
        val wrong = delete(403, """{"statusCode":403,"error":"two_factor_invalid","message":"Incorrect password or code"}""")
        assertEquals(FailureReason.INVALID_CODE, wrong.reason)
        assertEquals(text("error_invalid_code"), wrong.message)

        val limited = delete(429, """{"statusCode":429,"message":"Too many requests"}""")
        assertEquals(FailureReason.RATE_LIMITED, limited.reason)
        assertEquals(text("error_rate_limited"), limited.message)

        // Today's server, and an account without 2FA: unchanged.
        val forbidden = delete(403, """{"statusCode":403,"error":"Forbidden","message":"Incorrect password"}""")
        assertEquals(FailureReason.INVALID_CREDENTIALS, forbidden.reason)
    }

    @Test
    fun `the envelope parser reads the error field`() {
        assertEquals(
            "two_factor_required",
            ApiErrorMapper.parseEnvelope("""{"statusCode":403,"error":"two_factor_required","message":"x"}""")?.error,
        )
        assertNull(ApiErrorMapper.parseEnvelope("""{"statusCode":500,"message":"x"}""")?.error)
    }

    // ── A refused connect inside a 200 (A2-030) ─────────────────────────

    @Test
    fun `a full device cap gets the canonical sentence, other refusals keep their words`() {
        val (reason, message) = mapper.connectRefusal("Device limit reached (5 devices for OPERATIVE plan)")
        assertEquals(FailureReason.DEVICE_LIMIT, reason)
        assertEquals(text("error_device_limit"), message)
        assertFalse("the plan SLUG must not reach prose", "OPERATIVE" in message)

        assertEquals("Server de-fra-1 is at capacity", mapper.connectRefusal("Server de-fra-1 is at capacity").second)
        assertEquals(text("error_connect_failed"), mapper.connectRefusal(null).second)
        assertEquals(text("error_connect_failed"), mapper.connectRefusal("""{"statusCode":500}""").second)
    }

    @Test
    fun `presentable rejects bodies, reason phrases and stack traces`() {
        assertTrue(ApiErrorMapper.isPresentable("Port 8080 is already forwarded."))
        assertFalse(ApiErrorMapper.isPresentable("""{"statusCode":500}"""))
        assertFalse(ApiErrorMapper.isPresentable("Bad Request"))
        assertFalse(ApiErrorMapper.isPresentable("java.lang.IllegalStateException: x"))
        assertFalse(ApiErrorMapper.isPresentable("x".repeat(201)))
    }
}
