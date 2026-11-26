package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
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
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#948 (wanted 1): the golden-unicode-v2 smoke listed rows under their NFC path that could not be read, for
 * names the cloud spells another way (a stacked-combining-marks name with no twin, the decomposed Hangul and katakana names).
 * The row key is NFC (archive#171); the cloud item keeps its own spelling. A read resolves the row by the remote id first
 * and, where the drive addresses its items by path, by the provider's own spelling the row keeps: never by the NFC key.
 * Two providers, one per way a drive addresses an item.
 */
class HydrationListedRowSpellingTest {
    /** One cloud item: [spelling] is the path as the provider spells it. */
    private class Item(val id: String, val spelling: String, var content: String)

    private class Provider(private val byId: Boolean) : CloudProvider {
        override val id = "spelling-fake"
        override val displayName = "Spelling Fake"
        override var isAuthenticated = true
        override val deltaIsFullListing: Boolean get() = true
        override val caseSensitivity: CaseSensitivity get() = CaseSensitivity.SENSITIVE

        val items = ArrayList<Item>()
        val folders = LinkedHashSet<String>()
        val downloads = ArrayList<String>()

        private fun asCloud(item: Item) =
            CloudItem(
                id = item.id,
                name = item.spelling.substringAfterLast('/'),
                path = item.spelling,
                size = item.content.toByteArray().size.toLong(),
                isFolder = false,
                modified = Instant.parse("2026-01-01T12:00:00Z"),
                created = null,
                hash = null,
                mimeType = null,
                parentId = item.spelling.substringBeforeLast('/', "").ifEmpty { null },
            )

        private fun folder(path: String) =
            CloudItem(
                id = path,
                name = path.substringAfterLast('/'),
                path = path,
                size = 0,
                isFolder = true,
                modified = Instant.parse("2026-01-01T12:00:00Z"),
                created = null,
                hash = null,
                mimeType = null,
                parentId = path.substringBeforeLast('/', "").ifEmpty { null },
            )

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem =
            if (path in folders) folder(path) else items.firstOrNull { it.spelling == path }?.let { asCloud(it) } ?: throw ProviderException("Item not found: $path")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            downloads += remotePath
            val item = items.firstOrNull { it.spelling == remotePath } ?: throw ProviderException("Item not found: $remotePath")
            Files.createDirectories(destination.parent)
            Files.writeString(destination, item.content)
            return item.content.toByteArray().size.toLong()
        }

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long {
            if (!byId) return download(remotePath, destination)
            downloads += "id:$remoteId"
            val item = items.firstOrNull { it.id == remoteId } ?: throw ProviderException("Item not found: $remoteId")
            Files.createDirectories(destination.parent)
            Files.writeString(destination, item.content)
            return item.content.toByteArray().size.toLong()
        }

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
        ) = error("not expected in this test")

        override suspend fun createFolder(path: String): CloudItem = folder(path).also { folders += path }

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not expected in this test")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(folders.map { folder(it) } + items.map { asCloud(it) }, "c", hasMore = false, complete = true)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private class Env(byId: Boolean) {
        val provider = Provider(byId)
        val db = StateDatabase(dbPath = TestScratch.directory("unidrive-spelling-db").resolve("state.db"), inMemory = true)
        val engine: SyncEngine
        val mount: MountEngine
        val hydration: HydrationImpl

        init {
            db.initialize()
            engine = SyncEngine(provider = provider, db = db, syncRoot = TestScratch.directory("unidrive-spelling-sync"), cacheRoot = TestScratch.directory("unidrive-spelling-cache"))
            mount = MountEngine.over(engine)
            hydration = HydrationImpl(syncEngine = engine, stateDb = db)
            provider.folders += listOf("/hazards", DIR)
        }

        fun cloud(
            spelling: String,
            content: String,
        ): String {
            provider.items += Item("id-${provider.items.size}", spelling, content)
            return content
        }

        suspend fun listed() = mount.enumerateRemoteIntoState(reset = false)

        suspend fun read(path: String): String {
            val result = hydration.openForRead("conn1", "h-${path.hashCode()}", path)
            assertTrue(result is OpenResult.Ok, "open_read of ${esc(path)}: $result")
            return Files.readString(result.cachePath)
        }
    }

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    // The shapes of the four files #948 names. Each is a name the cloud spells apart from its NFC row key.
    private val stackedMarks = "$DIR/á̂̃̄̅.txt".let { nfd -> Normalizer.normalize(nfd, Normalizer.Form.NFD) }
    private val hangulJamo = "$DIR/" + Normalizer.normalize("가각갂.txt", Normalizer.Form.NFD)
    private val katakanaDakuten = "$DIR/" + Normalizer.normalize("ガギグゲゴ.txt", Normalizer.Form.NFD)
    private val cafeNfd = "$DIR/café.txt"
    private val cafeNfc = "$DIR/café.txt"

    private suspend fun assertLoneNameReadable(
        byId: Boolean,
        spelling: String,
    ) {
        val env = Env(byId)
        env.cloud(spelling, "bytes of the lone name")
        env.listed()
        val path = nfc(spelling)
        assertTrue(spelling != path, "the shape is a name that is not in NFC")
        assertEquals("bytes of the lone name", env.read(path), "the row at the NFC path reads the file (byId=$byId)")
        assertEquals(spelling, env.db.getEntry(path)?.let { it.remotePath ?: it.path }, "the row keeps the provider's own spelling")
    }

    @Test
    fun `a row kept under its NFC path stays readable when the provider item is spelled NFD`() =
        runTest {
            assertLoneNameReadable(byId = false, spelling = cafeNfd)
            assertLoneNameReadable(byId = true, spelling = cafeNfd)
        }

    @Test
    fun `a lone name with stacked combining marks stays readable under its NFC row path`() =
        runTest {
            assertLoneNameReadable(byId = false, spelling = stackedMarks)
            assertLoneNameReadable(byId = true, spelling = stackedMarks)
        }

    @Test
    fun `a decomposed Hangul and a decomposed katakana name stay readable under their NFC row path`() =
        runTest {
            for (spelling in listOf(hangulJamo, katakanaDakuten)) {
                assertLoneNameReadable(byId = false, spelling = spelling)
                assertLoneNameReadable(byId = true, spelling = spelling)
            }
        }

    @Test
    fun `the NFC member of a twin pair and its decorated twin each read their own bytes`() =
        runTest {
            for (byId in listOf(false, true)) {
                val env = Env(byId)
                env.cloud(cafeNfc, "composed")
                env.cloud(cafeNfd, "decomposed")
                env.listed()
                assertEquals("composed", env.read(cafeNfc))
                val twin = env.db.getAllEntries().single { it.remoteId != env.db.getEntry(cafeNfc)?.remoteId && !it.isFolder }
                assertEquals("decomposed", env.read(twin.path))
            }
        }

    @Test
    fun `the NFD member of a twin pair holds the row at the NFC path and reads its own bytes`() =
        runTest {
            for (byId in listOf(false, true)) {
                val env = Env(byId)
                env.cloud(cafeNfd, "decomposed")
                env.cloud(cafeNfc, "composed")
                env.listed()
                assertEquals("composed", env.read(cafeNfc), "the listing order does not move the name")
            }
        }

    private companion object {
        const val DIR = "/hazards/nfc-nfd-twins"

        fun esc(s: String): String =
            buildString {
                s.codePoints().forEach { cp -> if (cp in 0x20..0x7E) appendCodePoint(cp) else append("{U+%04X}".format(cp)) }
            }
    }
}
