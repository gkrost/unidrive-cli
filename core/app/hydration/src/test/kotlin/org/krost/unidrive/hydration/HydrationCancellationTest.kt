package org.krost.unidrive.hydration

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** gkrost/unidrive-cli#438: a cancelled caller is not a failed request; no reply is made up for it. */
class HydrationCancellationTest {
    @Test
    fun `a cancelled open_write_begin gives no failure reply`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        env.stateDb.insertUnhydratedEntry("/q/f.txt", remoteSize = 5, remoteHash = "v1")
        val swapLock = MountEngine.over(env.engine).cacheSwapLock("/q/f.txt")
        var reply: OpenResult? = null
        swapLock.withLock {
            val caller = launch { reply = env.hydration.openWriteBegin("conn1", "/q/f.txt", handleId = "h1") }
            runCurrent() // the truncate waits for the swap lock
            caller.cancel()
            runCurrent()
        }
        runCurrent()

        assertNull(reply, "the cancelled call made up a failure reply, got: $reply")
        assertEquals(null, env.stateDb.editBaseOf("/q/f.txt"), "nothing was marked or truncated")
    }
}
