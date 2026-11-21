package org.krost.unidrive.sync

import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// #865: a VACUUM needs twice the file in its folder and the live pages in SQLite's temp directory.
class RewriteRoomTest {
    private val folder = Path.of("/data/profile")
    private val file = folder.resolve("telemetry.db")
    private val temp = Path.of("/tmp")

    private fun room(
        space: Map<Path, Long>,
        same: Boolean,
        tempDir: Path? = temp,
    ) = RewriteRoom(usableSpace = { space.getValue(it) }, tempDir = { tempDir }, sameStore = { _, _ -> same })

    @Test
    fun `on one filesystem the folder needs room for the WAL copy and the temp copy together`() {
        // file 100, live 60: 2 * 100 + 60 = 260
        assertEquals(true, room(mapOf(folder to 260L), same = true).check(file, 100, 60))
        assertEquals(false, room(mapOf(folder to 259L), same = true).check(file, 100, 60))
    }

    @Test
    fun `on two filesystems each needs its own share`() {
        assertEquals(true, room(mapOf(folder to 200L, temp to 60L), same = false).check(file, 100, 60))
        assertEquals(false, room(mapOf(folder to 200L, temp to 59L), same = false).check(file, 100, 60))
        assertEquals(false, room(mapOf(folder to 199L, temp to 1_000L), same = false).check(file, 100, 60))
    }

    @Test
    fun `without a temp directory the folder stands in for it`() {
        assertEquals(true, room(mapOf(folder to 260L), same = false, tempDir = null).check(file, 100, 60))
        assertEquals(false, room(mapOf(folder to 259L), same = false, tempDir = null).check(file, 100, 60))
    }

    @Test
    fun `a space read that fails answers nothing`() {
        val failing = RewriteRoom(usableSpace = { throw IOException("no file store") }, tempDir = { temp }, sameStore = { _, _ -> false })
        assertNull(failing.check(file, 100, 60))
    }
}
