package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#180: a folder created under a name whose ends the server answer differs from (a space, a control
 * character) is created and then never found. The create must cache and compare by the form the listing shows, and
 * [InternxtProvider.sanitizeName] keeps a leading or trailing space (#144) while it strips a control character at either end.
 */
class InternxtEdgeSpaceFolderTest {
    private fun withProvider(block: suspend (InternxtProvider, UploadDrive) -> Unit) =
        runTest {
            val tmp = Files.createTempDirectory("ud-edge-space-")
            try {
                val drive = UploadDrive()
                val provider = rootedInternxtProvider(tmp, drive.engine)
                try {
                    block(provider, drive)
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a folder created under a name with edge spaces is found again from the cache and from a listing`() =
        withProvider { provider, drive ->
            provider.createFolder("/ docs ")
            assertEquals(emptyList(), provider.listChildren("/ docs "), "from the cache the create filled")
            val tmp = Files.createTempDirectory("ud-edge-space-2-")
            try {
                // A second provider over the same drive has no cache: the folder is found in the listing.
                val fresh = rootedInternxtProvider(tmp, drive.engine)
                try {
                    assertEquals(emptyList(), fresh.listChildren("/ docs "), "from a listing")
                } finally {
                    fresh.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    @Test
    fun `a folder created under a name with a trailing control character is found again under the same path`() =
        withProvider { provider, drive ->
            provider.createFolder("/docs\n")
            val listed = runCatching { provider.listChildren("/docs\n") }
            assertTrue(listed.isSuccess, "the folder was created and then not found: ${listed.exceptionOrNull()?.message}")
            assertEquals(emptyList(), listed.getOrThrow())
            assertEquals(1, drive.folders.size, "the create made one folder")
            val tmp = Files.createTempDirectory("ud-edge-ctl-2-")
            try {
                val fresh = rootedInternxtProvider(tmp, drive.engine)
                try {
                    assertEquals(emptyList(), fresh.listChildren("/docs\n"), "from a listing")
                } finally {
                    fresh.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
}
