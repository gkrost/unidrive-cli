package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ScanContext
import org.krost.unidrive.ScanProgress
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.http.RequestMetricsTap
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// A fake Internxt drive for the tests that run the provider's full listing end to end: the status filter, the keyset
// cursors (a cursor is good for the status it was issued for only), a cursor that can be walled with HTTP 408 for the
// filtered query, and a folder meta that answers HTTP 200 for a trashed folder, as the live server does. The engine's
// staging stands beside it as a test double.

/** A provider over [engine], signed in with test credentials, as the provider tests build it. */
internal fun internxtProviderOn(engine: MockEngine): InternxtProvider {
    val provider = InternxtProvider()
    val auth = InternxtProvider::class.java.getDeclaredField("authService").also { it.isAccessible = true }.get(provider)
    val credsField = auth.javaClass.getDeclaredField("credentials").also { it.isAccessible = true }
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
    credsField.set(
        auth,
        InternxtCredentials(
            jwt = "header.$payload.signature",
            mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            rootFolderId = "root",
            email = "test@example.invalid",
            bridgeUser = "bridge-user",
            bridgeUserId = "bridge-secret",
            bucket = "6928426c1a2316b856c9ab81",
        ),
    )
    val api = InternxtProvider::class.java.getDeclaredField("api").also { it.isAccessible = true }.get(provider)
    val clientField = InternxtApiService::class.java.getDeclaredField("httpClient").also { it.isAccessible = true }
    (clientField.get(api) as? HttpClient)?.close()
    clientField.set(api, HttpClient(engine))
    return provider
}

// ---- the fake drive --------------------------------------------------------------------------------------------

private val fakeJsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
private val fakeMillis = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
private val fakeMicros = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

internal fun fakeAt(time: String) = Instant.parse("2026-07-28T${time}Z")

internal data class FakeFolder(
    val uuid: String,
    val name: String,
    // Null for the top of a tree of its own (a device folder of the desktop app's backups), sent as the server sends it.
    val parent: String?,
    val updatedAt: Instant,
    val status: String = "EXISTS",
)

internal data class FakeFile(
    val uuid: String,
    val name: String,
    val folder: String,
    val updatedAt: Instant,
    val status: String = "EXISTS",
)

private class FakeCursorBody(
    val updatedAt: Instant,
    val uuid: String,
    val status: String?,
)

private fun fakeFolderJson(
    row: FakeFolder,
    withStatus: Boolean = true,
): String {
    val status = if (withStatus) ",\"status\":\"${row.status}\",\"deleted\":${row.status == "TRASHED"},\"removed\":${row.status == "DELETED"}" else ""
    val parent = row.parent?.let { "\"parentUuid\":\"$it\"" } ?: "\"parentId\":null,\"parentUuid\":null"
    return "{\"uuid\":\"${row.uuid}\",\"plainName\":\"${row.name}\",$parent$status," +
        "\"modificationTime\":\"2026-07-01T10:00:00.000Z\",\"updatedAt\":\"${fakeMillis.format(row.updatedAt)}\"}"
}

private fun fakeFileJson(row: FakeFile) =
    "{\"uuid\":\"${row.uuid}\",\"plainName\":\"${row.name}\",\"type\":\"txt\",\"size\":\"1\",\"folderUuid\":\"${row.folder}\"," +
        "\"status\":\"${row.status}\",\"modificationTime\":\"2026-07-01T10:00:00.000Z\",\"updatedAt\":\"${fakeMillis.format(row.updatedAt)}\"}"

/**
 * The drive. [wall]: a folders request with the status filter after the cursor of this uuid is answered 408, however
 * often it comes (the unfiltered form of the query answers). [metaDelayMs]: each folder meta takes this long.
 * [metaGone]: folders whose meta answers 404. [metaStatus]: folders whose meta answers this HTTP status instead (410 for a
 * folder that is gone, 503 for one that is unavailable). [metaOnly]: folders that only their meta answers.
 */
