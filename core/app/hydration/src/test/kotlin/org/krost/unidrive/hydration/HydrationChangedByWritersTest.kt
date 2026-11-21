package org.krost.unidrive.hydration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.DownloadReceipt
import org.krost.unidrive.FolderState
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which writers stamp a row `local` or `remote` (gkrost/unidrive-cli#849), and which leave the stamp the row has: only the
 * writer that IS a change stamps; an upload that sent nothing, a download of the version the row already recorded and a
 * lookup that read nothing do not set a stamp the row did not earn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HydrationChangedByWritersTest {
    private suspend fun HydrationTestEnv.stampOf(path: String): Pair<String?, Long?> =
        (hydration.stat(path) as StatResult.Ok).entry.let { it.changedBy to it.changedAtMs }

    @Test
    fun `an upload that found the cloud already holding the bytes sent nothing and stamps nothing`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.syncEngine.seedRemoteContent("/q/same.txt", "x")
        env.stateDb.insertCloudInStepRow("/q/same.txt", "x")
        val cache = env.syncEngine.resolveCachePath("/q/same.txt").also { Files.createDirectories(it.parent); Files.writeString(it, "x") }

        env.hydration.openForWrite("conn1", "h1", "/q/same.txt", cache)
        advanceUntilIdle()

        assertEquals(0, env.syncEngine.uploadAttempts(), "nothing was sent")
        assertEquals(null to null, env.stampOf("/q/same.txt"), "a touch the cloud already holds is not an upload")
    }

    @Test
    fun `a folder created again for an upload is the engine's own change`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertFolderEntry("/docs")
        env.stateDb.insertCreatedRow("/docs/new.txt")
        val cache = env.syncEngine.resolveCachePath("/docs/new.txt").also { Files.createDirectories(it.parent); Files.writeString(it, "the user's bytes") }
        env.syncEngine.refuseUploadsParentGone(1, folder = "/docs")
        env.syncEngine.answerFolderState("id-/docs", FolderState.TRASHED)
        assertEquals(null to null, env.stampOf("/docs"))

        env.hydration.openForWrite("conn1", "h1", "/docs/new.txt", cache)
        advanceUntilIdle()

        assertEquals(listOf("/docs"), env.syncEngine.createdFolders(), "the folder is created again")
        val (by, ms) = env.stampOf("/docs")
        assertEquals("local", by)
        assertNotNull(ms)
    }

    /** A mount over a [MemProvider] holding /f.txt, whose first enumeration has stamped the row `remote`. */
    private suspend fun kotlinx.coroutines.test.TestScope.mountWithDeliveredRow(): Mounted {
        val provider = MemProvider()
        provider.seed("/f.txt", "cloud bytes".toByteArray(), Instant.parse("2026-03-28T12:00:00Z"))
        val db = StateDatabase(TestScratch.directory("ud-writers-db").resolve("state.db"), inMemory = true).also { it.initialize() }
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("ud-writers-root"),
                cacheRoot = TestScratch.directory("ud-writers-cache"),
            )
        val hydration = HydrationImpl(engine, db, recoveryUploadScope = this)
        MountEngine.over(engine).enumerateRemoteIntoState(reset = false)
        // The row as the engine's own upload left it, long before: the stamp a later writer must replace or keep.
        db.upsertEntry(db.getEntry("/f.txt")!!.copy(changedBy = "local", changedAtMs = 1000))
        return Mounted(provider, db, engine, hydration)
    }

    private class Mounted(val provider: MemProvider, val db: StateDatabase, val engine: SyncEngine, val hydration: HydrationImpl) {
        fun stamp(): Pair<String?, Long?> = db.getEntry("/f.txt")!!.let { it.changedBy to it.changedAtMs }
    }

    @Test
    fun `a download that finds the cloud moved on since the listing stamps the row remote`() = runTest {
        val m = mountWithDeliveredRow()
        m.provider.receiptFor = { _, staged -> DownloadReceipt(Files.size(staged), itemHash = "h-newer") }

        m.hydration.openForRead("conn1", "h-read", "/f.txt")
        advanceUntilIdle()

        assertEquals("h-newer", m.db.getEntry("/f.txt")!!.remoteHash, "the row took the version that came down")
        val (by, ms) = m.stamp()
        assertEquals("remote", by)
        assertTrue(assertNotNull(ms) > 1000, "the stamp is the read's, not the earlier upload's")
        m.db.close()
    }

    @Test
    fun `a download of the version the row already recorded keeps the stamp it has`() = runTest {
        val m = mountWithDeliveredRow()
        m.provider.receiptFor = { _, staged -> DownloadReceipt(Files.size(staged), itemHash = "h-/f.txt") }

        m.hydration.openForRead("conn1", "h-read", "/f.txt")
        advanceUntilIdle()

        assertEquals(true, m.db.getEntry("/f.txt")!!.isHydrated, "the bytes came down")
        assertEquals("local" to 1000L, m.stamp(), "a download of what the row held is housekeeping")
        m.db.close()
    }

    @Test
    fun `a write refused for a newer cloud version rebases the row to it and stamps it remote`() = runTest {
        val m = mountWithDeliveredRow()
        m.provider.items["/f.txt"] = m.provider.items.getValue("/f.txt").copy(hash = "h-newer")

        MountEngine.over(m.engine).rebaseToCloudAfterConflict("/f.txt") {}

        assertEquals("h-newer", m.db.getEntry("/f.txt")!!.remoteHash)
        val (by, ms) = m.stamp()
        assertEquals("remote", by)
        assertTrue(assertNotNull(ms) > 1000)
        m.db.close()
    }

    @Test
    fun `a rebase whose lookup read nothing leaves the row with no stamp, as its version is unknown then`() = runTest {
        val m = mountWithDeliveredRow()
        m.provider.items.remove("/f.txt")

        MountEngine.over(m.engine).rebaseToCloudAfterConflict("/f.txt") {}

        val row = m.db.getEntry("/f.txt")!!
        assertNull(row.remoteVersion, "a version the row knows to be stale is unknown")
        assertEquals(null to null, m.stamp(), "the stamp of the version the row had vouches for nothing")
        m.db.close()
    }
}
