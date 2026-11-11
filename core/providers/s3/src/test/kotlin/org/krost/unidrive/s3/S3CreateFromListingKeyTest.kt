package org.krost.unidrive.s3

import org.krost.unidrive.ConfigurationException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The profile key `s3_create_from_listing` (gkrost/unidrive-cli#573) reaches the provider's config; absent means off. */
class S3CreateFromListingKeyTest {
    private val base = mapOf("bucket" to "b", "access_key_id" to "AK", "secret_access_key" to "SK")

    private fun config(extra: Map<String, String?>): S3Config {
        val dir = Files.createTempDirectory("s3-key")
        val provider = S3ProviderFactory().create(base + extra, dir) as S3Provider
        try {
            return provider.config
        } finally {
            provider.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `absent or false leaves the HEAD guard on`() {
        assertFalse(config(emptyMap()).createFromListing)
        assertFalse(config(mapOf("s3_create_from_listing" to "false")).createFromListing)
        assertFalse(config(mapOf("s3_create_from_listing" to null)).createFromListing)
    }

    @Test
    fun `true is the explicit opt-in`() {
        assertTrue(config(mapOf("s3_create_from_listing" to "true")).createFromListing)
    }

    @Test
    fun `anything else is a configuration error, not a silent default`() {
        assertFailsWith<ConfigurationException> { config(mapOf("s3_create_from_listing" to "yes")) }
        val e = assertFailsWith<ConfigurationException> { config(mapOf("s3_create_from_listing" to "1")) }
        assertTrue("s3_create_from_listing" in (e.message.orEmpty()), e.message)
    }
}
