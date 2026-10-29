package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A bulk move through the mount while some of the moved files' uploads still wait in their retry backoff (archive#610):
 * files written into a folder, the first upload attempt of some of them fails, and all of them are renamed into a
 * subfolder before the retry runs. The retry must end with exactly one cloud item per name, at the new path, and no
 * conflict copy: it may neither give the file up (the row at the new path would wait for the next start with nothing
 * uploading it) nor create it at the new path as a new item when the rename already moved the cloud item there (the
 * drive answers that create with a 409 that used to become a `(conflict ...)` copy).
 *
 * The interleaving is driven by the test dispatcher, not by sleeps: the first attempt runs and fails, the upload waits
 * in its backoff (virtual time), the rename lands, then the backoff ends and the retry runs.
 */
class HydrationRenameOvertakesUploadRetryTest {

    /**
     * A drive that keeps its items by path, like Internxt: a create (no existing id) at a path that already holds an
     * item is a collision, and the provider keeps both (the pre-archive#1008 UD-366 behaviour, which is what put the conflict
     * copy into the mount). [failFirst] fails the first upload of a path before anything lands; [ghostFirst] fails it
     * after the item landed (the answer was lost), which leaves a ghost for the rename's probe to find.
     */
    private class PathKeyedDrive : CloudProvider {
        override val id = "fake-path-keyed"
        override val displayName = "Fake (rename overtakes upload retry)"
        override var isAuthenticated = true

        val items = linkedMapOf<String, CloudItem>()
        val log = mutableListOf<String>()
        val failFirst = mutableSetOf<String>()
        val ghostFirst = mutableSetOf<String>()

        // archive#1217 case 2: an upload of a path held here suspends after it read the bytes and before anything lands,
        // until the test completes the gate (the attempt is RUNNING while the rename lands).
        val holdUpload = mutableMapOf<String, CompletableDeferred<Unit>>()

        // Ids whose first id-addressed move fails (transient), so the retry has to move the item again.
        val failMoveFirst = mutableSetOf<String>()

        // gkrost/unidrive-cli#76: a real drive moves a folder together with everything in it; by default this fake keeps every
        // other item at its own path (a folder move moves only the folder item itself).
        var folderMoveCarriesChildren = false

        // gkrost/unidrive-cli#171: a file item names its bytes (CloudItem.contentId), as Internxt's do; off by default.
        var namesContent = false
        private var nextId = 0

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()
        override suspend fun getMetadata(path: String): CloudItem = items[path] ?: throw ProviderException("Item not found: $path")
        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            val content = Files.readString(localPath)
            log += "upload:$remotePath(existing=${existingRemoteId ?: "none"})"
            holdUpload.remove(remotePath)?.await()
            if (failFirst.remove(remotePath)) throw IOException("first attempt fails before anything lands: $remotePath")
            val ghost = ghostFirst.remove(remotePath)
            val occupant = items[remotePath]
            val landed =
                when {
                    existingRemoteId != null && occupant?.id == existingRemoteId -> item(remotePath, content, existingRemoteId)
                    occupant != null -> {
                        // A create onto an occupied name: the drive refuses it and the provider keeps both.
                        val copy = remotePath.substringBeforeLast('.') + " (conflict).txt"
                        log += "conflict-copy:$copy"
                        item(copy, content, "id-${nextId++}")
                    }
                    else -> item(remotePath, content, "id-${nextId++}")
                }
            items[landed.path] = landed
            if (ghost) throw IOException("the item landed but the answer was lost: $remotePath")
            return landed
        }

        private fun item(path: String, content: String, id: String) =
            CloudItem(
                id = id,
                name = path.substringAfterLast('/'),
                path = path,
                size = content.length.toLong(),
                isFolder = false,
                modified = Instant.parse("2026-10-07T07:33:00Z"),
                created = null,
                hash = "h-$content",
                mimeType = null,
                contentId = "obj-$id|${content.length}".takeIf { namesContent },
            )

        override suspend fun delete(remotePath: String, ifMatchETag: String?) {
            log += "delete:$remotePath"
            items.remove(remotePath)
        }

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem {
            log += "move:$fromPath->$toPath"
            val moved = (items.remove(fromPath) ?: throw ProviderException("Item not found: $fromPath"))
                .copy(path = toPath, name = toPath.substringAfterLast('/'))
            items[toPath] = moved
            if (moved.isFolder && folderMoveCarriesChildren) carryChildren(fromPath, toPath)
            return moved
        }

