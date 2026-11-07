package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentUploadFailureException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// gkrost/unidrive-cli#450: the finish reconcile lists the Drive folder, and a Drive row only appears after
// createFile/replaceFile, which run after commitWithRetry returns. In the same attempt our own commit can therefore
// not be listed: a row with the same name, type and size created within the window is another (older) upload of the
// same file. Adopting it makes replaceFile point the file back at the old ciphertext, a silent revert of the edit.
// The only proof that a listed row is ours is its bridge object: the index (the per-upload random key/IV material)
// must be the one this attempt finished with.
class InternxtFinishReconcileBridgeIndexTest {
    private val ourIndex = "11".repeat(32)
    private val foreignIndex = "22".repeat(32)
    private val startedAt: java.time.Instant = java.time.Instant.parse("2026-05-18T10:00:00Z")
    private val missingUploadsBody =
        """{"statusCode":409,"message":"MissingUploadsError: shard upload missing for finish","error":"Conflict"}"""
    private val lostResponseMsg = "Failed to parse HTTP response: the server prematurely closed the connection"

    private enum class Finish { MISSING_UPLOADS_409, LOST_RESPONSE }

    private class Calls {
        val finish = AtomicInteger(0)
        val listing = AtomicInteger(0)
        val bridgeInfo = AtomicInteger(0)
    }

    private fun listingWith(vararg rows: Pair<String, java.time.Instant>): String =
        rows.joinToString(prefix = """{"children":[],"files":[""", postfix = "]}") { (fileId, created) ->
            """{"uuid":"uuid-$fileId","fileId":"$fileId","plainName":"db","type":"bin","size":"1024",""" +
                """"bucket":"test-bucket","status":"EXISTS","creationTime":"$created"}"""
        }

    // bridgeIndexByFileId: the index the bridge answers for each file id; a missing id answers 404.
    private fun engine(
        calls: Calls,
        finish: Finish,
        listingBody: String,
        bridgeIndexByFileId: Map<String, String>,
    ) = MockEngine { request ->
        val url = request.url.toString()
        val json = headersOf("Content-Type", "application/json")
        when {
            url.contains("/v2/buckets/") && url.endsWith("/files/finish") -> {
                calls.finish.incrementAndGet()
                when (finish) {
                    Finish.MISSING_UPLOADS_409 -> respond(missingUploadsBody, HttpStatusCode.Conflict, json)
                    Finish.LOST_RESPONSE -> throw java.io.IOException(lostResponseMsg)
                }
            }
            url.contains("/folders/content/folder-uuid-1") -> {
                calls.listing.incrementAndGet()
                respond(listingBody, HttpStatusCode.OK, json)
            }
            url.contains("/buckets/test-bucket/files/") && url.endsWith("/info") -> {
                calls.bridgeInfo.incrementAndGet()
                val fileId = url.substringAfter("/buckets/test-bucket/files/").substringBefore("/info")
                val index = bridgeIndexByFileId[fileId]
                if (index == null) {
                    respond("""{"error":"File not found"}""", HttpStatusCode.NotFound, json)
                } else {
                    respond(
                        """{"bucket":"test-bucket","id":"$fileId","index":"$index","size":1040,""" +
                            """"shards":[{"index":0,"hash":"${"ab".repeat(20)}"}]}""",
                        HttpStatusCode.OK,
                        json,
                    )
                }
            }
            else -> error("unexpected URL: $url")
        }
    }

    private fun service(engine: MockEngine) =
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

    private suspend fun commit(api: InternxtApiService) =
        commitWithRetry(
            api = api,
            bucket = "test-bucket",
            folderUuid = "folder-uuid-1",
            plainName = "db",
            ext = "bin",
            fileSize = 1024L,
            encryptedSize = 1040L,
            indexHex = ourIndex,
            hashHex = "aa".repeat(32),
            shardUuid = "shard-uuid-1",
            startedAt = startedAt,
            clock = { startedAt.plusMillis(500) },
        )

