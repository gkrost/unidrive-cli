package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.krost.unidrive.testing.TestScratch

/** #748 finding 9: deleting a file by id is one DELETE; S3's DELETE already answers for a key that is gone. */
class S3DeleteByIdRequestsTest {
    private val dir = TestScratch.directory("s3-delete-by-id")

    private fun provider(fake: FakeS3) = S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir), fake.engine)

    @Test
    fun `deleteById of a file sends no HEAD and removes the object`() =
        runTest {
            val fake = FakeS3()
            val p = provider(fake)
            p.upload(java.nio.file.Files.createTempFile(dir, "a", ".txt").also { java.nio.file.Files.writeString(it, "x") }, "/a.txt")
            val before = fake.requests.size

            p.deleteById("a.txt", "/a.txt")

            val sent = fake.requests.drop(before)
            assertFalse(sent.any { it.method == HttpMethod.Head }, "no HEAD before the DELETE: $sent")
            assertEquals(1, sent.count { it.method == HttpMethod.Delete })
            assertFalse(fake.objects.containsKey("a.txt"))
        }

    @Test
    fun `deleteById of a file that is gone already is done`() =
        runTest {
            provider(FakeS3()).deleteById("gone.txt", "/gone.txt")
        }
}
