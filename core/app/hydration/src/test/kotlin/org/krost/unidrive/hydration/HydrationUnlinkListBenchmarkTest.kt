package org.krost.unidrive.hydration

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.engine.CursorState
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#585: the recorded benchmark of unlinking never-uploaded rows (`remote_id` null, a cache copy,
 * nothing on the cloud) and of listing a folder that holds them, phase by phase, on a counting fake provider.
 * Asserts the operation counts the timings are attributed with (no probe of a row that records no upload was submitted, the
 * retained probe of an includable one, one live listing per TTL window); the wall-clock medians are printed as
 * `BENCH` lines and live in the PR, not asserted — a per-call timing test would be brittle. Run with:
 * `./gradlew :app:hydration:test --tests 'org.krost.unidrive.hydration.HydrationUnlinkListBenchmarkTest'`
 */
class HydrationUnlinkListBenchmarkTest {
    private val folder = "/t"
    private val rowsBig = 4300
    private val rowsMid = 2500
    private val rowsSmall = 500
    private val rowsRtt = 200

    private val provider = BenchProvider()
    private val base: Path = Files.createTempDirectory("ud-585-bench")
    private val db = StateDatabase(base.resolve("state.db")).also { it.initialize() }
    private val engine =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = Files.createDirectories(base.resolve("root")),
            cacheRoot = base.resolve("cache"),
            cacheKey = "bench",
        )
    private val hydration = HydrationImpl(engine, db)
    private val mount = MountEngine.over(engine)

    private var seeded = 0

    @AfterTest
    fun close() {
        db.close()
        base.toFile().deleteRecursively()
    }

    /** A file row the mount created and nobody uploaded (`~$` name, one cache byte), as the issue's repro leaves them. */
    private fun seedNeverUploaded(count: Int) {
        provider.seedFolder(folder)
        val now = Instant.now().toEpochMilli()
        db.batch {
            db.upsertEntry(
                SyncEntry(
                    path = folder,
                    remoteId = "id-t",
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = Instant.now(),
                    localMtime = now,
                    localSize = 0L,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            for (i in seeded until seeded + count) {
                db.upsertEntry(
                    SyncEntry(
                        path = "$folder/~\$b$i.tmp",
                        remoteId = null,
                        remoteHash = null,
                        remoteSize = 0L,
                        remoteModified = null,
                        localMtime = now,
                        localSize = 1L,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = Instant.now(),
                    ),
                )
                db.markNeverSubmitted("$folder/~\$b$i.tmp") // what the create of an excluded name leaves
                val cache = engine.resolveCachePath("$folder/~\$b$i.tmp")
                Files.createDirectories(cache.parent)
                Files.write(cache, byteArrayOf(1))
            }
        }
        seeded += count
    }

    private fun paths(): List<String> = (0 until seeded).map { "$folder/~\$b$it.tmp" }

    private fun report(
        phase: String,
        ns: List<Long>,
    ) {
        val sorted = ns.sorted()
        val median = sorted[sorted.size / 2] / 1_000_000.0
        val mean = ns.average() / 1_000_000.0
        val p90 = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)] / 1_000_000.0
        println("BENCH|$phase|n=${ns.size}|median_ms=%.3f|mean_ms=%.3f|p90_ms=%.3f".format(median, mean, p90))
    }

    @Test
    fun `unlink phases`() = runBlocking {
        seedNeverUploaded(rowsBig)

        // The whole verb, per call, with a 0 ms fake remote: whatever this costs is local work only.
        val whole = mutableListOf<Long>()
        for (path in paths()) {
            val t0 = System.nanoTime()
            val r = hydration.unlink(path, false)
            whole += System.nanoTime() - t0
            assertIs<UnlinkResult.Ok>(r, "unlink of $path: $r")
        }
        report("unlink_whole_4300_localonly", whole)
        assertEquals(0, provider.metadataCalls.get(), "an excluded never-failed row owes no remote probe (gkrost/unidrive-cli#585)")
        assertEquals(0, provider.deleteCalls.get(), "nothing is on the cloud, so no cloud delete")

        // The same verb on a small sibling set: a median that grows with the sibling count scales with siblings.
        val small = freshSmall()
        val wholeSmall = mutableListOf<Long>()
        for (path in small.second) {
            val t0 = System.nanoTime()
            assertIs<UnlinkResult.Ok>(small.first.unlink(path, false))
            wholeSmall += System.nanoTime() - t0
        }
        report("unlink_whole_500_localonly", wholeSmall)

        // One attributed remote round trip, modelled on an includable row (the probe the fix retains):
        // its cost is the latency the fake adds.
        val latency = 200L
        provider.latencyMs = latency
        val withRtt = mutableListOf<Long>()
        for (i in 0 until rowsSmall) {
            val path = "$folder/xr$i.deb"
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = null,
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = null,
                    localMtime = Instant.now().toEpochMilli(),
                    localSize = 1L,
                    isFolder = false,
                    isPinned = false,
                    isHydrated = true,
                    lastSynced = Instant.now(),
                ),
            )
            val t0 = System.nanoTime()
            val r = hydration.unlink(path, false)
            withRtt += System.nanoTime() - t0
            assertIs<UnlinkResult.Ok>(r)
        }
        provider.latencyMs = 0
        report("unlink_whole_500_probe_rtt_200ms", withRtt)

        // The rows this change is about, at the same latency: an excluded row that records no upload was ever submitted
        // owes no probe; the same row without the record (older build, other path of creation) keeps it.
        provider.latencyMs = latency
        for (recorded in listOf(true, false)) {
            val ns = mutableListOf<Long>()
            val before = provider.metadataCalls.get()
            for (i in 0 until rowsRtt) {
                val path = "$folder/~\$x${if (recorded) "r" else "u"}$i.tmp"
                db.upsertEntry(
                    SyncEntry(
                        path = path,
                        remoteId = null,
                        remoteHash = null,
                        remoteSize = 0L,
                        remoteModified = null,
                        localMtime = Instant.now().toEpochMilli(),
                        localSize = 1L,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = Instant.now(),
                    ),
                )
                if (recorded) db.markNeverSubmitted(path)
                val t0 = System.nanoTime()
                assertIs<UnlinkResult.Ok>(hydration.unlink(path, false))
                ns += System.nanoTime() - t0
            }
            report("unlink_whole_${rowsRtt}_excluded_${if (recorded) "recorded_never_submitted" else "no_record"}_rtt_200ms", ns)
            assertEquals(if (recorded) 0 else rowsRtt, provider.metadataCalls.get() - before, "provider probes (recorded=$recorded)")
        }
        provider.latencyMs = 0
        small.third.close()
    }

    @Test
    fun `unlink local phases alone`() = runBlocking {
        seedNeverUploaded(rowsSmall)

        val probe = mutableListOf<Long>()
        for (path in paths()) {
            val t0 = System.nanoTime()
            mount.ghostItemOrNull(path)
            probe += System.nanoTime() - t0
        }
        report("unlink_phase_ghost_probe", probe)
        assertEquals(paths().size, provider.metadataCalls.get())

        val slot = paths().map { timed { hydration.hasUploadSlot(it) } }
        report("unlink_phase_upload_slot_check", slot)

        val getEntry = paths().map { timed { db.getEntry(it) } }
        report("unlink_phase_db_getEntry", getEntry)

        val cacheDelete =
            paths().map { path ->
                timed { Files.deleteIfExists(engine.resolveCachePath(path)) }
            }
        report("unlink_phase_cache_file_delete", cacheDelete)

        val dbDelete = paths().map { timed { db.deleteEntry(it) } }
        report("unlink_phase_db_deleteEntry", dbDelete)
    }

    @Test
    fun `list phases`() = runBlocking {
        seedNeverUploaded(rowsMid)

        // While the first enumeration has not completed (empty delta cursor) a list enters the live listing once.
        val first = mutableListOf<Long>()
        first += timed { hydration.list(folder) }
        report("list_whole_first_call_livecursor", first)
        assertTrue(provider.listCalls.get() >= 1, "the first call lists the folder live")

        val live =
            (0 until 2).map {
                timed { hydration.list(folder) }
            }
        report("list_whole_within_emptyttl", live)
        println("BENCH|list_provider_listChildren_calls=${provider.listCalls.get()} (each call outlives the 10s empty TTL)")

        val liveAlone =
            (0 until 3).map {
                timed { mount.listFolderLive(folder) }
            }
        report("listFolderLive_alone", liveAlone)

        val listedBeforeCursor = provider.listCalls.get()
        CursorState(db).setDeltaCursor("c")
        val cursor =
            (0 until 5).map {
                timed { hydration.list(folder) }
            }
        report("list_whole_scan_complete", cursor)
        assertEquals(listedBeforeCursor, provider.listCalls.get(), "a completed enumeration answers from state only")

        val direct =
            (0 until 10).map {
                timed { db.listDirectChildren(folder) }
            }
        report("list_phase_listDirectChildren", direct)

        val cloudChild =
            (0 until 10).map {
                timed { db.hasCloudChild(folder) }
            }
        report("list_phase_hasCloudChild", cloudChild)

        val blocked =
            (0 until 10).map {
                timed { db.blockedRows() }
            }
        report("list_phase_blockedRows", blocked)

        val excluded = paths().map { timed { mount.isExcludedPath(it, false) } }
        report("list_phase_isExcludedPath_per_row", excluded)

        val size =
            paths().map { path ->
                timed { Files.size(engine.resolveCachePath(path)) }
            }
        report("list_phase_cache_stat_per_row_resolve_each", size)

        val cachePass = org.krost.unidrive.engine.CachePaths.Pass(engine.resolveCachePath("/"))
        val passSize = paths().map { path -> timed { cachePass.sizeOf(path) } }
        report("list_phase_cache_stat_per_row_one_pass", passSize)

        // Small sibling set, for the per-row scaling of the state-only answer.
        val small = freshSmall()
        CursorState(small.third).setDeltaCursor("c")
        val smallList =
            (0 until 5).map {
                timed { small.first.list(folder) }
            }
        report("list_whole_scan_complete_500", smallList)
        val smallDirect =
            (0 until 10).map {
                timed { small.third.listDirectChildren(folder) }
            }
        report("list_phase_listDirectChildren_500", smallDirect)
        small.third.close()
    }

    private suspend inline fun timed(crossinline block: suspend () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return System.nanoTime() - t0
    }

    // A second, throw-away stack for the sibling-count comparisons (its own db, engine and hydration).
    private fun freshSmall(): Triple<HydrationImpl, List<String>, StateDatabase> {
        val p = BenchProvider()
        val b = Files.createTempDirectory("ud-585-small")
        val d = StateDatabase(b.resolve("state.db")).also { it.initialize() }
        val e =
            SyncEngine(
                provider = p,
                db = d,
                syncRoot = Files.createDirectories(b.resolve("root")),
                cacheRoot = b.resolve("cache"),
                cacheKey = "bench",
            )
        val h = HydrationImpl(e, d)
        val now = Instant.now().toEpochMilli()
        d.batch {
            d.upsertEntry(
                SyncEntry(
                    path = folder,
                    remoteId = "id-t",
                    remoteHash = null,
                    remoteSize = 0L,
                    remoteModified = Instant.now(),
                    localMtime = now,
                    localSize = 0L,
                    isFolder = true,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
            for (i in 0 until rowsSmall) {
                d.upsertEntry(
                    SyncEntry(
                        path = "$folder/~\$b$i.tmp",
                        remoteId = null,
                        remoteHash = null,
                        remoteSize = 0L,
                        remoteModified = null,
                        localMtime = now,
                        localSize = 1L,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = Instant.now(),
                    ),
                )
                d.markNeverSubmitted("$folder/~\$b$i.tmp")
                val cache = e.resolveCachePath("$folder/~\$b$i.tmp")
                Files.createDirectories(cache.parent)
                Files.write(cache, byteArrayOf(1))
            }
        }
        return Triple(h, (0 until rowsSmall).map { "$folder/~\$b$it.tmp" }, d)
    }
}

/** The counting fake: an empty remote that answers the folder and not-found for everything else, with an optional round-trip latency. */
private class BenchProvider : CloudProvider {
    override val id = "bench"
    override val displayName = "Bench"
    override var isAuthenticated = true

    val metadataCalls = AtomicInteger()
    val listCalls = AtomicInteger()
    val deleteCalls = AtomicInteger()

    @Volatile var latencyMs: Long = 0

    private val remote = linkedMapOf<String, CloudItem>()

    fun seedFolder(path: String) {
        remote[path] =
            CloudItem(
                id = "id-$path",
                name = path.substringAfterLast('/'),
                path = path,
                size = 0,
                isFolder = true,
                modified = Instant.parse("2026-03-28T12:00:00Z"),
                created = Instant.parse("2026-03-28T12:00:00Z"),
                hash = null,
                mimeType = null,
            )
    }

    private suspend fun rtt() {
        if (latencyMs > 0) delay(latencyMs)
    }

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
    override suspend fun authenticate() {}

    override suspend fun listChildren(path: String): List<CloudItem> {
        listCalls.incrementAndGet()
        rtt()
        val parent = path.trimEnd('/')
        return remote.values.filter { !it.isFolder && it.path.substringBeforeLast('/') == parent }
    }

    override suspend fun getMetadata(path: String): CloudItem {
        metadataCalls.incrementAndGet()
        rtt()
        return remote[path] ?: throw ProviderException("Item not found: $path")
    }

    override suspend fun delete(
        remotePath: String,
        ifMatchETag: String?,
    ) {
        deleteCalls.incrementAndGet()
        rtt()
        remote.remove(remotePath)
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long = error("not used")

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem = error("not used")

    override suspend fun createFolder(path: String): CloudItem = error("not used")

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem = error("not used")

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((Int) -> Unit)?,
        scanContext: org.krost.unidrive.ScanContext?,
    ): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)

    override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
}
