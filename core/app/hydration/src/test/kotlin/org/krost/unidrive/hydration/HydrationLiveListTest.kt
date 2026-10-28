package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#27: while a profile's first enumeration has not completed, `hydration.list` reads a folder
 * state.db does not know yet from the provider, saves the rows the way the scan saves them, and answers with them.
 * The first scan of these tests is held on a gate (a drive that takes hours); the tests run on real time.
 */
class HydrationLiveListTest {
    private val base: Path = Files.createTempDirectory("ud-live-list")
    private val provider = MemProvider()
    private val db = StateDatabase(base.resolve("state.db")).also { it.initialize() }
    private var engine = newEngine(emptyList())
    private var hydration = HydrationImpl(engine, db)

    private fun newEngine(syncPaths: List<String>) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = Files.createDirectories(base.resolve("root")),
            cacheRoot = base.resolve("cache"),
            cacheKey = "profile",
            syncPaths = syncPaths,
            standingScope = syncPaths,
        )

    private fun scoped(vararg paths: String) {
        engine = newEngine(paths.toList())
        hydration = HydrationImpl(engine, db)
    }

    @AfterTest
    fun close() {
        provider.deltaGate?.complete(Unit)
        db.close()
        base.toFile().deleteRecursively()
    }

    private fun seedDrive() {
        provider.seedFolder("/docs")
        provider.seedFolder("/pics")
        provider.seed("/readme.txt", "hello".toByteArray())
        provider.seed("/docs/a.txt", "aaa".toByteArray())
        provider.seed("/docs/b.txt", "bbbb".toByteArray())
    }

    private suspend fun entries(path: String): List<ListResult.Entry> {
        val r = hydration.list(path)
        assertIs<ListResult.Ok>(r, "list of $path: $r")
        return r.entries
    }

    private suspend fun names(path: String) = entries(path).map { it.path }.sorted()

    private fun aliveRows(): Set<String> = db.listDirectChildren("").map { it.path }.toSet()

    private fun heldScan(): kotlinx.coroutines.Deferred<*> {
        val gate = CompletableDeferred<Unit>()
        provider.deltaGate = gate
        return kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async { MountEngine.over(engine).enumerateRemoteIntoState(false) }
    }

    private suspend fun finishScan(scan: kotlinx.coroutines.Deferred<*>) {
        provider.deltaGate!!.complete(Unit)
        withTimeout(TIMEOUT_MS) { scan.await() }
    }

    @Test
    fun `a folder state does not know lists live while the first scan runs, as cloud-only rows`() = runBlocking {
        seedDrive()
        val scan = heldScan()

        val root = entries("/")

        assertEquals(listOf("/docs", "/pics", "/readme.txt"), root.map { it.path }.sorted())
        val readme = root.single { it.path == "/readme.txt" }
        assertFalse(readme.isHydrated, "a live row is cloud-only")
        assertEquals(5L, readme.size)
        assertNotNull(readme.remoteId)
        assertNotNull(readme.remoteModifiedEpochMillis)
        assertTrue(root.single { it.path == "/docs" }.isFolder)
        assertEquals(listOf("/"), provider.listCalls.toList(), "one call for the folder")
        assertEquals(3, db.listDirectChildren("").size, "the rows exist afterwards")
        scan.cancel()
    }

    @Test
    fun `a folder below a live-listed folder lists live too, one call each`() = runBlocking {
        seedDrive()
        val scan = heldScan()

        entries("/")
        val docs = entries("/docs")

        assertEquals(listOf("/docs/a.txt", "/docs/b.txt"), docs.map { it.path }.sorted())
        assertEquals(listOf("/", "/docs"), provider.listCalls.toList())
        scan.cancel()
    }

    @Test
    fun `a folder state does not have a row for is not asked for`() = runBlocking {
        seedDrive()
        val scan = heldScan()

        assertEquals(emptyList(), names("/docs"), "the parent chain has not been seen in the cloud")
        assertEquals(emptyList(), provider.listCalls.toList())
        scan.cancel()
    }

    @Test
    fun `a known folder answers from state and never calls the provider`() = runBlocking {
        seedDrive()
        val scan = heldScan()
        entries("/")
        entries("/docs")
        provider.listCalls.clear()

        val again = entries("/")
        val docsAgain = entries("/docs")

        assertEquals(3, again.size)
        assertEquals(2, docsAgain.size)
        assertEquals(emptyList(), provider.listCalls.toList(), "the rows are state now")
        scan.cancel()
    }

    @Test
    fun `a folder with cloud rows already in state is answered from state`() = runBlocking {
        db.upsertEntry(row("/old.txt", remoteId = "id-old"))
        provider.seed("/new.txt", "x".toByteArray())
        val scan = heldScan()

        assertEquals(listOf("/old.txt"), names("/"))
        assertEquals(emptyList(), provider.listCalls.toList())
        scan.cancel()
    }

    @Test
    fun `once the first scan has completed an empty folder is empty without a provider call`() = runBlocking {
        provider.seedFolder("/docs")
        provider.seed("/docs/a.txt", "a".toByteArray())
        MountEngine.over(engine).enumerateRemoteIntoState(false)
        db.setSyncState("delta_cursor", "cursor")
        provider.listCalls.clear()
        db.upsertEntry(row("/empty", remoteId = "id-empty", folder = true))

        assertEquals(emptyList(), names("/empty"))
        assertEquals(listOf("/docs", "/docs/a.txt", "/empty"), db.getEntriesByPrefix("/").map { it.path }.sorted(), "no row was written")
        assertEquals(emptyList(), provider.listCalls.toList())
    }

    @Test
    fun `a failing live listing fails the list, writes no row and the next list asks again`() = runBlocking {
        seedDrive()
        val scan = heldScan()
        provider.listFailure = java.io.IOException("gateway timeout")

        val failed = hydration.list("/")
        assertIs<ListResult.Failed>(failed)
        assertEquals(0, db.listDirectChildren("").size, "no row from a failed listing")

        provider.listFailure = null
        assertEquals(3, entries("/").size)
        assertEquals(listOf("/", "/"), provider.listCalls.toList())
        scan.cancel()
    }

    @Test
    fun `a folder the cloud no longer has answers from state`() = runBlocking {
        seedDrive()
        val scan = heldScan()
        entries("/")
        provider.listFailure = FileNotFoundException("gone")
        db.listDirectChildren("/docs") // nothing below yet

        assertEquals(emptyList(), names("/docs"))
        scan.cancel()
    }

    @Test
    fun `children outside the sync scope are not written and the ancestors of the scope still list`() = runBlocking {
        provider.seedFolder("/_INBOX")
        provider.seedFolder("/other")
        provider.seed("/readme.txt", "x".toByteArray())
        provider.seed("/_INBOX/in.txt", "i".toByteArray())
        scoped("/_INBOX")
        val scan = heldScan()

        assertEquals(listOf("/_INBOX"), names("/"), "only the scope's folder, not its siblings")
        assertEquals(listOf("/_INBOX/in.txt"), names("/_INBOX"))
        assertEquals(setOf("/_INBOX"), aliveRows())
        scan.cancel()
    }

    @Test
    fun `an excluded name lists, flagged excluded, like the scan's rows`() = runBlocking {
        provider.seed("/scratch.tmp", "x".toByteArray())
        engine = SyncEngine(
            provider = provider,
            db = db,
            syncRoot = Files.createDirectories(base.resolve("root")),
            cacheRoot = base.resolve("cache"),
            cacheKey = "profile",
            excludePatterns = listOf("*.tmp"),
        )
        hydration = HydrationImpl(engine, db)
        val scan = heldScan()

        val tmp = entries("/").single()

        assertTrue(tmp.excluded)
        scan.cancel()
    }

    @Test
    fun `a folder of 1001 children lists whole`() = runBlocking {
        for (i in 1..1001) provider.seed("/f%04d.txt".format(i), ByteArray(i % 7))
        val scan = heldScan()

        assertEquals(1001, entries("/").size)
        assertEquals(1001, db.listDirectChildren("").size)
        scan.cancel()
    }

    @Test
    fun `a pending upload does not hide its cloud siblings and keeps its name`() = runBlocking {
        provider.seed("/a.txt", "cloud-a".toByteArray())
        provider.seed("/b.txt", "cloud-b".toByteArray())
        db.upsertEntry(row("/a.txt", remoteId = null, size = 3, hydrated = true))
        val scan = heldScan()

        val listed = entries("/").associateBy { it.path }

        assertEquals(setOf("/a.txt", "/b.txt"), listed.keys)
        assertTrue(listed.getValue("/a.txt").pendingUpload, "the local file stays its own")
        assertEquals(null, listed.getValue("/a.txt").remoteId)
        assertEquals("id-/b.txt", listed.getValue("/b.txt").remoteId)
        scan.cancel()
    }

    @Test
    fun `two items of one name are left to the scan`() = runBlocking {
        provider.seed("/dup.txt", "1".toByteArray())
        provider.seed("/solo.txt", "2".toByteArray())
        val one = provider.items.getValue("/dup.txt")
        provider.listOverride = listOf(one, one.copy(id = "other-id"), provider.items.getValue("/solo.txt"))
        val scan = heldScan()

        assertEquals(listOf("/solo.txt"), names("/"))
        scan.cancel()
    }

    @Test
    fun `an item deleted or in the provider trash is not listed`() = runBlocking {
        provider.seed("/live.txt", "1".toByteArray())
        provider.seed("/dead.txt", "2".toByteArray())
        provider.seed("/binned.txt", "3".toByteArray())
        provider.listOverride =
            listOf(
                provider.items.getValue("/live.txt"),
                provider.items.getValue("/dead.txt").copy(deleted = true),
                provider.items.getValue("/binned.txt").copy(trashed = true),
            )
        val scan = heldScan()

        assertEquals(listOf("/live.txt"), names("/"))
        scan.cancel()
    }

    @Test
    fun `calls for one folder at once make one provider call`() = runBlocking {
        seedDrive()
        val scan = heldScan()

        val lists = (1..8).map { async(Dispatchers.Default) { entries("/") } }.awaitAll()

        assertTrue(lists.all { it.size == 3 })
        assertEquals(listOf("/"), provider.listCalls.toList())
        scan.cancel()
    }

    @Test
    fun `a folder the cloud holds empty is not asked again within the ttl`() = runBlocking {
        provider.seedFolder("/void")
        val scan = heldScan()
        entries("/")

        assertEquals(emptyList(), names("/void"))
        assertEquals(emptyList(), names("/void"))

        assertEquals(listOf("/", "/void"), provider.listCalls.toList())
        scan.cancel()
    }

    @Test
    fun `the scan that completes behind a live listing leaves one row per child and reaps nothing`() = runBlocking {
        seedDrive()
        val scan = heldScan()
        entries("/")
        entries("/docs")
        val before = db.getEntryCount()

        finishScan(scan)

        assertEquals(before, db.getEntryCount(), "the scan wrote the same rows, no more, no fewer")
        assertEquals(listOf("/docs", "/pics", "/readme.txt"), names("/"))
        assertEquals(listOf("/docs/a.txt", "/docs/b.txt"), names("/docs"))
        assertEquals(5L, entries("/").single { it.path == "/readme.txt" }.size)
    }

    @Test
    fun `a live listing racing the scan's merge ends with one row per child`() = runBlocking {
        for (i in 1..40) provider.seed("/f$i.txt", ByteArray(i))
        val scan = heldScan()
        provider.deltaGate!!.complete(Unit)

        val listing = (1..4).map { async(Dispatchers.Default) { entries("/") } }
        withTimeout(TIMEOUT_MS) { scan.await() }
        listing.awaitAll()
        db.setSyncState("delta_cursor", "cursor")

        assertEquals(40, db.listDirectChildren("").size)
        assertEquals(40, entries("/").size)
    }

    private fun row(
        path: String,
        remoteId: String?,
        folder: Boolean = false,
        size: Long = 1,
        hydrated: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = size,
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = null,
        localSize = null,
        isFolder = folder,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.now(),
    )

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}
