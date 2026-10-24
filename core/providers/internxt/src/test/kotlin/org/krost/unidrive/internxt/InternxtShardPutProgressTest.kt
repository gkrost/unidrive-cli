package org.krost.unidrive.internxt

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.http.UploadWatchdogLimits
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * archive#1197: a shard PUT reports the body bytes it has sent while it runs, so a 2 GB upload does not read "0 B" until the
 * request is over.
 */
class InternxtShardPutProgressTest {
    private val url = "https://gateway.invalid/shard"
    private val files = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        files.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun file(size: Int): Path =
        Files.createTempFile("shard", ".bin").also { Files.write(it, ByteArray(size) { i -> (i % 251).toByte() }); files.add(it) }

    private val limits = UploadWatchdogLimits(idleWindowMs = 20_000, responseWaitFloorMs = 60_000, tickMs = 100)

    @Test
    fun `byte counts rise while the PUT is still running`() =
        runBlocking {
            val size = 24 * 1024 * 1024
            val path = file(size)
            val reports = CopyOnWriteArrayList<Long>()
            var reportsWhenServerAnswered = 0
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                exchange.readBody(size.toLong(), chunkBytes = 64 * 1024, pauseMs = 20)
                reportsWhenServerAnswered = reports.size
                exchange.respond(200, "OK", "{}")
            }.use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 30_000), listingSocketMs = 30_000, shardPutWatchdog = limits).use { api ->
                    api.putEncryptedShardFromFile(url, path, size.toLong(), onBytesSent = { reports.add(it) }, progressIntervalMs = 50)
                }
            }
            val seen = reports.toList()
            assertTrue(seen.size >= 3, "expected several reports while the body streamed, got $seen")
            assertTrue(reportsWhenServerAnswered >= 2, "reports must arrive before the PUT returns, got $reportsWhenServerAnswered")
            assertEquals(seen.sorted(), seen, "the sent count never goes down")
            assertTrue(seen.first() < size, "the first report is below the total: $seen")
            assertTrue(seen.last() <= size.toLong(), "never above the total: $seen")
        }

    @Test
    fun `a PUT the server cuts leaves the last report below the size`() =
        runBlocking {
            val size = 24 * 1024 * 1024
            val path = file(size)
            val reports = CopyOnWriteArrayList<Long>()
            LoopbackServer(tls = true) { exchange ->
                exchange.readRequestLine()
                exchange.readBody(2L * 1024 * 1024, chunkBytes = 64 * 1024, pauseMs = 10)
                Thread.sleep(300)
            }.use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 30_000), listingSocketMs = 30_000, shardPutWatchdog = limits).use { api ->
                    runCatching {
                        api.putEncryptedShardFromFile(url, path, size.toLong(), onBytesSent = { reports.add(it) }, progressIntervalMs = 50)
                    }.exceptionOrNull() ?: error("the cut PUT must fail")
                }
            }
            assertTrue(reports.isEmpty() || reports.last() < size, "last report ${reports.lastOrNull()} must be below $size")
        }
}
