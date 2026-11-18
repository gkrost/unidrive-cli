package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A provider over [engine] with test credentials, its staging below [stateDir]; nothing is written outside the test's directory. */
internal fun rootedInternxtProvider(
    stateDir: Path,
    engine: MockEngine,
    keepOverwritten: Boolean = false,
    tune: (InternxtConfig) -> InternxtConfig = { it },
): InternxtProvider {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
    return InternxtProvider(
        tune(InternxtConfig(credentialProtector = null, tokenPath = stateDir, keepOverwritten = keepOverwritten)),
        httpClient = HttpClient(engine),
        credentials =
            InternxtCredentials(
                jwt = "header.$payload.signature",
                mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = "6928426c1a2316b856c9ab81",
            ),
    )
}

/** One file of an [UploadDrive]. */
internal data class UploadDriveFile(
    val uuid: String,
    val plainName: String,
    val type: String?,
    val size: Long,
    val fileId: String,
    val folderUuid: String,
    val updatedAt: String = "2026-10-01T10:00:00.000Z",
)

/** One folder of an [UploadDrive]. */
internal data class UploadDriveFolder(
    val uuid: String,
    val plainName: String,
    val parentUuid: String,
    val updatedAt: String = "2026-10-01T10:00:00.000Z",
    /** The server's status word for the folder; a trashed folder still holds its name against a create. */
    val status: String = "EXISTS",
)

/**
 * A drive that takes uploads and folders: the bridge calls (start, shard PUT, finish) answer, `POST /files` stores the file
 * with the names it was given and answers them back, `POST /folders` does the same for folders (one or a batch), and the
 * folder listing shows what is stored. A second file with the same plainName and type in one folder is refused with 409, as
 * the server does. The change feed offered is the offset listing only (the sync listings answer 404). A test changes the
 * drive behind the provider's back by editing [files] and [folders].
 */
