package org.krost.unidrive.dropbox

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `forgetCredentials()` (archive#1186): after a sign-out through the running daemon, the daemon's own instance drops the
 * grant it holds in memory, without the browser fallback of `authenticate()` and without touching the token file.
 */
class DropboxForgetCredentialsTest {
    private val dir = Files.createTempDirectory("dropbox-forget")
    private val fake = FakeDropbox()

    @Test
    fun `the provider forgets its grant, no request carries it and the file is left to the sign-out`() =
        runTest {
            val provider = FakeDropbox.provider(fake, dir)
            assertTrue(provider.isAuthenticated)

            provider.forgetCredentials()

            assertFalse(provider.isAuthenticated, "the daemon's instance is signed out")
            assertTrue(Files.exists(dir.resolve(DropboxAuth.TOKEN_FILE)), "only the memory: the sign-out removes the files itself")
            assertFailsWith<AuthenticationException> { provider.listChildren("/") }
            assertTrue(fake.callsTo("files/list_folder").isEmpty(), "nothing went out with the forgotten token")
        }
}