        private fun carryChildren(fromPath: String, toPath: String) {
            for (key in items.keys.filter { it.startsWith("$fromPath/") }) {
                val child = items.remove(key)!!
                val newPath = toPath + key.removePrefix(fromPath)
                items[newPath] = child.copy(path = newPath)
            }
        }

        // Addressed by the item's id, as the real providers do: the item is found wherever it is, not by the name.
        override suspend fun moveById(remoteId: String, fromPath: String, toPath: String): CloudItem {
            log += "moveById:$remoteId:$fromPath->$toPath"
            if (failMoveFirst.remove(remoteId)) throw IOException("the move of $remoteId fails once")
            val at = items.entries.firstOrNull { it.value.id == remoteId }?.key ?: throw ProviderException("Item not found: $remoteId")
            val moved = items.remove(at)!!.copy(path = toPath, name = toPath.substringAfterLast('/'))
            items[toPath] = moved
            if (moved.isFolder && folderMoveCarriesChildren) carryChildren(fromPath, toPath)
            return moved
        }

        // A folder the drive already knows (a cloud-backed folder row's remote id is its id). Like a real drive, a
        // folder move moves only the folder item itself; this fake keeps every other item at its own path.
        fun cloudFolder(path: String) {
            items[path] = CloudItem(
                id = "folder-$path",
                name = path.substringAfterLast('/'),
                path = path,
                size = 0,
                isFolder = true,
                modified = Instant.parse("2026-10-07T07:33:00Z"),
                created = null,
                hash = null,
                mimeType = null,
            )
        }

        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage =
            DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private class Rig(scope: TestScope, uploadQueueDepth: Int = 256) {
        val drive = PathKeyedDrive()
        val db = StateDatabase(dbPath = Files.createTempDirectory("unidrive-610-db").resolve("state.db"), inMemory = true)
            .also { it.initialize() }
        val engine = SyncEngine(
            provider = drive,
            db = db,
            syncRoot = Files.createTempDirectory("unidrive-610-sync"),
            cacheRoot = Files.createTempDirectory("unidrive-610-cache"),
        )
        val impl = HydrationImpl(engine, db, recoveryUploadScope = scope, uploadStallTimeoutMs = 0L, uploadQueueDepth = uploadQueueDepth)

        fun seedFolder(path: String) = db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = "folder-$path",
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = null,
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )

