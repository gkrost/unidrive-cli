package org.krost.unidrive.http

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-207: lock the canonical HTTP retry-policy matrix as a contract on
 * `HttpRetryBudget`. Each test name reads as a sentence asserting one row of
 * the matrix in `HttpRetryBudget`'s class KDoc.
 *
 * Scope: `HttpRetryBudget` is the *coordination* layer (token bucket, circuit
 * breaker, IOException classifier). The per-status (4xx/5xx/408/429) decision
 * lives in each provider's `withRetry` / `authenticatedRequest` loop. Tests
 * here exercise only what the budget itself decides; the per-status rows
 * (4xx, 408, 5xx, the unknown-exception cap) are not the budget's job and
 * belong in the provider tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpRetryBudgetMatrixTest {
    private class FakeClock(
        var now: Long = 0L,
    ) : () -> Long {
        override fun invoke(): Long = now
    }

    // -- Row: Throttle (429) — storm threshold + largest-Retry-After gating ---

    // UD-811 audit: renamed from `429 honors Retry-After capped by maxRetryAfter`.
    // The original name promised a `maxRetryAfter` cap that does not exist in
    // HttpRetryBudget — only in the KDoc matrix's prescriptive cell. The body
    // never asserted any cap. The actual invariants the body pins are
    // (a) a single 429 below the storm threshold does not open the circuit,
    // and (b) once the storm trips, the largest Retry-After in the window
    // gates resumeAfterEpochMs. Renamed accordingly; if/when a maxRetryAfter
    // cap is actually implemented in HttpRetryBudget, a new test should pin
    // that contract separately.
    @Test
    fun `429 storm opens circuit honoring largest Retry-After observed`() =
        runTest {
            val clock = FakeClock(now = 1_000)
            val budget =
                HttpRetryBudget(
                    maxConcurrency = 8,
                    stormThreshold = 4,
                    clock = clock,
                )
            // Single 429 below the storm threshold — circuit stays closed.
            budget.recordThrottle(retryAfterMs = 5_000)
            assertEquals(0L, budget.resumeAfterEpochMs(), "single 429 must not open the circuit")
            assertEquals(8, budget.currentConcurrency(), "single 429 must not shrink concurrency")
            // Trip the storm — the cumulative max(retryAfter) gates the resumeAt.
            repeat(3) {
                budget.recordThrottle(retryAfterMs = 5_000)
                clock.now += 100
            }
            assertTrue(
                budget.resumeAfterEpochMs() >= clock.now + 5_000,
                "circuit resumeAt must honor the largest Retry-After observed in the storm window",
            )
        }

    // -- Row: Network error (no status) — retriability classification --------

    // UD-811 audit: renamed from `network IOException retries with exponential
    // backoff`. The previous name promised backoff verification, but the body
    // exercises only `HttpRetryBudget.isRetriableIoException`'s classifier —
    // it asserts which IOException subclasses are transient (retry-worthy)
    // versus misconfig (fail fast). The actual exponential-backoff behaviour
    // is tested at the provider layer (GraphApiService
    // uploadChunkWithRetries), not here. Renamed to match
    // the body's actual scope.
    @Test
    fun `isRetriableIoException distinguishes transient TCP failures from misconfig`() {
        // The budget classifies which IOExceptions are worth retrying via
        // isRetriableIoException on its companion. Transient TCP failures must be
        // retriable; misconfig classes (DNS, SSL) must NOT be.
        assertTrue(
            HttpRetryBudget.isRetriableIoException(java.net.SocketTimeoutException("read timed out")),
            "SocketTimeoutException is a transient TCP failure — must retry",
        )
        assertTrue(
            HttpRetryBudget.isRetriableIoException(java.net.SocketException("Connection reset")),
            "SocketException 'Connection reset' is transient — must retry",
        )
        assertTrue(
            HttpRetryBudget.isRetriableIoException(java.net.SocketException("Broken pipe")),
            "SocketException 'Broken pipe' is transient — must retry",
        )
        assertTrue(
            HttpRetryBudget.isRetriableIoException(javax.net.ssl.SSLException("premature end of stream")),
            "premature EOF mid-body in the SSL family is transient — must retry",
        )
        // Misconfig classes — explicit non-retriable.
        assertFalse(
            HttpRetryBudget.isRetriableIoException(java.net.UnknownHostException("nope.invalid")),
            "UnknownHostException is DNS misconfig — must NOT retry",
        )
        assertFalse(
            HttpRetryBudget.isRetriableIoException(javax.net.ssl.SSLHandshakeException("bad cert")),
            "SSLHandshakeException is protocol/cert misconfig — must NOT retry",
        )
        assertFalse(
            HttpRetryBudget.isRetriableIoException(javax.net.ssl.SSLPeerUnverifiedException("hostname mismatch")),
                "SSLPeerUnverifiedException is cert mismatch — must NOT retry",
        )
    }

    // Captive-portal pages, expired-link login screens and local OS messages carry the same words as
    // transient TCP failures ("Session aborted", "Reset your password"); message matching is reserved
    // for the socket and SSL families, where the localised OS text is the failure itself.
    @Test
    fun `isRetriableIoException does not classify server or local text by message substring`() {
        assertFalse(
            HttpRetryBudget.isRetriableIoException(java.io.IOException("Reset your password")),
            "server HTML text must not read as a transient TCP failure",
        )
        assertFalse(
            HttpRetryBudget.isRetriableIoException(java.io.IOException("Session aborted")),
            "server HTML text must not read as a transient TCP failure",
        )
        assertTrue(
            HttpRetryBudget.isRetriableIoException(javax.net.ssl.SSLException("Connection aborted")),
            "the SSL family keeps the localised-message match",
        )
    }

    @Test
    fun `the HTML-instead-of-content failure is terminal, not retriable`() {
        // The snippet in the message can carry server HTML whose text matches the transient words.
        assertFalse(
            HttpRetryBudget.isRetriableIoException(
                HtmlInsteadOfContentException(
                    "Download returned HTML instead of file bytes (status=200, Content-Type=text/html): Reset your password",
                ),
            ),
            "an HTML page in place of the file bytes is a permanent condition for this URL",
        )
        assertFalse(
            HttpRetryBudget.isRetriableIoException(
                HtmlInsteadOfContentException("Download returned HTML instead of file bytes (status=200, Content-Type=text/html)"),
            ),
            "an HTML page in place of the file bytes is a permanent condition for this URL",
        )
    }

    // -- Row: Clock — the budget's default clock is monotonic, so a wall-clock step (sleep resume, time sync)
    // cannot stretch or shrink the circuit and spacing waits. A wall-clock default puts every deadline near
    // epoch time; a monotonic one keeps them in System.nanoTime's domain, which this test can tell apart. --
    @Test
    fun `the default clock is monotonic, not the wall clock`() {
        val budget = HttpRetryBudget(maxConcurrency = 1, stormThreshold = 1, stormBackoffFactor = 1.0)
        val before = System.nanoTime() / 1_000_000
        budget.recordThrottle(retryAfterMs = 1_000)
        val resumeAt = budget.resumeAfterEpochMs()
        val after = System.nanoTime() / 1_000_000
        assertTrue(
            resumeAt >= before + 1_000,
            "the circuit deadline must sit one Retry-After past the budget clock's own now",
        )
        assertTrue(
            resumeAt <= after + 1_000,
            "a wall-clock default would put the deadline near epoch time, far past the monotonic now",
        )
    }

    // -- Row: Clock — System.nanoTime() may be negative (the JDK fixes no origin), so the budget must not read
    // 0 or a positive value as "circuit opened". The clock here follows the test scheduler's virtual time from a
    // negative origin, so a wait the budget should not take shows up as virtual time that passed. --

    @Test
    fun `with a negative clock the first awaitSlot does not wait`() =
        runTest {
            val origin = -10_000L
            val budget = HttpRetryBudget(maxConcurrency = 4, clock = { origin + testScheduler.currentTime })
            budget.awaitSlot()
            assertEquals(0L, testScheduler.currentTime, "nothing throttled, so the first request start must not wait")
            assertEquals(0L, budget.resumeAfterEpochMs(), "an unopened circuit still reports 0")
            assertEquals(0L, budget.currentSpacingMs(), "no throttle yet: the fast path has no spacing")
        }

    @Test
    fun `with a negative clock a storm opens the circuit until now plus the backoff`() =
        runTest {
            val origin = -10_000L
            val budget =
                HttpRetryBudget(
                    maxConcurrency = 4,
                    stormThreshold = 4,
                    stormBackoffFactor = 1.2,
                    stormSpacingMs = 500L,
                    clock = { origin + testScheduler.currentTime },
                )
            repeat(4) { budget.recordThrottle(retryAfterMs = 5_000) }
            assertEquals(origin + 6_000L, budget.resumeAfterEpochMs(), "the deadline is now + backoff, even below 0")
            assertEquals(500L, budget.currentSpacingMs(), "an open circuit spaces starts at stormSpacingMs")
            budget.awaitSlot()
            assertEquals(6_000L, testScheduler.currentTime, "awaitSlot waits out the open circuit, no more")
        }
}
