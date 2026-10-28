package org.krost.unidrive.dropbox

import io.ktor.client.plugins.HttpTimeoutCapability
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.PermanentUploadFailureException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.RemoteConflictException
import org.krost.unidrive.dropbox.FakeDropbox.Answer
import org.krost.unidrive.dropbox.FakeDropbox.Companion.file
import org.krost.unidrive.dropbox.FakeDropbox.Companion.folder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Writes and reads against the fake: the upload modes (overwrite, or `update` against the rev of the content the engine
 * saw), upload sessions, the names Dropbox never syncs, ids for downloads, case-only renames, the token refresh on 401
 * and Dropbox's own wait on 429.
 */
class DropboxWriteTest {
    private val dir = Files.createTempDirectory("dropbox-write")
    private val fake = FakeDropbox()
    private val provider = FakeDropbox.provider(fake, dir)
    private val hashSeen = "b".repeat(64)

    private fun local(
        name: String,
        content: String,
    ) = dir.resolve(name).also {
        Files.writeString(it, content)
        Files.setLastModifiedTime(it, FileTime.from(Instant.parse("2026-03-04T05:06:07.890Z")))
    }

    @Test
    fun `a new upload adds, keeps the mtime in whole seconds and escapes non-ASCII in the header`() =
        runTest {
            fake.json("files/upload", file("/Grüße.txt", "id:9"))

            val item = provider.upload(local("g.txt", "hello"), "/Grüße.txt")

            val call = fake.callsTo("files/upload").single()
            assertTrue(call.rawArgHeader!!.contains("\\u00fc"), "header: ${call.rawArgHeader}")
            assertTrue(call.rawArgHeader!!.all { it.code in 0x20..0x7E })
            assertEquals("/Grüße.txt", call.args!!["path"]!!.jsonPrimitive.content)
            // gkrost/unidrive-cli#57: a create never replaces a file that appeared after the plan.
            assertEquals("add", call.args!!["mode"]!!.jsonObject[".tag"]!!.jsonPrimitive.content)
            assertEquals(false, call.args!!["autorename"]!!.jsonPrimitive.boolean)
            assertEquals("2026-03-04T05:06:07Z", call.args!!["client_modified"]!!.jsonPrimitive.content)
            assertContentEquals("hello".toByteArray(), call.body)
            assertEquals("id:9", item.id)
        }

    // archive#1062: the API path of an engine path loses its leading and trailing '/', never white space inside the name.
    @Test
    fun `a name with a trailing space keeps it and does not alias the name without`() =
        runTest {
            assertEquals("/d/a ", DropboxItems.apiPath("/d/a "))
            assertEquals("/d/ a", DropboxItems.apiPath("/d/ a/"))
            assertEquals("", DropboxItems.apiPath("/"))
            fake.json("files/upload", file("/d/a ", "id:1"))

            provider.upload(local("one.txt", "1"), "/d/a ")
            provider.upload(local("two.txt", "2"), "/d/a")

            assertEquals(listOf("/d/a ", "/d/a"), fake.callsTo("files/upload").map { it.args!!["path"]!!.jsonPrimitive.content })
        }

