package org.krost.unidrive.sync

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.RenameIntent
import org.krost.unidrive.sync.model.ReplaceIntent
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #863 item 1: the DELETED rows older than the profile's retention go with the compaction at the daemon's start, and the
 * rows something still needs stay: a recent tombstone, the mount's delete receipt within the retention, and every row a
 * rename intent, a replace intent or an upload handle names. The purge reads and writes state.db alone.
 */
class TombstoneRetentionTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path
    private val logger = LoggerFactory.getLogger(StateDatabase::class.java) as Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    private val now: Instant = Instant.now()
    private val retention: Duration = Duration.ofDays(30)
    private val old: Instant = now.minus(Duration.ofDays(31))
    private val recent: Instant = now.minus(Duration.ofDays(29))

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("unidrive-tombstone-retention")
        dbFile = dir.resolve("state.db")
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterTest
    fun tearDown() {
        logger.detachAppender(appender)
        dir.toFile().deleteRecursively()
    }

    private fun opened(): StateDatabase = StateDatabase(dbFile).also { it.initialize() }

    private fun row(
        path: String,
        status: EntryStatus = EntryStatus.EXISTS,
        lastSynced: Instant = now,
        remoteId: String = "id-$path",
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = "h",
        remoteSize = 1,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = 1,
        localSize = 1,
        isFolder = false,
        isPinned = false,
        isHydrated = false,
        lastSynced = lastSynced,
        status = status,
    )

    private fun tombstone(
        db: StateDatabase,
        path: String,
        at: Instant,
    ) = db.upsertEntry(row(path, EntryStatus.DELETED, at))

    private fun pathsAnyStatus(db: StateDatabase): Set<String> = db.recovery.allEntriesAnyStatus().mapTo(sortedSetOf()) { "${it.status}:${it.path}" }

    @Test
    fun `tombstones older than the retention are purged on compaction, newer ones and the alive and trashed rows stay`() {
        val db = opened()
        try {
            db.upsertEntry(row("/alive.txt", lastSynced = old))
            db.upsertEntry(row("/trashed.txt", EntryStatus.TRASHED, old))
            tombstone(db, "/old-1.txt", old)
            tombstone(db, "/old-2.txt", old)
            tombstone(db, "/recent.txt", recent)

            db.compactOnce(tombstoneRetention = retention)

            assertEquals(
                sortedSetOf("EXISTS:/alive.txt", "TRASHED:/trashed.txt", "DELETED:/recent.txt"),
                pathsAnyStatus(db),
            )
            assertEquals(1, db.getEntryCount(), "the alive count is untouched")
            assertTrue(
                appender.list.any { it.formattedMessage.contains("Purged 2 DELETED row(s)") },
                "the purge says how many rows it removed: ${appender.list.map { it.formattedMessage }}",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `a retention of zero keeps every tombstone`() {
        val db = opened()
        try {
            tombstone(db, "/old.txt", Instant.parse("2020-01-01T00:00:00Z"))

            db.compactOnce(tombstoneRetention = Duration.ZERO)
            db.compactOnce()

            assertEquals(sortedSetOf("DELETED:/old.txt"), pathsAnyStatus(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun `tombstones a rename intent, a replace intent or an upload handle names stay at any age`() {
        val db = opened()
        try {
            for (name in listOf("rename-old", "rename-new", "rename-id", "replace-src", "replace-dest", "replace-park", "replace-src-id", "replace-dest-id", "handle", "free")) {
                tombstone(db, "/$name.txt", old)
            }
            db.recordRenameIntent(RenameIntent("/rename-old.txt", "/elsewhere-a.txt", "other-a", ghost = false))
            db.recordRenameIntent(RenameIntent("/elsewhere-b.txt", "/rename-new.txt", "other-b", ghost = false))
            db.recordRenameIntent(RenameIntent("/elsewhere-c.txt", "/elsewhere-d.txt", "id-/rename-id.txt", ghost = false))
            db.recordReplaceIntent(ReplaceIntent("/replace-src.txt", "/replace-dest.txt", "/replace-park.txt", null, null))
            db.recordReplaceIntent(ReplaceIntent("/x-src.txt", "/x-dest.txt", "/x-park.txt", "id-/replace-src-id.txt", "id-/replace-dest-id.txt"))
            db.recordUploadHandle("/handle.txt", "handle-1")

            db.compactOnce(tombstoneRetention = retention)

            val left = pathsAnyStatus(db)
            assertTrue("DELETED:/free.txt" !in left, "the tombstone nothing names goes: $left")
            assertEquals(9, left.size, "every named tombstone stays: $left")
        } finally {
            db.close()
        }
    }

    @Test
    fun `an old tombstone whose mount delete receipt is within the retention stays`() {
        val db = opened()
        try {
            db.upsertEntry(row("/mount-deleted.txt"))
            db.markDeleted("/mount-deleted.txt", mountDelete = true)
            db.close()
            // The row's last_synced ages, its receipt stays recent: the receipt alone keeps it.
            DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
                c.prepareStatement("UPDATE sync_entries SET last_synced = ? WHERE path = ?").use { st ->
                    st.setString(1, old.toString())
                    st.setString(2, "/mount-deleted.txt")
                    assertEquals(1, st.executeUpdate())
                }
            }
            val reopened = opened()
            try {
                reopened.compactOnce(tombstoneRetention = retention)
                assertEquals(sortedSetOf("DELETED:/mount-deleted.txt"), pathsAnyStatus(reopened))
            } finally {
                reopened.close()
            }
        } finally {
            runCatching { db.close() }
        }
    }

    @Test
    fun `the file shrinks after a purge, even when it was compacted once before`() {
        val db = opened()
        try {
            db.upsertEntry(row("/keep.txt"))
            assertEquals(false, db.compactOnce(), "precondition: nothing to rewrite yet")
            db.setSyncState(StateDatabase.COMPACTED_KEY, now.toString()) // the one rewrite of the file happened before
            db.batch { for (i in 0 until 6_000) tombstone(db, "/emptied/account/folder/f$i.txt", old) }
            db.pragma("wal_checkpoint(TRUNCATE)")
            val before = Files.size(dbFile)

            assertTrue(db.compactOnce(tombstoneRetention = retention), "the purge freed most of the file: it is rewritten")

            assertTrue(Files.size(dbFile) < before / 2, "${Files.size(dbFile)} < $before / 2")
            assertEquals(sortedSetOf("EXISTS:/keep.txt"), pathsAnyStatus(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a purge touches no file outside state db`() {
        val root = Files.createDirectories(dir.resolve("sync-root"))
        val local = Files.writeString(root.resolve("old-1.txt"), "bytes the purge must not touch")
        val mtime = Files.getLastModifiedTime(local)
        val db = opened()
        try {
            tombstone(db, "/old-1.txt", old)
            val before = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSortedSet() }

            db.compactOnce(tombstoneRetention = retention)

            assertEquals(emptySet(), pathsAnyStatus(db))
            assertEquals("bytes the purge must not touch", Files.readString(local))
            assertEquals(mtime, Files.getLastModifiedTime(local))
            val after = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList().toSortedSet() }
            assertEquals(before, after, "no file appears or goes beside state.db")
        } finally {
            db.close()
        }
    }
}
