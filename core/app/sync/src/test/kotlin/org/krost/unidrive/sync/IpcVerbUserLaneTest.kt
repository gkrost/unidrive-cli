package org.krost.unidrive.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.testing.TestScratch
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * gkrost/unidrive-cli#74 (owner decision): every IPC verb is user-started, so its handler runs on the user lane
 * ([org.krost.unidrive.http.Priority.Foreground]) and takes the Drive budget's reserved slot while the poll's
 * listings hang. The mount's listings (`hydration.list`, `hydration.stat`, ...) are IPC verbs and reach the provider
 * from inside their handler. If this test is removed or loosened, a user's verb silently queues behind the poll again.
 */
class IpcVerbUserLaneTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        socketDir = TestScratch.directory("unidrive-ipc-user-lane-test")
        socketPath = socketDir.resolve("test.sock")
    }

    @AfterTest
    fun tearDown() {
        server?.close()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketDir)
    }

    @Test
    fun `an IPC verb takes the reserved Drive slot while two poll listings hang`() =
        runBlocking<Unit>(Dispatchers.IO) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val budget = HttpRetryBudget(maxConcurrency = 2, reservedForUserSlots = 1, minSpacingMs = 0, stormSpacingMs = 0)
                val hung = CompletableDeferred<Unit>()
                val aPollHolds = CompletableDeferred<Unit>()
                // The poll's two listings: unmarked, so on the background lane. Whichever is dispatched first holds the
                // poll's share and hangs; the other parks behind it (either may run first, so both signal the hold).
                scope.launch { budget.withPermit { aPollHolds.complete(Unit); hung.await() } }
                scope.launch { budget.withPermit { aPollHolds.complete(Unit); hung.await() } }
                withTimeout(30_000) { aPollHolds.await() }

                val listed = CompletableDeferred<Unit>()
                val s = IpcServer(socketPath)
                server = s
                s.registerHandler("hydration.list") { _, _ ->
                    budget.withPermit { listed.complete(Unit) }
                    """{"ok":true}"""
                }
                s.start(scope)
                val client = SocketChannel.open(StandardProtocolFamily.UNIX)
                client.connect(UnixDomainSocketAddress.of(socketPath))
                val request = ByteBuffer.wrap("{\"verb\":\"hydration.list\"}\n".toByteArray(Charsets.UTF_8))
                while (request.hasRemaining()) client.write(request)

                // Completing inside this window is the assertion: on the poll's lane the verb would park until the
                // hung listing ends.
                withTimeout(30_000) { listed.await() }
                client.close()
                hung.complete(Unit)
            } finally {
                server?.close()
                scope.cancel()
            }
        }
}
