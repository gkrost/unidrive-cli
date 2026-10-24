package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-366 data-loss regression: create-collision keep-both.
 *
 * When POST /files returns 409 and existingRemoteId=null (DB-drift / stale
 * listing), the only safe silent overwrite is when the remote file is
 * provably byte-identical to the local upload.  For any other case the
 * remote must be preserved untouched; the local content lands under a
 * conflict-suffixed name.
 *
 * Content identity is the bucket-entry fileId plus size (Internxt's content
 * address for the encrypted blob), or, when that id differs, a same-size
 * comparison of the bytes. A different size, a null fileId, or a compare
 * that cannot be read is not proof → keep both.
 *
 * The file compared is the live row an exact full name selects. A baseName
 * sibling such as `Makefile.am` is not `Makefile`, and more than one live
 * exact match is not one file, so neither is adopted.
 *
 *  1. [create_collision_with_differing_remote_keeps_both_does_not_overwrite]
 *     — content not identical: replaceFile is not called; local lands as a
 *     conflict copy.
 *  2. [create_collision_with_identical_remote_adopts_no_conflict_copy]
 *     — identical bucket-entry + size → adopt (replaceFile); no conflict
 *     createFile.
 *  3. [create_collision_on_folder_path_still_throws]
 *     — existing item is a folder → original exception re-thrown unchanged.
 *  4. [conflict_name_helper_appends_counter_on_second_collision]
 *     — name helper: base → base (2) → base (3) …
 */
class InternxtCreateCollisionTest {

    // -------------------------------------------------------------------------
    // Shared test infrastructure (mirrors tombstone-test helpers in
    // InternxtApiServiceTest)
    // -------------------------------------------------------------------------

