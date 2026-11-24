package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.telemetry.TransferHistory
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `history.report` (gkrost/unidrive-cli#848 item 3, docs/dev/specs/unidrive-daemon-design.md §4.8): the mount host reports an
 * operation of its own; the daemon validates it and writes it as one row of its history, with the source named. One test
 * per invariant: the row is delivered once, a resend never adds a second, a bad request writes nothing, and the verb is not
 * for a read-scope client.
 */
class HistoryReportRpcTest {
    private lateinit var dir: Path
    private var history: TransferHistory? = null
    private var handler: HistoryRpcHandler? = null
    private val nowMs = 1_700_000_100_000L

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("history-report-rpc")
    }

    @AfterTest
    fun tearDown() {
        runCatching { handler?.close() }
        runCatching { history?.close() }
        runCatching { Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } } }
    }

    private fun open(): HistoryRpcHandler {
        val h = TransferHistory.open("p1", dir.resolve("telemetry.db"), clock = { nowMs }).also { history = it }
        return HistoryRpcHandler(h, HistoryRpcHandler.OPEN_FAILED, clock = { nowMs }).also { handler = it }
    }

    private fun obj(reply: String): JsonObject = Json.parseToJsonElement(reply).jsonObject

    private fun report(
        requestId: String = REQ,
        vararg override: Pair<String, Any?>,
    ): String {
        val members =
            linkedMapOf<String, Any?>(
                "verb" to "history.report",
                "request_id" to requestId,
                "source" to "windows-host",
                "operation" to "journal_replay",
                "path" to "/docs/a.txt",
                "result" to "success",
                "started_at_ms" to nowMs - 2_000,
                "finished_at_ms" to nowMs - 1_000,
                "bytes" to 42,
            )
        override.forEach { (k, v) -> if (v == REMOVE) members.remove(k) else members[k] = v }
        return members.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":" + (if (v is String) JsonPrimitive(v).toString() else v.toString())
        }
    }

    private fun listRows(h: HistoryRpcHandler): List<JsonObject> =
        runBlocking { obj(h.list("""{"verb":"history.list"}""")).getValue("rows").jsonArray.map { it.jsonObject } }

    @Test
    fun `a report is delivered once and listed with its source and operation`() {
        val h = open()

        val reply = obj(runBlocking { h.report(report()) })

        assertTrue(reply.getValue("ok").jsonPrimitive.boolean, reply.toString())
        assertEquals("host:$REQ", reply.getValue("operation_id").jsonPrimitive.content)
        assertEquals(REQ, reply.getValue("request_id").jsonPrimitive.content)
        val row = listRows(h).single()
        assertEquals("host:$REQ", row.getValue("operation_id").jsonPrimitive.content)
        assertEquals("windows-host", row.getValue("source").jsonPrimitive.content)
        assertEquals("journal_replay", row.getValue("operation").jsonPrimitive.content)
        assertEquals("other", row.getValue("direction").jsonPrimitive.content)
        assertEquals("/docs/a.txt", row.getValue("path").jsonPrimitive.content)
        assertEquals("success", row.getValue("result").jsonPrimitive.content)
    }

    @Test
    fun `a resend of the same report replays the answer and adds no second row`() {
        val h = open()
        val first = runBlocking { h.report(report()) }

        val second = runBlocking { h.report(report()) }

        assertEquals(first, second)
        assertEquals(1, listRows(h).size)
    }

    @Test
    fun `the same request id with another payload is idempotency_conflict and changes nothing`() {
        val h = open()
        runBlocking { h.report(report()) }

        val reply = obj(runBlocking { h.report(report(REQ, "path" to "/docs/other.txt")) })

        assertFalse(reply.getValue("ok").jsonPrimitive.boolean)
        assertEquals("idempotency_conflict", reply.getValue("error").jsonPrimitive.content)
        assertEquals("/docs/a.txt", listRows(h).single().getValue("path").jsonPrimitive.content)
    }

    @Test
    fun `a malformed report is invalid_request and writes nothing`() {
        val h = open()
        val bad =
            listOf(
                "no request id" to report(REQ, "request_id" to REMOVE),
                "request id not a uuid" to report("not-a-uuid"),
                "request id not canonical" to report(REQ.uppercase()),
                "an extra member" to report(REQ, "extra" to 1),
                "no path" to report(REQ, "path" to REMOVE),
                "a relative path" to report(REQ, "path" to "docs/a.txt"),
                "a control character in the path" to report(REQ, "path" to "/docs/a\u0001.txt"),
                "an empty operation" to report(REQ, "operation" to ""),
                "an operation with upper case" to report(REQ, "operation" to "Journal"),
                "a running result" to report(REQ, "result" to "running"),
                "an unknown result" to report(REQ, "result" to "done"),
                "a finish instant in the future" to report(REQ, "finished_at_ms" to nowMs + 10 * 60_000),
                "a negative finish instant" to report(REQ, "finished_at_ms" to -1),
                "a start after the finish" to report(REQ, "started_at_ms" to nowMs),
                "negative bytes" to report(REQ, "bytes" to -1),
                "a string finish instant" to report(REQ, "finished_at_ms" to "1"),
                "not an object" to "[1]",
            )
        for ((label, request) in bad) {
            val reply = obj(runBlocking { h.report(request) })
            assertEquals("invalid_request", reply["error"]?.jsonPrimitive?.content, "$label: $reply")
        }
        assertEquals(0, listRows(h).size)
    }

    @Test
    fun `a source that is not on the allow-list is unknown_source and writes nothing`() {
        val h = open()

        val reply = obj(runBlocking { h.report(report(REQ, "source" to "some-other-app")) })

        assertEquals("unknown_source", reply.getValue("error").jsonPrimitive.content)
        assertEquals(0, listRows(h).size)
    }

    @Test
    fun `a daemon without history answers history_unavailable`() {
        val reply = obj(runBlocking { HistoryRpcHandler(null, HistoryRpcHandler.MIRROR).report(report()) })

        assertEquals("history_unavailable", reply.getValue("error").jsonPrimitive.content)
        assertEquals("mirror", reply.getValue("reason").jsonPrimitive.content)
        assertNull(reply["ok"]?.jsonPrimitive?.boolean?.takeIf { it })
    }

    @Test
    fun `history report is in the write class, not for a read-scope client`() {
        assertEquals(IpcAuth.VerbClass.WRITE, IpcAuth.classOf("history.report"))
    }

    private companion object {
        const val REQ = "00000000-0000-0000-0000-00000000a001"
        val REMOVE = Any()
    }
}