internal class UploadDrive(
    private val bucketEntryId: String = "bucket-entry-1",
    /** The server's own clock: when set, every answer carries it as its `Date` header, and `GET /users/usage` answers. */
    private val serverClock: (() -> Instant)? = null,
) {
    val files = CopyOnWriteArrayList<UploadDriveFile>()
    val folders = CopyOnWriteArrayList<UploadDriveFolder>()
    val createBodies = CopyOnWriteArrayList<Pair<String, String?>>()
    val renames = CopyOnWriteArrayList<Triple<String, String, String?>>()
    val requests = CopyOnWriteArrayList<String>()

    /** The body length of every shard PUT, -1 when the body does not say. */
    val shardPutLengths = CopyOnWriteArrayList<Long>()
    val createSizes = CopyOnWriteArrayList<Long>()
    val createCalls = AtomicInteger()

    /** Answers a `POST /files` with this status instead (null lets it through); [call] counts from 1. */
    @Volatile var createFault: ((call: Int, plainName: String) -> HttpStatusCode?)? = null

    /** The body [createFault]'s answer carries instead of the generic one. */
    @Volatile var createFaultBody: String? = null

    /** The file is stored although [createFault] answers an error: the commit landed and the response was lost. */
    @Volatile var storeBeforeFault: Boolean = false

    /** Answers a replace (`PUT /files/{uuid}`) with this status instead (null lets it through); [call] counts from 1. */
    @Volatile var replaceFault: ((call: Int) -> HttpStatusCode?)? = null

    /** The replace is stored although [replaceFault] answers an error. */
    @Volatile var storeBeforeReplaceFault: Boolean = false

    /** The server answers a replace with 200 and keeps the old fileId (a write that did not take). */
    @Volatile var replaceKeepsOldFileId: Boolean = false

    /** The fileId of every replace sent, in order. */
    val replaceFileIds = CopyOnWriteArrayList<String?>()
    private val replaceCalls = AtomicInteger()

    /** Answers every upload start (`files/start`) with this status and body instead (null lets it through). */
    @Volatile var startRefusal: Pair<HttpStatusCode, String>? = null
    val startCalls = AtomicInteger()

    /** The stored objects the bridge holds (its file info answers); null: every object exists. */
    @Volatile var bridgeObjects: MutableSet<String>? = null

    /**
     * Folder uuid -> how many listings of its parent still leave it out (the combined call and the cursor stream of the folders
     * both count, one per call): a listing that lags behind a create or a restore, as the real one does for 1-3 s.
     */
    val listingLag = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Answers every cursor listing of a folder's children with this status instead (null lets it through). */
    @Volatile var pagedListingFault: HttpStatusCode? = null

    /** Runs before every single `POST /folders` is answered; [call] counts from 1. A test changes the drive from here. */
    @Volatile var onFolderCreate: ((call: Int) -> Unit)? = null
    private val folderCreateCalls = AtomicInteger()

    /** The uuids `POST /storage/trash/add` was asked to trash. The drive keeps listing them as they were (the trash lag). */
    val trashed = CopyOnWriteArrayList<String>()

    /** The order in which a batch `POST /folders` answers its folders. */
    @Volatile var batchAnswerOrder: (List<UploadDriveFolder>) -> List<UploadDriveFolder> = { it }

    private val shardUrl = "https://shard-host.invalid/put"
    private val jsonHeaders
        get() =
            serverClock?.let {
                headersOf(
                    "Content-Type" to listOf("application/json"),
                    "Date" to listOf(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(it().atZone(java.time.ZoneOffset.UTC))),
                )
            } ?: headersOf("Content-Type", "application/json")

    private fun fileJson(f: UploadDriveFile) =
        "{\"uuid\":\"${f.uuid}\",\"plainName\":${JsonPrimitive(f.plainName)},\"type\":${f.type?.let { JsonPrimitive(it).toString() } ?: "null"}," +
            "\"size\":\"${f.size}\",\"fileId\":\"${f.fileId}\",\"bucket\":\"6928426c1a2316b856c9ab81\",\"folderUuid\":\"${f.folderUuid}\"," +
            "\"status\":\"EXISTS\",\"creationTime\":\"2026-10-01T10:00:00.000Z\",\"modificationTime\":\"2026-10-01T10:00:00.000Z\"," +
            "\"updatedAt\":\"${f.updatedAt}\"}"

    // The folders below [parent] this listing shows: a folder in [listingLag] is left out and counts the listing off.
    private fun listedFolders(parent: String): List<UploadDriveFolder> =
        folders.filter { it.parentUuid == parent }.filter { f ->
            val hidden = listingLag[f.uuid] ?: 0
            if (hidden > 0) listingLag[f.uuid] = hidden - 1
            hidden == 0
        }

    private fun folderJson(f: UploadDriveFolder) =
        "{\"uuid\":\"${f.uuid}\",\"plainName\":${JsonPrimitive(f.plainName)},\"parentUuid\":\"${f.parentUuid}\",\"status\":\"${f.status}\"," +
            "\"modificationTime\":\"2026-10-01T10:00:00.000Z\",\"updatedAt\":\"${f.updatedAt}\"}"

    val engine =
        MockEngine { request ->
            val url = request.url.toString()
            val path = request.url.encodedPath
            requests += "${request.method.value} $path"

            fun body(): JsonObject {
                val text =
                    when (val b = request.body) {
                        is ByteArrayContent -> String(b.bytes(), Charsets.UTF_8)
                        is TextContent -> b.text
                        else -> "{}"
                    }
                return Json.parseToJsonElement(text).jsonObject
            }

            fun notFound() = respond("""{"statusCode":404}""", HttpStatusCode.NotFound, jsonHeaders)
            when {
                url.contains("/v2/buckets/") && url.contains("/files/start") && startCalls.incrementAndGet() > 0 && startRefusal != null ->
                    respond(startRefusal!!.second, startRefusal!!.first, jsonHeaders)
                url.contains("/v2/buckets/") && url.contains("/files/start") ->
                    respond("""{"uploads":[{"index":0,"uuid":"shard-uuid-1","url":"$shardUrl"}]}""", HttpStatusCode.OK, jsonHeaders)
                url == shardUrl -> {
                    shardPutLengths += request.body.contentLength ?: -1L
                    respond("", HttpStatusCode.OK)
                }
                request.method == HttpMethod.Get && Regex("/buckets/[^/]+/files/([^/]+)/info").containsMatchIn(path) -> {
                    val id = Regex("/files/([^/]+)/info").find(path)!!.groupValues[1]
                    if (bridgeObjects?.contains(id) == false) {
                        respond("""{"error":"Bucket entry $id not found"}""", HttpStatusCode.NotFound, jsonHeaders)
                    } else {
                        respond("""{"bucket":"6928426c1a2316b856c9ab81","id":"$id","index":"${"aa".repeat(32)}","shards":[]}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
                url.contains("/v2/buckets/") && url.contains("/files/finish") ->
                    respond(
                        """{"id":"$bucketEntryId","index":"${"aa".repeat(32)}","bucket":"6928426c1a2316b856c9ab81","name":"enc"}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                request.method == HttpMethod.Post && path.endsWith("/drive/files") -> {
                    val b = body()
                    val plain = b.getValue("plainName").jsonPrimitive.content
                    val type = b["type"]?.jsonPrimitive?.contentOrNull
                    val folder = b.getValue("folderUuid").jsonPrimitive.content
                    createBodies += plain to type
                    createSizes += b.getValue("size").jsonPrimitive.content.toLong()
                    val fault = createFault?.invoke(createCalls.incrementAndGet(), plain)
                    val clash = files.any { it.folderUuid == folder && it.plainName == plain && it.type == type }
                    val stored =
                        UploadDriveFile(
                            "file-${files.size + 1}",
                            plain,
                            type,
                            b.getValue("size").jsonPrimitive.content.toLong(),
                            b["fileId"]?.jsonPrimitive?.contentOrNull ?: "",
                            folder,
                        )
                    if (fault != null && storeBeforeFault && !clash) files += stored
                    when {
                        fault != null -> respond(createFaultBody ?: """{"statusCode":${fault.value},"message":"injected"}""", fault, jsonHeaders)
                        clash -> respond("""{"message":"File already exists","error":"Conflict","statusCode":409}""", HttpStatusCode.Conflict, jsonHeaders)
                        else -> {
                            files += stored
                            respond(fileJson(stored), HttpStatusCode.OK, jsonHeaders)
                        }
                    }
                }
                request.method == HttpMethod.Post && path.endsWith("/drive/folders") -> {
                    val b = body()
                    val parent = b.getValue("parentFolderUuid").jsonPrimitive.content
                    val names = b["folders"]?.jsonArray?.map { it.jsonObject.getValue("plainName").jsonPrimitive.content }
                    if (names == null) {
                        onFolderCreate?.invoke(folderCreateCalls.incrementAndGet())
                        val name = b.getValue("plainName").jsonPrimitive.content
                        if (folders.any { it.parentUuid == parent && it.plainName == name }) {
                            respond("""{"message":"Folder already exists","statusCode":409}""", HttpStatusCode.Conflict, jsonHeaders)
                        } else {
                            val f = UploadDriveFolder("folder-${folders.size + 1}", name, parent)
                            folders += f
                            respond(folderJson(f), HttpStatusCode.OK, jsonHeaders)
                        }
                    } else {
                        val made = names.map { UploadDriveFolder("folder-${folders.size + 1 + names.indexOf(it)}", it, parent) }
                        folders += made
                        respond(batchAnswerOrder(made).joinToString(",", "{\"result\":[", "]}") { folderJson(it) }, HttpStatusCode.OK, jsonHeaders)
                    }
                }
                request.method == HttpMethod.Post && path.endsWith("/storage/trash/add") -> {
                    body().getValue("items").jsonArray.forEach { trashed += it.jsonObject.getValue("uuid").jsonPrimitive.content }
                    respond("{}", HttpStatusCode.OK, jsonHeaders)
                }
                // Replace in place: the content of a file, its name untouched.
                request.method == HttpMethod.Put && !path.endsWith("/meta") && path.substringBeforeLast('/').endsWith("/drive/files") -> {
                    val old = files.firstOrNull { it.uuid == path.substringAfterLast('/') }
                    if (old == null) {
                        notFound()
                    } else {
                        val b = body()
                        val newFileId = b["fileId"]?.jsonPrimitive?.contentOrNull
                        replaceFileIds += newFileId
                        val replaced = old.copy(size = b.getValue("size").jsonPrimitive.content.toLong(), fileId = newFileId ?: "")
                        val fault = replaceFault?.invoke(replaceCalls.incrementAndGet())
                        when {
                            // The live server refuses a replace that names the file's current object.
                            newFileId != null && newFileId == old.fileId ->
                                respond("""{"message":"New file ID is the same as the old file ID","error":"Bad Request","statusCode":400}""", HttpStatusCode.BadRequest, jsonHeaders)
                            fault != null -> {
                                if (storeBeforeReplaceFault) files[files.indexOf(old)] = replaced
                                respond("""{"statusCode":${fault.value},"message":"injected"}""", fault, jsonHeaders)
                            }
                            replaceKeepsOldFileId -> respond(fileJson(old), HttpStatusCode.OK, jsonHeaders)
                            else -> {
                                files[files.indexOf(old)] = replaced
                                respond(fileJson(replaced), HttpStatusCode.OK, jsonHeaders)
                            }
                        }
                    }
                }
                request.method == HttpMethod.Put && path.endsWith("/meta") && "/files/" in path -> {
                    val uuid = path.substringBeforeLast("/meta").substringAfterLast('/')
                    val b = body()
                    val plain = b.getValue("plainName").jsonPrimitive.content
                    val type = b["type"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null }
                    renames += Triple(uuid, plain, type)
                    val old = files.firstOrNull { it.uuid == uuid }
                    when {
                        old == null -> notFound()
                        files.any { it.uuid != uuid && it.folderUuid == old.folderUuid && it.plainName == plain && it.type == type } ->
                            respond("""{"message":"File already exists","error":"Conflict","statusCode":409}""", HttpStatusCode.Conflict, jsonHeaders)
                        else -> {
                            val renamed = old.copy(plainName = plain, type = type)
                            files[files.indexOf(old)] = renamed
                            respond(fileJson(renamed), HttpStatusCode.OK, jsonHeaders)
                        }
                    }
                }
                request.method == HttpMethod.Get && path.endsWith("/meta") && "/files/" in path -> {
                    val f = files.firstOrNull { it.uuid == path.substringBeforeLast("/meta").substringAfterLast('/') }
                    if (f == null) notFound() else respond(fileJson(f), HttpStatusCode.OK, jsonHeaders)
                }
                request.method == HttpMethod.Get && path.endsWith("/meta") && "/folders/" in path -> {
                    val f = folders.firstOrNull { it.uuid == path.substringBeforeLast("/meta").substringAfterLast('/') }
                    if (f == null) notFound() else respond(folderJson(f), HttpStatusCode.OK, jsonHeaders)
                }
                request.method == HttpMethod.Get && "/drive/folders/v2/content/" in url -> {
                    val (folder, stream) = path.substringAfter("/v2/content/").split('/')
                    val fault = pagedListingFault
                    when {
                        fault != null -> respond("""{"statusCode":${fault.value},"message":"injected"}""", fault, jsonHeaders)
                        stream == "folders" ->
                            respond(listedFolders(folder).joinToString(",", "{\"folders\":[", "],\"nextCursor\":null}") { folderJson(it) }, HttpStatusCode.OK, jsonHeaders)
                        else ->
                            respond(files.filter { it.folderUuid == folder }.joinToString(",", "{\"files\":[", "],\"nextCursor\":null}") { fileJson(it) }, HttpStatusCode.OK, jsonHeaders)
                    }
                }
                request.method == HttpMethod.Get && "/drive/folders/content/" in url -> {
                    val folder = path.substringAfterLast('/')
                    val children = listedFolders(folder).joinToString(",") { folderJson(it) }
                    val listed = files.filter { it.folderUuid == folder }.joinToString(",") { fileJson(it) }
                    respond("""{"children":[$children],"files":[$listed]}""", HttpStatusCode.OK, jsonHeaders)
                }
                // The server does not serve the cursor listings: a gather falls back to the offset listing.
                path.endsWith("/drive/folders/sync") || path.endsWith("/drive/files/sync") -> notFound()
                request.method == HttpMethod.Get && (path.endsWith("/drive/folders") || path.endsWith("/drive/files")) -> {
                    val params = request.url.parameters
                    if ((params["offset"]?.toIntOrNull() ?: 0) > 0) return@MockEngine respond("[]", HttpStatusCode.OK, jsonHeaders)
                    val since = params["updatedAt"]?.let(Instant::parse)
                    val rows =
                        if (path.endsWith("/drive/folders")) {
                            folders.filter { since == null || Instant.parse(it.updatedAt).isAfter(since) }.map(::folderJson)
                        } else {
                            files.filter { since == null || Instant.parse(it.updatedAt).isAfter(since) }.map(::fileJson)
                        }
                    respond(rows.joinToString(",", "[", "]"), HttpStatusCode.OK, jsonHeaders)
                }
                serverClock != null && request.method == HttpMethod.Get && path.endsWith("/users/usage") ->
                    respond("""{"drive":0}""", HttpStatusCode.OK, jsonHeaders)
                else -> error("unexpected request in UploadDrive: ${request.method.value} $url")
            }
        }
}
