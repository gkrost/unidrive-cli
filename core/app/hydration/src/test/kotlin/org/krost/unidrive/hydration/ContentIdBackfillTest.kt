package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#202: the rows a state.db holds from before the content-id column (gkrost/unidrive-cli#164) learn the content id of
 * the version they record from one listing of their folder, once per profile, and nothing else of them changes.
 */
class ContentIdBackfillTest {
    private val base: Path = Files.createTempDirectory("ud-content-id-backfill")
    private val dbFile: Path = base.resolve("state.db")
    private var opened: StateDatabase? = null

    @AfterTest
    fun close() {
        opened?.close()
        base.toFile().deleteRecursively()
    }

    // A row as an engine before the column wrote it: Internxt's token `<uuid>|<modificationTime>|<size>` in remote_hash, no
    // remote_content_id column at all.
    private data class OldRow(
        val id: String,
        val path: String,
        val size: Long = 5,
        val folder: Boolean = false,
        val hydrated: Boolean = false,
    )

    private val oldRows =
        listOf(
            OldRow("u-top", "/top.txt", size = 3),
            OldRow("d-a", "/A", folder = true),
            OldRow("u-x", "/A/x.txt", size = 5, hydrated = true),
            OldRow("u-y", "/A/y.txt", size = 7),
            OldRow("d-b", "/A/B", folder = true),
            OldRow("u-z", "/A/B/z.txt", size = 9),
            OldRow("d-c", "/C", folder = true),
            OldRow("u-w", "/C/w.txt", size = 11),
            OldRow("d-trash", "/Papierkorb", folder = true),
            OldRow("u-old", "/Papierkorb/old.txt", size = 2),
        )

    /**
     * A state.db of the shape an engine before the column left (this build's schema without remote_content_id, the shape
     * StateDatabaseTest's migration fixture writes), with [rows] and a completed enumeration; opened with this build, which adds
     * the column back, empty.
     */
    private fun oldDatabase(rows: List<OldRow> = oldRows): StateDatabase {
        StateDatabase(dbFile).also { it.initialize() }.close()
        val statements =
            listOf(
                "DROP VIEW alive_entries",
                "ALTER TABLE sync_entries DROP COLUMN remote_content_id",
                "CREATE VIEW alive_entries AS SELECT * FROM sync_entries WHERE status='EXISTS'",
                "INSERT OR REPLACE INTO sync_state VALUES ('delta_cursor', '2026-10-28T09:00:00.000Z')",
            ) + rows.map(::insertOf)
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { conn -> conn.createStatement().use { stmt -> statements.forEach(stmt::executeUpdate) } }
        return StateDatabase(dbFile).also { it.initialize() }.also { opened = it }
    }

    private fun insertOf(r: OldRow): String {
        val hash = if (r.folder) "NULL" else "'${token(r.id, r.size)}'"
        val local = if (r.hydrated) "${MODIFIED.toEpochMilli()}, ${r.size}" else "NULL, NULL"
        return "INSERT INTO sync_entries (remote_id, path, remote_hash, remote_size, remote_modified, local_mtime, local_size, " +
            "is_folder, is_hydrated, cache_backed, last_synced) VALUES ('${r.id}', '${r.path}', $hash, ${if (r.folder) 0 else r.size}, " +
            "'$MODIFIED', $local, ${if (r.folder) 1 else 0}, ${if (r.hydrated) 1 else 0}, ${if (r.hydrated) 1 else "NULL"}, " +
            "'2026-10-28T09:00:00Z')"
    }

    /** What the cloud lists, by folder; it counts the listings and can fail one. */
    private class Cloud {
        val items = mutableListOf<CloudItem>()
        val listed = mutableListOf<String>()
        val failures = mutableMapOf<String, () -> Exception>()

        fun file(
            id: String,
            path: String,
            size: Long,
            modified: Instant = MODIFIED,
            storedObject: String = "obj-$id",
        ) {
            items +=
                CloudItem(
                    id = id,
                    name = path.substringAfterLast('/'),
                    path = path,
                    size = size,
                    isFolder = false,
                    modified = modified,
                    created = null,
                    hash = "$id|${if (modified == MODIFIED) MODIFIED_TEXT else modified.toString()}|$size",
                    mimeType = null,
                    contentId = "$storedObject|$size",
                )
        }

        fun folder(
            id: String,
            path: String,
        ) {
            items += CloudItem(id, path.substringAfterLast('/'), path, 0, true, MODIFIED, null, null, null)
        }

        suspend fun list(remotePath: String): List<CloudItem> {
            listed += remotePath
            failures[remotePath]?.let { throw it() }
            return items.filter { it.path.substringBeforeLast('/').ifEmpty { "/" } == remotePath }
        }
    }

