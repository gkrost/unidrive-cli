package org.krost.unidrive.internxt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A polling listing that the server never answers must not hold back another Drive call. The Drive REST budget
 * (`maxConcurrency = 2`) spaces request starts; it is not a count of calls in flight, so two hung listings leave every
 * other call free to go out. A call issued while two listings (files and folders) hang completes at once, long before
 * the listing socket timeout, on a real CIO client against a loopback TLS socket.
 */
class InternxtHungListingDoesNotBlockCallsTest {
    private val fileMeta = """{"uuid":"f1","plainName":"f1","type":"txt","size":"1","status":"EXISTS"}"""

    @Test
    fun `a metadata call completes while two listings hang`() {
        val listingsArrived = CountDownLatch(2)
        val release = CountDownLatch(1)
        LoopbackServer(tls = true) { exchange ->
            val request = exchange.readRequestLine()
            when {
                request.contains("/files/sync") || request.contains("/folders/sync") -> {
                    listingsArrived.countDown()
                    release.await(60, TimeUnit.SECONDS)
                }
                request.contains("/files/f1/meta") -> exchange.respond(200, "OK", fileMeta)
                else -> exchange.respond(404, "Not Found", "{}")
            }
        }.use { server ->
            loopbackService(loopbackClient(server, socketTimeoutMs = 60_000), listingSocketMs = 120_000).use { api ->
                runBlocking {
                    val files = async(Dispatchers.IO) { runCatching { api.getFilesSync(updatedAt = null, cursor = null) } }
                    val folders = async(Dispatchers.IO) { runCatching { api.getFoldersSync(updatedAt = null, cursor = null) } }
                    check(listingsArrived.await(20, TimeUnit.SECONDS)) { "both listings reached the server" }

                    val meta = withTimeout(10_000) { api.getFileMeta("f1") }

                    assertEquals("f1", meta.uuid)
                    release.countDown()
                    files.cancel()
                    folders.cancel()
                }
            }
        }
    }
}
