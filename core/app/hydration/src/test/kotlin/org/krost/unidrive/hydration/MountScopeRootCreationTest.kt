package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#22: a `sync_path` root that is a folder in the local root and that the cloud does not have is created
 * by the mount after its first enumeration, as the sync creates it (PR #299). A root the cloud has, or holds under another
 * spelling, is left alone, and a folder the drive refuses by name is recorded once, not asked for on every poll.
 */
class MountScopeRootCreationTest {
    private lateinit var root: Path
    private lateinit var db: StateDatabase
    private lateinit var fake: FakeCloudProvider

    // The folder creates the provider refuses by name (FakeCloudProvider has no such hook).
    private val refusedByName = mutableSetOf<String>()
    private var createCalls = 0

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("unidrive-mount-scope-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-mount-scope-db").resolve("state.db"))
        db.initialize()
        fake = FakeCloudProvider().apply { walksScopeRoots = true }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private inner class Provider(
        private val inner: FakeCloudProvider,
    ) : CloudProvider by inner {
        override suspend fun createFolder(path: String): CloudItem {
            createCalls++
            if (path in refusedByName) throw NameRefusedException("refused by name: $path", NameRefusedException.SIMILAR_NAME, "other")
            return inner.createFolder(path)
        }
    }

    private fun mount(scope: List<String>): MountEngine =
        MountEngine.over(
            SyncEngine(
                provider = Provider(fake),
                db = db,
                syncRoot = root,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                syncPaths = scope,
                standingScope = scope,
                cacheRoot = Files.createTempDirectory("unidrive-mount-scope-cache"),
            ),
        )

    private fun localFolder(path: String) {
        Files.createDirectories(root.resolve(path.removePrefix("/")))
    }

    private fun folder(path: String) =
        CloudItem(
            id = "id-$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = 0,
            isFolder = true,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = null,
            mimeType = null,
        )

    @Test
    fun `a scope root only the local side has is created once`() =
        runTest {
            localFolder("/new")
            val mount = mount(listOf("/new"))

            assertTrue(mount.enumerateRemoteIntoState(reset = false).ok)
            assertTrue(mount.enumerateRemoteIntoState(reset = false).ok)

            assertEquals(listOf("/new"), fake.createdFolders.toList())
            assertEquals(1, createCalls, "asked once, not on every enumeration")
            assertNotNull(db.getEntry("/new")?.remoteId, "the root's row takes the created folder")
            assertEquals(ScopeRootCreator.Outcome.Created(listOf("/new")), mount.scopeRootOutcomes()["/new"])
        }

    @Test
    fun `a nested scope root is created below its missing parent, parent first`() =
        runTest {
            localFolder("/p/q")
            val mount = mount(listOf("/p/q"))

            assertTrue(mount.enumerateRemoteIntoState(reset = false).ok)

            assertEquals(listOf("/p", "/p/q"), fake.createdFolders.toList())
            assertNotNull(db.getEntry("/p/q")?.remoteId)
        }

    @Test
    fun `a scope root the cloud has is left untouched`() =
        runTest {
            localFolder("/new")
            fake.deltaItems = listOf(folder("/new"))
            fake.childrenByParent["/"] = listOf(folder("/new"))
            val mount = mount(listOf("/new"))

            assertTrue(mount.enumerateRemoteIntoState(reset = false).ok)

            assertEquals(0, createCalls)
            assertNull(mount.scopeRootOutcomes()["/new"], "a root the listing brought in is not one to create")
        }

    @Test
    fun `a folder another creator made in the meantime is taken, not created again`() =
        runTest {
            localFolder("/new")
            // The listing did not show it yet; the parent's listing does.
            fake.childrenByParent["/"] = listOf(folder("/new"))
            fake.remoteFoldersExisting += "/new"
            val mount = mount(listOf("/new"))

            assertTrue(mount.enumerateRemoteIntoState(reset = false).ok)

            assertEquals(0, createCalls)
            assertEquals("exists-/new", db.getEntry("/new")?.remoteId)
            assertEquals(ScopeRootCreator.Outcome.Created(emptyList()), mount.scopeRootOutcomes()["/new"])
        }

    @Test
    fun `a racing creator that wins the create is taken as well`() =
        runTest {
            localFolder("/new")
            // The parent's listing does not show it, and the create answers 409: someone else was first.
            fake.remoteFoldersExisting += "/new"
            val mount = mount(listOf("/new"))

            assertTrue(mount.enumerateRemoteIntoState(reset = false).ok)

            assertEquals(1, createCalls)
            assertEquals("exists-/new", db.getEntry("/new")?.remoteId)
            assertIs<ScopeRootCreator.Outcome.Created>(mount.scopeRootOutcomes()["/new"])
        }

    @Test
    fun `nothing is created when the cloud holds the root under another case`() =
        runTest {
            localFolder("/new")
            fake.childrenByParent["/"] = listOf(folder("/New"))
            val mount = mount(listOf("/new"))

            assertTrue(mount.enumerateRemoteIntoState(reset = false).ok)
            assertTrue(mount.enumerateRemoteIntoState(reset = false).ok)

            assertEquals(0, createCalls)
            assertEquals(ScopeRootCreator.Outcome.NameTaken("/new", "New"), mount.scopeRootOutcomes()["/new"])
            assertNull(db.getEntry("/new"), "no row is written for it")
            assertEquals(1, fake.listChildrenCalls.count { it == "/" }, "the second enumeration does not ask again")
        }

    @Test
    fun `a root the drive refuses by name is recorded once and not retried on every enumeration`() =
        runTest {
            localFolder("/new")
            refusedByName += "/new"
            val mount = mount(listOf("/new"))

            repeat(3) { assertTrue(mount.enumerateRemoteIntoState(reset = false).ok) }

            assertEquals(1, createCalls, "no loop")
            assertEquals(ScopeRootCreator.Outcome.Refused("/new", NameRefusedException.SIMILAR_NAME), mount.scopeRootOutcomes()["/new"])
            assertNotNull(db.nameBlock("/new"), "the refusal is the blocked folder the status reports")
            assertNull(db.getEntry("/new")?.remoteId)
        }

    @Test
    fun `a create that fails for another reason is tried again after the next enumeration`() =
        runTest {
            localFolder("/new")
            fake.createFolderFailPaths += "/new"
            val mount = mount(listOf("/new"))

            assertTrue(mount.enumerateRemoteIntoState(reset = false).ok)
            assertIs<ScopeRootCreator.Outcome.Failed>(mount.scopeRootOutcomes()["/new"])

            fake.createFolderFailPaths.clear()
            assertTrue(mount.enumerateRemoteIntoState(reset = false).ok)

            assertEquals(2, createCalls)
            assertEquals(ScopeRootCreator.Outcome.Created(listOf("/new")), mount.scopeRootOutcomes()["/new"])
        }

    @Test
    fun `a root that keeps failing is given up on after three enumerations in a row`() =
        runTest {
            localFolder("/new")
            fake.createFolderFailPaths += "/new"
            val mount = mount(listOf("/new"))

            repeat(6) { assertTrue(mount.enumerateRemoteIntoState(reset = false).ok) }

            assertEquals(3, createCalls, "no retry past the third consecutive failure")
            assertIs<ScopeRootCreator.Outcome.Failed>(mount.scopeRootOutcomes()["/new"])
        }

    @Test
    fun `the exact spelling is taken even when a case-variant twin is listed first`() =
        runTest {
            localFolder("/new")
            fake.childrenByParent["/"] = listOf(folder("/New"), folder("/new"))
            fake.remoteFoldersExisting += "/new"
            val mount = mount(listOf("/new"))

            assertTrue(mount.enumerateRemoteIntoState(reset = false).ok)

            assertEquals(0, createCalls)
            assertEquals("exists-/new", db.getEntry("/new")?.remoteId)
            assertEquals(ScopeRootCreator.Outcome.Created(emptyList()), mount.scopeRootOutcomes()["/new"])
        }

    @Test
    fun `a root neither side has fails the enumeration and creates nothing`() =
        runTest {
            val mount = mount(listOf("/typo"))

            val result = mount.enumerateRemoteIntoState(reset = false)

            assertEquals(false, result.ok)
            assertTrue(result.error.orEmpty().contains("not found on the remote"), result.error)
            assertEquals(0, createCalls)
        }

    @Test
    fun `a root that synced before and is gone from the cloud is not created again`() =
        runTest {
            localFolder("/old")
            db.insertFolder(path = "/old", remoteId = "id-/old", mtime = Instant.parse("2026-03-28T12:00:00Z"))
            val mount = mount(listOf("/old"))

            mount.enumerateRemoteIntoState(reset = false)

            assertEquals(0, createCalls)
        }
}
