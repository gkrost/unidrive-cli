package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The write ladder's default (retryOnTransient without retryServerErrors) retries only 429/503: a
 * 500/502/504 can come from a gateway whose origin already committed the write, and repeating a
 * create that makes a second file must not blind-fire. The calls whose repeat is reconciled or writes
 * the same bytes keep the full set: the two permanent deletes (a repeat's 404 is already-gone), the two
 * PUT overwrites, and the trash (a 400 that follows a 5xx of the same call means the earlier attempt
 * committed).
 */
class InternxtWriteLadderRetryTest {
    private fun service(engine: MockEngine): InternxtApiService =
        InternxtApiService(
            InternxtConfig(credentialProtector = null),
            { _, _ ->
                org.krost.unidrive.internxt.model.InternxtCredentials(
                    jwt = "test-jwt",
                    mnemonic = "test-mnemonic",
                    rootFolderId = "test-root",
                    email = "test@example.invalid",
                    bridgeUser = "bridge-user",
                    bridgeUserId = "bridge-secret",
                    bucket = "test-bucket",
                )
            },
            httpClient = io.ktor.client.HttpClient(engine),
        )

    private suspend fun trashAgainst(vararg statuses: HttpStatusCode): Pair<Result<Unit>, Int> {
        val requests = java.util.concurrent.atomic.AtomicInteger(0)
        val engine =
            MockEngine { request ->
                assertTrue(request.url.toString().endsWith("/storage/trash/add"), request.url.toString())
                val status = statuses[minOf(requests.getAndIncrement(), statuses.size - 1)]
                if (status == HttpStatusCode.OK) {
                    respond("{}", status, headersOf("Content-Type", "application/json"))
                } else {
                    respond(
                        """{"statusCode":${status.value},"message":"x"}""",
                        status,
                        headersOf("Content-Type", "application/json"),
                    )
                }
            }
        val api = service(engine)
        try {
            val result = runCatching { api.trashItems(listOf("file-uuid-1" to "file")) }
            return result to requests.get()
        } finally {
            api.close()
        }
    }

    // The routine delete has no reconcile behind it, so a trash retries a 500/502/504 like the deletes do.
    @Test
    fun `trashItems retries a 502 and succeeds on the second attempt`() =
        runTest {
            val (result, requests) = trashAgainst(HttpStatusCode.BadGateway, HttpStatusCode.OK)
            assertTrue(result.isSuccess, "the retry answered 200: $result")
            assertEquals(2, requests, "the trash POST was repeated once")
        }

    // A 502 whose origin committed the trash, then the answer a repeat gets (400: the items are already in the bin).
    // That 400 follows a 5xx of the same call, so it means "an earlier attempt committed": the trash succeeded.
    @Test
    fun `trashItems treats a 400 after a 502 of the same call as already trashed`() =
        runTest {
            val (result, requests) = trashAgainst(HttpStatusCode.BadGateway, HttpStatusCode.BadRequest)
            assertTrue(result.isSuccess, "a 400 after a 5xx is the committed first attempt: $result")
            assertEquals(2, requests)
        }

    @Test
    fun `trashItems treats a 400 after a 500 and after a 504 as already trashed`() =
        runTest {
            for (first in listOf(HttpStatusCode.InternalServerError, HttpStatusCode.GatewayTimeout)) {
                val (result, requests) = trashAgainst(first, HttpStatusCode.BadRequest)
                assertTrue(result.isSuccess, "$first then 400: $result")
                assertEquals(2, requests)
            }
        }

    // A 400 on the first attempt is an invalid uuid, not a committed repeat: it still surfaces, unretried.
    @Test
    fun `trashItems still throws a 400 on the first attempt`() =
        runTest {
            val (result, requests) = trashAgainst(HttpStatusCode.BadRequest)
            val e = result.exceptionOrNull() as? InternxtApiException
            assertEquals(400, e?.statusCode, "the first-attempt 400 reaches the caller: $result")
            assertEquals(1, requests)
        }

    // A 503 or 429 is a refused answer that cannot have committed: retried as before, and a 400 after it is not
    // the committed case.
    @Test
    fun `trashItems retries a 503 and a 429 and a 400 after them still throws`() =
        runTest {
            for (first in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests)) {
                val (ok, okRequests) = trashAgainst(first, HttpStatusCode.OK)
                assertTrue(ok.isSuccess, "$first then 200: $ok")
                assertEquals(2, okRequests)
                val (bad, badRequests) = trashAgainst(first, HttpStatusCode.BadRequest)
                assertEquals(400, (bad.exceptionOrNull() as? InternxtApiException)?.statusCode, "$first then 400: $bad")
                assertEquals(2, badRequests)
            }
        }

    // The PUT overwrite repeats its identical body in place — nothing can duplicate — so its 5xx are
    // still retried (retryServerErrors) and the second attempt answers the update.
    @Test
    fun `replaceFile still retries a 502 and answers the overwrite`() =
        runTest {
            val requests = java.util.concurrent.atomic.AtomicInteger(0)
            val engine =
                MockEngine { request ->
                    assertTrue(request.url.toString().endsWith("/drive/files/file-uuid-1"), request.url.toString())
                    assertEquals(io.ktor.http.HttpMethod.Put, request.method)
                    when (requests.incrementAndGet()) {
                        1 -> respond("", HttpStatusCode.BadGateway, headersOf("Content-Type", "text/plain"))
                        else -> respond(
                            """{"uuid":"file-uuid-1","size":"5"}""",
                            HttpStatusCode.OK,
                            headersOf("Content-Type", "application/json"),
                        )
                    }
                }
            val api = service(engine)
            try {
                val file = api.replaceFile("file-uuid-1", size = 5L, fileId = "bucket-entry-new")
                assertEquals("file-uuid-1", file.uuid)
                assertEquals(2, requests.get(), "the overwrite was retried once")
            } finally {
                api.close()
            }
        }

    // The delete reconciles its own repeat inside the call (a 404 is already-gone), so a 502 whose
    // origin committed the delete is retried and the repeat's 404 completes it as success.
    @Test
    fun `deleteFile still retries a 502 and accepts the repeat's already-gone answer`() =
        runTest {
            val requests = java.util.concurrent.atomic.AtomicInteger(0)
            val engine =
                MockEngine { request ->
                    assertTrue(request.url.toString().endsWith("/drive/files/file-uuid-1"), request.url.toString())
                    assertEquals(io.ktor.http.HttpMethod.Delete, request.method)
                    when (requests.incrementAndGet()) {
                        1 -> respond("", HttpStatusCode.BadGateway, headersOf("Content-Type", "text/plain"))
                        else -> respond("", HttpStatusCode.NotFound, headersOf("Content-Type", "text/plain"))
                    }
                }
            val api = service(engine)
            try {
                api.deleteFile("file-uuid-1")
                assertEquals(2, requests.get(), "the delete was retried once and answered already-gone")
            } finally {
                api.close()
            }
        }
}
