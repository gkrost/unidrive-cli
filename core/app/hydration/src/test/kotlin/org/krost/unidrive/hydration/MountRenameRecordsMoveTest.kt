package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.engine.RemoteOperationGuard
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
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

// gkrost/unidrive-cli#164: the mount's own rename moved the item in the cloud and left the row with what the item was before.
// Internxt's token ("<uuid>|<modificationTime>|<size>") moves with a rename, so the next poll read the moved item as new content and
// ended the row's hydration. The row now takes what the move returned, as long as nothing says the bytes changed meanwhile.
class MountRenameRecordsMoveTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val before = Instant.parse("2026-10-28T14:55:02Z")
    private val after = Instant.parse("2026-10-28T15:04:36Z")

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-164-rename")
        Files.createDirectories(tmp.resolve("root"))
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    // An Internxt-like provider: no content hash, its hash is the token, and a move stamps a new modification time.
    private class TokenProvider(
        private val inner: FakeCloudProvider = FakeCloudProvider(),
    ) : CloudProvider by inner {
        var current: CloudItem? = null
        var moved: CloudItem? = null
        val lookups = mutableListOf<String>()

        override suspend fun getMetadata(path: String): CloudItem {
            lookups += path
            return assertNotNull(current, "no lookup expected")
        }

        override suspend fun moveById(
            remoteId: String,
            fromPath: String,
            toPath: String,
        ): CloudItem = assertNotNull(moved)
    }

    private fun item(
        path: String,
        modified: Instant,
        size: Long = 5,
    ) = CloudItem(
        id = "u-1",
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        isFolder = false,
        modified = modified,
        created = null,
        hash = "u-1|$modified|$size",
        mimeType = null,
    )

    private fun mountOver(provider: CloudProvider): MountEngine =
        SyncEngine(provider = provider, db = db, syncRoot = tmp.resolve("root"), reporter = ProgressReporter.Silent, cacheRoot = tmp.resolve("cache"))
            .mount

    private fun hydratedRow(listed: CloudItem) =
        SyncEntry(
            path = listed.path,
            remoteId = listed.id,
            remoteHash = listed.hash,
            remoteSize = listed.size,
            remoteModified = listed.modified,
            localMtime = 0,
            localSize = listed.size,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.now(),
            cacheBacked = true,
        )

    // The next poll delivers the moved item; the cache copy is a clean copy of the recorded bytes.
    private fun nextPoll(
        provider: CloudProvider,
        delivered: CloudItem,
    ) {
        RemoteGather(
            provider = provider,
            db = db,
            guard =
                RemoteOperationGuard(
                    standingScope = emptyList(),
                    syncPaths = emptyList(),
                    excludePatterns = emptyList(),
                    maxConcurrentTransfers = 2,
                    matchesGlob = { _, _ -> false },
                ),
            options = RemoteGather.Options(providerId = "internxt"),
            listener = object : RemoteGather.Listener {},
            log = LoggerFactory.getLogger(MountRenameRecordsMoveTest::class.java),
        ).updateRemoteEntries(mapOf(delivered.path to delivered), emptyMap(), keepPendingUploads = true, cacheIsPlainCopy = { true })
    }

    @Test
    fun `the row takes the token the move returned, and the next poll keeps it hydrated`() =
        runTest {
            val provider = TokenProvider()
            val listed = item("/a.txt", before)
            db.upsertEntry(hydratedRow(listed))
            provider.current = listed
            provider.moved = item("/b.txt", after)

            mountOver(provider).renameRemote("/a.txt", "/b.txt")

            val row = assertNotNull(db.getEntry("/b.txt"))
            assertEquals(provider.moved!!.hash, row.remoteHash, "the token the next poll lists")
            assertEquals(after, row.remoteModified)
            nextPoll(provider, provider.moved!!)
            assertTrue(assertNotNull(db.getEntry("/b.txt")).isHydrated, "a rename changes no byte")
        }

    @Test
    fun `a version the cloud took on before the rename is not recorded, so the next poll ends the hydration`() =
        runTest {
            // Another device uploaded other bytes of the same size after the last poll: the cache copy is not what the item holds.
            val provider = TokenProvider()
            val listed = item("/a.txt", before)
            db.upsertEntry(hydratedRow(listed))
            provider.current = item("/a.txt", Instant.parse("2026-10-28T15:00:00Z"))
            provider.moved = item("/b.txt", after)

            mountOver(provider).renameRemote("/a.txt", "/b.txt")

            assertEquals(listed.hash, assertNotNull(db.getEntry("/b.txt")).remoteHash, "the row still names the bytes it holds")
            nextPoll(provider, provider.moved!!)
            assertFalse(assertNotNull(db.getEntry("/b.txt")).isHydrated, "the next read downloads the cloud's bytes")
        }

    @Test
    fun `a row that names its bytes takes what the move returned without asking the cloud first`() =
        runTest {
            val provider = TokenProvider()
            val listed = item("/a.txt", before).copy(contentId = "obj-1|5")
            db.upsertEntry(hydratedRow(listed).copy(remoteContentId = listed.contentId))
            provider.moved = item("/b.txt", after).copy(contentId = "obj-1|5")

            mountOver(provider).renameRemote("/a.txt", "/b.txt")

            assertTrue(provider.lookups.isEmpty(), "the stored object proves the bytes: ${provider.lookups}")
            val row = assertNotNull(db.getEntry("/b.txt"))
            assertEquals(provider.moved!!.hash, row.remoteHash)
            assertEquals("obj-1|5", row.remoteContentId)
            nextPoll(provider, provider.moved!!)
            assertTrue(assertNotNull(db.getEntry("/b.txt")).isHydrated)
        }

    @Test
    fun `a move that answers with another stored object is not recorded`() =
        runTest {
            val provider = TokenProvider()
            val listed = item("/a.txt", before).copy(contentId = "obj-1|5")
            db.upsertEntry(hydratedRow(listed).copy(remoteContentId = listed.contentId))
            provider.moved = item("/b.txt", after).copy(contentId = "obj-2|5")

            mountOver(provider).renameRemote("/a.txt", "/b.txt")

            val row = assertNotNull(db.getEntry("/b.txt"))
            assertEquals(listed.hash, row.remoteHash)
            assertEquals("obj-1|5", row.remoteContentId)
            nextPoll(provider, provider.moved!!)
            assertFalse(assertNotNull(db.getEntry("/b.txt")).isHydrated)
        }
}