@Suppress("LongParameterList")
internal class FakeDrive(
    val folders: List<FakeFolder>,
    val files: List<FakeFile>,
    val pageSize: Int = 2,
    val wall: String? = null,
    val metaDelayMs: Long = 0,
    val metaGone: Set<String> = emptySet(),
    val metaStatus: Map<String, Int> = emptyMap(),
    // Folders that only their meta knows: the listings do not return them (an unchanged ancestor of an incremental delta).
    val metaOnly: List<FakeFolder> = emptyList(),
    // What `GET /sharings/folders` and `/sharings/files` answer; without it a request for either is a test error.
    val sharings: FakeSharings? = null,
    // The device folders `GET /backup/deviceAsFolder` lists, looked up in [folders] and [metaOnly]; null answers 400, as
    // the server does while backups are not enabled for the user. [deviceStatus] answers that status instead (0 is a
    // connect failure with no response).
    val devices: List<String>? = null,
    val deviceStatus: Int? = null,
    // Answers both sync listings with this HTTP status on every request (404: the server does not serve
    // them, and a gather — a poll included, #312 — falls back to the offset listing).
    val syncStatus: Int? = null,
) {
    val metaRequests = CopyOnWriteArrayList<String>()
    val deviceRequests = AtomicInteger()
    val writes = CopyOnWriteArrayList<String>()
    private val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()

    private fun cursorFor(
        updatedAt: Instant,
        uuid: String,
        status: String?,
    ): String {
        val fields = listOfNotNull("\"updatedAt\":\"${fakeMicros.format(updatedAt)}\"", "\"uuid\":\"$uuid\"", status?.let { "\"status\":\"$it\"" })
        return Base64.getEncoder().encodeToString("{${fields.joinToString(",")}}".toByteArray())
    }

    private fun decodeCursor(token: String): FakeCursorBody? =
        runCatching {
            val fields = Json.parseToJsonElement(String(Base64.getDecoder().decode(token))).jsonObject
            FakeCursorBody(
                Instant.parse(fields.getValue("updatedAt").jsonPrimitive.content),
                fields.getValue("uuid").jsonPrimitive.content,
                fields["status"]?.jsonPrimitive?.contentOrNull,
            )
        }.getOrNull()

    private fun <T> page(
        rows: List<T>,
        updatedAt: (T) -> Instant,
        uuid: (T) -> String,
        status: String?,
        cursor: FakeCursorBody?,
        since: String?,
    ): Pair<List<T>, String?> {
        val ordered = rows.sortedWith(compareBy({ updatedAt(it) }, { uuid(it) }))
        val after =
            if (cursor != null) {
                ordered.filter { r -> updatedAt(r).isAfter(cursor.updatedAt) || (updatedAt(r) == cursor.updatedAt && uuid(r) > cursor.uuid) }
            } else {
                ordered.filter { updatedAt(it).isAfter(Instant.parse(since!!)) }
            }
        val taken = after.take(pageSize)
        val next = if (after.size > pageSize) cursorFor(updatedAt(taken.last()), uuid(taken.last()), status) else null
        return taken to next
    }

    val engine =
        MockEngine { request ->
            val path = request.url.encodedPath
            val params = request.url.parameters
            val status = params["status"]
            val token = params["cursor"]
            val cursor = token?.let(::decodeCursor)
            fun respondJson(body: String) = respond(body, HttpStatusCode.OK, fakeJsonHeaders)
            when {
                path.endsWith("/drive/sharings/folders") || path.endsWith("/drive/sharings/files") -> {
                    val reply = (sharings ?: error("unexpected request: ${request.url}")).answer(path.substringAfterLast('/'), params)
                    respond(reply.body, HttpStatusCode.fromValue(reply.status), fakeSharingsHeaders)
                }
                path.endsWith("/drive/backup/deviceAsFolder") && request.method == HttpMethod.Get -> {
                    deviceRequests.incrementAndGet()
                    if (deviceStatus == 0) throw java.net.ConnectException("Connection refused")
                    if (deviceStatus != null) {
                        return@MockEngine respond("""{"statusCode":$deviceStatus}""", HttpStatusCode.fromValue(deviceStatus), fakeJsonHeaders)
                    }
                    if (devices == null) return@MockEngine respond("""{"statusCode":400,"message":"Backups is not enabled for this user"}""", HttpStatusCode.BadRequest, fakeJsonHeaders)
                    val rows = devices.map { uuid -> (folders + metaOnly).last { it.uuid == uuid } }
                    respondJson(rows.joinToString(",", "[", "]") { fakeFolderJson(it) })
                }
                path.endsWith("/drive/folders/sync") -> {
                    syncStatus?.let { return@MockEngine respond("""{"statusCode":$it}""", HttpStatusCode.fromValue(it), fakeJsonHeaders) }
                    if (token != null && cursor == null) return@MockEngine respond("{}", HttpStatusCode.BadRequest, fakeJsonHeaders)
                    if (cursor != null && cursor.status != status) return@MockEngine respond("{}", HttpStatusCode.BadRequest, fakeJsonHeaders)
                    if (status == "EXISTS" && cursor != null && cursor.uuid == wall) {
                        return@MockEngine respond("""{"statusCode":408,"message":"Request Timeout"}""", HttpStatusCode.RequestTimeout, fakeJsonHeaders)
                    }
                    val listed = folders.filter { status == null || it.status == status }
                    val (taken, next) = page(listed, { it.updatedAt }, { it.uuid }, status, cursor, params["updatedAt"])
                    val rows = taken.joinToString(",") { fakeFolderJson(it) }
                    respondJson("""{"folders":[$rows],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}""")
                }
                path.endsWith("/drive/files/sync") -> {
                    syncStatus?.let { return@MockEngine respond("""{"statusCode":$it}""", HttpStatusCode.fromValue(it), fakeJsonHeaders) }
                    val (taken, next) = page(files, { it.updatedAt }, { it.uuid }, status, cursor, params["updatedAt"])
                    respondJson("""{"files":[${taken.joinToString(",") { fakeFileJson(it) }}],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}""")
                }
                // The offset listing (an incremental delta asks for status ALL since a cursor): everything in one page, then nothing.
                path.endsWith("/drive/folders") || path.endsWith("/drive/files") -> {
                    if ((params["offset"]?.toIntOrNull() ?: 0) > 0) return@MockEngine respondJson("[]")
                    val since = params["updatedAt"]?.let(Instant::parse)
                    fun listed(status: String): Boolean = params["status"] == null || params["status"] == "ALL" || params["status"] == status
                    val rows =
                        if (path.endsWith("/drive/folders")) {
                            folders.filter { listed(it.status) && (since == null || it.updatedAt.isAfter(since)) }.map { fakeFolderJson(it) }
                        } else {
                            files.filter { listed(it.status) && (since == null || it.updatedAt.isAfter(since)) }.map { fakeFileJson(it) }
                        }
                    respondJson(rows.joinToString(",", "[", "]"))
                }
                path.endsWith("/meta") && "/files/" in path -> {
                    val uuid = path.substringBeforeLast("/meta").substringAfterLast('/')
                    val row = files.lastOrNull { it.uuid == uuid }
                    if (row == null) respond("""{"statusCode":404}""", HttpStatusCode.NotFound, fakeJsonHeaders) else respondJson(fakeFileJson(row))
                }
                // Anything that would change the drive is recorded and answered, so a test can say that none was sent.
                request.method != HttpMethod.Get -> {
                    writes += "${request.method.value} $path"
                    respondJson("{}")
                }
                path.endsWith("/meta") -> {
                    val uuid = path.substringBeforeLast("/meta").substringAfterLast('/')
                    metaRequests += uuid
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    try {
                        if (metaDelayMs > 0) delay(metaDelayMs)
                    } finally {
                        inFlight.decrementAndGet()
                    }
                    // A trashed folder is answered with its row and HTTP 200, as the live server does.
                    val row = (folders + metaOnly).lastOrNull { it.uuid == uuid }
                    metaStatus[uuid]?.let { code ->
                        return@MockEngine respond("""{"statusCode":$code}""", HttpStatusCode.fromValue(code), fakeJsonHeaders)
                    }
                    if (row == null || uuid in metaGone) respond("""{"statusCode":404}""", HttpStatusCode.NotFound, fakeJsonHeaders) else respondJson(fakeFolderJson(row))
                }
                else -> error("unexpected request: ${request.url}")
            }
        }
}

