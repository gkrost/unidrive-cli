package org.krost.unidrive.cli

import org.krost.unidrive.internxt.InternxtProviderFactory
import org.krost.unidrive.sync.SyncConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The profile key `internxt_strict_names` travels from config.toml through the properties map into the provider. */
class InternxtStrictNamesKeyTest {
    // The provider's answer for a new item at [path], under a profile section that adds [line].
    private fun issueUnder(
        line: String,
        path: String,
    ): String? {
        val raw = SyncConfig.parseRaw("[providers.ix]\ntype = \"internxt\"\n$line\n").providers.getValue("ix")
        val tokenDir = Files.createTempDirectory("ud-strict-key")
        val provider = InternxtProviderFactory(credentialProtector = null).create(Main().rawProviderToProperties(raw), tokenDir)
        try {
            return provider.newNameIssue(path)
        } finally {
            provider.close()
            tokenDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the key on makes the provider refuse a name the Internxt clients reject`() {
        assertEquals("name_incompatible: windows_character", issueUnder("internxt_strict_names = true", "/a:b"))
    }

    @Test
    fun `the key off or absent leaves names as they are`() {
        assertNull(issueUnder("internxt_strict_names = false", "/a:b"))
        assertNull(issueUnder("", "/a:b"))
    }
}
