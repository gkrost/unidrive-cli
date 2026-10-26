package org.krost.unidrive.localfs

import kotlinx.coroutines.test.runTest
import java.io.FileNotFoundException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * archive#1256 A: the local-directory provider answers a missing item with a [FileNotFoundException], the signal the
 * engine's one rule (`RemoteErrors.isNotFound`) reads.
 */
class LocalFsNotFoundSignalTest {
    @Test
    fun `a missing item is a FileNotFoundException`() =
        runTest {
            val provider = LocalFsProvider(Files.createTempDirectory("localfs-notfound"))
            provider.authenticate()

            assertFailsWith<FileNotFoundException> { provider.getMetadata("/missing.txt") }
        }
}
