package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ClosedReadChannelException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ScanContext
import org.krost.unidrive.ScanProgress
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// The harness of the listing tests (`InternxtCursorListingTest` and the tests that share its drive): the provider
// over a MockEngine, the fixtures both listing strategies are served from, and the fake drive that serves the
// cursor endpoints and the offset endpoints from the same fixture, so what the two strategies return can be
// compared. Kept apart from the test class itself, the way `InternxtFakeDrive.kt` stands beside its tests.

internal val jsonHeaders = headersOf("Content-Type", "application/json")
internal const val ROOT = "root"
internal const val EPOCH = "1970-01-01T00:00:00.000Z"

internal fun folder(
    uuid: String,
    name: String,
    parent: String,
) = """{"uuid":"$uuid","plainName":"$name","parentUuid":"$parent","status":"EXISTS",""" +
    """"modificationTime":"2026-09-01T10:00:00.123Z","updatedAt":"2026-10-01T10:00:00.456Z"}"""

internal fun file(
    uuid: String,
    name: String,
    ext: String,
    folder: String,
    size: Int = 1,
) = """{"uuid":"$uuid","plainName":"$name","type":"$ext","size":"$size","folderUuid":"$folder","status":"EXISTS",""" +
    """"modificationTime":"2026-09-01T10:00:00.123Z","updatedAt":"2026-10-01T10:00:00.456Z"}"""

// A ROOT-level file row that names its own updatedAt and status, for the tests that lay the change
// feed out themselves (archive#312). [withStatus] = false leaves the status out, the way a page asked
// without a status filter must never.
internal fun fileRow(
    uuid: String,
    updatedAt: String,
    status: String = "EXISTS",
    size: Int = 1,
    withStatus: Boolean = true,
) = """{"uuid":"$uuid","plainName":"$uuid","type":"txt","size":"$size","folderUuid":"root",""" +
    (if (withStatus) "\"status\":\"$status\"," else "") +
    """"modificationTime":"2026-09-01T10:00:00.123Z","updatedAt":"$updatedAt"}"""

// The listing is ordered by updatedAt, not by the tree: a child can arrive before its parent.
internal val standardFolderPages =
    listOf(
        listOf(folder("d4", "Deep", "d2"), folder("d2", "Sub", "d1")),
        listOf(folder("d3", "Pics", ROOT)),
        listOf(folder("d1", "Docs", ROOT)),
    )
internal val standardFilePages =
    listOf(
        listOf(file("f1", "top", "txt", ROOT)),
        listOf(file("f2", "a", "txt", "d1", 2), file("f3", "b", "txt", "d2", 3)),
        listOf(file("f4", "p", "jpg", "d3", 4)),
        listOf(file("f5", "deep", "txt", "d4", 5)),
    )
internal val standardPaths =
    setOf(
        "/Docs",
        "/Docs/Sub",
        "/Docs/Sub/Deep",
        "/Pics",
        "/top.txt",
        "/Docs/a.txt",
        "/Docs/Sub/b.txt",
        "/Pics/p.jpg",
        "/Docs/Sub/Deep/deep.txt",
    )

internal fun filesBody(
    items: List<String>,
    next: String? = null,
): String = "{\"files\":[${items.joinToString(",")}]" + (if (next == null) "" else ",\"nextCursor\":\"$next\"") + "}"

internal fun cutByTheGateway() =
    ClosedReadChannelException(
        java.io.EOFException("Failed to parse HTTP response: the server prematurely closed the connection"),
    )

// The tree the fallback walk serves: ROOT -> [w] with one file. Distinct from the cursor fixture's
// items, so a result can be attributed to the walk by its paths alone.
internal val walkTree =
    mapOf(
        "root" to
            """{"children":[{"uuid":"w","plainName":"w","status":"EXISTS"}],""" +
                """"files":[{"uuid":"wt","plainName":"wt","type":"txt","size":"9","status":"EXISTS"}]}""",
        "w" to """{"children":[],"files":[]}""",
    )
internal val walkPaths = setOf("/w", "/wt.txt")

internal sealed interface Failure {
    data class Status(
        val code: Int,
    ) : Failure

    data object Cut : Failure

    // No connection at all: no route, refused, unknown host. The client reports it as status 0.
    data object Unreachable : Failure
}

internal class Seen(
    val stream: String,
    val updatedAt: String?,
    val cursor: String?,
    val status: String?,
    val limit: String?,
    val query: String,
)

internal fun quiet(onProgress: ((ScanProgress) -> Unit)? = null) = ScanContext(null, emptyList(), { _, _ -> }, onProgress = onProgress)

// A resumed row keeps the cloud identity staging holds: no creation time, and a ROOT-level parent as null.
internal fun essence(item: CloudItem) = listOf(item.id, item.path, item.name, item.size, item.isFolder, item.modified, item.deleted)

