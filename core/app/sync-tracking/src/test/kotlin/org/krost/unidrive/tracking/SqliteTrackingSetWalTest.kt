package org.krost.unidrive.tracking

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** #222: tracking.db runs in WAL like state.db, so a CLI command reading it does not fail the daemon's write. */
class SqliteTrackingSetWalTest {
    @Test
    fun `the file runs in WAL and the mode stays with the file`() {
        val dir = Files.createTempDirectory("tracking-wal")
        try {
            val dbPath = dir.resolve("tracking.db")
            val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
            assertEquals("5000", tracking.pragma("busy_timeout"), "the shared timeout, not the driver's 3,000")
            tracking.close()

            val mode =
                DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
                    c.createStatement().use { st -> st.executeQuery("PRAGMA journal_mode").use { rs -> rs.next(); rs.getString(1) } }
                }
            assertEquals("wal", mode, "a connection that sets nothing (an older build) finds the file in WAL")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
