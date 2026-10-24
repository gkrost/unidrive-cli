package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * archive#1046: a remote rename `/a` -> `/b` while an unsynced local `/b` of the same size exists. Both sides are new at
 * `/b` and the same size, which the reconciler adopts and rename detection then turns into a move of `/a` onto `/b`.
 * The move used to replace the user's file. (Different sizes are a plain conflict, SyncEngineTest.)
 */
class RemoteRenameOntoLocalFileTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-rename-onto-local")
        db = StateDatabase(Files.createTempDirectory("unidrive-rename-onto-local-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
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

    private fun item(path: String) =
        CloudItem(
            id = "remote-a",
            name = path.substringAfterLast("/"),
            path = path,
            size = "remote bytes".length.toLong(),
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = "hash-remote-a",
            mimeType = null,
        )

    private fun copies(): List<Path> =
        Files.list(syncRoot).use { s -> s.filter { it.fileName.toString().contains(".conflict-local-") }.toList() }

    @Test
    fun `a rename onto an unsynced local file of the same size keeps that file as a conflict copy`() =
        runTest {
            provider.files["/a.txt"] = "remote bytes".toByteArray()
            provider.deltaItems = listOf(item("/a.txt"))
            engine.syncOnce()
            assertEquals("remote bytes", Files.readString(syncRoot.resolve("a.txt")))

            Files.writeString(syncRoot.resolve("b.txt"), "local bytes!")
            assertEquals("remote bytes".length, "local bytes!".length)
            provider.files["/b.txt"] = provider.files.remove("/a.txt")!!
            provider.deltaItems = listOf(item("/b.txt"))
            provider.deltaCursor = "cursor-2"

            engine.syncOnce()

            assertEquals("remote bytes", Files.readString(syncRoot.resolve("b.txt")), "the rename is applied")
            assertFalse(Files.exists(syncRoot.resolve("a.txt")))
            val kept = copies().single()
            assertEquals("local bytes!", Files.readString(kept), "the user's file survives")
            assertEquals("remote-a", db.getEntry("/b.txt")?.remoteId)

            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-3"
            engine.syncOnce()

            assertEquals("remote bytes", Files.readString(syncRoot.resolve("b.txt")))
            assertEquals("local bytes!", Files.readString(copies().single()))
            assertTrue(provider.deletedPaths.isEmpty(), "nothing is deleted in the cloud: ${provider.deletedPaths}")
            assertTrue(provider.uploadedPaths.any { it.contains(".conflict-local-") }, "the copy goes up: ${provider.uploadedPaths}")
        }
}
