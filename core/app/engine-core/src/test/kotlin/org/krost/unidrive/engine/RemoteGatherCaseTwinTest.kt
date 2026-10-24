package org.krost.unidrive.engine

import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * archive#1089: on a case-folding host two remote items that differ only in case collide in the gather. The winner must not
 * depend on which one the provider listed first: state.db tracks one of them, at the path of that one, and the lookup
 * has to look at both paths. The fold is a parameter of [RemoteGather], so the rule runs the same on any host.
 */
class RemoteGatherCaseTwinTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-case-twins")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun gather(fold: Boolean = true) =
        RemoteGather(
            provider = StubProvider(),
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
            log = LoggerFactory.getLogger(RemoteGatherCaseTwinTest::class.java),
            foldGatherKeys = fold,
        )

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

    private fun track(
        path: String,
        id: String,
    ) = db.upsertEntry(
        SyncEntry(
            path = path,
            remoteId = id,
            remoteHash = null,
            remoteSize = 1,
            remoteModified = null,
            localMtime = 1L,
            localSize = 1L,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
        ),
    )

    /** The items admitted in the given order; the key the winner holds and its id. */
    private fun admitInOrder(
        gather: RemoteGather,
        vararg items: CloudItem,
    ): Pair<String, String> {
        val changes = mutableMapOf<String, CloudItem>()
        val keyToPath = mutableMapOf<String, String>()
        val collisions = mutableMapOf<String, RemoteGather.CollisionRecord>()
        for (item in items) gather.admit(changes, keyToPath, collisions, item)
        val (key, winner) = changes.entries.single()
        return key to winner.id
    }

    private val readme = item("2", "/README")
    private val lowerCase = item("1", "/Readme")

    @Test
    fun `the tracked twin wins whichever case twin the provider lists first`() {
        track("/README", "2")
        val g = gather()

        val tracked = admitInOrder(g, readme, lowerCase)
        val reversed = admitInOrder(g, lowerCase, readme)

        // The new Readme has the smaller id: only the tracking decides, and it is the same in both orders.
        assertEquals("/README" to "2", tracked)
        assertEquals(tracked, reversed, "the winner must not depend on the emit order")
    }

    @Test
    fun `the tracked twin wins when the tracked row is the one with the other case`() {
        track("/Readme", "1")
        val g = gather()

        assertEquals("/Readme" to "1", admitInOrder(g, readme, lowerCase))
        assertEquals("/Readme" to "1", admitInOrder(g, lowerCase, readme))
    }

    @Test
    fun `untracked case twins resolve to the smallest id in either order`() {
        val g = gather()

        assertEquals("/Readme" to "1", admitInOrder(g, readme, lowerCase))
        assertEquals("/Readme" to "1", admitInOrder(g, lowerCase, readme))
    }

    @Test
    fun `a tracked item renamed in its case in the cloud still wins over a new twin`() {
        // state.db tracks id 2 at /README; the cloud now lists id 2 as /Readme and a new id 1 as /README.
        track("/README", "2")
        val g = gather()
        val renamed = item("2", "/Readme")
        val newcomer = item("1", "/README")

        val tracked = admitInOrder(g, renamed, newcomer)

        assertEquals("2", tracked.second, "the item state.db tracks keeps the name")
        assertEquals(tracked, admitInOrder(g, newcomer, renamed))
    }

    @Test
    fun `without case folding the twins are two names and nothing collides`() {
        track("/README", "2")
        val g = gather(fold = false)
        val changes = mutableMapOf<String, CloudItem>()
        val keyToPath = mutableMapOf<String, String>()
        val collisions = mutableMapOf<String, RemoteGather.CollisionRecord>()

        g.admit(changes, keyToPath, collisions, readme)
        g.admit(changes, keyToPath, collisions, lowerCase)

        assertEquals(setOf("/README", "/Readme"), changes.keys)
        assertEquals(emptyMap(), collisions)
    }

    private class StubProvider : CloudProvider {
        override val id = "test"
        override val displayName = "test"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

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

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = error("not used")

        override suspend fun quota(): QuotaInfo = error("not used")
    }
}
