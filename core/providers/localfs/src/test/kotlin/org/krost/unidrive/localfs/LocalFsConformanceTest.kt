package org.krost.unidrive.localfs

import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.testing.CloudProviderConformanceTest
import java.nio.file.Files
import java.nio.file.Path

/**
 * The SPI's conformance contracts against the real localfs provider over a fresh directory.
 *
 * The declared decisions the suite pins here: localfs is path-addressed (its item ids ARE the remote paths, and the
 * engine never routes its rows by id), so `deleteById`/`moveById` keep the documented path fallback; and a
 * filesystem copy carries no optimistic-concurrency token, so `ifMatchETag` is accepted and ignored on a replace
 * and on a delete ([LocalFsProvider.upload], [LocalFsProvider.delete]). The root directory is the credential:
 * there is no stored credential file to remove.
 */
class LocalFsConformanceTest : CloudProviderConformanceTest() {
    override val idAddressedDelete: Boolean = false
    override val idAddressedMove: Boolean = false
    override val guardedUpload: Boolean = false
    override val guardedDelete: Boolean = false

    override fun newProvider(): CloudProvider = LocalFsProvider(Files.createTempDirectory("conformance-localfs"))

    override suspend fun seedFile(
        provider: CloudProvider,
        path: String,
        content: String,
    ): CloudItem = provider.upload(localFile(content), path)

    override fun localFile(content: String): Path =
        Files.createTempFile("conformance-local", ".txt").also { Files.writeString(it, content) }
}
