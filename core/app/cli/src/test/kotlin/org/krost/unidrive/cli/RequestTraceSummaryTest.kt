package org.krost.unidrive.cli

import org.krost.unidrive.http.AttemptOutcome
import org.krost.unidrive.http.RequestAttempt
import org.krost.unidrive.http.RequestMetricsSink
import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestTraceSummaryTest {
    private fun attempt(
        method: String = "GET",
        outcome: AttemptOutcome = AttemptOutcome.OK,
        endpointClass: String = "GET /drive/items/{id}",
        retryNo: Int = 0,
        throttled: Boolean = false,
        bytesUp: Long? = null,
        bytesDown: Long? = null,
    ) = RequestAttempt(
        startedAtMs = 0L,
        provider = "fake",
        endpointClass = endpointClass,
        method = method,
        outcome = outcome,
        headersMs = 1L,
        totalMs = 2L,
        bytesUp = bytesUp,
        bytesDown = bytesDown,
        retryNo = retryNo,
        throttled = throttled,
    )

    // #578: the flag is opt-in — nothing is attached and nothing is printed without it.
    @Test
    fun `--trace-requests is off by default and on when passed`() {
        val off = CommandLine(Main()).parseArgs("sync").subcommand()?.commandSpec()?.userObject() as SyncCommand
        assertFalse(off.traceRequests, "--trace-requests must default to off")
        val on =
            CommandLine(Main()).parseArgs("sync", "--trace-requests").subcommand()?.commandSpec()?.userObject() as SyncCommand
        assertTrue(on.traceRequests, "--trace-requests must parse as a plain flag")
    }

    @Test
    fun `an empty run renders zero requests`() {
        val rendered = RequestTraceSummary().render()
        assertTrue(rendered.startsWith("requests: 0"), "empty run must report zero attempts; got: $rendered")
        assertTrue("retries: 0" in rendered && "throttled: 0" in rendered, "empty run reports zero counters; got: $rendered")
        assertFalse("2xx" in rendered, "an empty run must not stand in a zero for an outcome nobody made; got: $rendered")
    }

    // #578: the provider side of the seam, the way the real providers hold it (the tap's sink is read per request):
    // the run attaches the summary, and every finished attempt the provider's client hands over is counted by it.
    private class FakeProviderRequests {
        private var sink: RequestMetricsSink? = null

        fun attachRequestMetrics(sink: RequestMetricsSink?) {
            this.sink = sink
        }

        fun request(attempt: RequestAttempt) {
            sink?.record(attempt)
        }
    }

    @Test
    fun `the summary counts the attempts a fake provider makes`() {
        val provider = FakeProviderRequests()
        val summary = RequestTraceSummary()
        provider.attachRequestMetrics(summary)

        repeat(3) { provider.request(attempt(bytesUp = 100, bytesDown = 200)) }
        provider.request(attempt(outcome = AttemptOutcome.THROTTLED, throttled = true, bytesDown = null))
        provider.request(attempt(method = "POST", outcome = AttemptOutcome.SERVER_ERROR, retryNo = 1))
        provider.request(attempt(outcome = AttemptOutcome.RESET, retryNo = 2, bytesUp = 100))

        val rendered = summary.render()
        assertTrue(rendered.startsWith("requests: 6"), "every attempt counts once; got: $rendered")
        assertTrue("methods: GET 5, POST 1" in rendered, "attempts by method; got: $rendered")
        assertTrue("GET /drive/items/{id} 6" in rendered, "attempts by endpoint kind; got: $rendered")
        assertTrue("outcomes: 2xx 3, 429 1, 5xx 1, reset 1" in rendered, "attempts by outcome; got: $rendered")
        assertTrue("retries: 2, throttled: 1" in rendered, "retries are attempts with retry_no above 0; got: $rendered")
        assertTrue("bytes: up 400 (2 unknown), down 600 (3 unknown)" in rendered, "declared byte sums with unknown counts; got: $rendered")
    }

    // The tap is content-free and the summary must stay inside what an attempt already carries: no timestamp,
    // no provider id, no URL scheme — only counters, wires and the templated endpoint class.
    @Test
    fun `the summary is content-free`() {
        val provider = FakeProviderRequests()
        val summary = RequestTraceSummary()
        provider.attachRequestMetrics(summary)
        provider.request(attempt().copy(startedAtMs = 1_730_000_000_000))

        val rendered = summary.render()
        assertFalse("1730000000" in rendered, "no attempt timestamp may reach the summary; got: $rendered")
        assertFalse("http" in rendered, "no URL may reach the summary; got: $rendered")
        assertFalse("fake" in rendered, "no provider id may reach the summary; got: $rendered")
        assertTrue("GET /drive/items/{id} 1" in rendered, "the templated endpoint class is the only kind shown; got: $rendered")
    }
}
