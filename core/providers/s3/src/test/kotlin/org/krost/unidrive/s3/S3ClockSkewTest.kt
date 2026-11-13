package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A host clock more than 15 minutes off (a VM resumed with a stale clock) makes S3 refuse every signature with
 * 403 RequestTimeTooSkewed. The client learns S3's time from the refusal, signs again once, and keeps the offset.
 */
class S3ClockSkewTest {
    private val dir = Files.createTempDirectory("s3-skew")
    private val serverNow = Instant.parse("2026-06-01T12:00:00Z")

    private fun provider(
        fake: FakeS3,
        hostOffset: Duration,
    ): S3Provider {
        fake.serverClock = serverNow
        return S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir), fake.engine, clock = { serverNow.plus(hostOffset) })
    }

    private fun refusals(fake: FakeS3): Int =
        fake.requests.count { r ->
            val signedAt = java.time.LocalDateTime.parse(r.headers.getValue("x-amz-date"), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
            kotlin.math.abs(Duration.between(signedAt.toInstant(java.time.ZoneOffset.UTC), serverNow).toMinutes()) >= 15
        }

    @Test
    fun `a host clock an hour behind is corrected once and kept for every later request`() =
        runTest {
            val fake = FakeS3()
            fake.put("docs/a.txt", "hello")
            val p = provider(fake, Duration.ofHours(-1))

            p.authenticate()
            assertEquals(listOf("a.txt"), p.listChildren("/docs").map { it.name })
            val dest = Files.createTempDirectory("s3-skew-out").resolve("a.txt")
            assertEquals(5L, p.download("/docs/a.txt", dest))
            val src = Files.createTempFile("s3-skew", ".txt").also { Files.writeString(it, "new") }
            p.upload(src, "/docs/b.txt")

            assertEquals(1, refusals(fake), "only the first request went out with the host's own time")
            assertEquals("new", fake.text("docs/b.txt"))
        }

    @Test
    fun `a download that meets the skew first is signed again and succeeds`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "hello")
            val p = provider(fake, Duration.ofMinutes(40))

            val dest = Files.createTempDirectory("s3-skew-out").resolve("a.txt")
            assertEquals(5L, p.download("/a.txt", dest))

            assertEquals(2, fake.count { it.method == HttpMethod.Get })
            assertEquals(1, refusals(fake))
        }

    @Test
    fun `a HEAD refused without a body is corrected from the Date header`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "hello")
            val p = provider(fake, Duration.ofHours(2))

            assertEquals(5L, p.getMetadata("/a.txt").size)

            assertEquals(1, refusals(fake))
        }

    @Test
    fun `a host clock within the allowed skew is left alone`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "hello")
            val p = provider(fake, Duration.ofMinutes(-5))

            p.listChildren("/")
            p.getMetadata("/a.txt")

            assertEquals(0, refusals(fake))
            assertEquals(2, fake.requests.size)
        }
}
