package org.krost.unidrive.cli

import org.krost.unidrive.sync.SyncConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The line cut of `vault encrypt` (issue#83 C): a header or key shape ktoml parses but the cut cannot match used to
 * leave the secret in config.toml, where it wins over the vault. Every shape here must be cut, and whatever the cut
 * cannot remove must abort instead of reporting success.
 */
class VaultEncryptFilterTest {
    private val vaulted = mapOf("s3" to mapOf("password" to "synthetic-secret"))

    @Test
    fun `a plain section is cut as before`() {
        val lines =
            listOf(
                "[general]",
                "locale = \"en\"",
                "[providers.s3]",
                "type = \"s3\"",
                "password = \"synthetic-secret\"",
            )

        val filtered = filterVaultedLines(lines, vaulted)

        assertFalse("synthetic-secret" in filtered.joinToString("\n"))
        assertEquals(null, SyncConfig.parseRaw(filtered.joinToString("\n")).providers.getValue("s3").password)
    }

    @Test
    fun `a section header with a trailing comment is cut`() {
        val lines =
            listOf(
                "[providers.s3] # prod",
                "password = \"synthetic-secret\"",
            )

        val filtered = filterVaultedLines(lines, vaulted)

        assertFalse("synthetic-secret" in filtered.joinToString("\n"))
    }

    @Test
    fun `a section header with inner whitespace is cut`() {
        val lines =
            listOf(
                "[ providers.s3 ]",
                "password = \"synthetic-secret\"",
            )

        val filtered = filterVaultedLines(lines, vaulted)

        assertFalse("synthetic-secret" in filtered.joinToString("\n"))
    }

    @Test
    fun `a key with whitespace around the equals sign is cut`() {
        val lines =
            listOf(
                "[providers.s3]",
                "password\t=  \"synthetic-secret\"",
            )

        val filtered = filterVaultedLines(lines, vaulted)

        assertFalse("synthetic-secret" in filtered.joinToString("\n"))
    }

    @Test
    fun `a following section closes the cut so its own lines are kept`() {
        val lines =
            listOf(
                "[providers.s3]",
                "password = \"synthetic-secret\"",
                "[general]",
                "locale = \"en\"",
                "password = \"not-a-provider-secret\"",
            )

        val filtered = filterVaultedLines(lines, vaulted)

        assertTrue("not-a-provider-secret" in filtered.joinToString("\n"))
        assertFalse("synthetic-secret" in filtered.joinToString("\n"))
    }

    @Test
    fun `a shape the cut cannot remove aborts instead of leaving the secret`() {
        // A quoted key: ktoml reads it, the bare-key cut does not match it.
        val lines =
            listOf(
                "[providers.s3]",
                "\"password\" = \"synthetic-secret\"",
            )

        val e =
            assertFailsWith<IllegalStateException> {
                filterVaultedLines(lines, vaulted)
            }
        assertTrue("s3.password" in e.message.orEmpty(), e.message)
        assertTrue("synthetic-secret" !in e.message.orEmpty(), "the message names the field, never the value: ${e.message}")
    }
}
