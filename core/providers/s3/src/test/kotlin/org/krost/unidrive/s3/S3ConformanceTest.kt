package org.krost.unidrive.s3

import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.testing.CloudProviderConformanceTest
import java.nio.file.Files
import java.nio.file.Path

/**
 * The SPI's conformance contracts against [S3Provider] over [FakeS3], the stateful in-memory bucket.
 *
 * The declared decisions the suite pins here: the object keys are the item ids, so `deleteById` decides by the key
 * (the remote id) while `moveById` keeps the documented path fallback — with ids and paths the same address, either
 * names the same object; a stale `ifMatchETag` refuses the write and the delete (the HEAD comparison of
 * `requireUnchanged`, or the conditional write where the vendor has one); and the keys are the profile config
 * itself, so there is no stored credential file to remove.
 */
class S3ConformanceTest : CloudProviderConformanceTest() {
    override val idAddressedMove: Boolean = false

    private val dir = Files.createTempDirectory("conformance-s3")

    private lateinit var fake: FakeS3

    override fun newProvider(): CloudProvider {
        fake = FakeS3()
        return S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir), fake.engine)
    }

    override suspend fun seedFile(
        provider: CloudProvider,
        path: String,
        content: String,
    ): CloudItem = provider.upload(localFile(content), path)

    override fun localFile(content: String): Path =
        Files.createTempFile("conformance-local", ".txt").also { Files.writeString(it, content) }
}
