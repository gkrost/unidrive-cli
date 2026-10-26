package org.krost.unidrive.telemetry

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#134: the same refusal of the same path, offered again, is one history row whose finish instant says when
 * it was last seen, not a row per offer.
 */
class TransferHistoryRefusalTest {
    private lateinit var dir: Path
    private lateinit var history: TransferHistory
    private val wallMs = AtomicLong(1_700_000_000_000L)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("telemetry-refusal-test")
        history = TransferHistory.open("p1", dir.resolve("telemetry.db"), clock = { wallMs.get() }, nanoClock = { wallMs.get() * 1_000_000L })
    }

    @AfterTest
    fun tearDown() {
        runCatching { history.close() }
        runCatching { Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } } }
    }

    private fun rowsOf(path: String): List<TransferRow> {
        assertTrue(history.flush())
        return history.rows().filter { it.path == path }
    }

    private fun refuseAgain(
        path: String,
        reason: String,
    ) {
        wallMs.addAndGet(120_000)
        history.begin(TransferDirection.UPLOAD, path).finishRefused(reason, "refused_earlier", reason, attempted = false)
    }

    @Test
    fun `a refusal offered again is the row it already has, seen later`() {
        val first = history.begin(TransferDirection.UPLOAD, "/a/f.txt")
        first.started()
        first.finishRefused("parent_gone: /a", "ParentGoneException", "parent folder is gone")
        val firstRow = rowsOf("/a/f.txt").single()

        repeat(5) { refuseAgain("/a/f.txt", "parent_gone: /a") }

        val row = rowsOf("/a/f.txt").single()
        assertEquals(firstRow.operationId, row.operationId, "the first refusal's row stays the row")
        assertEquals("failed", row.result.wire)
        assertEquals("ParentGoneException", row.errorClass, "what the first answer said is kept")
        assertEquals(1, row.attempts, "the repeats made no attempt")
        assertEquals(wallMs.get(), row.finishedAt, "it was last seen at the last offer")
        assertTrue(row.finishedAt!! > firstRow.finishedAt!!)
    }

    @Test
    fun `a different reason, another path, or a transfer that ended otherwise gets its own row`() {
        history.begin(TransferDirection.UPLOAD, "/a/f.txt").finishRefused("parent_gone: /a", "ParentGoneException", "gone")
        refuseAgain("/a/f.txt", "something else")
        refuseAgain("/a/g.txt", "parent_gone: /a")
        assertEquals(2, rowsOf("/a/f.txt").size, "another reason is another row")
        assertEquals(1, rowsOf("/a/g.txt").size)

        // The path's upload goes through in between: the next refusal is a new fact.
        val ok = history.begin(TransferDirection.UPLOAD, "/a/g.txt")
        ok.started()
        ok.finish(TransferResult.SUCCESS, bytesDone = 3)
        refuseAgain("/a/g.txt", "parent_gone: /a")
        assertEquals(listOf("failed", "success", "failed").sorted(), rowsOf("/a/g.txt").map { it.result.wire }.sorted())
    }

    @Test
    fun `a refusal that was really attempted again replaces the earlier row of the path`() {
        val first = history.begin(TransferDirection.UPLOAD, "/a/f.txt")
        first.started()
        first.finishRefused("parent_gone: /a", "ParentGoneException", "gone")
        val firstId = rowsOf("/a/f.txt").single().operationId

        wallMs.addAndGet(60_000)
        val second = history.begin(TransferDirection.UPLOAD, "/a/f.txt")
        second.started()
        second.finishRefused("parent_gone: /a", "ParentGoneException", "gone")

        val rows = rowsOf("/a/f.txt")
        assertEquals(1, rows.size, "one failed row for the path, not two")
        assertNotEquals(firstId, rows.single().operationId, "the attempt that just ended is the row")
        assertEquals("failed", rows.single().result.wire)
    }
}
