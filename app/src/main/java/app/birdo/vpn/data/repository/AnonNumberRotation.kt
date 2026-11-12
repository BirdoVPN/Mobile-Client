package app.birdo.vpn.data.repository

import app.birdo.vpn.shared.model.TokenPair
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response

/**
 * MR-1707, Android half: a replaceable anonymous account number, in two phases
 * (birdo-web #850, `POST /auth/anonymous/rotate-number/{prepare,confirm}`;
 * Desktop-Client #416 is the reference client). This file holds the wire types,
 * the refusal classifier and the 401 rule. Both numbers are credentials: no
 * class here prints one, and nothing here logs.
 */

/** Body of `POST /auth/anonymous/rotate-number/prepare`. */
@Serializable
data class RotatePrepareRequest(
    val currentNumber: String,
    val password: String? = null,
    val twoFactorCode: String? = null,
) {
    override fun toString(): String = "RotatePrepareRequest([REDACTED])"
}

/** `200` of prepare: the NEW number, shown once. */
@Serializable
data class RotatePrepareResponse(
    val ok: Boolean = false,
    val newNumber: String = "",
    val expiresInSeconds: Int = 0,
) {
    override fun toString(): String = "RotatePrepareResponse(ok=$ok, newNumber=[REDACTED], expiresInSeconds=$expiresInSeconds)"
}

/** Body of `POST /auth/anonymous/rotate-number/confirm`: the re-typed number plus device context. */
@Serializable
data class RotateConfirmRequest(
    val newNumber: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val deviceType: String? = null,
    val platform: String? = null,
    val platformVersion: String? = null,
    val appVersion: String? = null,
) {
    override fun toString(): String = "RotateConfirmRequest([REDACTED])"
}

/** `200` of confirm: the caller's fresh session (every other one has been ended). */
@Serializable
data class RotateConfirmResponse(
    val ok: Boolean = false,
    @SerialName("tokens") val tokens: TokenPair? = null,
)

/** What a refused rotation call means for the flow. Branch on this, never on wording. */
enum class RotationFailure {
    /** 403 `anon_number_rotation_disabled`: the server switch is off. */
    DISABLED,
    /** 401 the handler wrote (`rotation_proof_invalid` or any non-bare message): the proof was wrong. Never retried. */
    PROOF_INVALID,
    /** A bare 401 that survived a refresh and retry: the session itself is gone. */
    SESSION_EXPIRED,
    /** 409 `rotation_not_pending`: expired or never prepared. Start again. */
    NOT_PENDING,
    /** 409 `rotation_account_changed`: the account moved on since prepare. Start again. */
    ACCOUNT_CHANGED,
    /** 400 `rotation_number_mismatch`: the re-typed number is not the new one. Stay on step 3. */
    MISMATCH,
    /** 429: too many attempts (or rotations today). */
    TOO_MANY,
    /** Any other 4xx/5xx: shown, the flow stays where it is. */
    REJECTED,
    /** No answer (offline, timeout, a refresh that could not finish). The server state is unknown. */
    NETWORK,
}

internal const val ERR_ROTATION_DISABLED = "anon_number_rotation_disabled"
internal const val ERR_ROTATION_NOT_PENDING = "rotation_not_pending"
internal const val ERR_ROTATION_ACCOUNT_CHANGED = "rotation_account_changed"
internal const val ERR_ROTATION_MISMATCH = "rotation_number_mismatch"

/**
 * Whether a 401 body is the guard's bare "Unauthorized": no `error` code, no
 * `details.code`, and a message that is absent or only the reason phrase. Only
 * that kind is a session problem; a body that says anything else is the
 * handler's own verdict on the proof (the rotation handler throws
 * `rotation_proof_invalid`), and refreshing for it would spend a single-use
 * refresh token on a healthy session, which the server reads as theft.
 */
internal fun isBareUnauthorized(envelope: ErrorEnvelope?): Boolean {
    if (envelope == null) return true
    // Nest adds `error: "Unauthorized"` (the reason phrase) next to a custom message: not a code.
    val error = envelope.error?.trim()?.lowercase()
    if (!(error.isNullOrEmpty() || error == "unauthorized") || !envelope.code.isNullOrBlank()) return false
    val message = envelope.message?.trim()?.trimEnd('.')?.lowercase()
    return message.isNullOrEmpty() || message == "unauthorized"
}

/** Classify a non-2xx answer. A bare 401 is classified as [RotationFailure.SESSION_EXPIRED] (the caller decides about the retry first). */
internal fun classifyRotationFailure(status: Int, envelope: ErrorEnvelope?): RotationFailure = when {
    status == 403 && envelope?.error == ERR_ROTATION_DISABLED -> RotationFailure.DISABLED
    status == 401 -> if (isBareUnauthorized(envelope)) RotationFailure.SESSION_EXPIRED else RotationFailure.PROOF_INVALID
    status == 409 && envelope?.error == ERR_ROTATION_NOT_PENDING -> RotationFailure.NOT_PENDING
    status == 409 && envelope?.error == ERR_ROTATION_ACCOUNT_CHANGED -> RotationFailure.ACCOUNT_CHANGED
    status == 400 && envelope?.error == ERR_ROTATION_MISMATCH -> RotationFailure.MISMATCH
    status == 429 -> RotationFailure.TOO_MANY
    else -> RotationFailure.REJECTED
}

/** Outcome of one rotation call, as the repository hands it to the flow. */
sealed interface RotationResult<out T> {
    data class Ok<T>(val data: T) : RotationResult<T>
    data class Refused(val failure: RotationFailure, val retryAfterMs: Long? = null) : RotationResult<Nothing>
}

/**
 * Send [call]; ONLY a bare 401 refreshes the token once and retries once. A
 * 401 that carries the handler's own error is returned as it is, so a wrong
 * number cannot burn the refresh token. A transport failure is [RotationFailure.NETWORK].
 *
 * [refresh] is the repository's single-flight refresh; [presented] the access
 * token this request carried.
 */
internal suspend fun <T> sendRotationCall(
    presented: String?,
    refresh: suspend (String?) -> RefreshOutcome,
    call: suspend () -> Response<T>,
): RotationResult<T> {
    suspend fun once(): Response<T> = call()
    fun map(response: Response<T>): RotationResult<T> {
        if (response.isSuccessful) {
            return response.body()?.let { RotationResult.Ok(it) } ?: RotationResult.Refused(RotationFailure.NETWORK)
        }
        val raw = runCatching { response.errorBody()?.string() }.getOrNull()
        val envelope = ApiErrorMapper.parseEnvelope(raw)
        val failure = classifyRotationFailure(response.code(), envelope)
        val retryAfter = RetryAfter.forFailure(
            raw,
            runCatching { response.headers()["Retry-After"] }.getOrNull(),
            ConnectErrorCode.fromWire(envelope?.error),
        )
        return RotationResult.Refused(failure, retryAfter)
    }

    return try {
        val first = once()
        val firstMapped = map(first)
        if (firstMapped is RotationResult.Refused && firstMapped.failure == RotationFailure.SESSION_EXPIRED) {
            when (refresh(presented)) {
                RefreshOutcome.SUCCESS -> map(once())
                RefreshOutcome.UNAUTHORIZED -> RotationResult.Refused(RotationFailure.SESSION_EXPIRED)
                RefreshOutcome.TRANSIENT -> RotationResult.Refused(RotationFailure.NETWORK)
            }
        } else {
            firstMapped
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        RotationResult.Refused(RotationFailure.NETWORK)
    }
}
