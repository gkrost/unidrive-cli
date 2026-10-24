package org.krost.unidrive.cli

import org.krost.unidrive.sync.SyncConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VaultRestoreTest {
    @Test
    fun `restore credentials into a commented section`() {
        val result = restoreVaultCredentials("[providers.a] # main\ntype = \"localfs\"\n", mapOf("a" to mapOf("password" to "synthetic")))
        assertEquals("synthetic", SyncConfig.parseRaw(result).providers.getValue("a").password)
    }

    @Test
    fun `refuse to restore when a profile is missing`() {
        assertFailsWith<IllegalArgumentException> {
            restoreVaultCredentials("[general]\n", mapOf("missing" to mapOf("password" to "synthetic")))
        }
    }
}
