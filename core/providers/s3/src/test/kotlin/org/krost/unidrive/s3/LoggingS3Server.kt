package org.krost.unidrive.s3

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A small S3 server on loopback, for tests that must see what a client really sends: one bucket in memory, path-style,
 * HTTP/1.1 with keep-alive, and a log of every request and every connection. Other providers' tests can use it for
 * their own S3-compatible endpoints.
 *
 * It is a plain [ServerSocket], not `com.sun.net.httpserver`: that server never says who ended a connection, and the
 * question "does the client reuse its connections" is the reason this class exists.
 *
 * - **Served:** GET, HEAD, PUT and DELETE of objects, and an empty ListObjectsV2 answer for `GET /bucket`. PUT checks
 *   `Content-MD5` (400 `BadDigest` on a mismatch) and answers 412 to `If-None-Match: *` on a taken key and to a stale
 *   `If-Match`. Request bodies may be sized or chunked. [flipByteInNextPut] corrupts the next PUT body on arrival, as a
 *   bad link would.
 * - **Not served** (501 `NotImplemented`): listings of objects, multi-delete, multipart uploads and copies; the request
 *   *patterns* of those belong on `FakeS3`, which has them. No signature is checked; [Request.signed] records whether the
 *   client sent one.
 * - **Logged:** per request the method, key, query, the connection and its client port, the status, `Content-MD5` and
 *   `If-None-Match`; per [Connection] who ended it ([Closer.CLIENT]: the client closed or reset; [Closer.SERVER]: this
 *   server did after `Connection: close` or an HTTP/1.0 request; null: still open); and [peakInFlight], the most
 *   requests the server held open at once. A request is logged before its answer is written, so a client that has the
 *   answer finds the entry.
 */
