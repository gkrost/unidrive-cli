package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * gkrost/unidrive-cli#739 (and the HydrationRmdirIndexedAfterListingTest half of #781): the rmdir window guard compares a
 * row's `lastSynced` with when a client was last told the folder whole. That instant was kept in whole milliseconds while
 * `lastSynced` has the clock's full resolution, so a row written shortly before the listing, in the same millisecond, read
 * as indexed after it. The clock here is the test's: the listing is noted one microsecond after the row was written.
 */
class RmdirWindowGuardTest {
    private class Env {
        val provider = MemProvider()
        val db = StateDatabase(dbPath = TestScratch.directory("ud-739-db").resolve("state.db"), inMemory = true)
        val engine: SyncEngine
        val mount: MountEngine

        init {
            db.initialize()
            engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = TestScratch.directory("ud-739-sync"),
                    cacheRoot = TestScratch.directory("ud-739-cache"),
                )
            mount = MountEngine.over(engine)
        }

        fun liveFolders(listedAt: Instant) =
            LiveFolderList(
                db = db,
                gather = engine.mountWiring.gather,
                guard = engine.mountWiring.guard,
                remotePathOf = { path, _ -> path },
                listRemote = { emptyList() },
                log = LoggerFactory.getLogger(RmdirWindowGuardTest::class.java),
                now = { listedAt },
            )
    }

    @Test
    fun `a row written in the millisecond of the whole listing but before it is not indexed after it`() = runTest {
        val env = Env()
        env.provider.seedFolder("/dir")
        env.provider.seed("/dir/kept.txt", "kept bytes".toByteArray())
        env.mount.enumerateRemoteIntoState(reset = false)
        val kept = assertNotNull(env.db.getEntry("/dir/kept.txt"))
        val folders = env.liveFolders(listedAt = kept.lastSynced.plusNanos(1_000))
        folders.coverage("/dir", env.mount.enumerationStatus())

        // Nothing below was indexed after the listing: no refusal.
        refuseIndexedAfterWholeListing(env.db.getEntriesByPrefix("/dir/"), folders.lastWholeListing("/dir"))
    }

    // Test drive 3: a file this mount uploaded into the folder after the client's last whole listing, whose cache copy was then
    // evicted, read as a cloud item a delta had indexed: every recursive rmdir of the folder was refused remote_unobserved
    // until a daemon restart. A file this computer wrote (or read) is one the client knows.
    @Test
    fun `a file the mount uploaded after the folder's last listing does not refuse its recursive rmdir`() = runTest {
        val env = Env()
        env.provider.seedFolder("/R")
        env.mount.enumerateRemoteIntoState(reset = false)
        val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
        val listed = assertIs<ListResult.Ok>(hydration.list("/R"))
        assertEquals(true, listed.complete, "precondition: the folder was listed whole")

        val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/R/n1.txt"))
        java.nio.file.Files.writeString(created.cachePath, "uploaded here")
        hydration.openForWrite("conn", "h1", "/R/n1.txt", created.cachePath)
        testScheduler.advanceUntilIdle()
        assertNotNull(env.db.getEntry("/R/n1.txt")?.remoteId, "precondition: the upload landed")
        hydration.closeHandle("conn", "h1")
        assertIs<DehydrateResult.Ok>(hydration.dehydrate("/R/n1.txt"))

        assertEquals(RmdirResult.Ok, hydration.rmdir("/R", recursive = true))
    }

    @Test
    fun `of two rows indexed after the whole listing the earliest indexed is reported, whatever the row order`() {
        val listedAt = Instant.parse("2026-11-18T10:00:00.000500Z")
        fun row(path: String, lastSynced: Instant) =
            org.krost.unidrive.sync.model.SyncEntry(
                path = path,
                remoteId = "id-$path",
                remoteHash = null,
                remoteSize = 1L,
                remoteModified = null,
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = lastSynced,
            )
        val rows =
            listOf(
                row("/dir/a-later.txt", listedAt.plusMillis(20)),
                row("/dir/b-earlier.txt", listedAt.plusMillis(10)),
            )

        for (order in listOf(rows, rows.reversed())) {
            val refused = assertFailsWith<UnobservedChildException> { refuseIndexedAfterWholeListing(order, listedAt) }
            assertEquals("/dir/b-earlier.txt", refused.childPath)
        }
    }
}
