package org.krost.unidrive.hydration

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.PermanentUploadFailureException
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * gkrost/unidrive-cli#952: `rm -r` of a folder refused its own run's data. Three invariants, one test each:
 * a file this run uploaded and settled is deletable (the folder delete's snapshot guard compared the upload's content hash
 * with a listing that carries none); a file that never reached the cloud and whose upload the provider refused for good
 * does not hold the delete back; a file that really changed in the cloud still refuses.
 */
class HydrationRmOwnRunTest {
    private class Env(
        val provider: MemProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
    )

    private suspend fun emptyFolderTree(): Env {
        val provider = MemProvider()
        provider.seedFolder("/dir")
        val db = StateDatabase(TestScratch.directory("ud-952-db").resolve("state.db"))
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-952-root"),
                cacheRoot = TestScratch.directory("ud-952-cache"),
                cacheKey = "ud-952-isolated-profile",
            )
        engine.syncOnce()
        return Env(provider, db, engine)
    }

    // What an S3 listing says of an object this run uploaded: its size, time and write token, but no content hash (a
    // listing cannot tell the encryption kind, so the ETag is no proven MD5), while the upload's HEAD gave one.
    private fun listedWithoutHash(item: CloudItem) = item.copy(hash = null)

    @Test
    fun `a file this run uploaded and settled is deletable although the listing carries no content hash`() =
        runTest(timeout = 5.minutes) {
            val env = emptyFolderTree()
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
            val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/dir/up.txt"))
            Files.writeString(created.cachePath, "own bytes")
            hydration.openForWrite("conn", "h1", "/dir/up.txt", created.cachePath)
            advanceUntilIdle()
            hydration.closeHandle("conn", "h1")
            assertNotNull(env.db.getEntry("/dir/up.txt")?.remoteId, "precondition: the upload settled")
            assertNotNull(env.db.getEntry("/dir/up.txt")?.remoteHash, "precondition: the upload recorded a content hash")
            env.provider.listOverride = listOf(listedWithoutHash(env.provider.items.getValue("/dir/up.txt")))

            assertIs<RmdirResult.Ok>(hydration.rmdir("/dir", recursive = true))

            assertEquals(listOf("/dir"), env.provider.deleted)
            assertNull(env.db.getEntry("/dir/up.txt"), "the settled row goes with the tree")
        }

    @Test
    fun `a file that never reached the cloud and whose upload was refused for good does not block the delete`() =
        runTest(timeout = 5.minutes) {
            val env = emptyFolderTree()
            env.provider.uploadFailure = PermanentUploadFailureException("402 payment required: the plan refuses empty files")
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this, exhaustedRetryDelaysMs = emptyList())
            val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/dir/refused.txt"))
            Files.writeString(created.cachePath, "")
            hydration.openForWrite("conn", "h1", "/dir/refused.txt", created.cachePath)
            advanceUntilIdle()
            hydration.closeHandle("conn", "h1")
            val row = assertNotNull(env.db.getEntry("/dir/refused.txt"), "precondition: the row is there")
            assertTrue(row.isPendingUpload, "precondition: it never reached the cloud")
            assertNotNull(env.db.uploadRefusal("/dir/refused.txt"), "precondition: the refusal is recorded")

            assertIs<RmdirResult.Ok>(hydration.rmdir("/dir", recursive = true))

            assertNull(env.db.getEntry("/dir/refused.txt"), "its row is cleaned up locally")
            assertTrue(Files.notExists(created.cachePath), "and so is its cache copy")
        }

    @Test
    fun `a file that really changed in the cloud still refuses the delete`() =
        runTest(timeout = 5.minutes) {
            val env = emptyFolderTree()
            val hydration = HydrationImpl(env.engine, env.db, recoveryUploadScope = this)
            val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/dir/up.txt"))
            Files.writeString(created.cachePath, "own bytes")
            hydration.openForWrite("conn", "h1", "/dir/up.txt", created.cachePath)
            advanceUntilIdle()
            hydration.closeHandle("conn", "h1")
            val uploaded = env.provider.items.getValue("/dir/up.txt")
            // Someone else rewrote it: other bytes (size, time), still no hash in the listing.
            env.provider.listOverride = listOf(listedWithoutHash(uploaded).copy(size = uploaded.size + 7, modified = Instant.parse("2026-04-01T00:00:00Z")))

            val refused = hydration.rmdir("/dir", recursive = true)

            assertEquals(RmdirResult.NotEmpty(UnobservedChildException.CHANGED, "/dir/up.txt"), refused)
            assertTrue(env.provider.deleted.isEmpty(), "nothing may be deleted in the cloud")
            assertNotNull(env.db.getEntry("/dir/up.txt"), "the row stays")
        }
}
