package org.krost.unidrive.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetryAfterTest {
    // 2026-10-21 07:28:00 GMT, a Wednesday.
    private val now = 1_792_567_680_000L
    private val dateAhead5s = "Wed, 21 Oct 2026 07:28:05 GMT"

    @Test
    fun `delta-seconds become milliseconds, surrounding blanks ignored`() {
        assertEquals(7_000L, parseRetryAfterHeader("7", now))
        assertEquals(7_000L, parseRetryAfterHeader(" 7 ", now))
        assertEquals(0L, parseRetryAfterHeader("0", now))
    }

    @Test
    fun `an HTTP date gives the wait until that date`() {
        assertEquals(5_000L, parseRetryAfterHeader(dateAhead5s, now))
        assertEquals(5_000L, parseRetryAfterHeader("  $dateAhead5s ", now))
    }

    @Test
    fun `a date that has passed leaves lower precedence retry hints available`() {
        assertNull(parseRetryAfterHeader("Wed, 21 Oct 2026 07:27:00 GMT", now))
        assertNull(parseRetryAfterHeader("Wed, 21 Oct 2026 07:28:00 GMT", now))
    }

    // The response's own Date header is the baseline the server used when it set the Retry-After
    // date, so a local clock minutes off does not turn the hint into null or a wrong wait.
    @Test
    fun `a date hint is measured from the response's Date header, not the local clock`() {
        val dateHeader = "Wed, 21 Oct 2026 07:28:00 GMT"
        val retryAfter = "Wed, 21 Oct 2026 07:29:00 GMT"
        // The local clock here runs five minutes fast; pre-fix, target - nowMs was negative and the hint was lost.
        assertEquals(60_000L, parseRetryAfterHeader(retryAfter, nowMs = now + 300_000, responseDateHeader = dateHeader))
    }

    @Test
    fun `a date hint survives a local clock minutes fast, measured from the response Date`() {
        assertEquals(5_000L, parseRetryAfterHeader(dateAhead5s, nowMs = now + 300_000, responseDateHeader = "Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun `a date hint already past relative to the response Date gives null`() {
        assertNull(
            parseRetryAfterHeader("Wed, 21 Oct 2026 07:27:00 GMT", nowMs = now, responseDateHeader = "Wed, 21 Oct 2026 07:28:00 GMT"),
        )
    }

    @Test
    fun `an absent or unparseable Date header falls back to the local clock`() {
        assertEquals(5_000L, parseRetryAfterHeader(dateAhead5s, nowMs = now, responseDateHeader = null))
        assertEquals(5_000L, parseRetryAfterHeader(dateAhead5s, nowMs = now, responseDateHeader = "not a date"))
    }

    @Test
    fun `the Date-relative wait is still clamped`() {
        assertEquals(
            MAX_RETRY_AFTER_MS,
            parseRetryAfterHeader("Fri, 21 Oct 2101 07:28:00 GMT", nowMs = now, responseDateHeader = dateAhead5s),
        )
    }

    @Test
    fun `a wait far in the future is clamped`() {
        assertEquals(MAX_RETRY_AFTER_MS, parseRetryAfterHeader("Fri, 21 Oct 2101 07:28:00 GMT", now))
        assertEquals(MAX_RETRY_AFTER_MS, parseRetryAfterHeader("86400", now))
        assertEquals(MAX_RETRY_AFTER_MS, parseRetryAfterHeader("99999999999999999999999", now))
    }

    @Test
    fun `negative, garbage, empty and absent give null`() {
        assertNull(parseRetryAfterHeader("-1", now))
        assertNull(parseRetryAfterHeader("soon", now))
        assertNull(parseRetryAfterHeader("1.5", now))
        assertNull(parseRetryAfterHeader("Thu, 21 Oct 2026 07:28:00 GMT", now)) // 21 Oct 2026 is no Thursday
        assertNull(parseRetryAfterHeader("", now))
        assertNull(parseRetryAfterHeader("   ", now))
        assertNull(parseRetryAfterHeader(null, now))
    }

    @Test
    fun `a throttle wait takes the hint, else the fallback, and keeps both between the floor and the cap`() {
        assertEquals(7_000L, throttleWaitMs(7_000L, 2_000L, 1_000L, 300_000L))
        assertEquals(2_000L, throttleWaitMs(null, 2_000L, 1_000L, 300_000L))
        assertEquals(1_000L, throttleWaitMs(0L, 2_000L, 1_000L, 300_000L))
        assertEquals(300_000L, throttleWaitMs(MAX_RETRY_AFTER_MS, 2_000L, 1_000L, 300_000L))
        assertEquals(300_000L, throttleWaitMs(null, 600_000L, 1_000L, 300_000L))
    }
}
