package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#745 finding 8, the tail: [RemoteGather.moveRenamedFolders] landed one rename per step and looked
 * up the row of every pending rename in each step, so p independent sibling renames took p steps and about p²/2 row
 * lookups. Renames that touch no other pending rename's trees land in the same step. The cost is pinned in runs of the
 * by-id row lookup.
 */
class RemoteGatherSiblingRenamesTest {
    private lateinit var tmp: Path
    private val spy = StatementCountingConnector()
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        tmp = TestScratch.directory("unidrive-sibling-renames")
        db = StateDatabase(tmp.resolve("state.db"), inMemory = true, connector = spy)
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun gather() =
        RemoteGather(
            provider = FakeViewProvider(kinds = emptySet()),
            db = db,
            guard =
                RemoteOperationGuard(
                    standingScope = emptyList(),
                    syncPaths = emptyList(),
                    excludePatterns = emptyList(),
                    maxConcurrentTransfers = 2,
                    matchesGlob = { _, _ -> false },
                ),
            options = RemoteGather.Options(providerId = "test"),
            listener = object : RemoteGather.Listener {},
            log = LoggerFactory.getLogger(RemoteGatherSiblingRenamesTest::class.java),
        )

    private fun seed(
        path: String,
        id: String,
        folder: Boolean,
    ) = db.upsertEntry(
        SyncEntry(
            path = path,
            remoteId = id,
            remoteHash = if (folder) null else "h-$id",
            remoteSize = if (folder) 0 else 5,
            remoteModified = null,
            localMtime = null,
            localSize = null,
            isFolder = folder,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.parse("2026-03-01T00:00:00Z"),
        ),
    )

    private fun folderItem(
        id: String,
        path: String,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = 0,
        isFolder = true,
        modified = null,
        created = null,
        hash = null,
        mimeType = null,
    )

    private fun byIdLookups(): Int = spy.runsOf("SELECT * FROM alive_entries WHERE remote_id = ?")

    @Test
    fun `independent sibling renames land in one step, with lookups linear in their number`() {
        val p = 40
        seed("/t", "top", folder = true)
        val changes = linkedMapOf<String, CloudItem>()
        for (i in 0 until p) {
            seed("/t/a$i", "folder-$i", folder = true)
            seed("/t/a$i/f.txt", "file-$i", folder = false)
            changes["/t/b$i"] = folderItem("folder-$i", "/t/b$i")
        }
        val gather = gather()
        spy.reset()

        val moved = gather.moveRenamedFolders(changes, emptyMap())

        // collectRenames reads each row once, the step's sourcesOf once, and each landing a fixed few: about 5 per
        // rename. One rename per step was a sourcesOf per step: 40 + 39 + ... + 1 = 820 more.
        val lookups = byIdLookups()
        assertTrue(lookups <= 6 * p, "$lookups by-id lookups for $p sibling renames")
        assertEquals((0 until p).map { RemoteGather.RemoteMerge.Move("/t/a$it", "/t/b$it") }.toSet(), moved.toSet())
        for (i in 0 until p) {
            assertNull(db.getEntry("/t/a$i"))
            assertEquals("folder-$i", db.getEntry("/t/b$i")?.remoteId)
            assertEquals("file-$i", db.getEntry("/t/b$i/f.txt")?.remoteId, "the row below moves along")
        }
    }

    @Test
    fun `a chain still waits for the tree it lands in to leave, beside independent renames`() {
        // /t/a -> /t/b while /t/b -> /t/c: a may land only once b has left. /s/x -> /s/y touches neither.
        seed("/t", "top", folder = true)
        seed("/t/a", "A", folder = true)
        seed("/t/a/fa.txt", "fa", folder = false)
        seed("/t/b", "B", folder = true)
        seed("/t/b/fb.txt", "fb", folder = false)
        seed("/s", "s", folder = true)
        seed("/s/x", "X", folder = true)
        seed("/s/x/fx.txt", "fx", folder = false)
        val changes =
            linkedMapOf(
                "/t/b" to folderItem("A", "/t/b"),
                "/t/c" to folderItem("B", "/t/c"),
                "/s/y" to folderItem("X", "/s/y"),
            )

        val moved = gather().moveRenamedFolders(changes, emptyMap())

        val order = moved.map { it.from to it.to }
        assertTrue(order.indexOf("/t/b" to "/t/c") < order.indexOf("/t/a" to "/t/b"), "b leaves before a lands: $order")
        assertTrue(("/s/x" to "/s/y") in order, "the independent rename lands: $order")
        assertEquals("A", db.getEntry("/t/b")?.remoteId)
        assertEquals("fa", db.getEntry("/t/b/fa.txt")?.remoteId)
        assertEquals("B", db.getEntry("/t/c")?.remoteId)
        assertEquals("fb", db.getEntry("/t/c/fb.txt")?.remoteId)
        assertEquals("fx", db.getEntry("/s/y/fx.txt")?.remoteId)
        assertNull(db.getEntry("/t/a"))
        assertNull(db.getEntry("/s/x"))
    }

    @Test
    fun `a nested rename is not batched with the rename of its parent`() {
        // /p -> /q and its child /p/c -> /q/d: the child's source moves with the parent, so they never share a step.
        seed("/p", "P", folder = true)
        seed("/p/c", "C", folder = true)
        seed("/p/c/f.txt", "f", folder = false)
        val changes =
            linkedMapOf(
                "/q" to folderItem("P", "/q"),
                "/q/d" to folderItem("C", "/q/d"),
            )

        gather().moveRenamedFolders(changes, emptyMap())

        assertEquals("P", db.getEntry("/q")?.remoteId)
        assertEquals("C", db.getEntry("/q/d")?.remoteId)
        assertEquals("f", db.getEntry("/q/d/f.txt")?.remoteId)
        assertNull(db.getEntry("/p/c"))
        assertNull(db.getEntry("/q/c"))
    }
}
