package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * UD-806 (chunk: 401 → refresh attempt): pin the GraphApiService auth-refresh
 * contract. Three orthogonal invariants, one named test each:
 *
 * 1. **One-shot refresh.** A 401 response triggers exactly one retry, and
 *    the retry's bearer token is sourced from `tokenProvider(refreshed = true)`.
 *    The retry's response body is what the caller sees.
 * 2. **No infinite-loop on expired refresh.** When the refreshed call ALSO
 *    returns 401 (e.g. refresh-token revoked), the helper surfaces
 *    `AuthenticationException` — it does not retry a third time, does not
 *    crash, and does not return success-shaped garbage.
 * 3. **Success path is unchanged.** When the first response is success, the
 *    refresh path is never entered and `tokenProvider(refreshed = true)` is
 *    never called.
 *
 * The contract under test lives in `GraphApiService.authenticatedRequest`
 * (single-method form). Identical logic appears in the body-carrying overload
 * and in the chunked download path; pinning the single-method form here
 * documents the canonical shape — UD-806 follow-ups can iterate to other
 * call sites once this baseline is in place.
 */
class GraphAuthRefreshTest {
    @Test
    fun `401 then 200 surfaces refreshed token on retry and returns refreshed response`() =
        runTest {
            val tokenCalls = mutableListOf<Boolean>()
            val requestBearers = mutableListOf<String?>()
            val attempt = AtomicInteger(0)

            val engine =
                MockEngine { request ->
                    requestBearers += request.headers[HttpHeaders.Authorization]
                    if (attempt.getAndIncrement() == 0) {
                        respond(
                            content = "{\"error\":\"token expired\"}",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content =
                                """{"id":"drive-id","driveType":"personal","quota":{"total":0,"used":0,"remaining":0,"deleted":0}}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val service =
                GraphApiService(
                    config = OneDriveConfig(),
                    httpClient = HttpClient(engine),
                    tokenProvider = { refreshed, _ ->
                        tokenCalls += refreshed
                        if (refreshed) "refreshed-token" else "stale-token"
                    },
                )

            // getDrive() goes through authenticatedRequest; the success branch returns its parsed body.
            val drive = service.getDrive()

            assertEquals(2, attempt.get(), "expected exactly one retry after the 401")
            assertEquals(listOf(false, true), tokenCalls, "second token request must ask for a refreshed token")
            assertEquals(
                listOf<String?>("Bearer stale-token", "Bearer refreshed-token"),
                requestBearers,
                "second request must carry the refreshed bearer token",
            )
            assertEquals("drive-id", drive.id, "caller should see the post-refresh response body")
            service.close()
        }

    @Test
    fun `401 then another 401 surfaces AuthenticationException without a third attempt`() =
        runTest {
            val attempt = AtomicInteger(0)
            val tokenCalls = mutableListOf<Boolean>()

            val engine =
                MockEngine {
                    attempt.incrementAndGet()
                    respond(
                        content = "{\"error\":\"refresh token revoked\"}",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service =
                GraphApiService(
                    config = OneDriveConfig(),
                    httpClient = HttpClient(engine),
                    tokenProvider = { refreshed, _ ->
                        tokenCalls += refreshed
                        if (refreshed) "still-bad" else "bad"
                    },
                )

            val ex =
                assertFailsWith<AuthenticationException> {
                    service.getDrive()
                }
            assertEquals(2, attempt.get(), "401-after-refresh must NOT trigger a third attempt")
            assertEquals(listOf(false, true), tokenCalls, "both attempts must run, the second with refresh=true")
            assertTrue(
                ex.message?.contains("401") == true,
                "AuthenticationException message must name the 401; got: ${ex.message}",
            )
            service.close()
        }

    @Test
    fun `200 on the first attempt never invokes tokenProvider with refresh=true`() =
        runTest {
            val tokenCalls = mutableListOf<Boolean>()

            val engine =
                MockEngine {
                    respond(
                        content =
                            """{"id":"drive-id","driveType":"personal","quota":{"total":0,"used":0,"remaining":0,"deleted":0}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }

            val service =
                GraphApiService(
                    config = OneDriveConfig(),
                    httpClient = HttpClient(engine),
                    tokenProvider = { refreshed, _ ->
                        tokenCalls += refreshed
                        "ok-token"
                    },
                )

            service.getDrive()

            assertEquals(listOf(false), tokenCalls, "success path must not request a refreshed token")
            service.close()
        }

    /**
     * The replay names the token the refused request carried (#215): the token manager can then take a
     * stored token that already differs from it as fresh (another caller's refresh answered the 401)
     * instead of rotating the token a second time.
     */
    @Test
    fun `the 401 replay names the token the refused request carried`() =
        runTest {
            val attempt = AtomicInteger(0)
            val rejectedSeen = mutableListOf<String?>()

            val engine =
                MockEngine {
                    if (attempt.getAndIncrement() == 0) {
                        respond(
                            content = "{\"error\":\"token expired\"}",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond(
                            content = "{\"id\":\"drive-id\",\"driveType\":\"personal\",\"quota\":{\"total\":0,\"used\":0,\"remaining\":0,\"deleted\":0}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }

            val service =
                GraphApiService(
                    config = OneDriveConfig(),
                    httpClient = HttpClient(engine),
                    tokenProvider = { refreshed, rejected ->
                        if (refreshed) rejectedSeen += rejected
                        if (refreshed) "refreshed-token" else "stale-token"
                    },
                )

            service.getDrive()

            assertEquals(listOf<String?>("stale-token"), rejectedSeen, "the replay names the token the refused request carried")
            service.close()
        }

    /**
     * Only the replay of a 401 is a forced refresh naming a rejected token. `401(A) -> 429(B) -> 200`: B was
     * throttled, not refused, so the third request asks for no refresh, names nothing as rejected, and carries B.
     */
    @Test
    fun `a throttle after the 401 replay does not force a second refresh`() =
        runTest {
            val attempt = AtomicInteger(0)
            val asks = mutableListOf<Pair<Boolean, String?>>()
            val requestBearers = mutableListOf<String?>()

            val engine =
                MockEngine { request ->
                    requestBearers += request.headers[HttpHeaders.Authorization]
                    when (attempt.getAndIncrement()) {
                        0 ->
                            respond(
                                content = "{\"error\":\"token expired\"}",
                                status = HttpStatusCode.Unauthorized,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        1 ->
                            respond(
                                content = "{\"error\":\"throttled\"}",
                                status = HttpStatusCode.TooManyRequests,
                                headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("1")),
                            )
                        else ->
                            respond(
                                content = "{\"id\":\"drive-id\",\"driveType\":\"personal\",\"quota\":{\"total\":0,\"used\":0,\"remaining\":0,\"deleted\":0}}",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                    }
                }

            var stored = "A"
            val service =
                GraphApiService(
                    config = OneDriveConfig(),
                    httpClient = HttpClient(engine),
                    tokenProvider = { force, rejected ->
                        asks += force to rejected
                        if (force) stored = "B"
                        stored
                    },
                )

            service.getDrive()

            assertEquals(3, attempt.get())
            assertEquals(listOf<Pair<Boolean, String?>>(false to null, true to "A", false to null), asks, "one forced refresh, naming A")
            assertEquals(listOf<String?>("Bearer A", "Bearer B", "Bearer B"), requestBearers, "both later requests carry B")
            service.close()
        }

    /** The same `401(A) -> 429(B) -> 200` sequence on the download loop, whose item has no pre-authenticated URL. */
    @Test
    fun `a download throttled after the 401 replay does not force a second refresh`() =
        runTest {
            val contentGets = AtomicInteger(0)
            val asks = mutableListOf<Pair<Boolean, String?>>()
            val contentBearers = mutableListOf<String?>()

            val engine =
                MockEngine { request ->
                    if (!request.url.toString().endsWith("/content")) {
                        respond(
                            content = "{\"id\":\"item-9\",\"name\":\"f.bin\",\"size\":4,\"file\":{\"mimeType\":\"application/octet-stream\"}}",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        contentBearers += request.headers[HttpHeaders.Authorization]
                        when (contentGets.getAndIncrement()) {
                            0 -> respond(content = "{}", status = HttpStatusCode.Unauthorized, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                            1 ->
                                respond(
                                    content = "{}",
                                    status = HttpStatusCode.TooManyRequests,
                                    headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("1")),
                                )
                            else ->
                                respond(
                                    content = "REAL",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                                )
                        }
                    }
                }

            var stored = "A"
            val service =
                GraphApiService(
                    config = OneDriveConfig(),
                    httpClient = HttpClient(engine),
                    tokenProvider = { force, rejected ->
                        asks += force to rejected
                        if (force) stored = "B"
                        stored
                    },
                )
            val dest = java.nio.file.Files.createTempFile("od-401-429", ".bin")

            service.downloadFile("item-9", dest)

            assertEquals(3, contentGets.get())
            // The first ask is the item lookup; the content requests follow.
            assertEquals(
                listOf<Pair<Boolean, String?>>(false to null, false to null, true to "A", false to null),
                asks,
                "one forced refresh, naming A",
            )
            assertEquals(listOf<String?>("Bearer A", "Bearer B", "Bearer B"), contentBearers, "both later requests carry B")
            service.close()
            java.nio.file.Files.deleteIfExists(dest)
        }
}
