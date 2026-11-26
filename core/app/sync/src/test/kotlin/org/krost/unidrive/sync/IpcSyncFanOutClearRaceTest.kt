package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [IpcSyncFanOut.clear] runs while a subscriber leaves on its own (its connection closes: [IpcSyncFanOut.remove], or its
 * writer ends and drops itself). With one subscriber left, a snapshot of the key set taken by size first and then by
 * iterator could find the set empty in between and throw NoSuchElementException (seen in CI as a teardown failure of
 * IpcProgressReporterTest). clear must tolerate a concurrent removal.
 */
class IpcSyncFanOutClearRaceTest {
    @Test
    fun `clear tolerates a subscriber that is removed at the same moment`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        try {
            repeat(ROUNDS) { round ->
                val fanOut = IpcSyncFanOut(scope, { _, _ -> true })
                val id = "c$round"
                fanOut.add(id)
                val barrier = CyclicBarrier(2)
                val remover = thread { barrier.await(); runCatching { fanOut.remove(id) }.onFailure { failures += it } }
                barrier.await()
                runCatching { fanOut.clear() }.onFailure { failures += it }
                remover.join()
            }
        } finally {
            scope.cancel()
        }
        assertTrue(failures.isEmpty(), "clear or remove threw: ${failures.firstOrNull()}")
    }

    private companion object {
        const val ROUNDS = 20_000
    }
}
