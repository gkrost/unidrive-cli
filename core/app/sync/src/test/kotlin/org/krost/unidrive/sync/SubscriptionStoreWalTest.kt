package org.krost.unidrive.sync

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

/** #222: the subscription table shares state.db, so its connection must not switch the file away from WAL or fail a commit as busy. */
class SubscriptionStoreWalTest {
    @Test
    fun `a store on a fresh file runs it in WAL`() {
        val dir = Files.createTempDirectory("sub-store-wal")
        try {
            val dbPath = dir.resolve("state.db")
            val store = SubscriptionStore(dbPath).also { it.initialize() }
            assertEquals("5000", store.pragma("busy_timeout"), "the shared timeout, not the driver's 3,000")
            store.close()

            val mode =
                DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
                    c.createStatement().use { st -> st.executeQuery("PRAGMA journal_mode").use { rs -> rs.next(); rs.getString(1) } }
                }
            assertEquals("wal", mode)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
