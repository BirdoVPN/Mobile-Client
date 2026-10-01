package app.birdo.vpn.data.repository

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import app.birdo.vpn.R
import app.birdo.vpn.billing.StoreLinkRefusal
import app.birdo.vpn.utils.InputValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Why an API call failed, decided ONCE, where the HTTP status, the error body or
 * the exception was actually seen.
 *
 * Callers branch on this, never on the wording of [ApiResult.Error.message]:
 * the message is copy and may change, the reason is the contract. It exists
 * because every caller used to re-derive the cause by substring-matching raw
 * bodies and `Throwable.message`, which is how "Login failed: {"statusCode":500,…}"
 * and "Unable to resolve host "api.birdo.app"" reached the screen.
 */
enum class FailureReason {
    /** No usable network at all (DNS could not resolve the API host). */
    OFFLINE,
    /** The API could not be reached, or stopped answering mid-call. */
    UNREACHABLE,
    /** TLS or certificate-pin failure: a captive portal, a proxy, or an outdated pin set. */
    SECURE_CONNECTION,
    /** The refresh token is dead; the user has to sign in again. */
    SESSION_EXPIRED,
    RATE_LIMITED,
    /** HTTP 426: this build is below the support floor. */
    UPDATE_REQUIRED,
    /** The plan's device cap is reached. */
    DEVICE_LIMIT,
    /** 5xx, or a refresh that could not reach a verdict. The session is intact. */
    SERVER_UNAVAILABLE,
    /** Sign-in: the credentials were wrong. Delete: the confirmation password was wrong. */
    INVALID_CREDENTIALS,
    ACCOUNT_LOCKED,
    /** Banned or suspended. The server deliberately says one uniform sentence for both. */
    ACCOUNT_BLOCKED,
    /** 2FA: the code was rejected. */
    INVALID_CODE,
    /** 2FA: the challenge from the password step expired or was already used. */
    CHALLENGE_EXPIRED,
    /** A refusal the server explained in its own words (see [ApiErrorMapper]). */
    REFUSED,
    /** A response we could not make sense of. */
    UNEXPECTED,
}

/**
 * Which call a failure came from. The same status means different things on
 * different routes: a 401 from `/auth/login` is a wrong password, a 401 from
 * `/gdpr/delete` with "Incorrect password" is a wrong confirmation, and a 401
 * anywhere else is a dead session.
 */
enum class ErrorContext {
    GENERAL,
    SIGN_IN,
    ANONYMOUS_SIGN_IN,
    ANONYMOUS_REGISTER,
    TWO_FACTOR,
    DELETE_ACCOUNT,
}

/**
 * Resolves the app's copy from strings.xml for code that runs outside Compose:
 * [ApiErrorMapper], the Play rail, the view models. The seam keeps them plain
 * JVM classes under unit test. The app binds it to the application's resources
 * (NetworkModule); JVM tests bind it to the shipped strings.xml (`StringsXml`),
 * so an assertion about wording is an assertion about what ships.
 */
interface StringLookup {
    /** `Context.getString(id, *args)`; with no [args] the text is returned as written. */
    fun get(@StringRes id: Int, vararg args: Any): String

    /** `Resources.getQuantityString(id, count, *args)`. */
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
}

/**
 * The parts of the backend's error envelope a client may use.
 *
 * GlobalExceptionFilter emits `{statusCode, message, error?, details?}`, where
 * `message` is a string for a thrown HttpException and an ARRAY for a
 * validation failure. The iOS client decodes the same envelope
 * (APIClient.swift, APIErrorBody); Android used to hand the raw body to the UI.
 */
internal data class ErrorEnvelope(val message: String?, val code: String?)

/**
 * THE one place an API failure becomes words on the screen.
 *
 * Every string it returns comes from strings.xml, is a whole sentence, and is
 * safe to show: no JSON, no exception text, no hostnames. The server's own
 * sentence is kept only where the canonical copy says so (a refusal it
 * explained, such as a port that is already forwarded or a node at capacity),
 * and only when it reads as a sentence rather than a reason phrase or a body.
 */
class ApiErrorMapper(private val strings: StringLookup) {

