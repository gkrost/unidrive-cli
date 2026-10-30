package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** #222: the scan staging file opens like state.db does, so a refused switch to WAL costs a warning, not the scan. */
class ScanStagingWalTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-staging-wal")
        dbFile = dir.resolve("state.db")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun journalModeOfStaging(): String =
        DriverManager.getConnection("jdbc:sqlite:${dir.resolve(StateDatabase.SCAN_STAGING_FILE_NAME)}").use { c ->
            c.createStatement().use { st -> st.executeQuery("PRAGMA journal_mode").use { rs -> rs.next(); rs.getString(1) } }
        }

    private fun stageAPage(connector: StateDatabaseConnector) {
        val db = StateDatabase(dbFile, connector = connector).also { it.initialize() }
        try {
            val scanId = db.beginScan(initialMarker = null)
            db.persistScanPage(scanId, StateDatabaseScanStagingTest.items(0, 5), marker = "page-0")
            assertEquals(5, db.loadStagedItems(scanId).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a staging file whose switch to WAL is refused as busy stays in its journal mode and the scan goes on`() {
        stageAPage(WalSwitchRefusingConnector(busy = true))

        assertEquals("delete", journalModeOfStaging())
    }

    @Test
    fun `a staging file whose file system answers another mode stays in that mode and the scan goes on`() {
        stageAPage(WalSwitchRefusingConnector(busy = false))

        assertEquals("delete", journalModeOfStaging())
    }

    @Test
    fun `the staging file runs in WAL and the next open after a refusal switches it`() {
        stageAPage(WalSwitchRefusingConnector(busy = true))
        stageAPage(JdbcStateDatabaseConnector)

        assertEquals("wal", journalModeOfStaging())
    }
}
