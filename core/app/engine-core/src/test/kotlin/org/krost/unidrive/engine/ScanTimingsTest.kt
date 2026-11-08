package org.krost.unidrive.engine

import org.krost.unidrive.sync.StateDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ScanTimings stores the per-phase scan seconds and counts under the stored names (gkrost/unidrive-cli#269). */
class ScanTimingsTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private lateinit var timings: ScanTimings

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-scan-timings")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
        timings = ScanTimings(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `all four keys are absent on a new database`() {
        assertNull(timings.remoteSecs)
        assertNull(timings.remoteCount)
        assertNull(timings.localSecs)
        assertNull(timings.localCount)
    }

    @Test
    fun `the local pair round-trips under its stored names, seconds first`() {
        timings.recordLocal(42, 1234)
        assertEquals("42", db.getSyncState("last_scan_secs_local"))
        assertEquals("1234", db.getSyncState("last_scan_count_local"))
        assertEquals(42L, timings.localSecs)
        assertEquals(1234, timings.localCount)
    }

    @Test
    fun `the remote pair round-trips under its stored names, seconds first`() {
        timings.recordRemote(7, 99)
        assertEquals("7", db.getSyncState("last_scan_secs_remote"))
        assertEquals("99", db.getSyncState("last_scan_count_remote"))
        assertEquals(7L, timings.remoteSecs)
        assertEquals(99, timings.remoteCount)
    }

    @Test
    fun `a second record replaces the stored text`() {
        timings.recordRemote(7, 99)
        timings.recordRemote(10, 20)
        assertEquals(10L, timings.remoteSecs)
        assertEquals(20, timings.remoteCount)
    }

    @Test
    fun `a stored non-number reads as null`() {
        db.setSyncState("last_scan_secs_local", "abc")
        assertNull(timings.localSecs)
    }
}
