package org.krost.unidrive.engine

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#745 finding 6: one gather can deliver the same remote id more than once (Graph delta documents it and
 * says "use the last occurrence"; an offset-paged listing does it when an item moves mid-listing). The gather is keyed by
 * path, so each occurrence used to hold a key of its own: the item was both at its old and its new path, and the merge
 * (path order, not delivery order) could leave the row at the stale one. The last occurrence of an id is the only one
 * the gather keeps.
 */
class RemoteGatherRepeatedIdTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private val provider = OnePage()

    @BeforeTest
    fun setUp() {
        tmp = TestScratch.directory("unidrive-repeated-id")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun gathered(vararg items: CloudItem): Map<String, String> {
        provider.items = items.toList()
        val gather =
            RemoteGather(
                provider = provider,
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
                log = LoggerFactory.getLogger(RemoteGatherRepeatedIdTest::class.java),
            )
        return runBlocking { gather.gather() }.mapValues { (_, item) -> if (item.deleted) "-${item.id}" else item.id }
    }

    private fun item(
        id: String,
        path: String,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = 1,
        isFolder = false,
        modified = null,
        created = null,
        hash = null,
        mimeType = null,
    )

    @Test
    fun `an id delivered at two paths is kept at its last path only`() {
        val result = gathered(item("X", "/b/x"), item("X", "/a/x"))
        assertEquals(mapOf("/a/x" to "X"), result, "the item moved mid-gather: its earlier path is stale")
    }

    @Test
    fun `a live id followed by its pathless tombstone leaves no live entry`() {
        // An untracked id: the tombstone resolves to no row and is dropped, but it still ends the live occurrence.
        val result = gathered(item("X", "/n"), item("X", "").copy(deleted = true))
        assertEquals(emptyMap(), result, "the last occurrence is the deletion: no ghost entry for X")
    }

    @Test
    fun `an id that lost a collision and is delivered again elsewhere is no twin`() {
        // "A" < "B": A wins /p, B is recorded as its twin, then B is delivered at /q.
        val result = gathered(item("A", "/p"), item("B", "/p"), item("B", "/q"))
        assertEquals(mapOf("/p" to "A", "/q" to "B"), result, "B is at /q only, not also a decorated twin of /p")
    }

    @Test
    fun `an id that won a collision and moves away hands the path to the twin it displaced`() {
        val result = gathered(item("A", "/p"), item("B", "/p"), item("A", "/q"))
        assertEquals(mapOf("/p" to "B", "/q" to "A"), result, "A left /p, so B holds it undecorated")
    }

    @Test
    fun `guard - distinct ids at distinct paths are all kept`() {
        val result = gathered(item("X", "/x"), item("Y", "/y"), item("X", "/x"))
        assertEquals(mapOf("/x" to "X", "/y" to "Y"), result)
    }

    @Test
    fun `guard - id-less items are never collapsed`() {
        // Dropbox sends id-less tombstones keyed by path; two of them are two deletions.
        val result = gathered(item("", "/a").copy(deleted = true), item("", "/b").copy(deleted = true))
        assertEquals(mapOf("/a" to "-", "/b" to "-"), result)
    }

    private class OnePage : CloudProvider {
        var items: List<CloudItem> = emptyList()

        override val id = "test"
        override val displayName = "test"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(items = items, cursor = "c", hasMore = false, complete = true)

        override suspend fun listChildren(path: String): List<CloudItem> = error("not used")

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not used")

        override suspend fun quota(): QuotaInfo = error("not used")
    }
}
