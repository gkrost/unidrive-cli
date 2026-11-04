package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scope a host gets when it builds a [HydrationImpl] without passing one supervises: one failed
 * recovery upload must not fail the scope and cancel the uploads behind it (the daemon's own wiring
 * passes a supervising scope; tests and other hosts get the same shape from the default).
 */
class HydrationRecoveryScopeDefaultTest {
    @Test
    fun `the default recovery upload scope survives a failed child and runs the next one`() =
        runBlocking {
            val scope = HydrationImpl.defaultRecoveryUploadScope()
            try {
                withTimeout(WAIT_MS) {
                    // The failure is intentional: a test-local handler receives it, so it never reaches the
                    // process-wide uncaught-exception handler (which the coroutines test module reports into the
                    // next test of the module as UncaughtExceptionsBeforeTest).
                    val caught = CompletableDeferred<Throwable>()
                    val handler = CoroutineExceptionHandler { _, e -> caught.complete(e) }
                    val failing = scope.launch(handler) { throw IllegalStateException("first recovery upload failed") }
                    assertEquals("first recovery upload failed", caught.await().message)
                    failing.join()

                    // A child launched after the failure must still run: the scope is alive.
                    val secondRan = CompletableDeferred<Unit>()
                    scope.launch { secondRan.complete(Unit) }.join()
                    secondRan.await()
                    assertTrue(scope.isActive, "the scope stays alive after a failed child")
                }
            } finally {
                scope.coroutineContext[Job]?.let { job ->
                    job.cancel()
                    withTimeout(WAIT_MS) { job.join() }
                }
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

    private companion object {
        // Bounded, so a broken supervisor fails the test instead of hanging the module.
        const val WAIT_MS = 30_000L
    }
}
