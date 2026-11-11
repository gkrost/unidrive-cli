package org.krost.unidrive.s3

import org.junit.After
import java.io.InputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The logging server itself, with a hand-written client: the request-pattern tests rest on its connection accounting,
 * so that is pinned here, apart from any S3 client.
 */
class LoggingS3ServerTest {
    private val server = LoggingS3Server()

    @After
    fun stop() = server.close()

    private class Answer(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun Socket.call(
        method: String,
        target: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): Answer {
        val head =
            buildString {
                append("$method $target HTTP/1.1\r\nHost: localhost\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                if (body.isNotEmpty()) append("Content-Length: ${body.size}\r\n")
                append("\r\n")
            }
        getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1) + body)
        getOutputStream().flush()
        val input = getInputStream()
        val status = readLine(input).split(' ')[1].toInt()
        val answerHeaders = generateSequence { readLine(input).takeIf { it.isNotEmpty() } }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val length = if (method == "HEAD") 0 else answerHeaders["content-length"]?.toInt() ?: 0
        return Answer(status, answerHeaders, String(input.readNBytes(length), Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
        }
    }

    // A read that finds no answer fails after READ_TIMEOUT_MS; without it a server that never answers would hang the build.
    private fun client(): Socket = Socket("127.0.0.1", server.endpoint.substringAfterLast(':').toInt()).also { it.soTimeout = READ_TIMEOUT_MS }

    private fun untilClosed(connection: LoggingS3Server.Connection): LoggingS3Server.Closer? {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (connection.closedBy == null && System.nanoTime() < deadline) Thread.sleep(10)
        return connection.closedBy
    }

    @Test
    fun `requests on one connection are one connection and a client that hangs up closed it`() {
        client().use { socket ->
            repeat(5) { assertEquals(404, socket.call("GET", "/b/missing").status) }
            assertEquals(5, server.requests().size)
            assertEquals(1, LoggingS3Server.connectionCount(server.requests()), LoggingS3Server.summary(server.requests()))
            assertNull(server.connections().single().closedBy, "the connection is open while the client holds it")
        }
        assertEquals(LoggingS3Server.Closer.CLIENT, untilClosed(server.connections().single()))
    }

    @Test
    fun `a connection per request shows as a connection per request, each closed by the client`() {
        repeat(3) { client().use { it.call("HEAD", "/b/missing") } }

        assertEquals(3, LoggingS3Server.connectionCount(server.requests()), LoggingS3Server.summary(server.requests()))
        assertEquals(setOf(LoggingS3Server.Closer.CLIENT), server.connections().map { untilClosed(it) }.toSet())
    }

    @Test
    fun `a request that asks for Connection close is closed by the server`() {
        client().use { socket ->
            val answer = socket.call("GET", "/b/missing", mapOf("Connection" to "close"))
            assertEquals("close", answer.headers["connection"])
        }
        assertEquals(LoggingS3Server.Closer.SERVER, untilClosed(server.connections().single()))
    }

    @Test
    fun `an object round trips with its metadata and a bad Content-MD5 is BadDigest`() {
        val bytes = "hello".toByteArray()
        val md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))
        client().use { socket ->
            assertEquals(200, socket.call("PUT", "/b/dir/a.txt", mapOf("Content-MD5" to md5, "x-amz-meta-mtime" to "1.5"), bytes).status)
            val head = socket.call("HEAD", "/b/dir/a.txt")
            assertEquals("5", head.headers["content-length"])
            assertEquals("1.5", head.headers["x-amz-meta-mtime"])
            assertEquals("hello", socket.call("GET", "/b/dir/a.txt").body)

            val bad = socket.call("PUT", "/b/dir/b.txt", mapOf("Content-MD5" to md5), "hellp".toByteArray())
            assertEquals(400, bad.status)
            assertTrue("BadDigest" in bad.body)
            assertEquals(404, socket.call("HEAD", "/b/dir/b.txt").status)
        }
        assertContentEquals(bytes, server.bytesOf("dir/a.txt"))
        assertEquals(md5, server.requests().first().contentMd5)
    }

    @Test
    fun `If-None-Match star is a 412 on a taken key`() {
        server.seed("taken", "x".toByteArray())
        client().use { socket ->
            assertEquals(412, socket.call("PUT", "/b/taken", mapOf("If-None-Match" to "*"), "y".toByteArray()).status)
            assertEquals(200, socket.call("PUT", "/b/free", mapOf("If-None-Match" to "*"), "y".toByteArray()).status)
        }
        assertEquals(listOf("*", "*"), server.requests().map { it.ifNoneMatch })
    }

    @Test
    fun `a flipped byte reaches the store when no digest guards it`() {
        server.flipByteInNextPut()
        client().use { it.call("PUT", "/b/k", emptyMap(), byteArrayOf(0, 1, 2)) }

        assertContentEquals(byteArrayOf(1, 1, 2), server.bytesOf("k"))
    }

    @Test
    fun `a mark taken while a request is still being read leaves out the requests that arrived before it`() {
        client().use { slow ->
            // The head of a PUT whose body is still to come: the server has counted the request but not answered it, so it is not logged.
            val head = "PUT /b/slow HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\n"
            slow.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
            slow.getOutputStream().flush()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (server.connections().singleOrNull()?.requestCount?.get() != 1 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(1, server.connections().singleOrNull()?.requestCount?.get(), "the server has read the head of the slow request")
            client().use { assertEquals(404, it.call("GET", "/b/missing").status) }

            val mark = server.mark()
            slow.getOutputStream().write("hello".toByteArray())
            slow.getOutputStream().flush()
            assertEquals(200, readStatus(slow))
            assertEquals(2, server.requests().size, "both requests are logged by now")
            assertEquals(emptyList(), server.requestsSince(mark).map { it.key }, "both arrived before the mark")

            client().use { assertEquals(404, it.call("GET", "/b/after").status) }
            assertEquals(listOf("after"), server.requestsSince(mark).map { it.key })
        }
    }

    @Test
    fun `of many concurrent creates with If-None-Match star exactly one wins`() {
        val body = ByteArray(4 shl 20) { it.toByte() } // its MD5 is computed between looking at the key and storing
        val statuses = ConcurrentLinkedQueue<Int>()
        val ready = CountDownLatch(CREATORS)
        val go = CountDownLatch(1)
        val workers =
            (1..CREATORS).map {
                thread {
                    client().use { socket ->
                        ready.countDown()
                        go.await()
                        statuses += socket.call("PUT", "/b/taken", mapOf("If-None-Match" to "*"), body).status
                    }
                }
            }
        assertTrue(ready.await(10, TimeUnit.SECONDS), "the clients connected")
        go.countDown()
        workers.forEach { it.join(30_000) }

        assertEquals(CREATORS, statuses.size, "every create was answered")
        assertEquals(1, statuses.count { it == 200 }, "statuses: ${statuses.groupingBy { it }.eachCount()}")
        assertContentEquals(body, server.bytesOf("taken"))
    }

    @Test
    fun `close ends the threads of the server and its connections`() {
        val sockets = (1..3).map { client().also { socket -> socket.call("GET", "/b/missing") } }

        server.close()

        assertTrue(server.stopped, "close() returned while the server still had threads running")
        sockets.forEach { assertEquals(-1, runCatching { it.getInputStream().read() }.getOrDefault(-1), "the server closed this connection") }
        sockets.forEach { it.close() }
    }

    private fun readStatus(socket: Socket): Int {
        val input = socket.getInputStream()
        val status = readLine(input).split(' ')[1].toInt()
        val headers = generateSequence { readLine(input).takeIf { it.isNotEmpty() } }.associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        input.readNBytes(headers["content-length"]?.toInt() ?: 0)
        return status
    }

    private companion object {
        const val READ_TIMEOUT_MS = 15_000
        const val CREATORS = 8
    }
}
