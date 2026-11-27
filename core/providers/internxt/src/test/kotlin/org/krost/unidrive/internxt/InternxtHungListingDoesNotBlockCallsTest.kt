package org.krost.unidrive.internxt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.http.Priority
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A polling listing that the server never answers must not hold back another Drive call. The two hung listings (files
 * and folders) hold both of the Drive REST budget's permits (`maxConcurrency = 2`), but only the heavy listings take a
 * permit: a cheap call is paced, not counted, so it goes out at once. A call issued while the two listings hang completes
 * long before the listing socket timeout, on a real CIO client against a loopback TLS socket.
 *
 * The second test is the reserved user-op slot (gkrost/unidrive-cli#74): one of the Drive REST budget's permits is
 * reserved for non-poll callers, so the poll's two slow listings can never occupy both and a user-driven heavy listing
 * goes out on the reserved slot while the poll's listings hang. It builds the service on the production budget, so the
 * wiring itself is what is under test.
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

    @Test
    fun `a foreground listing is served while the poll's two listings hang`() {
        val pollListingArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pollConnections = AtomicInteger(0)
        LoopbackServer(tls = true) { exchange ->
            val request = exchange.readRequestLine()
            when {
                request.contains("/folders/content/1111") || request.contains("/folders/content/2222") -> {
                    pollConnections.incrementAndGet()
                    pollListingArrived.countDown()
                    release.await(60, TimeUnit.SECONDS)
                    exchange.respond(200, "OK", "{}")
                }
                request.contains("/folders/content/user") -> exchange.respond(200, "OK", "{}")
                else -> exchange.respond(404, "Not Found", "{}")
            }
        }.use { server ->
            // The production drive budget: one of its two slots is reserved for non-poll callers, so the poll's
            // two slow listings can never occupy both (gkrost/unidrive-cli#74). Nothing else is overridden: this
            // is the wiring a real daemon builds.
            InternxtApiService(
                InternxtConfig(credentialProtector = null),
                credentialsProvider = { _, _ ->
                    InternxtCredentials(
                        jwt = "test-jwt",
                        mnemonic = "test-mnemonic",
                        rootFolderId = "test-root",
                        email = "test@example.invalid",
                    )
                },
                listingSocketTimeoutMs = 120_000,
                httpClient = loopbackClient(server, socketTimeoutMs = 60_000),
            ).use { api ->
                runBlocking {
                    val poll1 = async(Dispatchers.IO) { runCatching { api.getFolderContents("1111") } }
                    val poll2 = async(Dispatchers.IO) { runCatching { api.getFolderContents("2222") } }
                    check(pollListingArrived.await(20, TimeUnit.SECONDS)) { "the poll's first listing reached the server" }

                    // A user-driven listing goes out on the reserved slot instead of queueing behind the hung
                    // poll listings; completing inside this window is the assertion.
                    val served = withTimeout(10_000) {
                        withContext(Priority.Foreground) { api.getFolderContents("user") }
                    }
                    check(served.children.isEmpty() && served.files.isEmpty())

                    // While the first poll listing hangs, the second must still be waiting for a permit: only one
                    // poll listing ever connected.
                    assertEquals(1, pollConnections.get(), "the poll's second listing never took the slot reserved for user ops")

                    release.countDown()
                    poll1.await()
                    poll2.await()
                }
            }
        }
    }
}
