package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.HashAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#438: a download whose bytes fail the engine's own integrity check (`verify_integrity`) is refused with
 * `content_refused: hash_mismatch`, and the refusal is remembered until the row names another version of the file: a preview,
 * a thumbnail or a retry of the same version no longer downloads the whole file again, and no time limit ends that. A new
 * version (another hash, version token or content id) is downloaded and checked afresh.
 */
class HydrationHashMismatchMemoryTest {
    private val path = "/q/f.txt"
    private val token = "content_refused: ${RemoteIncompleteMemory.HASH_MISMATCH_REASON}"

    private fun env(): HydrationTestEnv {
        val env = HydrationTestEnv(verifyIntegrity = true)
        env.providerForTest.hashAlgorithmValue = HashAlgorithm.Sha256Hex
        env.stateDb.insertUnhydratedEntry(path, remoteSize = 5, remoteHash = "not-the-hash-of-the-bytes")
        env.syncEngine.seedRemoteContent(path, "cloud")
        return env
    }

    private fun tokenOf(result: OpenResult): String {
        assertTrue(result is OpenResult.Failed, "expected a refusal: $result")
        return result.error.message
    }

    @Test
    fun `a mismatch is refused once from the cloud and then from memory, whatever time passes`() = runTest {
        val env = env()
        MountEngine.over(env.engine).remoteIncomplete.ttlMs = 0 // a time limit would revalidate at once; this one has none

        assertEquals(token, tokenOf(env.hydration.openForRead("conn1", "h1", path)))
        assertEquals(token, tokenOf(env.hydration.openForRead("conn1", "h2", path)))
        assertTrue(env.hydration.hydrate(path) is HydrateResult.Failed)

        assertEquals(1, env.syncEngine.downloadAttempts(), "ONE download for three reads of the same version")
    }

    @Test
    fun `a new version of the row is downloaded and checked again`() = runTest {
        val env = env()
        assertEquals(token, tokenOf(env.hydration.openForRead("conn1", "h1", path)))

        // The next listing brings another version of the file: its row names another hash.
        env.stateDb.insertUnhydratedEntry(path, remoteSize = 5, remoteHash = "the-hash-of-the-next-version")
        tokenOf(env.hydration.openForRead("conn1", "h2", path))

        assertEquals(2, env.syncEngine.downloadAttempts(), "another version is downloaded and checked again")
    }
}
