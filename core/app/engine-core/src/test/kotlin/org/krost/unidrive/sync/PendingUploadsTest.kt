package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** #86: what counts as an upload on its way, and the drop of a row whose file is gone. */
class PendingUploadsTest {
    private lateinit var dir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-pending-uploads")
        db = StateDatabase(dir.resolve("state.db")).also { it.initialize() }
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun put(
        path: String,
        remoteId: String = "local:$path",
    ) = db.upsertEntry(
        SyncEntry(
            path = path,
            remoteId = remoteId,
            remoteHash = null,
            remoteSize = 0,
            remoteModified = null,
            localMtime = 1L,
            localSize = 1L,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.parse("2000-01-01T00:00:00Z"),
        ),
    )

    @Test
    fun `rows are sorted into waiting, kept local and gone`() {
        for (p in listOf("/a.txt", "/Thumbs.db", "/movie.part", "/vanished.txt")) put(p)
        val present = setOf("/a.txt", "/Thumbs.db")

        val split = PendingUploads.split(db.pendingUploadRows(), { it.endsWith(".db") || it.endsWith(".part") }, { it in present })

        assertEquals(listOf("/a.txt"), split.waiting.map { it.path })
        assertEquals(listOf("/Thumbs.db"), split.keptLocal.map { it.path })
        // Gone wins over kept local: a row without a file is dead weight whatever the rules say.
        assertEquals(listOf("/movie.part", "/vanished.txt"), split.gone.map { it.path }.sorted())
    }

    @Test
    fun `without a way to tell, no row is gone`() {
        put("/a.txt")

        val split = PendingUploads.split(db.pendingUploadRows(), { false }, null)

        assertEquals(listOf("/a.txt"), split.waiting.map { it.path })
        assertTrue(split.gone.isEmpty())
    }

    @Test
    fun `the detail paths are sorted and capped`() {
        for (i in 9 downTo 0) put("/f$i.txt")

        val split = PendingUploads.split(db.pendingUploadRows(), { false }, null)

        assertEquals((0 until PendingUploads.DETAIL_PATHS_CAP).map { "/f$it.txt" }, split.detailPaths())
    }

    @Test
    fun `dropping a gone row leaves the rows with a real remote id alone`() {
        put("/vanished.txt")
        put("/uploaded.txt", remoteId = "real-id")

        assertTrue(db.deleteLocalPendingRow("/vanished.txt"))
        assertFalse(db.deleteLocalPendingRow("/uploaded.txt"), "the cloud's row is never ours to drop")

        assertEquals(emptyList(), db.pendingUploadRows())
        assertNotNull(db.getEntry("/uploaded.txt"))
    }
}
