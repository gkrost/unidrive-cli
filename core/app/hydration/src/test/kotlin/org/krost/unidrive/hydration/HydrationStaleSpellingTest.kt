package org.krost.unidrive.hydration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.krost.unidrive.Capability
import org.krost.unidrive.CaseSensitivity
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner decision (2026-11-26) on gkrost/unidrive-cli#948: on a drive that addresses its items by path (S3), once the NFC upload
 * of a name the cloud held in decomposed form has landed, the old decomposed object is deleted (to the provider's trash where
 * it has one), so only one spelling is left. A failed delete is logged and leaves the NFC file and its row as they are.
 * The row stops addressing the cloud item by the decomposed name before the upload starts, atomically: a row that changed
 * underneath (an upload in flight, an edit noted) is not adopted.
 */
class HydrationStaleSpellingTest {
    private val dir = "/hazards/nfc-nfd-twins"
    private val nfc = "$dir/café.txt"
    private val nfd = "$dir/café.txt"

    private fun json(s: String) = s.replace("\\", "\\\\")

    /** A drive whose item id is its key (S3): an upload to a key makes that key, a delete removes it. */
    private class KeyedProvider : CloudProvider {
        override val id = "keyed-fake"
        override val displayName = "Keyed Fake"
        override var isAuthenticated = true
        override val deltaIsFullListing: Boolean get() = true
        override val caseSensitivity: CaseSensitivity get() = CaseSensitivity.SENSITIVE

        val objects = ConcurrentHashMap<String, String>()
        val folders = LinkedHashSet<String>()

        @Volatile var failDelete = false

        private fun item(key: String) =
            CloudItem(
                id = key, name = key.substringAfterLast('/'), path = key, size = objects.getValue(key).toByteArray().size.toLong(),
                isFolder = false, modified = Instant.parse("2026-01-01T12:00:00Z"), created = null, hash = null, mimeType = null,
                parentId = key.substringBeforeLast('/', "").ifEmpty { null },
            )

        private fun folder(path: String) =
            CloudItem(
                id = path, name = path.substringAfterLast('/'), path = path, size = 0, isFolder = true,
                modified = Instant.parse("2026-01-01T12:00:00Z"), created = null, hash = null, mimeType = null,
                parentId = path.substringBeforeLast('/', "").ifEmpty { null },
            )

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem =
            if (path in folders) folder(path) else if (objects.containsKey(path)) item(path) else throw ProviderException("Item not found: $path")

        override suspend fun download(remotePath: String, destination: Path): Long {
            val content = objects[remotePath] ?: throw ProviderException("Item not found: $remotePath")
            Files.createDirectories(destination.parent)
            Files.writeString(destination, content)
            return content.toByteArray().size.toLong()
        }

        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, ifMatchETag: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem {
            objects[remotePath] = Files.readString(localPath)
            return item(remotePath).copy(modified = Instant.now())
        }

        override suspend fun delete(remotePath: String, ifMatchETag: String?) {
            if (failDelete) throw ProviderException("delete refused")
            objects.remove(remotePath) ?: throw ProviderException("Item not found: $remotePath")
        }

        override suspend fun createFolder(path: String): CloudItem = folder(path).also { folders += path }

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not expected in this test")

        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: ScanContext?): DeltaPage =
            DeltaPage(folders.map { folder(it) } + objects.keys.map { item(it) }, "c", hasMore = false, complete = true)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private class Env {
        val provider = KeyedProvider()
        val db = StateDatabase(dbPath = TestScratch.directory("unidrive-stale-db").resolve("state.db"), inMemory = true)
        val engine: SyncEngine
        val mount: MountEngine
        val hydration: HydrationImpl
        val store = StateDbNameSpellingStore(db)
        val handler: HydrationIpcHandler by lazy { HydrationIpcHandler(hydration, spellingsStore = store) } // after the record is made

        init {
            db.initialize()
            engine = SyncEngine(provider = provider, db = db, syncRoot = TestScratch.directory("unidrive-stale-sync"), cacheRoot = TestScratch.directory("unidrive-stale-cache"))
            mount = MountEngine.over(engine)
            hydration = HydrationImpl(syncEngine = engine, stateDb = db)
            provider.folders += listOf("/hazards", "/hazards/nfc-nfd-twins")
        }
    }