internal class StagingKilled : RuntimeException("killed by the test")

/** The engine's staging as a test double: the rows and every marker, handed to the next attempt as `loadStagedItems` returns them. */
internal class FakeStaging(
    var dieWhen: ((String) -> Boolean)? = null,
) {
    val rows = ConcurrentHashMap<String, CloudItem>()
    val markers: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val persistPage: suspend (List<CloudItem>, String) -> Unit = { items, marker ->
        items.forEach { rows[it.id] = it }
        markers += marker
        if (dieWhen?.invoke(marker) == true) throw StagingKilled()
    }

    fun context(onProgress: ((ScanProgress) -> Unit)? = null): ScanContext {
        // What the engine hands back keeps the cloud identity only: no path, no creation time, no mime type.
        val staged = rows.values.sortedBy { it.id }.map { it.copy(path = "/${it.name}", created = null, mimeType = null) }
        return ScanContext(markers.lastOrNull(), staged, persistPage, onProgress = onProgress)
    }
}

// The account: two live folders on the first page (the cursor after a2 is walled), then a trashed tree with live
// content under it, a deleted folder, and a live folder whose parent is trashed. Files hang below all of them.
//
//   a1, a2 (live, top)          T1 "OldStuff" (trashed, top)   D1 "Gone" (deleted, top)
//                               T2 "Deep" (trashed, in T1)
//                               L2 "Kept" (live, in T2)
internal fun fakeAccountFolders() =
    listOf(
        FakeFolder("a1", "Docs", "root", fakeAt("19:30:00")),
        FakeFolder("a2", "Pics", "root", fakeAt("19:31:00")),
        FakeFolder("T1", "OldStuff", "root", fakeAt("19:40:00"), "TRASHED"),
        FakeFolder("T2", "Deep", "T1", fakeAt("19:41:00"), "TRASHED"),
        FakeFolder("D1", "Gone", "root", fakeAt("19:42:00"), "DELETED"),
        FakeFolder("L2", "Kept", "T2", fakeAt("19:50:00")),
    )

