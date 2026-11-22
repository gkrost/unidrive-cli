package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.model.EntryStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * archive#796: a row the daemon lists, whose file the live provider shows at the same path, must not answer
 * `not_found` only because the remote id the row keeps no longer resolves.
 *
 * `ls --live` resolves a path by name, folder by folder; `hydration.open_read` downloads by the row's remote id. When the
 * two disagree (the row keeps an id the drive no longer knows, while a file of that name lies at the path) the read used
 * to fail as a gone file. A download that fails as not found by id asks the provider for the item at the row's path once;
 * a file there under another id is read, and the row takes that id. Nothing at the path stays `not_found`.
 *
 * gkrost/unidrive-cli#40: a row the read cannot heal although something is at its path (the same id again, a folder, an id
 * another row already holds, a lookup the provider does not answer) answers `unresolvable_row: <reason>` instead, and every
 * row the read cannot heal is marked (`download_quarantined`) so `unidrive doctor` names it: reported, not hidden. A read
 * that comes through ends the mark. None of it deletes or moves anything in the cloud.
 */
class HydrationOpenReadStaleIdTest {
    private class FakeStatusException(
        message: String,
        @Suppress("unused") val statusCode: Int,
    ) : RuntimeException(message)

    private val goneById = FakeStatusException("API error: 404 Not Found", statusCode = 404)

    @Test
    fun `a listed row whose id is gone is read through the file the provider shows at its path`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/ops/bench/run.py", remoteSize = 5, remoteId = "stale-uuid")
            env.syncEngine.seedRemoteContent("/ops/bench/run.py", "print")
            env.syncEngine.makeNextDownloadThrow(goneById)

            val result = env.hydration.openForRead("conn1", "h1", "/ops/bench/run.py")

