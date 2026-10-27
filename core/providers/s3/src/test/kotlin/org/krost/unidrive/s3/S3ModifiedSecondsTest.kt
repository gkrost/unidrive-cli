package org.krost.unidrive.s3

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A listing reports the object's time with milliseconds, a HEAD's RFC-1123 date only whole seconds. The engine
 * compares the time it recorded with the listed one, so the sub-second part made every uploaded file read as
 * remotely changed: it was re-downloaded once, and a local edit in between produced a false conflict copy (#82 D).
 * Both sources are therefore cut to whole seconds.
 */
class S3ModifiedSecondsTest {
    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("s3-seconds")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun provider(fake: FakeS3) =
        S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir), fake.engine)

    @Test
    fun `a listing reports whole seconds even when the store carries milliseconds`() = runTest {
        val fake = FakeS3()
        val bytes = "T".toByteArray()
        fake.objects["t.txt"] = FakeS3.Obj(bytes, 1, FakeS3.md5Hex(bytes), emptyMap(), Instant.parse("2026-01-01T00:00:00.500Z"))
        val provider = provider(fake)

        val listed = provider.listChildren("/").single()

        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), listed.modified)
    }

    @Test
    fun `the listing and the HEAD of one object report the same time`() = runTest {
        val fake = FakeS3()
        val bytes = "T".toByteArray()
        fake.objects["t.txt"] = FakeS3.Obj(bytes, 1, FakeS3.md5Hex(bytes), emptyMap(), Instant.parse("2026-01-01T00:00:00.750Z"))
        val provider = provider(fake)

        val listed = provider.listChildren("/").single()
        val headed = provider.getMetadata("/t.txt")

        assertEquals(listed.modified, headed.modified)
    }

    @Test
    fun `an upload is not re-listed as remotely changed`() = runTest {
        val fake = FakeS3()
        val provider = provider(fake)
        val local = dir.resolve("u.txt").also {
            Files.writeString(it, "upload")
            Files.setLastModifiedTime(it, java.nio.file.attribute.FileTime.from(Instant.parse("2026-02-03T04:05:06Z")))
        }

        val uploaded = provider.upload(local, "/u.txt", existingRemoteId = null, ifMatchETag = null, onProgress = null)
        val listed = provider.listChildren("/").single()

        assertEquals(uploaded.modified, listed.modified, "the time the engine recorded at upload must equal the listed one")
    }
}
