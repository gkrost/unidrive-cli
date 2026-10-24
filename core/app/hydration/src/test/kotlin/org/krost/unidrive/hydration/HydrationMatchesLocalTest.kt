package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ContentProof
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.model.EntryStatus
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * archive#1219, `hydration.matches_local`: whether a plain local file holds the bytes of the cloud copy, proven without downloading it.
 * The order of the proofs (size, the row's content hash, the recorded SHA-256 of bytes the engine wrote itself, the provider's stored-object
 * proof) and the one rule that matters: "no proof possible" is [MatchesLocalResult.Unknown], never a difference.
 */
class HydrationMatchesLocalTest {
    // quickXorHash("abc") as OneDrive reports it, and SHA-256("abc").
    private val quickXorAbc = "YRDDGAAAAAAAAAAAAwAAAAAAAAA="
    private val sha256Abc = MessageDigest.getInstance("SHA-256").digest("abc".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun local(content: String): Path = Files.createTempFile("matches-local", ".bin").also { Files.writeString(it, content) }

    private fun row(
        path: String,
        remoteSize: Long = 3,
        remoteHash: String? = null,
        isHydrated: Boolean = false,
        localHash: String? = null,
        localSize: Long? = null,
        editBase: String? = null,
    ) = SyncEntry(
        path = path,
        remoteId = "id-$path",
        remoteHash = remoteHash,
        remoteSize = remoteSize,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = if (isHydrated) 1_000L else null,
        localSize = localSize,
        isFolder = false,
        isPinned = false,
        isHydrated = isHydrated,
        lastSynced = Instant.now(),
        localHash = localHash,
        editBase = editBase,
    )

    private val unknownSizeIsNotADifference = "a row with size 0 can be the older size-unknown shape"

    @Test
    fun `a content-hash provider proves the same file by the rows hash and never asks the provider`() =
        runTest {
            val env = HydrationTestEnv()
            env.providerForTest.hashAlgorithmValue = HashAlgorithm.QuickXor
            env.stateDb.upsert(row("/a.txt", remoteHash = quickXorAbc))

            assertEquals(MatchesLocalResult.Same("quickxor"), env.hydration.matchesLocal("/a.txt", local("abc")))
            assertEquals(MatchesLocalResult.Differs("quickxor"), env.hydration.matchesLocal("/a.txt", local("abd")))
            assertTrue(env.providerForTest.proofAsks.isEmpty(), "a row hash needs no question to the provider")
        }

    @Test
    fun `a size other than the rows is a difference at once, without hashing or asking`() =
        runTest {
            val env = HydrationTestEnv()
            env.providerForTest.hashAlgorithmValue = HashAlgorithm.QuickXor
            env.stateDb.upsert(row("/a.txt", remoteSize = 3, remoteHash = quickXorAbc))

            assertEquals(MatchesLocalResult.Differs("size"), env.hydration.matchesLocal("/a.txt", local("abcd")))
            assertTrue(env.providerForTest.proofAsks.isEmpty())
        }

    @Test
    fun `a row of size zero is no evidence of a size difference`() =
        runTest {
            val env = HydrationTestEnv()
            env.providerForTest.hashAlgorithmValue = HashAlgorithm.QuickXor
            env.stateDb.upsert(row("/a.txt", remoteSize = 0, remoteHash = quickXorAbc))

            assertEquals(MatchesLocalResult.Same("quickxor"), env.hydration.matchesLocal("/a.txt", local("abc")), unknownSizeIsNotADifference)
        }

    @Test
    fun `a hash of no shape the algorithm knows is not judged and falls through to the providers own proof`() =
        runTest {
            val env = HydrationTestEnv()
            env.providerForTest.hashAlgorithmValue = HashAlgorithm.QuickXor
            env.providerForTest.contentProof = { _, _ -> null }
            env.stateDb.upsert(row("/a.txt", remoteHash = "some-etag"))

            assertEquals(MatchesLocalResult.Unknown("no_proof"), env.hydration.matchesLocal("/a.txt", local("abc")))
            assertEquals(listOf("id-/a.txt"), env.providerForTest.proofAsks.toList())
        }

    @Test
    fun `a provider without a hash uses the sha256 the engine recorded for these very bytes`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.upsert(row("/a.txt", isHydrated = true, localHash = sha256Abc, localSize = 3))

            assertEquals(MatchesLocalResult.Same("recorded-sha256"), env.hydration.matchesLocal("/a.txt", local("abc")))
            assertTrue(env.providerForTest.proofAsks.isEmpty(), "the recorded hash needs no network")
        }