    // The cloud as it holds the old rows' items unchanged.
    private fun unchangedCloud(): Cloud =
        Cloud().apply {
            for (r in oldRows) if (r.folder) folder(r.id, r.path) else file(r.id, r.path, r.size)
        }

    private inner class Pass(
        private val db: StateDatabase,
        private val cloud: Cloud,
        private val reportsContentIds: Boolean = true,
        private val reachable: () -> Boolean = { true },
        private val unsentEdits: Set<String> = emptySet(),
    ) {
        val announced = mutableListOf<String>()

        private val backfill =
            ContentIdBackfill(
                db = db,
                reportsContentIds = reportsContentIds,
                mount =
                    object : ContentIdBackfill.Mount {
                        override fun isReadOnlyPath(path: String) = path == "/Papierkorb" || path.startsWith("/Papierkorb/")

                        override fun holdsUnsyncedEdit(row: SyncEntry) = row.path in unsentEdits || row.isPendingUpload || row.editBase != null

                        override fun remotePathOf(
                            path: String,
                            row: SyncEntry?,
                        ) = path

                        override suspend fun listRemote(remotePath: String) = cloud.list(remotePath)

                        override fun providerReachable() = reachable()

                        override suspend fun beforeFolder() = Unit

                        override fun folderFilled(folder: String) {
                            announced += folder
                        }
                    },
                log = LoggerFactory.getLogger(ContentIdBackfillTest::class.java),
                pageFolders = 2,
            )

        fun run(): ContentIdBackfill.Outcome = runBlocking { backfill.pass() }
    }

    private fun StateDatabase.contentIdOf(path: String): String? = getEntry(path)?.remoteContentId

    // Every column but the content id, as the row had it before the pass.
    private fun StateDatabase.rowsWithoutContentIds(): Map<String, SyncEntry> = getAllEntries().associate { it.path to it.copy(remoteContentId = null) }

    @Test
    fun `rows from before the column learn the content id their folder lists, once, and nothing else of them changes`() {
        val db = oldDatabase()
        val before = db.rowsWithoutContentIds()
        val cloud = unchangedCloud()
        val pass = Pass(db, cloud)

        val outcome = pass.run()

        assertEquals(ContentIdBackfill.Outcome.Finished(folders = 4, filled = 5), outcome)
        assertEquals("obj-u-top|3", db.contentIdOf("/top.txt"))
        assertEquals("obj-u-x|5", db.contentIdOf("/A/x.txt"))
        assertEquals("obj-u-y|7", db.contentIdOf("/A/y.txt"))
        assertEquals("obj-u-z|9", db.contentIdOf("/A/B/z.txt"))
        assertEquals("obj-u-w|11", db.contentIdOf("/C/w.txt"))
        assertNull(db.contentIdOf("/A"), "a folder names no bytes")
        assertNull(db.contentIdOf("/Papierkorb/old.txt"), "the trash namespace has no provider folder to list")
        assertEquals(listOf("/", "/A", "/A/B", "/C"), cloud.listed, "each folder of such rows is listed once, the trash's never")
        assertEquals(before, db.rowsWithoutContentIds(), "hydration, sizes, tokens and baselines are as they were")
        assertTrue(db.getEntry("/A/x.txt")!!.isHydrated)
        assertEquals(listOf("", "/A", "/A/B", "/C"), pass.announced, "each folder that learned ids is announced for a client to list again")

        // The profile is done: a later pass reads one key and lists nothing.
        assertEquals(ContentIdBackfill.Outcome.AlreadyDone, Pass(db, cloud).run())
        assertEquals(4, cloud.listed.size)
    }

