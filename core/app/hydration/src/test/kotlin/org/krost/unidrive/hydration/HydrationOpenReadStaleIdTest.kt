package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentDownloadFailureException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive#796: a row the daemon lists, whose file the live provider shows at the same path, must not answer
 * `not_found` only because the remote id the row keeps no longer resolves.
 *
 * `ls --live` resolves a path by name, folder by folder; `hydration.open_read` downloads by the row's remote id. When the
 * two disagree (the row keeps an id the drive no longer knows, while a file of that name lies at the path) the read used
 * to fail as a gone file. A download that fails as not found by id asks the provider for the item at the row's path once;
 * a file there under another id is read, and the row takes that id. Everything else stays `not_found`: nothing at the path,
 * the same id again, a folder, an id another row already holds.
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
    fun `the same id at the path stays not_found and is not downloaded twice`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/same.txt", remoteSize = 5) // id-/same.txt, what getMetadata reports
            env.syncEngine.seedRemoteContent("/same.txt", "same!")
            env.syncEngine.makeNextDownloadThrow(goneById)

            val result = env.hydration.openForRead("conn1", "h1", "/same.txt")

            assertTrue(result is OpenResult.Failed, "$result")
            assertEquals(HydrationError.NotFound.message, result.error.message)
            assertEquals(1, env.providerForTest.downloadsById.size)
        }

    @Test
    fun `a live id another row holds is not taken`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/twin.txt", remoteSize = 5, remoteId = "stale-uuid")
            env.stateDb.insertUnhydratedEntry("/elsewhere.txt", remoteSize = 5, remoteId = "id-/twin.txt")
            env.syncEngine.seedRemoteContent("/twin.txt", "twin!")
            env.syncEngine.makeNextDownloadThrow(goneById)

            val result = env.hydration.openForRead("conn1", "h1", "/twin.txt")

            assertTrue(result is OpenResult.Failed, "$result")
            assertEquals(HydrationError.NotFound.message, result.error.message)
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
}
