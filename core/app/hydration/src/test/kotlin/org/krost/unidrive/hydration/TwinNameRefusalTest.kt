package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#24: a client that holds two spellings of one name (an NFC/NFD pair, U+00C5 next to U+212B) uploads
 * neither (the owner's decision of 2026-10-03), but until now the engine never heard of them: no row, so nothing in
 * `uploads`, `hydration.list` or the status said the files are not in the cloud. `hydration.refuse_name` records the refusal
 * as a local-only row blocked by name ([HydrationError.NAME_COLLIDES_TOKEN], the sibling's name), through the channel of the
 * drive's own name refusals; clearing it when the clash ends takes the row away again.
 */
class TwinNameRefusalTest {
    private lateinit var provider: MemProvider
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private lateinit var hydration: HydrationImpl

    private val composed = "/café.txt"

    @BeforeTest
    fun setUp() {
        provider = MemProvider()
        db = StateDatabase(dbPath = Files.createTempDirectory("ud-twin-db").resolve("state.db"), inMemory = true)
        db.initialize()
        engine = SyncEngine(provider = provider, db = db, syncRoot = Files.createTempDirectory("ud-twin-sync"), cacheRoot = Files.createTempDirectory("ud-twin-cache"))
        hydration =
            HydrationImpl(
                syncEngine = engine,
                stateDb = db,
                recoveryUploadScope = CoroutineScope(Dispatchers.IO),
                uploadRetryDelaysMs = listOf(10L, 10L),
                failedReplayDelayMs = 0L,
            )
    }

    @AfterTest
    fun tearDown() {
        runBlocking { hydration.shutdownUploads() }
        db.close()
    }

    private fun refuse(path: String, sibling: String? = "café.txt") = runBlocking { hydration.refuseName(path, sibling) }

    private fun listed(path: String): ListResult.Entry? =
        assertIs<ListResult.Ok>(runBlocking { hydration.list("/") }).entries.firstOrNull { it.path == path }

    @Test
    fun `a refused twin is a blocked row with the stable reason and the sibling, not a pending upload`() {
        assertEquals(RefuseNameResult.Ok, refuse(composed))

        val block = assertNotNull(db.nameBlock(composed))
        assertEquals("name_collides_after_normalisation", block.reason)
        assertEquals("café.txt", block.sibling)
        val health = hydration.uploadHealth()
        assertEquals(1, health.blocked.count, "the status names the file")
        assertEquals("name_collides_after_normalisation", health.blocked.items.single().reason)
        assertEquals(0, health.pending, "it waits for nothing: no upload will run while the clash exists")
        assertEquals("name_collides_after_normalisation", assertNotNull(listed(composed)).blocked, "hydration.list says why")
    }

    @Test
    fun `a refused twin survives a start and is never uploaded by the replay`() {
        refuse(composed)

        runBlocking { hydration.replayPendingUploads() }

        assertNotNull(db.nameBlock(composed), "a start does not drop the refusal (it has no cache file)")
        assertTrue(provider.remote.isEmpty(), "nothing went to the cloud")
    }

    @Test
    fun `refusing again is the same refusal`() {
        refuse(composed)
        assertEquals(RefuseNameResult.Ok, refuse(composed, sibling = "Café.txt"))

        assertEquals(1, hydration.uploadHealth().blocked.count)
        assertEquals("Café.txt", db.nameBlock(composed)?.sibling, "the newest sibling is named")
    }

    @Test
    fun `clearing the refusal takes the row away and the name can be created`() {
        refuse(composed)

        assertTrue(runBlocking { hydration.clearNameRefusal(composed) })

        assertNull(db.getEntry(composed))
        assertEquals(0, hydration.uploadHealth().blocked.count)
        assertIs<CreateResult.Ok>(runBlocking { hydration.create("c", "h1", composed) }, "the upload of the file can start now")
    }

    @Test
    fun `clearing a name that is not refused changes nothing`() {
        assertEquals(false, runBlocking { hydration.clearNameRefusal(composed) })
        provider.seed(composed, "cloud".toByteArray(), Instant.parse("2026-03-28T12:00:00Z"))
        db.upsertEntry(
            org.krost.unidrive.sync.model.SyncEntry(
                path = composed, remoteId = "id-1", remoteHash = "h", remoteSize = 5L, remoteModified = Instant.EPOCH,
                localMtime = null, localSize = null, isFolder = false, isPinned = false, isHydrated = false, lastSynced = Instant.EPOCH,
            ),
        )
        assertEquals(false, runBlocking { hydration.clearNameRefusal(composed) })
        assertNotNull(db.getEntry(composed), "a cloud row is never removed by a clear")
    }

    @Test
    fun `a row created before the twin arrived is marked, kept out of the pending count and of the replay, and released by the clear`() {
        // The first spelling was created (its bytes are in the cache) before the second one landed on disk.
        val created = assertIs<CreateResult.Ok>(runBlocking { hydration.create("c", "h1", composed) })
        Files.writeString(created.cachePath, "first spelling")

        assertEquals(RefuseNameResult.Ok, refuse(composed))

        assertEquals("name_collides_after_normalisation", db.nameBlock(composed)?.reason)
        assertEquals(0, hydration.uploadHealth().pending)
        runBlocking { hydration.replayPendingUploads() }
        assertTrue(provider.remote.isEmpty(), "the replay does not upload one spelling's bytes under the shared name")

        assertTrue(runBlocking { hydration.clearNameRefusal(composed) })
        assertNull(db.nameBlock(composed))
        assertNotNull(db.getEntry(composed), "the row holds the only copy of its bytes: it stays, waiting for its upload again")
        assertEquals(1, hydration.uploadHealth().pending)
    }

    @Test
    fun `a name the cloud already has is not marked`() {
        provider.seed(composed, "cloud".toByteArray())
        db.upsertEntry(
            org.krost.unidrive.sync.model.SyncEntry(
                path = composed, remoteId = "id-1", remoteHash = "h", remoteSize = 5L, remoteModified = Instant.EPOCH,
                localMtime = null, localSize = null, isFolder = false, isPinned = false, isHydrated = false, lastSynced = Instant.EPOCH,
            ),
        )

        assertEquals(RefuseNameResult.InCloud, refuse(composed))
        assertNull(db.nameBlock(composed), "a block on a cloud row would turn its delete and rename into local-only ones")
    }

    @Test
    fun `a folder is not refused by this verb`() {
        db.insertFolder("/café", remoteId = "id-f", mtime = Instant.EPOCH)

        assertEquals(RefuseNameResult.PathIsFolder, refuse("/café"))
        assertNull(db.nameBlock("/café"))
    }
}
