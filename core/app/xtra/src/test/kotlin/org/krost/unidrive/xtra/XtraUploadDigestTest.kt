package org.krost.unidrive.xtra

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.HydrationIpcHandler
import org.krost.unidrive.hydration.MountEngine
import org.krost.unidrive.localfs.LocalFsProvider
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncDirection
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#1018 item 16, the single-machine step before the manifest: X-tra's AEAD binds only the header, so a
 * store that serves another valid X-tra object at a path, or an older one, passes the decryption, and with no hash
 * algorithm the engines had no content check left. A download or hydration of an object whose remote version is still
 * the one this machine uploaded now compares the plaintext's SHA-256 with the digest of what was sent, and refuses a
 * mismatch: nothing is placed, the copy that was there stays, and the row keeps the digest it had.
 *
 * The store here is a localfs root, as the X-tra chain the CLI wires lists it. A localfs item names no ETag and no
 * version, so its version is its id, size and modification time: a swap or a rollback that keeps the listing keeps those,
 * and a real edit by another device changes the time. The last test runs the same over a provider that names a token per
 * write and hashes the bytes it sends, as Internxt does.
 */
class XtraUploadDigestTest {
    private val base: Path = TestScratch.directory("ud-xtra-digest")
    private val cloud: Path = Files.createDirectories(base.resolve("cloud"))
    private val root: Path = Files.createDirectories(base.resolve("root"))
    private val db = StateDatabase(base.resolve("state.db")).also { it.initialize() }
    private val keys = lazy { XtraKeyManager(base.resolve("xtra.key")).also { it.generate("digest-pass".toCharArray()) } }
    private val strategy = AesGcmStrategy(keys)
    private val warnings = Warnings()

    // The chain the CLI wires: the listings decorator (plaintext sizes) below the cipher, here over a localfs root.
    private fun xtraOver(inner: CloudProvider) = XtraEncryptedProvider(XtraPlaintextListings(inner, strategy), strategy)

    private fun localfs() = LocalFsProvider(cloud).also { runBlocking { it.authenticate() } }

    private val provider = xtraOver(localfs())

    // Another device with the same key, writing to the same store.
    private val otherDevice = xtraOver(localfs())

    @AfterTest
    fun close() {
        db.close()
        base.toFile().deleteRecursively()
    }

    private class Warnings : ProgressReporter by ProgressReporter.Silent {
        val seen: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        override fun onWarning(message: String) {
            seen += message
        }
    }