internal fun fakeAccountFiles() =
    listOf(
        FakeFile("f1", "readme", "a1", fakeAt("19:10:00")),
        FakeFile("f2", "note", "T1", fakeAt("19:20:00")),
        FakeFile("f3", "keep", "L2", fakeAt("19:30:00")),
    )

// ---- the sharings routes ---------------------------------------------------------------------------------------

/**
 * What must never reach a log line, an exception text, a reference or a `toString()`: one distinct string for each secret that
 * a sharings answer carries. They sit in the fixtures below at every place the server puts the field, nested ones included.
 * The values of the key and the token are kept low in entropy on purpose, so that a secret scanner does not read the fixture as
 * a key.
 */
internal object Canary {
    const val ENCRYPTION_KEY = "CNRY-ENC-KEY-1"
    const val NETWORK_PASS = "CNRY-networkPass-5be27a"
    const val NETWORK_USER = "CNRY-networkUser-c40e96"
    const val TOKEN = "CNRY-TOKEN-4"
    const val ENCRYPTED_CODE = "CNRY-encryptedCode-91cb4e"
    const val LINK_CODE = "CNRY-linkCode-e62f08"
    const val OWNER_EMAIL = "cnry-owner-mail-7714@cnry.invalid"
    const val AVATAR = "https://cnry.invalid/avatar/CNRY-avatar-2c9e51"
    const val BRIDGE_USER = "CNRY-bridgeUser-0f58d3"
    const val OWNER_USER_ID = "CNRY-ownerUserId-b3a690"

    val ALL =
        listOf(ENCRYPTION_KEY, NETWORK_PASS, NETWORK_USER, TOKEN, ENCRYPTED_CODE, LINK_CODE, OWNER_EMAIL, AVATAR, BRIDGE_USER, OWNER_USER_ID)

    /** The canaries found in [text], none when it is clean. */
    fun foundIn(text: String): List<String> = ALL.filter { it in text }
}

/**
 * One sharing row of the account as the server's `FolderWithSharedInfo` or `FileWithSharedInfo` carries it. [sharedWithMe]:
 * the item belongs to another account. [status], [deleted] and [removed] are sent as given (a null [status] leaves the key
 * out, as a folder row may). [publicLink]: the sharing is a public link, whose key and code are canaries again.
 */
internal data class FakeShare(
    val uuid: String,
    val name: String,
    val isFolder: Boolean = true,
    val sharedWithMe: Boolean = false,
    val status: String? = "EXISTS",
    val deleted: Boolean = false,
    val removed: Boolean = false,
    val publicLink: Boolean = false,
)

