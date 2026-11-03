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
 * non-idempotent write — a trash whose items are already trashed, a create that makes a second file —
 * must not blind-fire. The calls whose repeat is reconciled or writes the same bytes (the two deletes,
 * the two PUT overwrites) keep the full set.
 */
class InternxtWriteLadderRetryTest {
    private fun service(engine: MockEngine): InternxtApiService =
        InternxtApiService(
            InternxtConfig(credentialProtector = null),
            { _ ->
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

    // A 502 whose origin committed the trash, then the answer a repeat would get (400: the items are
    // already in the bin). The 502 must reach the caller after one request — the caller reconciles;
    // a retried trash would surface a failed delete for items that are in fact trashed.
    @Test
    fun `trashItems does not repeat a write after a 502`() =
        runTest {
            val requests = java.util.concurrent.atomic.AtomicInteger(0)
            val engine =
                MockEngine { request ->
                    when (requests.incrementAndGet()) {
                        1 -> respond("", HttpStatusCode.BadGateway, headersOf("Content-Type", "text/plain"))
                        else -> respond(
                            """{"statusCode":400,"message":"at least one uuid was invalid","error":"Bad Request"}""",
                            HttpStatusCode.BadRequest,
                            headersOf("Content-Type", "application/json"),
                        )
                    }
                }
            val api = service(engine)
            try {
                val e =
                    assertFailsWith<InternxtApiException> {
                        api.trashItems(listOf("file-uuid-1" to "file"))
                    }
                assertEquals(502, e.statusCode, "the 502 reaches the caller, which reconciles")
                assertEquals(1, requests.get(), "the trash POST was sent exactly once")
            } finally {
                api.close()
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
