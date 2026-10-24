package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.RemoteGather
import org.krost.unidrive.engine.RemoteOperationGuard
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A cloud-backed file edited through the mount whose upload the provider refused (a permanent refusal of the request):
 * the cache copy is the only copy of the edit. Neither the refusal nor the next poll that delivers the item again may
 * make it look like the cloud's version.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UploadRefusalSurvivesRedeliveryTest {
    private class Env(
        val provider: MinimalFakeProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
        val cache: Path,
        val baselineMtime: Long,
        val baselineSize: Long,
    )

    // A row the mount hydrated: its baseline is the cache copy, then the file is edited and the edit is queued.
    private suspend fun TestScope.editedRow(): Pair<Env, HydrationImpl> {
        val provider = MinimalFakeProvider(mutableMapOf("/a.txt" to "original".toByteArray()))
        val db = StateDatabase(Files.createTempDirectory("refusal-redelivery-db").resolve("state.db"))
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createTempDirectory("refusal-redelivery-root"),
                cacheRoot = Files.createTempDirectory("refusal-redelivery-cache"),
                cacheKey = "refusal-redelivery-isolated-profile",
            )
        db.upsertEntry(
            SyncEntry(
                path = "/a.txt",
                remoteId = "id-a",
                remoteHash = null,
                remoteSize = 8,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.parse("2026-03-28T12:00:00Z"),
            ),
        )
        val cache = engine.ensureHydrated("/a.txt")
        val baseline = assertNotNull(db.getEntry("/a.txt"))
        assertEquals(true, baseline.cacheBacked, "precondition: the cache copy is the row's baseline")
        val hydration =
            HydrationImpl(
                engine,
                db,
                recoveryUploadScope = this,
                cacheMaxBytes = 1,
                cacheAccessGraceMs = 0,
                evictionDelayMs = 0,
                evictionMinIntervalMs = 0,
                failedReplayDelayMs = 0,
            )
        Files.writeString(cache, "an edit the cloud refuses")
        Files.setLastModifiedTime(cache, FileTime.from(Instant.now().plusSeconds(5)))
        return Env(provider, db, engine, cache, baseline.localMtime!!, baseline.localSize!!) to hydration
    }

    private fun mergeAgain(db: StateDatabase) {
        val gather =
            RemoteGather(
                provider = MinimalFakeProvider(),
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
                log = LoggerFactory.getLogger(UploadRefusalSurvivesRedeliveryTest::class.java),
            )
        val row = assertNotNull(db.getEntry("/a.txt"))
        gather.updateRemoteEntries(
            mapOf(
                "/a.txt" to
                    CloudItem(
                        id = row.remoteId!!,
                        name = "a.txt",
                        path = "/a.txt",
                        size = row.remoteSize,
                        isFolder = false,
                        modified = row.remoteModified,
                        created = null,
                        hash = row.remoteHash,
                        mimeType = null,
                    ),
            ),
        )
    }

    @Test
    fun `the cache copy of a refused edit is kept when the poll delivers the item again and the budget is small`() =
        runTest {
            val (env, hydration) = editedRow()
            env.provider.uploadRefusedRemaining.set(1)
            hydration.openForWrite("conn", "h1", "/a.txt", env.cache)
            advanceUntilIdle()
            hydration.closeHandle("conn", "h1") // an open handle would protect the copy by itself
            assertNotNull(env.db.uploadRefusal("/a.txt"), "precondition: the provider refused the upload")

            mergeAgain(env.db)
            hydration.evictCache()

            assertTrue(Files.exists(env.cache), "the cache copy is the only copy of the refused edit")
            assertEquals("an edit the cloud refuses", Files.readString(env.cache))
            assertEquals(true, env.db.getEntry("/a.txt")?.isHydrated)
            env.db.close()
        }

    @Test
    fun `a refused edit of a cloud-backed row is protected by its bytes alone, whatever the failure marks say`() =
        runTest {
            val (env, hydration) = editedRow()
            env.provider.uploadRefusedRemaining.set(1)
            hydration.openForWrite("conn", "h1", "/a.txt", env.cache)
            advanceUntilIdle()
            hydration.closeHandle("conn", "h1")
            // Even a row whose failure time is gone (and whose refusal is gone) must not read as the cloud's version.
            env.db.upsertEntry(assertNotNull(env.db.getEntry("/a.txt")).copy(lastErrorAt = null))
            assertNull(env.db.uploadRefusal("/a.txt"))

            hydration.evictCache()

            assertTrue(Files.exists(env.cache))
            env.db.close()
        }

    @Test
    fun `a refusal leaves the baseline of a cloud-backed row where the download put it`() =
        runTest {
            val (env, hydration) = editedRow()
            env.provider.uploadRefusedRemaining.set(1)

            hydration.openForWrite("conn", "h1", "/a.txt", env.cache)
            advanceUntilIdle()

            val row = assertNotNull(env.db.getEntry("/a.txt"))
            assertNotNull(env.db.uploadRefusal("/a.txt"))
            assertEquals(env.baselineMtime, row.localMtime, "the baseline is the cloud's version, not the refused edit")
            assertEquals(env.baselineSize, row.localSize)
            env.db.close()
        }

    @Test
    fun `a refused edit of a cloud-backed row is replayed once the retry deadline has passed`() =
        runTest {
            val (env, hydration) = editedRow()
            env.provider.uploadRefusedRemaining.set(1)
            hydration.openForWrite("conn", "h1", "/a.txt", env.cache)
            advanceUntilIdle()
            val attempts = env.provider.uploadAttempts()
            assertEquals(0, hydration.replayPendingUploads(), "before the deadline the same bytes are not sent again")
            assertEquals(attempts, env.provider.uploadAttempts())

            // The deadline passes: the stamp keeps the content's mtime and size, its retry time lies in the past.
            val stamp = "${Files.getLastModifiedTime(env.cache).toMillis()}|${Files.size(env.cache)}"
            assertTrue(env.db.markUploadRefused("/a.txt", "$stamp|${System.currentTimeMillis() - 1}|too large", keepCloudBaseline = true))

            assertEquals(1, hydration.replayPendingUploads(), "the edit is still owed to the cloud")
            advanceUntilIdle()
            assertEquals(attempts + 1, env.provider.uploadAttempts())
            assertEquals("an edit the cloud refuses", env.provider.uploadedContent("/a.txt"))
            env.db.close()
        }
}
