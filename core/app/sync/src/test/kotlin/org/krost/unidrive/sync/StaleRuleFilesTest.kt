package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#642: which local rule file is "older than the cloud copy" (replaced before the plan, or its folder
 * waits), and what is put back when the download that replaced it is not the cloud's copy.
 */
class StaleRuleFilesTest {
    private val dir = TestScratch.directory("unidrive-642")
    private val local: Path = dir.resolve(".unidrive.ignore")
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        db = StateDatabase(dir.resolve("state.db"))
        db.initialize()
        Files.writeString(local, "*.tmp\n")
        Files.setLastModifiedTime(local, FileTime.fromMillis(MTIME))
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private val row =
        SyncEntry(
            path = "/.unidrive.ignore",
            remoteId = "rule",
            remoteHash = "old-hash",
            remoteSize = 6,
            remoteModified = Instant.parse("2026-01-01T00:00:00Z"),
            localMtime = MTIME,
            localSize = 6,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.now(),
        )

    private fun listed(
        hash: String,
        modified: String = "2026-02-01T00:00:00Z",
    ) = CloudItem(
        id = "rule",
        name = ".unidrive.ignore",
        path = "/.unidrive.ignore",
        size = 12,
        isFolder = false,
        modified = Instant.parse(modified),
        created = null,
        hash = hash,
        mimeType = null,
    )

    @Test
    fun `an unchanged local rule file is stale when the listing reports other content`() {
        assertTrue(staleLocalRuleFile(row, listed("new-hash"), local))
    }

    @Test
    fun `a local rule file edited here is not stale, the plan decides the conflict`() {
        Files.writeString(local, "*.tmp\n*.bak\n")

        assertFalse(staleLocalRuleFile(row, listed("new-hash"), local))
    }

    @Test
    fun `a cloud change of the time alone with the same content is not stale`() {
        assertFalse(staleLocalRuleFile(row, listed("old-hash"), local))
    }

    @Test
    fun `a rule file the listing does not report, or reports deleted, is not stale`() {
        assertFalse(staleLocalRuleFile(row, null, local))
        assertFalse(staleLocalRuleFile(row, listed("new-hash").copy(deleted = true), local))
    }

    @Test
    fun `a download that is not the cloud copy puts the replaced local rule file and its row back`() {
        db.upsertEntry(row)
        val before = RuleFileBefore.of(db.getEntry(row.path), local)
        Files.writeString(local, "garbage that is not the cloud's copy")
        db.upsertEntry(row.copy(remoteHash = "new-hash", localSize = 36))

        before.restore(db, row.path, local)

        assertEquals("*.tmp\n", Files.readString(local), "the local bytes are back")
        assertEquals(MTIME, Files.getLastModifiedTime(local).toMillis(), "with their time, so the scan reads them unchanged")
        assertEquals("old-hash", db.getEntry(row.path)?.remoteHash, "and the row as it was")
    }

    @Test
    fun `a download that is not the cloud copy of a rule file not here before leaves no file and no row`() {
        Files.delete(local)
        val before = RuleFileBefore.of(null, local)
        Files.writeString(local, "garbage")
        db.upsertEntry(row)

        before.restore(db, row.path, local)

        assertFalse(Files.exists(local))
        assertNull(db.getEntry(row.path))
    }

    private companion object {
        const val MTIME = 1_767_225_600_000L
    }
}
