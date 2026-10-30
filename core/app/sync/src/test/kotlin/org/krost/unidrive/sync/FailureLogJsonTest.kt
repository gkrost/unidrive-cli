package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * #216: every failures.jsonl line is one valid JSON record that reads back as written, whatever the path, the
 * move's source or the error message holds. The line used to be a string template: `path` and `from_path` were not
 * escaped at all and `error` only for `"`, so a quote, a backslash or a line break broke the record or changed its value.
 */
class FailureLogJsonTest {
    private lateinit var syncRoot: Path
    private lateinit var stateDir: Path
    private lateinit var failureLog: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-failure-log-root")
        stateDir = Files.createTempDirectory("ud-failure-log-state")
        failureLog = stateDir.resolve("failures.jsonl")
        db = StateDatabase(stateDir.resolve("state.db")).also { it.initialize() }
        provider = FakeCloudProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                failureLogPath = failureLog,
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
        syncRoot.toFile().deleteRecursively()
        stateDir.toFile().deleteRecursively()
    }

    private val ts = Instant.parse("2026-10-30T10:00:00Z")

    // A quote, a backslash (`\b` would read back as a backspace), a line break and a tab in one name.
    private val nastyPath = "/Doc \"quoted\" a\\b\nsecond line\t.txt"
    private val nastyFrom = "/Old \"name\" c\\d\r\nx.txt"

    // A multi-line message with a Windows path: `\U` is no JSON escape, `\n` in it is.
    private val windowsMessage = "upload refused for C:\\Users\\me\\new\\file.txt\n  caused by: 403 \"Forbidden\""

    private fun parse(line: String): JsonObject {
        assertFalse('\n' in line || '\r' in line, "one record is one line, got: $line")
        return Json.parseToJsonElement(line).jsonObject
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `a path with a quote, a backslash and a line break reads back exactly`() {
        val obj = parse(SyncEngine.formatFailureJson("up", SyncAction.Upload(nastyPath), RuntimeException("boom"), ts))

        assertEquals(listOf("ts", "action", "path", "error"), obj.keys.toList(), "the field names and their order stay")
        assertEquals("2026-10-30T10:00:00Z", obj.text("ts"))
        assertEquals("up", obj.text("action"))
        assertEquals(nastyPath, obj.text("path"))
        assertEquals("boom", obj.text("error"))
    }

    @Test
    fun `a move records both paths, and its source reads back exactly`() {
        val move = SyncAction.MoveRemote(path = nastyPath, fromPath = nastyFrom, remoteId = "id-1")
        val obj = parse(SyncEngine.formatFailureJson("move", move, RuntimeException("boom"), ts))

        assertEquals(listOf("ts", "action", "path", "from_path", "error"), obj.keys.toList())
        assertEquals(nastyPath, obj.text("path"), "path keeps the destination")
        assertEquals(nastyFrom, obj.text("from_path"))
    }

    @Test
    fun `a multi-line message with a Windows path reads back exactly`() {
        val obj = parse(SyncEngine.formatFailureJson("up", SyncAction.Upload("/a.txt"), RuntimeException(windowsMessage), ts))

        assertEquals(windowsMessage, obj.text("error"))
    }

    @Test
    fun `a failure without a message records the exception's class name`() {
        val obj = parse(SyncEngine.formatFailureJson("del-remote", SyncAction.DeleteRemote("/a.txt"), IllegalStateException(), ts))

        assertEquals("IllegalStateException", obj.text("error"))
        assertNull(obj["from_path"], "only a move has a source")
    }

    // The engine's own write: a failed upload lands in failures.jsonl through logFailure, one record per line.
    @Test
    fun `a failed upload writes one parseable record to failures jsonl`() =
        runTest {
            Files.writeString(syncRoot.resolve("a.txt"), "hello")
            provider.uploadThrowByPath["/a.txt"] = java.io.IOException(windowsMessage)

            engine.syncOnce()

            val lines = Files.readAllLines(failureLog).filter { it.isNotEmpty() }
            assertEquals(1, lines.size, "one failure is one line, got: $lines")
            val obj = parse(lines.single())
            assertEquals("up", obj.text("action"))
            assertEquals("/a.txt", obj.text("path"))
            assertEquals(windowsMessage, obj.text("error"))
        }
}
