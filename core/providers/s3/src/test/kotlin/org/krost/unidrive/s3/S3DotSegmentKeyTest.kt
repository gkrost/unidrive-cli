package org.krost.unidrive.s3

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentUploadFailureException
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#845 item #748-4: the client normalises `.` and `..` out of the URL path, so a signature over such a key
 * covers another path than the one sent. Such keys are left out of listings and no request is ever signed for one.
 */
class S3DotSegmentKeyTest {
    private val dir = TestScratch.directory("s3-dot-segment")
    private val config = S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir)
    private val local = dir.resolve("local.bin").also { Files.write(it, "data".toByteArray()) }

    private fun provider(fake: FakeS3) = S3Provider(config, fake.engine)

    private suspend fun warnings(run: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(S3Provider::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val before = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            run()
        } finally {
            logger.detachAppender(appender)
            logger.level = before
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    @Test
    fun `the dot-segment predicate matches whole segments only`() {
        for (key in listOf("a/./b", "a/../b", "./x", "x/..", ".", "..", "a/b/.", "../x", "a//./b")) {
            assertTrue(S3Keys.hasDotSegment(key), "'$key' has a dot segment")
        }
        for (key in listOf("a.b", "..x", "x..", "a/.b/c", "a/b./c", "...", "a/.../b", ".hidden", "a/b", "")) {
            assertFalse(S3Keys.hasDotSegment(key), "'$key' has none")
        }
    }

    @Test
    fun `a listing leaves out keys with dot segments and warns naming each`() =
        runTest {
            val fake = FakeS3()
            fake.put("good.txt", "g")
            fake.put("a/./b.txt", "x")
            fake.put("a/../c.txt", "x")
            fake.put("dir/ok.txt", "o")
            fake.put("dir/./hidden.txt", "x")
            fake.put("a.b", "y")
            val provider = provider(fake)
            var delta = emptySet<String>()
            var root = emptySet<String>()
            var inDir = emptySet<String>()

            val warned =
                warnings {
                    delta = provider.delta(null).items.map { it.path }.toSet()
                    root = provider.listChildren("/").map { it.path }.toSet()
                    inDir = provider.listChildren("/dir").map { it.path }.toSet()
                }

            assertEquals(setOf("/good.txt", "/a.b", "/dir", "/dir/ok.txt"), delta)
            // "/a" is the common prefix "a/", a plain folder name; only the keys below it with a dot segment are left out.
            assertEquals(setOf("/good.txt", "/a.b", "/a", "/dir"), root)
            assertEquals(setOf("/dir/ok.txt"), inDir)
            for (key in listOf("a/./b.txt", "a/../c.txt", "dir/./hidden.txt")) {
                assertNotNull(warned.firstOrNull { "'$key'" in it }, "a WARN names '$key': $warned")
            }
        }

    @Test
    fun `the warnings of one listing stay bounded`() =
        runTest {
            val fake = FakeS3()
            repeat(100) { fake.put("d/./$it.txt", "x") }
            val provider = provider(fake)

            val warned = warnings { provider.delta(null) }

            assertTrue(warned.size <= 25, "at most the named keys and one summary, got ${warned.size}")
            assertTrue(warned.any { "100" in it }, "the summary counts them all: $warned")
        }

    @Test
    fun `no request is ever signed for a key with a dot segment`() =
        runTest {
            val calls = AtomicInteger()
            val api =
                S3Api(
                    config,
                    MockEngine {
                        calls.incrementAndGet()
                        respond("")
                    },
                )
            val bad = "a/../b"
            val ok = "a/b"
            val entryPoints: Map<String, suspend () -> Any?> =
                mapOf(
                    "head" to { api.head(bad) },
                    "get" to { api.get(bad, dir.resolve("out.bin")) },
                    "put" to { api.put(bad, local) },
                    "createMultipart" to { api.createMultipart(bad, emptyMap()) },
                    "uploadPart" to { api.uploadPart(bad, "u", 1, local, 0, 4, ByteArray(16)) },
                    "uploadPartCopy key" to { api.uploadPartCopy(bad, "u", 1, ok, 0L..3L) },
                    "uploadPartCopy source" to { api.uploadPartCopy(ok, "u", 1, bad, 0L..3L) },
                    "completeMultipart" to { api.completeMultipart(bad, "u", listOf(1 to "e")) },
                    "abortMultipart" to { api.abortMultipart(bad, "u") },
                    "listParts" to { api.listParts(bad, "u") },
                    "copy source" to { api.copy(bad, ok) },
                    "copy destination" to { api.copy(ok, bad) },
                    "delete" to { api.delete(bad) },
                    "deleteMany" to { api.deleteMany(listOf(ok, bad)) },
                    "presign" to { api.presign(bad, 60) },
                )
            for ((name, call) in entryPoints) {
                val e = assertFailsWith<S3DotSegmentKeyException>("$name must refuse before signing") { call() }
                assertTrue("a/../b" in e.message!!, "$name names the key: ${e.message}")
            }
            assertEquals(0, calls.get(), "no request left the client")
        }

    @Test
    fun `an upload to a key with a dot segment fails permanently before any request`() =
        runTest {
            val fake = FakeS3()
            val provider = provider(fake)

            val e = assertFailsWith<PermanentUploadFailureException> { provider.upload(local, "/a/./b.txt") }
            assertTrue("a/./b.txt" in e.message!!)
            assertFailsWith<S3DotSegmentKeyException> { provider.download("/x/../y.txt", dir.resolve("d.bin")) }
            assertFailsWith<S3DotSegmentKeyException> { provider.delete("/x/../y.txt") }
            assertEquals(0, fake.requests.size, "nothing was sent")
        }

    @Test
    fun `ordinary keys still list and transfer`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.b", "1")
            fake.put("..x", "2")
            fake.put("x..", "3")
            fake.put("a/.b/c", "4")
            val provider = provider(fake)

            val listed = provider.delta(null).items.filter { !it.isFolder }.map { it.path }.toSet()
            assertEquals(setOf("/a.b", "/..x", "/x..", "/a/.b/c"), listed)

            provider.upload(local, "/up/ok.txt")
            val out = dir.resolve("got.bin")
            provider.download("/a/.b/c", out)
            assertEquals("4", Files.readString(out))
            provider.delete("/a.b")
            assertFalse(fake.objects.containsKey("a.b"))
            assertTrue(fake.objects.containsKey("up/ok.txt"))
        }

    @Test
    fun `a folder delete and a folder move leave keys with dot segments alone`() =
        runTest {
            val fake = FakeS3()
            fake.put("d/ok.txt", "o")
            fake.put("d/./x.txt", "x")
            fake.put("m/ok.txt", "o")
            fake.put("m/../y.txt", "y")
            val provider = provider(fake)

            provider.deleteById("d/", "/d")
            provider.move("/m", "/n")

            assertTrue(fake.objects.containsKey("d/./x.txt"), "the delete left the dot-segment key")
            assertFalse(fake.objects.containsKey("d/ok.txt"))
            assertTrue(fake.objects.containsKey("m/../y.txt"), "the move left the dot-segment key")
            assertTrue(fake.objects.containsKey("n/ok.txt"))
            assertFalse(fake.objects.containsKey("n/../y.txt"))
            val named = fake.requests.filter { (it.key ?: "").let { k -> "/./" in k || "/../" in k } || "/./" in String(it.body) || "/../" in String(it.body) }
            assertEquals(emptyList(), named.map { it.method to it.key }, "no request named a dot-segment key")
        }

    @Test
    fun `a resumed listing drops staged items whose key has a dot segment`() =
        runTest {
            val fake = FakeS3()
            fake.put("b.txt", "2")
            val staged =
                listOf(
                    org.krost.unidrive.CloudItem("a.txt", "", "placeholder", 1, false, null, null, null, null),
                    org.krost.unidrive.CloudItem("r/../z.txt", "", "placeholder", 1, false, null, null, null, null),
                    org.krost.unidrive.CloudItem("q/./", "", "placeholder", 0, true, null, null, null, null),
                )
            val context = org.krost.unidrive.ScanContext("a.txt", staged, { _, _ -> })

            val paths = provider(fake).delta(null, scanContext = context).items.map { it.path }.toSet()

            assertEquals(setOf("/a.txt", "/b.txt"), paths)
        }
}