class LoggingS3Server(
    val bucket: String = "b",
) : AutoCloseable {
    enum class Closer { CLIENT, SERVER }

    /** One accepted TCP connection. [id] counts connections, [clientPort] is the client's ephemeral port (ports can be reused, ids are not). */
    class Connection(
        val id: Int,
        val clientPort: Int,
    ) {
        val requestCount = AtomicInteger()

        @Volatile
        var closedBy: Closer? = null

        override fun toString(): String = "connection #$id (client port $clientPort, ${requestCount.get()} request(s), closed by ${closedBy ?: "nobody yet"})"
    }

    /** One request as the server saw it. [query] is the raw query string, [params] its decoded form. */
    data class Request(
        val seq: Long,
        val connection: Int,
        val clientPort: Int,
        val method: String,
        val key: String,
        val query: String,
        val params: Map<String, String>,
        val status: Int,
        val contentMd5: String?,
        val ifNoneMatch: String?,
        val signed: Boolean,
        val bodyBytes: Long,
    ) {
        /** `PUT`, `HEAD`, ... ; a bucket-level call is named by what it is: `LIST`, `POST delete`. */
        val label: String
            get() =
                when {
                    key.isNotEmpty() -> method
                    method == "GET" && "list-type" in params -> "LIST"
                    params.isNotEmpty() -> "$method ${params.keys.sorted().joinToString(",")}"
                    else -> "$method bucket"
                }
    }

    private class StoredObject(
        val bytes: ByteArray,
        val meta: Map<String, String>,
        val modified: Instant,
    ) {
        val etag: String = md5Hex(bytes)
    }

    private class Head(
        val method: String,
        val target: String,
        val version: String,
        val headers: Map<String, String>,
    )

    private class Response(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        val contentLength: Long = body.size.toLong(),
    )

    private val flipNextPut = AtomicBoolean(false)

    private val objects = ConcurrentSkipListMap<String, StoredObject>()
    private val log = CopyOnWriteArrayList<Request>()
    private val connectionLog = CopyOnWriteArrayList<Connection>()
    private val open = CopyOnWriteArrayList<Socket>()
    private val seq = AtomicLong()
    private val inFlight = AtomicInteger()
    private val peak = AtomicInteger()
    private val connectionIds = AtomicInteger()
    private val stopping = AtomicBoolean(false)
    private val writeLock = Any()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "logging-s3").apply { isDaemon = true } }
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())

    /** `http://127.0.0.1:<port>`: what an `endpoint` setting takes (S3 clients treat a plain http endpoint as path-style). */
    val endpoint: String = "http://127.0.0.1:${server.localPort}"

    /** True once close() has returned: the server's threads have ended and nothing of it runs on. */
    val stopped: Boolean get() = pool.isTerminated

    init {
        pool.execute(::acceptLoop)
    }

    /** The properties [S3ProviderFactory.create] takes, pointing at this server. The keys are throwaway values; none is checked. */
    fun profile(): Map<String, String?> =
        mapOf("bucket" to bucket, "region" to "us-east-1", "endpoint" to endpoint, "access_key_id" to "AKTESTKEY", "secret_access_key" to "test-secret-key")

    // ── the bucket, as a test sees it ──

    /** Puts an object in the bucket without a request, with an optional `x-amz-meta-*` map (keys without the prefix). */
    fun seed(
        key: String,
        bytes: ByteArray,
        meta: Map<String, String> = emptyMap(),
    ) {
        objects[key] = StoredObject(bytes, meta, Instant.now())
    }

    fun seedFrom(
        key: String,
        file: Path,
    ) = seed(key, java.nio.file.Files.readAllBytes(file))

    /** The bytes stored at [key], or null. */
    fun bytesOf(key: String): ByteArray? = objects[key]?.bytes

    fun keys(): List<String> = objects.keys.toList()

    /** Corrupt the first byte of the next PUT body on arrival, before the `Content-MD5` check and the store. */
    fun flipByteInNextPut() = flipNextPut.set(true)

    // ── the log ──

    /**
     * Where the log stands now: pass it to [requestsSince] to read what was sent after this point. It is the arrival number of
     * the last request that has arrived, not a count of log entries: the log is written when a request is answered, so a request
     * that is still being read at the mark is logged after later arrivals and a count would drop the wrong entries.
     */
    fun mark(): Long = seq.get()

    /** The requests logged so far, in arrival order. */
    fun requests(): List<Request> = log.sortedBy { it.seq }

    /** The requests that arrived after [mark] was taken. */
    fun requestsSince(mark: Long): List<Request> = requests().filter { it.seq > mark }

    fun connections(): List<Connection> = connectionLog.toList()

    /** The most requests that were between their first line and the end of their answer at the same time. */
    val peakInFlight: Int get() = peak.get()

    // ── serving ──

    /** Stops accepting, closes every connection and waits for the server's threads: nothing of it runs on after the test. */
    override fun close() {
        stopping.set(true)
        runCatching { server.close() }
        pool.shutdownNow()
        // Sockets are closed after the pool is shut down: one accepted in the meantime is closed by its acceptor (see acceptLoop).
        open.forEach { runCatching { it.close() } }
        check(pool.awaitTermination(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)) { "the logging S3 server's threads did not end" }
    }

    private fun acceptLoop() {
        while (!stopping.get()) {
            val socket =
                try {
                    server.accept()
                } catch (_: IOException) {
                    return
                }
            open += socket
            try {
                pool.execute { serve(socket) }
            } catch (_: RejectedExecutionException) {
                // close() won the race: this connection was accepted after the pool stopped, nobody would serve or close it.
                runCatching { socket.close() }
                open -= socket
                return
            }
        }
    }

    private fun serve(socket: Socket) {
        val connection = Connection(connectionIds.incrementAndGet(), socket.port)
        connectionLog += connection
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), BUFFER)
            val output = BufferedOutputStream(socket.getOutputStream(), BUFFER)
            while (!stopping.get()) {
                val head = readHead(input)
                if (head == null) {
                    connection.closedBy = Closer.CLIENT
                    return
                }
                if (!answer(connection, head, input, output)) {
                    connection.closedBy = Closer.SERVER
                    return
                }
            }
        } catch (_: IOException) {
            // A reset, or an EOF inside a request: the peer went away.
            if (connection.closedBy == null && !stopping.get()) connection.closedBy = Closer.CLIENT
        } finally {
            runCatching { socket.close() }
            open -= socket
        }
    }

    // Reads and answers one request; false when the connection is to be closed after the answer.
    private fun answer(
        connection: Connection,
        head: Head,
        input: InputStream,
        output: BufferedOutputStream,
    ): Boolean {
        val arrival = seq.incrementAndGet()
        connection.requestCount.incrementAndGet()
        peak.accumulateAndGet(inFlight.incrementAndGet()) { a, b -> maxOf(a, b) }
        try {
            return respond(connection, head, input, output, arrival)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun respond(
        connection: Connection,
        head: Head,
        input: InputStream,
        output: BufferedOutputStream,
        arrival: Long,
    ): Boolean {
        if (head.headers["expect"].equals("100-continue", ignoreCase = true)) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
        var body = readBody(input, head.headers)
        val path = head.target.substringBefore('?')
        val rawQuery = head.target.substringAfter('?', "")
        val params = parseQuery(rawQuery)
        val parts = decodePath(path).removePrefix("/").split('/', limit = 2)
        val bucketName = parts[0]
        val key = parts.getOrElse(1) { "" }
        val objectBody = head.method == "PUT" && key.isNotEmpty() && body.isNotEmpty()
        if (objectBody && flipNextPut.compareAndSet(true, false)) {
            body = body.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        }
        val response = route(head, bucketName, key, params, body)
        val closing = head.version != "HTTP/1.1" || head.headers["connection"]?.contains("close", ignoreCase = true) == true
        log +=
            Request(
                seq = arrival,
                connection = connection.id,
                clientPort = connection.clientPort,
                method = head.method,
                key = key,
                query = rawQuery,
                params = params,
                status = response.status,
                contentMd5 = head.headers["content-md5"],
                ifNoneMatch = head.headers["if-none-match"],
                signed = head.headers["authorization"]?.startsWith("AWS4-HMAC-SHA256 ") == true || "X-Amz-Signature" in params,
                bodyBytes = body.size.toLong(),
            )
        write(output, head.method == "HEAD", response, closing)
        return !closing
    }

    private fun write(
        output: BufferedOutputStream,
        head: Boolean,
        response: Response,
        closing: Boolean,
    ) {
        val text =
            buildString {
                append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
                response.headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
                append("Content-Length: ").append(response.contentLength).append("\r\n")
                append("x-amz-request-id: logging-s3\r\n")
                if (closing) append("Connection: close\r\n")
                append("\r\n")
            }
        output.write(text.toByteArray(Charsets.ISO_8859_1))
        if (!head) output.write(response.body)
        output.flush()
    }

    private fun route(
        head: Head,
        bucketName: String,
        key: String,
        params: Map<String, String>,
        body: ByteArray,
    ): Response =
        when {
            bucketName != bucket -> failure(404, "NoSuchBucket", "The specified bucket does not exist")
            key.isEmpty() -> bucketCall(head.method)
            "uploadId" in params || "partNumber" in params || "uploads" in params -> notImplemented("multipart uploads")
            else -> objectCall(head, key, body)
        }

    private fun bucketCall(method: String): Response =
        when {
            method == "HEAD" -> Response(200)
            method == "GET" -> xmlResponse("""<?xml version="1.0" encoding="UTF-8"?><ListBucketResult><Name>$bucket</Name><KeyCount>0</KeyCount><IsTruncated>false</IsTruncated></ListBucketResult>""")
            else -> notImplemented("$method on the bucket")
        }

    private fun objectCall(
        head: Head,
        key: String,
        body: ByteArray,
    ): Response =
        when (head.method) {
            "PUT" -> put(head.headers, key, body)
            "GET", "HEAD" -> objects[key]?.let(::objectResponse) ?: failure(404, "NoSuchKey", "The specified key does not exist.")
            "DELETE" -> Response(204).also { objects.remove(key) }
            else -> notImplemented("${head.method} on an object")
        }

    private fun put(
        headers: Map<String, String>,
        key: String,
        body: ByteArray,
    ): Response {
        val md5 = headers["content-md5"]
        val ifNoneMatch = headers["if-none-match"]
        val ifMatch = headers["if-match"]
        if (headers["x-amz-copy-source"] != null) return notImplemented("copy")
        if (md5 != null && md5 != Base64.getEncoder().encodeToString(md5Bytes(body))) {
            return failure(400, "BadDigest", "The Content-MD5 you specified did not match what we received.")
        }
        val stored = StoredObject(body, headers.filterKeys { it.startsWith("x-amz-meta-") }, Instant.now())
        // The check and the store are one step: of two concurrent creates with `If-None-Match: *`, one wins, as on S3.
        val refused =
            synchronized(writeLock) {
                val current = objects[key]
                val held =
                    when {
                        ifNoneMatch == "*" && current != null -> false
                        ifMatch != null && current?.etag?.let { "\"$it\"" } != ifMatch -> false
                        else -> true
                    }
                if (held) objects[key] = stored
                !held
            }
        return if (refused) {
            failure(412, "PreconditionFailed", "At least one of the pre-conditions you specified did not hold")
        } else {
            Response(200, mapOf("ETag" to "\"${stored.etag}\""))
        }
    }

    private fun objectResponse(stored: StoredObject): Response =
        Response(
            200,
            mapOf(
                "ETag" to "\"${stored.etag}\"",
                "Last-Modified" to DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(stored.modified, ZoneOffset.UTC)),
                "Content-Type" to "application/octet-stream",
                "Accept-Ranges" to "bytes",
            ) + stored.meta,
            stored.bytes,
        )

    private fun xmlResponse(text: String): Response = Response(200, mapOf("Content-Type" to "application/xml"), text.toByteArray(Charsets.UTF_8))

    private fun failure(
        status: Int,
        code: String,
        message: String,
    ): Response =
        Response(
            status,
            mapOf("Content-Type" to "application/xml"),
            """<?xml version="1.0" encoding="UTF-8"?><Error><Code>$code</Code><Message>${xml(message)}</Message></Error>""".toByteArray(Charsets.UTF_8),
        )

    private fun notImplemented(what: String): Response = failure(501, "NotImplemented", "LoggingS3Server does not serve $what")

    // ── HTTP framing ──

    private fun readHead(input: InputStream): Head? {
        val requestLine = readLine(input) ?: return null
        val words = requestLine.split(' ')
        if (words.size != 3) throw IOException("not an HTTP request line: '$requestLine'")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: throw EOFException("EOF inside the request headers")
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        return Head(words[0], words[1], words[2], headers)
    }

    // null only at a clean EOF before the first byte of a line.
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else throw EOFException("EOF inside a line")
            if (b == '\n'.code) return line.toString()
            if (b != '\r'.code) line.append(b.toChar())
        }
    }

    private fun readBody(
        input: InputStream,
        headers: Map<String, String>,
    ): ByteArray {
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) return readChunked(input)
        val length = headers["content-length"]?.toIntOrNull() ?: return ByteArray(0)
        return input.readNBytes(length).also { if (it.size != length) throw EOFException("EOF inside a body of $length bytes") }
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val size = (readLine(input) ?: throw EOFException("EOF before a chunk size")).substringBefore(';').trim().toInt(RADIX_HEX)
            if (size == 0) {
                while (readLine(input)?.isNotEmpty() == true) Unit // trailers, up to the blank line
                return out.toByteArray()
            }
            val chunk = input.readNBytes(size)
            if (chunk.size != size) throw EOFException("EOF inside a chunk")
            out.write(chunk)
            readLine(input)
        }
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
        }

    // A path keeps its '+' (it is a plus, not a space).
    private fun decodePath(path: String): String = URLDecoder.decode(path.replace("+", "%2B"), Charsets.UTF_8)

    private fun reason(status: Int): String =
        when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            412 -> "Precondition Failed"
            501 -> "Not Implemented"
            else -> "Status"
        }

    companion object {
        private const val BACKLOG = 128
        private const val CLOSE_WAIT_SECONDS = 10L
        private const val BUFFER = 64 * 1024
        private const val RADIX_HEX = 16

        private fun md5Bytes(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

        private fun md5Hex(bytes: ByteArray): String = HexFormat.of().formatHex(md5Bytes(bytes))

        private fun xml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")



        /** The requests per object key, in arrival order; bucket-level calls (listings, multi-delete) are left out. */
        fun perKey(requests: List<Request>): Map<String, List<Request>> = requests.filter { it.key.isNotEmpty() }.groupBy { it.key }

        fun connectionCount(requests: List<Request>): Int = requests.map { it.connection }.toSet().size

        /**
         * The observed counts as one text for an assertion message: requests by kind, connections, and the most common
         * per-object sequences (for example `[PUT HEAD] x42`).
         */
        fun summary(requests: List<Request>): String {
            val byKind = requests.groupingBy { it.label }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} x${it.value}" }
            val sequences =
                perKey(requests).values.groupingBy { calls -> calls.joinToString(" ") { it.label + if (it.status >= 400) "(${it.status})" else "" } }
                    .eachCount().entries.sortedByDescending { it.value }.take(SEQUENCES_SHOWN).joinToString("; ") { "[${it.key}] x${it.value}" }
            return "${requests.size} request(s) on ${connectionCount(requests)} connection(s): $byKind; per object: $sequences"
        }

        private const val SEQUENCES_SHOWN = 4
    }
}
