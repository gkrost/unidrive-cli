package org.krost.unidrive.cli

import org.krost.unidrive.s3.S3Provider
import org.krost.unidrive.s3.S3ProviderFactory
import org.krost.unidrive.sync.SyncConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The profile key `s3_create_from_listing` (gkrost/unidrive-cli#573) travels from config.toml through the properties map into the provider. */
class S3CreateFromListingKeyTest {
    private fun createFromListingUnder(line: String): Boolean {
        val raw =
            SyncConfig
                .parseRaw("[providers.s3]\ntype = \"s3\"\nbucket = \"b\"\naccess_key_id = \"AK\"\nsecret_access_key = \"SK\"\n$line\n")
                .providers
                .getValue("s3")
        val tokenDir = Files.createTempDirectory("ud-s3-key")
        val provider = S3ProviderFactory().create(Main().rawProviderToProperties(raw), tokenDir) as S3Provider
        try {
            return provider.config.createFromListing
        } finally {
            provider.close()
            tokenDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the key on reaches the provider's config`() {
        assertTrue(createFromListingUnder("s3_create_from_listing = true"))
    }

    @Test
    fun `the key off or absent leaves the HEAD guard on`() {
        assertFalse(createFromListingUnder("s3_create_from_listing = false"))
        assertEquals(false, createFromListingUnder(""))
    }
}