    private fun newProviderRooted(tokenPath: java.nio.file.Path): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = tokenPath))
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        val payload = """{"exp":9999999999}"""
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.toByteArray())
        val fakeJwt = "header.$payloadB64.signature"
        credsField.set(
            authService,
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = fakeJwt,
                mnemonic =
                    "abandon abandon abandon abandon abandon abandon " +
                        "abandon abandon abandon abandon abandon about",
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = "6928426c1a2316b856c9ab81",
            ),
        )
        return provider
    }

    private fun installMockClientOnProvider(
        provider: InternxtProvider,
        engine: io.ktor.client.engine.mock.MockEngine,
    ) {
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        val field = InternxtApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(api) as? io.ktor.client.HttpClient)?.close()
        field.set(api, io.ktor.client.HttpClient(engine))
    }

    /**
     * Standard upload pre-amble responses: startUpload + PUT + finishUpload.
     * These three are identical across all collision tests; only the POST
     * /files and GET /folders/content responses differ.
     */
    private val shardUrl = "https://shard-host.invalid/collision-put"
    private val localFileId = "local-bucket-entry-id"       // what we just uploaded
    private val remoteFileId = "remote-bucket-entry-different" // a DIFFERENT remote content

    // -------------------------------------------------------------------------
    // TEST 1 (non-negotiable regression guard)
    // -------------------------------------------------------------------------

    /**
     * Invariant guarded: a remote file whose content is NOT provably identical
     * to the local upload must NEVER be overwritten/replaced.
     *
     * Scenario:
     *  - POST /files → 409 (path exists on remote, existingRemoteId=null)
     *  - GET /folders/content → remote file with DIFFERENT fileId and DIFFERENT size
     *  - Expected: replaceFile (PUT) is NEVER called; a second POST /files is
     *    called with a conflict-suffixed name carrying the local content.
     */
    @Test
    fun `create_collision_with_differing_remote_keeps_both_does_not_overwrite`() =
        runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-coll-diff-")
            // Uploaded file: "AGENTS.md" (31 KB plaintext — real size doesn't matter
            // for the HTTP-mock path; we use a small actual file here)
            val local =
                java.nio.file.Files
                    .createTempFile(tmp, "AGENTS-", ".md")
                    .also { p -> java.nio.file.Files.write(p, ByteArray(200) { it.toByte() }) }
            try {
                val startCalls = AtomicInteger(0)
                val putCalls = AtomicInteger(0)
                val finishCalls = AtomicInteger(0)
                val replaceFileCalls = AtomicInteger(0)
                val createFileCalls = AtomicInteger(0)
                val folderContentCalls = AtomicInteger(0)
                // Capture the plainName sent to each POST /files so we can assert
                // the first was "AGENTS" (the original attempt) and the second
                // carries a conflict-suffix.
                val capturedCreatePlainNames =
                    java.util.Collections.synchronizedList(mutableListOf<String>())

                val engine =
                    io.ktor.client.engine.mock.MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            // startUpload
                            url.contains("/v2/buckets/") && url.contains("/files/start") -> {
                                startCalls.incrementAndGet()
                                respond(
                                    content = """{"uploads":[{"index":0,"uuid":"shard-uuid-1","url":"$shardUrl"}]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                            // PUT shard
                            url == shardUrl -> {
                                putCalls.incrementAndGet()
                                respond("", HttpStatusCode.OK)
                            }
                            // finishUpload
                            url.contains("/v2/buckets/") && url.contains("/files/finish") -> {
                                finishCalls.incrementAndGet()
                                respond(
                                    content = """{"id":"$localFileId","index":"${"aa".repeat(32)}","bucket":"6928426c1a2316b856c9ab81","name":"enc-agents"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                            // POST /drive/files — first call is the original (409);
                            // second call is the conflict copy (OK).
                            url.startsWith("https://gateway.internxt.com/drive/files") &&
                                request.method == HttpMethod.Post -> {
                                val bodyText =
                                    when (val b = request.body) {
                                        is io.ktor.http.content.ByteArrayContent ->
                                            String(b.bytes(), Charsets.UTF_8)
                                        is io.ktor.http.content.TextContent -> b.text
                                        else -> "{}"
                                    }
                                val parsed = Json.parseToJsonElement(bodyText).jsonObject
                                val pn = parsed["plainName"]?.toString()?.trim('"') ?: ""
                                capturedCreatePlainNames.add(pn)
                                val n = createFileCalls.incrementAndGet()
                                if (n == 1) {
                                    // First POST → 409 (simulates path-already-exists)
                                    respond(
                                        content = """{"statusCode":409,"message":"File already exists","error":"Conflict"}""",
                                        status = HttpStatusCode.Conflict,
                                        headers = headersOf("Content-Type", "application/json"),
                                    )
                                } else {
                                    // Second POST → conflict copy succeeds
                                    respond(
                                        content = """{"uuid":"conflict-copy-uuid","plainName":"$pn","type":"md","size":"200","bucket":"6928426c1a2316b856c9ab81","fileId":"$localFileId","status":"EXISTS"}""",
                                        status = HttpStatusCode.OK,
                                        headers = headersOf("Content-Type", "application/json"),
                                    )
                                }
                            }
                            // PUT /drive/files/{uuid} — replaceFile
                            url.startsWith("https://gateway.internxt.com/drive/files/") &&
                                request.method == HttpMethod.Put -> {
                                replaceFileCalls.incrementAndGet()
                                respond(
                                    content = """{"uuid":"replaced-uuid","plainName":"AGENTS","type":"md","size":"200","bucket":"6928426c1a2316b856c9ab81","fileId":"$localFileId","status":"EXISTS"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                            // GET /drive/folders/content/{uuid} — used by the UD-366 fallback
                            // to inspect the existing remote file's content signal.
                            url.contains("/drive/folders/content/") -> {
                                folderContentCalls.incrementAndGet()
                                // Remote has DIFFERENT fileId AND different size → not identical
                                respond(
                                    content =
                                        """{"children":[],"files":[{
                                            "uuid":"existing-file-uuid",
                                            "fileId":"$remoteFileId",
                                            "plainName":"AGENTS",
                                            "type":"md",
                                            "size":"31744",
                                            "bucket":"6928426c1a2316b856c9ab81",
                                            "folderUuid":"root-folder-uuid",
                                            "status":"EXISTS"
                                        }]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                            else -> error("unexpected URL in collision-diff test: $url (method=${request.method})")
                        }
                    }

                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(provider, engine)
                    provider.upload(local, "/AGENTS.md", existingRemoteId = null, onProgress = null)

                    // THE non-negotiable assertion: the 31 KB remote MUST NOT be overwritten.
                    assertEquals(
                        0,
                        replaceFileCalls.get(),
                        "REGRESSION GUARD: replaceFile (PUT) must NEVER be called when remote content differs — " +
                            "calling it would silently destroy the 31 KB remote file",
                    )

                    // Local content must land as a conflict copy (second POST /files).
                    assertEquals(
                        2,
                        createFileCalls.get(),
                        "createFile must be called twice: once for the original path (→ 409) " +
                            "and once for the conflict-suffixed copy",
                    )

                    // The second POST must carry a conflict-suffix in plainName.
                    assertTrue(
                        capturedCreatePlainNames.size == 2,
                        "exactly two POST /files calls captured",
                    )
                    val conflictPlainName = capturedCreatePlainNames[1]
                    assertTrue(
                        conflictPlainName.contains("conflict", ignoreCase = true),
                        "conflict copy plainName must contain 'conflict'; got: '$conflictPlainName'",
                    )
                    assertFalse(
                        conflictPlainName == capturedCreatePlainNames[0],
                        "conflict copy plainName must differ from the original",
                    )

                    // getFolderContents must have been called to inspect the remote.
                    assertTrue(
                        folderContentCalls.get() >= 1,
                        "folders/content must be queried to read the remote file's content signal",
                    )
                } finally {
                    provider.close()
                }
            } finally {
                local.toFile().deleteRecursively()
                tmp.toFile().deleteRecursively()
            }
        }

    // -------------------------------------------------------------------------
    // TEST 2
    // -------------------------------------------------------------------------

    /**
     * Invariant guarded: when the remote file is provably byte-identical to
     * the local upload (same bucket-entry fileId AND same size), the adopt
     * path (replaceFile) is used and NO conflict copy is created.
     *
     * This is safe because equal fileId + size ⇒ the same encrypted blob is
     * already registered on the remote — replaceFile is a no-op-equivalent
     * re-link, not a destructive overwrite.
     */
    @Test
    fun `create_collision_with_identical_remote_adopts_no_conflict_copy`() =
        runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-coll-ident-")
            val local =
                java.nio.file.Files
                    .createTempFile(tmp, "notes-", ".txt")
                    .also { p -> java.nio.file.Files.write(p, ByteArray(64) { 0x41 }) }
            try {
                val replaceFileCalls = AtomicInteger(0)
                val createFileCalls = AtomicInteger(0)

                val engine =
                    io.ktor.client.engine.mock.MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            url.contains("/v2/buckets/") && url.contains("/files/start") ->
                                respond(
                                    content = """{"uploads":[{"index":0,"uuid":"shard-u","url":"$shardUrl"}]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            url == shardUrl ->
                                respond("", HttpStatusCode.OK)
                            url.contains("/v2/buckets/") && url.contains("/files/finish") ->
                                respond(
                                    content = """{"id":"$localFileId","index":"${"cc".repeat(32)}","bucket":"6928426c1a2316b856c9ab81","name":"enc-notes"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            url.startsWith("https://gateway.internxt.com/drive/files") &&
                                request.method == HttpMethod.Post -> {
                                val n = createFileCalls.incrementAndGet()
                                if (n == 1) {
                                    respond(
                                        content = """{"statusCode":409,"message":"File already exists","error":"Conflict"}""",
                                        status = HttpStatusCode.Conflict,
                                        headers = headersOf("Content-Type", "application/json"),
                                    )
                                } else {
                                    // Should not be reached for the identical case
                                    respond(
                                        content = """{"uuid":"x","plainName":"notes","type":"txt","size":"64","bucket":"6928426c1a2316b856c9ab81","fileId":"$localFileId","status":"EXISTS"}""",
                                        status = HttpStatusCode.OK,
                                        headers = headersOf("Content-Type", "application/json"),
                                    )
                                }
                            }
                            url.startsWith("https://gateway.internxt.com/drive/files/") &&
                                request.method == HttpMethod.Put -> {
                                replaceFileCalls.incrementAndGet()
                                respond(
                                    content = """{"uuid":"existing-file-uuid","plainName":"notes","type":"txt","size":"64","bucket":"6928426c1a2316b856c9ab81","fileId":"$localFileId","status":"EXISTS"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                            url.contains("/drive/folders/content/") ->
                                // IDENTICAL: same fileId as what we just uploaded, same size (64)
                                respond(
                                    content =
                                        """{"children":[],"files":[{
                                            "uuid":"existing-file-uuid",
                                            "fileId":"$localFileId",
                                            "plainName":"notes",
                                            "type":"txt",
                                            "size":"64",
                                            "bucket":"6928426c1a2316b856c9ab81",
                                            "folderUuid":"root-folder-uuid",
                                            "status":"EXISTS"
                                        }]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            else -> error("unexpected URL in collision-ident test: $url")
                        }
                    }

                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(provider, engine)
                    provider.upload(local, "/notes.txt", existingRemoteId = null, onProgress = null)

                    assertEquals(
                        1,
                        replaceFileCalls.get(),
                        "identical content → adopt via replaceFile exactly once",
                    )
                    assertEquals(
                        1,
                        createFileCalls.get(),
                        "only the original 409 POST; no second POST for a conflict copy",
                    )
                } finally {
                    provider.close()
                }
            } finally {
                local.toFile().deleteRecursively()
                tmp.toFile().deleteRecursively()
            }
        }

    // -------------------------------------------------------------------------
    // TEST 3
    // -------------------------------------------------------------------------

    /**
     * Invariant guarded: when the existing item at the conflicting path is a
     * FOLDER (not a file), the original 409 exception is re-thrown unchanged.
     * The folder must not be touched and no conflict copy is created.
     */
    @Test
    fun `create_collision_on_folder_path_still_throws`() =
        runTest {
            val tmp = java.nio.file.Files.createTempDirectory("ud-coll-folder-")
            val local =
                java.nio.file.Files
                    .createTempFile(tmp, "thing-", ".bin")
                    .also { p -> java.nio.file.Files.write(p, ByteArray(8) { 0x00 }) }
            try {
                val replaceFileCalls = AtomicInteger(0)
                val createFileCallsAfterFirst = AtomicInteger(0)

                val engine =
                    io.ktor.client.engine.mock.MockEngine { request ->
                        val url = request.url.toString()
                        when {
                            url.contains("/v2/buckets/") && url.contains("/files/start") ->
                                respond(
                                    content = """{"uploads":[{"index":0,"uuid":"shard-f","url":"$shardUrl"}]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            url == shardUrl ->
                                respond("", HttpStatusCode.OK)
                            url.contains("/v2/buckets/") && url.contains("/files/finish") ->
                                respond(
                                    content = """{"id":"local-fid","index":"${"dd".repeat(32)}","bucket":"6928426c1a2316b856c9ab81","name":"enc"}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            url.startsWith("https://gateway.internxt.com/drive/files") &&
                                request.method == HttpMethod.Post -> {
                                // First call is the original → 409; any subsequent POST
                                // (should not happen) increments a separate counter.
                                val bodyText =
                                    when (val b = request.body) {
                                        is io.ktor.http.content.ByteArrayContent ->
                                            String(b.bytes(), Charsets.UTF_8)
                                        is io.ktor.http.content.TextContent -> b.text
                                        else -> "{}"
                                    }
                                val pn =
                                    Json.parseToJsonElement(bodyText).jsonObject["plainName"]
                                        ?.toString()?.trim('"') ?: ""
                                // Count any POST after the first (would be a wrongly-created conflict copy)
                                if (pn.contains("conflict")) createFileCallsAfterFirst.incrementAndGet()
                                respond(
                                    content = """{"statusCode":409,"message":"File already exists","error":"Conflict"}""",
                                    status = HttpStatusCode.Conflict,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                            url.startsWith("https://gateway.internxt.com/drive/files/") &&
                                request.method == HttpMethod.Put -> {
                                replaceFileCalls.incrementAndGet()
                                respond("", HttpStatusCode.OK)
                            }
                            url.contains("/drive/folders/content/") ->
                                // The path "thing.bin" resolves as a FOLDER (edge case but possible
                                // if a folder has that name)
                                respond(
                                    content =
                                        """{"children":[{
                                            "uuid":"folder-uuid-existing",
                                            "plainName":"thing.bin",
                                            "name":"enc-thing",
                                            "status":"EXISTS"
                                        }],"files":[]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            else -> error("unexpected URL in collision-folder test: $url")
                        }
                    }

                val provider = newProviderRooted(tmp)
                try {
                    installMockClientOnProvider(provider, engine)
                    kotlin.test.assertFailsWith<InternxtApiException> {
                        provider.upload(local, "/thing.bin", existingRemoteId = null, onProgress = null)
                    }
                    assertEquals(0, replaceFileCalls.get(), "replaceFile must not be called for a folder conflict")
                    assertEquals(0, createFileCallsAfterFirst.get(), "no conflict copy must be created for a folder conflict")
                } finally {
                    provider.close()
                }
            } finally {
                local.toFile().deleteRecursively()
                tmp.toFile().deleteRecursively()
            }
        }

    // -------------------------------------------------------------------------
    // archive#1002: same size, different bucket entry — the bytes decide
    // -------------------------------------------------------------------------

    private class SameSizeCollision(
        val localBytes: ByteArray,
        val remoteBytes: ByteArray,
        val bridgeInfoStatus: HttpStatusCode = HttpStatusCode.OK,
        val compareCapBytes: Long? = null,
    ) {
        val createPlainNames: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf<String>())
        val replaceFileCalls = AtomicInteger(0)
        val shardDownloads = AtomicInteger(0)
    }

    private val bucketId = "6928426c1a2316b856c9ab81"
    private val testMnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val remoteIndex = "11".repeat(32)

    // An upload of [SameSizeCollision.localBytes] to /report.pdf whose create answers 409 because the folder already
    // holds a report.pdf of the same size under another bucket entry; that remote decrypts to remoteBytes.
    private suspend fun uploadIntoSameSizeCollision(case: SameSizeCollision): org.krost.unidrive.CloudItem {
        val crypto = InternxtCrypto()
        val indexBytes = InternxtCrypto.hexToBytes(remoteIndex)
        val fileKey = crypto.deriveFileKey(crypto.deriveBucketKey(crypto.mnemonicToSeed(testMnemonic), bucketId), indexBytes)
        val stored = crypto.createContentEncryptCipher(fileKey, indexBytes.copyOfRange(0, 16)).doFinal(case.remoteBytes)
        val storedSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(stored).joinToString("") { "%02x".format(it) }
        val size = case.remoteBytes.size
        val json = headersOf("Content-Type", "application/json")
        val engine =
            io.ktor.client.engine.mock.MockEngine { request ->
                val url = request.url.toString()
                val path = request.url.encodedPath
                when {
                    url.contains("/v2/buckets/") && url.contains("/files/start") ->
                        respond("""{"uploads":[{"index":0,"uuid":"shard-s","url":"$shardUrl"}]}""", HttpStatusCode.OK, json)
                    url == shardUrl && request.method == HttpMethod.Put -> respond("", HttpStatusCode.OK)
                    url.contains("/v2/buckets/") && url.contains("/files/finish") ->
                        respond("""{"id":"$localFileId","index":"${"ee".repeat(32)}","bucket":"$bucketId","name":"enc"}""", HttpStatusCode.OK, json)
                    path == "/drive/files" && request.method == HttpMethod.Post -> {
                        val body = (request.body as? io.ktor.http.content.TextContent)?.text
                            ?: String((request.body as io.ktor.http.content.ByteArrayContent).bytes(), Charsets.UTF_8)
                        val pn = Json.parseToJsonElement(body).jsonObject["plainName"].toString().trim('"')
                        case.createPlainNames += pn
                        if (case.createPlainNames.size == 1) {
                            respond("""{"statusCode":409,"message":"File already exists","error":"Conflict"}""", HttpStatusCode.Conflict, json)
                        } else {
                            respond(
                                """{"uuid":"conflict-copy-uuid","plainName":"$pn","type":"pdf","size":"$size","bucket":"$bucketId","fileId":"$localFileId","status":"EXISTS"}""",
                                HttpStatusCode.OK,
                                json,
                            )
                        }
                    }
                    path.startsWith("/drive/files/") && request.method == HttpMethod.Put -> {
                        case.replaceFileCalls.incrementAndGet()
                        respond(
                            """{"uuid":"existing-file-uuid","plainName":"report","type":"pdf","size":"$size","bucket":"$bucketId","fileId":"$localFileId","status":"EXISTS"}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    }
                    path.contains("/drive/folders/content/") ->
                        respond(
                            """{"children":[],"files":[{"uuid":"existing-file-uuid","fileId":"$remoteFileId","plainName":"report","type":"pdf",
                                "size":"$size","bucket":"$bucketId","folderUuid":"root-folder-uuid","status":"EXISTS"}]}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    path == "/drive/files/existing-file-uuid/meta" && request.method == HttpMethod.Get ->
                        respond(
                            """{"uuid":"existing-file-uuid","bucket":"$bucketId","fileId":"$remoteFileId","size":"$size","status":"EXISTS"}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    path == "/buckets/$bucketId/files/$remoteFileId/info" && request.method == HttpMethod.Get ->
                        if (case.bridgeInfoStatus == HttpStatusCode.OK) {
                            respond(
                                """{"bucket":"$bucketId","id":"$remoteFileId","index":"$remoteIndex","shards":[{"index":0,"hash":"$storedSha256","url":"https://shard-host.invalid/get/remote"}]}""",
                                HttpStatusCode.OK,
                                json,
                            )
                        } else {
                            respond("""{"error":"not found"}""", case.bridgeInfoStatus, json)
                        }
                    url == "https://shard-host.invalid/get/remote" && request.method == HttpMethod.Get -> {
                        case.shardDownloads.incrementAndGet()
                        respond(stored, HttpStatusCode.OK, headersOf("Content-Type", "application/octet-stream"))
                    }
                    else -> error("unexpected request in same-size collision test: ${request.method} $url")
                }
            }
        val tmp = java.nio.file.Files.createTempDirectory("ud-coll-same-size-")
        val local = tmp.resolve("report.pdf").also { java.nio.file.Files.write(it, case.localBytes) }
        val provider = newProviderRooted(tmp)
        try {
            installMockClientOnProvider(provider, engine)
            case.compareCapBytes?.let { provider.collisionCompareMaxBytes = it }
            return provider.upload(local, "/report.pdf", existingRemoteId = null, onProgress = null)
        } finally {
            provider.close()
            tmp.toFile().deleteRecursively()
        }
    }

    // archive#1002: a fresh upload always lands in a new bucket entry, so a remote that already holds the very same bytes
    // never shares its fileId. That case was duplicated as "(conflict <date>)"; the content decides now.
    @Test
    fun `create collision with a same-size remote of the same content adopts it and makes no conflict copy`() =
        runTest {
            val bytes = "the same quarterly figures".toByteArray()
            val case = SameSizeCollision(localBytes = bytes, remoteBytes = bytes.copyOf())

            val item = uploadIntoSameSizeCollision(case)

            assertEquals(listOf("report"), case.createPlainNames, "only the create that hit 409; no conflict copy")
            assertEquals(0, case.replaceFileCalls.get(), "the remote is adopted as it is, not replaced")
            assertEquals(1, case.shardDownloads.get(), "the remote was read to compare it")
            assertEquals("existing-file-uuid", item.id, "the row takes the existing remote file")
        }

    @Test
    fun `create collision with a same-size remote of different content still keeps both`() =
        runTest {
            val case =
                SameSizeCollision(
                    localBytes = "the local quarterly figures".toByteArray(),
                    remoteBytes = "the cloud quarterly figures".toByteArray(),
                )

            val item = uploadIntoSameSizeCollision(case)

            assertEquals(2, case.createPlainNames.size, "the 409 create, then the conflict copy: ${case.createPlainNames}")
            assertTrue("conflict" in case.createPlainNames[1], "copy name: ${case.createPlainNames[1]}")
            assertEquals(0, case.replaceFileCalls.get(), "the differing remote must never be replaced")
            assertEquals("conflict-copy-uuid", item.id)
        }

    @Test
    fun `the collision compare is capped at 256 MiB`() {
        assertEquals(256L * 1024 * 1024, InternxtProvider.COLLISION_COMPARE_MAX_BYTES)
    }

    // Above the cap the compare would cost a big download: it is skipped and both are kept, as before the compare.
    // The cap is lowered to the test file's size so no 256 MiB file is needed.
    @Test
    fun `create collision with a same-size remote above the compare cap keeps both without reading it`() =
        runTest {
            val bytes = "the same quarterly figures".toByteArray()
            val case = SameSizeCollision(localBytes = bytes, remoteBytes = bytes.copyOf(), compareCapBytes = bytes.size - 1L)

            val item = uploadIntoSameSizeCollision(case)

            assertEquals(0, case.shardDownloads.get(), "a remote above the cap is not downloaded")
            assertEquals(2, case.createPlainNames.size, "above the cap both are kept: ${case.createPlainNames}")
            assertEquals(0, case.replaceFileCalls.get())
            assertEquals("conflict-copy-uuid", item.id)
        }

    @Test
    fun `create collision with a same-size remote at the compare cap is still compared and adopted`() =
        runTest {
            val bytes = "the same quarterly figures".toByteArray()
            val case = SameSizeCollision(localBytes = bytes, remoteBytes = bytes.copyOf(), compareCapBytes = bytes.size.toLong())

            val item = uploadIntoSameSizeCollision(case)

            assertEquals(1, case.shardDownloads.get(), "at the cap the remote is read to compare it")
            assertEquals(listOf("report"), case.createPlainNames, "no conflict copy")
            assertEquals("existing-file-uuid", item.id)
        }

    @Test
    fun `create collision whose remote cannot be read keeps both rather than guessing`() =
        runTest {
            val bytes = "the same quarterly figures".toByteArray()
            val case = SameSizeCollision(localBytes = bytes, remoteBytes = bytes.copyOf(), bridgeInfoStatus = HttpStatusCode.NotFound)

            val item = uploadIntoSameSizeCollision(case)

            assertEquals(2, case.createPlainNames.size, "an unproven match keeps both: ${case.createPlainNames}")
            assertEquals(0, case.replaceFileCalls.get())
            assertEquals("conflict-copy-uuid", item.id)
        }

    // The bytes that decide a create collision are the live exact-name file's. A same-size
    // `Makefile.am` (plainName `Makefile`, type `am`) listed first is a different file.
    @Test
    fun `create collision adopts the exact Makefile not the same-size am sibling listed first`() =
        runTest {
            val bytes = "the makefile body exactly".toByteArray()
            val adopted =
                uploadMakefileBesideAm(
                    localBytes = bytes,
                    exactBytes = bytes.copyOf(),
                    siblingBytes = bytes.copyOf(),
                )

            assertEquals(listOf("Makefile"), adopted.createPlainNames, "only the create that hit 409; no conflict copy")
            assertEquals(0, adopted.replaceFileCalls.get(), "the exact file is adopted as it is, not replaced")
            assertEquals("makefile-uuid", adopted.item.id, "the row takes Makefile, not the am sibling listed first")
        }

    @Test
    fun `create collision adopts the exact file when the listed-first sibling content differs`() =
        runTest {
            val local = "local makefile bytes!!".toByteArray()
            val sibling = "other makefile bytes!!".toByteArray()
            check(local.size == sibling.size)
            val adopted =
                uploadMakefileBesideAm(
                    localBytes = local,
                    exactBytes = local.copyOf(),
                    siblingBytes = sibling,
                )

            assertEquals(listOf("Makefile"), adopted.createPlainNames, "a differing sibling must not force a conflict copy")
            assertEquals(0, adopted.replaceFileCalls.get(), "the differing sibling must not be replaced either")
            assertEquals("makefile-uuid", adopted.item.id)
        }

    private class MakefileBesideAm(
        val createPlainNames: MutableList<String>,
        val replaceFileCalls: AtomicInteger,
        val item: org.krost.unidrive.CloudItem,
    )

    // Upload of [localBytes] to /Makefile. The folder lists `Makefile.am` before an exact
    // `Makefile`; both are the same size. Each decrypts to the bytes named here.
    private suspend fun uploadMakefileBesideAm(
        localBytes: ByteArray,
        exactBytes: ByteArray,
        siblingBytes: ByteArray,
    ): MakefileBesideAm {
        val exactFileId = "exact-makefile-entry"
        val siblingFileId = "sibling-am-entry"
        val exactIndex = "33".repeat(32)
        val siblingIndex = "44".repeat(32)
        val exactStored = encryptPlaintext(exactBytes, exactIndex)
        val siblingStored = encryptPlaintext(siblingBytes, siblingIndex)
        val size = localBytes.size
        check(exactBytes.size == size && siblingBytes.size == size)
        val createPlainNames = java.util.Collections.synchronizedList(mutableListOf<String>())
        val replaceFileCalls = AtomicInteger(0)
        val json = headersOf("Content-Type", "application/json")
        val engine =
            io.ktor.client.engine.mock.MockEngine { request ->
                val url = request.url.toString()
                val path = request.url.encodedPath
                when {
                    url.contains("/v2/buckets/") && url.contains("/files/start") ->
                        respond("""{"uploads":[{"index":0,"uuid":"shard-s","url":"$shardUrl"}]}""", HttpStatusCode.OK, json)
                    url == shardUrl && request.method == HttpMethod.Put -> respond("", HttpStatusCode.OK)
                    url.contains("/v2/buckets/") && url.contains("/files/finish") ->
                        respond(
                            """{"id":"$localFileId","index":"${"ee".repeat(32)}","bucket":"$bucketId","name":"enc"}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    path == "/drive/files" && request.method == HttpMethod.Post -> {
                        val body = (request.body as? io.ktor.http.content.TextContent)?.text
                            ?: String((request.body as io.ktor.http.content.ByteArrayContent).bytes(), Charsets.UTF_8)
                        val pn = Json.parseToJsonElement(body).jsonObject["plainName"].toString().trim('"')
                        createPlainNames += pn
                        if (createPlainNames.size == 1) {
                            respond(
                                """{"statusCode":409,"message":"File already exists","error":"Conflict"}""",
                                HttpStatusCode.Conflict,
                                json,
                            )
                        } else {
                            respond(
                                """{"uuid":"conflict-copy-uuid","plainName":"$pn","type":null,"size":"$size","bucket":"$bucketId","fileId":"$localFileId","status":"EXISTS"}""",
                                HttpStatusCode.OK,
                                json,
                            )
                        }
                    }
                    path.startsWith("/drive/files/") && request.method == HttpMethod.Put -> {
                        replaceFileCalls.incrementAndGet()
                        respond(
                            """{"uuid":"makefile-uuid","plainName":"Makefile","type":null,"size":"$size","bucket":"$bucketId","fileId":"$localFileId","status":"EXISTS"}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    }
                    path.contains("/drive/folders/content/") ->
                        respond(
                            """{"children":[],"files":[
                                {"uuid":"makefile-am-uuid","fileId":"$siblingFileId","plainName":"Makefile","type":"am",
                                    "size":"$size","bucket":"$bucketId","folderUuid":"root-folder-uuid","status":"EXISTS"},
                                {"uuid":"makefile-uuid","fileId":"$exactFileId","plainName":"Makefile","type":null,
                                    "size":"$size","bucket":"$bucketId","folderUuid":"root-folder-uuid","status":"EXISTS"}
                            ]}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    path == "/drive/files/makefile-uuid/meta" && request.method == HttpMethod.Get ->
                        respond(
                            """{"uuid":"makefile-uuid","bucket":"$bucketId","fileId":"$exactFileId","size":"$size","status":"EXISTS"}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    path == "/drive/files/makefile-am-uuid/meta" && request.method == HttpMethod.Get ->
                        respond(
                            """{"uuid":"makefile-am-uuid","bucket":"$bucketId","fileId":"$siblingFileId","size":"$size","status":"EXISTS"}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    path == "/buckets/$bucketId/files/$exactFileId/info" && request.method == HttpMethod.Get ->
                        respond(
                            """{"bucket":"$bucketId","id":"$exactFileId","index":"$exactIndex","shards":[{"index":0,"hash":"${exactStored.sha256}","url":"https://shard-host.invalid/get/makefile"}]}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    path == "/buckets/$bucketId/files/$siblingFileId/info" && request.method == HttpMethod.Get ->
                        respond(
                            """{"bucket":"$bucketId","id":"$siblingFileId","index":"$siblingIndex","shards":[{"index":0,"hash":"${siblingStored.sha256}","url":"https://shard-host.invalid/get/makefile-am"}]}""",
                            HttpStatusCode.OK,
                            json,
                        )
                    url == "https://shard-host.invalid/get/makefile" && request.method == HttpMethod.Get ->
                        respond(exactStored.ciphertext, HttpStatusCode.OK, headersOf("Content-Type", "application/octet-stream"))
                    url == "https://shard-host.invalid/get/makefile-am" && request.method == HttpMethod.Get ->
                        respond(siblingStored.ciphertext, HttpStatusCode.OK, headersOf("Content-Type", "application/octet-stream"))
                    else -> error("unexpected request in makefile collision test: ${request.method} $url")
                }
            }
        val tmp = java.nio.file.Files.createTempDirectory("ud-coll-makefile-")
        val local = tmp.resolve("Makefile").also { java.nio.file.Files.write(it, localBytes) }
        val provider = newProviderRooted(tmp)
        try {
            installMockClientOnProvider(provider, engine)
            val item = provider.upload(local, "/Makefile", existingRemoteId = null, onProgress = null)
            return MakefileBesideAm(createPlainNames, replaceFileCalls, item)
        } finally {
            provider.close()
            tmp.toFile().deleteRecursively()
        }
    }

    private class StoredCiphertext(val ciphertext: ByteArray, val sha256: String)

    private fun encryptPlaintext(bytes: ByteArray, indexHex: String): StoredCiphertext {
        val crypto = InternxtCrypto()
        val indexBytes = InternxtCrypto.hexToBytes(indexHex)
        val fileKey =
            crypto.deriveFileKey(
                crypto.deriveBucketKey(crypto.mnemonicToSeed(testMnemonic), bucketId),
                indexBytes,
            )
        val stored = crypto.createContentEncryptCipher(fileKey, indexBytes.copyOfRange(0, 16)).doFinal(bytes)
        val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(stored).joinToString("") { "%02x".format(it) }
        return StoredCiphertext(stored, sha256)
    }

    // -------------------------------------------------------------------------
    // TEST 4
    // -------------------------------------------------------------------------

    /**
     * Invariant guarded: the conflict-name helper [InternxtProvider.conflictName]
     * appends a numeric counter suffix to avoid a second collision when the
     * first conflict-name is itself already taken.
     *
     * Naming contract:
     *   base = "$plainName (conflict $date)"
     *   counter 1 → base            (e.g. "AGENTS (conflict 2026-05-26)")
     *   counter 2 → "$base (2)"     (e.g. "AGENTS (conflict 2026-05-26) (2)")
     *   counter 3 → "$base (3)"     …
     */
    @Test
    fun `conflict_name_helper_appends_counter_on_second_collision`() {
        val date = "2026-05-26"
        val first = InternxtProvider.conflictName("AGENTS", date, 1)
        val second = InternxtProvider.conflictName("AGENTS", date, 2)
        val third = InternxtProvider.conflictName("AGENTS", date, 3)

        assertEquals("AGENTS (conflict $date)", first)
        assertEquals("AGENTS (conflict $date) (2)", second)
        assertEquals("AGENTS (conflict $date) (3)", third)
    }
}
