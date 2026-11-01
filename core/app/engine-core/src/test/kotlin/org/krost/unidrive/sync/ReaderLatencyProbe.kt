package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/**
 * The reader latency harness of the state database: a writer replays a merge over every row of a temp `state.db` (per
 * row the lookup by remote path and the upsert, [CHUNK_ROWS] rows per `batch`, as the remote enumeration merges), while
 * reader threads call `getSyncState`, `listDirectChildren` of a folder of 20 children and `getEntry` every
 * [READER_INTERVAL_MS] ms. The latency of a reader call includes any wait for the writer. Wall-clock numbers of a busy
 * machine: compare runs made back to back, never across sessions. Not part of `check`: run with
 * `./gradlew :app:engine-core:readerLatencyProbe -Prows=200000`.
 */
private const val CHUNK_ROWS = 1_000
private const val READER_INTERVAL_MS = 10L
private const val SMALL_FOLDER_CHILDREN = 20
private const val FILES_PER_FOLDER = 1_000

private val stamp = Instant.parse("2026-03-28T12:00:00Z")

private fun file(
    path: String,
    hash: String,
) = SyncEntry(
    path = path,
    remoteId = "id-$path",
    remoteHash = hash,
    remoteSize = 1,
    remoteModified = stamp,
    localMtime = null,
    localSize = null,
    isFolder = false,
    isPinned = false,
    isHydrated = false,
    lastSynced = stamp,
)

private fun paths(rows: Int): List<String> {
    val out = ArrayList<String>(rows)
    for (i in 0 until SMALL_FOLDER_CHILDREN) out += "/small/f$i"
    var i = 0
    while (out.size < rows) {
        out += "/d${i / FILES_PER_FOLDER}/f${i % FILES_PER_FOLDER}"
        i++
    }
    return out
}

private fun percentile(
    sorted: LongArray,
    p: Double,
): Long = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p).toInt()]

private fun ms(nanos: Long) = String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0)

private class Reader(
    val name: String,
    val call: () -> Unit,
) {
    val latencies = ArrayList<Long>()
}

fun main(args: Array<String>) {
    val rows = args.firstOrNull()?.toInt() ?: 200_000
    val dir = Files.createTempDirectory("reader-latency-probe")
    val db = StateDatabase(dir.resolve("state.db"))
    db.initialize()
    try {
        val all = paths(rows)
        val seedStarted = System.nanoTime()
        for (chunk in all.chunked(10_000)) db.batch { for (p in chunk) db.upsertEntry(file(p, "h0")) }
        db.setSyncState("probe_key", "v")
        println("# reader latency probe: $rows rows, writer chunk $CHUNK_ROWS rows, readers every $READER_INTERVAL_MS ms")
        println("# JVM: ${System.getProperty("java.vm.version")}; seeded in ${ms(System.nanoTime() - seedStarted)} ms")

        val readers =
            listOf(
                Reader("getSyncState") { db.getSyncState("probe_key") },
                Reader("listDirectChildren") { check(db.listDirectChildren("/small").size == SMALL_FOLDER_CHILDREN) },
                Reader("getEntry") { checkNotNull(db.getEntry("/small/f7")) },
            )
        val done = AtomicBoolean(false)
        val threads =
            readers.map { r ->
                thread(name = "probe-${r.name}") {
                    while (!done.get()) {
                        val started = System.nanoTime()
                        r.call()
                        r.latencies += System.nanoTime() - started
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(READER_INTERVAL_MS))
                    }
                }
            }

        val holds = ArrayList<Long>()
        val writerStarted = System.nanoTime()
        for ((n, chunk) in all.chunked(CHUNK_ROWS).withIndex()) {
            val started = System.nanoTime()
            db.batch {
                for (p in chunk) {
                    val existing = db.getEntryByRemotePath(p) ?: db.getEntryByRemoteId("id-$p")
                    db.upsertEntry((existing ?: file(p, "h0")).copy(remoteHash = "h${n + 1}"), keepMarks = true)
                }
            }
            holds += System.nanoTime() - started
        }
        val writerNanos = System.nanoTime() - writerStarted
        done.set(true)
        threads.forEach { it.join() }

        val sortedHolds = holds.toLongArray().also { it.sort() }
        println(
            "writer: ${ms(writerNanos)} ms total, ${holds.size} chunks, chunk p50 ${ms(percentile(sortedHolds, 0.5))} ms, " +
                "max ${ms(sortedHolds.last())} ms",
        )
        println("# reader | calls | p50 ms | p99 ms | max ms | calls > 1 s")
        val everything = ArrayList<Long>()
        for (r in readers) {
            everything += r.latencies
            val s = r.latencies.toLongArray().also { it.sort() }
            println(
                "${r.name} | ${s.size} | ${ms(percentile(s, 0.5))} | ${ms(percentile(s, 0.99))} | ${ms(s.lastOrNull() ?: 0)} | " +
                    "${s.count { it > TimeUnit.SECONDS.toNanos(1) }}",
            )
        }
        val s = everything.toLongArray().also { it.sort() }
        println(
            "all readers | ${s.size} | ${ms(percentile(s, 0.5))} | ${ms(percentile(s, 0.99))} | ${ms(s.lastOrNull() ?: 0)} | " +
                "${s.count { it > TimeUnit.SECONDS.toNanos(1) }}",
        )
    } finally {
        db.close()
        dir.toFile().deleteRecursively()
    }
}