/** A provider over [engine], signed in with test credentials, as the listing tests build it. */
internal fun provider(engine: MockEngine): InternxtProvider {
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

/**
 * The drive as a server: [folderPages] and [filePages] are served page by page by `/folders/sync` and `/files/sync`
 * (the cursor of page i is `cursorOf(stream, i)`, an opaque string with characters that need URL encoding), and in
 * one piece by the offset endpoints when [offsetListing] is on. [tree] serves `/folders/content/{uuid}` bodies for
 * the fallback walk. [failure] makes a page fail, [body] replaces one. Every cursor request is recorded in [seen],
 * every other request in [other].
 */
@Suppress("LongParameterList") // a test double's options bag, as on FakeDrive
internal class DriveMock(
    val folderPages: List<List<String>> = standardFolderPages,
    val filePages: List<List<String>> = standardFilePages,
    val offsetListing: Boolean = false,
    val tree: Map<String, String>? = null,
    val contentStatus: Int? = null,
    val failure: (stream: String, page: Int) -> Failure? = { _, _ -> null },
    val body: (stream: String, cursor: String?) -> String? = { _, _ -> null },
    // archive#523/#647: raw item bodies for the paged per-folder listing, keyed by folder uuid; a uuid with no
    // entry serves an empty listing. Two endpoint families serve them, each enforcing the validation of the
    // public server source (the unit tests used to honour any limit, which hid archive#647):
    //  - the cursor endpoints GET /folders/v2/content/{uuid}/folders and /files: order ASC|DESC, limit 50..1000
    //    (default 100), an opaque cursor, the reply carries nextCursor (null on the last page);
    //  - the deprecated offset endpoints GET /folders/content/{uuid}/folders and /files: limit REQUIRED 1..50,
    //    offset REQUIRED >= 0, sort one of the listed columns; anything else answers 400.
    // [pagedStatus] fails both families instead; [v2Status] fails only the cursor endpoints, [offsetStatus] only the offset ones.
    val pagedContent: Map<String, Pair<List<String>, List<String>>> = emptyMap(),
    val pagedStatus: Int? = null,
    val v2Status: Int? = null,
    val offsetStatus: Int? = null,
    // A gateway that caps `limit` below what is asked serves at most [pagedMaxPage] (offset) or [v2MaxPage]
    // (cursor) items per page; one that ignores `offset` on the files stream serves its first page again and
    // again ([pagedIgnoresOffset]); one that ignores the cursor does the same on the cursor files stream
    // ([v2IgnoresCursor]); one whose nextCursor never changes serves a new page per request under it ([v2StuckCursor]).
    val pagedMaxPage: Int? = null,
    val v2MaxPage: Int? = null,
    val pagedIgnoresOffset: Boolean = false,
    val v2IgnoresCursor: Boolean = false,
    val v2StuckCursor: Boolean = false,
    // Serves the offset listing by window instead of in one piece: the rows of `(stream, offset)`,
    // an empty list for the end. The closure is asked on every fetch, so a test can change what a
    // window holds between the gather and the re-fetch (archive#312).
    val offsetPages: ((stream: String, offset: Int) -> List<String>?)? = null,
) {
    val seen = CopyOnWriteArrayList<Seen>()
    val other = CopyOnWriteArrayList<String>()
    private val v2Requests = ConcurrentHashMap<String, Int>()

    fun cursorOf(
        stream: String,
        page: Int,
    ) = "c-$stream-$page+/="

    fun pageOf(
        stream: String,
        cursor: String?,
    ): Int {
        if (cursor == null) return 0
        val match = Regex("""c-$stream-(\d+)\+/=""").matchEntire(cursor) ?: error("a malformed $stream cursor reached the server: $cursor")
        return match.groupValues[1].toInt()
    }

    private fun pageBody(
        stream: String,
        page: Int,
    ): String {
        val pages = if (stream == "folders") folderPages else filePages
        val next = if (page + 1 < pages.size) ",\"nextCursor\":\"${cursorOf(stream, page + 1)}\"" else ""
        return "{\"$stream\":[${pages[page].joinToString(",")}]$next}"
    }

    val engine =
        MockEngine { request ->
            val path = request.url.encodedPath
            val params = request.url.parameters
            val stream =
                when {
                    path.endsWith("/drive/folders/sync") -> "folders"
                    path.endsWith("/drive/files/sync") -> "files"
                    else -> null
                }
            if (stream == null) {
                other += request.url.toString()
                when {
                    path.contains("/folders/v2/content/") &&
                        (path.endsWith("/files") || path.endsWith("/folders")) &&
                        (pagedContent.isNotEmpty() || pagedStatus != null || v2Status != null) -> {
                        val status = v2Status ?: pagedStatus
                        if (status != null) return@MockEngine respond("{}", HttpStatusCode.fromValue(status), jsonHeaders)
                        val limit = params["limit"]?.let { it.toIntOrNull() ?: -1 } ?: 100
                        val order = params["order"] ?: "ASC"
                        if (limit !in 50..1000 || order !in setOf("ASC", "DESC")) {
                            return@MockEngine respond("""{"message":"Bad Request"}""", HttpStatusCode.BadRequest, jsonHeaders)
                        }
                        val uuid = path.substringAfter("/folders/v2/content/").substringBefore('/')
                        val (folders, files) = pagedContent[uuid] ?: Pair(emptyList(), emptyList())
                        val isFiles = path.endsWith("/files")
                        val items = if (isFiles) files else folders
                        val cursor = params["cursor"]
                        if (cursor != null && !Regex("""v2-\d+""").matches(cursor) && cursor != "stuck") {
                            return@MockEngine respond("""{"message":"Invalid cursor"}""", HttpStatusCode.BadRequest, jsonHeaders)
                        }
                        val size = minOf(limit, v2MaxPage ?: Int.MAX_VALUE)
                        val from =
                            when {
                                isFiles && v2IgnoresCursor -> 0
                                // a stuck cursor: the n-th request of the stream gets the n-th page
                                v2StuckCursor -> v2Requests.merge("$uuid|$path", 1, Int::plus)!!.minus(1) * size
                                else -> cursor?.removePrefix("v2-")?.toInt() ?: 0
                            }
                        val page = items.drop(from).take(size)
                        val next = if (from + page.size < items.size) (if (v2StuckCursor) "stuck" else "v2-${from + page.size}") else null
                        respond(
                            """{"${if (isFiles) "files" else "folders"}":[${page.joinToString(",")}],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                    path.contains("/folders/content/") &&
                        (path.endsWith("/files") || path.endsWith("/folders")) &&
                        (pagedContent.isNotEmpty() || pagedStatus != null || offsetStatus != null) -> {
                        (offsetStatus ?: pagedStatus)?.let { return@MockEngine respond("{}", HttpStatusCode.fromValue(it), jsonHeaders) }
                        val isFiles = path.endsWith("/files")
                        val limit = params["limit"]?.let { it.toIntOrNull() ?: -1 } ?: 0
                        val offset = params["offset"]?.let { it.toIntOrNull() ?: -1 } ?: -1
                        val sort = params["sort"]
                        val sortable = if (isFiles) setOf("updatedAt", "size", "id", "plainName", "uuid") else setOf("updatedAt", "id", "plainName", "uuid")
                        val sortOk = sort == null || sort in sortable
                        if (limit !in 1..50 || offset < 0 || !sortOk) {
                            return@MockEngine respond("""{"message":"Bad Request"}""", HttpStatusCode.BadRequest, jsonHeaders)
                        }
                        val uuid = path.substringAfter("/folders/content/").substringBefore('/')
                        val (folders, files) = pagedContent[uuid] ?: Pair(emptyList(), emptyList())
                        val items = if (isFiles) files else folders
                        val from = if (pagedIgnoresOffset && isFiles) 0 else offset
                        respond(
                            """{"${if (isFiles) "files" else "folders"}":[${items.drop(from).take(minOf(limit, pagedMaxPage ?: Int.MAX_VALUE)).joinToString(",")}]}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }
                    contentStatus != null && (path.contains("/folders/content/") || path.contains("/folders/v2/content/")) ->
                        respond("{}", HttpStatusCode.fromValue(contentStatus), jsonHeaders)
                    tree != null && path.contains("/folders/content/") -> {
                        val content =
                            tree.entries.firstOrNull { path.endsWith("/drive/folders/content/${it.key}") }?.value
                        if (content != null) respond(content, HttpStatusCode.OK, jsonHeaders)
                        else error("unexpected request: ${request.url}")
                    }
                    !offsetListing || !(path.endsWith("/drive/folders") || path.endsWith("/drive/files")) -> {
                        error("unexpected request: ${request.url}")
                    }
                    else -> {
                        val offset = params["offset"]?.toInt() ?: 0
                        val stream = if (path.endsWith("/drive/folders")) "folders" else "files"
                        val custom = offsetPages?.invoke(stream, offset)
                        if (custom != null) {
                            respond(custom.joinToString(",", "[", "]"), HttpStatusCode.OK, jsonHeaders)
                        } else {
                            val all = (if (stream == "folders") folderPages else filePages).flatten()
                            respond(if (offset == 0) all.joinToString(",", "[", "]") else "[]", HttpStatusCode.OK, jsonHeaders)
                        }
                    }
                }
            } else {
                val cursor = params["cursor"]
                seen += Seen(stream, params["updatedAt"], cursor, params["status"], params["limit"], request.url.encodedQuery)
                val custom = body(stream, cursor)
                if (custom != null) {
                    respond(custom, HttpStatusCode.OK, jsonHeaders)
                } else {
                    val page = pageOf(stream, cursor)
                    when (val f = failure(stream, page)) {
                        is Failure.Status -> respond("{}", HttpStatusCode.fromValue(f.code), jsonHeaders)
                        Failure.Cut -> throw cutByTheGateway()
                        Failure.Unreachable -> throw java.net.NoRouteToHostException("No route to host")
                        null -> respond(pageBody(stream, page), HttpStatusCode.OK, jsonHeaders)
                    }
                }
            }
        }
}
