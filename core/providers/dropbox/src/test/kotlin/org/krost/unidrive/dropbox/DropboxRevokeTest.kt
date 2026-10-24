package org.krost.unidrive.dropbox

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.TokenRevocation
import org.krost.unidrive.dropbox.FakeDropbox.Answer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #1052: a sign-out revokes the grant at Dropbox (`auth/token/revoke`) before the local files go; the answer is
 * [TokenRevocation.REVOKED] only when Dropbox accepted the call.
 */
class DropboxRevokeTest {
    private val dir = Files.createTempDirectory("dropbox-revoke")
    private val fake = FakeDropbox()

    @Test
    fun `the revoke call carries the stored access token and its confirmation is the answer`() =
        runTest {
            val provider = FakeDropbox.provider(fake, dir)
            fake.json("auth/token/revoke", "null")

            assertEquals(TokenRevocation.REVOKED, provider.revokeToken())

            val call = fake.callsTo("auth/token/revoke").single()
            assertEquals("Bearer ${"a".repeat(40)}", call.headers["Authorization"])
        }

    @Test
    fun `a provider built fresh for the sign-out reads the stored grant before it revokes`() =
        runTest {
            FakeDropbox.provider(fake, dir).close() // writes the token file
            val fresh = DropboxProvider(DropboxConfig(appKey = "key", tokenPath = dir), fake.engine) // no authenticate()
            fake.json("auth/token/revoke", "null")

            assertEquals(TokenRevocation.REVOKED, fresh.revokeToken())

            assertEquals("Bearer ${"a".repeat(40)}", fake.callsTo("auth/token/revoke").single().headers["Authorization"])
        }

    @Test
    fun `revoking does not delete the local grant, logout does`() =
        runTest {
            val provider = FakeDropbox.provider(fake, dir)
            fake.json("auth/token/revoke", "null")

            provider.revokeToken()
            assertTrue(Files.exists(dir.resolve(DropboxAuth.TOKEN_FILE)), "revoke is the provider's side only")

            provider.logout()
            assertFalse(Files.exists(dir.resolve(DropboxAuth.TOKEN_FILE)))
        }

    @Test
    fun `an expired access token is refreshed once and the revoke is retried with the new one`() =
        runTest {
            val provider = FakeDropbox.provider(fake, dir)
            fake.on("auth/token/revoke") { Answer(401, """{"error_summary":"expired_access_token/.."}""") }
            fake.on("auth/token/revoke") { Answer(body = "null") }
            fake.json("oauth2/token", """{"access_token":"${"n".repeat(40)}","token_type":"bearer","expires_in":14400}""")

            assertEquals(TokenRevocation.REVOKED, provider.revokeToken())

            assertEquals("Bearer ${"n".repeat(40)}", fake.callsTo("auth/token/revoke").last().headers["Authorization"])
        }

    @Test
    fun `a refusal by Dropbox is a failure, never a revocation`() =
        runTest {
            val provider = FakeDropbox.provider(fake, dir)
            fake.json("auth/token/revoke", """{"error_summary":"internal_server_error/.."}""", status = 500)

            assertFailsWith<org.krost.unidrive.ProviderException> { provider.revokeToken() }
        }

    @Test
    fun `without a stored grant there is nothing to revoke and Dropbox is not called`() =
        runTest {
            val provider = DropboxProvider(DropboxConfig(appKey = "key", tokenPath = dir), fake.engine)

            assertFailsWith<AuthenticationException> { provider.revokeToken() }

            assertTrue(fake.callsTo("auth/token/revoke").isEmpty())
        }
}
