package org.krost.unidrive.dropbox

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AccountOwner
import org.krost.unidrive.CompleteAuthResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A Dropbox sign-in compares the token answer's `account_id` with the profile's stored user before it stores the grant. */
class DropboxAccountOwnerTest {
    private val dir: Path = Files.createTempDirectory("dropbox-owner")
    private val profile = mapOf("client_id" to "app-key")

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun signIn(accountId: String): CompleteAuthResult =
        runBlocking {
            val fake = FakeDropbox()
            fake.json(
                "oauth2/token",
                """{"access_token":"${"a".repeat(40)}","expires_in":14400,"refresh_token":"r-$accountId","account_id":"$accountId"}""",
            )
            val factory =
                object : DropboxProviderFactory() {
                    override fun newHttpClientForBegin() = io.ktor.client.HttpClient(fake.engine)
                }
            val begun = factory.beginInteractiveAuth(dir, profile)
            factory.completeInteractiveAuth(dir, begun.continuationHandle, "code", profile)
        }

    private fun grant(): String = Files.readString(dir.resolve(DropboxAuth.TOKEN_FILE))

    @Test
    fun `the first sign-in records the account id and the same account signs in again`() {
        assertIs<CompleteAuthResult.Success>(signIn("dbid:ada"))
        assertEquals("dbid:ada", AccountOwner.read(dir))

        assertIs<CompleteAuthResult.Success>(signIn("dbid:ada"))
    }

    @Test
    fun `another account is refused and the stored grant stays`() {
        signIn("dbid:ada")
        val before = grant()

        val outcome = signIn("dbid:bob")

        assertIs<CompleteAuthResult.Failure>(outcome)
        assertEquals(CompleteAuthResult.Reason.ACCOUNT_CHANGED, outcome.reason)
        assertEquals(before, grant())
        assertEquals("dbid:ada", AccountOwner.read(dir))
    }

    @Test
    fun `a profile without a stored id adopts the first sign-in's account`() {
        Files.writeString(dir.resolve(DropboxAuth.TOKEN_FILE), "{}")

        assertIs<CompleteAuthResult.Success>(signIn("dbid:bob"))

        assertEquals("dbid:bob", AccountOwner.read(dir))
    }
}
