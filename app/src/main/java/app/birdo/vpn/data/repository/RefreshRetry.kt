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
 *  - 5xx, 403, an unexpected status (426 included), a 2xx without a pair, or
 *    no answer at all (a timeout, a pin failure): capped exponential backoff
 *    with jitter ([backoffMs]), never shorter than a `Retry-After` the server
 *    sent. A session-store outage answers 503 with the token intact. Once the
 *    server half of MR-2500 lands (not on birdo-web main yet; it follows web
 *    #929), a rotation that fails after consuming the token will restore it
 *    and answer 503 too, so the same token renews later. Until then that
 *    failure is a 401 from the server.
 *
 * One timing table with Desktop #467 (`refresh_policy.rs`, `error.rs`
 * `RETRY_AFTER_MAX_SECS`; lead decision L2, 2026-11-19): the backoff starts at
 * 1 s, doubles, is capped at 30 s and jitters in the step's upper half; a wait
 * the server names is clamped to 1 s .. 60 s, and a named 0 is 1 s.
 */
internal object RefreshRetry {

    /** The wait after a 429 that named none this client can use. */
    const val RATE_LIMITED_DEFAULT_MS = 60_000L

    /** The first backoff's ceiling; each failure in a row doubles it. Desktop `BACKOFF_BASE`. */
    const val BACKOFF_BASE_MS = 1_000L

    /** The backoff never grows past this, jitter included. Desktop `BACKOFF_CAP`. */
    const val BACKOFF_CAP_MS = 30_000L

    /** The shortest wait the server may name for a refresh: a named 0 is this. */
    const val SERVER_WAIT_MIN_MS = 1_000L

    /** The longest wait the server may name for a refresh. Desktop `RETRY_AFTER_MAX_SECS`. */
    const val SERVER_WAIT_MAX_MS = 60_000L

    /** 1 s doubled 5 times is past the cap; more doublings only risk an overflow. */
    private const val MAX_DOUBLINGS = 16

    /** The wait after a 429: the header, else `details.retryAfter`, else [RATE_LIMITED_DEFAULT_MS]. */
    fun rateLimitedWaitMs(retryAfterHeader: String?, rawBody: String?): Long =
        headerWaitMs(retryAfterHeader)
            ?: serverWaitMs(RetryAfter.refusalDetailsSeconds(rawBody))
            ?: RATE_LIMITED_DEFAULT_MS

    /** A refresh answer's delta-seconds `Retry-After` header as a wait ([serverWaitMs]). */
    fun headerWaitMs(retryAfterHeader: String?): Long? = serverWaitMs(RetryAfter.headerSeconds(retryAfterHeader))

    /**
     * A wait the server named for a refresh, in [seconds]: clamped to
     * [SERVER_WAIT_MIN_MS] .. [SERVER_WAIT_MAX_MS], so a 0 is 1 s (as on
     * Desktop) and a day is a minute. Null when there is none to use: absent,
     * negative, or not a whole number (junk, an HTTP date), which the caller
     * replaces with its own wait. Clamped in seconds before the multiply, so a
     * huge value cannot overflow.
     */
    fun serverWaitMs(seconds: Long?): Long? {
        if (seconds == null || seconds < 0) return null
        return seconds.coerceIn(SERVER_WAIT_MIN_MS / 1000, SERVER_WAIT_MAX_MS / 1000) * 1000
    }

    /**
     * The wait after the [failures]th refresh in a row that got no verdict:
     * a ceiling of [BACKOFF_BASE_MS] doubled per failure, capped at
     * [BACKOFF_CAP_MS], and a random point in the ceiling's upper half
     * ([random] in [0, 1]): Desktop's "equal jitter". The jitter keeps
     * clients that failed together from coming back together; the lower
     * bound keeps any wait from being zero.
     */
    fun backoffMs(failures: Int, random: Double): Long {
        val doublings = (failures.coerceAtLeast(1) - 1).coerceAtMost(MAX_DOUBLINGS)
        val ceiling = (BACKOFF_BASE_MS shl doublings).coerceAtMost(BACKOFF_CAP_MS)
        val half = ceiling / 2
        return half + (half * random.coerceIn(0.0, 1.0)).toLong()
    }

    /** A jitter for [backoffMs], uniform in [0, 1) as on Desktop. */
    fun jitter(): Double = Random.nextDouble()
}
