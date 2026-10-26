package org.krost.unidrive.hydration

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.telemetry.HistoryQuery
import org.krost.unidrive.telemetry.TransferHistory
import org.krost.unidrive.telemetry.TransferResult
import org.krost.unidrive.telemetry.TransferRow
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#134: a client that offers the same refused file again and again (open_write every few minutes) must not
 * add a failed history row per offer: `history.list result=failed` shows the refusal once.
 */
class HydrationRefusalHistoryTest {
    private val dir: Path = Files.createTempDirectory("ud-hydration-refusal-history")
    private val history: TransferHistory = TransferHistory.open("p1", dir.resolve("telemetry.db"))

    @AfterTest
    fun tearDown() {
        history.close()
        runCatching { dir.toFile().deleteRecursively() }
    }

    private fun failedRows(): List<TransferRow> {
        assertTrue(history.flush())
        history.openReader().use { reader ->
            return reader.query(HistoryQuery(results = setOf(TransferResult.FAILED), limit = HistoryQuery.MAX_LIMIT)).rows
        }
    }

    @Test
    fun `a refused file offered again is one failed row, not one per offer`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this, history = history)
        env.stateDb.insertCreatedRow("/trashed/new.txt")
        val cache = env.syncEngine.resolveCachePath("/trashed/new.txt")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "the user's bytes")
        env.syncEngine.refuseUploadsParentGone(1, folder = "/trashed")

        for (offer in 1..6) {
            env.hydration.openForWrite("conn1", "h$offer", "/trashed/new.txt", cache)
            advanceUntilIdle()
        }

        assertEquals(1, env.syncEngine.uploadAttempts(), "only the first offer reached the provider")
        val rows = failedRows()
        assertEquals(1, rows.size, "one row for the path: ${rows.map { it.errorClass }}")
        assertEquals("/trashed/new.txt", rows.single().path)
        assertEquals("ParentGoneException", rows.single().errorClass, "the row of the real refusal")
        assertEquals(1, rows.single().attempts, "the repeats made no attempt")
    }
}
