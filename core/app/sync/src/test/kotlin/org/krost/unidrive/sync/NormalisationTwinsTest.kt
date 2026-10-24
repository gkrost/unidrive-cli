package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CaseSensitivity
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.engine.DuplicateNames
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * archive#491, download direction: two cloud items whose names differ only in Unicode normalisation (an NFC/NFD pair, the
 * ANGSTROM SIGN U+212B next to U+00C5) are two items. state.db keys paths in NFC (archive#171), so the pair meets at one key;
 * the gather keeps the member spelled in NFC at the name and the other one under its decorated twin name (archive#403). What
 * the engine then does to either one reaches that one and never the other, also on a drive that addresses its items by
 * path only (localfs, S3: no by-id delete, the id is the path). A name NFKC folds and NFC does not (the ligature) is no
 * twin at all.
 *
 * The names are the golden unicode suite v2's (scripts/golden/unicode-tree/layout.json, hazards/nfc-nfd-twins,
 * hazards/case-fold, hazards/nfkc-only). Every member's bytes name the member, so a file that holds its twin's bytes
 * shows.
 */
class NormalisationTwinsTest {
    private lateinit var syncRoot: Path
    private lateinit var dbDir: Path
    private lateinit var cacheRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: PathAddressedProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-nfctwin-root")
        dbDir = Files.createTempDirectory("ud-nfctwin-db")
        cacheRoot = Files.createTempDirectory("ud-nfctwin-cache")
        db = StateDatabase(dbDir.resolve("state.db"))
        db.initialize()
        provider = PathAddressedProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            cacheRoot = cacheRoot,
            cacheKey = "nfc-twins-test",
        )

    private fun seedTwins() {
        provider.folder("/hazards")
        provider.folder(TWINS)
        provider.folder(CASE_FOLD)
        provider.folder(NFKC)
        for (name in TWIN_NAMES + CASE_FOLD_NAMES + NFKC_NAMES) provider.put(name, contentOf(name))
    }

    private fun localBytes(path: String): String = Files.readString(syncRoot.resolve(path.removePrefix("/")))

    // The local path the engine keeps [cloudPath] under: its NFC name, or its decorated twin name.
    private fun localPathOf(cloudPath: String): String {
        val nfc = PathNormalizer.nfc(cloudPath)
        val holder = db.getEntry(nfc)
        if (holder?.remoteId == cloudPath) return nfc
        return DuplicateNames.decorate(nfc, cloudPath)
    }

    @Test
    fun `every twin is a row of its own whose local file holds its own bytes`() =
        runTest {
            seedTwins()
            engine().syncOnce()

            for (name in TWIN_NAMES + CASE_FOLD_NAMES + NFKC_NAMES) {
                val local = localPathOf(name)
                val row = db.getEntry(local)
                assertNotNull(row, "a row for ${esc(name)} at ${esc(local)}")
                assertEquals(name, row.remoteId, "the row at ${esc(local)} is the item ${esc(name)}")
                assertEquals(contentOf(name), localBytes(local), "the local copy of ${esc(name)} holds its own bytes")
            }
            val files = db.getAllEntries().filter { !it.isFolder }
            assertEquals(TWIN_NAMES.size + CASE_FOLD_NAMES.size + NFKC_NAMES.size, files.size, "one row per cloud item")
            assertEquals(files.size, files.mapNotNull { it.remoteId }.toSet().size, "never one row for two cloud items")
        }

    @Test
    fun `the member spelled in NFC keeps the name`() =
        runTest {
            seedTwins()
            engine().syncOnce()

            for (name in listOf(CAFE_NFC, ANGSTROM_NFC, HANGUL_NFC, KATAKANA_NFC, A_RING)) {
                assertEquals(name, db.getEntry(name)?.remoteId, "${esc(name)} holds its own name")
            }
        }

    @Test
    fun `an edit of a decorated twin reaches that twin and never the member at the name`() =
        runTest {
            seedTwins()
            engine().syncOnce()

            val edits = listOf(CAFE_NFD, ANGSTROM_NFD, ANGSTROM_SIGN, HANGUL_NFD, KATAKANA_NFD, ANGSTROM_SIGN_ALONE)
            for (name in edits) {
                val local = syncRoot.resolve(localPathOf(name).removePrefix("/"))
                Files.writeString(local, "edited " + contentOf(name))
                Files.setLastModifiedTime(local, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000))
            }
            engine().syncOnce()

            for (name in edits) {
                assertEquals("edited " + contentOf(name), provider.contentAt(name), "the edit of ${esc(name)} lands on ${esc(name)}")
            }
            for (name in listOf(CAFE_NFC, ANGSTROM_NFC, HANGUL_NFC, KATAKANA_NFC, A_RING)) {
                assertEquals(contentOf(name), provider.contentAt(name), "${esc(name)} is untouched by its twin's edit")
            }
            assertEquals(TWIN_NAMES.size + CASE_FOLD_NAMES.size + NFKC_NAMES.size, provider.fileCount(), "no item appeared")
        }

    @Test
    fun `deleting a decorated twin deletes that twin and never the member at the name`() =
        runTest {
            seedTwins()
            engine().syncOnce()

            Files.delete(syncRoot.resolve(localPathOf(CAFE_NFD).removePrefix("/")))
            Files.delete(syncRoot.resolve(localPathOf(ANGSTROM_SIGN_ALONE).removePrefix("/")))
            engine().syncOnce()

            assertEquals(null, provider.contentAt(CAFE_NFD), "the NFD member is gone")
            assertEquals(null, provider.contentAt(ANGSTROM_SIGN_ALONE), "the angstrom-sign member is gone")
            assertEquals(contentOf(CAFE_NFC), provider.contentAt(CAFE_NFC), "the NFC member stays")
            assertEquals(contentOf(A_RING), provider.contentAt(A_RING), "the U+00C5 member stays")
            assertEquals(contentOf(CAFE_NFC), localBytes(CAFE_NFC))
        }

    @Test
    fun `a second pass changes nothing`() =
        runTest {
            seedTwins()
            engine().syncOnce()
            val rows = db.getAllEntries().associate { it.path to it.remoteId }
            val uploadsBefore = provider.uploads.size

            engine().syncOnce()

            assertEquals(rows, db.getAllEntries().associate { it.path to it.remoteId })
            assertEquals(uploadsBefore, provider.uploads.size, "nothing is uploaded on a second pass")
            assertTrue(provider.deleted.isEmpty(), "nothing is deleted: ${provider.deleted.map(::esc)}")
        }

    @Test
    fun `a tracked member spelled apart keeps the name and its own spelling when its NFC twin appears later`() =
        runTest {
            provider.folder("/hazards")
            provider.folder(TWINS)
            provider.put(CAFE_NFD, contentOf(CAFE_NFD))
            engine().syncOnce()
            assertEquals(contentOf(CAFE_NFD), localBytes(CAFE_NFC), "a lone decomposed name is downloaded under its NFC key")

            provider.put(CAFE_NFC, contentOf(CAFE_NFC))
            engine().syncOnce()
            assertEquals(CAFE_NFD, db.getEntry(CAFE_NFC)?.remoteId, "the tracked member keeps the name")
            assertEquals(contentOf(CAFE_NFC), localBytes(localPathOf(CAFE_NFC)), "the newcomer is kept under its decorated name")

            for (name in listOf(CAFE_NFD, CAFE_NFC)) {
                val local = syncRoot.resolve(localPathOf(name).removePrefix("/"))
                Files.writeString(local, "edited " + contentOf(name))
                Files.setLastModifiedTime(local, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000))
            }
            engine().syncOnce()

            for (name in listOf(CAFE_NFD, CAFE_NFC)) {
                assertEquals("edited " + contentOf(name), provider.contentAt(name), "the edit of ${esc(name)} lands on ${esc(name)}")
            }
            assertEquals(2, provider.fileCount(), "no item appeared")
        }

    @Test
    fun `the ligature and its NFKC twin are two names, no collision`() =
        runTest {
            seedTwins()
            engine().syncOnce()

            for (name in NFKC_NAMES) assertEquals(name, db.getEntry(name)?.remoteId, "${esc(name)} keeps its own name")
            assertTrue(db.getSyncState("remote_collision_paths").orEmpty().split('\t').none { it.startsWith(NFKC) })
        }

    /**
     * A drive that addresses its items by path only and gives an item its path as its id, like localfs: no by-id
     * download, delete or move. It tells names apart by code points (NTFS and ext4 both hold an NFC/NFD pair).
     */
    private class PathAddressedProvider : CloudProvider {
        override val id = "path-addressed-fake"
        override val displayName = "Path Addressed Fake"
        override var isAuthenticated = true
        override val deltaIsFullListing: Boolean get() = true
        override val caseSensitivity: CaseSensitivity get() = CaseSensitivity.SENSITIVE

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        private val files = LinkedHashMap<String, String>()
        private val folders = LinkedHashSet<String>()
        val uploads = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        fun folder(path: String) {
            folders += path
        }

        fun put(
            path: String,
            content: String,
        ) {
            files[path] = content
        }

        fun contentAt(path: String): String? = files[path]

        fun fileCount(): Int = files.size

        private fun item(
            path: String,
            folder: Boolean,
        ) = CloudItem(
            id = path,
            name = path.substringAfterLast('/'),
            path = path,
            size = if (folder) 0 else files.getValue(path).toByteArray().size.toLong(),
            isFolder = folder,
            modified = MTIME,
            created = MTIME,
            hash = null,
            mimeType = null,
            parentId = path.substringBeforeLast('/', "").ifEmpty { null },
        )

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String): CloudItem =
            if (path in folders) item(path, true) else item(files.keys.first { it == path }, false)

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            val content = files[remotePath] ?: throw org.krost.unidrive.ProviderException("Item not found: $remotePath")
            Files.createDirectories(destination.parent)
            Files.writeString(destination, content)
            return content.toByteArray().size.toLong()
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploads += remotePath
            files[remotePath] = Files.readString(localPath)
            return item(remotePath, false).copy(modified = Instant.now())
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            deleted += remotePath
            files.remove(remotePath)
            folders.remove(remotePath)
        }

        override suspend fun createFolder(path: String): CloudItem {
            folders += path
            return item(path, true)
        }

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not expected in this test")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage {
            val items = folders.map { item(it, true) } + files.keys.map { item(it, false) }
            return DeltaPage(items = items, cursor = "c-${System.nanoTime()}", hasMore = false, complete = true)
        }

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0, used = 0, remaining = 0)
    }

    private companion object {
        val MTIME: Instant = Instant.parse("2026-01-01T12:00:00Z")

        const val TWINS = "/hazards/nfc-nfd-twins"
        const val CASE_FOLD = "/hazards/case-fold"
        const val NFKC = "/hazards/nfkc-only"

        const val CAFE_NFC = "$TWINS/caf\u00E9.txt"
        const val CAFE_NFD = "$TWINS/cafe\u0301.txt"
        const val ANGSTROM_NFC = "$TWINS/\u00C5ngstr\u00F6m.txt"
        const val ANGSTROM_NFD = "$TWINS/A\u030Angstro\u0308m.txt"
        const val ANGSTROM_SIGN = "$TWINS/\u212Bngstr\u00F6m.txt"
        const val HANGUL_NFC = "$TWINS/\uD55C\uAE00.txt"
        const val HANGUL_NFD = "$TWINS/\u1112\u1161\u11AB\u1100\u1173\u11AF.txt"
        const val KATAKANA_NFC = "$TWINS/\u30AC\u30AE\u30B0\u30B2\u30B4.txt"
        const val KATAKANA_NFD = "$TWINS/\u30AB\u3099\u30AD\u3099\u30AF\u3099\u30B1\u3099\u30B3\u3099.txt"
        const val A_RING = "$CASE_FOLD/\u00C5.txt"
        const val ANGSTROM_SIGN_ALONE = "$CASE_FOLD/\u212B.txt"

        val TWIN_NAMES =
            listOf(CAFE_NFC, CAFE_NFD, ANGSTROM_NFC, ANGSTROM_NFD, ANGSTROM_SIGN, HANGUL_NFC, HANGUL_NFD, KATAKANA_NFC, KATAKANA_NFD)
        val CASE_FOLD_NAMES = listOf(A_RING, ANGSTROM_SIGN_ALONE)
        val NFKC_NAMES = listOf("$NFKC/\uFB01les.txt", "$NFKC/files.txt")

        fun contentOf(path: String): String = "bytes of " + esc(path)

        // {U+XXXX} for every non-ASCII character, as the golden tools print names.
        fun esc(s: String): String =
            buildString {
                s.codePoints().forEach { cp -> if (cp in 0x20..0x7E) appendCodePoint(cp) else append("{U+%04X}".format(cp)) }
            }
    }
}
