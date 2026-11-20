package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #744 finding 8: [StateDatabase.getEntryCaseInsensitive] folds beyond ASCII and answers deterministically. */
class StateDatabaseCaseInsensitiveLookupTest {
    private lateinit var dir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-ci-lookup")
        db = StateDatabase(dir.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun row(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = null,
            remoteSize = 1,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = null,
            localSize = null,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
        )

    @Test
    fun `case-insensitive lookup matches a non-ASCII letter that differs in case`() {
        db.upsertEntry(row("/docs/übersicht.docx"))

        assertEquals("/docs/übersicht.docx", db.getEntryCaseInsensitive("/docs/Übersicht.docx")?.path)
        assertNull(db.getEntryCaseInsensitive("/docs/Uebersicht.docx"), "a different spelling is not a case variant")
    }

    // The non-ASCII comparison reads the folder's siblings only, so the folder itself must be found case-insensitively too.
    @Test
    fun `case-insensitive lookup matches a non-ASCII name under a folder whose row differs in case`() {
        db.upsertEntry(row("/Ärger").copy(isFolder = true))
        db.upsertEntry(row("/Ärger/Übung.txt"))

        assertEquals("/Ärger/Übung.txt", db.getEntryCaseInsensitive("/ärger/übung.txt")?.path)
    }

    @Test
    fun `case-insensitive lookup does not match a non-ASCII name in another folder`() {
        db.upsertEntry(row("/a/übersicht.docx"))

        assertNull(db.getEntryCaseInsensitive("/b/Übersicht.docx"))
    }

    @Test
    fun `case-insensitive lookup picks the same row whatever the insertion order`() {
        db.upsertEntry(row("/a/Report.txt"))
        db.upsertEntry(row("/a/REPORT.txt"))
        db.upsertEntry(row("/a/report.txt"))

        // Binary order: uppercase sorts before lowercase.
        assertEquals("/a/REPORT.txt", db.getEntryCaseInsensitive("/a/rEpOrT.txt")?.path)
    }
}
