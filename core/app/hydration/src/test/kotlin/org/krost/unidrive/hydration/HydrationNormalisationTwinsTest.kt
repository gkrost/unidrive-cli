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
import org.krost.unidrive.engine.DuplicateNames
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #491 on the mount: two cloud items whose names differ only in Unicode normalisation (the golden suite v2's
 * `hazards/nfc-nfd-twins/caf{U+00E9}.txt` pair and `hazards/case-fold/{U+00C5}.txt` next to the ANGSTROM SIGN) list as two entries,
 * the member spelled in NFC under its name and the other under its decorated twin name, and every verb on either one
 * reaches that one at a drive that addresses its items by path: reading, uploading the cache copy, deleting.
 */
class HydrationNormalisationTwinsTest {
    private class PathAddressedProvider : CloudProvider {
        override val id = "path-addressed-fake"
        override val displayName = "Path Addressed Fake"
        override var isAuthenticated = true
        override val deltaIsFullListing: Boolean get() = true
        override val caseSensitivity: CaseSensitivity get() = CaseSensitivity.SENSITIVE

        val files = LinkedHashMap<String, String>()
        val folders = LinkedHashSet<String>()

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

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem =
            when (path) {
                in folders -> item(path, true)
                in files -> item(path, false)
                else -> throw ProviderException("Item not found: $path")
            }

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            val content = files[remotePath] ?: throw ProviderException("Item not found: $remotePath")
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
            files[remotePath] = Files.readString(localPath)
            return item(remotePath, false).copy(modified = Instant.now())
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            files.remove(remotePath)
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
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(folders.map { item(it, true) } + files.keys.map { item(it, false) }, "c", hasMore = false, complete = true)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private class Env {
        val provider = PathAddressedProvider()
        val db = StateDatabase(dbPath = Files.createTempDirectory("unidrive-nfctwin-db").resolve("state.db"), inMemory = true)
        val engine: SyncEngine
        val mount: MountEngine
        val handler: HydrationIpcHandler

        init {
            db.initialize()
            engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = Files.createTempDirectory("unidrive-nfctwin-sync"),
                    cacheRoot = Files.createTempDirectory("unidrive-nfctwin-cache"),
                )
            mount = MountEngine.over(engine)
            handler = HydrationIpcHandler(HydrationImpl(syncEngine = engine, stateDb = db))
            provider.folders += listOf("/hazards", TWINS, CASE_FOLD)
            for (name in NAMES) provider.files[name] = contentOf(name)
        }
    }

    // Where the mount lists [cloudPath]: under its NFC name when it holds it, else under its decorated twin name.
    private fun Env.mountPathOf(cloudPath: String): String {
        val nfc = java.text.Normalizer.normalize(cloudPath, java.text.Normalizer.Form.NFC)
        return if (db.getEntry(nfc)?.remoteId == cloudPath) nfc else DuplicateNames.decorate(nfc, cloudPath)
    }

    private suspend fun Env.read(path: String): String {
        val reply = handler.handle("conn1", """{"verb":"hydration.open_read","handle_id":"h-${path.hashCode()}","path":${json(path)}}""")
        assertTrue(reply.contains("\"ok\":true"), "open_read of ${esc(path)}: $reply")
        return Files.readString(engine.resolveCachePath(path))
    }

    @Test
    fun `the mount lists both members and reads each one's own bytes`() =
        runTest {
            val env = Env()
            env.mount.enumerateRemoteIntoState(reset = false)

            for (name in NAMES) {
                val path = env.mountPathOf(name)
                assertEquals(name, env.db.getEntry(path)?.remoteId, "${esc(name)} is listed at ${esc(path)}")
                assertEquals(contentOf(name), env.read(path), "reading ${esc(path)} gives the bytes of ${esc(name)}")
            }
            assertEquals(CAFE_NFC, env.db.getEntry(CAFE_NFC)?.remoteId, "the member spelled in NFC holds the name")
        }

    @Test
    fun `an upload of a twin's cache copy lands on that twin, the member at the name stays`() =
        runTest {
            val env = Env()
            env.mount.enumerateRemoteIntoState(reset = false)
            for (name in listOf(CAFE_NFD, ANGSTROM_SIGN)) {
                val path = env.mountPathOf(name)
                env.read(path)
                val cache = env.engine.resolveCachePath(path)
                Files.writeString(cache, "edited " + contentOf(name))

                env.mount.uploadFromCache(path, cache)

                assertEquals("edited " + contentOf(name), env.provider.files[name], "the upload of ${esc(path)} lands on ${esc(name)}")
            }
            assertEquals(contentOf(CAFE_NFC), env.provider.files[CAFE_NFC], "the NFC member is untouched")
            assertEquals(contentOf(A_RING), env.provider.files[A_RING], "the U+00C5 member is untouched")
            assertEquals(NAMES.size, env.provider.files.size, "no item appeared")
        }

    @Test
    fun `unlinking a twin deletes that twin, the member at the name stays`() =
        runTest {
            val env = Env()
            env.mount.enumerateRemoteIntoState(reset = false)

            for (name in listOf(CAFE_NFD, ANGSTROM_SIGN)) {
                val reply = env.handler.handle("conn1", """{"verb":"hydration.unlink","path":${json(env.mountPathOf(name))}}""")
                assertEquals("""{"ok":true}""", reply.trim())
            }

            assertEquals(setOf(CAFE_NFC, A_RING), env.provider.files.keys, "only the twins are gone")
        }

    private companion object {
        const val TWINS = "/hazards/nfc-nfd-twins"
        const val CASE_FOLD = "/hazards/case-fold"
        const val CAFE_NFC = "$TWINS/caf\u00E9.txt"
        const val CAFE_NFD = "$TWINS/cafe\u0301.txt"
        const val A_RING = "$CASE_FOLD/\u00C5.txt"
        const val ANGSTROM_SIGN = "$CASE_FOLD/\u212B.txt"
        val NAMES = listOf(CAFE_NFC, CAFE_NFD, A_RING, ANGSTROM_SIGN)

        fun contentOf(path: String): String = "bytes of " + esc(path)

        fun esc(s: String): String =
            buildString {
                s.codePoints().forEach { cp -> if (cp in 0x20..0x7E) appendCodePoint(cp) else append("{U+%04X}".format(cp)) }
            }

        // A JSON string with every non-ASCII character escaped, as a client may send it.
        fun json(s: String): String =
            buildString {
                append('"')
                for (c in s) if (c.code in 0x20..0x7E && c != '"' && c != '\\') append(c) else append("\\u%04x".format(c.code))
                append('"')
            }
    }
}