    @Test
    fun `a recorded hash that differs from the file decides nothing, the providers proof decides`() =
        runTest {
            val env = HydrationTestEnv()
            env.providerForTest.contentProof = { _, _ -> ContentProof(false, "internxt-shard-hash") }
            env.stateDb.upsert(row("/a.txt", isHydrated = true, localHash = sha256Abc, localSize = 3))

            assertEquals(MatchesLocalResult.Differs("internxt-shard-hash"), env.hydration.matchesLocal("/a.txt", local("abd")))
        }

    @Test
    fun `a recorded hash is not trusted for a row with an unsynced edit, an unhydrated row or another baseline size`() =
        runTest {
            val env = HydrationTestEnv()
            env.providerForTest.contentProof = { _, _ -> null }
            env.stateDb.upsert(row("/edit.txt", isHydrated = true, localHash = sha256Abc, localSize = 3, editBase = ""))
            env.stateDb.upsert(row("/cold.txt", isHydrated = false, localHash = sha256Abc, localSize = 3))
            env.stateDb.upsert(row("/size.txt", isHydrated = true, localHash = sha256Abc, localSize = 99))

            for (p in listOf("/edit.txt", "/cold.txt", "/size.txt")) {
                assertEquals(MatchesLocalResult.Unknown("no_proof"), env.hydration.matchesLocal(p, local("abc")), p)
            }
            assertEquals(3, env.providerForTest.proofAsks.size, "each fell through to the provider")
        }

    @Test
    fun `the stored-object proof of the provider is the answer when no cheaper proof applies`() =
        runTest {
            val env = HydrationTestEnv()
            env.providerForTest.contentProof = { _, _ -> ContentProof(true, "internxt-shard-hash") }
            env.stateDb.upsert(row("/a.txt"))

            assertEquals(MatchesLocalResult.Same("internxt-shard-hash"), env.hydration.matchesLocal("/a.txt", local("abc")))
        }

    @Test
    fun `a provider that has no proof, or fails, leaves the answer unknown and never a difference`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.upsert(row("/a.txt"))

            assertEquals(MatchesLocalResult.Unknown("no_proof"), env.hydration.matchesLocal("/a.txt", local("abc")))

            env.providerForTest.contentProof = { _, _ -> throw java.io.IOException("network down: https://example.invalid/secret-token") }
            assertEquals(MatchesLocalResult.Unknown("proof_failed"), env.hydration.matchesLocal("/a.txt", local("abc")))
        }

    @Test
    fun `a paused daemon asks the provider nothing`() =
        runTest {
            val env = HydrationTestEnv()
            env.providerForTest.contentProof = { _, _ -> ContentProof(true, "internxt-shard-hash") }
            env.stateDb.upsert(row("/a.txt"))
            env.pause.pause("user", auto = false)

            assertEquals(MatchesLocalResult.Unknown("paused"), env.hydration.matchesLocal("/a.txt", local("abc")))
            assertTrue(env.providerForTest.proofAsks.isEmpty())
        }

    @Test
    fun `a path without a cloud copy is unknown`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.upsert(row("/folder").copy(isFolder = true))
            env.stateDb.upsert(row("/pending.txt").copy(remoteId = null))
            env.stateDb.upsert(row("/gone.txt").copy(status = EntryStatus.DELETED))

            // A row that is no longer alive (deleted) is read as no row at all, like every read verb reads it.
            for (p in listOf("/nope.txt", "/gone.txt")) {
                assertEquals(MatchesLocalResult.Unknown("unknown_path"), env.hydration.matchesLocal(p, local("abc")), p)
            }
            for (p in listOf("/folder", "/pending.txt")) {
                assertEquals(MatchesLocalResult.Unknown("no_cloud_copy"), env.hydration.matchesLocal(p, local("abc")), p)
            }
            assertTrue(env.providerForTest.proofAsks.isEmpty())
        }

    @Test
    fun `a local file that is missing, a folder, or a relative path cannot be answered`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.upsert(row("/a.txt"))

            val missing = Files.createTempDirectory("matches-local-dir").resolve("not-there.bin")
            val folder = Files.createTempDirectory("matches-local-dir")

            for (p in listOf(missing, folder)) {
                val r = env.hydration.matchesLocal("/a.txt", p)
                assertIs<MatchesLocalResult.Failed>(r, p.toString())
                assertEquals("local_file_unreadable", r.error.message)
            }
            val relative = env.hydration.matchesLocal("/a.txt", Paths.get("relative.bin"))
            assertIs<MatchesLocalResult.Failed>(relative)
            assertEquals("invalid_local_path", relative.error.message)
        }
}
