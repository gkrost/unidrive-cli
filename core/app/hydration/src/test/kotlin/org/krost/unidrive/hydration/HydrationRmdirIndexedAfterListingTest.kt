package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.junit.experimental.categories.Category
import org.krost.unidrive.Capability
import org.krost.unidrive.CaseSensitivity
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.testing.Flaky
import org.krost.unidrive.testing.FlakyTests
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#717 over the IPC wire, on the mount engine: a client compares the folder's cloud contents with
 * the engine's listing and then sends the recursive rmdir. A cloud item the engine indexes between that listing and
 * its handling of the rmdir is one the comparison cannot have seen, so the rmdir refuses with `not_empty`,
 * `reason: remote_unobserved` and the item as `child`, instead of deleting it. A fresh listing of the folder lets
 * the same rmdir through: the client has now seen the item.
 */
class HydrationRmdirIndexedAfterListingTest {
    private class DeltaProvider : CloudProvider {
        override val id = "delta-fake"
        override val displayName = "Delta Fake"
        override var isAuthenticated = true
        override val deltaIsFullListing: Boolean get() = true
        override val caseSensitivity: CaseSensitivity get() = CaseSensitivity.SENSITIVE

        val files = LinkedHashMap<String, String>()
        val folders = LinkedHashSet<String>()
        var lastDeletedPath: String? = null

        private fun item(
            path: String,
            folder: Boolean,
        ) = CloudItem(
            id = path,
            name = path.substringAfterLast('/'),
            path = path,
            size = if (folder) 0 else files.getValue(path).toByteArray().size.toLong(),
            isFolder = folder,
            modified = Instant.parse("2026-01-01T12:00:00Z"),
            created = null,
            hash = null,
            mimeType = null,
            parentId = path.substringBeforeLast('/', "").ifEmpty { null },
        )

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override suspend fun authenticate() {}

        // The folder delete's snapshot guard asks this after the window guard has had its turn; an empty answer
        // passes it, so a refusal here can only come from the window guard.
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem =
            when {
                path in folders -> item(path, true)
                path in files -> item(path, false)
                else -> throw ProviderException("Item not found: $path")
            }

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not expected in this test")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not expected in this test")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            lastDeletedPath = remotePath
            // A folder delete takes its subtree with it, as every provider's does.
            files.keys.filter { it == remotePath || it.startsWith("$remotePath/") }.forEach { files.remove(it) }
            folders.removeAll { it == remotePath || it.startsWith("$remotePath/") }
        }

        // A delta that re-delivers the whole tree, as a provider whose cursor rewinds does: existing rows keep
        // their last sync, an item the mount has not indexed yet becomes a row written now.
        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(folders.map { item(it, true) } + files.keys.map { item(it, false) }, "c", hasMore = false, complete = true)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)

        override suspend fun createFolder(path: String): CloudItem = error("not expected in this test")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not expected in this test")
    }

    private class Env {
        val provider = DeltaProvider()
        val db = StateDatabase(dbPath = Files.createTempDirectory("unidrive-rmdir-window-db").resolve("state.db"), inMemory = true)
        val engine: SyncEngine
        val mount: MountEngine
        val handler: HydrationIpcHandler

        init {
            db.initialize()
            engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = Files.createTempDirectory("unidrive-rmdir-window-sync"),
                    cacheRoot = Files.createTempDirectory("unidrive-rmdir-window-cache"),
                )
            mount = MountEngine.over(engine)
            handler = HydrationIpcHandler(HydrationImpl(syncEngine = engine, stateDb = db))
        }
    }

    private suspend fun handle(
        env: Env,
        request: String,
    ): String = env.handler.handle("conn1", request).trim()

    @Test
    @Category(FlakyTests::class)
    @Flaky(
        issue = 739,
        reason = "the window guard's reported child is non-deterministic on Linux CI: a row indexed before the whole " +
            "listing satisfies the lastSynced window predicate under runner-load ordering and firstOrNull then " +
            "reports kept.txt instead of x4.txt (3 of the 4 Linux runs since #737 merged; the refusal still fires, " +
            "nothing is deleted)",
        firstFailure = "https://github.com/gkrost/unidrive-cli/actions/runs/43583576483",
    )
    fun `a recursive rmdir refuses a cloud item indexed after the folder was last listed whole`() = runTest {
        val env = Env()
        env.provider.folders += "/dir"
        env.provider.files["/dir/kept.txt"] = "kept bytes"
        env.mount.enumerateRemoteIntoState(reset = false)

        // The client's comparison is built on this listing.
        val listed = handle(env, """{"verb":"hydration.list","prefix":"/dir"}""")
        assertTrue(listed.contains("\"complete\":true"), "the folder is listed whole: $listed")

        // A cloud item arrives and the engine indexes it; the client has not listed again.
        env.provider.files["/dir/x4.txt"] = "new bytes"
        env.mount.enumerateRemoteIntoState(reset = false)

        val refused = handle(env, """{"verb":"hydration.rmdir","path":"/dir","recursive":true}""")
        assertEquals(
            """{"ok":false,"error":"not_empty","reason":"remote_unobserved","child":"/dir/x4.txt"}""",
            refused,
        )
        assertEquals(null, env.provider.lastDeletedPath, "nothing may be deleted in the cloud")
        assertEquals("kept bytes", env.provider.files["/dir/kept.txt"], "the tree stays")
        assertEquals("new bytes", env.provider.files["/dir/x4.txt"], "the just-indexed item stays")
    }

    @Test
    fun `a fresh listing of the folder lets the recursive rmdir through`() = runTest {
        val env = Env()
        env.provider.folders += "/dir"
        env.provider.files["/dir/kept.txt"] = "kept bytes"
        env.mount.enumerateRemoteIntoState(reset = false)
        assertTrue(handle(env, """{"verb":"hydration.list","prefix":"/dir"}""").contains("\"complete\":true"))

        env.provider.files["/dir/x4.txt"] = "new bytes"
        env.mount.enumerateRemoteIntoState(reset = false)
        assertTrue(
            handle(env, """{"verb":"hydration.rmdir","path":"/dir","recursive":true}""")
                .contains("\"error\":\"not_empty\""),
            "precondition: the indexed item refuses the delete",
        )

        // The client lists again, sees the item, and the delete goes through.
        assertTrue(handle(env, """{"verb":"hydration.list","prefix":"/dir"}""").contains("\"complete\":true"))
        assertEquals("""{"ok":true}""", handle(env, """{"verb":"hydration.rmdir","path":"/dir","recursive":true}"""))
        assertEquals("/dir", env.provider.lastDeletedPath)
        assertTrue(env.provider.files.isEmpty(), "the whole tree is gone in the cloud")
    }
}
