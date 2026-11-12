package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChunkedRemovalReviewTest {
    private fun entry(path: String) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = "hash",
        remoteSize = 1,
        remoteModified = Instant.EPOCH,
        localMtime = 0,
        localSize = 1,
        isFolder = false,
        isPinned = false,
        isHydrated = false,
        lastSynced = Instant.EPOCH,
    )

    @Test
    fun `scope narrowing retains only case-sensitive descendants`() {
        val db = StateDatabase(Files.createTempDirectory("scope-review").resolve("state.db"))
        db.initialize()
        try {
            db.upsertEntry(entry("/in/kept.txt"))
            db.upsertEntry(entry("/IN/removed.txt"))

            assertEquals(1, db.deleteOutOfScope(listOf("/in"), emptySet()))
            assertNotNull(db.getEntry("/in/kept.txt"))
            assertNull(db.getEntry("/IN/removed.txt"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `reap commits once per chunk instead of once per row`() {
        val connector = RemovalRecordingConnector()
        val db = StateDatabase(Files.createTempDirectory("reap-review").resolve("state.db"), connector = connector)
        db.initialize()
        try {
            val paths = (0 until 7).map { "/f$it.txt" }
            db.batch { paths.forEach { db.upsertEntry(entry(it)) } }
            connector.commits = 0
            connector.autoCommitUpdates.clear()

            assertEquals(7, db.markDeletedChunked(paths, chunkSize = 3).size)

            assertFalse(connector.autoCommitUpdates.isEmpty(), "the connector observed the reap's UPDATEs")
            assertFalse(
                connector.autoCommitUpdates.any { it },
                "updates must share a chunk transaction; observed autoCommit=${connector.autoCommitUpdates}",
            )
            assertEquals(3, connector.commits, "seven rows with chunkSize three must make three durable commits")
        } finally {
            db.close()
        }
    }

    @Test
    fun `reap gives the monitor back after each chunk`() {
        val db = StateDatabase(Files.createTempDirectory("reap-monitor-review").resolve("state.db"))
        db.initialize()
        try {
            val paths = (0 until 7).map { "/f$it.txt" }
            db.batch { paths.forEach { db.upsertEntry(entry(it)) } }
            db.holds.reset()

            db.markDeletedChunked(paths, chunkSize = 3)

            assertEquals(
                3L,
                db.holds.snapshot().single { it.method == "markDeletedChunked" }.calls,
                "one monitor acquisition per chunk, as for renamePrefix",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `scope narrowing gives the monitor back after each chunk`() {
        val db = StateDatabase(Files.createTempDirectory("scope-monitor-review").resolve("state.db"))
        db.initialize()
        try {
            db.batch { (0 until 7).forEach { db.upsertEntry(entry("/out/f$it.txt")) } }
            db.holds.reset()

            assertEquals(7, db.deleteOutOfScope(listOf("/in"), emptySet(), chunkSize = 3))

            assertEquals(
                3L,
                db.holds.snapshot().single { it.method == "deleteOutOfScope" }.calls,
                "one monitor acquisition per chunk, as for renamePrefix",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `chunked removals join the owners manual batch`() {
        val db = StateDatabase(Files.createTempDirectory("removal-batch").resolve("state.db"))
        db.initialize()
        try {
            val paths = (0 until 7).map { "/out/f$it.txt" }
            db.batch { paths.forEach { db.upsertEntry(entry(it)) } }
            db.beginBatch()
            db.markDeletedChunked(paths.take(3), chunkSize = 2)
            db.deleteOutOfScope(listOf("/in"), emptySet(), chunkSize = 2)
            db.rollbackBatch()
            assertEquals(7, db.getEntryCount())
            paths.forEach { assertNotNull(db.getEntry(it)) }
        } finally {
            db.close()
        }
    }

    @Test
    fun `guarded removal keeps changed identity edit and pending rows`() {
        val db = StateDatabase(Files.createTempDirectory("removal-guard").resolve("state.db"))
        db.initialize()
        try {
            val paths = listOf("/identity", "/edited", "/pending", "/clean")
            paths.forEach { db.upsertEntry(entry(it)) }
            val expected = paths.associateWith { assertNotNull(db.getEntry(it)) }
            db.markDeleted("/identity")
            db.upsertEntry(entry("/identity").copy(remoteId = "replacement"))
            db.markEdited("/edited", "base")
            db.setPendingLocal("/pending", "delete")

            assertEquals(listOf("/clean"), db.markDeletedChunked(paths, chunkSize = 2, expectedRows = expected))
            assertEquals("replacement", assertNotNull(db.getEntry("/identity")).remoteId)
            assertEquals("base", assertNotNull(db.getEntry("/edited")).editBase)
            assertNotNull(db.getEntry("/pending"))
            assertNull(db.getEntry("/clean"))
        } finally {
            db.close()
        }
    }

    private class RemovalRecordingConnector : StateDatabaseConnector by JdbcStateDatabaseConnector {
        val autoCommitUpdates = mutableListOf<Boolean>()
        var commits = 0

        override fun open(path: Path?): Connection {
            val c = JdbcStateDatabaseConnector.open(path)
            return object : Connection by c {
                override fun commit() {
                    c.commit()
                    commits++
                }

                override fun prepareStatement(sql: String): PreparedStatement {
                    val s = c.prepareStatement(sql)
                    if (!sql.startsWith("UPDATE sync_entries SET status='DELETED'")) return s
                    return object : PreparedStatement by s {
                        override fun executeUpdate(): Int {
                            autoCommitUpdates += c.autoCommit
                            return s.executeUpdate()
                        }
                    }
                }
            }
        }
    }
}
