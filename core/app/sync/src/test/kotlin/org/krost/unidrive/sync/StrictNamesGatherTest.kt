package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.PermanentUploadFailureException
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gather side of `internxt_strict_names` (gkrost/unidrive-cli#29): a cloud item whose name the provider's own
 * clients would not accept ([CloudProvider.existingNameIssue]) is not materialised — the row is quarantined and
 * marked blocked by name, the siblings list on, and nothing is re-attempted on the next pass. A write the provider
 * refuses by name blocks the row too, and a delivery the provider accepts again takes a stale block off. With the
 * mode off the same items materialise as today. The fake answers for plain host-portable names, so the suite is
 * platform-independent.
 */
class StrictNamesGatherTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-strict-gather")
        db = StateDatabase(Files.createTempDirectory("unidrive-strict-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider().also { it.strictNameMode = true }
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
            )
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun cloudItem(
        path: String,
        isFolder: Boolean = false,
        id: String = "id-$path",
    ): CloudItem =
        CloudItem(
            id = id,
            name = path.substringAfterLast("/"),
            path = path,
            size = 5,
            isFolder = isFolder,
            modified = null,
            created = null,
            hash = null,
            mimeType = null,
        )

    private fun seed(path: String) {
        provider.files[path] = "bytes".toByteArray()
    }

    private fun downloaded(path: String): Boolean =
        provider.downloadByIdCalls.any { it.second == path } || provider.downloadByPathCalls.any { it == path }

    @Test
    fun `a refused cloud file is not materialised, is quarantined and blocked, and its sibling lists`() =
        runTest {
            provider.existingNameIssues["/skipme.txt"] = "name_incompatible: windows_character"
            seed("/keep.txt")
            provider.deltaItems = listOf(cloudItem("/skipme.txt"), cloudItem("/keep.txt"))

            engine.syncOnce()

            assertTrue(db.getEntry("/keep.txt") != null, "the sibling is tracked")
            assertTrue(Files.exists(syncRoot.resolve("keep.txt")), "the sibling is materialised")
            val refused = db.getEntry("/skipme.txt")
            assertNotNull(refused, "the refused item keeps its row")
            assertTrue(refused.downloadQuarantined, "the row is quarantined")
            assertEquals("name_incompatible: windows_character", db.nameBlock("/skipme.txt")?.reason)
            assertTrue(!Files.exists(syncRoot.resolve("skipme.txt")), "nothing is materialised under the refused name")
            assertTrue(!downloaded("/skipme.txt"), "no bytes are fetched for it")
        }

    @Test
    fun `a refused cloud folder is not created and its row is blocked`() =
        runTest {
            provider.existingNameIssues["/skipdir"] = "name_incompatible: trailing_whitespace"
            provider.deltaItems = listOf(cloudItem("/skipdir", isFolder = true), cloudItem("/okdir", isFolder = true))

            engine.syncOnce()

            assertTrue(Files.isDirectory(syncRoot.resolve("okdir")), "the sibling folder is created")
            assertTrue(!Files.exists(syncRoot.resolve("skipdir")), "the refused folder is not created")
            assertEquals("name_incompatible: trailing_whitespace", db.nameBlock("/skipdir")?.reason)
        }

    @Test
    fun `the next pass does not re-attempt the refused item`() =
        runTest {
            provider.existingNameIssues["/skipme.txt"] = "name_incompatible: windows_character"
            seed("/skipme.txt")
            provider.deltaItems = listOf(cloudItem("/skipme.txt"))
            engine.syncOnce()

            provider.deltaItems = emptyList()
            engine.syncOnce()

            assertTrue(!downloaded("/skipme.txt"), "the quarantined row is not re-downloaded")
            assertTrue(!Files.exists(syncRoot.resolve("skipme.txt")))
        }

    @Test
    fun `with the mode off the same items materialise as today`() =
        runTest {
            provider.strictNameMode = false
            seed("/skipme.txt")
            provider.deltaItems = listOf(cloudItem("/skipme.txt"), cloudItem("/skipdir", isFolder = true))

            engine.syncOnce()

            assertTrue(Files.exists(syncRoot.resolve("skipme.txt")))
            assertTrue(Files.isDirectory(syncRoot.resolve("skipdir")))
            assertNull(db.nameBlock("/skipme.txt"))
            assertNull(db.nameBlock("/skipdir"))
        }

    @Test
    fun `an upload the provider refuses by name blocks the row and is not retried while the content stands`() =
        runTest {
            provider.deltaItems = emptyList()
            Files.writeString(syncRoot.resolve("local.txt"), "refused by name")
            provider.uploadThrowByPath["/local.txt"] =
                NameRefusedException("Refused before any request", reason = "name_incompatible: windows_character")

            engine.syncOnce()

            assertEquals("name_incompatible: windows_character", db.nameBlock("/local.txt")?.reason)
            assertEquals(listOf("/local.txt"), provider.uploadAttempts, "one attempt, no retry ladder")
            assertNotNull(db.uploadRefusal("/local.txt"), "the refusal is recorded as any permanent one")
        }

    @Test
    fun `a mode that goes off lets the blocked row settle on the next delivery`() =
        runTest {
            provider.existingNameIssues["/skipme.txt"] = "name_incompatible: windows_character"
            seed("/skipme.txt")
            provider.deltaItems = listOf(cloudItem("/skipme.txt"))
            engine.syncOnce()
            assertEquals("name_incompatible: windows_character", db.nameBlock("/skipme.txt")?.reason)

            // The mode goes off and the item arrives again (it was never gone in the cloud). The delivery is
            // merged after the plan of this pass (archive#1043 order), so the pass takes the stale block and the
            // quarantine off, and the next pass materialises the row.
            provider.existingNameIssues.clear()
            provider.deltaItems = listOf(cloudItem("/skipme.txt"))
            engine.syncOnce()
            assertTrue(!downloaded("/skipme.txt"))

            engine.syncOnce()

            assertTrue(Files.exists(syncRoot.resolve("skipme.txt")), "the item is materialised with the mode off")
            assertNull(db.nameBlock("/skipme.txt"), "the block does not survive a delivery the provider accepts")
        }

    @Test
    fun `the mode turning on marks a settled row whose name the clients refuse, and touches nothing`() =
        runTest {
            seed("/skipme.txt")
            provider.deltaItems = listOf(cloudItem("/skipme.txt"))
            engine.syncOnce()
            assertNull(db.nameBlock("/skipme.txt"))

            provider.existingNameIssues["/skipme.txt"] = "name_incompatible: windows_character"
            engine.syncOnce()

            assertEquals("name_incompatible: windows_character", db.nameBlock("/skipme.txt")?.reason)
            assertTrue(Files.exists(syncRoot.resolve("skipme.txt")), "the local copy stays where it is")
            assertTrue(provider.deletedPaths.isEmpty(), "the cloud item is not deleted")
            assertTrue(provider.uploadAttempts.isEmpty(), "nothing is uploaded over it")
        }

    @Test
    fun `a block of another kind is neither overwritten nor lifted by the strict-name reconcile`() =
        runTest {
            seed("/twin.txt")
            provider.deltaItems = listOf(cloudItem("/twin.txt"))
            engine.syncOnce()
            db.markNameBlocked("/twin.txt", "similar_name", "Twin.txt")

            provider.existingNameIssues["/twin.txt"] = "name_incompatible: windows_character"
            engine.syncOnce()
            assertEquals("similar_name", db.nameBlock("/twin.txt")?.reason, "the sibling block stays while the name is refused")
            assertEquals("Twin.txt", db.nameBlock("/twin.txt")?.sibling)

            provider.existingNameIssues.clear()
            engine.syncOnce()
            assertEquals("similar_name", db.nameBlock("/twin.txt")?.reason, "and stays when the strict-name answer is gone")
        }

    @Test
    fun `a remote rename of a settled item into a refused name marks the row at its new path`() =
        runTest {
            seed("/fine.txt")
            provider.deltaItems = listOf(cloudItem("/fine.txt", id = "the-id"))
            engine.syncOnce()
            assertNull(db.nameBlock("/fine.txt"))

            // Renamed in the web UI to a name the official clients refuse: the same id arrives at the new path.
            provider.existingNameIssues["/bad.txt"] = "name_incompatible: windows_character"
            seed("/bad.txt")
            provider.deltaItems = listOf(cloudItem("/bad.txt", id = "the-id"))
            engine.syncOnce()

            assertEquals("name_incompatible: windows_character", db.nameBlock("/bad.txt")?.reason, "the renamed row is blocked by its new name")
            assertNull(db.nameBlock("/fine.txt"))
        }

    @Test
    fun `a refused name is not deleted from the cloud by the plan`() =
        runTest {
            provider.existingNameIssues["/skipme.txt"] = "name_incompatible: windows_character"
            seed("/skipme.txt")
            provider.deltaItems = listOf(cloudItem("/skipme.txt"))
            engine.syncOnce()
            provider.deltaItems = emptyList()
            engine.syncOnce()

            assertTrue(provider.deletedPaths.isEmpty(), "nothing is deleted remotely: ${provider.deletedPaths}")
            assertTrue(!downloaded("/skipme.txt"))
        }

    @Test
    fun `a non-name permanent upload failure does not block the row by name`() =
        runTest {
            provider.deltaItems = emptyList()
            Files.writeString(syncRoot.resolve("plan.txt"), "content")
            provider.uploadThrowByPath["/plan.txt"] = PermanentUploadFailureException("plan refuses empty files")

            engine.syncOnce()

            assertNull(db.nameBlock("/plan.txt"), "only a refusal by the name itself is a name block")
            assertNotNull(db.uploadRefusal("/plan.txt"))
        }
}
