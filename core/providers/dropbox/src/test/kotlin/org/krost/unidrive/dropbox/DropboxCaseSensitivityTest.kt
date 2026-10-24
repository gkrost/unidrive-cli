package org.krost.unidrive.dropbox

import org.krost.unidrive.CaseSensitivity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DropboxCaseSensitivityTest {
    @Test
    fun `Dropbox declares case-insensitive, case-preserving names`() {
        val provider = DropboxProvider(DropboxConfig(appKey = "key", tokenPath = Files.createTempDirectory("dbx-case")), FakeDropbox().engine)
        assertEquals(CaseSensitivity.INSENSITIVE_PRESERVING, provider.caseSensitivity)
    }
}
