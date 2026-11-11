package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#570: a single-part PUT carries the `Content-MD5` of its body, taken in the read of the file that also makes
 * the SHA-256 upload proof, so S3 refuses (400 `BadDigest`, nothing stored) bytes that are not the ones that were read. The
 * tree-wide check of the header is in [S3RequestPatternTest]; this has the edges and the failure.
 */
class S3ContentMd5Test {
    private val dir = Files.createTempDirectory("s3-content-md5")
    private val mib = 1024L * 1024

    private fun provider(
        fake: FakeS3,
        threshold: Long = S3Config.MULTIPART_THRESHOLD,
    ) = S3Provider(
        S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir, multipartThreshold = threshold, minPartSize = mib),
        fake.engine,
    )

    private fun local(
        name: String,
        bytes: ByteArray,
    ): Path = dir.resolve(name).also { Files.write(it, bytes) }

    private fun md5(bytes: ByteArray) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))

    private fun puts(fake: FakeS3) = fake.requests.filter { it.method == HttpMethod.Put && it.key != null && !it.key.endsWith("/") }

    @Test
    fun `a create and a modify both carry the MD5 of the bytes the server received`() =
        runTest {
            val fake = FakeS3()
            val s3 = provider(fake)

            val first = s3.upload(local("a.txt", "one".toByteArray()), "/a.txt")
            s3.upload(local("a.txt", "two, a little longer".toByteArray()), "/a.txt", existingRemoteId = first.id)

            val sent = puts(fake)
            assertEquals(2, sent.size)
            sent.forEach { assertEquals(md5(it.body), it.headers["content-md5"], "the header is the MD5 of the received body") }
            assertEquals("two, a little longer", fake.text("a.txt"))
        }

    @Test
    fun `an empty file carries the MD5 of nothing`() =
        runTest {
            val fake = FakeS3()

            provider(fake).upload(local("empty", ByteArray(0)), "/empty")

            assertEquals("1B2M2Y8AsgTpgAmY7PhCfg==", puts(fake).single().headers["content-md5"])
        }

    @Test
    fun `a file exactly at the multipart threshold is still one PUT with the header, one above it has none on the complete`() =
        runTest {
            val fake = FakeS3()
            val s3 = provider(fake, threshold = mib)
            val atThreshold = ByteArray(mib.toInt()) { (it % 251).toByte() }

            s3.upload(local("at.bin", atThreshold), "/at.bin")
            assertEquals(md5(atThreshold), puts(fake).single { it.key == "at.bin" }.headers["content-md5"])

            s3.upload(local("above.bin", ByteArray((2.5 * mib).toInt()) { 7 }), "/above.bin")
            val complete = fake.requests.last { it.method == HttpMethod.Post && "uploadId" in it.query }
            assertNull(complete.headers["content-md5"], "the multipart complete has no body to digest; its parts carry theirs")
            assertTrue(fake.requests.filter { it.key == "above.bin" && "partNumber" in it.query }.all { it.headers["content-md5"] != null })
        }

    @Test
    fun `a file changed between the read and the PUT is refused by BadDigest and nothing is stored`() =
        runTest {
            val fake = FakeS3()
            val file = local("a.txt", ByteArray(4096) { 1 })
            var changed = false
            fake.beforeBody = { method, key ->
                if (method == HttpMethod.Put && key == "a.txt" && !changed) {
                    changed = true
                    Files.write(file, ByteArray(4096) { 2 })
                }
            }

            val failure = assertFailsWith<S3Exception> { provider(fake).upload(file, "/a.txt") }

            assertEquals("BadDigest", failure.code)
            assertEquals(400, failure.status)
            assertTrue("changed on disk" in failure.message.orEmpty(), "the message says what it is: ${failure.message}")
            assertNull(fake.objects["a.txt"], "the object with the wrong bytes does not exist")
        }

    @Test
    fun `the next attempt after a BadDigest sends the file as it is now`() =
        runTest {
            val fake = FakeS3()
            val file = local("a.txt", ByteArray(4096) { 1 })
            var changed = false
            fake.beforeBody = { method, key ->
                if (method == HttpMethod.Put && key == "a.txt" && !changed) {
                    changed = true
                    Files.write(file, ByteArray(4096) { 2 })
                }
            }
            val s3 = provider(fake)

            assertFailsWith<S3Exception> { s3.upload(file, "/a.txt") }
            s3.upload(file, "/a.txt")

            assertEquals(ByteArray(4096) { 2 }.toList(), fake.objects.getValue("a.txt").bytes!!.toList())
        }

    @Test
    fun `a server that calls the digest invalid is a failure that says so, not an authentication error`() =
        runTest {
            val fake = FakeS3()
            fake.failures += Triple({ r: FakeS3.Request -> r.headers.containsKey("content-md5") }, 400, "InvalidDigest")

            val failure = assertFailsWith<S3Exception> { provider(fake).upload(local("a.txt", "x".toByteArray()), "/a.txt") }

            assertEquals("InvalidDigest", failure.code)
            assertTrue("Content-MD5" in failure.message.orEmpty(), failure.message)
        }
}
