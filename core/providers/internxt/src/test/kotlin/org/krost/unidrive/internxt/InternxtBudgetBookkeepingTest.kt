package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.internxt.model.InternxtCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterizes what each Internxt call site tells its [HttpRetryBudget] (#222 item 5): one slot per attempt before the
 * request, a throttle for a 429/503 answer (with its Retry-After), a success for a 2xx, and neither for anything else.
 *
 * The budget is observed through its state, with a clock that follows the test's virtual time: a storm threshold of 1
 * halves the concurrency at every recorded throttle (so `64 shr n` counts n throttles), the circuit's resume time shows
 * the Retry-After that was handed over, a success after the quiet interval gives one permit back, and a call made while
 * the circuit is open completes only after it closes (the slot was awaited).
 */
class InternxtBudgetBookkeepingTest {
    private class Site(
        val name: String,
        val budget: Which,
        val call: suspend InternxtApiService.() -> Unit,
    )

    private enum class Which { DRIVE, BRIDGE }

    private val sites =
        listOf(
            Site("createFile", Which.DRIVE) { createFile("b", "f", "p", "e", 1L, "txt") },
            Site("replaceFile", Which.DRIVE) { replaceFile("u", 1L, "id") },
            Site("createFoldersBatch", Which.DRIVE) { createFoldersBatch("p", listOf("a" to "ea")) },
            Site("createFolder", Which.DRIVE) { createFolder("p", "n", "en") },
            Site("renameFile", Which.DRIVE) { renameFile("u", "n", "txt") },
            Site("renameFolder", Which.DRIVE) { renameFolder("u", "n") },
            Site("moveFile", Which.DRIVE) { moveFile("u", "d") },
            Site("moveFolder", Which.DRIVE) { moveFolder("u", "d") },
            Site("deleteFile", Which.DRIVE) { deleteFile("u") },
            Site("trashItems", Which.DRIVE) { trashItems(listOf("u" to "file")) },
            Site("deleteFolder", Which.DRIVE) { deleteFolder("u") },
            Site("bridgeGet", Which.BRIDGE) { getBridgeFileInfo("b", "f") },
            Site("bridgePost", Which.BRIDGE) { startUpload("b", 1L) },
        )

    private class Observed(
        val concurrency: Int,
        val resumeAfterMs: Long,
        val requests: Int,
    )

    private class Rig(
        val service: InternxtApiService,
        val drive: HttpRetryBudget,
        val bridge: HttpRetryBudget,
        var status: HttpStatusCode,
        var retryAfter: String?,
        var requests: Int,
    )

    private fun TestScope.rig(): Rig {
        fun budget() =
            HttpRetryBudget(
                maxConcurrency = 64,
                stormThreshold = 1,
                minSpacingMs = 0,
                stormSpacingMs = 0,
                recoveryCleanIntervalMs = 1_000,
                clock = { currentTime },
            )
        val drive = budget()
        val bridge = budget()
        lateinit var rig: Rig
        val engine =
            MockEngine {
                rig.requests++
                val headers =
                    if (rig.retryAfter != null) {
                        headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf(rig.retryAfter!!))
                    } else {
                        headersOf(HttpHeaders.ContentType, "application/json")
                    }
                respond(content = "{}", status = rig.status, headers = headers)
            }
        val service =
            InternxtApiService(
                InternxtConfig(credentialProtector = null),
                credentialsProvider = { _ ->
                    InternxtCredentials(jwt = "jwt", mnemonic = "m", rootFolderId = "root", email = "e@example.invalid", bridgeUser = "bu", bridgeUserId = "bi")
                },
                driveBudget = drive,
                bridgeBudget = bridge,
                httpClient = HttpClient(engine),
            )
        rig = Rig(service, drive, bridge, HttpStatusCode.OK, null, 0)
        return rig
    }

    private suspend fun Rig.run(
        site: Site,
        status: HttpStatusCode,
        retryAfter: String? = null,
    ) {
        this.status = status
        this.retryAfter = retryAfter
        // Whatever the call ends in (an API error, or a body that does not parse), the budget was told first.
        runCatching { site.call(service) }
    }

    private fun Rig.budgetOf(site: Site) = if (site.budget == Which.DRIVE) drive else bridge

    private fun Rig.observe(site: Site) = Observed(budgetOf(site).currentConcurrency(), budgetOf(site).resumeAfterEpochMs(), requests)

    @Test
    fun `a 429 or 503 is recorded as a throttle for every attempt, with its Retry-After`() =
        runTest {
            val attempts = mutableMapOf<String, Int>()
            for (site in sites) {
                for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
                    val rig = rig()
                    rig.run(site, status, retryAfter = "2")
                    val seen = rig.observe(site)
                    val throttles = Integer.numberOfTrailingZeros(64 / seen.concurrency)
                    assertTrue(throttles >= 1, "${site.name} $status: no throttle recorded")
                    assertEquals(seen.requests, throttles, "${site.name} $status: one throttle per attempt")
                    attempts.merge(site.name, throttles) { a, b -> if (a == b) a else -1 }
                    assertEquals(2_400L, seen.resumeAfterMs - currentTime, "${site.name} $status: Retry-After 2 s opens the circuit for 2 s x 1.2")
                }
            }
            for (site in sites) {
                val expected = if (site.budget == Which.DRIVE) 3 else 1
                assertEquals(expected, attempts[site.name], "${site.name}: attempts (the drive calls retry three times, the bridge calls once)")
            }
        }

    @Test
    fun `without a Retry-After the throttle is recorded with no hint`() =
        runTest {
            for (site in sites) {
                val rig = rig()
                rig.run(site, HttpStatusCode.TooManyRequests)
                val seen = rig.observe(site)
                assertTrue(seen.concurrency < 64, "${site.name}: recorded")
                // hint 0 -> a storm backoff of max(0, minSpacing 0) * 1.2 = 0: the circuit is closed again at once.
                assertEquals(0L, seen.resumeAfterMs - currentTime, "${site.name}: no Retry-After, no circuit pause")
            }
        }

    @Test
    fun `other statuses record no throttle`() =
        runTest {
            for (site in sites) {
                for (status in listOf(HttpStatusCode.BadRequest, HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.Forbidden)) {
                    val rig = rig()
                    rig.run(site, status, retryAfter = "2")
                    val seen = rig.observe(site)
                    assertEquals(64, seen.concurrency, "${site.name} $status: not a throttle")
                    assertEquals(0L, seen.resumeAfterMs, "${site.name} $status: the circuit stays closed")
                }
            }
        }

    @Test
    fun `a 2xx is recorded as a success and any other answer is not`() =
        runTest {
            for (site in sites) {
                val rig = rig()
                rig.run(site, HttpStatusCode.TooManyRequests, retryAfter = "1")
                val storm = rig.observe(site).concurrency
                delay(30_000) // the circuit closes, the quiet interval has passed

                rig.run(site, HttpStatusCode.BadRequest)
                assertEquals(storm, rig.observe(site).concurrency, "${site.name}: a refusal gives no permit back")

                rig.run(site, HttpStatusCode.OK)
                assertEquals(storm + 1, rig.observe(site).concurrency, "${site.name}: a success gives one permit back, even when the body does not parse")
            }
        }

    @Test
    fun `a slot is awaited before the request, so a call made while the circuit is open waits for it`() =
        runTest {
            for (site in sites) {
                val rig = rig()
                rig.run(site, HttpStatusCode.TooManyRequests, retryAfter = "20")
                val resumeAt = rig.observe(site).resumeAfterMs
                assertTrue(resumeAt > currentTime, "${site.name}: precondition, the circuit is open")

                rig.run(site, HttpStatusCode.OK)

                assertTrue(currentTime >= resumeAt, "${site.name}: ran at $currentTime, before the circuit closed at $resumeAt")
            }
        }

    @Test
    fun `the drive and the bridge budgets are kept apart`() =
        runTest {
            for (site in sites) {
                val rig = rig()
                rig.run(site, HttpStatusCode.TooManyRequests, retryAfter = "2")
                val other = if (site.budget == Which.DRIVE) rig.bridge else rig.drive
                assertEquals(64, other.currentConcurrency(), "${site.name}: only its own budget hears about it")
            }
        }
}
