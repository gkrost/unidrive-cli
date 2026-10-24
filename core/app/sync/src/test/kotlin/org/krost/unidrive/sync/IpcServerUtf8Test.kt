package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.coroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * archive#1054 S2: a request line is bytes until its newline. A read that ends inside a multi-byte UTF-8 character used to be
 * decoded on its own, so the character became U+FFFD and a path in create/rename/mkdir was corrupted (raw-UTF-8 clients
 * such as the FUSE co-daemon; the .NET client escapes non-ASCII).
 */
class IpcServerUtf8Test {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-utf8")
        socketPath = socketDir.resolve("test.sock")
    }

    @AfterTest
    fun tearDown() {
        server?.close()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketDir.resolve("test.sock.meta"))
        Files.deleteIfExists(socketDir)
    }

    private fun write(
        client: SocketChannel,
        bytes: ByteArray,
    ) {
        val w = ByteBuffer.wrap(bytes)
        while (w.hasRemaining()) client.write(w)
    }

    private suspend fun readLine(client: SocketChannel): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(4096)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            buf.clear()
            val n = client.read(buf)
            if (n > 0) {
                out.write(buf.array(), 0, n)
                if (out.toByteArray().contains('\n'.code.toByte())) break
            } else {
                delay(20)
            }
        }
        return out.toByteArray().decodeToString().trim()
    }

    /** One request line sent in the given pieces (a pause between them), answered by an echo of its `path` field. */
    private fun echoedPath(pieces: (ByteArray) -> List<ByteArray>): String =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                server = IpcServer(socketPath)
                server!!.registerHandler("echo.path") { _, json ->
                    val path = Json.parseToJsonElement(json).jsonObject["path"]!!.jsonPrimitive.content
                    """{"ok":true,"path":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(path))}}"""
                }
                server!!.start(serverScope)
                delay(100)
                val client = SocketChannel.open(StandardProtocolFamily.UNIX)
                client.connect(UnixDomainSocketAddress.of(socketPath))
                client.configureBlocking(false)
                val request = ("""{"verb":"echo.path","path":"$PATH"}""" + "\n").toByteArray(Charsets.UTF_8)
                for (piece in pieces(request)) {
                    write(client, piece)
                    delay(250) // the server's reader has run on this piece before the next one arrives
                }
                val reply = Json.parseToJsonElement(readLine(client)).jsonObject
                client.close()
                reply["path"]!!.jsonPrimitive.content
            } finally {
                serverScope.cancel()
            }
        }

    @Test
    fun `a request split inside a multi-byte character reaches the handler intact`() {
        val seen =
            echoedPath { request ->
                // cut between the two bytes of the Cyrillic "д" (D0 B4)
                val cut = request.toList().windowed(2).indexOfFirst { it[0] == 0xD0.toByte() && it[1] == 0xB4.toByte() } + 1
                listOf(request.copyOfRange(0, cut), request.copyOfRange(cut, request.size))
            }

        assertEquals(PATH, seen)
    }

    @Test
    fun `a request split inside a four-byte character reaches the handler intact`() {
        val seen =
            echoedPath { request ->
                // cut after the first byte of the emoji (F0 9F 98 80)
                val cut = request.toList().indexOf(0xF0.toByte()) + 1
                listOf(request.copyOfRange(0, cut), request.copyOfRange(cut, request.size))
            }

        assertEquals(PATH, seen)
    }

    private companion object {
        // Cyrillic (2 bytes per character) and an emoji (4 bytes)
        const val PATH = "/каталог/файл-д-😀.txt"
    }
}
