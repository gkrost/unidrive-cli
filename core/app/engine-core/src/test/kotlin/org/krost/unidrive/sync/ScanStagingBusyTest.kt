package org.krost.unidrive.sync

import java.nio.file.Path
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * gkrost/unidrive-cli#1034, the staging side of #1033: a busy BEGIN of a staging transaction is retried a bounded
 * number of times (the file is single-writer among unidrive processes, but a foreign handle on Windows can still
 * answer busy), and a BEGIN that never lands leaves the autoCommit flag restored — sqlite-jdbc flips the flag before
 * it runs BEGIN, so a flag left false would make every later staging transaction of the connection run statement by
 * statement, rollbacks undoing nothing.
 */
class ScanStagingBusyTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-staging-busy")
        dbFile = dir.resolve("state.db")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun stageAPage(connector: StateDatabaseConnector): Int {
        val db = StateDatabase(dbFile, connector = connector).also { it.initialize() }
        try {
            val scanId = db.beginScan(initialMarker = null)
            db.persistScanPage(scanId, StateDatabaseScanStagingTest.items(0, 5), marker = "page-0")
            return db.loadStagedItems(scanId).size
        } finally {
            db.close()
        }
    }

    @Test
    fun `a busy BEGIN of a staging transaction is retried and the page lands`() {
        val connector = BusyBeginConnector(failFirst = 1)

        assertEquals(5, stageAPage(connector), "the page landed after the retry")

        assertEquals(1, connector.refused, "exactly one BEGIN was refused as busy")
        assertTrue(connector.begins > connector.refused, "the retry issued the BEGIN again and the scan landed")
    }

    @Test
    fun `a BEGIN that never lands fails with busy and leaves the autoCommit flag restored`() {
        val connector = BusyBeginConnector(failFirst = Int.MAX_VALUE)

        val failure = assertFailsWith<SQLException> { stageAPage(connector) }

        assertTrue(failure.message!!.contains("SQLITE_BUSY"), failure.message)
        assertEquals(SQLITE_BUSY_ATTEMPTS, connector.refused, "every attempt of the bounded retry ran")
        assertEquals(true, connector.lastStagingAutoCommit, "the failed BEGIN put the autoCommit flag back")
    }
}