        // A folder created through the mount whose upload never ran: no remote id, like a written file's row.
        fun seedLocalFolder(path: String) = db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = null,
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = null,
                localMtime = Instant.now().toEpochMilli(),
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )

        // A file written through the mount: no remote id yet, the cache holds the only copy.
        fun seedWritten(path: String, content: String): Path {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = null,
                    localMtime = Instant.now().toEpochMilli(),
                    localSize = content.length.toLong(),
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
            val cache = engine.resolveCachePath(path)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, content)
            return cache
        }
    }

    private suspend fun TestScope.moveAllWhileRetriesWait(
        rig: Rig,
        names: List<String>,
    ): List<HydrationEvent.Completed> {
        rig.seedFolder("/move")
        rig.seedFolder("/move/sub")
        val completed = mutableListOf<HydrationEvent.Completed>()
        val collector = launch { rig.impl.events.collect { if (it is HydrationEvent.Completed) completed += it } }
        runCurrent()
        names.forEachIndexed { i, name ->
            val path = "/move/$name"
            val cache = rig.seedWritten(path, "content-$name")
            launch { assertTrue(rig.impl.openForWrite("c", "h$i", path, cache, null) is OpenResult.Ok) }
        }
        // Every first attempt runs; the failed ones now wait in their backoff (virtual time does not move here).
        runCurrent()
        // The bulk move lands while those retries wait.
        for (name in names) {
            assertEquals(RenameResult.Ok, rig.impl.rename("/move/$name", "/move/sub/$name"), "rename of $name")
        }
        advanceUntilIdle()
        collector.cancel()
        return completed
    }

    private fun assertOneItemPerNameAtTheNewPath(
        rig: Rig,
        names: List<String>,
    ) {
        assertTrue(rig.drive.log.none { it.startsWith("conflict-copy:") }, "no conflict copy may be made: ${rig.drive.log}")
        assertEquals(
            names.map { "/move/sub/$it" }.sorted(),
            rig.drive.items.keys.sorted(),
            "exactly one cloud item per name, at the new path: ${rig.drive.log}",
        )
        for (name in names) {
            assertNull(rig.db.getEntry("/move/$name"), "no row may be left at the old path of $name")
            val row = assertNotNull(rig.db.getEntry("/move/sub/$name"), "the row of $name lives at the new path")
            assertEquals(
                rig.drive.items["/move/sub/$name"]?.id,
                row.remoteId,
                "the row at the new path is the cloud item there (uploaded, not left pending): ${rig.drive.log}",
            )
        }
        assertTrue(
            rig.db.getAllEntries().none { it.path.contains("conflict") },
            "no conflict row may appear in state.db: ${rig.db.getAllEntries().map { it.path }}",
        )
    }

    @Test
    fun `a retry the rename overtook uploads the file at its new path`() = runTest {
        val rig = Rig(this)
        rig.drive.failFirst += "/move/f2.txt"

        val completed = moveAllWhileRetriesWait(rig, listOf("f2.txt"))

        assertOneItemPerNameAtTheNewPath(rig, listOf("f2.txt"))
        assertTrue(completed.single { it.handleId == "h0" }.ok, "the moved file's upload completes: $completed")
    }

    @Test
    fun `a retry the rename overtook does not create a second item where the rename moved the first`() = runTest {
        val rig = Rig(this)
        // The first attempt landed the item but its answer was lost: the row stays pending, the rename's ghost probe finds
        // the item and moves it into the subfolder, and the retry then meets it at the new path.
        rig.drive.ghostFirst += "/move/f11.txt"

        val completed = moveAllWhileRetriesWait(rig, listOf("f11.txt"))

        assertOneItemPerNameAtTheNewPath(rig, listOf("f11.txt"))
        assertTrue(
            rig.drive.log.none { it == "upload:/move/sub/f11.txt(existing=none)" },
            "nothing may create the moved file at its new path as a new item: ${rig.drive.log}",
        )
        assertTrue(completed.single { it.handleId == "h0" }.ok, "the moved file's upload completes: $completed")
    }

    @Test
    fun `a bulk move while some uploads wait in their retries ends with one item per name`() = runTest {
        val rig = Rig(this)
        val names = (1..15).map { "f$it.txt" }
        rig.drive.failFirst += listOf("/move/f2.txt", "/move/f7.txt")
        rig.drive.ghostFirst += "/move/f11.txt"

        val completed = moveAllWhileRetriesWait(rig, names)

        assertOneItemPerNameAtTheNewPath(rig, names)
        assertEquals(names.size, completed.count { it.ok }, "every upload completes: $completed")
    }

    // The folder-rename flavour of archive#610's shape (archive#1217 case 1): children written into a folder, every one of them
    // waiting in its retry backoff, and the FOLDER renamed while they wait. The children's retries must end with
    // exactly one cloud item per child at its new path — not given up because their slots still ran at the old ones.
    private suspend fun TestScope.renameFolderWhileUploadsWait(
        rig: Rig,
        names: List<String>,
    ): List<HydrationEvent.Completed> {
        val completed = mutableListOf<HydrationEvent.Completed>()
        val collector = launch { rig.impl.events.collect { if (it is HydrationEvent.Completed) completed += it } }
        runCurrent()
        names.forEachIndexed { i, name ->
            val path = "/move/$name"
            val cache = rig.seedWritten(path, "content-$name")
            launch { assertTrue(rig.impl.openForWrite("c", "h$i", path, cache, null) is OpenResult.Ok) }
        }
        // Every first attempt runs and fails: all the children now wait in their backoff (virtual time does not move).
        runCurrent()
        // The folder itself is renamed while those retries wait.
        assertEquals(RenameResult.Ok, rig.impl.rename("/move", "/moved"), "rename of the folder")
        advanceUntilIdle()
        collector.cancel()
        return completed
    }

    private fun assertOneItemPerChildAtTheNewPath(
        rig: Rig,
        names: List<String>,
        folderInDrive: Boolean,
    ) {
        assertTrue(rig.drive.log.none { it.startsWith("conflict-copy:") }, "no conflict copy may be made: ${rig.drive.log}")
        assertEquals(
            names.map { "/moved/$it" }.sorted(),
            rig.drive.items.keys.filter { !folderInDrive || rig.drive.items[it]?.isFolder == false }.sorted(),
            "exactly one cloud item per child, at the new path: ${rig.drive.log}",
        )
        for (name in names) {
            assertNull(rig.db.getEntry("/move/$name"), "no row may be left at the old path of $name")
            val row = assertNotNull(rig.db.getEntry("/moved/$name"), "the row of $name lives at the new path")
            assertEquals(
                rig.drive.items["/moved/$name"]?.id,
                row.remoteId,
                "the row at the new path is the cloud item there (uploaded, not left pending): ${rig.drive.log}",
            )
        }
        assertTrue(
            rig.db.getAllEntries().none { it.path.contains("conflict") },
            "no conflict row may appear in state.db: ${rig.db.getAllEntries().map { it.path }}",
        )
    }

    @Test
    fun `a folder renamed while its children's uploads wait uploads them at the new paths`() = runTest {
        val rig = Rig(this)
        rig.seedFolder("/move")
        rig.drive.cloudFolder("/move")
        val names = listOf("f1.txt", "f2.txt", "f3.txt")
        rig.drive.failFirst += names.map { "/move/$it" }

        val completed = renameFolderWhileUploadsWait(rig, names)

        assertOneItemPerChildAtTheNewPath(rig, names, folderInDrive = true)
        assertEquals(names.size, completed.count { it.ok }, "every child's upload completes: $completed")
    }

    @Test
    fun `a never-uploaded folder renamed while its children's uploads wait uploads them at the new paths`() = runTest {
        val rig = Rig(this)
        rig.seedLocalFolder("/move")
        val names = listOf("f1.txt", "f2.txt", "f3.txt")
        rig.drive.failFirst += names.map { "/move/$it" }

        val completed = renameFolderWhileUploadsWait(rig, names)

        assertOneItemPerChildAtTheNewPath(rig, names, folderInDrive = false)
        assertEquals(names.size, completed.count { it.ok }, "every child's upload completes: $completed")
    }

    // archive#1217 case 2: the rename lands while an upload attempt is RUNNING (the provider holds it after reading the bytes,
    // before anything lands). The rename's ghost probe finds nothing, so the rename is local only; the attempt then
    // creates the item at the OLD path. The engine must move that item to the new path by its id: exactly one cloud
    // item, at the new path, the row there carrying its id, and no second upload.
    private suspend fun TestScope.renameWhileAttemptsRun(
        rig: Rig,
        renames: List<Pair<String, String>>,
        beforeRelease: () -> Unit = {},
    ): List<HydrationEvent.Completed> {
        rig.seedFolder("/move")
        rig.seedFolder("/move/sub")
        val completed = mutableListOf<HydrationEvent.Completed>()
        val collector = launch { rig.impl.events.collect { if (it is HydrationEvent.Completed) completed += it } }
        runCurrent()
        val gates = renames.map { (from, _) -> CompletableDeferred<Unit>().also { rig.drive.holdUpload[from] = it } }
        renames.forEachIndexed { i, (from, _) ->
            val cache = rig.seedWritten(from, "content-${from.substringAfterLast('/')}")
            launch { assertTrue(rig.impl.openForWrite("c", "h$i", from, cache, null) is OpenResult.Ok) }
        }
        // Every attempt starts and is now suspended inside the provider's upload.
        runCurrent()
        assertEquals(renames.size, rig.drive.log.count { it.startsWith("upload:") }, "every attempt is running: ${rig.drive.log}")
        for ((from, to) in renames) assertEquals(RenameResult.Ok, rig.impl.rename(from, to), "rename of $from")
        beforeRelease()
        gates.forEach { it.complete(Unit) }
        advanceUntilIdle()
        collector.cancel()
        return completed
    }

    private fun assertOneItemPerRenameAtItsNewPath(
        rig: Rig,
        renames: List<Pair<String, String>>,
    ) {
        assertTrue(rig.drive.log.none { it.startsWith("conflict-copy:") }, "no conflict copy may be made: ${rig.drive.log}")
        assertEquals(
            renames.size,
            rig.drive.log.count { it.startsWith("upload:") },
            "each file is uploaded once, never again at its new path: ${rig.drive.log}",
        )
        assertEquals(
            renames.map { it.second }.sorted(),
            rig.drive.items.keys.sorted(),
            "exactly one cloud item per file, at the new path, none left at the old one: ${rig.drive.log}",
        )
        for ((from, to) in renames) {
            assertNull(rig.db.getEntry(from), "no row may be left at the old path $from")
            val row = assertNotNull(rig.db.getEntry(to), "the row lives at the new path $to")
            assertEquals(rig.drive.items[to]?.id, row.remoteId, "the row at $to is the cloud item there: ${rig.drive.log}")
        }
    }

    @Test
    fun `a file renamed while its upload attempt runs ends as one cloud item at the new path`() = runTest {
        val rig = Rig(this)
        val renames = listOf("/move/f1.txt" to "/move/sub/f1.txt")

        val completed = renameWhileAttemptsRun(rig, renames)

        assertOneItemPerRenameAtItsNewPath(rig, renames)
        assertTrue(rig.drive.log.any { it.startsWith("moveById:") }, "the landed item is moved by its id: ${rig.drive.log}")
        val done = completed.single { it.handleId == "h0" }
        assertTrue(done.ok, "the renamed file's upload completes: $completed")
        assertEquals("/move/sub/f1.txt", done.path, "the completion names the new path: $completed")
    }

    @Test
    fun `files renamed and moved while their attempts run end as one cloud item each`() = runTest {
        val rig = Rig(this)
        val renames = listOf(
            "/move/a.txt" to "/move/sub/a.txt",
            "/move/b.txt" to "/move/b2.txt",
            "/move/c.txt" to "/move/sub/c3.txt",
        )

        val completed = renameWhileAttemptsRun(rig, renames)

        assertOneItemPerRenameAtItsNewPath(rig, renames)
        assertEquals(renames.size, completed.count { it.ok }, "every upload completes: $completed")
    }

    @Test
    fun `an edit written at the new path while the attempt ran is not taken for the uploaded bytes`() = runTest {
        val rig = Rig(this)
        val renames = listOf("/move/f1.txt" to "/move/sub/f1.txt")
        val sentLength = "content-f1.txt".length.toLong()

        renameWhileAttemptsRun(rig, renames) {
            // The file is written again at its new path before the attempt lands: these bytes were never sent.
            val cache = rig.engine.resolveCachePath("/move/sub/f1.txt")
            Files.writeString(cache, "a longer edit made after the rename")
            Files.setLastModifiedTime(cache, java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(60)))
        }

        assertOneItemPerRenameAtItsNewPath(rig, renames)
        val row = assertNotNull(rig.db.getEntry("/move/sub/f1.txt"))
        assertEquals(sentLength, row.localSize, "the row records the bytes that were sent, so the edit stays dirty: $row")
        assertEquals(sentLength, row.remoteSize, "the cloud item holds the sent bytes: $row")
    }

    @Test
    fun `a move that fails after the attempt landed is retried as a move, not as a second upload`() = runTest {
        val rig = Rig(this)
        val renames = listOf("/move/f1.txt" to "/move/sub/f1.txt")
        // The fake gives ids in order: the only upload's item is id-0.
        rig.drive.failMoveFirst += "id-0"

        val completed = renameWhileAttemptsRun(rig, renames)

        assertOneItemPerRenameAtItsNewPath(rig, renames)
        assertEquals(2, rig.drive.log.count { it.startsWith("moveById:id-0:") }, "the move is asked again: ${rig.drive.log}")
        assertTrue(completed.single { it.handleId == "h0" }.ok, "the upload completes after the retried move: $completed")
    }

    // The folder flavour of the running-attempt case: the FOLDER is renamed while its children's attempts run. Each
    // attempt then lands its item at the old path, and the engine must move it to the new one by its id.
    private suspend fun TestScope.renameFolderWhileAttemptsRun(
        rig: Rig,
        names: List<String>,
    ): List<HydrationEvent.Completed> {
        val completed = mutableListOf<HydrationEvent.Completed>()
        val collector = launch { rig.impl.events.collect { if (it is HydrationEvent.Completed) completed += it } }
        runCurrent()
        val gates = names.map { name -> CompletableDeferred<Unit>().also { rig.drive.holdUpload["/move/$name"] = it } }
        names.forEachIndexed { i, name ->
            val path = "/move/$name"
            val cache = rig.seedWritten(path, "content-$name")
            launch { assertTrue(rig.impl.openForWrite("c", "h$i", path, cache, null) is OpenResult.Ok) }
        }
        runCurrent()
        assertEquals(names.size, rig.drive.log.count { it.startsWith("upload:") }, "every attempt is running: ${rig.drive.log}")
        assertEquals(RenameResult.Ok, rig.impl.rename("/move", "/moved"), "rename of the folder")
        gates.forEach { it.complete(Unit) }
        advanceUntilIdle()
        collector.cancel()
        return completed
    }

    @Test
    fun `a folder renamed while its children's attempts run ends as one cloud item per child at the new path`() = runTest {
        val rig = Rig(this)
        rig.seedFolder("/move")
        rig.drive.cloudFolder("/move")
        val names = listOf("f1.txt", "f2.txt")

        val completed = renameFolderWhileAttemptsRun(rig, names)

        assertEquals(names.size, rig.drive.log.count { it.startsWith("upload:") }, "each child is uploaded once: ${rig.drive.log}")
        assertOneItemPerChildAtTheNewPath(rig, names, folderInDrive = true)
        assertEquals(names.size, completed.count { it.ok }, "every child's upload completes: $completed")
    }

    @Test
    fun `a never-uploaded folder renamed while its children's attempts run ends as one cloud item per child at the new path`() = runTest {
        val rig = Rig(this)
        rig.seedLocalFolder("/move")
        val names = listOf("f1.txt", "f2.txt")

        val completed = renameFolderWhileAttemptsRun(rig, names)

        assertEquals(names.size, rig.drive.log.count { it.startsWith("upload:") }, "each child is uploaded once: ${rig.drive.log}")
        assertOneItemPerChildAtTheNewPath(rig, names, folderInDrive = false)
        assertEquals(names.size, completed.count { it.ok }, "every child's upload completes: $completed")
    }

    // A child whose submitter still waits for a place in the upload queue (every place is taken) has no running
    // coroutine yet. The folder is renamed meanwhile: when the child gets its place it must upload at the new path
    // instead of finding its row gone and being given up until the next start.
    @Test
    fun `a child still waiting for a queue place when its folder is renamed uploads at the new path`() = runTest {
        val rig = Rig(this, uploadQueueDepth = 1)
        rig.seedFolder("/move")
        rig.drive.cloudFolder("/move")
        rig.seedFolder("/other")
        val completed = mutableListOf<HydrationEvent.Completed>()
        val collector = launch { rig.impl.events.collect { if (it is HydrationEvent.Completed) completed += it } }
        runCurrent()
        // /other/x.txt is uploading (held in the provider); a second upload of it waits for the path's turn and
        // takes the only waiting place.
        val gate = CompletableDeferred<Unit>().also { rig.drive.holdUpload["/other/x.txt"] = it }
        val xCache = rig.seedWritten("/other/x.txt", "content-x")
        launch { assertTrue(rig.impl.openForWrite("c", "hx1", "/other/x.txt", xCache, null) is OpenResult.Ok) }
        runCurrent()
        launch { assertTrue(rig.impl.openForWrite("c", "hx2", "/other/x.txt", xCache, null) is OpenResult.Ok) }
        runCurrent()
        // The child's submitter finds no place left and waits for one.
        val bCache = rig.seedWritten("/move/b.txt", "content-b")
        launch { assertTrue(rig.impl.openForWrite("c", "hb", "/move/b.txt", bCache, null) is OpenResult.Ok) }
        runCurrent()
        assertTrue(rig.drive.log.none { it.startsWith("upload:/move/b.txt") }, "the child has not started: ${rig.drive.log}")

        assertEquals(RenameResult.Ok, rig.impl.rename("/move", "/moved"), "rename of the folder")
        gate.complete(Unit)
        advanceUntilIdle()
        collector.cancel()

        val done = completed.single { it.handleId == "hb" }
        assertTrue(done.ok, "the waiting child's upload completes at the new path: $completed")
        assertEquals("/moved/b.txt", done.path)
        assertEquals(listOf("/moved/b.txt"), rig.drive.items.keys.filter { it.endsWith("b.txt") }, "one cloud item: ${rig.drive.log}")
        assertNull(rig.db.getEntry("/move/b.txt"), "no row at the old path")
        val row = assertNotNull(rig.db.getEntry("/moved/b.txt"))
        assertEquals(rig.drive.items["/moved/b.txt"]?.id, row.remoteId, "the row at the new path is the uploaded item: ${rig.drive.log}")
    }

    // gkrost/unidrive-cli#76 case 2: a child's first attempt landed its item but the answer was lost (a ghost), so its row is
    // still pending while the item sits in the cloud. The folder is renamed; the drive moves the item along with the folder.
    // The child's retry must meet that item (the row adopts it), not create the file a second time beside it.
    @Test
    fun `a ghost child of a renamed folder is adopted, not uploaded a second time`() = runTest {
        val rig = Rig(this)
        rig.seedFolder("/move")
        rig.drive.cloudFolder("/move")
        rig.drive.folderMoveCarriesChildren = true
        rig.drive.ghostFirst += "/move/g.txt"

        val completed = renameFolderWhileUploadsWait(rig, listOf("g.txt"))

        assertOneItemPerChildAtTheNewPath(rig, listOf("g.txt"), folderInDrive = true)
        assertTrue(
            rig.drive.log.none { it == "upload:/moved/g.txt(existing=none)" },
            "nothing may create the child a second time at its new path: ${rig.drive.log}",
        )
        assertTrue(completed.single { it.handleId == "h0" }.ok, "the child's upload completes: $completed")
    }

    // gkrost/unidrive-cli#171: the child the rename adopts names its bytes as the item does at once, before its retry runs, so
    // a client listing the folder meanwhile can tell the move from new bytes.
    @Test
    fun `a ghost child adopted by its folder's rename names the item's stored object`() = runTest {
        val rig = Rig(this)
        rig.seedFolder("/move")
        rig.drive.cloudFolder("/move")
        rig.drive.folderMoveCarriesChildren = true
        rig.drive.namesContent = true
        rig.drive.ghostFirst += "/move/g.txt"
        val cache = rig.seedWritten("/move/g.txt", "content-g.txt")
        launch { assertTrue(rig.impl.openForWrite("c", "h0", "/move/g.txt", cache, null) is OpenResult.Ok) }
        // The first attempt lands the item and loses the answer: the child waits in its backoff (virtual time does not move).
        runCurrent()

        assertEquals(RenameResult.Ok, rig.impl.rename("/move", "/moved"), "rename of the folder")

        val item = assertNotNull(rig.drive.items["/moved/g.txt"], "the drive moved the item with its folder: ${rig.drive.log}")
        val row = assertNotNull(rig.db.getEntry("/moved/g.txt"))
        assertEquals(item.id, row.remoteId, "the rename adopted the item: ${rig.drive.log}")
        assertEquals(assertNotNull(item.contentId), row.remoteContentId)
        advanceUntilIdle()
    }

    // gkrost/unidrive-cli#76 case 4: the slot of a child was opened under the decomposed (NFD) spelling of its path while the
    // folder is renamed under the composed (NFC) one; state.db keeps one spelling (NFC), so both name the same file.
    @Test
    fun `a child whose slot was opened under another unicode spelling follows its folder's rename`() = runTest {
        val rig = Rig(this)
        val oldFolderNfc = "/möve"
        val oldFolderNfd = "/möve"
        rig.seedFolder(oldFolderNfc)
        rig.drive.cloudFolder(oldFolderNfc)
        // The provider is asked under the spelling the upload was opened with.
        rig.drive.failFirst += listOf("$oldFolderNfc/f.txt", "$oldFolderNfd/f.txt")
        val completed = mutableListOf<HydrationEvent.Completed>()
        val collector = launch { rig.impl.events.collect { if (it is HydrationEvent.Completed) completed += it } }
        runCurrent()
        val cache = rig.seedWritten("$oldFolderNfc/f.txt", "content-f")
        launch { assertTrue(rig.impl.openForWrite("c", "h0", "$oldFolderNfd/f.txt", cache, null) is OpenResult.Ok) }
        runCurrent()

        assertEquals(RenameResult.Ok, rig.impl.rename(oldFolderNfc, "/moved"), "rename of the folder")
        advanceUntilIdle()
        collector.cancel()

        assertEquals(listOf("/moved/f.txt"), rig.drive.items.keys.filter { it.endsWith("f.txt") }, "one cloud item at the new path: ${rig.drive.log}")
        assertTrue(completed.single { it.handleId == "h0" }.ok, "the child's upload completes at the new path: $completed")
        assertEquals(rig.drive.items["/moved/f.txt"]?.id, rig.db.getEntry("/moved/f.txt")?.remoteId, "the row at the new path is the uploaded item")
    }
}