    private suspend fun eventually(what: String, condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            repeat(200) { if (condition()) return@withContext else delay(50) }
        }
        assertTrue(condition(), what)
    }

    // The cloud holds only the decomposed key; a client named it by that spelling first; then the NFC spelling writes.
    private suspend fun kotlinx.coroutines.test.TestScope.nfcWriteOverDecomposedKey(env: Env): String {
        env.provider.objects[nfd] = "old bytes"
        env.mount.enumerateRemoteIntoState(reset = false)
        assertEquals(nfd, env.db.getEntry(nfc)?.remotePath, "the row addresses the cloud item by its decomposed key")
        env.store.record(nfc, nfd)
        env.handler.start(backgroundScope) { _, _ -> true }
        backgroundScope.launch { env.hydration.events.collect { env.handler.dispatchEvent(it) } }
        val cache = env.engine.resolveCachePath(nfc)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "new bytes of the NFC file")
        return env.handler.handle("conn1", """{"verb":"hydration.open_write","path":"${nfc}","cache_path":"${json(cache.toString())}"}""")
    }

    @Test
    fun `an NFC open_write over an NFD S3 key leaves only the NFC key after it lands`() = runTest {
        val env = Env()

        val reply = nfcWriteOverDecomposedKey(env)

        assertTrue(reply.contains("\"ok\":true"), reply)
        eventually("the NFC key holds the new bytes") { env.provider.objects[nfc] == "new bytes of the NFC file" }
        eventually("the decomposed key is deleted once the NFC upload landed") { !env.provider.objects.containsKey(nfd) }
        assertEquals(setOf(nfc), env.provider.objects.keys.toSet())
        assertNull(env.db.getEntry(nfc)?.remotePath)
        assertEquals(nfc, env.db.getEntry(nfc)?.remoteId)
    }

    @Test
    fun `a failed delete of the old NFD key leaves the NFC file intact and is logged`() = runTest {
        val env = Env()
        env.provider.failDelete = true
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            val reply = nfcWriteOverDecomposedKey(env)

            assertTrue(reply.contains("\"ok\":true"), reply)
            eventually("the NFC upload landed") { env.provider.objects[nfc] == "new bytes of the NFC file" }
            eventually("the failed delete is logged") { appender.list.any { it.level == Level.WARN && it.formattedMessage.contains("#948") } }
            assertTrue(env.provider.objects.containsKey(nfd), "the old key is left where it is")
            assertEquals("new bytes of the NFC file", env.provider.objects[nfc], "the NFC file is intact")
            assertNull(env.db.getEntry(nfc)?.remotePath)
            assertEquals(nfc, env.db.getEntry(nfc)?.remoteId, "the row stays consistent with the NFC object")
        } finally {
            root.detachAppender(appender)
        }
    }

    @Test
    fun `adopting the NFC spelling backs off when the row changed underneath`() = runTest {
        val env = Env()
        val now = Instant.now()
        fun row(pending: Boolean) =
            SyncEntry(
                path = nfc, remotePath = nfd, remoteId = nfd, remoteHash = "h", remoteSize = 5, remoteModified = now,
                localMtime = now.toEpochMilli(), localSize = 5, isFolder = false, isPinned = false, isHydrated = true, lastSynced = now,
                editBase = if (pending) "base" else null,
            )
        env.db.upsertEntry(row(pending = true))

        assertEquals(Adoption.Changed, env.store.adoptNfc(nfc), "an unsynced edit is noted on the row: not adopted")
        assertEquals(nfd, env.db.getEntry(nfc)?.remotePath, "the row is as it was")

        env.db.upsertEntry(row(pending = false))
        assertEquals(Adoption.Dropped(StaleSpelling(nfd, nfd)), env.store.adoptNfc(nfc))
        assertNull(env.db.getEntry(nfc)?.remotePath)
    }
}
