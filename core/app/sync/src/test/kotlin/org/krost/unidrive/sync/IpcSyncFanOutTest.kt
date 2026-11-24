package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.krost.unidrive.testing.TestScratch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#926 S3: the `sync.subscribe` stream gives every subscriber its own bounded queue and writer, so one that
 * does not read neither delays the others nor loses their events, and it is told what it lost.
 */
class IpcSyncFanOutTest {
    private lateinit var socketPath: Path
    private var server: IpcServer? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val channels = CopyOnWriteArrayList<SocketChannel>()

    @BeforeTest
    fun setUp() {
        socketPath = TestScratch.directory("ipc-fanout").resolve("f.sock")
    }

    @AfterTest
    fun tearDown() {
        channels.forEach { runCatching { it.close() } }
        server?.close()
        scope.cancel()
    }

    private fun start(writeTimeoutMs: Long): IpcServer =
        IpcServer(socketPath, writeTimeoutMs = writeTimeoutMs, idleTimeoutMs = 0).also {
            server = it
            it.registerSyncSubscribeVerb()
            it.start(scope)
        }

    // A blocking connection that has subscribed; its reader is the caller's.
    private fun subscribe(): Pair<SocketChannel, BufferedReader> {
        val ch = SocketChannel.open(StandardProtocolFamily.UNIX).also { it.connect(UnixDomainSocketAddress.of(socketPath)) }
        channels += ch
        ch.write(ByteBuffer.wrap("{\"verb\":\"sync.subscribe\"}\n".toByteArray()))
        val reader = BufferedReader(InputStreamReader(Channels.newInputStream(ch), Charsets.UTF_8))
        check(reader.readLine() == """{"ok":true}""")
        return ch to reader
    }

    private fun awaitSubscribers(
        srv: IpcServer,
        n: Int,
    ) {
        val deadline = System.currentTimeMillis() + 5_000
        while (srv.syncSubscribersSnapshot.size < n && System.currentTimeMillis() < deadline) Thread.sleep(10)
        check(srv.syncSubscribersSnapshot.size == n) { "subscribers registered" }
    }

    // An event large enough that a few hundred of them overflow a socket buffer nobody reads.
    private fun bigEvent(i: Int) = """{"event":"tick","i":$i,"pad":"${"x".repeat(16 * 1024)}"}"""

    @Test
    fun slow_sync_subscriber_does_not_delay_other_subscribers() {
        val srv = start(writeTimeoutMs = 5_000)
        subscribe() // the slow one: never reads
        val (_, fast) = subscribe()
        awaitSubscribers(srv, 2)
        val markerSeen = CountDownLatch(1)
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val line = fast.readLine() ?: break
                    if ("\"marker\"" in line) markerSeen.countDown()
                }
            }
        }

        repeat(200) { srv.emit(bigEvent(it)) } // more than the slow subscriber's socket buffer holds
        val sentAt = System.nanoTime()
        srv.emit("""{"event":"marker"}""")

        assertTrue(markerSeen.await(4, TimeUnit.SECONDS), "the reading subscriber gets the marker without waiting out the slow one")
        val ms = (System.nanoTime() - sentAt) / 1_000_000
        assertTrue(ms < 2_000, "the marker took $ms ms, the length of a write timeout spent on the slow subscriber")
    }

    @Test
    fun a_slow_sync_subscriber_is_told_how_many_events_it_lost() {
        val srv = start(writeTimeoutMs = 30_000)
        val (_, slow) = subscribe()
        awaitSubscribers(srv, 1)

        repeat(600) { srv.emit(bigEvent(it)) } // the socket buffer and its queue overflow while it does not read
        Thread.sleep(500)
        srv.emit("""{"event":"marker"}""")

        val lines = CopyOnWriteArrayList<String>()
        val markerSeen = CountDownLatch(1)
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val line = slow.readLine() ?: break
                    lines += line.take(80)
                    if ("\"marker\"" in line) markerSeen.countDown()
                }
            }
        }
        assertTrue(markerSeen.await(10, TimeUnit.SECONDS), "the newest event reaches it once it reads again")
        assertTrue(lines.any { it.startsWith("""{"event":"lost","since_last":""") }, "a lost line names the dropped events")
    }
}
