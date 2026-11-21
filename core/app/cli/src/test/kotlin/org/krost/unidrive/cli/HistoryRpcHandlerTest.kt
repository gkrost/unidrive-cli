package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.hydration.HistoryRetryOutcome
import org.krost.unidrive.telemetry.TransferDirection
import org.krost.unidrive.telemetry.TransferHistory
import org.krost.unidrive.telemetry.TransferResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/** The history read verbs without a socket: parsing, paging, the byte cap, the tail and its `lost` marker, and the unavailable replies. */
class HistoryRpcHandlerTest {
    private lateinit var dir: Path
    private lateinit var db: Path
    private val wallMs = AtomicLong(1_700_000_000_000L)
    private var history: TransferHistory? = null
    private var handler: HistoryRpcHandler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("history-rpc-test")
        db = dir.resolve("telemetry.db")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        runCatching { handler?.close() }
        runCatching { history?.close() }
        runCatching { Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } } }
    }

    private fun open(capacity: Int = HistoryRpcHandler.SUBSCRIBER_QUEUE_CAPACITY): HistoryRpcHandler {
        val h = TransferHistory.open("p1", db, clock = { wallMs.get() }).also { history = it }
        return HistoryRpcHandler(h, HistoryRpcHandler.OPEN_FAILED, subscriberCapacity = capacity).also { handler = it }
    }

    private fun record(
        path: String,
        direction: TransferDirection = TransferDirection.UPLOAD,
        result: TransferResult = TransferResult.SUCCESS,
    ) {
        wallMs.addAndGet(1)
        val handle = history!!.begin(direction, path, bytesTotal = 10)
        wallMs.addAndGet(1)
        handle.finish(result, bytesDone = 10)
    }

    private fun obj(reply: String) = Json.parseToJsonElement(reply).jsonObject

    @Test
    fun `a row carries the public fields only and a value that was not measured is null`() = runBlocking {
        val h = open()
        wallMs.set(5_000)
        val handle = history!!.begin(TransferDirection.DOWNLOAD, "/a.txt")
        handle.started()
        assertTrue(history!!.flush())
        h.start(scope) { _, _ -> true }

        val row = obj(h.list("""{"verb":"history.list"}""")).getValue("rows").jsonArray.single().jsonObject
        assertEquals(
            setOf(
                "operation_id", "direction", "path", "queued_at_ms", "started_at_ms", "finished_at_ms", "queue_wait_ms", "transfer_ms",
                "bytes_total", "bytes_done", "result", "error_class", "error_message", "attempts", "max_attempts", "next_try_at_ms",
            ),
            row.keys,
            "no profile, no hashes, no internal key",
        )
        assertEquals("running", row.getValue("result").jsonPrimitive.content)
        for (unmeasured in listOf(
            "finished_at_ms", "transfer_ms", "bytes_total", "bytes_done", "error_class", "error_message", "attempts", "max_attempts",
            "next_try_at_ms",
        )) {
            assertEquals(JsonNull, row.getValue(unmeasured), "$unmeasured: unknown stays null, never 0")
        }
    }

    @Test
    fun `an upload in a retry backoff says its attempt limit and next try, and one that ran out of attempts says neither wait`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        wallMs.set(5_000)
        val waiting = history!!.begin(TransferDirection.UPLOAD, "/waiting.txt", bytesTotal = 10, maxAttempts = 6)
        waiting.started()
        waiting.attemptFailed("SocketTimeoutException", "timed out", nextTryAtMs = 305_000L)
        wallMs.addAndGet(1)
        val spent = history!!.begin(TransferDirection.UPLOAD, "/spent.txt", bytesTotal = 10, maxAttempts = 1)
        spent.started()
        spent.finish(TransferResult.FAILED, errorClass = "SocketTimeoutException", message = "timed out")
        assertTrue(history!!.flush())

        val rows = obj(h.list("""{"verb":"history.list"}""")).getValue("rows").jsonArray.map { it.jsonObject }.associateBy { it.getValue("path").jsonPrimitive.content }
        val inBackoff = rows.getValue("/waiting.txt")
        assertEquals("running", inBackoff.getValue("result").jsonPrimitive.content)
        assertEquals(1, inBackoff.getValue("attempts").jsonPrimitive.int)
        assertEquals(6, inBackoff.getValue("max_attempts").jsonPrimitive.int)
        assertEquals(305_000L, inBackoff.getValue("next_try_at_ms").jsonPrimitive.long)
        val outOfAttempts = rows.getValue("/spent.txt")
        assertEquals("failed", outOfAttempts.getValue("result").jsonPrimitive.content)
        assertEquals(1, outOfAttempts.getValue("max_attempts").jsonPrimitive.int)
        assertEquals(JsonNull, outOfAttempts.getValue("next_try_at_ms"))
    }

    @Test
    fun `an empty history is an empty list with no next cursor, flagged complete`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        val reply = obj(h.list("""{"verb":"history.list"}"""))
        assertEquals(0, reply.getValue("rows").jsonArray.size)
        assertEquals(JsonNull, reply.getValue("next_cursor"))
        assertFalse(reply.getValue("incomplete").jsonPrimitive.boolean)
        assertEquals(0L, reply.getValue("tail_seq").jsonPrimitive.long)
    }

    @Test
    fun `a limit over the maximum is the maximum and the cursor carries on`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        repeat(520) { record("/f$it") }
        assertTrue(history!!.flush())

        val first = obj(h.list("""{"verb":"history.list","limit":100000}"""))
        assertEquals(500, first.getValue("rows").jsonArray.size)
        val cursor = first.getValue("next_cursor").jsonPrimitive.content
        val second = obj(h.list("""{"verb":"history.list","limit":100000,"cursor":"$cursor"}"""))
        assertEquals(20, second.getValue("rows").jsonArray.size)
        assertEquals(JsonNull, second.getValue("next_cursor"))
        val ids = (first.getValue("rows").jsonArray + second.getValue("rows").jsonArray).map { it.jsonObject.getValue("operation_id").jsonPrimitive.content }
        assertEquals(520, ids.toSet().size, "every row once")
    }

    @Test
    fun `paging stays whole while rows arrive between the pages`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        repeat(30) { record("/f$it") }
        assertTrue(history!!.flush())

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var arrivals = 0
        do {
            val request = if (cursor == null) """{"verb":"history.list","limit":7}""" else """{"verb":"history.list","limit":7,"cursor":"$cursor"}"""
            val page = obj(h.list(request))
            page.getValue("rows").jsonArray.forEach { seen += it.jsonObject.getValue("path").jsonPrimitive.content }
            cursor = (page.getValue("next_cursor") as? JsonPrimitive)?.takeIf { it.isString }?.content
            // New transfers land between two pages.
            repeat(3) { record("/late${arrivals++}") }
            assertTrue(history!!.flush())
        } while (cursor != null)

        assertEquals((0 until 30).map { "/f${29 - it}" }, seen, "the 30 rows that existed, newest first, none twice, none missed")
    }

    @Test
    fun `a reply stays under the byte cap and the next cursor loses nothing`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        val longName = "x".repeat(3_000)
        repeat(300) { record("/$it/$longName") }
        assertTrue(history!!.flush())

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val request = if (cursor == null) """{"verb":"history.list","limit":500}""" else """{"verb":"history.list","limit":500,"cursor":"$cursor"}"""
            val reply = h.list(request)
            assertTrue(reply.length < HistoryRpcHandler.MAX_REPLY_BYTES + 8_000, "a page of ${reply.length} chars")
            val page = obj(reply)
            page.getValue("rows").jsonArray.forEach { seen += it.jsonObject.getValue("operation_id").jsonPrimitive.content }
            cursor = (page.getValue("next_cursor") as? JsonPrimitive)?.takeIf { it.isString }?.content
            pages++
        } while (cursor != null)

        assertTrue(pages > 1, "300 rows of 3 KB do not fit one reply")
        assertEquals(300, seen.toSet().size)
        assertEquals(300, seen.size)
    }

    @Test
    fun `a request that cannot be read is refused with a token and changes nothing`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        assertEquals("""{"ok":false,"error":"invalid_request"}""", h.list("""[1,2]"""))
        assertEquals("""{"ok":false,"error":"invalid_request"}""", h.list("""not json"""))
        assertEquals("""{"ok":false,"error":"invalid_limit"}""", h.list("""{"limit":-3}"""))
        assertEquals("""{"ok":false,"error":"invalid_limit"}""", h.list("""{"limit":1.5}"""))
        assertEquals("""{"ok":false,"error":"invalid_cursor"}""", h.list("""{"cursor":7}"""))
        assertEquals("""{"ok":false,"error":"invalid_direction"}""", h.list("""{"filters":{"direction":["upload",3]}}"""))
        assertEquals("""{"ok":false,"error":"invalid_result"}""", h.list("""{"filters":{"result":"done"}}"""))
        assertEquals("""{"ok":false,"error":"invalid_time_range"}""", h.list("""{"filters":{"from_ms":"yesterday"}}"""))
        // Unknown members are ignored: older and newer clients meet.
        assertEquals("true", obj(h.list("""{"verb":"history.list","future_member":true,"filters":{"future":1}}""")).getValue("ok").toString())
    }

    @Test
    fun `no history is history_unavailable for both verbs, with the reason`() = runBlocking {
        for (reason in listOf(HistoryRpcHandler.MIRROR, HistoryRpcHandler.OPEN_FAILED)) {
            val h = HistoryRpcHandler(null, reason)
            h.start(scope) { _, _ -> true }
            assertEquals("""{"ok":false,"error":"history_unavailable","reason":"$reason"}""", h.list("""{"verb":"history.list"}"""))
            assertEquals("""{"ok":false,"error":"history_unavailable","reason":"$reason"}""", h.subscribe("c1"))
            assertFalse(h.hasSubscribers())
        }
    }

    @Test
    fun `a file that cannot be read is history_unavailable, not an empty list`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        record("/a")
        assertTrue(history!!.flush())
        assertEquals(1, obj(h.list("""{"verb":"history.list"}""")).getValue("rows").jsonArray.size)

        history!!.close()
        h.close()
        Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.forEach { it.deleteIfExists() } }
        val reply = obj(h.list("""{"verb":"history.list"}"""))
        assertEquals("history_unavailable", reply.getValue("error").jsonPrimitive.content)
        assertEquals("read_failed", reply.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun `rows that were dropped mark the list incomplete`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        record("/a")
        assertTrue(history!!.flush())
        // The writer's own account of a gap: a snapshot it could not keep.
        java.sql.DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.createStatement().use { it.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('incomplete_since', '1700000000000')") }
        }
        assertTrue(obj(h.list("""{"verb":"history.list"}""")).getValue("incomplete").jsonPrimitive.boolean)
    }

    @Test
    fun `a subscriber is told the tail position, then gets each committed row with a rising seq`() = runBlocking {
        val h = open()
        val lines = CopyOnWriteArrayList<String>()
        h.start(scope) { _, line -> lines += line; true }
        record("/before")
        assertTrue(history!!.flush())

        val reply = obj(h.subscribe("c1"))
        val tailSeq = reply.getValue("tail_seq").jsonPrimitive.long
        assertEquals(1L, tailSeq)
        assertTrue(h.hasSubscribers())
        // Nothing is written before the reply is out: the stream starts when the server says so.
        record("/after1")
        assertTrue(history!!.flush())
        delay(200)
        assertTrue(lines.isEmpty(), "no line before beginStreaming: $lines")

        h.beginStreaming("c1")
        record("/after2")
        assertTrue(history!!.flush())
        awaitLines(lines, 2)
        val events = lines.map { obj(it) }
        assertEquals(listOf("history.row", "history.row"), events.map { it.getValue("event").jsonPrimitive.content })
        assertEquals(listOf("/after1", "/after2"), events.map { it.getValue("row").jsonObject.getValue("path").jsonPrimitive.content })
        assertEquals(listOf(2L, 3L), events.map { it.getValue("seq").jsonPrimitive.long }, "every event past tail_seq, none before it")
    }

    @Test
    fun `a subscriber that falls behind gets a lost marker with the count, never a silent gap`() = runBlocking {
        val h = open(capacity = 4)
        val gate = CompletableDeferred<Unit>()
        val firstWrite = CompletableDeferred<Unit>()
        val lines = CopyOnWriteArrayList<String>()
        h.start(scope) { _, line ->
            firstWrite.complete(Unit)
            gate.await()
            lines += line
            true
        }
        h.subscribe("c1")
        h.beginStreaming("c1")
        // One snapshot goes to the writer, which then sits at the gate: from here every further snapshot has to wait in the
        // queue of 4, so which ones are lost does not depend on how quickly the writer coroutine got going.
        record("/first")
        assertTrue(history!!.flush())
        withTimeout(10_000) { firstWrite.await() }
        repeat(20) { record("/f$it") }
        assertTrue(history!!.flush())
        gate.complete(Unit)
        awaitLines(lines, 1 + 4 + 1) // the held snapshot, the marker, the 4 that stayed in the queue

        val events = lines.map { obj(it) }
        val lost = events.filter { it.getValue("event").jsonPrimitive.content == "history.lost" }
        val rows = events.filter { it.getValue("event").jsonPrimitive.content == "history.row" }
        assertEquals(1, lost.size, "one gap: $lines")
        assertEquals(setOf("event", "since_last"), lost.first().keys, "the shape events/history.lost.ndjson pins")
        assertEquals(16, lost.first().getValue("since_last").jsonPrimitive.int, "21 snapshots: 1 held, 4 queued, the 16 oldest of the rest dropped")
        assertEquals(21, rows.size + lost.sumOf { it.getValue("since_last").jsonPrimitive.int }, "delivered plus lost is everything committed")
        // The marker comes ahead of the row after the gap, and the seqs rise.
        val seqs = rows.map { it.getValue("seq").jsonPrimitive.long }
        assertEquals(listOf(1L, 18L, 19L, 20L, 21L), seqs)
        val markerIndex = events.indexOfFirst { it.getValue("event").jsonPrimitive.content == "history.lost" }
        assertEquals("history.row", events[markerIndex - 1].getValue("event").jsonPrimitive.content)
        assertEquals(1L, events[markerIndex - 1].getValue("seq").jsonPrimitive.long, "the row before the gap is delivered before its marker")
        assertEquals("history.row", events[markerIndex + 1].getValue("event").jsonPrimitive.content)
        assertEquals(18L, events[markerIndex + 1].getValue("seq").jsonPrimitive.long, "the marker is ahead of the first row after the gap")
    }

    @Test
    fun `a subscriber whose writer starts late is told about the snapshots it never got`() = runBlocking {
        val h = open(capacity = 4)
        val lines = CopyOnWriteArrayList<String>()
        h.start(scope) { _, line -> lines += line; true }
        h.subscribe("c1")
        repeat(20) { record("/f$it") }
        assertTrue(history!!.flush())
        // Nothing has taken a snapshot off the queue yet: 16 were dropped before the stream even began.
        h.beginStreaming("c1")
        awaitLines(lines, 5)

        val events = lines.map { obj(it) }
        assertEquals(listOf("history.lost", "history.row", "history.row", "history.row", "history.row"), events.map { it.getValue("event").jsonPrimitive.content })
        assertEquals(16, events.first().getValue("since_last").jsonPrimitive.int)
        assertEquals(listOf(17L, 18L, 19L, 20L), events.drop(1).map { it.getValue("seq").jsonPrimitive.long })
    }

    @Test
    fun `the lost marker has the shape of its corpus fixture`() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/ipc-contract/events/history.lost.ndjson")) { "fixture missing" }
            .bufferedReader().readText().trim()
        val parsed = obj(fixture)
        assertEquals("history.lost", parsed.getValue("event").jsonPrimitive.content)
        assertEquals(setOf("event", "since_last"), parsed.keys)
        assertEquals(3, parsed.getValue("since_last").jsonPrimitive.int)
    }

    @Test
    fun `a closed connection stops its stream and a repeated subscribe leaves one writer`() = runBlocking {
        val h = open()
        val lines = CopyOnWriteArrayList<String>()
        h.start(scope) { _, line -> lines += line; true }
        h.subscribe("c1")
        h.beginStreaming("c1")
        h.subscribe("c1")
        h.beginStreaming("c1")
        record("/a")
        assertTrue(history!!.flush())
        awaitLines(lines, 1)
        delay(200)
        assertEquals(1, lines.size, "one writer: the row is written once")

        h.onConnectionClosed("c1")
        assertFalse(h.hasSubscribers())
        record("/b")
        assertTrue(history!!.flush())
        delay(200)
        assertEquals(1, lines.size)
    }

    private suspend fun awaitLines(
        lines: List<String>,
        count: Int,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (lines.size < count && System.currentTimeMillis() < deadline) delay(10)
        assertNotNull(lines.getOrNull(count - 1), "expected $count line(s), got $lines")
    }

    // ── the `order` member (gkrost/unidrive-cli#848) ─────────────────────────────────────────────

    // Rows with distinct sizes and results, one of them without a size; ids ascend with the wall clock.
    private fun seedOrderRows() {
        wallMs.set(1_000)
        history!!.begin(TransferDirection.UPLOAD, "/a-success.txt", bytesTotal = 100).let { it.started(); it.finish(TransferResult.SUCCESS, bytesDone = 100) }
        wallMs.set(2_000)
        history!!.begin(TransferDirection.UPLOAD, "/b-failed.txt", bytesTotal = 20).let { it.started(); it.finish(TransferResult.FAILED, errorClass = "network") }
        wallMs.set(3_000)
        history!!.begin(TransferDirection.DOWNLOAD, "/c-nosize.txt").let { it.started(); it.finish(TransferResult.INTERRUPTED, errorClass = "cancelled") }
        wallMs.set(4_000)
        history!!.begin(TransferDirection.UPLOAD, "/d-conflict.txt", bytesTotal = 5).let { it.started(); it.finish(TransferResult.CONFLICT, errorClass = "conflict") }
        wallMs.set(5_000)
        history!!.begin(TransferDirection.UPLOAD, "/e-skipped.txt", bytesTotal = 50).let { it.finish(TransferResult.SKIPPED) }
        assertTrue(history!!.flush())
    }

    private fun pathsOf(reply: JsonObject) = reply.getValue("rows").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }

    private fun order(field: String, direction: String) = """{"verb":"history.list","order":{"field":"$field","direction":"$direction"}}"""

    @Test
    fun `the size order sorts by bytes with the unmeasured after every known value in both directions`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        seedOrderRows()

        val asc = obj(h.list(order("size", "asc")))
        assertEquals(listOf("/d-conflict.txt", "/b-failed.txt", "/e-skipped.txt", "/a-success.txt", "/c-nosize.txt"), pathsOf(asc), "smallest first, null last")
        assertEquals(true, asc.getValue("live_keyset").jsonPrimitive.boolean, "a non-default order is not live")
        assertEquals("size", asc.getValue("order").jsonObject.getValue("field").jsonPrimitive.content)
        assertEquals("asc", asc.getValue("order").jsonObject.getValue("direction").jsonPrimitive.content)

        val desc = obj(h.list(order("size", "desc")))
        assertEquals(listOf("/a-success.txt", "/e-skipped.txt", "/b-failed.txt", "/d-conflict.txt", "/c-nosize.txt"), pathsOf(desc), "largest first, null still last")
        assertEquals("desc", desc.getValue("order").jsonObject.getValue("direction").jsonPrimitive.content)
    }

    @Test
    fun `the result order follows the fixed rank, not the string bytes`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        seedOrderRows()

        val asc = obj(h.list(order("result", "asc")))
        assertEquals(
            listOf("/b-failed.txt", "/d-conflict.txt", "/c-nosize.txt", "/e-skipped.txt", "/a-success.txt"),
            pathsOf(asc),
            "failed, conflict, interrupted, skipped, success (no running row was seeded)",
        )
    }

    @Test
    fun `the path order is unsigned byte order and ties break by operation id in the same direction`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        // 'Z' (0x5A) sorts before 'a' (0x61) in byte order; equal paths break by id.
        wallMs.set(1_000)
        history!!.begin(TransferDirection.UPLOAD, "/Z.txt", bytesTotal = 1).let { it.started(); it.finish(TransferResult.SUCCESS) }
        wallMs.set(2_000)
        val firstTie = history!!.begin(TransferDirection.UPLOAD, "/a.txt", bytesTotal = 1).let { it.started(); it.finish(TransferResult.SUCCESS); it }
        wallMs.set(3_000)
        val secondTie = history!!.begin(TransferDirection.UPLOAD, "/a.txt", bytesTotal = 1).let { it.started(); it.finish(TransferResult.FAILED); it }
        assertTrue(history!!.flush())

        val asc = obj(h.list(order("path", "asc")))
        assertEquals(listOf("/Z.txt", "/a.txt", "/a.txt"), pathsOf(asc), "'Z' before 'a', ids ascending")
        assertEquals(listOf("/a.txt", "/a.txt", "/Z.txt"), pathsOf(obj(h.list(order("path", "desc")))))
        // Equal primary values break by the operation id in the direction of the order.
        val ascIds = asc.getValue("rows").jsonArray.map { it.jsonObject.getValue("operation_id").jsonPrimitive.content }
        assertEquals(listOf("/Z.txt", "/a.txt", "/a.txt"), pathsOf(asc))
        assertEquals(
            listOf(firstTie.operationId, secondTie.operationId).sorted(),
            ascIds.drop(1),
            "equal paths break by the operation id ascending under an ascending order",
        )
        val descIds = obj(h.list(order("path", "desc"))).getValue("rows").jsonArray.map { it.jsonObject.getValue("operation_id").jsonPrimitive.content }
        assertEquals(
            listOf(firstTie.operationId, secondTie.operationId).sortedDescending(),
            descIds.dropLast(1),
            "and descending under a descending order",
        )
    }

    @Test
    fun `time ascending pages oldest first and the named default order is today's wire shape`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        seedOrderRows()

        val asc = obj(h.list(order("time", "asc")))
        assertEquals(listOf("/a-success.txt", "/b-failed.txt", "/c-nosize.txt", "/d-conflict.txt", "/e-skipped.txt"), pathsOf(asc), "oldest first")
        assertEquals(true, asc.getValue("live_keyset").jsonPrimitive.boolean, "time ascending is not the default order")

        // An explicitly named default order behaves exactly as the omitted one, with the echo saying so.
        val named = obj(h.list(order("time", "desc")))
        assertEquals(false, named.getValue("live_keyset").jsonPrimitive.boolean)
        assertEquals(pathsOf(obj(h.list("""{"verb":"history.list"}"""))), pathsOf(named))
        assertEquals(setOf("ok", "rows", "next_cursor", "order", "live_keyset", "tail_seq", "incomplete"), named.keys)
        // No order member at all: today's reply shape, no echo, no liveness flag.
        assertFalse(obj(h.list("""{"verb":"history.list"}""")).keys.contains("order"))
        assertFalse(obj(h.list("""{"verb":"history.list"}""")).keys.contains("live_keyset"))
    }

    @Test
    fun `an order that is not the contract's is refused`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        assertEquals("invalid_order", obj(h.list("""{"verb":"history.list","order":{"field":"bytes","direction":"asc"}}""")).getValue("error").jsonPrimitive.content)
        assertEquals("invalid_order", obj(h.list("""{"verb":"history.list","order":{"field":"size","direction":"up"}}""")).getValue("error").jsonPrimitive.content)
        assertEquals("invalid_order", obj(h.list("""{"verb":"history.list","order":{"field":"size"}}""")).getValue("error").jsonPrimitive.content, "both members are required")
        assertEquals("invalid_order", obj(h.list("""{"verb":"history.list","order":{"field":"size","direction":"asc","extra":1}}""")).getValue("error").jsonPrimitive.content)
        assertEquals("invalid_order", obj(h.list("""{"verb":"history.list","order":"size"}""")).getValue("error").jsonPrimitive.content)
        assertEquals("invalid_order", obj(h.list("""{"verb":"history.list","order":7}""")).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `a cursor of another order or another filter set is invalid_cursor`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        repeat(5) { record("/f$it") }
        assertTrue(history!!.flush())

        // The v1 cursor (the default order's) is not one a non-default order accepts.
        val v1 = obj(h.list("""{"verb":"history.list","limit":2}""")).getValue("next_cursor").jsonPrimitive.content
        assertEquals("invalid_cursor", obj(h.list("""{"verb":"history.list","limit":2,"order":{"field":"size","direction":"asc"},"cursor":"$v1"}""")).getValue("error").jsonPrimitive.content)

        // A cursor issued under one non-default order (or filter set) is not another's.
        val sizePage = obj(h.list("""{"verb":"history.list","limit":2,"order":{"field":"size","direction":"asc"}}"""))
        val sizeCursor = sizePage.getValue("next_cursor").jsonPrimitive.content
        assertEquals(2, sizePage.getValue("rows").jsonArray.size)
        assertEquals(
            "invalid_cursor",
            obj(h.list("""{"verb":"history.list","limit":2,"order":{"field":"path","direction":"asc"},"cursor":"$sizeCursor"}""")).getValue("error").jsonPrimitive.content,
            "the order the cursor carries is not the requested one",
        )
        assertEquals(
            "invalid_order",
            obj(h.list("""{"verb":"history.list","limit":2,"order":{"field":"size","direction":"asc","x":"y"}}""")).getValue("error").jsonPrimitive.content,
            "the order object is strict: exactly field and direction",
        )
        assertEquals(
            "invalid_cursor",
            obj(h.list("""{"verb":"history.list","limit":2,"order":{"field":"size","direction":"desc"},"cursor":"$sizeCursor"}""")).getValue("error").jsonPrimitive.content,
            "the order the cursor carries is not the requested one",
        )

        // A cursor issued under one normalised filter set is not another's.
        val filtered = obj(h.list("""{"verb":"history.list","limit":2,"order":{"field":"size","direction":"asc"},"filters":{"direction":"upload"}}"""))
        val filteredCursor = filtered.getValue("next_cursor")
        assertNotEquals(JsonNull, filteredCursor, "the filtered page has rows to carry on from")
        assertEquals(
            "invalid_cursor",
            obj(h.list("""{"verb":"history.list","limit":2,"order":{"field":"size","direction":"asc"},"cursor":"${filteredCursor.jsonPrimitive.content}"}""")).getValue("error").jsonPrimitive.content,
            "the filters the cursor was issued under are not the requested ones",
        )
        val carried = obj(h.list("""{"verb":"history.list","limit":2,"order":{"field":"size","direction":"asc"},"filters":{"direction":"upload"},"cursor":"${filteredCursor.jsonPrimitive.content}"}"""))
        assertEquals(2, carried.getValue("rows").jsonArray.size, "the same filters carry on")
        // And paging under one order and one filter set carries on.
        val wholePage = obj(h.list("""{"verb":"history.list","limit":2,"order":{"field":"size","direction":"asc"}}"""))
        val wholeCursor = wholePage.getValue("next_cursor").jsonPrimitive.content
        val next = obj(h.list("""{"verb":"history.list","limit":2,"order":{"field":"size","direction":"asc"},"cursor":"$wholeCursor"}"""))
        val seen = (wholePage.getValue("rows").jsonArray + next.getValue("rows").jsonArray).map { it.jsonObject.getValue("operation_id").jsonPrimitive.content }
        assertEquals(4, seen.toSet().size, "two pages cover four rows once each")
    }

    @Test
    fun `an ordered cursor survives a daemon restart`() = runBlocking {
        val h = open()
        h.start(scope) { _, _ -> true }
        seedOrderRows()
        val first = obj(h.list("""{"verb":"history.list","limit":3,"order":{"field":"result","direction":"asc"}}"""))
        val cursor = first.getValue("next_cursor").jsonPrimitive.content
        val firstPaths = pathsOf(first)

        runCatching { handler?.close() }
        runCatching { history?.close() }
        handler = null
        history = null
        val reopened = open()
        reopened.start(scope) { _, _ -> true }

        val second = obj(reopened.list("""{"verb":"history.list","limit":3,"order":{"field":"result","direction":"asc"},"cursor":"$cursor"}"""))
        assertEquals(firstPaths + pathsOf(second), pathsOf(obj(reopened.list(order("result", "asc")))), "the restart left the pages where they were")
    }

    // ── `history.retry` (gkrost/unidrive-cli#848) ────────────────────────────────────────────────

    private inner class ScriptedRetryEngine(
        /** The engine's half of the idempotency contract: the outcome is recorded durably before the reply. */
        private val record: Boolean = false,
        private val scripted: (path: String, payloadOperationId: String, requestId: String) -> HistoryRetryOutcome,
    ) : HistoryRetryEngine {
        val calls = CopyOnWriteArrayList<Triple<String, String, String>>()

        override fun retryUpload(
            path: String,
            payloadOperationId: String,
            requestId: String,
        ): HistoryRetryOutcome {
            calls += Triple(path, payloadOperationId, requestId)
            val outcome = scripted(path, payloadOperationId, requestId)
            if (record) {
                val at = java.util.concurrent.atomic.AtomicLong(wallMs.get())
                val row =
                    when (outcome) {
                        is HistoryRetryOutcome.Admitted -> org.krost.unidrive.telemetry.RetryOutcomeRecord(payloadOperationId, true, outcome.newOperationId, null, at.get())
                        is HistoryRetryOutcome.Refused -> org.krost.unidrive.telemetry.RetryOutcomeRecord(payloadOperationId, false, null, outcome.token, at.get())
                    }
                checkNotNull(history).recordRetryOutcome(requestId, row)
            }
            return outcome
        }
    }

    private fun openWithRetry(engine: HistoryRetryEngine?): HistoryRpcHandler {
        val h = TransferHistory.open("p1", db, clock = { wallMs.get() }).also { history = it }
        return HistoryRpcHandler(h, HistoryRpcHandler.OPEN_FAILED, retry = engine).also { handler = it }
    }

    // A failed upload, on record with its row id.
    private fun seedFailedUpload(path: String): String {
        wallMs.set(10_000)
        val handle = history!!.begin(TransferDirection.UPLOAD, path, bytesTotal = 10, maxAttempts = 3)
        handle.started()
        wallMs.set(11_000)
        handle.finish(TransferResult.FAILED, errorClass = "network", message = "connection reset")
        assertTrue(history!!.flush())
        return handle.operationId
    }

    private fun retry(opId: String, requestId: String, extra: String = "") = """{"verb":"history.retry","operation_id":"$opId","request_id":"$requestId"$extra}"""

    @Test
    fun `a well-formed retry reaches the engine with the row's path and answers its admission`() = runBlocking {
        val engine = ScriptedRetryEngine { path, payload, _ -> HistoryRetryOutcome.Admitted("new-row-id") }
        val h = openWithRetry(engine)
        val failedId = seedFailedUpload("/docs/f.txt")

        val reply = obj(h.retry(retry(failedId, "00000000-0000-0000-0000-000000000001")))
        assertTrue(reply.getValue("ok").jsonPrimitive.boolean)
        assertTrue(reply.getValue("accepted").jsonPrimitive.boolean)
        assertEquals("new-row-id", reply.getValue("operation_id").jsonPrimitive.content)
        assertEquals("00000000-0000-0000-0000-000000000001", reply.getValue("request_id").jsonPrimitive.content)
        assertEquals(listOf(Triple("/docs/f.txt", failedId, "00000000-0000-0000-0000-000000000001")), engine.calls, "the engine got the row's path, payload and request id")
    }

    @Test
    fun `a retry without an engine, or of a row that is not an eligible newest failed upload, is refused`() = runBlocking {
        val noEngine = openWithRetry(null)
        seedFailedUpload("/docs/f.txt")
        val listedBefore = obj(noEngine.list("""{"verb":"history.list"}""")).getValue("rows").jsonArray.single().jsonObject
        assertEquals(
            "retry_unavailable",
            obj(noEngine.retry(retry(listedBefore.getValue("operation_id").jsonPrimitive.content, "00000000-0000-0000-0000-000000000002"))).getValue("error").jsonPrimitive.content,
            "no engine: there is no retry, whatever the row",
        )

        // The engine is never asked about a row that is not an upload that failed or was interrupted.
        val engine = ScriptedRetryEngine { _, _, _ -> throw IllegalStateException("the engine is not asked for a refused row") }
        runCatching { handler?.close(); history?.close() }
        handler = null
        history = null
        val h = openWithRetry(engine)
        wallMs.set(12_000)
        history!!.begin(TransferDirection.DOWNLOAD, "/d.txt").let { it.started(); it.finish(TransferResult.FAILED, errorClass = "network") }
        wallMs.set(13_000)
        history!!.begin(TransferDirection.UPLOAD, "/s.txt").let { it.started(); it.finish(TransferResult.SUCCESS) }
        wallMs.set(14_000)
        history!!.begin(TransferDirection.UPLOAD, "/r.txt", maxAttempts = 3).let { it.started() }
        assertTrue(history!!.flush())
        val listed = obj(h.list("""{"verb":"history.list"}""")).getValue("rows").jsonArray.map { it.jsonObject }.associateBy { it.getValue("path").jsonPrimitive.content }
        for (path in listOf("/d.txt", "/s.txt", "/r.txt")) {
            assertEquals(
                "not_retryable",
                obj(h.retry(retry(listed.getValue(path).getValue("operation_id").jsonPrimitive.content, "00000000-0000-0000-0000-000000000003"))).getValue("error").jsonPrimitive.content,
                "not an upload that failed or was interrupted: $path",
            )
        }
        assertTrue(engine.calls.isEmpty(), "no refused row reached the engine")
    }

    @Test
    fun `a row a later row of its path superseded is not the newest and is not retryable`() = runBlocking {
        val engine = ScriptedRetryEngine { _, _, _ -> HistoryRetryOutcome.Admitted("new-row-id") }
        val h = openWithRetry(engine)
        val failedId = seedFailedUpload("/docs/f.txt")
        // A later success of the same path supersedes the failed row.
        wallMs.set(20_000)
        history!!.begin(TransferDirection.UPLOAD, "/docs/f.txt").let { it.started(); it.finish(TransferResult.SUCCESS) }
        assertTrue(history!!.flush())

        assertEquals("not_retryable", obj(h.retry(retry(failedId, "00000000-0000-0000-0000-000000000005"))).getValue("error").jsonPrimitive.content)
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `a row retention took away answers not_found`() = runBlocking {
        val h = openWithRetry(ScriptedRetryEngine { _, _, _ -> HistoryRetryOutcome.Admitted("new-row-id") })
        val reply = obj(h.retry(retry("00000000-0000-0000-0000-999999999999", "00000000-0000-0000-0000-000000000006")))
        assertEquals("not_found", reply.getValue("error").jsonPrimitive.content)
        assertEquals("00000000-0000-0000-0000-000000000006", reply.getValue("request_id").jsonPrimitive.content)
    }

    @Test
    fun `a malformed retry is refused before anything is looked up`() = runBlocking {
        val engine = ScriptedRetryEngine { _, _, _ -> HistoryRetryOutcome.Admitted("new-row-id") }
        val h = openWithRetry(engine)
        for (request in listOf(
            """{"verb":"history.retry"}""",
            """{"verb":"history.retry","operation_id":"x"}""",
            """{"verb":"history.retry","request_id":"00000000-0000-0000-0000-000000000001"}""",
            """{"verb":"history.retry","operation_id":"","request_id":"00000000-0000-0000-0000-000000000001"}""",
            """{"verb":"history.retry","operation_id":"x","request_id":"not-a-uuid"}""",
            """{"verb":"history.retry","operation_id":"x","request_id":"00000000-0000-0000-0000-000000000001","extra":1}""",
            """{"verb":"history.retry","operation_id":7,"request_id":"00000000-0000-0000-0000-000000000001"}""",
            """not json""",
        )) {
            assertEquals("invalid_request", obj(h.retry(request)).getValue("error").jsonPrimitive.content, "for $request")
        }
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun `an engine refusal passes through with the request id`() = runBlocking {
        val engine = ScriptedRetryEngine { _, _, _ -> HistoryRetryOutcome.Refused("busy") }
        val h = openWithRetry(engine)
        val failedId = seedFailedUpload("/docs/f.txt")
        val reply = obj(h.retry(retry(failedId, "00000000-0000-0000-0000-000000000007")))
        assertFalse(reply.getValue("ok").jsonPrimitive.boolean)
        assertEquals("busy", reply.getValue("error").jsonPrimitive.content)
        assertEquals("00000000-0000-0000-0000-000000000007", reply.getValue("request_id").jsonPrimitive.content)
    }

    @Test
    fun `a resend of one request id replays the stored answer, another payload is a conflict`() = runBlocking {
        val engine = ScriptedRetryEngine(record = true) { _, _, _ -> HistoryRetryOutcome.Admitted("new-row-id") }
        val h = openWithRetry(engine)
        val failedId = seedFailedUpload("/docs/f.txt")
        val requestId = "00000000-0000-0000-0000-000000000008"

        val first = obj(h.retry(retry(failedId, requestId)))
        assertTrue(first.getValue("accepted").jsonPrimitive.boolean)
        assertEquals(1, engine.calls.size)
        val again = obj(h.retry(retry(failedId, requestId)))
        assertTrue(again.getValue("accepted").jsonPrimitive.boolean, "the same outcome replays")
        assertEquals("new-row-id", again.getValue("operation_id").jsonPrimitive.content)
        assertEquals(1, engine.calls.size, "the engine is not asked again")

        // The same request id for another row is a reused idempotency key, not a retry.
        wallMs.set(30_000)
        val otherId = seedFailedUpload("/docs/other.txt")
        val conflict = obj(h.retry(retry(otherId, requestId)))
        assertEquals("idempotency_conflict", conflict.getValue("error").jsonPrimitive.content)
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `a resend after a refusal replays the refusal`() = runBlocking {
        val engine = ScriptedRetryEngine(record = true) { _, _, _ -> HistoryRetryOutcome.Refused("source_changed") }
        val h = openWithRetry(engine)
        val failedId = seedFailedUpload("/docs/f.txt")
        val requestId = "00000000-0000-0000-0000-000000000009"
        assertEquals("source_changed", obj(h.retry(retry(failedId, requestId))).getValue("error").jsonPrimitive.content)
        val again = obj(h.retry(retry(failedId, requestId)))
        assertEquals("source_changed", again.getValue("error").jsonPrimitive.content, "the same outcome replays")
        assertEquals(1, engine.calls.size, "the engine is not asked again")
    }
}
