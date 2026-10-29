package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#171: a `hydration.list` file entry carries `content_id`, the name the provider gives the bytes of the
 * version the row records (SyncEntry.remoteContentId; Internxt: `<fileId>|<size>`). A rename stamps a new modified time and so
 * a new etag on Internxt; a client that compared those read the rename as new bytes and dropped its local copy. The id stays
 * through a rename and changes with new bytes. The key is absent where the row names none, and on a folder.
 */
class HydrationListContentIdTest {
    private val base: Path = Files.createTempDirectory("ud-list-content-id")
    private val db = StateDatabase(base.resolve("state.db")).also { it.initialize() }

    @AfterTest
    fun close() {
        db.close()
        base.toFile().deleteRecursively()
    }

    /**
     * A drive that names a file's bytes apart from its write token, like Internxt: the token is `<id>|<modified>|<size>`, a
     * move stamps a new modified time (a new token) and keeps the stored object, an upload stores a new object. An empty
     * file has no stored object and is named `|0`.
     */
    private class ObjectNamingDrive : CloudProvider {
        override val id = "fake-object-naming"
        override val displayName = "Fake (names stored objects)"
        override var isAuthenticated = true

        val items = linkedMapOf<String, CloudItem>()
        private val content = mutableMapOf<String, ByteArray>()
        private var clock = Instant.parse("2026-10-28T14:55:00Z")
        private var objects = 0
        private var ids = 0

        // An upload the server stamps with the old version's modified time: the token stays, the stored object is another.
        var uploadKeepsToken = false

        private fun tick(): Instant = clock.plusSeconds(60).also { clock = it }

        private fun file(
            id: String,
            path: String,
            size: Long,
            modified: Instant,
            storedObject: String?,
        ) = CloudItem(
            id = id,
            name = path.substringAfterLast('/'),
            path = path,
            size = size,
            isFolder = false,
            modified = modified,
            created = null,
            hash = "$id|$modified|$size",
            mimeType = null,
            contentId = if (storedObject != null) "$storedObject|$size" else "|0".takeIf { size == 0L },
        )

        /** A file in the cloud; [storedObject] false is an answer that names no object for a file of some bytes. */
        fun seed(
            path: String,
            bytes: ByteArray,
            storedObject: Boolean = bytes.isNotEmpty(),
        ): CloudItem {
            val id = "u-${++ids}"
            content[id] = bytes
            return file(id, path, bytes.size.toLong(), tick(), if (storedObject) "obj-${++objects}" else null).also { items[path] = it }
        }

        fun seedFolder(path: String) {
            items[path] = folder(path)
        }

        private fun folder(path: String) =
            CloudItem(
                id = "d-$path",
                name = path.substringAfterLast('/'),
                path = path,
                size = 0,
                isFolder = true,
                modified = clock,
                created = null,
                hash = null,
                mimeType = null,
            )

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = items.values.filter { it.path.substringBeforeLast('/') == path.trimEnd('/') }

        override suspend fun getMetadata(path: String): CloudItem = items[path] ?: throw ProviderException("Item not found: $path")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = downloadById(getMetadata(remotePath).id, remotePath, destination)

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long {
            val bytes = content.getValue(remoteId)
            Files.createDirectories(destination.parent)
            Files.write(destination, bytes)
            return bytes.size.toLong()
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            val bytes = Files.readAllBytes(localPath)
            val prior = items[remotePath]
            val id = existingRemoteId ?: prior?.id ?: "u-${++ids}"
            content[id] = bytes
            val modified = prior?.modified?.takeIf { uploadKeepsToken } ?: tick()
            return file(id, remotePath, bytes.size.toLong(), modified, "obj-${++objects}".takeIf { bytes.isNotEmpty() }).also { items[remotePath] = it }
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            items.remove(remotePath)
        }

        override suspend fun createFolder(path: String): CloudItem = folder(path).also { items[path] = it }

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem {
            val item = items.remove(fromPath) ?: throw ProviderException("Item not found: $fromPath")
            val modified = tick()
            val moved = item.copy(path = toPath, name = toPath.substringAfterLast('/'), modified = modified, hash = "${item.id}|$modified|${item.size}")
            items[toPath] = moved
            return moved
        }

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = DeltaPage(items = items.values.toList(), cursor = "cursor", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    /** A mount of [provider], spoken to over the IPC handler, as a client does. */
    private inner class Mount(
        provider: CloudProvider,
    ) {
        private val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createDirectories(base.resolve("root")),
                cacheRoot = base.resolve("cache"),
                cacheKey = "profile",
            )
        private val hydration = HydrationImpl(engine, db)
        private val ipc = HydrationIpcHandler(hydration)

        /** A scan: the first one, or the refresh a later one is. */
        suspend fun scan() {
            MountEngine.over(engine).enumerateRemoteIntoState(false)
        }

        suspend fun call(request: String): String = ipc.handle("client", request)

        suspend fun list(prefix: String = "/"): String =
            call("""{"verb":"hydration.list","prefix":"$prefix"}""").also { assertTrue(it.startsWith("""{"ok":true,"entries":"""), it) }

        suspend fun entry(path: String): String = entryOf(list(path.substringBeforeLast('/').ifEmpty { "/" }), path)

        suspend fun hydrate(path: String) = assertEquals("""{"ok":true}""", call("""{"verb":"hydration.hydrate","path":"$path"}"""))

        suspend fun rename(
            from: String,
            to: String,
        ) = assertEquals("""{"ok":true}""", call("""{"verb":"hydration.rename","old_path":"$from","new_path":"$to"}"""))

        /** A save through the mount: new bytes in the cache copy, open_write, and the upload's completed event. */
        suspend fun write(
            path: String,
            bytes: ByteArray,
        ) = coroutineScope {
            val handle = "h-$path"
            val done = async(start = CoroutineStart.UNDISPATCHED) { hydration.events.filterIsInstance<HydrationEvent.Completed>().first { it.handleId == handle } }
            val cache = engine.resolveCachePath(path)
            Files.createDirectories(cache.parent)
            Files.write(cache, bytes)
            assertIs<OpenResult.Ok>(hydration.openForWrite("client", handle, path, cache))
            assertTrue(withTimeout(TIMEOUT_MS) { done.await() }.ok, "the upload of $path landed")
        }
    }

