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
                val firstPollHolds = CompletableDeferred<Unit>()
                // The poll's two listings: unmarked, so on the background lane. The first holds the poll's share and
                // hangs; the second parks behind it.
                scope.launch { budget.withPermit { firstPollHolds.complete(Unit); hung.await() } }
                scope.launch { budget.withPermit { hung.await() } }
                withTimeout(5_000) { firstPollHolds.await() }

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
                withTimeout(5_000) { listed.await() }
                client.close()
                hung.complete(Unit)
            } finally {
                server?.close()
                scope.cancel()
            }
        }
}
