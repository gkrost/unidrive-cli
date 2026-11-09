package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#581: a damaged state.db is told apart from an older schema. The open names the file and the
 * recovery instead of throwing the driver's SQLITE_CORRUPT, and the read-only open no longer reads a damaged file as
 * "no schema version (written by an older unidrive)".
 */
class StateDatabaseCorruptFileTest {
    private fun newDir(): Path = Files.createTempDirectory("unidrive-corrupt-db-test")

    private fun seededDb(dir: Path): Path {
        val path = dir.resolve("state.db")
        val db = StateDatabase(path)
        try {
            db.initialize()
            // Enough rows that the file is several pages long, so a cut leaves a header that promises more than is there.
            for (i in 0 until 300) db.setSyncState("key-$i", "v".repeat(200))
        } finally {
            db.close()
        }
        // One file: no -wal/-shm beside it, like the chaos test.
        Files.deleteIfExists(dir.resolve("state.db-wal"))
        Files.deleteIfExists(dir.resolve("state.db-shm"))
        return path
    }

    private fun truncated(dir: Path): Path {
        val path = seededDb(dir)
        val bytes = Files.readAllBytes(path)
        check(bytes.size > 5000) { "the seeded file is only ${bytes.size} bytes" }
        Files.write(path, bytes.copyOf(5000))
        return path
    }

    private fun garbage(dir: Path): Path {
        val path = dir.resolve("state.db")
        Files.write(path, ByteArray(8192) { (it * 31 + 7).toByte() })
        return path
    }

    private fun assertCorrupt(
        path: Path,
        readOnly: Boolean,
    ) {
        val before = Files.readAllBytes(path)
        val ex = assertFailsWith<StateDbCorruptException> { StateDatabase(path, readOnly = readOnly).initialize() }
        assertEquals(path, ex.dbPath)
        val msg = ex.message!!
        assertTrue("corrupt" in msg, msg)
        assertTrue(path.toString() in msg, "names the file: $msg")
        assertTrue("move" in msg && "aside" in msg, "names the recovery: $msg")
        assertTrue("rebuilds the state from the cloud" in msg, msg)
        assertTrue("lost" in msg, "says what the recovery costs: $msg")
        assertFalse("unidrive sync" in msg || "older unidrive" in msg, "not the upgrade advice: $msg")
        assertTrue(before.contentEquals(Files.readAllBytes(path)), "the open must not touch the file")
    }

    @Test
    fun `a truncated state_db is reported as corrupt on a writable open`() = assertCorrupt(truncated(newDir()), readOnly = false)

    @Test
    fun `a truncated state_db is reported as corrupt on a read-only open`() = assertCorrupt(truncated(newDir()), readOnly = true)

    @Test
    fun `a garbage state_db is reported as corrupt on a writable open`() = assertCorrupt(garbage(newDir()), readOnly = false)

    @Test
    fun `a garbage state_db is reported as corrupt on a read-only open`() = assertCorrupt(garbage(newDir()), readOnly = true)

    @Test
    fun `a database with no schema version still asks for the upgrade, not for a recovery`() {
        val path = newDir().resolve("state.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use {
            it.createStatement().use { st -> st.execute("CREATE TABLE sync_state(key TEXT PRIMARY KEY, value TEXT)") }
        }
        val ex = assertFailsWith<IllegalStateException> { StateDatabase(path, readOnly = true).initialize() }
        assertFalse(ex is StateDbCorruptException, "an older schema is not a corrupt file")
        assertTrue("older unidrive" in ex.message!! && "unidrive sync" in ex.message!!, ex.message)
    }

    @Test
    fun `a writable open of a healthy state_db is unaffected`() {
        val path = seededDb(newDir())
        val db = StateDatabase(path)
        try {
            db.initialize()
            assertEquals("v".repeat(200), db.getSyncState("key-5"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `isCorruption recognises the driver codes and texts and nothing else`() {
        assertTrue(StateDbCorruptException.isCorruption(java.sql.SQLException("x", "S", 11)))
        assertTrue(StateDbCorruptException.isCorruption(java.sql.SQLException("x", "S", 26)))
        assertTrue(StateDbCorruptException.isCorruption(java.sql.SQLException("x", "S", 11 or (1 shl 8))))
        assertTrue(StateDbCorruptException.isCorruption(java.sql.SQLException("[SQLITE_CORRUPT] The database disk image is malformed")))
        assertFalse(StateDbCorruptException.isCorruption(java.sql.SQLException("[SQLITE_BUSY] The database file is locked", "S", 5)))
        assertTrue(StateDbCorruptException.isCorruption(RuntimeException("wrapper", java.sql.SQLException("x", "S", 11))))
        assertFalse(StateDbCorruptException.isCorruption(null as Throwable?))
    }
}
