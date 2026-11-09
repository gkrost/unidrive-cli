package org.krost.unidrive.s3

import org.junit.After
import java.io.InputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
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

    private fun client(): Socket = Socket("127.0.0.1", server.endpoint.substringAfterLast(':').toInt())

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
}