private fun fakeShareJson(item: FakeShare): String {
    val status = item.status?.let { ",\"status\":\"$it\"" } ?: ""
    val own =
        if (item.isFolder) {
            "\"type\":\"folder\",\"id\":7001,\"parentId\":6000,\"parentUuid\":\"parent-of-${item.uuid}\",\"bucket\":\"6928426c1a2316b856c9ab81\",\"size\":0"
        } else {
            "\"type\":\"txt\",\"id\":8001,\"fileId\":\"bridge-file-${item.uuid}\",\"folderId\":6000,\"folderUuid\":\"parent-of-${item.uuid}\"," +
                "\"bucket\":\"6928426c1a2316b856c9ab81\",\"size\":\"1234\",\"shares\":[],\"thumbnails\":[]"
        }
    // A public link: the sharing's own key and code, the code under the mnemonic, once beside the item and once in the sharings array.
    val link =
        if (item.publicLink) {
            ",\"encryptedCode\":\"${Canary.ENCRYPTED_CODE}\",\"sharings\":[{\"id\":\"link-${item.uuid}\",\"type\":\"public\"," +
                "\"encryptionKey\":\"${Canary.ENCRYPTION_KEY}\",\"encryptedCode\":\"${Canary.ENCRYPTED_CODE}\",\"code\":\"${Canary.LINK_CODE}\"," +
                "\"token\":\"${Canary.TOKEN}\"}]"
        } else {
            ""
        }
    return "{$own,\"name\":\"enc-${item.uuid}\",\"plainName\":\"${item.name}\",\"uuid\":\"${item.uuid}\",\"encryptVersion\":\"03-aes\"," +
        "\"deleted\":${item.deleted},\"deletedAt\":null,\"removed\":${item.removed},\"removedAt\":null,\"userId\":4242$status," +
        "\"creationTime\":\"2026-07-01T10:00:00.000Z\",\"modificationTime\":\"2026-07-01T10:00:00.000Z\"," +
        "\"createdAt\":\"2026-07-01T10:00:00.000Z\",\"updatedAt\":\"2026-07-02T10:00:00.000Z\"," +
        "\"sharingId\":\"sharing-${item.uuid}\",\"encryptionKey\":\"${Canary.ENCRYPTION_KEY}\",\"dateShared\":\"2026-07-03T10:00:00.000Z\"," +
        "\"sharedWithMe\":${item.sharedWithMe},\"token\":\"${Canary.TOKEN}\"$link," +
        "\"user\":{\"uuid\":\"owner-uuid\",\"email\":\"${Canary.OWNER_EMAIL}\",\"name\":\"Owner\",\"lastname\":\"Canary\",\"avatar\":\"${Canary.AVATAR}\"," +
        "\"userId\":\"${Canary.OWNER_USER_ID}\",\"bridgeUser\":\"${Canary.BRIDGE_USER}\"}," +
        "\"credentials\":{\"networkPass\":\"${Canary.NETWORK_PASS}\",\"networkUser\":\"${Canary.NETWORK_USER}\"}}"
}

/** A whole page as the server answers it: the items in their half, the other half empty, and the caller's own credentials on top. */
internal fun fakeSharingsPageJson(
    items: List<FakeShare>,
    isFolder: Boolean,
): String {
    val rows = items.joinToString(",", "[", "]") { fakeShareJson(it) }
    val halves = if (isFolder) "\"folders\":$rows,\"files\":[]" else "\"folders\":[],\"files\":$rows"
    return "{$halves,\"credentials\":{\"networkPass\":\"${Canary.NETWORK_PASS}\",\"networkUser\":\"${Canary.NETWORK_USER}\"}," +
        "\"token\":\"${Canary.TOKEN}\",\"role\":\"OWNER\"}"
}

/** An error body as the worst gateway would write it: the status, and everything it holds echoed. */
internal fun fakeHostileErrorBody(status: Int): String =
    "{\"statusCode\":$status,\"message\":\"failed for ${Canary.OWNER_EMAIL} with encryptionKey=${Canary.ENCRYPTION_KEY}\",\"retry_after\":7," +
        "\"credentials\":{\"networkPass\":\"${Canary.NETWORK_PASS}\",\"networkUser\":\"${Canary.NETWORK_USER}\"}," +
        "\"token\":\"${Canary.TOKEN}\",\"encryptedCode\":\"${Canary.ENCRYPTED_CODE}\",\"code\":\"${Canary.LINK_CODE}\"}"

internal class FakeSharingsReply(
    val status: Int,
    val body: String,
)

private val fakeSharingsHeaders = headersOf(HttpHeaders.ContentType to listOf("application/json"), "x-request-id" to listOf("srv-sharings"))

