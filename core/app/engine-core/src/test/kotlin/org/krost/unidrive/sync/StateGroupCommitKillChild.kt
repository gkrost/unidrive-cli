package org.krost.unidrive.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Path
import java.time.Instant

/**
 * The child process of [StateGroupCommitKillTest]: writes rows through a [StateGroupCommit], prints `ACK <path>` for each
 * call that has returned, then writes rows whose commit never finishes (the write hook parks inside the transaction) and
 * prints `STALLED` once the first of them is inside it (the others queue behind it). The parent kills this process at that point, which is what a `kill -9`
 * (or a power cut) does to a pass.
 */
fun main(args: Array<String>) {
    val db = StateDatabase(Path.of(args[0]))
    db.initialize()
    val stalled = java.util.concurrent.atomic.AtomicInteger()
    val group =
        StateGroupCommit(db) { entry, keepMarks ->
            db.upsertEntry(entry, keepMarks)
            if (entry.path.startsWith("/stall-")) {
                if (stalled.incrementAndGet() == 1) println("STALLED")
                Thread.sleep(Long.MAX_VALUE)
            }
        }
    fun row(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = "h",
            remoteSize = 1,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = 1L,
            localSize = 1L,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.now(),
        )
    runBlocking {
        // Sequential calls, then concurrent ones: each is acknowledged only after it returned.
        for (i in 1..5) {
            group.upsertEntry(row("/ok-$i"))
            println("ACK /ok-$i")
        }
        (1..8)
            .map { i ->
                async(Dispatchers.Default) {
                    group.upsertEntry(row("/ok-c$i"))
                    println("ACK /ok-c$i")
                }
            }.awaitAll()
        // These never return: the write parks inside the group's transaction. Their rows are in it, uncommitted.
        for (i in 1..STALL_ROWS) async(Dispatchers.Default) {
            group.upsertEntry(row("/stall-$i"))
            println("ACK /stall-$i")
        }
        Thread.sleep(Long.MAX_VALUE)
    }
}

internal const val STALL_ROWS = 3
