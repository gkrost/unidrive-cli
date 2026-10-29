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
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-path upload slot must never be lost out from under a submitter (#210): the last release's
 * decrement and its map removal were two steps, so a submit whose `compute` landed between them joined
 * the dying slot and then held an entry the map no longer had — the next submit created a fresh slot,
 * and two uploads of one path could run at once. A submit landing inside the release window (the
 * [HydrationImpl.slotReleaseWindow] seam) must end up on a slot that stays in the map until its own
 * release removes it.
 */
class UploadSlotReleaseRaceTest {

    private class GatedProvider : CloudProvider {
        override val id = "fake-slot-release-race"
        override val displayName = "Fake (slot release race test)"
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
                return CloudItem(
                    id = "up-$remotePath",
                    name = remotePath.substringAfterLast('/'),
                    path = remotePath,
                    size = content.length.toLong(),
                    isFolder = false,
                    modified = Instant.now(),
                    created = null,
                    hash = "h-${content.length}",
                    mimeType = null,
                )
            } finally {
                inFlight.merge(remotePath, -1, Int::plus)
            }
        }

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")
        override suspend fun createFolder(path: String): CloudItem = error("not used")
        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")
        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)
        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private class Rig {
        val provider = GatedProvider()
        val db = StateDatabase(dbPath = Files.createTempDirectory("unidrive-slot-db").resolve("state.db"), inMemory = true)
            .also { it.initialize() }
        val engine = SyncEngine(
            provider = provider,
            db = db,
            syncRoot = Files.createTempDirectory("unidrive-slot-sync"),
            cacheRoot = Files.createTempDirectory("unidrive-slot-cache"),
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

        fun seedLocalOnly(path: String, content: String): Path {
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
        "log=${synchronized(rig.provider.log) { rig.provider.log.toList() }} completed=${synchronized(rig.completedHandles) { rig.completedHandles.toList() }} slot=${rig.impl.hasUploadSlot("/a.txt")}"

    @Test
    fun `a submit landing in the last release's window keeps a mapped slot`() {
        val rig = Rig()
        val impl = rig.impl
        val cache = rig.seedLocalOnly("/a.txt", "NEW")

        // The first upload parks in its gate holding the path's slot; opening the gate lets its finally run
        // the last release — which stops inside the seam, with the removal not yet decided.
        val enteredA = CountDownLatch(1)
        val gateA = CountDownLatch(1)
        rig.provider.nextCall(enteredA, gateA)
        thread(name = "submit-a") {
            runBlocking(Dispatchers.IO) { impl.openForWrite("c", "hA", "/a.txt", cache) }
        }
        assertTrue(enteredA.await(10, TimeUnit.SECONDS), "the first upload never reached its gate: ${state(rig)}")

        val releaseReachedWindow = CountDownLatch(1)
        val removalMayProceed = CountDownLatch(1)
        val seamArmed = AtomicBoolean(false)
        impl.slotReleaseWindow = {
            if (seamArmed.compareAndSet(false, true)) {
                releaseReachedWindow.countDown()
                removalMayProceed.await()
            }
        }

        // The second call's gate must be queued before the racing submit's worker can reach the provider.
        val enteredC = CountDownLatch(1)
        val gateC = CountDownLatch(1)
        rig.provider.nextCall(enteredC, gateC)

        gateA.countDown() // the first upload returns; its finally decrements to zero and enters the window
        assertTrue(releaseReachedWindow.await(20, TimeUnit.SECONDS), "the last release never reached the window: ${state(rig)}")

        // The racing submit: its compute joins the slot while its count is zero and the removal is still
        // undecided; its worker then parks in the second gate — enteredC cannot fire before the compute ran.
        thread(name = "submit-c") {
            runBlocking(Dispatchers.IO) { impl.openForWrite("c", "hC", "/a.txt", cache) }
        }
        assertTrue(enteredC.await(20, TimeUnit.SECONDS), "the racing submit's worker never reached its gate: ${state(rig)}")

        removalMayProceed.countDown()
        awaitCompleted(rig, "hA")

        assertTrue(
            impl.hasUploadSlot("/a.txt"),
            "the racing submit holds a slot the map has lost: the removal ran although its count was raised again",
        )

        // The racing submit's own upload runs, finishes, and its release cleans the map at zero.
        gateC.countDown()
        assertTrue(awaitTrue { synchronized(rig.provider.log) { rig.provider.log.size == 2 } }, "both uploads must run: ${rig.provider.log}")
        awaitCompleted(rig, "hC")
        assertTrue(awaitTrue { !impl.hasUploadSlot("/a.txt") }, "the slot must be gone once the last upload released it")
        assertEquals(emptyList(), rig.provider.overlaps.toList(), "two uploads of one path ran at once")

        rig.dispose()
    }
}