    /** A non-2xx response. [rawBody] is the error body, read once by the caller. */
    fun fromResponse(
        status: Int,
        rawBody: String?,
        context: ErrorContext,
        @StringRes fallback: Int,
    ): ApiResult.Error {
        val server = parseEnvelope(rawBody)?.message?.takeIf(::isPresentable)
        val (reason, message) = when (context) {
            ErrorContext.SIGN_IN, ErrorContext.ANONYMOUS_SIGN_IN ->
                signIn(status, server, anonymous = context == ErrorContext.ANONYMOUS_SIGN_IN)
            ErrorContext.TWO_FACTOR -> twoFactor(status, server)
            ErrorContext.ANONYMOUS_REGISTER -> anonymousRegister(status, server)
            ErrorContext.DELETE_ACCOUNT -> deleteAccount(status, server)
            ErrorContext.GENERAL -> general(status, server, fallback)
        }
        return ApiResult.Error(message, status, reason)
    }

    /**
     * A call that threw. Classified by exception TYPE, never by its message:
     * the message is the resolver's or the TLS stack's text, which is what
     * users used to be shown verbatim.
     */
    fun fromException(e: Throwable): ApiResult.Error {
        val reason = when (e) {
            // DoH throws UnknownHostException carrying only the bare hostname,
            // the system resolver "Unable to resolve host …": both mean no DNS,
            // which on a phone almost always means no network.
            is UnknownHostException -> FailureReason.OFFLINE
            is SocketTimeoutException -> FailureReason.UNREACHABLE
            // OkHttp's callTimeout surfaces as a bare InterruptedIOException("timeout").
            is InterruptedIOException -> FailureReason.UNREACHABLE
            is ConnectException, is NoRouteToHostException, is SocketException -> FailureReason.UNREACHABLE
            // Pin mismatch (SSLPeerUnverifiedException) and handshake failures.
            is SSLException, is CertificateException -> FailureReason.SECURE_CONNECTION
            is IOException -> FailureReason.UNREACHABLE
            else -> FailureReason.UNEXPECTED
        }
        return ApiResult.Error(textFor(reason), 0, reason)
    }

    /** The refresh token was rejected: the caller treats this as signed out. */
    fun sessionExpired(): ApiResult.Error =
        ApiResult.Error(textFor(FailureReason.SESSION_EXPIRED), 401, FailureReason.SESSION_EXPIRED)

    /**
     * The refresh could not reach a verdict (5xx, timeout, or a sign-out landed
     * mid-flight). NOT a 401, so nothing downstream signs the user out over it.
     */
    fun temporarilyUnavailable(): ApiResult.Error =
        ApiResult.Error(textFor(FailureReason.SERVER_UNAVAILABLE), 503, FailureReason.SERVER_UNAVAILABLE)

    /** A 2xx body that could not be used (missing tokens, an empty body where one was required). */
    fun unexpected(): ApiResult.Error =
        ApiResult.Error(textFor(FailureReason.UNEXPECTED), 0, FailureReason.UNEXPECTED)

    /**
     * A connect the server REFUSED inside a 200 (`{success: false, message}`).
     *
     * That is how the backend reports a full device cap (vpn.service.ts:
     * "Device limit reached (5 devices for OPERATIVE plan)"), so the refusal
     * never passes through [fromResponse]. The cap gets the canonical sentence,
     * which says what to do about it; any other explained refusal ("at
     * capacity") keeps the server's words.
     */
    fun connectRefusal(serverMessage: String?): Pair<FailureReason, String> = when {
        serverMessage != null && serverMessage.trim().startsWith("Device limit reached", ignoreCase = true) ->
            FailureReason.DEVICE_LIMIT to textFor(FailureReason.DEVICE_LIMIT)
        serverMessage != null && isPresentable(serverMessage) ->
            FailureReason.REFUSED to serverMessage.trim()
        else -> FailureReason.UNEXPECTED to strings.get(R.string.error_connect_failed)
    }

