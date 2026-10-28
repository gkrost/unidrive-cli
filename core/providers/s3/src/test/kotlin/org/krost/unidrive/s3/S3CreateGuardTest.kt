package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteConflictException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#57: a create must not replace an object that appeared after the plan. The PUT (or the multipart complete)
 * carries `If-None-Match: *`, and a taken key is a conflict. A vendor that does not implement the header answers 501 or
 * 400: the provider then checks with a HEAD first and stops sending it.
 */
class S3CreateGuardTest {
    private val dir = Files.createTempDirectory("s3-create-guard")
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
    ) = dir.resolve(name).also { Files.write(it, bytes) }

    private fun puts(fake: FakeS3) = fake.requests.filter { it.method == HttpMethod.Put && it.key == "a.txt" }

    @Test
    fun `a create carries If-None-Match and a taken key is a conflict that leaves the object`() =
        runTest {
            val fake = FakeS3()
            val s3 = provider(fake)

            s3.upload(local("a.txt", "mine".toByteArray()), "/a.txt")
            assertEquals("*", puts(fake).single().headers["if-none-match"])
            assertEquals("mine", fake.text("a.txt"))

            fake.put("a.txt", "theirs")
            assertFailsWith<RemoteConflictException> { s3.upload(local("a.txt", "mine again".toByteArray()), "/a.txt") }
            assertEquals("theirs", fake.text("a.txt"))
        }

    @Test
    fun `a modify is not a create`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "old")

            provider(fake).upload(local("a.txt", "new".toByteArray()), "/a.txt", existingRemoteId = "a.txt")

            assertNull(puts(fake).single().headers["if-none-match"])
            assertEquals("new", fake.text("a.txt"))
        }

    @Test
    fun `a multipart create carries the guard on its complete`() =
        runTest {
            val fake = FakeS3()
            fake.put("big.bin", "theirs")
            val bytes = ByteArray((2.5 * mib).toInt()) { (it % 251).toByte() }

            assertFailsWith<RemoteConflictException> { provider(fake, threshold = mib).upload(local("big.bin", bytes), "/big.bin") }

            val complete = fake.requests.last { it.method == HttpMethod.Post && "uploadId" in it.query }
            assertEquals("*", complete.headers["if-none-match"])
            assertEquals("theirs", fake.text("big.bin"))
        }

    @Test
    fun `a vendor that answers 501 to the header gets a HEAD check instead, and the header is not sent again`() =
        runTest {
            val fake = FakeS3()
            fake.failures += Triple({ r: FakeS3.Request -> r.headers.containsKey("if-none-match") }, 501, "NotImplemented")
            val s3 = provider(fake)

            s3.upload(local("a.txt", "mine".toByteArray()), "/a.txt")
            assertEquals("mine", fake.text("a.txt"))

            fake.put("a.txt", "theirs")
            assertFailsWith<RemoteConflictException> { s3.upload(local("a.txt", "mine again".toByteArray()), "/a.txt") }
            assertEquals("theirs", fake.text("a.txt"))
            assertEquals(listOf("*", null), puts(fake).map { it.headers["if-none-match"] }, "one refused attempt with it, one without")
        }

    @Test
    fun `a vendor that answers 400 to the header falls back the same way`() =
        runTest {
            val fake = FakeS3()
            fake.failures += Triple({ r: FakeS3.Request -> r.headers.containsKey("if-none-match") }, 400, "InvalidArgument")

            provider(fake).upload(local("a.txt", "mine".toByteArray()), "/a.txt")

            assertEquals("mine", fake.text("a.txt"))
        }
}
