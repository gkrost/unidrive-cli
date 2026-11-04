package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.Delayed
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hold-and-emit coalescer's slot ownership (the steal fix): a task that runs while a newer event
 * has already re-armed its path must stay quiet and leave the newer task's slot alone — remove-by-key
 * removed the newer task instead, enqueued at once before the newer quiet window, and left the newer
 * slot in the map to never fire. The tests drive the schedule by hand instead of waiting real time,
 * and run the stale task even though its cancel(false) arrived, which is what a task that had already
 * started does.
 */
class LocalWatcherDebounceStealTest {
    private lateinit var syncRoot: Path
    private lateinit var watcher: LocalWatcher
    private val coalescer = ManualCoalescer()

    @AfterTest
    fun tearDown() {
        watcher.stop()
        syncRoot.toFile().deleteRecursively()
    }

    private fun armWatcher() {
        syncRoot = Files.createTempDirectory("unidrive-watch-steal-test")
        watcher = LocalWatcher(syncRoot, debounceMs = 60_000, coalescer = coalescer)
    }

    private fun modify(path: String) = watcher.handleEvent(StandardWatchEventKinds.ENTRY_MODIFY, path)

    @Test
    fun `a task that runs after a newer event re-armed its path stays quiet and the newer task fires`() {
        armWatcher()
        // Event 1 arms task A; event 2 evicts and cancels A and arms B — as a burst does.
        modify("/doc.txt")
        modify("/doc.txt")
        assertEquals(2, coalescer.scheduled.size)
        assertTrue(coalescer.scheduled[0].cancelled, "the older task was cancelled by the re-arm")
        assertFalse(coalescer.scheduled[1].cancelled, "the newer task holds the slot")

        // A task that had already started ignores its cancellation, so the stale task runs anyway.
        coalescer.runNextRegardlessOfCancel()
        assertEquals(
            emptyList(),
            watcher.drainChanges(),
            "the stale task must neither emit nor remove the newer task's slot",
        )

        // The newer task fires within its own quiet window, exactly once.
        coalescer.runAllDue()
        assertEquals(listOf("/doc.txt"), watcher.drainChanges(), "the re-armed task emits once for the burst")
    }

    @Test
    fun `a task whose own window elapsed still emits when nothing re-armed its path`() {
        armWatcher()
        modify("/solo.txt")

        coalescer.runAllDue()

        assertEquals(listOf("/solo.txt"), watcher.drainChanges(), "a quiet window that elapsed emits once")
    }

    @Test
    fun `a burst of three events leaves exactly one emit on the last task`() {
        armWatcher()
        repeat(3) { modify("/hot.txt") }

        assertEquals(3, coalescer.scheduled.size)
        coalescer.runNextRegardlessOfCancel() // the first task, already started when the burst re-armed
        coalescer.runAllDue()

        assertEquals(listOf("/hot.txt"), watcher.drainChanges())
    }

    @Test
    fun `a zero-delay task that runs before schedule returns still emits and leaves no slot behind`() {
        syncRoot = Files.createTempDirectory("unidrive-watch-steal-test")
        // The executor may run a zero-delay task on its own thread before schedule() returns; run it inline here.
        val immediate = ImmediateCoalescer()
        watcher = LocalWatcher(syncRoot, debounceMs = 0, coalescer = immediate)

        modify("/now.txt")

        assertEquals(listOf("/now.txt"), watcher.drainChanges(), "the emit that ran before schedule returned is queued")
        // A second event must arm and emit again: no stale, never-firing slot left in the map by the first.
        modify("/now.txt")
        assertEquals(listOf("/now.txt"), watcher.drainChanges())
    }

    @Test
    fun `a schedule that throws leaves no slot behind`() {
        syncRoot = Files.createTempDirectory("unidrive-watch-steal-test")
        val rejecting =
            object : ScheduledExecutorService by Executors.newSingleThreadScheduledExecutor() {
                override fun schedule(
                    command: Runnable,
                    delay: Long,
                    unit: TimeUnit,
                ): ScheduledFuture<*> = throw java.util.concurrent.RejectedExecutionException("shut down")
            }
        watcher = LocalWatcher(syncRoot, debounceMs = 0, coalescer = rejecting)

        kotlin.runCatching { modify("/gone.txt") }

        assertTrue(watcher.pendingEmitCount() == 0, "a rejected schedule must not leave its emit installed")
    }

    private class ImmediateCoalescer : ScheduledExecutorService by Executors.newSingleThreadScheduledExecutor() {
        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            command.run()
            return StubFuture(command).also { it.ran = true }
        }
    }

    private class ManualCoalescer : ScheduledExecutorService by Executors.newSingleThreadScheduledExecutor() {
        val scheduled = mutableListOf<StubFuture>()

        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            val future = StubFuture(command)
            scheduled += future
            return future
        }

        /** Runs the oldest not-yet-run task even though it was cancelled: the stand-in for a started task. */
        fun runNextRegardlessOfCancel() {
            val future = scheduled.firstOrNull { !it.ran } ?: error("nothing left scheduled")
            future.ran = true
            future.command.run()
        }

        /** Runs every not-yet-run task that was not cancelled, in schedule order. */
        fun runAllDue() {
            scheduled.filter { !it.ran && !it.cancelled }.forEach {
                it.ran = true
                it.command.run()
            }
        }
    }

    private class StubFuture(
        val command: Runnable,
    ) : ScheduledFuture<Unit> {
        var ran = false
        var cancelled = false

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            if (ran) return false
            cancelled = true
            return true
        }

        override fun isCancelled(): Boolean = cancelled

        override fun isDone(): Boolean = ran || cancelled

        override fun get(): Unit = throw UnsupportedOperationException()

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Unit = throw UnsupportedOperationException()

        override fun getDelay(unit: TimeUnit): Long = 0

        override fun compareTo(other: Delayed): Int = 0
    }
}
