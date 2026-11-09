package org.krost.unidrive.sync

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** gkrost/unidrive-cli#581: the move-aside of a corrupt state.db keeps every byte and the sidecars. */
class StateDbRecoveryTest {
    @Test
    fun `moveAside keeps the file, its wal and shm and the scan staging under a corrupt-time name`() {
        val dir = Files.createTempDirectory("unidrive-recovery-test")
        val db = dir.resolve("state.db")
        Files.write(db, byteArrayOf(1))
        Files.write(dir.resolve("state.db-wal"), byteArrayOf(2))
        Files.write(dir.resolve("state.db-shm"), byteArrayOf(3))
        Files.write(dir.resolve(StateDatabase.SCAN_STAGING_FILE_NAME), byteArrayOf(4))

        val r = StateDbRecovery.moveAside(db, Instant.parse("2026-11-09T14:30:05Z"))

        assertEquals(dir.resolve("state.db.corrupt-20261109T143005Z"), r.movedTo)
        assertEquals(Instant.parse("2026-11-09T14:30:05Z").toEpochMilli(), r.atMs)
        assertTrue(byteArrayOf(1).contentEquals(Files.readAllBytes(r.movedTo)))
        assertTrue(byteArrayOf(2).contentEquals(Files.readAllBytes(dir.resolve("state.db.corrupt-20261109T143005Z-wal"))))
        assertTrue(byteArrayOf(3).contentEquals(Files.readAllBytes(dir.resolve("state.db.corrupt-20261109T143005Z-shm"))))
        assertTrue(byteArrayOf(4).contentEquals(Files.readAllBytes(dir.resolve("scan-staging.db.corrupt-20261109T143005Z"))))
        assertFalse(Files.exists(db) || Files.exists(dir.resolve("state.db-wal")) || Files.exists(dir.resolve("state.db-shm")))
        assertFalse(Files.exists(dir.resolve(StateDatabase.SCAN_STAGING_FILE_NAME)))
        assertTrue("lost" in r.message && r.movedTo.toString() in r.message, r.message)
    }
}