            assertTrue(result is OpenResult.Ok, "the file exists at the path: $result")
            assertEquals("print", Files.readString(result.cachePath))
            assertEquals(
                listOf("stale-uuid", "id-/ops/bench/run.py"),
                env.providerForTest.downloadsById.map { it.first },
                "the stored id first, then the id the provider reports at the path",
            )
            assertEquals("id-/ops/bench/run.py", env.stateDb.remoteIdOf("/ops/bench/run.py"), "the row takes the live id")
        }

    @Test
    fun `a typed gone failure by id is re-resolved the same way`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/a.txt", remoteSize = 1, remoteId = "stale-uuid")
            env.syncEngine.seedRemoteContent("/a.txt", "a")
            env.syncEngine.makeNextDownloadThrow(PermanentDownloadFailureException("bucket entry gone (404)"))

            val result = env.hydration.openForRead("conn1", "h1", "/a.txt")

            assertTrue(result is OpenResult.Ok, "$result")
        }

    @Test
    fun `nothing at the path stays not_found`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/gone.txt", remoteSize = 5, remoteId = "stale-uuid")
            env.syncEngine.makeNextDownloadThrow(goneById)

            val result = env.hydration.openForRead("conn1", "h1", "/gone.txt")

            assertTrue(result is OpenResult.Failed, "$result")
            assertEquals(HydrationError.NotFound.message, result.error.message)
            assertEquals(1, env.providerForTest.downloadsById.size, "no second download")
            assertEquals("stale-uuid", env.stateDb.remoteIdOf("/gone.txt"), "the row is left as it was")
        }

    @Test
    fun `the same id at the path answers unresolvable_row and is not downloaded twice`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/same.txt", remoteSize = 5) // id-/same.txt, what getMetadata reports
            env.syncEngine.seedRemoteContent("/same.txt", "same!")
            env.syncEngine.makeNextDownloadThrow(goneById)

            val result = env.hydration.openForRead("conn1", "h1", "/same.txt")

            assertTrue(result is OpenResult.Failed, "$result")
            // gkrost/unidrive-cli#40: the cloud lists the file, so this is no gone file.
            assertEquals(HydrationError.UnresolvableRow(UnresolvableRowException.SAME_ID).message, result.error.message)
            assertEquals(1, env.providerForTest.downloadsById.size)
        }

    @Test
    fun `a live id another row holds is not taken and answers unresolvable_row`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/twin.txt", remoteSize = 5, remoteId = "stale-uuid")
            env.stateDb.insertUnhydratedEntry("/elsewhere.txt", remoteSize = 5, remoteId = "id-/twin.txt")
            env.syncEngine.seedRemoteContent("/twin.txt", "twin!")
            env.syncEngine.makeNextDownloadThrow(goneById)

            val result = env.hydration.openForRead("conn1", "h1", "/twin.txt")

            assertTrue(result is OpenResult.Failed, "$result")
            assertEquals(HydrationError.UnresolvableRow(UnresolvableRowException.ANOTHER_ROWS_ID).message, result.error.message)
            assertEquals(1, env.providerForTest.downloadsById.size)
            assertEquals("id-/twin.txt", env.stateDb.remoteIdOf("/elsewhere.txt"), "the other row keeps its id")
        }

    @Test
    fun `a failure that is not a not-found is not re-resolved`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/flaky.txt", remoteSize = 5, remoteId = "stale-uuid")
            env.syncEngine.seedRemoteContent("/flaky.txt", "flaky")
            env.syncEngine.makeNextDownloadThrow(FakeStatusException("serviceUnavailable", statusCode = 503))

            val result = env.hydration.openForRead("conn1", "h1", "/flaky.txt")

            assertTrue(result is OpenResult.Failed, "$result")
            assertTrue(result.error.message != HydrationError.NotFound.message, result.error.message)
            assertEquals(1, env.providerForTest.downloadsById.size)
            assertEquals("stale-uuid", env.stateDb.remoteIdOf("/flaky.txt"))
        }

    @Test
    fun `a folder at the path answers unresolvable_row`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/dir.txt", remoteSize = 5, remoteId = "stale-uuid")
            env.providerForTest.liveFolders += "/dir.txt"
            env.syncEngine.makeNextDownloadThrow(goneById)

            val result = env.hydration.openForRead("conn1", "h1", "/dir.txt")

            assertTrue(result is OpenResult.Failed, "$result")
            assertEquals(HydrationError.UnresolvableRow(UnresolvableRowException.FOLDER).message, result.error.message)
            assertEquals(1, env.providerForTest.downloadsById.size)
            assertEquals("stale-uuid", env.stateDb.remoteIdOf("/dir.txt"), "the row keeps its id")
        }

    @Test
    fun `a path lookup the provider does not answer as not found answers unresolvable_row`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/twins.txt", remoteSize = 5, remoteId = "stale-uuid")
            env.syncEngine.seedRemoteContent("/twins.txt", "twins")
            // Two items of one name: the provider fails closed (archive#402) instead of picking one.
            env.providerForTest.onFolderMetadata = { p ->
                if (p == "/twins.txt") throw ProviderException("ambiguous: 2 items named 'twins.txt' in / resolve by path")
            }
            env.syncEngine.makeNextDownloadThrow(goneById)

            val result = env.hydration.openForRead("conn1", "h1", "/twins.txt")

            assertTrue(result is OpenResult.Failed, "$result")
            assertEquals(HydrationError.UnresolvableRow(UnresolvableRowException.LOOKUP_FAILED).message, result.error.message)
            assertEquals(1, env.providerForTest.downloadsById.size, "no file picked")
            assertEquals("stale-uuid", env.stateDb.remoteIdOf("/twins.txt"))
        }

    @Test
    fun `a row the read cannot heal is marked for doctor`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/same.txt", remoteSize = 5)
            env.syncEngine.seedRemoteContent("/same.txt", "same!")
            env.stateDb.insertUnhydratedEntry("/gone.txt", remoteSize = 5, remoteId = "stale-uuid")
            env.syncEngine.makeNextDownloadThrow(goneById)
            env.hydration.openForRead("conn1", "h1", "/same.txt")
            env.syncEngine.makeNextDownloadThrow(goneById)
            env.hydration.openForRead("conn1", "h2", "/gone.txt")

            assertTrue(env.stateDb.isDownloadQuarantined("/same.txt"), "unresolvable: something is at the path")
            assertTrue(env.stateDb.isDownloadQuarantined("/gone.txt"), "not_found: nothing is at the path")
            assertTrue(env.stateDb.lastErrorAt("/same.txt") != null)
            assertEquals(EntryStatus.EXISTS, env.stateDb.statusOf("/gone.txt"), "the row stays listed")
        }

    @Test
    fun `a failure that is not a not-found does not mark the row`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/flaky.txt", remoteSize = 5, remoteId = "stale-uuid")
            env.syncEngine.makeNextDownloadThrow(FakeStatusException("serviceUnavailable", statusCode = 503))

            env.hydration.openForRead("conn1", "h1", "/flaky.txt")

            assertTrue(!env.stateDb.isDownloadQuarantined("/flaky.txt"))
        }

    @Test
    fun `a read that comes through ends the mark`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/same.txt", remoteSize = 5)
            env.syncEngine.seedRemoteContent("/same.txt", "same!")
            env.syncEngine.makeNextDownloadThrow(goneById)
            env.hydration.openForRead("conn1", "h1", "/same.txt")
            assertTrue(env.stateDb.isDownloadQuarantined("/same.txt"))

            val again = env.hydration.openForRead("conn1", "h2", "/same.txt")

            assertTrue(again is OpenResult.Ok, "$again")
            assertTrue(!env.stateDb.isDownloadQuarantined("/same.txt"), "the mark ends with the read")
            assertEquals(null, env.stateDb.lastErrorAt("/same.txt"))
        }

    @Test
    fun `a healed read ends the mark of an earlier failed one`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/run.py", remoteSize = 5, remoteId = "stale-uuid")
            env.syncEngine.makeNextDownloadThrow(goneById)
            env.hydration.openForRead("conn1", "h1", "/run.py") // nothing at the path yet
            assertTrue(env.stateDb.isDownloadQuarantined("/run.py"))
            env.syncEngine.seedRemoteContent("/run.py", "print") // the file reappears under another id
            env.syncEngine.makeNextDownloadThrow(goneById)

            val healed = env.hydration.openForRead("conn1", "h2", "/run.py")

            assertTrue(healed is OpenResult.Ok, "$healed")
            assertEquals("id-/run.py", env.stateDb.remoteIdOf("/run.py"))
            assertTrue(!env.stateDb.isDownloadQuarantined("/run.py"))
        }

    @Test
    fun `healing or failing to heal never deletes or moves a cloud copy`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/heal.txt", remoteSize = 4, remoteId = "stale-1")
            env.syncEngine.seedRemoteContent("/heal.txt", "heal")
            env.stateDb.insertUnhydratedEntry("/same.txt", remoteSize = 5)
            env.syncEngine.seedRemoteContent("/same.txt", "same!")
            env.stateDb.insertUnhydratedEntry("/twin.txt", remoteSize = 5, remoteId = "stale-2")
            env.stateDb.insertUnhydratedEntry("/other.txt", remoteSize = 5, remoteId = "id-/twin.txt")
            env.syncEngine.seedRemoteContent("/twin.txt", "twin!")
            for ((i, p) in listOf("/heal.txt", "/same.txt", "/twin.txt").withIndex()) {
                env.syncEngine.makeNextDownloadThrow(goneById)
                env.hydration.openForRead("conn1", "h$i", p)
            }

            assertEquals(emptyList(), env.providerForTest.deletedPaths)
            assertEquals(emptyList(), env.providerForTest.permanentlyDeleted)
            assertEquals(emptyList(), env.providerForTest.movedPairs)
            for (p in listOf("/heal.txt", "/same.txt", "/twin.txt")) {
                assertTrue(env.providerForTest.remoteContent(p) != null, "$p is still in the cloud")
            }
        }
}