    /**
     * A Play purchase the server did not link: its own sentence when it wrote a
     * showable one, otherwise the refusal's copy from strings.xml.
     */
    fun storeRefusal(kind: StoreLinkRefusal, serverMessage: String?): String =
        InputValidator.sanitizeErrorMessage(serverMessage, strings.get(kind.fallbackMessageRes))

    // ── Per-context rules ──────────────────────────────────────────────────

    private fun general(status: Int, server: String?, @StringRes fallback: Int): Pair<FailureReason, String> =
        when {
            status == 401 -> FailureReason.SESSION_EXPIRED.withText()
            status == 426 -> FailureReason.UPDATE_REQUIRED.withText()
            status == 429 -> FailureReason.RATE_LIMITED.withText()
            status >= 500 -> FailureReason.SERVER_UNAVAILABLE.withText()
            server != null -> FailureReason.REFUSED to server
            else -> FailureReason.REFUSED to strings.get(fallback)
        }

    /**
     * The backend answers 401 for three different situations and only its
     * sentence tells them apart: a wrong password ("Invalid credentials"), a
     * lockout ("Too many failed login attempts" / "Account locked …" /
     * "Account is locked") and a ban or suspension ("Unable to sign in. Please
     * contact support."). Reporting all three as a wrong password told a
     * locked-out user to keep retyping, so the specific sentences are matched
     * first and the bare 401 only as the last resort. "failed" matters: the
     * 429 body "Too many login attempts, please try later" is the IP rate
     * limit, a different condition with a different remedy.
     */
    private fun signIn(status: Int, server: String?, anonymous: Boolean): Pair<FailureReason, String> {
        val lower = server?.lowercase().orEmpty()
        return when {
            "unable to sign in" in lower -> FailureReason.ACCOUNT_BLOCKED.withText()
            "account locked" in lower || "account is locked" in lower ||
                "too many failed login attempts" in lower -> FailureReason.ACCOUNT_LOCKED.withText()
            status == 429 -> FailureReason.RATE_LIMITED.withText()
            status == 426 -> FailureReason.UPDATE_REQUIRED.withText()
            status >= 500 -> FailureReason.SERVER_UNAVAILABLE.withText()
            "invalid credentials" in lower || status == 401 -> FailureReason.INVALID_CREDENTIALS to strings.get(
                if (anonymous) R.string.error_invalid_account_number else R.string.error_invalid_credentials,
            )
            server != null -> FailureReason.REFUSED to server
            else -> FailureReason.REFUSED to strings.get(R.string.error_sign_in_failed)
        }
    }

    /**
     * Both a wrong code and a dead challenge are 401s (two-factor.controller.ts:
     * "Invalid verification code" vs "Invalid or expired verification
     * session"); a malformed challenge is a 400 from the validation pipe. Only
     * the wrong code is the user's to fix by retyping.
     */
    private fun twoFactor(status: Int, server: String?): Pair<FailureReason, String> {
        val lower = server?.lowercase().orEmpty()
        return when {
            status == 429 -> FailureReason.RATE_LIMITED.withText()
            status >= 500 -> FailureReason.SERVER_UNAVAILABLE.withText()
            "expired" in lower || "verification session" in lower || "challenge" in lower ->
                FailureReason.CHALLENGE_EXPIRED.withText()
            status == 401 || status == 400 -> FailureReason.INVALID_CODE.withText()
            server != null -> FailureReason.REFUSED to server
            else -> FailureReason.REFUSED to strings.get(R.string.error_two_factor_failed)
        }
    }

    /**
     * `POST /auth/register/anonymous` has TWO rate limits behind one 429 and
     * the body says which fired: 3 per network per hour ("from this network")
     * and 5 per device per 24 h ("from this device"). The remedies differ by a
     * day, so they are never collapsed into "please wait a moment". iOS makes
     * the same split (GuestAccess.swift).
     */
    private fun anonymousRegister(status: Int, server: String?): Pair<FailureReason, String> {
        val lower = server?.lowercase().orEmpty()
        return when {
            "from this device" in lower ->
                FailureReason.RATE_LIMITED to strings.get(R.string.error_anon_register_device_limit)
            "from this network" in lower || status == 429 ->
                FailureReason.RATE_LIMITED to strings.get(R.string.error_anon_register_network_limit)
            status >= 500 -> FailureReason.SERVER_UNAVAILABLE.withText()
            server != null -> FailureReason.REFUSED to server
            else -> FailureReason.REFUSED to strings.get(R.string.error_anon_register_failed)
        }
    }

