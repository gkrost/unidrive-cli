package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A folder move takes the keys, sizes and ETags from its listing: no HEAD per key, only for an object above the
 * single-copy limit, whose multipart copy writes its mtime again. The copies run several at a time.
 */
class S3FolderMoveRequestsTest {
    private val dir = Files.createTempDirectory("s3-folder-move")

    private fun provider(fake: FakeS3) = S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir), fake.engine)

    private fun FakeS3.heads() = count { it.method == HttpMethod.Head }

    private fun FakeS3.copies() = count { it.method == HttpMethod.Put && it.headers.containsKey("x-amz-copy-source") }

    @Test
    fun `a folder of small objects moves with no HEAD per key`() =
        runTest {
            val fake = FakeS3()
            repeat(40) { fake.put("d/f$it.txt", "content $it") }

            provider(fake).move("/d", "/e")

            assertEquals((0 until 40).map { "e/f$it.txt" }.toSet(), fake.objects.keys.toSet())
            assertEquals((0 until 40).map { "content $it" }.toSet(), fake.objects.keys.map { fake.text(it) }.toSet())
            // Before: one HEAD for the folder path plus one per key.
            assertEquals(1, fake.heads(), "only the HEAD that tells a file from a folder")
            assertEquals(40, fake.copies())
        }

    @Test
    fun `an object above the single-copy limit is HEADed for its mtime and copied in parts`() =
        runTest {
            val fake = FakeS3()
            fake.put("d/small.txt", "small")
            fake.putSized("d/huge.bin", S3Config.MAX_SINGLE_COPY + 1)

            provider(fake).move("/d", "/e")

            assertEquals(setOf("e/small.txt", "e/huge.bin"), fake.objects.keys.toSet())
            assertEquals(2, fake.heads(), "the folder probe and the one large object")
        }

    @Test
    fun `a key gone between the listing and its copy is skipped and the rest moves`() =
        runTest {
            val fake = FakeS3()
            fake.put("d/a.txt", "a")
            fake.put("d/b.txt", "b")
            fake.failures += Triple({ r: FakeS3.Request -> r.headers["x-amz-copy-source"]?.endsWith("d/a.txt") == true }, 404, "NoSuchKey")

            provider(fake).move("/d", "/e")

            assertEquals(setOf("e/b.txt"), fake.objects.keys.toSet())
        }
}
