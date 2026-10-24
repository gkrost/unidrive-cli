package org.krost.unidrive.internxt

import org.krost.unidrive.CaseSensitivity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class InternxtCaseSensitivityTest {
    @Test
    fun `Internxt declares case-sensitive names`() {
        val provider =
            InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = Files.createTempFile("inxt-case-token", ".tmp")))
        assertEquals(CaseSensitivity.SENSITIVE, provider.caseSensitivity)
    }
}
