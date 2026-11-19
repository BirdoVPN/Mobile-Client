package app.birdo.vpn.data.repository

import kotlin.random.Random

/**
 * MR-1771 / MR-2500: how long no `/auth/refresh` is sent after an answer that
 * kept the session without rotating it. iOS twin: RefreshAnswer.swift
 * (`RefreshRetry`, `RefreshHoldOff`).
 *
 * birdo-web's rule (backend/contract/README.md, "Token refresh"): only a 401
 * ends the session. Every other answer keeps the stored tokens, and once the
 * wait is over the next refresh presents the SAME refresh token:
 *  - 429: every limiter on the route answers before the token is read, so it
 *    is unspent. Wait the `Retry-After` header, else the body's
 *    `details.retryAfter` (web #912), else [RATE_LIMITED_DEFAULT_MS].
 *  - 5xx, 403, an unexpected status, or no answer at all: capped exponential
 *    backoff with jitter ([backoffMs]), never shorter than a `Retry-After` the
 *    server sent. A session-store outage answers 503 with the token intact,
 *    and a rotation that fails after consuming the token is to restore it and
 *    answer 503 (MR-2500, the server half), so the same token renews later.
 */
internal object RefreshRetry {

    /** The wait after a 429 that named none this client can use. */
    const val RATE_LIMITED_DEFAULT_MS = 60_000L

    /** The first backoff's ceiling; each failure in a row doubles it. */
    const val BACKOFF_BASE_MS = 2_000L

    /** The backoff never grows past this. */
    const val BACKOFF_CAP_MS = 5 * 60_000L

    /** 2 s doubled 8 times is past the cap; more doublings only risk an overflow. */
    private const val MAX_DOUBLINGS = 16

    /** The wait after a 429: the header, else `details.retryAfter`, else [RATE_LIMITED_DEFAULT_MS]. */
    fun rateLimitedWaitMs(retryAfterHeader: String?, rawBody: String?): Long =
        RetryAfter.fromHeader(retryAfterHeader)
            ?: RetryAfter.fromRefusalDetails(rawBody)
            ?: RATE_LIMITED_DEFAULT_MS

    /**
     * The wait after the [failures]th refresh in a row that got no verdict:
     * a ceiling of [BACKOFF_BASE_MS] doubled per failure, capped at
     * [BACKOFF_CAP_MS], and a random point in the ceiling's upper half
     * ([random] in [0, 1]). The jitter keeps clients that failed together from
     * coming back together; the lower bound keeps any wait from being zero.
     */
    fun backoffMs(failures: Int, random: Double): Long {
        val doublings = (failures.coerceAtLeast(1) - 1).coerceAtMost(MAX_DOUBLINGS)
        val ceiling = (BACKOFF_BASE_MS shl doublings).coerceAtMost(BACKOFF_CAP_MS)
        val half = ceiling / 2
        return half + (half * random.coerceIn(0.0, 1.0)).toLong()
    }

    /** A jitter for [backoffMs]. */
    fun jitter(): Double = Random.nextDouble()
}
