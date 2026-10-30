package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rename half of #210 (#234): a rename's re-key finds the moved slot's count at zero (its last upload has just
 * ended) and removes the entry it put under the new path. A submit for the new path that joins the slot between that
 * check and the removal must keep a mapped slot — removed by identity alone, its upload would run outside the map,
 * invisible to the busy guard, the cancel and the next submit.
 */
class UploadSlotRekeyRaceTest {

    private class GatedProvider : CloudProvider {
        override val id = "fake-slot-rekey-race"
        override val displayName = "Fake (slot rekey race test)"
        override var isAuthenticated = true

        val log = mutableListOf<String>()
        val overlaps = mutableListOf<String>()
        private val inFlight = ConcurrentHashMap<String, Int>()
        // One gate pair per upload call, in call order; a call without a pass gate runs at once.
        private val enteredGates = ConcurrentLinkedQueue<CountDownLatch>()
        private val passGates = ConcurrentLinkedQueue<CountDownLatch>()

        fun nextCall(entered: CountDownLatch, pass: CountDownLatch?) {
            enteredGates.add(entered)
            passGates.add(pass ?: CountDownLatch(0))
        }

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()
        override suspend fun getMetadata(path: String): CloudItem = throw ProviderException("Item not found: $path")
        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            enteredGates.poll().countDown()
            passGates.poll().await()
            val running = inFlight.merge(remotePath, 1, Int::plus)!!
            if (running > 1) synchronized(overlaps) { overlaps.add(remotePath) }
            try {
                val content = Files.readString(localPath)
                synchronized(log) { log.add("upload:$remotePath=$content") }
                return item(remotePath, content.length.toLong(), "h-${content.length}")
            } finally {
                inFlight.merge(remotePath, -1, Int::plus)
            }
        }

        override suspend fun move(fromPath: String, toPath: String): CloudItem {
            synchronized(log) { log.add("move:$fromPath->$toPath") }
            return item(toPath, 0L, null)
        }

        private fun item(path: String, size: Long, hash: String?) = CloudItem(
            id = "id-$path",
            name = path.substringAfterLast('/'),
            path = path,
            size = size,
            isFolder = false,
            modified = Instant.now(),
            created = null,
            hash = hash,
            mimeType = null,
        )

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")
        override suspend fun createFolder(path: String): CloudItem = error("not used")
        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)
        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private class Rig {
        val provider = GatedProvider()
        val db = StateDatabase(dbPath = Files.createTempDirectory("unidrive-rekey-db").resolve("state.db"), inMemory = true)
            .also { it.initialize() }
        val engine = SyncEngine(
            provider = provider,
            db = db,
            syncRoot = Files.createTempDirectory("unidrive-rekey-sync"),
            cacheRoot = Files.createTempDirectory("unidrive-rekey-cache"),
        )
        val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val impl = HydrationImpl(engine, db, recoveryUploadScope = uploadScope)
        val completedHandles = mutableListOf<String>()

        init {
            uploadScope.launch {
                impl.events.collect {
                    if (it is HydrationEvent.Completed) synchronized(completedHandles) { completedHandles.add(it.handleId) }
                }
            }
        }

        // A file that already lives in the cloud, with an edit in its cache: the rename takes the cloud-move path, which
        // moves the row and the cache before it re-keys the slot.
        fun seedUploadedWithEdit(path: String, content: String): Path {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = "rid-$path",
                    remoteHash = "h-old",
                    remoteSize = 3L,
                    remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            val cache = engine.resolveCachePath(path)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, content)
            return cache
        }

        fun dispose() {
            uploadScope.cancel()
            db.close()
        }
    }

    private fun awaitTrue(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun awaitCompleted(rig: Rig, handleId: String) {
        assertTrue(awaitTrue { synchronized(rig.completedHandles) { handleId in rig.completedHandles } }, "no Completed for $handleId")
    }

    private fun state(rig: Rig) =
        "log=${synchronized(rig.provider.log) { rig.provider.log.toList() }} completed=${synchronized(rig.completedHandles) { rig.completedHandles.toList() }} " +
            "src=${rig.impl.hasUploadSlot("/src.txt")} dst=${rig.impl.hasUploadSlot("/dst.txt")}"

    @Test
    fun `a submit for the new path landing in the rekey window keeps a mapped slot`() {
        val rig = Rig()
        val impl = rig.impl
        val cache = rig.seedUploadedWithEdit("/src.txt", "EDIT")

        // The first upload parks in its gate holding the slot of /src.txt.
        val enteredA = CountDownLatch(1)
        val gateA = CountDownLatch(1)
        rig.provider.nextCall(enteredA, gateA)
        thread(name = "submit-a") {
            runBlocking(Dispatchers.IO) { impl.openForWrite("c", "hA", "/src.txt", cache) }
        }
        assertTrue(enteredA.await(10, TimeUnit.SECONDS), "the first upload never reached its gate: ${state(rig)}")

        // Its last release runs the rename inside the release window, so the re-key moves a slot whose count is zero;
        // inside the rekey window a submit for the new path joins it before the removal is decided.
        val renameResult = AtomicReference<RenameResult>()
        val releaseArmed = AtomicBoolean(false)
        impl.slotReleaseWindow = {
            if (releaseArmed.compareAndSet(false, true)) {
                renameResult.set(runBlocking { impl.rename("/src.txt", "/dst.txt") })
            }
        }
        val rekeyArmed = AtomicBoolean(false)
        impl.slotRekeyWindow = {
            if (rekeyArmed.compareAndSet(false, true)) {
                val dstCache = rig.engine.resolveCachePath("/dst.txt")
                if (!Files.exists(dstCache)) {
                    Files.createDirectories(dstCache.parent)
                    Files.writeString(dstCache, "EDIT")
                }
                // Returns once the submit's compute has joined the slot and its worker is launched.
                runBlocking { impl.openForWrite("c", "hC", "/dst.txt", dstCache) }
            }
        }

        // The racing submit's worker parks in the second gate, holding the joined slot's count.
        val enteredC = CountDownLatch(1)
        val gateC = CountDownLatch(1)
        rig.provider.nextCall(enteredC, gateC)

        gateA.countDown()
        assertTrue(enteredC.await(20, TimeUnit.SECONDS), "the racing submit's worker never reached its gate: ${state(rig)}")
        awaitCompleted(rig, "hA")
        assertTrue(rekeyArmed.get(), "the re-key never found the moved slot dying: ${state(rig)}")
        assertEquals(RenameResult.Ok, renameResult.get(), state(rig))

        assertTrue(
            impl.hasUploadSlot("/dst.txt"),
            "the racing submit holds a slot the map has lost: the re-key removed it although its count was raised again",
        )

        // The racing submit's own upload finishes, and its release cleans the map at zero.
        gateC.countDown()
        awaitCompleted(rig, "hC")
        assertTrue(awaitTrue { !impl.hasUploadSlot("/dst.txt") }, "the slot must be gone once the last upload released it")
        assertEquals(emptyList(), rig.provider.overlaps.toList(), "two uploads of one path ran at once")

        rig.dispose()
    }
}
