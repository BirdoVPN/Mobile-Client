package app.birdo.vpn.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * When the server says to come back: a retryable refusal such as birdo-web
 * PR #590's 503 `{error: "quota_check_unavailable", details: {retryable: true,
 * retryAfterSeconds: 30}}`, or a standard `Retry-After` header. Clamped, so a
 * wrong value can neither hammer the API nor park a session for an hour.
 */
internal object RetryAfter {

    const val MIN_MS = 1_000L
    const val MAX_MS = 10 * 60_000L

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

    private fun clamp(seconds: Long): Long? =
        if (seconds <= 0) null else (seconds * 1000).coerceIn(MIN_MS, MAX_MS)
}