    @Test
    fun `a guarded upload writes against the rev of the content the engine saw`() =
        runTest {
            fake.json("files/get_metadata", file("/a.txt", "id:1", hash = hashSeen, rev = "015abc"))
            fake.json("files/upload", file("/a.txt", "id:1", rev = "015abd"))

            provider.upload(local("a.txt", "new"), "/a.txt", existingRemoteId = "id:1", ifMatchETag = hashSeen)

            val args = fake.callsTo("files/upload").single().args!!
            val mode = args["mode"]!!.jsonObject
            assertEquals("update", mode[".tag"]!!.jsonPrimitive.content)
            assertEquals("015abc", mode["update"]!!.jsonPrimitive.content)
            assertTrue(args["strict_conflict"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `a guarded upload of content that moved on is a conflict and sends nothing`() =
        runTest {
            fake.json("files/get_metadata", file("/a.txt", "id:1", hash = "c".repeat(64)))

            assertFailsWith<RemoteConflictException> {
                provider.upload(local("a.txt", "new"), "/a.txt", ifMatchETag = hashSeen)
            }
            assertTrue(fake.callsTo("files/upload").isEmpty())
        }

    // gkrost/unidrive-cli#57: the engine compares and keeps both; the provider only reports the taken path.
    @Test
    fun `a new upload onto a taken path is a conflict`() =
        runTest {
            fake.json("files/upload", """{"error_summary":"path/conflict/file/..","error":{".tag":"path"}}""", status = 409)

            assertFailsWith<RemoteConflictException> { provider.upload(local("a.txt", "new"), "/a.txt") }
        }

    @Test
    fun `a replace without a token still overwrites`() =
        runTest {
            fake.json("files/upload", file("/a.txt", "id:1"))

            provider.upload(local("a.txt", "new"), "/a.txt", existingRemoteId = "id:1")

            assertEquals("overwrite", fake.callsTo("files/upload").single().args!!["mode"]!!.jsonObject[".tag"]!!.jsonPrimitive.content)
        }

    @Test
    fun `dropbox refusing the rev is a conflict too`() =
        runTest {
            fake.json("files/get_metadata", file("/a.txt", "id:1", hash = hashSeen))
            fake.json("files/upload", """{"error_summary":"path/conflict/file/..","error":{".tag":"path"}}""", status = 409)

            assertFailsWith<RemoteConflictException> {
                provider.upload(local("a.txt", "new"), "/a.txt", ifMatchETag = hashSeen)
            }
        }

    @Test
    fun `a big file goes up in a session of appends at growing offsets`() =
        runTest {
            val small = FakeDropbox.provider(fake, dir, { it.copy(simpleUploadMaxBytes = 4, uploadChunkBytes = 4) })
            fake.json("files/upload_session/start", """{"session_id":"s1"}""")
            fake.json("files/upload_session/append_v2", "null")
            fake.json("files/upload_session/finish", file("/big.bin", "id:3", size = 10))
            val progress = mutableListOf<Long>()

            small.upload(local("big.bin", "0123456789"), "/big.bin") { done, _ -> progress += done }

            val appends = fake.callsTo("files/upload_session/append_v2")
            assertEquals(listOf(0L, 4L, 8L), appends.map { it.args!!["cursor"]!!.jsonObject["offset"]!!.jsonPrimitive.long })
            assertEquals(listOf("0123", "4567", "89"), appends.map { it.body.toString(Charsets.UTF_8) })
            val finish = fake.callsTo("files/upload_session/finish").single().args!!
            assertEquals(10, finish["cursor"]!!.jsonObject["offset"]!!.jsonPrimitive.long)
            assertEquals("/big.bin", finish["commit"]!!.jsonObject["path"]!!.jsonPrimitive.content)
            assertEquals(listOf(4L, 8L, 10L, 10L), progress)
        }

    // archive#867: the session survives the process. The first attempt dies after the first append; a
    // restarted provider continues at the committed offset instead of starting over.
    // archive#1058: the socket timeout is a read-idle timer from the start of the request and Dropbox answers only after the
    // body; at its 60 s default it cut every upload a slow uplink needs longer than that for.
    @Test
    fun `every upload request, single and session append, runs under a socket timeout as long as its request timeout`() =
        runTest {
            val small = FakeDropbox.provider(fake, dir, { it.copy(simpleUploadMaxBytes = 4, uploadChunkBytes = 4) })
            fake.json("files/upload", file("/a.txt", "id:1"))
            fake.json("files/upload_session/start", """{"session_id":"s1"}""")
            fake.json("files/upload_session/append_v2", "null")
            fake.json("files/upload_session/finish", file("/big.bin", "id:3", size = 10))

            provider.upload(local("a.txt", "hi"), "/a.txt")
            small.upload(local("big.bin", "0123456789"), "/big.bin")

            val uploads = fake.engine.requestHistory.filter { it.url.encodedPath.endsWith("/files/upload") || it.url.encodedPath.endsWith("/append_v2") }
            assertEquals(1 + 3, uploads.size, "one single upload and three appends")
            for (request in uploads) {
                val timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
                assertNotNull(timeouts, "an upload carries timeouts")
                assertEquals(timeouts.requestTimeoutMillis, timeouts.socketTimeoutMillis, "socket timeout of ${request.url.encodedPath}")
                assertTrue(timeouts.socketTimeoutMillis!! > HttpDefaults.SOCKET_TIMEOUT_MS, "longer than the ${HttpDefaults.SOCKET_TIMEOUT_MS} ms default")
            }
        }

    @Test
    fun `an interrupted session resumes at the committed offset`() =
        runTest {
            val small = FakeDropbox.provider(fake, dir, { it.copy(simpleUploadMaxBytes = 4, uploadChunkBytes = 4) })
            fake.json("files/upload_session/start", """{"session_id":"s1"}""")
            val received = StringBuilder()
            var appends = 0
            fake.on("files/upload_session/append_v2") { call ->
                appends++
                if (appends == 2) {
                    FakeDropbox.Answer(500, """{"error_summary":"internal_error/"}""")
                } else {
                    received.append(call.body.toString(Charsets.UTF_8))
                    FakeDropbox.Answer(200, "null")
                }
            }
            fake.json("files/upload_session/finish", file("/big.bin", "id:3", size = 10))

            runCatching { small.upload(local("big.bin", "0123456789"), "/big.bin") }
            assertTrue(received.length >= 4, "the first chunk landed before the interruption")

            val again = FakeDropbox.provider(fake, dir, { it.copy(simpleUploadMaxBytes = 4, uploadChunkBytes = 4) })
            fake.json("files/upload_session/finish", file("/big.bin", "id:3", size = 10))

            again.upload(local("big.bin", "0123456789"), "/big.bin")

            assertEquals(1, fake.callsTo("files/upload_session/start").size, "no second session was started")
            // The first attempt made two append calls (one landed, one failed); the rest are the resume.
            val resumed = fake.callsTo("files/upload_session/append_v2").drop(2)
            assertEquals(4L, resumed.first().args!!["cursor"]!!.jsonObject["offset"]!!.jsonPrimitive.long, "the retry continues at the committed offset")
            assertEquals(listOf("4567", "89"), resumed.map { it.body.toString(Charsets.UTF_8) })
            assertContentEquals("0123456789".toByteArray(), received.toString().toByteArray(), "the final file equals the source")
            assertEquals(10L, fake.callsTo("files/upload_session/finish").last().args!!["cursor"]!!.jsonObject["offset"]!!.jsonPrimitive.long)
        }

    // archive#867: Dropbox answers incorrect_offset with the offset it actually holds. The upload moves
    // there once; a second mismatch ends the session with a typed error instead of guessing again.
    @Test
    fun `an incorrect_offset answer moves the upload and a second one aborts`() =
        runTest {
            // The stored resume offset is stale (the server holds 4, not 8).
            DropboxSessionStore(dir).put("/big.bin", "s0", 8, 10, Instant.parse("2026-03-04T05:06:07.890Z").toEpochMilli())
            val received = StringBuilder()
            var correctedOnce = false
            fake.on("files/upload_session/append_v2") { call ->
                val offset = call.args!!["cursor"]!!.jsonObject["offset"]!!.jsonPrimitive.long
                when {
                    offset == 8L -> FakeDropbox.Answer(
                        400,
                        """{"error_summary":"incorrect_offset/","error":{"reason":{".tag":"incorrect_offset","correct_offset":4},".tag":"upload_session_offset_error"}}""",
                    )
                    offset == 4L && !correctedOnce -> {
                        correctedOnce = true
                        received.append(call.body.toString(Charsets.UTF_8))
                        FakeDropbox.Answer(200, "null")
                    }
                    else -> FakeDropbox.Answer(
                        400,
                        """{"error_summary":"incorrect_offset/","error":{"reason":{".tag":"incorrect_offset","correct_offset":4},".tag":"upload_session_offset_error"}}""",
                    )
                }
            }
            val small = FakeDropbox.provider(fake, dir, { it.copy(simpleUploadMaxBytes = 4, uploadChunkBytes = 4) })
            val failure = assertFailsWith<ProviderException> { small.upload(local("big.bin", "0123456789"), "/big.bin") }
            assertTrue("offset mismatch" in (failure.message ?: ""), failure.message ?: "")
            assertEquals("4567", received.toString(), "the corrected append went through before the second mismatch")
            assertNull(DropboxSessionStore(dir).get("/big.bin", 10, Instant.parse("2026-03-04T05:06:07.890Z").toEpochMilli()), "the unusable session is dropped")
        }

    // A session Dropbox no longer has (expired, closed, or never ours) answers not_found / closed on the append and
    // lookup_failed on the finish. Kept in the store, every later attempt would resume into it and fail the same way.
    @Test
    fun `a session dropbox no longer has is dropped and the next attempt starts a new one`() =
        runTest {
            val mtime = Instant.parse("2026-03-04T05:06:07.890Z").toEpochMilli()
            DropboxSessionStore(dir).put("/big.bin", "gone", 4, 10, mtime)
            fake.json("files/upload_session/start", """{"session_id":"s1"}""")
            fake.on("files/upload_session/append_v2") { call ->
                val id = call.args!!["cursor"]!!.jsonObject["session_id"]!!.jsonPrimitive.content
                if (id == "gone") {
                    FakeDropbox.Answer(409, """{"error_summary":"not_found/","error":{".tag":"not_found"}}""")
                } else {
                    FakeDropbox.Answer(200, "null")
                }
            }
            fake.json("files/upload_session/finish", file("/big.bin", "id:3", size = 10))
            val small = FakeDropbox.provider(fake, dir, { it.copy(simpleUploadMaxBytes = 4, uploadChunkBytes = 4) })

            runCatching { small.upload(local("big.bin", "0123456789"), "/big.bin") }
            assertNull(DropboxSessionStore(dir).get("/big.bin", 10, mtime), "the dead session is dropped")

            small.upload(local("big.bin", "0123456789"), "/big.bin")
            assertEquals(1, fake.callsTo("files/upload_session/start").size, "the retry started a new session")
            assertEquals(10L, fake.callsTo("files/upload_session/finish").last().args!!["cursor"]!!.jsonObject["offset"]!!.jsonPrimitive.long)
        }

    @Test
    fun `a finish that cannot find the session drops it`() =
        runTest {
            val mtime = Instant.parse("2026-03-04T05:06:07.890Z").toEpochMilli()
            DropboxSessionStore(dir).put("/big.bin", "gone", 10, 10, mtime)
            fake.on("files/upload_session/finish") { _ ->
                FakeDropbox.Answer(
                    409,
                    """{"error_summary":"lookup_failed/not_found/","error":{".tag":"lookup_failed","lookup_failed":{".tag":"not_found"}}}""",
                )
            }
            val small = FakeDropbox.provider(fake, dir, { it.copy(simpleUploadMaxBytes = 4, uploadChunkBytes = 4) })

            runCatching { small.upload(local("big.bin", "0123456789"), "/big.bin") }
            assertNull(DropboxSessionStore(dir).get("/big.bin", 10, mtime), "the dead session is dropped")
        }

    // `closed` is Dropbox's answer for a session that was committed (or closed) already, `lookup_failed/closed` the same on
    // the finish: a lost answer to a finish that went through leaves exactly this behind. Neither can be resumed.
    @Test
    fun `an append or a finish into a closed session drops it`() =
        runTest {
            val mtime = Instant.parse("2026-03-04T05:06:07.890Z").toEpochMilli()
            val small = FakeDropbox.provider(fake, dir, { it.copy(simpleUploadMaxBytes = 4, uploadChunkBytes = 4) })

            DropboxSessionStore(dir).put("/big.bin", "done", 4, 10, mtime)
            fake.json("files/upload_session/append_v2", """{"error_summary":"closed/..","error":{".tag":"closed"}}""", status = 409)
            runCatching { small.upload(local("big.bin", "0123456789"), "/big.bin") }
            assertNull(DropboxSessionStore(dir).get("/big.bin", 10, mtime), "an append into a closed session drops it")

            DropboxSessionStore(dir).put("/big.bin", "done", 10, 10, mtime)
            fake.json(
                "files/upload_session/finish",
                """{"error_summary":"lookup_failed/closed/..","error":{".tag":"lookup_failed","lookup_failed":{".tag":"closed"}}}""",
                status = 409,
            )
            runCatching { small.upload(local("big.bin", "0123456789"), "/big.bin") }
            assertNull(DropboxSessionStore(dir).get("/big.bin", 10, mtime), "a finish into a closed session drops it")
        }

    // The other side of the drop: Dropbox asking for a retry is not a dead session. The session stays, and the retry goes
    // straight to the finish without sending the bytes again.
    @Test
    fun `a finish dropbox asks to retry keeps the session`() =
        runTest {
            val mtime = Instant.parse("2026-03-04T05:06:07.890Z").toEpochMilli()
            DropboxSessionStore(dir).put("/big.bin", "s1", 10, 10, mtime)
            fake.json("files/upload_session/finish", """{"error_summary":"too_many_write_operations/..","error":{".tag":"too_many_write_operations"}}""", status = 409)
            fake.json("files/upload_session/finish", file("/big.bin", "id:3", size = 10))
            val small = FakeDropbox.provider(fake, dir, { it.copy(simpleUploadMaxBytes = 4, uploadChunkBytes = 4) })

            runCatching { small.upload(local("big.bin", "0123456789"), "/big.bin") }
            assertEquals("s1", DropboxSessionStore(dir).get("/big.bin", 10, mtime)?.sessionId, "the session is kept")

            small.upload(local("big.bin", "0123456789"), "/big.bin")
            assertTrue(fake.callsTo("files/upload_session/start").isEmpty(), "no new session")
            assertTrue(fake.callsTo("files/upload_session/append_v2").isEmpty(), "no byte is sent again")
            assertEquals(2, fake.callsTo("files/upload_session/finish").size)
        }

    @Test
    fun `names dropbox never syncs are refused before any request`() =
        runTest {
            for (name in listOf("~\$Report.docx", "desktop.ini", "~WRL0001.tmp", ".DS_Store")) {
                assertFailsWith<PermanentUploadFailureException>(name) { provider.upload(local("x", "x"), "/Docs/$name") }
            }
            assertTrue(fake.callsTo("files/upload").isEmpty())
        }

    @Test
    fun `a download by id fetches the id and stages the bytes`() =
        runTest {
            fake.on("files/download") { Answer(bytes = "content".toByteArray(), headers = mapOf(DropboxApi.API_RESULT to file("/a.txt", "id:5"))) }
            val target = dir.resolve("out/a.txt")

            val written = provider.downloadById("id:5", "/a.txt", target)

            assertEquals(7, written)
            assertEquals("content", Files.readString(target))
            assertEquals("id:5", fake.callsTo("files/download").single().args!!["path"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a file that is gone is a permanent download failure`() =
        runTest {
            fake.json("files/download", """{"error_summary":"path/not_found/.."}""", status = 409)

            assertFailsWith<PermanentDownloadFailureException> { provider.download("/gone.txt", dir.resolve("gone.txt")) }
        }

    @Test
    fun `a guarded delete sends the rev and a delete of what is gone is done`() =
        runTest {
            fake.json("files/get_metadata", file("/a.txt", "id:1", hash = hashSeen, rev = "r7"))
            fake.json("files/delete_v2", """{"metadata":${file("/a.txt", "id:1")}}""")

            provider.delete("/a.txt", ifMatchETag = hashSeen)
            assertEquals("r7", fake.callsTo("files/delete_v2").single().args!!["parent_rev"]!!.jsonPrimitive.content)

            fake.json("files/delete_v2", """{"error_summary":"path_lookup/not_found/.."}""", status = 409)
            provider.deleteById("id:2", "/b.txt")
            assertEquals("id:2", fake.callsTo("files/delete_v2").last().args!!["path"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a permanent delete uses permanently_delete, by id when it has one, and never delete_v2`() =
        runTest {
            fake.json("files/permanently_delete", "{}")

            provider.deletePermanently("/a.txt")
            provider.deletePermanentlyById("id:2", "/b.txt")

            val calls = fake.callsTo("files/permanently_delete")
            assertEquals("/a.txt", calls[0].args!!["path"]!!.jsonPrimitive.content)
            assertEquals("id:2", calls[1].args!!["path"]!!.jsonPrimitive.content)
            assertEquals(0, fake.callsTo("files/delete_v2").size, "the trash delete must not run")
        }

    @Test
    fun `a permanent delete of what is gone is done`() =
        runTest {
            fake.json("files/permanently_delete", """{"error_summary":"path_lookup/not_found/.."}""", status = 409)
            provider.deletePermanently("/gone.txt")
        }

    @Test
    fun `any other refusal of a permanent delete is an error, not a silent success`() =
        runTest {
            fake.json("files/permanently_delete", """{"error_summary":"path_lookup/malformed_path/.."}""", status = 409)

            assertFailsWith<DropboxApiException> { provider.deletePermanently("/a.txt") }
        }

    @Test
    fun `a rename that changes only the case goes through a temporary name`() =
        runTest {
            fake.on("files/move_v2") { call ->
                Answer(body = """{"metadata":${file(call.args!!["to_path"]!!.jsonPrimitive.content, "id:1")}}""")
            }

            val moved = provider.moveById("id:1", "/Docs/a.txt", "/Docs/A.txt")

            val moves = fake.callsTo("files/move_v2").map { it.args!!["from_path"]!!.jsonPrimitive.content to it.args!!["to_path"]!!.jsonPrimitive.content }
            assertEquals(2, moves.size)
            assertEquals("id:1", moves[0].first)
            assertTrue(moves[0].second.startsWith("/Docs/.unidrive-case-"))
            assertEquals(moves[0].second to "/Docs/A.txt", moves[1])
            assertEquals("/Docs/A.txt", moved.path)
        }

    @Test
    fun `creating a folder that is there already answers the folder`() =
        runTest {
            fake.json("files/create_folder_v2", """{"error_summary":"path/conflict/folder/.."}""", status = 409)
            fake.json("files/get_metadata", folder("/Docs", "id:f"))

            val created = provider.createFolder("/Docs")

            assertTrue(created.isFolder)
            assertEquals("id:f", created.id)
        }

    @Test
    fun `a 401 refreshes the token once, keeps the refresh token and retries`() =
        runTest {
            fake.on("files/get_metadata") { Answer(401, """{"error_summary":"expired_access_token/.."}""") }
            fake.on("files/get_metadata") { Answer(body = file("/a.txt", "id:1")) }
            fake.json("oauth2/token", """{"access_token":"${"n".repeat(40)}","token_type":"bearer","expires_in":14400}""")

            provider.getMetadata("/a.txt")

            val refresh = fake.callsTo("oauth2/token").single().body.toString(Charsets.UTF_8)
            assertTrue(refresh.contains("grant_type=refresh_token") && refresh.contains("refresh_token=r1") && refresh.contains("client_id=key"))
            val retried = fake.callsTo("files/get_metadata").last()
            assertEquals("Bearer ${"n".repeat(40)}", retried.headers["Authorization"])
            val saved = org.krost.unidrive.UnidriveJson.decodeFromString(DropboxToken.serializer(), Files.readString(dir.resolve("token.json")))
            assertEquals("r1", saved.refreshToken)
        }

    @Test
    fun `a 429 waits as long as dropbox asks and then succeeds`() =
        runTest {
            fake.on("files/get_metadata") { Answer(429, """{"error_summary":"too_many_requests/..","error":{"retry_after":2}}""") }
            fake.on("files/get_metadata") { Answer(body = file("/a.txt", "id:1")) }

            val started = testScheduler.currentTime
            provider.getMetadata("/a.txt")

            // At least Dropbox's wait; the shared budget may pace the retry a little more.
            val waited = testScheduler.currentTime - started
            assertTrue(waited in 2_000..5_000, "waited $waited ms")
            assertEquals(2, fake.callsTo("files/get_metadata").size)
        }

    @Test
    fun `a 429 with Retry-After in the HTTP date form waits until that date`() =
        runTest {
            val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(6))
            fake.on("files/get_metadata") { Answer(429, """{"error_summary":"too_many_requests/.."}""", headers = mapOf("Retry-After" to date)) }
            fake.on("files/get_metadata") { Answer(body = file("/a.txt", "id:1")) }

            val started = testScheduler.currentTime
            provider.getMetadata("/a.txt")

            val waited = testScheduler.currentTime - started
            assertTrue(waited in 4_500..7_000, "waited $waited ms")
            assertEquals(2, fake.callsTo("files/get_metadata").size)
        }

    @Test
    fun `verify, quota and an existing share link`() =
        runTest {
            fake.json("files/get_metadata", """{"error_summary":"path/not_found/.."}""", status = 409)
            fake.json("users/get_space_usage", """{"used":300,"allocation":{".tag":"individual","allocated":1000}}""")
            fake.json(
                "sharing/create_shared_link_with_settings",
                """{"error_summary":"shared_link_already_exists/..","error":{".tag":"shared_link_already_exists",""" +
                    """"shared_link_already_exists":{".tag":"metadata","metadata":{"url":"https://www.dropbox.com/s/x"}}}}""",
                status = 409,
            )

            assertEquals(CapabilityResult.Success(false), provider.verifyItemExists("id:gone"))
            val quota = provider.quota()
            assertEquals(1000, quota.total)
            assertEquals(700, quota.remaining)
            assertEquals(CapabilityResult.Success("https://www.dropbox.com/s/x"), provider.share("/a.txt", expiryHours = 0))
            assertFalse(fake.callsTo("sharing/create_shared_link_with_settings").single().args!!["settings"]!!.jsonObject.containsKey("expires"))
            assertNull(fake.callsTo("files/get_metadata").single().headers[DropboxApi.PATH_ROOT])
        }
}
