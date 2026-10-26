package org.krost.unidrive.s3

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * XML 1.0 cannot carry a control character such as U+0008, so S3 documents `encoding-type=url` for keys that have
 * one ("Amazon S3 ... XML 1.0 parser can't parse certain characters, such as characters with an ASCII value from
 * 0 to 10", ListObjectsV2 request parameters). Without it, one such key fails the XML parse and with it the whole
 * listing, including every ordinary key beside it.
 */
class S3ControlCharKeyListingTest {
    private val dir = Files.createTempDirectory("s3-control-char")

    @Test
    fun `a key with a control character does not fail the listing of its neighbours`() =
        runTest {
            val fake = FakeS3()
            fake.put("good.txt", "g")
            fake.put("a\u0008b.txt", "x")
            fake.put("sp ace.txt", "s")
            val provider = S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir), fake.engine)

            val names = provider.listChildren("/").map { it.name }.toSet()

            assertEquals(setOf("good.txt", "a\u0008b.txt", "sp ace.txt"), names)
        }
}