    /** gdpr.controller.ts answers a wrong confirmation password with 401 "Incorrect password". */
    private fun deleteAccount(status: Int, server: String?): Pair<FailureReason, String> {
        val lower = server?.lowercase().orEmpty()
        return when {
            (status == 401 || status == 403) && "password" in lower ->
                FailureReason.INVALID_CREDENTIALS to strings.get(R.string.error_incorrect_password)
            status == 401 -> FailureReason.SESSION_EXPIRED.withText()
            status == 429 -> FailureReason.RATE_LIMITED.withText()
            status >= 500 -> FailureReason.SERVER_UNAVAILABLE.withText()
            server != null -> FailureReason.REFUSED to server
            else -> FailureReason.REFUSED to strings.get(R.string.error_delete_failed)
        }
    }

    // ── Copy ───────────────────────────────────────────────────────────────

    private fun FailureReason.withText(): Pair<FailureReason, String> = this to textFor(this)

    /** The canonical sentence for a reason that has one (P1-parity "Key errors"). */
    private fun textFor(reason: FailureReason): String = strings.get(
        when (reason) {
            FailureReason.OFFLINE -> R.string.error_offline
            FailureReason.UNREACHABLE -> R.string.error_unreachable
            FailureReason.SECURE_CONNECTION -> R.string.error_secure_connection
            FailureReason.SESSION_EXPIRED -> R.string.error_session_expired
            FailureReason.RATE_LIMITED -> R.string.error_rate_limited
            FailureReason.UPDATE_REQUIRED -> R.string.error_update_required
            FailureReason.DEVICE_LIMIT -> R.string.error_device_limit
            FailureReason.SERVER_UNAVAILABLE -> R.string.error_server_unavailable
            FailureReason.INVALID_CREDENTIALS -> R.string.error_invalid_credentials
            FailureReason.ACCOUNT_LOCKED -> R.string.error_account_locked
            FailureReason.ACCOUNT_BLOCKED -> R.string.error_account_blocked
            FailureReason.INVALID_CODE -> R.string.error_invalid_code
            FailureReason.CHALLENGE_EXPIRED -> R.string.error_challenge_expired
            FailureReason.REFUSED, FailureReason.UNEXPECTED -> R.string.error_unexpected
        },
    )

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Nest's default messages are the HTTP reason phrase ("Bad Request",
         * "Unauthorized"). They name the status, not the problem, so they are
         * never shown; the caller's own fallback says more.
         */
        private val reasonPhrases = setOf(
            "bad request", "unauthorized", "forbidden", "not found", "conflict", "gone",
            "unprocessable entity", "too many requests", "internal server error",
            "service unavailable", "bad gateway", "gateway timeout", "http exception",
            "validation failed",
        )

        /**
         * Reads `{message, details.code}` from a Nest error body. `message` may be
         * a string or, for validation failures, an array of strings (joined).
         * Anything that is not a JSON object yields null — an HTML 502 page from
         * the edge is not an envelope, and nothing in it is shown.
         */
        fun parseEnvelope(raw: String?): ErrorEnvelope? {
            if (raw.isNullOrBlank()) return null
            val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
            val message = when (val m = root["message"]) {
                is JsonPrimitive -> m.contentOrNull
                is JsonArray -> m.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .takeIf { it.isNotEmpty() }?.joinToString("; ")
                else -> null
            }
            val code = ((root["details"] as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
            return ErrorEnvelope(message, code)
        }

        /** Whether a server sentence can be shown as-is. */
        fun isPresentable(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty() || t.length > 200) return false
            if (t.startsWith("{") || t.startsWith("[") || '<' in t) return false
            if ("Exception" in t || "\tat " in t || "stackTrace" in t) return false
            return t.lowercase().trimEnd('.') !in reasonPhrases
        }
    }
}