    @Test
    fun `a row whose listing shows another version is not filled and keeps its hydration`() {
        val db = oldDatabase()
        val cloud = unchangedCloud()
        // /A/x.txt was renamed or touched elsewhere (a new modification time, so another token), /A/y.txt was replaced by other bytes of
        // another size, /C/w.txt is another item at the row's path (deleted and created again), /A/B/z.txt is gone from its folder.
        cloud.items.removeAll { it.path in setOf("/A/x.txt", "/A/y.txt", "/C/w.txt", "/A/B/z.txt") }
        cloud.file("u-x", "/A/x.txt", 5, modified = Instant.parse("2026-10-29T10:00:00Z"))
        cloud.file("u-y", "/A/y.txt", 8, storedObject = "obj-y2")
        cloud.file("u-w2", "/C/w.txt", 11)
        val before = db.rowsWithoutContentIds()
        val pass = Pass(db, cloud)

        val outcome = pass.run()

        assertEquals(ContentIdBackfill.Outcome.Finished(folders = 4, filled = 1), outcome)
        assertEquals("obj-u-top|3", db.contentIdOf("/top.txt"), "the unchanged item is named")
        assertNull(db.contentIdOf("/A/x.txt"), "another token: the refresh delivers the change and judges the row")
        assertNull(db.contentIdOf("/A/y.txt"), "another size and object")
        assertNull(db.contentIdOf("/C/w.txt"), "another item at the path")
        assertNull(db.contentIdOf("/A/B/z.txt"), "no item")
        assertEquals(before, db.rowsWithoutContentIds(), "nothing else changes: the hydrated row stays hydrated")
        assertEquals(listOf(""), pass.announced, "only the folder that learned an id is announced")
        assertEquals(ContentIdBackfill.Outcome.AlreadyDone, Pass(db, cloud).run(), "a folder is listed once, whatever it showed")
        assertEquals(4, cloud.listed.size)
    }

    @Test
    fun `a row whose cache copy may hold an unsent edit, or that awaits its first upload, is not filled`() {
        val db = oldDatabase()
        assertTrue(db.markEdited("/A/y.txt", token("u-y", 7)))
        db.upsertEntry(
            SyncEntry(
                path = "/A/new.txt", remoteId = null, remoteHash = null, remoteSize = 4, remoteModified = null, localMtime = 1L, localSize = 4,
                isFolder = false, isPinned = false, isHydrated = true, lastSynced = Instant.now(),
            ),
        )
        val pass = Pass(db, unchangedCloud(), unsentEdits = setOf("/A/x.txt"))

        pass.run()

        assertNull(db.contentIdOf("/A/x.txt"), "an upload queued or a copy newer than the watermark")
        assertNull(db.contentIdOf("/A/y.txt"), "an edit noted against the version")
        assertNull(db.contentIdOf("/A/new.txt"))
        assertEquals("obj-u-z|9", db.contentIdOf("/A/B/z.txt"))
    }

    @Test
    fun `a provider that names no content is not listed and nothing is written`() {
        val db = oldDatabase()
        val cloud = unchangedCloud()

        assertEquals(ContentIdBackfill.Outcome.NotNeeded, Pass(db, cloud, reportsContentIds = false).run())

        assertEquals(emptyList(), cloud.listed)
        assertNull(db.getSyncState(ContentIdBackfill.STATE_KEY))
        assertNull(db.getSyncState(ContentIdBackfill.AFTER_KEY))
        assertTrue(db.getAllEntries().all { it.remoteContentId == null })
    }

    @Test
    fun `no pass runs before an enumeration has completed`() {
        val db = oldDatabase()
        db.setSyncState("delta_cursor", "")
        val cloud = unchangedCloud()

        assertEquals(ContentIdBackfill.Outcome.AwaitingFirstScan, Pass(db, cloud).run())

        assertEquals(emptyList(), cloud.listed)
        assertNull(db.getSyncState(ContentIdBackfill.STATE_KEY))
    }

    @Test
    fun `a pass interrupted by a failed listing or a stop resumes after the last folder it finished`() {
        val db = oldDatabase()
        val cloud = unchangedCloud()
        // The provider is out of reach when /A/B is listed: the pass stops there, and nothing counts against the folder.
        cloud.failures["/A/B"] = { ProviderException("connect timed out") }

        val first = Pass(db, cloud, reachable = { false }).run()

        assertEquals(ContentIdBackfill.Outcome.Stopped("/A/B/"), first)
        assertEquals(listOf("/", "/A", "/A/B"), cloud.listed)
        assertEquals("obj-u-x|5", db.contentIdOf("/A/x.txt"), "a finished folder keeps what it learned")
        assertNull(db.contentIdOf("/A/B/z.txt"))
        assertEquals("/A/", db.getSyncState(ContentIdBackfill.AFTER_KEY))
        assertTrue(db.getSyncState(ContentIdBackfill.STRIKES_KEY).isNullOrEmpty(), "an outage is no strike")

        // The daemon stops while /A/B is being listed.
        cloud.failures["/A/B"] = { CancellationException("the daemon stops") }
        assertFailsWith<CancellationException> { Pass(db, cloud).run() }
        assertEquals("/A/", db.getSyncState(ContentIdBackfill.AFTER_KEY))

        // The next pass starts at /A/B: no finished folder is listed again.
        cloud.failures.clear()
        cloud.listed.clear()
        assertIs<ContentIdBackfill.Outcome.Finished>(Pass(db, cloud).run())
        assertEquals(listOf("/A/B", "/C"), cloud.listed)
        assertEquals("obj-u-z|9", db.contentIdOf("/A/B/z.txt"))
        assertEquals("obj-u-w|11", db.contentIdOf("/C/w.txt"))
    }