/**
 * The two sharings routes. [folders] and [files] give the rows of a page by its number (0-based), so a test lays the pages out
 * itself: the server's paging is unstable, a page can repeat a row or skip one. [failure] answers a page with that HTTP status
 * and a [fakeHostileErrorBody] instead. [raw] answers a page with a body of its own.
 */
internal class FakeSharings(
    private val folders: (page: Int) -> List<FakeShare> = { emptyList() },
    private val files: (page: Int) -> List<FakeShare> = { emptyList() },
    private val failure: (route: String, page: Int) -> Int? = { _, _ -> null },
    private val raw: (route: String, page: Int) -> String? = { _, _ -> null },
) {
    /** `<route> page=<n> perPage=<n>` of every request, in the order they came. */
    val requests = CopyOnWriteArrayList<String>()

    fun answer(
        route: String,
        params: Parameters,
    ): FakeSharingsReply {
        val page = params["page"]?.toIntOrNull() ?: error("no page in $params")
        requests += "$route page=$page perPage=${params["perPage"]}"
        failure(route, page)?.let { return FakeSharingsReply(it, fakeHostileErrorBody(it)) }
        raw(route, page)?.let { return FakeSharingsReply(200, it) }
        val isFolder = route == "folders"
        return FakeSharingsReply(200, fakeSharingsPageJson(if (isFolder) folders(page) else files(page), isFolder))
    }

    companion object {
        /** The rows laid out in pages of [perPage] in the order given, the way a server with a stable order serves them. */
        fun pages(
            all: List<FakeShare>,
            perPage: Int = 50,
        ): (Int) -> List<FakeShare> = { page -> all.drop(page * perPage).take(perPage) }
    }
}

/** An [InternxtApiService] on [drive]'s engine, built the way the production one is (the request-id plugin included), with no request spacing. */
internal fun internxtApiOn(
    drive: FakeDrive,
    credentials: suspend (forceRefresh: Boolean) -> InternxtCredentials = { fakeCredentials() },
): InternxtApiService =
    InternxtApiService(
        InternxtConfig(credentialProtector = null),
        credentialsProvider = credentials,
        driveBudget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0),
        httpClient = InternxtApiService.defaultHttpClient(RequestMetricsTap(), drive.engine, profile = "sharings"),
    )

internal fun fakeCredentials() =
    InternxtCredentials(jwt = "test-jwt", mnemonic = "test-mnemonic", rootFolderId = "test-root", email = "test@example.invalid")

// ---- a drive that moves and renames --------------------------------------------------------------------------

/** One item of a [FakeMoveDrive]: [type] is the file type (null for a folder or a file without one). */
internal data class FakeMoveItem(
    val uuid: String,
    val name: String,
    val parent: String,
    val isFolder: Boolean,
    val type: String? = null,
    val modified: Instant = fakeAt("10:00:00"),
)

/**
 * A drive whose moves (`PATCH /files|folders/{uuid}`) and renames (`PUT /files|folders/{uuid}/meta`) change its state, and
 * refuse with 409 the way the server does: an item cannot land in a folder that already holds an item of its kind under the
 * same name. A write answers the item as it is now with its modified time from before the write, and stamps a new one that the meta
 * reports, as the live server does. [legs]: every move and rename that landed, as `move <uuid> -> <folder>` and
 * `rename <uuid> -> <name>`. [failNext]: answers the next write whose leg starts with this text with that status once.
 */
