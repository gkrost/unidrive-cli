package org.krost.unidrive.dropbox

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AccountName
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.NotSignedInException
import org.krost.unidrive.dropbox.FakeDropbox.Answer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `checkCredentials()` (#1240): one `users/get_space_usage` with the stored grant, never the browser sign-in. */
class DropboxCheckCredentialsTest {
    private val dir = Files.createTempDirectory("dropbox-check")
    private val fake = FakeDropbox()

    private fun fresh() = DropboxProvider(DropboxConfig(appKey = "key", tokenPath = dir), fake.engine) // no authenticate()

    @Test
    fun `an accepted grant is one usage call with the stored token`() =
        runTest {
            FakeDropbox.provider(fake, dir).close() // writes the token file
            AccountName.write(dir, "ada@example.test")
            fake.calls.clear()
            fake.json("users/get_space_usage", """{"used":1,"allocation":{".tag":"individual","allocated":10}}""")

            val proof = fresh().checkCredentials()

            assertEquals("ada@example.test", proof.account)
            val call = fake.callsTo("users/get_space_usage").single()
            assertEquals("Bearer ${"a".repeat(40)}", call.headers["Authorization"])
            assertEquals(1, fake.calls.size, "one authenticated call")
        }

    @Test
    fun `nothing stored is not signed in, with no request and no browser`() =
        runTest {
            assertFailsWith<NotSignedInException> { fresh().checkCredentials() }

            assertTrue(fake.calls.isEmpty())
        }

    @Test
    fun `a grant Dropbox refuses is an authentication failure`() =
        runTest {
            FakeDropbox.provider(fake, dir).close()
            fake.on("users/get_space_usage") { Answer(401, """{"error_summary":"invalid_access_token/.."}""") }
            fake.on("oauth2/token") { Answer(400, """{"error":"invalid_grant"}""") }

            assertFailsWith<AuthenticationException> { fresh().checkCredentials() }
        }
}
