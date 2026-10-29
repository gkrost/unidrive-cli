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
import org.krost.unidrive.DownloadReceipt
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.RemoteConflictException
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
     * file has no stored object and is named `|0`. A read looks the file's stored object up when it starts, as Internxt's
     * does, and its receipt names the version that came down. A replace whose write token is not the file's current one is
     * refused as a conflict, as Internxt refuses it.
     */
    private class ObjectNamingDrive(
        // gkrost/unidrive-cli#202: whether the drive declares that its listings name the bytes (CloudProvider.reportsContentIds).
        private val declaresContentIds: Boolean = true,
    ) : CloudProvider {
        override val id = "fake-object-naming"
        override val displayName = "Fake (names stored objects)"
        override var isAuthenticated = true
        override val reportsContentIds: Boolean get() = declaresContentIds

        // The folder listings asked for.
        var listings = 0

        val items = linkedMapOf<String, CloudItem>()
        private val content = mutableMapOf<String, ByteArray>()
        private val stored = mutableMapOf<String, ByteArray>()
        private var clock = Instant.parse("2026-10-28T14:55:00Z")
        private var objects = 0
        private var ids = 0

        // An upload the server stamps with the old version's modified time: the token stays, the stored object is another.
        var uploadKeepsToken = false

        // Runs when a read has looked up the object it reads, before a byte of it is written.
        var whileDownloading: (suspend () -> Unit)? = null

        var downloads = 0
        var uploads = 0

        private fun tick(): Instant = clock.plusSeconds(60).also { clock = it }

        // A new stored object for [bytes]; an empty file has none.
        private fun store(bytes: ByteArray): String? = "obj-${++objects}".takeIf { bytes.isNotEmpty() }?.also { stored[it] = bytes }

        /** The bytes a content id names. */
        fun bytesNamed(contentId: String): ByteArray? = if (contentId == "|0") ByteArray(0) else stored[contentId.substringBefore('|')]

        /** The bytes the file at [path] holds in the cloud now. */
        fun bytesAt(path: String): String = String(content.getValue(items.getValue(path).id))

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
            return file(id, path, bytes.size.toLong(), tick(), if (storedObject) store(bytes) else null).also { items[path] = it }
        }

        /** Another device saves [bytes] over the file at [path]: the same file, a new stored object and a new modified time. */
        fun replace(
            path: String,
            bytes: ByteArray,
        ) {
            val prior = items.getValue(path)
            content[prior.id] = bytes
            items[path] = file(prior.id, path, bytes.size.toLong(), tick(), store(bytes))
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

        override suspend fun listChildren(path: String): List<CloudItem> {
            listings++
            return items.values.filter { it.path.substringBeforeLast('/') == path.trimEnd('/') }
        }

        override suspend fun getMetadata(path: String): CloudItem = items[path] ?: throw ProviderException("Item not found: $path")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = downloadById(getMetadata(remotePath).id, remotePath, destination)

        override suspend fun downloadById(
            remoteId: String,
            remotePath: String,
            destination: Path,
        ): Long = downloadByIdWithHash(remoteId, remotePath, destination).bytesWritten

        override suspend fun downloadWithHash(
            remotePath: String,
            destination: Path,
            targetMtimeMillis: Long?,
        ): DownloadReceipt = downloadByIdWithHash(getMetadata(remotePath).id, remotePath, destination, targetMtimeMillis)

        override suspend fun downloadByIdWithHash(
            remoteId: String,
            remotePath: String,
            destination: Path,
            targetMtimeMillis: Long?,
        ): DownloadReceipt {
            val item = items.values.firstOrNull { it.id == remoteId } ?: throw ProviderException("Item not found: $remotePath")
            val bytes = content.getValue(remoteId)
            downloads++
            whileDownloading?.invoke()
            Files.createDirectories(destination.parent)
            Files.write(destination, bytes)
            return DownloadReceipt(
                bytes.size.toLong(),
                contentId = item.contentId,
                itemHash = item.hash,
                itemVersion = item.version,
                itemModified = item.modified,
            )
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
            if (ifMatchETag != null && prior != null && prior.hash != ifMatchETag) throw RemoteConflictException("changed before replace: $remotePath")
            uploads++
            val id = existingRemoteId ?: prior?.id ?: "u-${++ids}"
            content[id] = bytes
            val modified = prior?.modified?.takeIf { uploadKeepsToken } ?: tick()
            return file(id, remotePath, bytes.size.toLong(), modified, store(bytes)).also { items[remotePath] = it }
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
        // The paths the engine announced as changed (view.invalidated).
        val invalidated = mutableListOf<String>()

        private val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createDirectories(base.resolve("root")),
                cacheRoot = base.resolve("cache"),
                cacheKey = "profile",
                viewInvalidationSink = { paths, _, _ -> invalidated += paths },
            )
        private val hydration = HydrationImpl(engine, db)
        private val ipc = HydrationIpcHandler(hydration)

        /** A scan: the first one, or the refresh a later one is. */
        suspend fun scan() {
            MountEngine.over(engine).enumerateRemoteIntoState(false)
        }

        suspend fun call(request: String): String = ipc.handle("client", request)

        /** gkrost/unidrive-cli#202: one pass of the content-id backfill. */
        suspend fun backfillOnce(): ContentIdBackfill.Outcome = MountEngine.over(engine).backfillContentIdsOnce()

        /** gkrost/unidrive-cli#202: the backfill as the daemon runs it, without its waits. */
        suspend fun backfillAsTheDaemonDoes() = MountEngine.over(engine).backfillContentIds(startDelayMs = 0, retryMs = 0)

        suspend fun list(prefix: String = "/"): String =
            call("""{"verb":"hydration.list","prefix":"$prefix"}""").also { assertTrue(it.startsWith("""{"ok":true,"entries":"""), it) }

        suspend fun entry(path: String): String = entryOf(list(path.substringBeforeLast('/').ifEmpty { "/" }), path)

        suspend fun hydrate(path: String) = assertEquals("""{"ok":true}""", call("""{"verb":"hydration.hydrate","path":"$path"}"""))

        suspend fun openRead(path: String): String =
            call("""{"verb":"hydration.open_read","handle_id":"r-$path","path":"$path"}""").also { assertTrue(it.startsWith("""{"ok":true,"cache_path":"""), it) }

        suspend fun rename(
            from: String,
            to: String,
        ) = assertEquals("""{"ok":true}""", call("""{"verb":"hydration.rename","old_path":"$from","new_path":"$to"}"""))

        /** A save through the mount, based on the etag [base] when the client has one: the upload's completed event. */
        suspend fun write(
            path: String,
            bytes: ByteArray,
            base: String? = null,
        ): HydrationEvent.Completed =
            coroutineScope {
                val handle = "h-$path"
                val done = async(start = CoroutineStart.UNDISPATCHED) { hydration.events.filterIsInstance<HydrationEvent.Completed>().first { it.handleId == handle } }
                val cache = engine.resolveCachePath(path)
                Files.createDirectories(cache.parent)
                Files.write(cache, bytes)
                assertIs<OpenResult.Ok>(hydration.openForWrite("client", handle, path, cache, base))
                withTimeout(TIMEOUT_MS) { done.await() }
            }

        /** The next start of the daemon over the same state: what it replays as edits the cloud has not seen. */
        suspend fun replayAtStart(): Int = HydrationImpl(engine, db).replayPendingUploads()

        fun cacheCopy(path: String): Path = engine.resolveCachePath(path)
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

    // The bytes an open_read reply serves: the file at its cache_path.
    private fun servedBytes(reply: String): String =
        String(Files.readAllBytes(Path.of(assertNotNull(reply.field("cache_path"), reply).removeSurrounding("\"").replace("\\\\", "\\"))))

    // An open_read reply names the bytes it serves, or none: never other ones.
    private fun assertNamesServedBytes(
        drive: ObjectNamingDrive,
        reply: String,
    ) {
        val named = reply.field("content_id")?.removeSurrounding("\"") ?: return
        assertEquals(drive.bytesNamed(named)?.let(::String), servedBytes(reply), "$named names other bytes than $reply serves")
    }

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

    // A client learns what a placeholder holds when it fills it (open_read), not only from a listing: a file opened and then
    // renamed before any listing compares the id it was served with the one the list gives after the rename.
    @Test
    fun `open_read names the bytes it served, and the list gives the same content_id after a rename`() =
        runBlocking {
            val drive = ObjectNamingDrive()
            drive.seed("/a.txt", "hello".toByteArray())
            val mount = Mount(drive)
            mount.scan()

            val served = mount.openRead("/a.txt")

            assertEquals("\"obj-1|5\"", served.field("content_id"), "a cold read names the bytes it downloaded: $served")
            assertEquals("\"obj-1|5\"", mount.openRead("/a.txt").field("content_id"), "and so does a warm one")
            mount.rename("/a.txt", "/b.txt")
            assertEquals(served.field("content_id"), mount.entry("/b.txt").field("content_id"))
        }

    // Internxt reads a file by its id and serves the object the file holds when the read starts. A file replaced by other bytes
    // of the same size since it was listed comes down as the new version: the reply names those bytes, not the listed ones.
    @Test
    fun `a file replaced by bytes of the same size before a cold read is named as the version that came down`() =
        runBlocking {
            val drive = ObjectNamingDrive()
            drive.seed("/a.txt", "hello".toByteArray())
            val mount = Mount(drive)
            mount.scan()
            drive.replace("/a.txt", "world".toByteArray())

            val cold = mount.openRead("/a.txt")

            assertEquals("world", servedBytes(cold))
            assertEquals("\"obj-2|5\"", cold.field("content_id"), cold)
            val warm = mount.openRead("/a.txt")
            assertEquals("\"obj-2|5\"", warm.field("content_id"), "the copy keeps the name of the bytes it holds: $warm")
            assertNamesServedBytes(drive, warm)
            assertEquals("\"obj-2|5\"", mount.entry("/a.txt").field("content_id"), "the row took the name with the bytes, as it takes their size")
            mount.scan()
            val refreshed = mount.entry("/a.txt")
            assertEquals("\"obj-2|5\"", refreshed.field("content_id"), refreshed)
            assertEquals("true", refreshed.field("hydrated"), "the refresh finds the copy current: $refreshed")
        }

    // A refresh that brings another version while a read downloads the old one moves the row past the bytes that come down. The
    // reply names the bytes it serves; the next refresh finds the copy outlived, and the next read brings the new version.
    @Test
    fun `a refresh that brings other bytes while a read downloads names the bytes that came down`() =
        runBlocking {
            val drive = ObjectNamingDrive()
            drive.seed("/a.txt", "hello".toByteArray())
            val mount = Mount(drive)
            mount.scan()
            drive.whileDownloading = {
                drive.whileDownloading = null
                drive.replace("/a.txt", "world".toByteArray())
                mount.scan()
            }

            val cold = mount.openRead("/a.txt")

            assertEquals("hello", servedBytes(cold))
            assertEquals("\"obj-1|5\"", cold.field("content_id"), cold)
            assertNamesServedBytes(drive, mount.openRead("/a.txt"))
            mount.scan()
            val fresh = mount.openRead("/a.txt")
            assertEquals("world", servedBytes(fresh), "the refresh after the read found the copy outlived: $fresh")
            assertEquals("\"obj-2|5\"", fresh.field("content_id"), fresh)
        }

    // A refresh that overtakes a read leaves the row with the version the copy holds, not the newer one: an edit of that copy
    // goes up based on what it was made from, and the cloud refuses it as a conflict instead of having it overwrite the newer
    // version the edit never saw.
    @Test
    fun `an edit of a copy a refresh overtook goes up based on the version it holds and leaves the newer one alone`() =
        runBlocking {
            val drive = ObjectNamingDrive()
            drive.seed("/a.txt", "hello".toByteArray())
            val mount = Mount(drive)
            mount.scan()
            val listed = mount.entry("/a.txt")
            drive.whileDownloading = {
                drive.whileDownloading = null
                drive.replace("/a.txt", "world".toByteArray())
                mount.scan()
            }
            assertEquals("hello", servedBytes(mount.openRead("/a.txt")))
            val held = mount.entry("/a.txt")

            val upload = mount.write("/a.txt", "hello, edited".toByteArray(), base = assertNotNull(held.field("etag")).removeSurrounding("\""))

            assertFalse(upload.ok, "the edit of the older version must not replace the newer one: $upload")
            assertEquals(HydrationError.Conflict, upload.error)
            assertEquals("world", drive.bytesAt("/a.txt"), "the newer version in the cloud is untouched")
            assertEquals(listed.field("etag"), held.field("etag"), "the row named the version its copy held: $held")
            assertEquals(listed.field("remote_modified_ms"), held.field("remote_modified_ms"))
        }

    // The other way round, nothing overtook the read but the cloud had moved on since the listing: the copy is that newer version,
    // and the row takes it with its token, so an edit of the copy is based on the version it was made from and goes up.
    @Test
    fun `a file replaced before a cold read takes the version that came down, so an edit of the copy goes up`() =
        runBlocking {
            val drive = ObjectNamingDrive()
            drive.seed("/a.txt", "hello".toByteArray())
            val mount = Mount(drive)
            mount.scan()
            drive.replace("/a.txt", "world".toByteArray())
            assertEquals("world", servedBytes(mount.openRead("/a.txt")))
            val held = mount.entry("/a.txt")

            val upload = mount.write("/a.txt", "world, edited".toByteArray(), base = assertNotNull(held.field("etag")).removeSurrounding("\""))

            assertTrue(upload.ok, "an edit of the version the copy holds is no conflict: $upload")
            assertEquals("world, edited", drive.bytesAt("/a.txt"))
        }

    // What the race must not cost: the copy a refresh overtook was served, so it is hydrated (a pin pass does not fetch it
    // again); the size check holds it against its own size, not the newer version's; and its stats are the row's baseline, so
    // neither the next start nor a client's recovery scan takes it for an edit to upload.
    @Test
    fun `a copy a refresh overtook stays hydrated, is served whole whatever the newer size, and is no edit to replay`() =
        runBlocking {
            val drive = ObjectNamingDrive()
            drive.seed("/a.txt", "hello".toByteArray())
            drive.seed("/b.txt", "hello".toByteArray())
            val mount = Mount(drive)
            mount.scan()
            fun overtakeWith(path: String) {
                drive.whileDownloading = {
                    drive.whileDownloading = null
                    drive.replace(path, "a newer and longer version".toByteArray())
                    mount.scan()
                }
            }
            suspend fun assertKept(path: String) {
                val entry = mount.entry(path)
                assertEquals("true", entry.field("hydrated"), "$path: $entry")
                assertEquals("5", entry.field("size"), "$path: $entry")
                val row = assertNotNull(db.getEntry(path))
                val copy = mount.cacheCopy(path)
                assertEquals(row.localMtime, Files.getLastModifiedTime(copy).toMillis(), "$path: the copy is the row's baseline")
                assertEquals(row.localSize, Files.size(copy))
                assertTrue(Files.getLastModifiedTime(copy).toMillis() <= row.lastSynced.toEpochMilli(), "$path: not newer than the watermark")
                assertEquals(0, mount.replayAtStart(), "$path: the next start finds no edit to send")
            }

            overtakeWith("/a.txt")
            val read = mount.openRead("/a.txt")

            assertEquals("hello", servedBytes(read), "the read was served whole")
            assertKept("/a.txt")

            overtakeWith("/b.txt")
            mount.hydrate("/b.txt")
            val fetched = drive.downloads
            mount.hydrate("/b.txt")

            assertEquals(fetched, drive.downloads, "a pin pass does not fetch the hydrated copy again")
            assertKept("/b.txt")
            assertEquals(0, drive.uploads)
        }

    // A read that nothing overtook, of the version the row records, leaves the row's cloud fields as they were.
    @Test
    fun `a read no refresh overtook records what it recorded before`() =
        runBlocking {
            val drive = ObjectNamingDrive()
            drive.seed("/a.txt", "hello".toByteArray())
            val mount = Mount(drive)
            mount.scan()
            val before = assertNotNull(db.getEntry("/a.txt"))

            mount.openRead("/a.txt")

            val after = assertNotNull(db.getEntry("/a.txt"))
            assertEquals(
                before.copy(
                    isHydrated = true,
                    localMtime = after.localMtime,
                    localSize = after.localSize,
                    localHash = after.localHash,
                    lastSynced = after.lastSynced,
                    cacheBacked = after.cacheBacked,
                ),
                after,
            )
        }

    @Test
    fun `open_read of a copy that holds an unsynced edit names no bytes`() =
        runBlocking {
            val drive = ObjectNamingDrive()
            drive.seed("/a.txt", "hello".toByteArray())
            val mount = Mount(drive)
            mount.scan()
            mount.hydrate("/a.txt")
            // The client starts an edit: the cache copy is now the copy of record, and not the cloud's version.
            assertTrue(mount.call("""{"verb":"hydration.open_write_begin","path":"/a.txt","handle_id":"w-1"}""").startsWith("""{"ok":true"""))

            val served = mount.openRead("/a.txt")

            assertNull(served.field("content_id"), "the served bytes are the edit's: $served")
            assertEquals("\"obj-1|5\"", mount.entry("/a.txt").field("content_id"), "the list still names the cloud's version")
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

            assertTrue(mount.write("/a.txt", "world".toByteArray()).ok, "the upload landed")

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
            val read = mount.openRead("/docs/a.txt")
            mount.rename("/docs/a.txt", "/docs/c.txt")

            val replies = listOf(read, mount.list("/"), mount.list("/docs"))

            assertTrue(replies.drop(1).all { it.contains("\"etag\":") }, "the entries are there: $replies")
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

    // gkrost/unidrive-cli#202: an incremental poll re-lists only the items that change, so the rows an engine wrote before the column
    // waited for a change of their item. The backfill lists their folders once and names the bytes of each row whose item is unchanged.
    @Test
    fun `after the backfill hydration_list names the content_id of rows from before the column, and a rename keeps it`() =
        runBlocking {
            val drive = ObjectNamingDrive()
            drive.seedFolder("/docs")
            drive.seed("/docs/a.txt", "hello".toByteArray())
            drive.seed("/docs/b.txt", "world!".toByteArray())
            drive.seed("/c.txt", "x".toByteArray())
            val mount = Mount(drive)
            mount.scan()
            mount.hydrate("/docs/a.txt")
            // The rows as an engine without the column left them: the same versions, nothing that names their bytes.
            db.getAllEntries().forEach { db.upsertEntry(it.copy(remoteContentId = null), keepMarks = true) }
            assertNull(mount.entry("/docs/a.txt").field("content_id"), "before: ${mount.list("/docs")}")
            val rowBefore = db.getEntry("/docs/a.txt")!!
            val copy = mount.cacheCopy("/docs/a.txt")
            val copyTime = Files.getLastModifiedTime(copy)
            mount.invalidated.clear()

            assertEquals(ContentIdBackfill.Outcome.Finished(folders = 2, filled = 3), mount.backfillOnce())

            val a = mount.entry("/docs/a.txt")
            assertEquals("\"${drive.items.getValue("/docs/a.txt").contentId}\"", a.field("content_id"), "after: $a")
            assertEquals("\"${drive.items.getValue("/docs/b.txt").contentId}\"", mount.entry("/docs/b.txt").field("content_id"))
            assertEquals("\"${drive.items.getValue("/c.txt").contentId}\"", mount.entry("/c.txt").field("content_id"))
            assertNull(mount.entry("/docs").field("content_id"), "a folder names no bytes")
            assertEquals("true", a.field("hydrated"))
            assertEquals(rowBefore.copy(remoteContentId = drive.items.getValue("/docs/a.txt").contentId), db.getEntry("/docs/a.txt"), "only the id changed")
            assertEquals("hello", String(Files.readAllBytes(copy)))
            assertEquals(copyTime, Files.getLastModifiedTime(copy), "the cache copy is untouched")
            assertEquals(listOf("/", "/docs"), mount.invalidated, "the folders are announced, so a client lists them again and learns the ids")

            mount.rename("/docs/a.txt", "/docs/a2.txt")
            mount.scan()

            val renamed = mount.entry("/docs/a2.txt")
            assertEquals(a.field("content_id"), renamed.field("content_id"), "a rename keeps the backfilled id: $renamed")
            assertNotEquals(a.field("etag"), renamed.field("etag"))
            assertEquals("true", renamed.field("hydrated"))
        }

    @Test
    fun `the backfill as the daemon runs it ends after one pass, and costs a provider that names no content nothing`() =
        runBlocking {
            val naming = ObjectNamingDrive()
            naming.seed("/a.txt", "hello".toByteArray())
            val mount = Mount(naming)
            mount.scan()
            db.getAllEntries().forEach { db.upsertEntry(it.copy(remoteContentId = null), keepMarks = true) }
            val before = naming.listings

            withTimeout(TIMEOUT_MS) { mount.backfillAsTheDaemonDoes() }

            assertEquals(1, naming.listings - before, "one listing of the one folder")
            assertEquals("\"obj-1|5\"", mount.entry("/a.txt").field("content_id"))
            assertEquals(ContentIdBackfill.DONE, db.getSyncState(ContentIdBackfill.STATE_KEY))
            withTimeout(TIMEOUT_MS) { mount.backfillAsTheDaemonDoes() }
            assertEquals(1, naming.listings - before, "a profile that is done lists nothing again")
        }

    @Test
    fun `a provider that names no content gets no listing from the backfill`() =
        runBlocking {
            val drive = ObjectNamingDrive(declaresContentIds = false)
            drive.seed("/a.txt", "hello".toByteArray())
            val mount = Mount(drive)
            mount.scan()
            db.getAllEntries().forEach { db.upsertEntry(it.copy(remoteContentId = null), keepMarks = true) }
            val before = drive.listings

            withTimeout(TIMEOUT_MS) { mount.backfillAsTheDaemonDoes() }

            assertEquals(ContentIdBackfill.Outcome.NotNeeded, mount.backfillOnce())
            assertEquals(before, drive.listings)
            assertNull(mount.entry("/a.txt").field("content_id"))
            assertNull(db.getSyncState(ContentIdBackfill.STATE_KEY))
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
