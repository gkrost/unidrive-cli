package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scope a host gets when it builds a [HydrationImpl] without passing one supervises: one failed
 * recovery upload must not fail the scope and cancel the uploads behind it (the daemon's own wiring
 * passes a supervising scope; tests and other hosts get the same shape from the default).
 */
class HydrationRecoveryScopeDefaultTest {
    @Test
    fun `the default recovery upload scope survives a failed child and runs the next one`() = runBlocking {
        val scope = HydrationImpl.defaultRecoveryUploadScope()
        try {
            val firstMayRun = CompletableDeferred<Unit>()
            val secondRan = CompletableDeferred<Unit>()
            val thirdRan = CompletableDeferred<Unit>()

            // The first child runs, then fails — as a failed recovery upload does.
            scope.launch {
                firstMayRun.await()
                throw IllegalStateException("first recovery upload failed")
            }
            // The second child runs meanwhile and completes.
            scope.launch { secondRan.complete(Unit) }

            firstMayRun.complete(Unit)
            secondRan.await()
            // A child launched after the failure must still run: the scope is alive.
            scope.launch { thirdRan.complete(Unit) }
            thirdRan.await()

            assertTrue(scope.isActive, "the scope stays alive after a failed child")
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `every call hands out its own supervising scope`() {
        val first = HydrationImpl.defaultRecoveryUploadScope()
        val second = HydrationImpl.defaultRecoveryUploadScope()
        try {
            first.cancel()
            assertFalse(first.isActive)
            assertTrue(second.isActive, "a second default scope is its own job, not the cancelled one")
        } finally {
            second.cancel()
        }
    }
}
