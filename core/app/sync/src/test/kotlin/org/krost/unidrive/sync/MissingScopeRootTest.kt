package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// gkrost/unidrive-cli#22: a sync_path root that is a folder in the local sync root but not on the remote is an empty
// remote folder the engine creates; one that neither side has still fails the run (the typo case).
class MissingScopeRootTest {
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-missing-root")
        dbPath = Files.createTempDirectory("unidrive-missing-root-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        provider = FakeCloudProvider().apply { walksScopeRoots = true }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine(
        scope: List<String>,
        state: StateDatabase = db,
    ) = SyncEngine(
        provider = provider,
        db = state,
        syncRoot = syncRoot,
        conflictPolicy = ConflictPolicy.KEEP_BOTH,
        reporter = ProgressReporter.Silent,
        syncPaths = scope,
        standingScope = scope,
    )

    private fun local(
        path: String,
        content: String,
    ) {
        val file = syncRoot.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun remote(
        path: String,
        isFolder: Boolean = false,
        content: String = "",
    ): CloudItem {
        if (!isFolder) provider.files[path] = content.toByteArray()
        return CloudItem(
            id = "id-$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = if (isFolder) 0 else content.length.toLong(),
            isFolder = isFolder,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = null,
            mimeType = null,
        )
    }

    @Test
    fun `a scope root only the local side has is created on the remote and synced on the first run`() =
        runTest {
            local("new/a.txt", "a")
            local("new/sub/b.txt", "bb")

            engine(listOf("/new")).syncOnce()

            assertEquals(setOf("/new"), provider.lastScanContext?.creatableRoots)
            assertTrue("/new" in provider.createdFolders, "the root is created: ${provider.createdFolders}")
            assertTrue("/new/sub" in provider.createdFolders, "the folder below it too: ${provider.createdFolders}")
            assertEquals(setOf("/new/a.txt", "/new/sub/b.txt"), provider.uploadedPaths.toSet())
            assertNotNull(db.getEntry("/new"))
            assertNotNull(db.getEntry("/new/a.txt")?.remoteId)
            assertNotNull(db.getEntry("/new/sub/b.txt")?.remoteId)
            assertTrue(provider.deletedPaths.isEmpty(), "nothing is deleted on the remote: ${provider.deletedPaths}")
        }

    @Test
    fun `rows awaiting their upload below a local-only root do not stop it from being created`() =
        runTest {
            // What the local scan of a run that failed on the missing root left behind: a pending-upload row, no remote id.
            local("new/a.txt", "a")
            db.upsertEntry(
                org.krost.unidrive.sync.model.SyncEntry(
                    path = "/new/a.txt",
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0,
                    remoteModified = null,
                    localMtime = Files.getLastModifiedTime(syncRoot.resolve("new/a.txt")).toMillis(),
                    localSize = 1,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.EPOCH,
                ),
            )

            engine(listOf("/new")).syncOnce()

            assertEquals(setOf("/new"), provider.lastScanContext?.creatableRoots)
            assertTrue("/new" in provider.createdFolders, "the root is created: ${provider.createdFolders}")
            assertEquals(listOf("/new/a.txt"), provider.uploadedPaths)
        }

    @Test
    fun `a nested scope root whose parent the remote lacks too is created with its parent`() =
        runTest {
            local("p/q/x.txt", "x")

            engine(listOf("/p/q")).syncOnce()

            assertEquals(setOf("/p/q"), provider.lastScanContext?.creatableRoots)
            assertTrue("/p" in provider.createdFolders, "the parent is created: ${provider.createdFolders}")
            assertTrue("/p/q" in provider.createdFolders, "the root is created: ${provider.createdFolders}")
            assertTrue(
                provider.createdFolders.indexOf("/p") < provider.createdFolders.indexOf("/p/q"),
                "the parent first: ${provider.createdFolders}",
            )
            assertEquals(listOf("/p/q/x.txt"), provider.uploadedPaths)
        }

    @Test
    fun `a scope root neither side has still fails the run`() =
        runTest {
            local("elsewhere/a.txt", "a")

            val error = assertFailsWith<ProviderException> { engine(listOf("/typo")).syncOnce() }

            assertEquals("sync_path '/typo' not found on the remote: no folder 'typo'", error.message)
            assertEquals(emptySet(), provider.lastScanContext?.creatableRoots)
            assertTrue(provider.createdFolders.isEmpty(), "nothing is created: ${provider.createdFolders}")
            assertTrue(provider.uploadedPaths.isEmpty(), "nothing is uploaded: ${provider.uploadedPaths}")
        }

    @Test
    fun `a local file at the scope root's path is no folder and the run still fails`() =
        runTest {
            local("typo", "not a folder")

            assertFailsWith<ProviderException> { engine(listOf("/typo")).syncOnce() }

            assertEquals(emptySet(), provider.lastScanContext?.creatableRoots)
            assertTrue(provider.createdFolders.isEmpty(), "nothing is created: ${provider.createdFolders}")
        }

    @Test
    fun `a scope root spelled otherwise than the local folder is no folder to create`() =
        runTest {
            // A file system that folds case finds "Docs" for "/docs" too; the spelling still has to match.
            local("Docs/a.txt", "a")

            assertFailsWith<ProviderException> { engine(listOf("/docs")).syncOnce() }

            assertEquals(emptySet(), provider.lastScanContext?.creatableRoots)
            assertTrue(provider.createdFolders.isEmpty(), "nothing is created: ${provider.createdFolders}")
        }

    @Test
    fun `a scope root the remote has is synced as before`() =
        runTest {
            provider.deltaItems = listOf(remote("/docs", isFolder = true), remote("/docs/r.txt", content = "remote"))
            local("docs/l.txt", "local")

            engine(listOf("/docs")).syncOnce()

            assertFalse("/docs" in provider.createdFolders, "an existing root is not created again: ${provider.createdFolders}")
            assertEquals("remote", Files.readString(syncRoot.resolve("docs/r.txt")))
            assertEquals(listOf("/docs/l.txt"), provider.uploadedPaths)
            assertTrue(provider.deletedPaths.isEmpty(), "nothing is deleted on the remote: ${provider.deletedPaths}")
        }

    @Test
    fun `a tracked scope root that is gone from the remote still fails a full listing and deletes nothing`() =
        runTest {
            provider.deltaItems = listOf(remote("/docs", isFolder = true), remote("/docs/r.txt", content = "remote"))
            engine(listOf("/docs")).syncOnce()
            assertNotNull(db.getEntry("/docs/r.txt"))

            // The folder is gone on the remote, and the next gather is a full listing (as after a cursor that expired).
            provider.deltaItems = emptyList()
            provider.files.clear()
            db.setSyncState("delta_cursor", "")

            assertFailsWith<ProviderException> { engine(listOf("/docs")).syncOnce() }

            assertEquals(emptySet(), provider.lastScanContext?.creatableRoots, "state.db tracks rows below it")
            assertEquals("remote", Files.readString(syncRoot.resolve("docs/r.txt")), "the local copy stays")
            assertNotNull(db.getEntry("/docs/r.txt"))
            assertTrue(provider.createdFolders.isEmpty(), "nothing is created: ${provider.createdFolders}")
        }

    @Test
    fun `adding a local-only scope root deletes nothing in the roots already synced`() =
        runTest {
            provider.deltaItems =
                listOf(
                    remote("/a", isFolder = true),
                    remote("/a/one.txt", content = "1"),
                    remote("/a/two.txt", content = "2"),
                    remote("/other", isFolder = true),
                    remote("/other/o.txt", content = "o"),
                )
            engine(listOf("/a")).syncOnce()
            assertNotNull(db.getEntry("/a/two.txt"))
            local("new/n.txt", "n")

            // Widening the scope re-enumerates the drive with both roots; /new exists only on the local side.
            engine(listOf("/a", "/new")).syncOnce()

            assertEquals(setOf("/new"), provider.lastScanContext?.creatableRoots)
            assertTrue(provider.deletedPaths.isEmpty(), "nothing is deleted on the remote: ${provider.deletedPaths}")
            assertEquals(listOf("/new/n.txt"), provider.uploadedPaths)
            assertEquals("1", Files.readString(syncRoot.resolve("a/one.txt")))
            assertEquals("2", Files.readString(syncRoot.resolve("a/two.txt")))
            assertNotNull(db.getEntry("/a/one.txt"))
            assertNotNull(db.getEntry("/new/n.txt")?.remoteId)
        }

    @Test
    fun `a dry run plans the creation instead of failing`() =
        runTest {
            local("new/a.txt", "a")

            val snapshot = StateDatabase.snapshotOf(dbPath)
            try {
                engine(listOf("/new"), snapshot).syncOnce(dryRun = true)
            } finally {
                snapshot.close()
            }

            assertEquals(setOf("/new"), provider.lastScanContext?.creatableRoots)
            assertTrue(provider.createdFolders.isEmpty(), "a dry run creates nothing: ${provider.createdFolders}")
            assertTrue(provider.uploadedPaths.isEmpty(), "a dry run uploads nothing: ${provider.uploadedPaths}")
        }

    @Test
    fun `a provider that lists the whole drive creates a local-only scope root as well`() =
        runTest {
            provider.walksScopeRoots = false
            local("new/a.txt", "a")

            engine(listOf("/new")).syncOnce()

            assertTrue("/new" in provider.createdFolders, "the root is created: ${provider.createdFolders}")
            assertEquals(listOf("/new/a.txt"), provider.uploadedPaths)
        }
}