    @Test
    fun `a 409 reconcile never adopts a same-name same-size row whose bridge index is another upload's`() =
        runTest {
            val calls = Calls()
            val api =
                service(
                    engine(
                        calls,
                        Finish.MISSING_UPLOADS_409,
                        listingWith("bridge-fileId-PREVIOUS-SAVE" to startedAt.minusSeconds(90)),
                        mapOf("bridge-fileId-PREVIOUS-SAVE" to foreignIndex),
                    ),
                )
            try {
                assertFailsWith<PermanentUploadFailureException> { commit(api) }
                assertEquals(1, calls.finish.get(), "no second finish on an exhausted session")
                assertEquals(1, calls.bridgeInfo.get(), "the candidate's bridge object was looked at before deciding")
            } finally {
                api.close()
            }
        }

    @Test
    fun `a lost-response reconcile never adopts a same-name same-size row whose bridge index is another upload's`() =
        runTest {
            val calls = Calls()
            val api =
                service(
                    engine(
                        calls,
                        Finish.LOST_RESPONSE,
                        listingWith("bridge-fileId-PREVIOUS-SAVE" to startedAt.minusSeconds(90)),
                        mapOf("bridge-fileId-PREVIOUS-SAVE" to foreignIndex),
                    ),
                )
            try {
                val thrown = assertFailsWith<java.io.IOException> { commit(api) }
                assertEquals(lostResponseMsg, thrown.message, "the original failure surfaces so the pipeline retries")
                assertEquals(1, calls.bridgeInfo.get(), "the candidate's bridge object was looked at before deciding")
            } finally {
                api.close()
            }
        }

    @Test
    fun `a reconcile whose bridge lookup fails does not adopt the row`() =
        runTest {
            val calls = Calls()
            val api =
                service(
                    engine(
                        calls,
                        Finish.LOST_RESPONSE,
                        listingWith("bridge-fileId-UNKNOWN" to startedAt.plusSeconds(1)),
                        emptyMap(), // the bridge answers 404
                    ),
                )
            try {
                assertFailsWith<java.io.IOException> { commit(api) }
                assertEquals(1, calls.bridgeInfo.get())
            } finally {
                api.close()
            }
        }

    @Test
    fun `a reconcile adopts the row whose bridge index is ours`() =
        runTest {
            val calls = Calls()
            val api =
                service(
                    engine(
                        calls,
                        Finish.MISSING_UPLOADS_409,
                        listingWith("bridge-fileId-OURS" to startedAt.plusSeconds(2)),
                        mapOf("bridge-fileId-OURS" to ourIndex),
                    ),
                )
            try {
                val entry = commit(api)
                assertEquals("bridge-fileId-OURS", entry.id)
                assertEquals(1, calls.finish.get(), "no re-finish when the reconcile proves the commit landed")
            } finally {
                api.close()
            }
        }

    @Test
    fun `a lost-response reconcile adopts the row whose bridge index is ours`() =
        runTest {
            val calls = Calls()
            val api =
                service(
                    engine(
                        calls,
                        Finish.LOST_RESPONSE,
                        listingWith("bridge-fileId-OURS" to startedAt.plusSeconds(2)),
                        mapOf("bridge-fileId-OURS" to ourIndex),
                    ),
                )
            try {
                assertEquals("bridge-fileId-OURS", commit(api).id)
            } finally {
                api.close()
            }
        }

    @Test
    fun `with several candidates only the one whose bridge index is ours is adopted, even when it is not the youngest`() =
        runTest {
            val calls = Calls()
            val api =
                service(
                    engine(
                        calls,
                        Finish.MISSING_UPLOADS_409,
                        listingWith(
                            "bridge-fileId-OURS" to startedAt.plusSeconds(2),
                            "bridge-fileId-FOREIGN-YOUNGER" to startedAt.plusSeconds(30),
                        ),
                        mapOf("bridge-fileId-OURS" to ourIndex, "bridge-fileId-FOREIGN-YOUNGER" to foreignIndex),
                    ),
                )
            try {
                assertEquals("bridge-fileId-OURS", commit(api).id)
            } finally {
                api.close()
            }
        }
}
