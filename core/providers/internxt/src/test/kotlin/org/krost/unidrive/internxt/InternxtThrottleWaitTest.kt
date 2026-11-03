package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.krost.unidrive.internxt.model.InternxtFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared throttle-wait rule: a server hint of `Retry-After: 0` still waits the one-second floor, on the write
 * ladder ([InternxtApiService.retryOnTransient]) and on the enumeration GET ladder alike. Both used to clamp the hint
 * into half a second and resent below the floor.
 */
class InternxtThrottleWaitTest {
    private fun service(engine: MockEngine) =
        InternxtApiService(
            InternxtConfig(credentialProtector = null, ),
            credentialsProvider = { _ ->
                InternxtCredentials(
                    jwt = "test-jwt",
                    mnemonic = "test-mnemonic",
                    rootFolderId = "test-root",
                    email = "test@example.invalid",
                )
            },
            driveBudget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0),
            httpClient = HttpClient(engine),
        )

    @Test
    fun `a write whose Retry-After is zero still waits the one-second floor`() =
        runTest {
            val service = service(MockEngine { respond("ok") })
            var attempts = 0

            val started = testScheduler.currentTime
            service.retryOnTransient {
                attempts++
                if (attempts == 1) throw InternxtApiException("throttled", statusCode = 429, retryAfterMs = 0L, responseReceived = true)
                "done"
            }

            val waited = testScheduler.currentTime - started
            assertTrue(waited in 1_000..2_500, "waited $waited ms")
            assertEquals(2, attempts)
            service.close()
        }

    @Test
    fun `an enumeration 429 whose Retry-After is zero waits the one-second floor`() =
        runTest {
            var calls = 0
            val service =
                service(
                    MockEngine {
                        calls++
                        if (calls == 1) {
                            respond(content = "slow down", status = HttpStatusCode.TooManyRequests, headers = headersOf("Retry-After" to listOf("0")))
                        } else {
                            respond(content = "[]", status = HttpStatusCode.OK, headers = headersOf("Content-Type", "application/json"))
                        }
                    },
                )

            val started = testScheduler.currentTime
            assertEquals(emptyList<InternxtFile>(), service.listFiles())

            val waited = testScheduler.currentTime - started
            assertTrue(waited in 1_000..2_500, "waited $waited ms")
            assertEquals(2, calls, "one throttled attempt, one answered")
            service.close()
        }
}
