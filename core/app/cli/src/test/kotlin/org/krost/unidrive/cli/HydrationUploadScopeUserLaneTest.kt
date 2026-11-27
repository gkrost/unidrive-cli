package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.http.HttpRetryBudget
import kotlin.test.Test

/**
 * gkrost/unidrive-cli#74 (owner decision): a mount upload is a user's write, so the scope it runs in puts it on the user
 * lane and its lookups (`getMetadata`, `findFileByName`: heavy listings) take the Drive budget's reserved slot while the
 * poll's listings hang. If this test is removed or loosened, a mount upload silently queues behind the poll again.
 */
class HydrationUploadScopeUserLaneTest {
    @Test
    fun `a mount upload takes the reserved Drive slot while two poll listings hang`() =
        runBlocking<Unit> {
            val polls = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val uploads = hydrationUploadScope("qwprofile")
            try {
                val budget = HttpRetryBudget(maxConcurrency = 2, reservedForUserSlots = 1, minSpacingMs = 0, stormSpacingMs = 0)
                val hung = CompletableDeferred<Unit>()
                val aPollHolds = CompletableDeferred<Unit>()
                polls.launch { budget.withPermit { aPollHolds.complete(Unit); hung.await() } }
                polls.launch { budget.withPermit { aPollHolds.complete(Unit); hung.await() } }
                withTimeout(30_000) { aPollHolds.await() }

                val listed = CompletableDeferred<Unit>()
                uploads.launch { budget.withPermit { listed.complete(Unit) } }

                // Completing inside this window is the assertion: on the poll's lane the lookup parks until the hung
                // listing ends.
                withTimeout(30_000) { listed.await() }
                hung.complete(Unit)
            } finally {
                uploads.cancel()
                polls.cancel()
            }
        }
}
