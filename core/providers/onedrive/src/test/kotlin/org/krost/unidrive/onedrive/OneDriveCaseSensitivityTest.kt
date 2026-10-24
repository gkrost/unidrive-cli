package org.krost.unidrive.onedrive

import org.krost.unidrive.CaseSensitivity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OneDriveCaseSensitivityTest {
    @Test
    fun `OneDrive declares case-insensitive, case-preserving names`() {
        val provider = OneDriveProvider(OneDriveConfig(tokenPath = Files.createTempDirectory("od-case")))
        assertEquals(CaseSensitivity.INSENSITIVE_PRESERVING, provider.caseSensitivity)
        // The provider's own name comparison agrees.
        assertTrue(OneDriveServerNames.sameName("case1.txt", "CASE1.txt"))
    }
}
