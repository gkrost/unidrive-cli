package org.krost.unidrive.s3

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An object key may start with a slash (another tool wrote `/a.txt`). `keyOf` used to strip every leading slash, so
 * the object was listed at `//a.txt` but every operation on that path addressed `a.txt`: the wrong object was read,
 * touched or deleted (#82 E). One leading slash is stripped, and the path maps back to the key it came from.
 */
class S3LeadingSlashKeyTest {
    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("s3-slash")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun provider(fake: FakeS3) =
        S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir), fake.engine)

    @Test
    fun `key and path round-trip`() {
        assertEquals("", S3Provider.keyOf("/"))
        assertEquals("", S3Provider.keyOf(""))
        assertEquals("a/b.txt", S3Provider.keyOf("/a/b.txt"))
        assertEquals("/a.txt", S3Provider.keyOf("//a.txt"))
        assertEquals("//a.txt", S3Provider.pathOf("/a.txt"))
        assertEquals("/", S3Provider.pathOf(""))
        for (key in listOf("a.txt", "a/b.txt", "/a.txt", "a//b.txt", "spa ce.txt")) {
            assertEquals(key, S3Provider.keyOf(S3Provider.pathOf(key)), "key '$key' must come back from its path")
        }
    }

    @Test
    fun `an object whose key starts with a slash is listed, read and deleted at its own key`() = runTest {
        val fake = FakeS3()
        fake.put("a.txt", "PLAIN")
        val bytes = "SLASHY".toByteArray()
        fake.objects["/a.txt"] = FakeS3.Obj(bytes, bytes.size.toLong(), FakeS3.md5Hex(bytes), emptyMap(), Instant.parse("2026-01-01T00:00:00Z"))
        val provider = provider(fake)

        // The flat listing of the bucket shows the object at the path its key maps to. A delimited listing cannot
        // show it as a child anywhere: S3 rolls the key into the "/" common prefix. What the defect broke was the
        // addressing: every operation on '//a.txt' used to hit the plain object at 'a.txt' instead.
        val flat = provider.delta(null).items.single { it.id == "/a.txt" }
        assertEquals("//a.txt", flat.path)

        assertEquals(bytes.size.toLong(), provider.getMetadata("//a.txt").size, "the metadata must be the slash-key object's, not the plain one's")

        val destination = dir.resolve("downloaded.txt")
        provider.download("//a.txt", destination)
        assertEquals("SLASHY", Files.readString(destination))

        provider.delete("//a.txt", ifMatchETag = null)
        assertNull(fake.objects["/a.txt"], "the delete must take the slash-key object")
        assertEquals("PLAIN", fake.text("a.txt"), "the plain object at the stripped path must survive")
    }
}
