package org.krost.unidrive.http

import java.time.DateTimeException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Longest wait [parseRetryAfterHeader] returns; callers still apply their own, lower caps. */
const val MAX_RETRY_AFTER_MS: Long = 3_600_000L

private val DELTA_SECONDS = Regex("""\d+""")

/**
 * The wait in milliseconds a `Retry-After` header asks for (RFC 9110 section 10.2.3), or null when the
 * header is absent, empty, negative or not understood. Both forms are read: delta-seconds, and an HTTP date
 * in the IMF-fixdate form (`Wed, 21 Oct 2026 07:28:00 GMT`). A date that has already passed gives null, and a
 * wait longer than [MAX_RETRY_AFTER_MS] is clamped to it.
 *
 * The date form is measured against [responseDateHeader], the response's own `Date` header, when one is
 * present and parseable: that is the baseline the server used when it set the date, so a local clock minutes
 * off does not turn the hint into null or a wrong wait. Only a response without a usable `Date` falls back
 * to [nowMs].
 */
fun parseRetryAfterHeader(
    header: String?,
    nowMs: Long = System.currentTimeMillis(),
    responseDateHeader: String? = null,
): Long? {
    val raw = header?.trim()?.takeUnless { it.isEmpty() } ?: return null
    if (DELTA_SECONDS.matches(raw)) {
        val seconds = raw.toLongOrNull() ?: return MAX_RETRY_AFTER_MS
        return minOf(seconds, MAX_RETRY_AFTER_MS / 1000) * 1000
    }
    val target = parseHttpDateMs(raw) ?: return null
    val baseMs = parseHttpDateMs(responseDateHeader) ?: nowMs
    return (target - baseMs).takeIf { it > 0L }?.coerceAtMost(MAX_RETRY_AFTER_MS)
}

private fun parseHttpDateMs(raw: String?): Long? =
    raw?.trim()?.takeUnless { it.isEmpty() }?.let {
        try {
            ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }

/**
 * The wait before a throttled call is sent again (#220): the server's [hintMs] (a `Retry-After` header or a hint in
 * the body) when there is one, else the caller's own back-off [fallbackMs]; either way at least [minMs], so a
 * `Retry-After: 0` is not resent at once, and at most [maxMs], so an hour's hint does not hold a transfer for an hour.
 * The value a caller waits is the value it records on its budget.
 */
fun throttleWaitMs(
    hintMs: Long?,
    fallbackMs: Long,
    minMs: Long,
    maxMs: Long,
): Long = (hintMs ?: fallbackMs).coerceIn(minMs, maxMs)