    @Test
    fun `a folder the pass comes back to is listed again and only its unnamed rows are filled`() {
        val db = oldDatabase()
        val cloud = unchangedCloud()
        // The pass stopped after the root; /A/x.txt learned its id meanwhile from a delivery of its item.
        assertTrue(db.fillContentId(db.getEntry("/A/x.txt")!!, "obj-u-x|5"))
        db.setSyncState(ContentIdBackfill.AFTER_KEY, "/")

        assertIs<ContentIdBackfill.Outcome.Finished>(Pass(db, cloud).run())

        assertEquals(listOf("/A", "/A/B", "/C"), cloud.listed)
        assertEquals("obj-u-x|5", db.contentIdOf("/A/x.txt"))
        assertEquals("obj-u-y|7", db.contentIdOf("/A/y.txt"))
    }

    @Test
    fun `a folder whose listing keeps failing while the provider answers is passed over after three strikes`() {
        val db = oldDatabase()
        val cloud = unchangedCloud()
        cloud.failures["/A/B"] = { ProviderException("HTTP 500 from the folder listing") }

        repeat(ContentIdBackfill.MAX_STRIKES - 1) { assertEquals(ContentIdBackfill.Outcome.Stopped("/A/B/"), Pass(db, cloud).run()) }
        val last = Pass(db, cloud).run()

        assertEquals(ContentIdBackfill.Outcome.Finished(folders = 1, filled = 1), last, "the third failure passes /A/B over and /C is done")
        assertEquals(ContentIdBackfill.MAX_STRIKES, cloud.listed.count { it == "/A/B" })
        assertNull(db.contentIdOf("/A/B/z.txt"))
        assertEquals("obj-u-w|11", db.contentIdOf("/C/w.txt"))
        assertEquals(ContentIdBackfill.DONE, db.getSyncState(ContentIdBackfill.STATE_KEY))
    }

    @Test
    fun `a folder gone from the cloud is passed over at once`() {
        val db = oldDatabase()
        val cloud = unchangedCloud()
        cloud.failures["/C"] = { ProviderException("Folder not found: C in /C") }

        assertEquals(ContentIdBackfill.Outcome.Finished(folders = 3, filled = 4), Pass(db, cloud).run())
        assertNull(db.contentIdOf("/C/w.txt"))
        assertEquals(1, cloud.listed.count { it == "/C" })
    }

    @Test
    fun `the version rule is the merge's - token and size, or the time where a row has no token`() {
        val row =
            SyncEntry(
                path = "/a.txt", remoteId = "u-1", remoteHash = "u-1|t1|5", remoteSize = 5, remoteModified = MODIFIED, localMtime = null,
                localSize = null, isFolder = false, isPinned = false, isHydrated = false, lastSynced = Instant.now(),
            )
        val item = CloudItem("u-1", "a.txt", "/a.txt", 5, false, MODIFIED, null, "u-1|t1|5", null, contentId = "obj|5")
        assertTrue(ContentIdBackfill.isRowsVersion(row, item))
        assertTrue(ContentIdBackfill.isRowsVersion(row, item.copy(path = "/b.txt", modified = Instant.EPOCH)), "the token decides, not the time")
        assertTrue(!ContentIdBackfill.isRowsVersion(row, item.copy(hash = "u-1|t2|5")))
        assertTrue(!ContentIdBackfill.isRowsVersion(row, item.copy(size = 6, hash = "u-1|t1|6")))
        assertTrue(!ContentIdBackfill.isRowsVersion(row, item.copy(id = "u-2")))
        assertTrue(!ContentIdBackfill.isRowsVersion(row, item.copy(version = "v2")))
        val tokenless = row.copy(remoteHash = null)
        assertTrue(ContentIdBackfill.isRowsVersion(tokenless, item.copy(hash = null)))
        assertTrue(!ContentIdBackfill.isRowsVersion(tokenless, item.copy(hash = null, modified = Instant.EPOCH)))
        assertTrue(!ContentIdBackfill.isRowsVersion(tokenless.copy(remoteModified = null), item.copy(hash = null, modified = null)))
    }

    private companion object {
        const val MODIFIED_TEXT = "2026-10-28T08:00:00.000Z"
        val MODIFIED: Instant = Instant.parse(MODIFIED_TEXT)

        fun token(
            id: String,
            size: Long,
        ) = "$id|$MODIFIED_TEXT|$size"
    }
}
