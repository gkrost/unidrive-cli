package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.http.HttpRetryBudget
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * archive#291: OneDrive lost-update — a MODIFIED file ≤4 MiB replaced via the simple content PUT
 * carried NO `If-Match` header, so a concurrent remote edit landing between the engine's
 * plan and the apply was blind-overwritten. archive#113 only guarded the >4 MiB upload-session
 * path; the simple PUT (the majority of files) had no optimistic-concurrency token at all.
 *
 * Each test names one orthogonal invariant:
 *  1. a ≤4 MiB MODIFIED upload sends `If-Match` with the expected eTag (the closed gap).
 *  2. a 412 Precondition Failed from the simple PUT maps to the keep-both collision outcome —
 *     NOT a silent overwrite and NOT an unhandled throw (the no-lost-update guarantee).
 *  3. a NEW upload (existingRemoteId == null) sends NO `If-Match` and still succeeds.
 *  4. a normal MODIFIED upload with no concurrent edit still succeeds.
 *  5. a guard lookup failing with a retryable status (503, after the client's own retries)
 *     propagates — the PUT never goes out unguarded (#453).
 *  6. the name-confusion 404 of the guard lookup propagates too: the sibling it resolved to is
 *     never blind-replaced (#453).
 *  7. a genuine 404 — the item is gone — falls through to an unguarded replace, because there
 *     is nothing left to guard (#453).
 *  8. a cancellation of the guard lookup is rethrown, not absorbed into an unconditional
 *     replace (#453).
 */
class OneDriveIfMatchTest {
    private fun driveItemJson(
        id: String,
        name: String,
        size: Long = 5,
        eTag: String? = null,
    ): String {
        val eTagField = if (eTag != null) ""","eTag":"$eTag"""" else ""
        return """
            {"id":"$id","name":"$name","size":$size,"file":{"mimeType":"application/octet-stream"},
             "parentReference":{"path":"/drive/root:"}$eTagField}
            """.trimIndent()
    }

    /** Build a real OneDriveProvider whose GraphApiService transport is the supplied MockEngine. */
    private fun providerWith(engine: MockEngine): OneDriveProvider {
        val freshGraphApi =
            GraphApiService(
                config = OneDriveConfig(),
                httpClient = HttpClient(engine),
                tokenProvider = { _, _ -> "test-token" },
            )

        val provider = OneDriveProvider(graphApi = freshGraphApi)
        return provider
    }

    // The guard-lookup tests that ride the throttle loop (a 503 is retried internally) need its
    // waits on the test's virtual clock: the budget's clock is [currentTime], and the loop's
    // delays advance it with the rest.
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.providerWithVirtualBudget(engine: MockEngine): OneDriveProvider {
        val freshGraphApi =
            GraphApiService(
                config = OneDriveConfig(),
                throttleBudget = HttpRetryBudget(maxConcurrency = 8, clock = { currentTime }),
                httpClient = HttpClient(engine),
                tokenProvider = { _, _ -> "test-token" },
            )

        return OneDriveProvider(graphApi = freshGraphApi)
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `small modified upload sends If-Match with the current eTag`() =
        runTest {
            val ifMatchHeaders = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    when {
                        // At-upload-time eTag fetch for the MODIFIED path.
                        request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") ->
                            respond(driveItemJson("owned-id", "doc.txt", eTag = "etag-current"), HttpStatusCode.OK, jsonHeaders())
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            ifMatchHeaders += request.headers[HttpHeaders.IfMatch]
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-ifmatch", ".txt")
            Files.write(local, "world".toByteArray()) // ≤4 MiB → simple PUT

            provider.upload(local, "/doc.txt", existingRemoteId = "owned-id")

            assertEquals(
                listOf<String?>("etag-current"),
                ifMatchHeaders,
                "LOST UPDATE: a ≤4 MiB modified upload must send If-Match with the current eTag",
            )
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `small modified upload 412 keeps both and never overwrites`() =
        runTest {
            val conflictBehaviors = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") ->
                            respond(driveItemJson("owned-id", "doc.txt", eTag = "etag-stale"), HttpStatusCode.OK, jsonHeaders())
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            val cb = request.url.parameters["@microsoft.graph.conflictBehavior"]
                            conflictBehaviors += cb
                            // The replace carrying the now-stale If-Match → server has moved on.
                            if (cb == "replace") {
                                respond(
                                    content = """{"error":{"code":"preconditionFailed","message":"etag mismatch"}}""",
                                    status = HttpStatusCode.PreconditionFailed,
                                    headers = jsonHeaders(),
                                )
                            } else {
                                // keep-both rename landed.
                                respond(driveItemJson("kept-both-id", "doc 1.txt"), HttpStatusCode.OK, jsonHeaders())
                            }
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("kept-both-id", "doc 1.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-412", ".txt")
            Files.write(local, "world".toByteArray())

            val result = provider.upload(local, "/doc.txt", existingRemoteId = "owned-id")

            assertEquals(
                listOf<String?>("replace", "rename"),
                conflictBehaviors,
                "a 412 on the guarded replace must fall back to keep-both via rename, preserving the concurrent remote edit",
            )
            assertEquals("doc 1.txt", result.name, "result reflects the kept-both copy, not an overwrite of the concurrent edit")
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `keep both after a stale update lands under a server name when rename is refused`() =
        runTest {
            val behaviors = mutableListOf<String?>()
            val name = "g2-c-ꕙ.txt"
            val serverName = OneDriveServerNames.disambiguate(name)
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            val behavior = request.url.parameters["@microsoft.graph.conflictBehavior"]
                            behaviors += behavior
                            when (behavior) {
                                "replace" -> respond(
                                    """{"error":{"code":"preconditionFailed","message":"etag mismatch"}}""",
                                    HttpStatusCode.PreconditionFailed,
                                    jsonHeaders(),
                                )
                                "rename" -> respond(
                                    """{"error":{"code":"nameAlreadyExists","message":"The specified item name is incompatible with a similar name on an existing item."}}""",
                                    HttpStatusCode.Conflict,
                                    jsonHeaders(),
                                )
                                else -> respond(driveItemJson("landed-id", serverName), HttpStatusCode.OK, jsonHeaders())
                            }
                        }
                        request.method == HttpMethod.Patch ->
                            respond(driveItemJson("landed-id", serverName), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-stale-similar", ".txt")
            try {
                Files.writeString(local, "local edit")
                val item = provider.upload(local, "/$name", existingRemoteId = "owned-id", ifMatchETag = "stale")

                assertEquals(listOf<String?>("replace", "rename", "fail"), behaviors)
                assertTrue(item.nameOverridden)
                assertEquals("/$serverName", item.path)
            } finally {
                provider.close()
                Files.deleteIfExists(local)
            }
        }

    @Test
    fun `new upload sends no If-Match and succeeds`() =
        runTest {
            val ifMatchHeaders = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            ifMatchHeaders += request.headers[HttpHeaders.IfMatch]
                            respond(driveItemJson("new-id", "fresh.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("new-id", "fresh.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-new", ".txt")
            Files.write(local, "data".toByteArray())

            val result = provider.upload(local, "/fresh.txt", existingRemoteId = null)

            assertEquals(1, ifMatchHeaders.size, "exactly one content PUT for a fresh create")
            assertNull(ifMatchHeaders.single(), "a NEW upload must not send If-Match — there is nothing to guard against")
            assertEquals("fresh.txt", result.name)
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `small modified upload with no concurrent edit succeeds`() =
        runTest {
            var putSucceeded = false
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") ->
                            respond(driveItemJson("owned-id", "doc.txt", eTag = "etag-current"), HttpStatusCode.OK, jsonHeaders())
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            putSucceeded = true
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-noedit", ".txt")
            Files.write(local, "world".toByteArray())

            val result = provider.upload(local, "/doc.txt", existingRemoteId = "owned-id")

            assertTrue(putSucceeded, "the guarded replace must complete when the eTag still matches")
            assertEquals("doc.txt", result.name)
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `explicit plan-time eTag is preferred over the at-upload-time fetch`() =
        runTest {
            val ifMatchHeaders = mutableListOf<String?>()
            var metadataFetched = false
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") -> {
                            metadataFetched = true
                            respond(driveItemJson("owned-id", "doc.txt", eTag = "etag-at-upload"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            ifMatchHeaders += request.headers[HttpHeaders.IfMatch]
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-plantime", ".txt")
            Files.write(local, "world".toByteArray())

            provider.upload(local, "/doc.txt", existingRemoteId = "owned-id", ifMatchETag = "etag-plan-time")

            assertEquals(
                listOf<String?>("etag-plan-time"),
                ifMatchHeaders,
                "an engine-supplied plan-time eTag must be used verbatim, not overridden by an at-upload fetch",
            )
            assertTrue(!metadataFetched, "when the caller supplies a plan-time eTag the provider must skip the at-upload metadata GET")
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `a guard lookup failing with a retryable status propagates and no unconditional put goes out`() =
        runTest {
            var gets = 0
            var puts = 0
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") -> {
                            gets++
                            respond(
                                """{"error":{"code":"serviceUnavailable","message":"try again later"}}""",
                                HttpStatusCode.ServiceUnavailable,
                                headersOf(HttpHeaders.RetryAfter, "0"),
                            )
                        }
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            puts++
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWithVirtualBudget(engine)
            val local = Files.createTempFile("ud-guard-503", ".txt")
            Files.write(local, "world".toByteArray())

            val thrown =
                assertFailsWith<GraphApiException> {
                    provider.upload(local, "/doc.txt", existingRemoteId = "owned-id")
                }

            assertTrue(gets >= 2, "the lookup must have been retried internally first (503 after retries), got $gets GETs")
            assertEquals(0, puts, "a failed guard lookup must not degrade into an unconditional replace PUT")
            assertEquals(503, thrown.statusCode, "the 503 of the guard lookup must reach the caller, not strip the guard")
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `the name confusion 404 of the guard lookup does not turn the replace unconditional`() =
        runTest {
            var puts = 0
            val engine =
                MockEngine { request ->
                    when {
                        // The lookup answers with a SIMILAR sibling: itemAt must refuse the resolution
                        // ("resolved to a different name") instead of guarding the replace with it.
                        request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") ->
                            respond(driveItemJson("sibling-id", "doc 1.txt", eTag = "sibling-etag"), HttpStatusCode.OK, jsonHeaders())
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            puts++
                            respond(driveItemJson("sibling-id", "doc 1.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("sibling-id", "doc 1.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-guard-name404", ".txt")
            Files.write(local, "world".toByteArray())

            val thrown =
                assertFailsWith<GraphApiException> {
                    provider.upload(local, "/doc.txt", existingRemoteId = "owned-id")
                }

            assertEquals(404, thrown.statusCode)
            assertTrue(
                thrown.message.orEmpty().contains("resolved to a different name"),
                "the refusal must be the name confusion of the lookup, got: ${thrown.message}",
            )
            assertEquals(0, puts, "a name-confused lookup must never degrade into an unconditional replace of the sibling")
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `a gone item's 404 on the guard lookup falls through to an unguarded replace`() =
        runTest {
            val ifMatchHeaders = mutableListOf<String?>()
            val conflictBehaviors = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    when {
                        request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") ->
                            respond("""{"error":{"code":"itemNotFound","message":"The item does not exist"}}""", HttpStatusCode.NotFound, jsonHeaders())
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            ifMatchHeaders += request.headers[HttpHeaders.IfMatch]
                            conflictBehaviors += request.url.parameters["@microsoft.graph.conflictBehavior"]
                            respond(driveItemJson("recreated-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("recreated-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-guard-404", ".txt")
            Files.write(local, "world".toByteArray())

            val result = provider.upload(local, "/doc.txt", existingRemoteId = "owned-id")

            assertEquals(
                listOf<String?>(null),
                ifMatchHeaders,
                "a gone item leaves nothing to guard: the replace must go out unguarded instead of failing",
            )
            assertEquals(listOf<String?>("replace"), conflictBehaviors, "the fall-through must still be a replace at the same path")
            assertEquals("doc.txt", result.name)
            provider.close()
            Files.deleteIfExists(local)
        }

    @Test
    fun `a cancelled guard lookup is rethrown, not absorbed into an unconditional replace`() =
        runTest {
            var puts = 0
            val engine =
                MockEngine { request ->
                    when {
                        // The transport cancelled the lookup mid-flight (a request timeout surfaces
                        // the same way): the cancellation must end the upload, not strip its guard.
                        request.method == HttpMethod.Get && request.url.encodedPath.contains("/root:/") ->
                            throw CancellationException("the lookup was cancelled mid-flight")
                        request.method == HttpMethod.Put && request.url.encodedPath.endsWith("/content") -> {
                            puts++
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        }
                        request.method.value == "PATCH" ->
                            respond(driveItemJson("owned-id", "doc.txt"), HttpStatusCode.OK, jsonHeaders())
                        else -> respond("{}", HttpStatusCode.OK, jsonHeaders())
                    }
                }
            val provider = providerWith(engine)
            val local = Files.createTempFile("ud-guard-cancel", ".txt")
            Files.write(local, "world".toByteArray())

            assertFailsWith<CancellationException> {
                provider.upload(local, "/doc.txt", existingRemoteId = "owned-id")
            }

            assertEquals(0, puts, "a cancelled lookup must stop the upload, not degrade it into an unconditional replace")
            provider.close()
            Files.deleteIfExists(local)
        }
}
