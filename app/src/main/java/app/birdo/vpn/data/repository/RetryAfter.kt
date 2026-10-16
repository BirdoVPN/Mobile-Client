package app.birdo.vpn.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * When the server says to come back: a retryable refusal such as birdo-web's
 * 503 `{error: "quota_check_unavailable" | "psk_delivery_unavailable",
 * details: {retryable: true, retryAfterSeconds: 30}}` (15 s for
 * `tunnel_address_unavailable`), or a standard
 * `Retry-After` header. Clamped, so a wrong value can neither hammer the API
 * nor park a session for an hour.
 */
internal object RetryAfter {

    const val MIN_MS = 1_000L
    const val MAX_MS = 10 * 60_000L

    /**
     * The wait after a [ConnectErrorCode.retryable] refusal that named none
     * the client could use. The contract REQUIRES details.retryAfterSeconds on
     * every retryable code and the server always sends one (30 for
     * PskOverSshRefusedError and QuotaCheckUnavailableError, 15 for
     * TunnelAddressUnavailableError .RETRY_AFTER_SECONDS), so this only covers
     * a body that lost it on the way: re-asking at the supervisor's 2 s
     * backoff would hammer a server that has already said "not yet".
     */
    const val RETRYABLE_DEFAULT_MS = 30_000L

    /**
     * How long to wait after a failed call: the body's
     * `details.retryAfterSeconds`, else a delta-seconds `Retry-After` header,
     * else [RETRYABLE_DEFAULT_MS] when [code] is one the contract marks
     * retryable, else null (the caller's own backoff decides).
     */
    fun forFailure(rawBody: String?, retryAfterHeader: String?, code: ConnectErrorCode): Long? =
        fromBody(rawBody) ?: fromHeader(retryAfterHeader) ?: RETRYABLE_DEFAULT_MS.takeIf { code.retryable }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** `details.retryAfterSeconds` (or a top-level `retryAfterSeconds`) from an error body. */
    fun fromBody(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        val details = root["details"] as? JsonObject
        val seconds = (details?.get("retryAfterSeconds") as? JsonPrimitive)?.longOrNull
            ?: (root["retryAfterSeconds"] as? JsonPrimitive)?.longOrNull
            ?: return null
        return clamp(seconds)
    }

    /** A delta-seconds `Retry-After` header. An HTTP date is ignored: the device clock is not the server's. */
    fun fromHeader(value: String?): Long? = value?.trim()?.toLongOrNull()?.let(::clamp)

    // Capped in seconds BEFORE the multiply: a huge value used to overflow
    // into a negative and come out as MIN_MS, the opposite of what it asked.
    private fun clamp(seconds: Long): Long? =
        if (seconds <= 0) null else (seconds.coerceAtMost(MAX_MS / 1000) * 1000).coerceIn(MIN_MS, MAX_MS)
}