internal class FakeMoveDrive(
    initial: List<FakeMoveItem>,
) {
    val items = ConcurrentHashMap(initial.associateBy { it.uuid })
    val legs = CopyOnWriteArrayList<String>()
    val renamePlains = CopyOnWriteArrayList<String>()
    val refused = CopyOnWriteArrayList<String>()
    val failNext = ConcurrentHashMap<String, Int>()
    private val clock = AtomicInteger()

    private fun fullName(item: FakeMoveItem) = if (item.type.isNullOrEmpty()) item.name else "${item.name}.${item.type}"

    fun pathOf(uuid: String): String {
        val item = items.getValue(uuid)
        val parent = if (item.parent == "root") "" else pathOf(item.parent)
        return "$parent/${fullName(item)}"
    }

    fun paths(): Set<String> = items.keys.map(::pathOf).toSet()

    private fun json(item: FakeMoveItem): String {
        val time = fakeMillis.format(item.modified)
        return if (item.isFolder) {
            "{\"uuid\":\"${item.uuid}\",\"plainName\":\"${item.name}\",\"parentUuid\":\"${item.parent}\",\"status\":\"EXISTS\"," +
                "\"modificationTime\":\"$time\",\"updatedAt\":\"$time\"}"
        } else {
            "{\"uuid\":\"${item.uuid}\",\"plainName\":\"${item.name}\",\"type\":\"${item.type ?: ""}\",\"size\":\"1\"," +
                "\"folderUuid\":\"${item.parent}\",\"status\":\"EXISTS\",\"modificationTime\":\"$time\",\"updatedAt\":\"$time\"}"
        }
    }

    private fun write(
        leg: String,
        before: FakeMoveItem,
        after: FakeMoveItem,
    ): Pair<HttpStatusCode, String> {
        failNext.keys.firstOrNull { leg.startsWith(it) }?.let { key ->
            val status = failNext.remove(key)!!
            refused += leg
            return HttpStatusCode.fromValue(status) to "{\"statusCode\":$status,\"message\":\"injected\"}"
        }
        val clash =
            items.values.any {
                it.uuid != after.uuid && it.parent == after.parent && it.isFolder == after.isFolder && fullName(it) == fullName(after)
            }
        if (clash) {
            refused += leg
            val kind = if (after.isFolder) "folder" else "file"
            return HttpStatusCode.Conflict to
                "{\"message\":\"A $kind with the same name already exists in destination folder\",\"error\":\"Conflict\",\"statusCode\":409}"
        }
        items[after.uuid] = after.copy(modified = fakeAt("11:00:00").plusSeconds(clock.incrementAndGet().toLong()))
        legs += leg
        return HttpStatusCode.OK to json(after.copy(modified = before.modified))
    }

    val engine =
        MockEngine { request ->
            val path = request.url.encodedPath
            val body = (request.body as? io.ktor.http.content.TextContent)?.text
            fun field(name: String) = body?.let { Json.parseToJsonElement(it).jsonObject[name]?.jsonPrimitive?.contentOrNull }
            fun answer(reply: Pair<HttpStatusCode, String>) = respond(reply.second, reply.first, fakeJsonHeaders)
            val segments = path.removePrefix("/drive/").split('/')
            when {
                request.method == HttpMethod.Get && segments.size == 5 &&
                    segments[0] == "folders" && segments[1] == "v2" && segments[2] == "content" -> {
                    val children = items.values.filter { it.parent == segments[3] }
                    val files = segments[4] == "files"
                    val listed = children.filter { it.isFolder != files }.joinToString(",") { json(it) }
                    val key = if (files) "files" else "folders"
                    respond("{\"$key\":[$listed],\"nextCursor\":null}", HttpStatusCode.OK, fakeJsonHeaders)
                }
                request.method == HttpMethod.Get && segments.size == 3 && segments[0] == "folders" && segments[1] == "content" -> {
                    val children = items.values.filter { it.parent == segments[2] }
                    val folders = children.filter { it.isFolder }.joinToString(",") { json(it) }
                    val files = children.filter { !it.isFolder }.joinToString(",") { json(it) }
                    respond("{\"children\":[$folders],\"files\":[$files]}", HttpStatusCode.OK, fakeJsonHeaders)
                }
                request.method == HttpMethod.Get && segments.size == 3 && segments[2] == "meta" -> {
                    val item = items[segments[1]]?.takeIf { it.isFolder == (segments[0] == "folders") }
                    if (item == null) respond("{\"statusCode\":404}", HttpStatusCode.NotFound, fakeJsonHeaders) else respond(json(item), HttpStatusCode.OK, fakeJsonHeaders)
                }
                request.method == HttpMethod.Patch && segments.size == 2 -> {
                    val item = items.getValue(segments[1])
                    val dest = field("destinationFolder")!!
                    answer(write("move ${item.uuid} -> $dest", item, item.copy(parent = dest)))
                }
                request.method == HttpMethod.Put && segments.size == 3 && segments[2] == "meta" -> {
                    val item = items.getValue(segments[1])
                    val plain = field("plainName")!!
                    renamePlains += plain
                    val renamed = item.copy(name = plain, type = if (item.isFolder) null else field("type")?.ifEmpty { null })
                    answer(write("rename ${item.uuid} -> ${fullName(renamed)}", item, renamed))
                }
                else -> error("unexpected request: ${request.method.value} ${request.url}")
            }
        }
}
