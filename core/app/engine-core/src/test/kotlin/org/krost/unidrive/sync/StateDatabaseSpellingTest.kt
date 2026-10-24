package org.krost.unidrive.sync

import org.krost.unidrive.engine.DuplicateNames
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * archive#491: state.db keys paths in NFC (archive#171), and so a remote path. A row of a normalisation collision keeps the
 * provider's own spelling as its remote path instead: the twin under its decorated name and the item that holds the
 * NFC name. The lookup by remote path still finds the holder by the NFC key, a writer that only brings the NFC key
 * (a download, an upload) keeps the spelling, and every other remote path stays NFC. Names are built from code points,
 * so the source stays ASCII.
 */
class StateDatabaseSpellingTest {
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("unidrive-spelling").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun cps(vararg codePoints: Int) = String(codePoints, 0, codePoints.size)

    private val nfc = "/caf" + cps(0xE9) + ".txt"
    private val nfd = "/cafe" + cps(0x301) + ".txt"

    private fun entry(
        path: String,
        id: String,
        remotePath: String? = null,
    ) = SyncEntry(
        path = path,
        remotePath = remotePath,
        remoteId = id,
        remoteHash = "h",
        remoteSize = 1,
        remoteModified = Instant.parse("2026-01-01T12:00:00Z"),
        localMtime = 1,
        localSize = 1,
        isFolder = false,
        isPinned = false,
        isHydrated = false,
        lastSynced = Instant.now(),
    )

    @Test
    fun `a twin under its decorated name keeps the provider's spelling`() {
        val twin = DuplicateNames.decorate(nfc, "id-nfd")
        db.upsertEntry(entry(twin, "id-nfd", remotePath = nfd), keepMarks = true)

        assertEquals(nfd, db.getEntry(twin)?.remotePath)
    }

    @Test
    fun `the holder of the NFC name keeps its spelling and is found by the NFC key`() {
        db.upsertEntry(entry(nfc, "id-nfd", remotePath = nfd), keepMarks = true)

        assertEquals(nfd, db.getEntry(nfc)?.remotePath)
        assertEquals("id-nfd", db.getEntryByRemotePath(nfc)?.remoteId)
        assertEquals("id-nfd", db.getEntryByRemotePath(nfd)?.remoteId)
    }

    @Test
    fun `a writer that brings the NFC key keeps the spelling, the merge may clear it`() {
        db.upsertEntry(entry(nfc, "id-nfd", remotePath = nfd), keepMarks = true)

        db.upsertEntry(entry(nfc, "id-nfd", remotePath = null))
        assertEquals(nfd, db.getEntry(nfc)?.remotePath, "a download writes the row without the spelling")

        db.upsertEntry(entry(nfc, "id-nfd", remotePath = null), keepMarks = true)
        assertEquals(null, db.getEntry(nfc)?.remotePath, "the merge's word is final")
    }

    @Test
    fun `any other remote path is stored in NFC`() {
        val folder = "/Bilder" + nfc
        db.upsertEntry(entry(folder, "id-alias", remotePath = "/Pictures$nfd"), keepMarks = true)

        assertEquals("/Pictures$nfc", db.getEntry(folder)?.remotePath)
    }
}