    private fun mirror(direction: SyncDirection = SyncDirection.BIDIRECTIONAL) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = root,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = warnings,
            syncDirection = direction,
        )

    private fun stored(path: String): Path = cloud.resolve(path.trimStart('/'))

    private fun writeLocal(
        path: String,
        content: String,
    ): Path = root.resolve(path.trimStart('/')).also { Files.writeString(it, content) }

    // The store swaps the objects of two files and keeps the listing: each name keeps its size (same-length plaintexts)
    // and its modification time.
    private fun swapStoredObjects(
        a: String,
        b: String,
    ) {
        val fa = stored(a)
        val fb = stored(b)
        val ta = Files.getLastModifiedTime(fa)
        val tb = Files.getLastModifiedTime(fb)
        val bytesA = Files.readAllBytes(fa)
        Files.write(fa, Files.readAllBytes(fb))
        Files.write(fb, bytesA)
        Files.setLastModifiedTime(fa, ta)
        Files.setLastModifiedTime(fb, tb)
    }

    // The store serves [older] (an earlier object of the same file) under the listing of the current one.
    private fun rollBack(
        path: String,
        older: ByteArray,
    ) {
        val current = stored(path)
        val time = Files.getLastModifiedTime(current)
        Files.write(current, older)
        Files.setLastModifiedTime(current, time)
    }

    // A precondition of every refusal test: the listing names the version the row recorded at its upload.
    private suspend fun assertListingKept(path: String) {
        val row = assertNotNull(db.getEntry(path))
        val listed = provider.getMetadata(path)
        assertEquals(row.remoteId, listed.id, "precondition: the listing keeps the id of $path")
        assertEquals(row.remoteSize, listed.size, "precondition: the listing keeps the size of $path")
        assertEquals(row.remoteModified, listed.modified, "precondition: the listing keeps the time of $path")
    }

    // Another device uploads [content] over [path]; the store stamps a later time, as it does for any new write.
    private suspend fun editOnOtherDevice(
        path: String,
        content: String,
    ) {
        val before = Files.getLastModifiedTime(stored(path)).toMillis()
        val file = base.resolve("other-device.txt")
        Files.writeString(file, content)
        otherDevice.upload(file, path, existingRemoteId = path, ifMatchETag = null)
        Files.setLastModifiedTime(stored(path), FileTime.fromMillis(before + 2_000))
    }

    private fun refusedPaths(): List<String> = warnings.seen.filter { it.startsWith("Permanent failure: ") }

    // ── the mirror ──────────────────────────────────────────────────────────

    @Test
    fun `a mirror re-download of two swapped objects is refused and places neither`() =
        runBlocking {
            writeLocal("/a.txt", "alpha content")
            writeLocal("/b.txt", "bravo content")
            mirror().syncOnce()
            swapStoredObjects("/a.txt", "/b.txt")
            assertListingKept("/a.txt")
            assertListingKept("/b.txt")

            // A download-only profile re-downloads a file whose local copy is gone (archive#160).
            Files.delete(root.resolve("a.txt"))
            Files.delete(root.resolve("b.txt"))
            mirror(SyncDirection.DOWNLOAD).syncOnce()

            for ((path, other) in listOf("/a.txt" to "bravo content", "/b.txt" to "alpha content")) {
                val local = root.resolve(path.trimStart('/'))
                assertNotEquals(other, if (Files.exists(local)) Files.readString(local) else null, "the other file's bytes must not land at $path")
                val row = assertNotNull(db.getEntry(path))
                assertTrue(row.downloadQuarantined, "a refused content is quarantined like a failed seal: $path")
                assertTrue(refusedPaths().any { it.startsWith("Permanent failure: $path - ") }, "the refusal is reported: ${warnings.seen}")
            }
        }

    @Test
    fun `a mirror re-download of an older object under the current listing is refused`() =
        runBlocking {
            val local = writeLocal("/a.txt", "version one")
            mirror().syncOnce()
            val versionOne = Files.readAllBytes(stored("/a.txt"))
            Files.writeString(local, "version two")
            Files.setLastModifiedTime(local, FileTime.fromMillis(Files.getLastModifiedTime(local).toMillis() + 2_000))
            mirror().syncOnce()
            assertNotEquals(versionOne.toList(), Files.readAllBytes(stored("/a.txt")).toList(), "precondition: version two is stored")
            rollBack("/a.txt", versionOne)
            assertListingKept("/a.txt")

            Files.delete(local)
            mirror(SyncDirection.DOWNLOAD).syncOnce()

            assertNotEquals("version one", if (Files.exists(local)) Files.readString(local) else null, "the older object must not land")
            assertTrue(assertNotNull(db.getEntry("/a.txt")).downloadQuarantined)
            assertTrue(refusedPaths().any { it.startsWith("Permanent failure: /a.txt - ") }, warnings.seen.toString())
        }

    @Test
    fun `a mirror download of another device's edit is accepted`() =
        runBlocking {
            val local = writeLocal("/a.txt", "alpha content")
            mirror().syncOnce()

            // Same length, so only the store's time tells the new version from the old one.
            editOnOtherDevice("/a.txt", "alpha edited!")
            mirror().syncOnce()

            assertEquals("alpha edited!", Files.readString(local))
            val row = assertNotNull(db.getEntry("/a.txt"))
            assertFalse(row.downloadQuarantined)
            assertTrue(refusedPaths().isEmpty(), warnings.seen.toString())
        }

    // ── the mount ───────────────────────────────────────────────────────────

    private inner class Mount(
        over: CloudProvider = provider,
    ) {
        val engine =
            SyncEngine(
                provider = over,
                db = db,
                syncRoot = root,
                cacheRoot = base.resolve("cache"),
                cacheKey = "profile",
            )
        private val ipc = HydrationIpcHandler(HydrationImpl(engine, db))
        val front: MountEngine get() = MountEngine.over(engine)

        suspend fun call(request: String): JsonObject = Json.parseToJsonElement(ipc.handle("client", request)).jsonObject

        // A file written through the mount: the row the create verb makes (no remote yet, the cache copy is the only one),
        // then the write-back the upload queue runs.
        suspend fun write(
            path: String,
            content: String,
        ) {
            val cache = engine.resolveCachePath(path)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, content)
            if (db.getEntry(path) == null) {
                db.upsertEntry(
                    SyncEntry(
                        path = path,
                        remoteId = null,
                        remoteHash = null,
                        remoteSize = 0L,
                        remoteModified = null,
                        localMtime = Files.getLastModifiedTime(cache).toMillis(),
                        localSize = Files.size(cache),
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = Instant.now(),
                    ),
                )
            }
            front.uploadFromCache(path, cache)
        }

        suspend fun dehydrate(path: String) {
            val reply = call("""{"verb":"hydration.dehydrate","path":"$path"}""")
            assertEquals("true", reply.getValue("ok").jsonPrimitive.content, "precondition: $path frees its cache copy: $reply")
        }

        suspend fun scan() = front.enumerateRemoteIntoState(false)

        suspend fun read(path: String): JsonObject =
            call("""{"verb":"hydration.open_read","handle_id":"r-$path","path":"$path"}""").also { reply ->
                if (reply.getValue("ok").jsonPrimitive.content == "true") {
                    call("""{"verb":"hydration.close_handle","handle_id":"r-$path"}""")
                }
            }
    }

    private fun assertRefused(
        reply: JsonObject,
        path: String,
    ) {
        assertEquals("false", reply.getValue("ok").jsonPrimitive.content, "the hydration of $path must be refused: $reply")
        val error = reply.getValue("error").jsonPrimitive.content
        assertTrue(error.startsWith("content_refused: "), "a refused content, not another failure: $error")
    }

    private fun assertRead(
        reply: JsonObject,
        content: String,
    ) {
        assertEquals("true", reply.getValue("ok").jsonPrimitive.content, reply.toString())
        assertEquals(content, Files.readString(Path.of(reply.getValue("cache_path").jsonPrimitive.content)))
    }

    @Test
    fun `a hydration of two swapped objects is refused and the rows keep the upload digest`() =
        runBlocking {
            val mount = Mount()
            mount.write("/a.txt", "alpha content")
            mount.write("/b.txt", "bravo content")
            mount.dehydrate("/a.txt")
            mount.dehydrate("/b.txt")
            swapStoredObjects("/a.txt", "/b.txt")
            // The mount re-lists the store as it polls; the listing is the one the uploads left.
            mount.scan()
            assertListingKept("/a.txt")
            assertListingKept("/b.txt")
            val before = listOf("/a.txt", "/b.txt").associateWith { assertNotNull(db.getEntry(it)) }

            for ((path, other) in listOf("/a.txt" to "bravo content", "/b.txt" to "alpha content")) {
                assertRefused(mount.read(path), path)
                val cache = mount.engine.resolveCachePath(path)
                assertNotEquals(other, if (Files.exists(cache)) Files.readString(cache) else null, "the other file's bytes must not land at $path")
                val row = assertNotNull(db.getEntry(path))
                assertFalse(row.isHydrated)
                assertEquals(before.getValue(path), row, "a refused hydration writes nothing to the row of $path")
                // The same version reads the same bytes: a repeat read is refused without another download.
                assertRefused(mount.read(path), path)
            }
        }

    @Test
    fun `a hydration of an older object under the current listing is refused`() =
        runBlocking {
            val mount = Mount()
            mount.write("/a.txt", "version one")
            val versionOne = Files.readAllBytes(stored("/a.txt"))
            mount.write("/a.txt", "version two")
            mount.dehydrate("/a.txt")
            rollBack("/a.txt", versionOne)
            mount.scan()
            assertListingKept("/a.txt")

            assertRefused(mount.read("/a.txt"), "/a.txt")
        }

    @Test
    fun `a hydration of another device's edit is accepted`() =
        runBlocking {
            val mount = Mount()
            mount.write("/a.txt", "alpha content")
            mount.dehydrate("/a.txt")
            editOnOtherDevice("/a.txt", "alpha edited!")
            mount.scan()

            assertRead(mount.read("/a.txt"), "alpha edited!")
        }

    @Test
    fun `over a provider that names a token per write and hashes what it sends, a swap is refused and a new token is read`() =
        runBlocking {
            // Internxt's shape: a fresh token per write, and the SHA-256 of the bytes its upload read (the ciphertext X-tra
            // hands it) on the answer.
            val inner =
                FakeCloudProvider().apply {
                    uniqueUploadTokens = true
                    reportSentSha256 = ::sha256Hex
                }
            val chain = xtraOver(inner)
            val mount = Mount(chain)
            mount.write("/a.txt", "alpha content")
            mount.write("/b.txt", "bravo content")
            // The listing names each object as its upload answered it.
            inner.deltaItems = listOf("/a.txt", "/b.txt").map { path -> listedAsUploaded(inner, path) }
            mount.scan()

            // An untouched file reads back: the digest the row compares with is the plaintext's, not the ciphertext's.
            mount.dehydrate("/a.txt")
            assertRead(mount.read("/a.txt"), "alpha content")

            mount.dehydrate("/a.txt")
            mount.dehydrate("/b.txt")
            val bytesA = inner.files.getValue("/a.txt")
            inner.files["/a.txt"] = inner.files.getValue("/b.txt")
            inner.files["/b.txt"] = bytesA
            assertRefused(mount.read("/a.txt"), "/a.txt")
            assertRefused(mount.read("/b.txt"), "/b.txt")

            // Another device writes a new version: a new token, the same size. That version is read.
            val file = base.resolve("other-device.txt")
            Files.writeString(file, "alpha edited!")
            val edited = chain.upload(file, "/a.txt", existingRemoteId = "id-/a.txt", ifMatchETag = null)
            inner.deltaItems = listOf(edited.copy(size = inner.files.getValue("/a.txt").size.toLong()), listedAsUploaded(inner, "/b.txt"))
            mount.scan()
            assertRead(mount.read("/a.txt"), "alpha edited!")
        }

    // The fake lists what a test seeds: [path] as its row recorded the upload answer, in the stored object's own size (the
    // listings decorator turns it into the plaintext's, as for the real store).
    private fun listedAsUploaded(
        inner: FakeCloudProvider,
        path: String,
    ): CloudItem {
        val row = assertNotNull(db.getEntry(path))
        return CloudItem(
            id = assertNotNull(row.remoteId),
            name = path.substringAfterLast('/'),
            path = path,
            size = inner.files.getValue(path).size.toLong(),
            isFolder = false,
            modified = row.remoteModified,
            created = row.remoteModified,
            hash = row.remoteHash,
            mimeType = null,
            version = row.remoteVersion,
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
}
