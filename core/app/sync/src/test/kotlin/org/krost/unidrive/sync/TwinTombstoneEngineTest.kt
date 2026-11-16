package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An incremental delta that carries only the tombstone of a twin (a duplicate-named item kept under its decorated name)
 * while the tracked winner of the canonical path is not mentioned. The tombstone's canonical path is the winner's path:
 * it must never reach the winner, in either engine mode; it concerns the twin's decorated row alone.
 */
class TwinTombstoneEngineTest {
    private val winner = file("/dup.txt", id = "aaaaaaaa-0001", size = 7)
    private val twin = file("/dup.txt", id = "bbbbbbbb-0002", size = 9)
    private val twinPath = "/dup (dup-bbbbbbbb).txt"

    private data class Outcome(
        val files: Map<String, Long>,
        val rows: Map<String, String?>,
        val deletedByIds: List<String>,
        val deletedPaths: List<String>,
        val uploads: List<Pair<String?, String>>,
    )

    private fun scenario(
        streaming: Boolean,
        beforeSecondPass: (Path) -> Unit = {},
    ): Outcome {
        val syncRoot = Files.createTempDirectory("ud-twintomb-root")
        val db = StateDatabase(Files.createTempDirectory("ud-twintomb-db").resolve("state.db"))
        db.initialize()
        try {
            val provider = TombstoneFakeProvider()
            val engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    reporter = DuplicateTwinNamesTest.CountingReporter(),
                    cacheRoot = Files.createTempDirectory("ud-twintomb-cache"),
                    cacheKey = "twin-tombstone-test",
                    streamingReconciliation = streaming,
                )
            provider.deltaItems = listOf(twin, winner)
            runTest { engine.syncOnce() }
            assertTrue(Files.exists(syncRoot.resolve(twinPath.removePrefix("/"))), "setup: the twin is materialized")

            beforeSecondPass(syncRoot)
            // The next, incremental delta carries only the tombstone of the twin, with the canonical path of the winner.
            provider.deltaItems = listOf(twin.copy(deleted = true))
            runTest { engine.syncOnce() }

            val files =
                Files.walk(syncRoot).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.toList().associate { syncRoot.relativize(it).toString().replace('\\', '/') to Files.size(it) }
                }
            return Outcome(
                files,
                db.getAllEntries().associate { it.path to it.remoteId },
                provider.deletedByIds.toList(),
                provider.deletedPaths.toList(),
                provider.uploads.toList(),
            )
        } finally {
            db.close()
        }
    }

    private fun check(streaming: Boolean) {
        // (1) Nothing changed locally: the twin's decorated copy goes, the winner's file and row stay.
        val unchanged = scenario(streaming)
        assertEquals(7L, unchanged.files["dup.txt"], "the winner's file is untouched (streaming=$streaming): ${unchanged.files}")
        assertEquals(winner.id, unchanged.rows["/dup.txt"], "the winner's row is untouched (streaming=$streaming): ${unchanged.rows}")
        assertEquals(null, unchanged.files[twinPath.removePrefix("/")], "the twin's decorated copy is removed (streaming=$streaming)")
        assertTrue(twinPath !in unchanged.rows, "and its row (streaming=$streaming): ${unchanged.rows}")
        assertTrue(unchanged.deletedByIds.isEmpty() && unchanged.deletedPaths.isEmpty(), "nothing is deleted in the cloud")

        // (2) The twin was edited locally: the edit is kept, nothing is deleted.
        val edited =
            scenario(streaming) { root ->
                val local = root.resolve(twinPath.removePrefix("/"))
                Files.write(local, ByteArray(12))
                Files.setLastModifiedTime(local, FileTime.fromMillis(System.currentTimeMillis() + 60_000))
            }
        assertEquals(12L, edited.files[twinPath.removePrefix("/")], "the edited twin file is kept (streaming=$streaming): ${edited.files}")
        assertEquals(7L, edited.files["dup.txt"], "the winner's file is untouched (streaming=$streaming)")
        assertEquals(winner.id, edited.rows["/dup.txt"], "the winner's row is untouched (streaming=$streaming)")
        assertTrue(edited.deletedByIds.isEmpty() && edited.deletedPaths.isEmpty(), "nothing is deleted in the cloud")

        // (3) The twin's file was already deleted locally: nothing happens to the winner.
        val gone = scenario(streaming) { root -> Files.delete(root.resolve(twinPath.removePrefix("/"))) }
        assertEquals(7L, gone.files["dup.txt"], "the winner's file is untouched (streaming=$streaming): ${gone.files}")
        assertEquals(winner.id, gone.rows["/dup.txt"], "the winner's row is untouched (streaming=$streaming)")
        assertTrue(gone.deletedByIds.isEmpty() && gone.deletedPaths.isEmpty(), "nothing is deleted in the cloud")
    }

    @Test
    fun `a twin tombstone alone never reaches the winner - buffered`() = check(streaming = false)

    @Test
    fun `a twin tombstone alone never reaches the winner - streaming`() = check(streaming = true)

    @Test
    fun `streaming and buffered end in the same disk and database state`() {
        val edits: List<(Path) -> Unit> =
            listOf(
                {},
                { root ->
                    val local = root.resolve(twinPath.removePrefix("/"))
                    Files.write(local, ByteArray(12))
                    Files.setLastModifiedTime(local, FileTime.fromMillis(System.currentTimeMillis() + 60_000))
                },
                { root -> Files.delete(root.resolve(twinPath.removePrefix("/"))) },
            )
        for ((i, edit) in edits.withIndex()) {
            assertEquals(scenario(streaming = false, edit), scenario(streaming = true, edit), "scenario ${i + 1}")
        }
    }

    // ---- fakes ----

    private fun file(
        path: String,
        id: String,
        size: Long,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        isFolder = false,
        modified = Instant.parse("2026-01-01T00:00:00Z"),
        created = Instant.parse("2026-01-01T00:00:00Z"),
        hash = null,
        mimeType = null,
    )

    private class TombstoneFakeProvider : CloudProvider {
        override val id = "twin-tombstone-fake"
        override val displayName = "Twin Tombstone Fake"
        override var isAuthenticated = true
        override val allowsDuplicateNames = true

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        var deltaItems: List<CloudItem> = emptyList()
        val deletedPaths = mutableListOf<String>()
        val deletedByIds = mutableListOf<String>()
        val uploads = mutableListOf<Pair<String?, String>>()
        private val known = mutableMapOf<String, CloudItem>()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String): CloudItem = deltaItems.last { it.path == path }

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long {
            val item = known[remoteId] ?: deltaItems.first { it.id == remoteId }
            known[remoteId] = item
            val bytes = ByteArray(item.size.toInt())
            Files.createDirectories(destination.parent)
            Files.write(destination, bytes)
            return bytes.size.toLong()
        }

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("path download must not happen for id-carrying rows")

        override suspend fun deleteById(
            remoteId: String,
            remotePath: String,
        ) {
            deletedByIds += remoteId
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            deletedPaths += remotePath
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploads += existingRemoteId to remotePath
            return CloudItem(
                id = existingRemoteId ?: "ffffffff-${uploads.size}",
                name = remotePath.substringAfterLast('/'),
                path = remotePath,
                size = Files.size(localPath),
                isFolder = false,
                modified = Instant.parse("2026-01-02T00:00:00Z"),
                created = Instant.parse("2026-01-01T00:00:00Z"),
                hash = null,
                mimeType = null,
            )
        }

        override suspend fun createFolder(path: String): CloudItem = error("not expected in this test")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not expected in this test")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(items = deltaItems, cursor = "c1", hasMore = false, complete = true)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0, used = 0, remaining = 0)
    }
}
