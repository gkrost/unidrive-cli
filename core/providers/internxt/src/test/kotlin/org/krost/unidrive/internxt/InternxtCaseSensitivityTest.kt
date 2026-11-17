package org.krost.unidrive.internxt

import org.krost.unidrive.CaseSensitivity
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import org.krost.unidrive.testing.TestScratch

class InternxtCaseSensitivityTest {
    @Test
    fun `Internxt declares case-sensitive names`() {
        val provider =
            InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = TestScratch.file("inxt-case-token", ".tmp")))
        assertEquals(CaseSensitivity.SENSITIVE, provider.caseSensitivity)
    }
}
