package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentUploadFailureException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Candidate P6 of the audit: a folder created under a name with a leading or trailing space. The create sends the raw name and
 * caches the trimmed one, the path walk looks the raw segment up; the listing trims. Either the folder is usable under the path
 * it was created with, or it is refused for good at the create: it must not be created and then be "not found".
 */
class InternxtEdgeSpaceFolderTest {
    @Test
    fun `a folder created under a name with edge spaces is usable under that path or refused at the create`() =
        runTest {
            val tmp = Files.createTempDirectory("ud-edge-space-")
            try {
                val drive = UploadDrive()
                val provider = rootedInternxtProvider(tmp, drive.engine)
                try {
                    val created = runCatching { provider.createFolder("/ docs ") }
                    created.exceptionOrNull()?.let { assertTrue(it is PermanentUploadFailureException, "refused, but not for good: $it") }
                    if (created.isSuccess) {
                        val listed = runCatching { provider.listChildren("/ docs ") }
                        assertTrue(listed.isSuccess, "the folder was created and then not found: ${listed.exceptionOrNull()?.message}")
                        assertEquals(emptyList(), listed.getOrThrow())
                    }
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
}