    // The entry of [path] in a hydration.list reply, as the text of its JSON object (no value of these tests holds a brace).
    private fun entryOf(
        reply: String,
        path: String,
    ): String {
        val start = reply.indexOf("""{"path":"$path",""")
        assertTrue(start >= 0, "no entry for $path in $reply")
        return reply.substring(start, reply.indexOf('}', start) + 1)
    }

    // The raw JSON value of the member [name] of an entry, or null when the entry has no such member.
    private fun String.field(name: String): String? = Regex(""""$name":("(?:[^"\\]|\\.)*"|[^,}]*)""").find(this)?.groupValues?.get(1)

    @Test
    fun `a renamed file lists the same content_id while its etag and modified time change, and a refresh keeps it`() =
        runBlocking {
            val drive = ObjectNamingDrive()
            drive.seed("/a.txt", "hello".toByteArray())
            val mount = Mount(drive)
            mount.scan()
            mount.hydrate("/a.txt")
            val before = mount.entry("/a.txt")
            assertEquals("\"obj-1|5\"", before.field("content_id"), "after the first scan: $before")
            assertEquals("true", before.field("hydrated"))

            mount.rename("/a.txt", "/b.txt")

            val after = mount.entry("/b.txt")
            assertEquals(before.field("content_id"), after.field("content_id"), "a rename keeps the name of the bytes: $after")
            assertNotEquals(before.field("etag"), after.field("etag"), "the token moved with the rename")
            assertNotEquals(before.field("remote_modified_ms"), after.field("remote_modified_ms"), "the server stamped a new time")
            assertEquals("true", after.field("hydrated"))

            mount.scan()

            val refreshed = mount.entry("/b.txt")
            assertEquals(before.field("content_id"), refreshed.field("content_id"), "after a refresh: $refreshed")
            assertEquals(after.field("etag"), refreshed.field("etag"))
            assertEquals("true", refreshed.field("hydrated"), "a rename changes no byte")
        }

    @Test
    fun `a re-upload lists another content_id, also where the token stays`() =
        runBlocking {
            val drive = ObjectNamingDrive().apply { uploadKeepsToken = true }
            drive.seed("/a.txt", "hello".toByteArray())
            val mount = Mount(drive)
            mount.scan()
            mount.hydrate("/a.txt")
            val before = mount.entry("/a.txt")

            mount.write("/a.txt", "world".toByteArray())

            val after = mount.entry("/a.txt")
            assertEquals(before.field("etag"), after.field("etag"), "the server kept the token: $after")
            assertEquals("\"obj-2|5\"", after.field("content_id"), "the upload stored another object")
        }

    @Test
    fun `an empty file lists the size alone, a file whose answer names no object lists none, and a folder never lists one`() =
        runBlocking {
            val drive = ObjectNamingDrive()
            drive.seed("/empty.txt", ByteArray(0))
            drive.seed("/unnamed.bin", "abc".toByteArray(), storedObject = false)
            drive.seedFolder("/docs")
            val mount = Mount(drive)
            mount.scan()

            val reply = mount.list()

            assertEquals("\"|0\"", entryOf(reply, "/empty.txt").field("content_id"))
            assertNull(entryOf(reply, "/unnamed.bin").field("content_id"), reply)
            assertNotNull(entryOf(reply, "/unnamed.bin").field("etag"), "the etag is there as always")
            assertNull(entryOf(reply, "/docs").field("content_id"), reply)
            // Whatever a folder's row holds, its entry has no bytes to name.
            db.upsertEntry(assertNotNull(db.getEntry("/docs")).copy(remoteContentId = "obj-9|0"))
            assertNull(mount.entry("/docs").field("content_id"))
        }

    @Test
    fun `a provider that names no content lists no content_id at all`() =
        runBlocking {
            val provider = MemProvider()
            provider.seedFolder("/docs")
            provider.seed("/docs/a.txt", "hello".toByteArray())
            provider.seed("/b.txt", ByteArray(0))
            val mount = Mount(provider)
            mount.scan()
            mount.hydrate("/docs/a.txt")
            mount.rename("/docs/a.txt", "/docs/c.txt")

            val replies = listOf(mount.list("/"), mount.list("/docs"))

            assertTrue(replies.all { it.contains("\"etag\":") }, "the entries are there: $replies")
            assertFalse(replies.any { it.contains("content_id") }, "no key where the provider names nothing: $replies")
        }

    @Test
    fun `a row from before the column lists none until a listing names its bytes`() =
        runBlocking {
            val drive = ObjectNamingDrive()
            val listed = drive.seed("/old.txt", "hello".toByteArray())
            // The row an engine without the column wrote: the same version, nothing that names its bytes.
            db.upsertEntry(
                SyncEntry(
                    path = "/old.txt",
                    remoteId = listed.id,
                    remoteHash = listed.hash,
                    remoteSize = listed.size,
                    remoteModified = listed.modified,
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            val mount = Mount(drive)

            assertNull(mount.entry("/old.txt").field("content_id"), "nothing is made up for it")

            mount.scan()

            assertEquals("\"obj-1|5\"", mount.entry("/old.txt").field("content_id"), "the next listing names the bytes")
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
