package org.krost.unidrive.internxt

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `reloadCredentials()` (#813): a running daemon's provider instance picks up the credentials a sign-in through the
 * daemon stored, without a restart and without the console prompt of `authenticate()`. Every value is made up.
 */
class InternxtReloadCredentialsTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ix-reload-")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun store() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        val creds =
            InternxtCredentials(
                jwt = "header.$payload.signature",
                mnemonic = "fake mnemonic",
                rootFolderId = "root-folder-uuid",
                email = "user@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-id",
                bucket = "bucket",
            )
        Files.writeString(dir.resolve("credentials.json"), Json.encodeToString(InternxtCredentials.serializer(), creds))
    }

    @Test
    fun `the same provider instance reads credentials stored after it started, and reads their removal too`() =
        runBlocking {
            val provider = InternxtProvider(InternxtConfig(credentialProtector = null, tokenPath = dir))
            try {
                provider.reloadCredentials()
                assertFalse(provider.isAuthenticated, "nothing stored: not signed in, and nothing was prompted for")

                store()
                provider.reloadCredentials()
                assertTrue(provider.isAuthenticated, "the stored credentials are the provider's now")

                Files.delete(dir.resolve("credentials.json"))
                provider.reloadCredentials()
                assertFalse(provider.isAuthenticated)
            } finally {
                provider.close()
            }
        }
}
