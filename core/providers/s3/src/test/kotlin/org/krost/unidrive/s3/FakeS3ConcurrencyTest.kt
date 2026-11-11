package org.krost.unidrive.s3

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [FakeS3] is entered by four transfers at once in [S3RequestPatternTest], so its own state has to hold up under that: a
 * request that is lost, counted twice or answered with a failure scripted for one other request would make a request-pattern
 * count wrong without any provider change. These tests call the fake's engine from many threads, with no provider in between.
 */
class FakeS3ConcurrencyTest {
    private val fake = FakeS3()
    private val client = HttpClient(fake.engine)

    @After
    fun stop() = client.close()

    private suspend fun call(
        method: HttpMethod,
        path: String,
        body: ByteArray? = null,
        extra: Map<String, String> = emptyMap(),
    ): HttpStatusCode =
        client
            .request("http://localhost/b/$path") {
                this.method = method
                header("Authorization", "AWS4-HMAC-SHA256 Credential=AK/20260101/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=00")
                header("x-amz-date", "20260101T000000Z")
                extra.forEach { (k, v) -> header(k, v) }
                if (body != null) setBody(body)
            }.status

    /** [work] for every index in 0 until [count], all at once on the IO threads. */
    private fun <T> concurrently(
        count: Int,
        work: suspend (Int) -> T,
    ): List<T> =
        runBlocking(Dispatchers.IO) {
            withTimeout(TIMEOUT_MS) { (0 until count).map { i -> async { work(i) } }.awaitAll() }
        }

    @Test
    fun `two requests are inside the fake at the same time`() {
        // The premise of the other tests here and of the four-transfer tests: the engine does not serialise the handler. Each HEAD
        // waits for a second one; if the handler were serial, no second would come and the wait would run out.
        val together = CountDownLatch(2)
        val overlapped = AtomicBoolean(true)
        fake.put("k", "v")
        fake.onHead = { _, _ ->
            together.countDown()
            if (!together.await(WAIT_S, TimeUnit.SECONDS)) overlapped.set(false)
        }

        concurrently(THREADS) { call(HttpMethod.Head, "k") }

        assertTrue(overlapped.get(), "two HEADs were never inside the fake together")
    }

    @Test
    fun `a failure scripted once answers one of many concurrent requests`() {
        fake.put("k", "v")
        // A slow predicate widens the window between finding the scripted failure and taking it out of the list.
        fake.failures += Triple({ r: FakeS3.Request -> r.key == "k" && Thread.sleep(2).let { true } }, 503, "SlowDown")

        val statuses = concurrently(32) { call(HttpMethod.Get, "k") }

        assertEquals(1, statuses.count { it == HttpStatusCode.ServiceUnavailable }, "statuses: ${statuses.groupingBy { it }.eachCount()}")
        assertTrue(fake.failures.isEmpty(), "the failure was used up")
    }

    @Test
    fun `concurrent HEADs are counted one to N, each number once`() {
        fake.put("k", "v")
        val seen = ConcurrentLinkedQueue<Int>()
        fake.onHead = { _, count -> seen += count }
        val heads = 8 * 200

        concurrently(heads) { call(HttpMethod.Head, "k") }

        assertEquals((1..heads).toList(), seen.sorted(), "a number was counted twice or skipped")
    }

    @Test
    fun `concurrent PUTs each leave their own request entry and their own modified time`() {
        val puts = 8 * 100

        concurrently(puts) { call(HttpMethod.Put, "k$it", "x".toByteArray()) }

        assertEquals(puts, fake.count { it.method == HttpMethod.Put }, "a request was lost from the log")
        assertEquals(puts, fake.objects.values.map { it.lastModified }.toSet().size, "two objects got the same modified time")
    }

    @Test
    fun `concurrent multipart initiations get distinct upload ids and none is lost`() {
        val uploads = 8 * 50

        concurrently(uploads) { call(HttpMethod.Post, "k$it?uploads=") }

        assertEquals(uploads, fake.openUploads, "an initiated upload was lost")
    }

    @Test
    fun `the request log can be read while requests are being recorded`() {
        fake.put("k", "v")
        val running = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val reader =
            thread {
                try {
                    while (running.get()) fake.requests.count { it.method == HttpMethod.Get }
                } catch (e: ConcurrentModificationException) {
                    failure.set(e)
                }
            }

        try {
            concurrently(8 * 200) { call(HttpMethod.Get, "k") }
        } finally {
            running.set(false)
            reader.join(TIMEOUT_MS)
            assertTrue(!reader.isAlive, "the log reader stopped")
        }

        assertEquals(null, failure.get(), "a reader of the log hit a write")
        assertEquals(8 * 200, fake.count { it.method == HttpMethod.Get })
    }

    @Test
    fun `of many concurrent creates with If-None-Match star exactly one wins`() {
        val body = ByteArray(1 shl 20) { it.toByte() } // the MD5 of a body sits between the check and the store

        val statuses = concurrently(16) { call(HttpMethod.Put, "taken", body, mapOf("If-None-Match" to "*")) }

        assertEquals(1, statuses.count { it == HttpStatusCode.OK }, "statuses: ${statuses.groupingBy { it }.eachCount()}")
    }

    private companion object {
        const val THREADS = 4
        const val WAIT_S = 10L
        const val TIMEOUT_MS = 60_000L
    }
}
