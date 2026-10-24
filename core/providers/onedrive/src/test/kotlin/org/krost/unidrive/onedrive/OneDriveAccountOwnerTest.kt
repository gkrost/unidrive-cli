package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AccountChangedException
import org.krost.unidrive.AccountName
import org.krost.unidrive.AccountOwner
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.onedrive.model.Token
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * A OneDrive sign-in compares the drive owner id (`/me/drive` `owner.user.id`) of the new token with the profile's stored user
 * ([AccountOwner]) before it saves the token: another user is refused and the stored token stays as it was.
 */
class OneDriveAccountOwnerTest {
    private lateinit var profileDir: Path

    @BeforeTest
    fun setUp() {
        profileDir = Files.createTempDirectory("od-owner-")
    }

    @AfterTest
    fun tearDown() {
        profileDir.toFile().deleteRecursively()
    }

    private val jsonHeaders = headersOf("Content-Type" to listOf("application/json"))

    private val authEngine =
        MockEngine { request ->
            if (request.url.encodedPath.endsWith("/devicecode")) {
                respond(
                    ByteReadChannel(
                        """{"device_code":"DC-1","user_code":"USR-1","verification_uri":"https://microsoft.com/devicelogin","expires_in":900,"interval":1,"message":"go"}""",
                    ),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            } else {
                respond(
                    ByteReadChannel(
                        """{"access_token":"${"n".repeat(200)}","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-new","scope":"Files.ReadWrite.All offline_access"}""",
                    ),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            }
        }

    /** A factory whose Graph `/me` always answers 403 (file-only scopes) and whose `/me/drive` answers [meJson], or 403 when it is null. */
    private fun factory(meJson: String?) =
        object : OneDriveProviderFactory() {
            override fun newOAuthServiceForBegin(profileDir: Path): OAuthService =
                OAuthService(OneDriveConfig(tokenPath = profileDir), HttpClient(authEngine))

            override fun newGraphApiForIdentity(
                profileDir: Path,
                token: Token,
            ): GraphApiService =
                GraphApiService(
                    OneDriveConfig(tokenPath = profileDir),
                    engine =
                        MockEngine { request ->
                            if (request.url.encodedPath.endsWith("/me")) {
                                respond(ByteReadChannel(""), HttpStatusCode.Forbidden)
                            } else if (meJson == null) {
                                respond(ByteReadChannel(""), HttpStatusCode.Forbidden)
                            } else {
                                respond(ByteReadChannel(meJson), HttpStatusCode.OK, jsonHeaders)
                            }
                        },
                ) { "access" }
        }

    /** A `/me/drive` answer whose owner is user [id] called [name]. */
    private fun me(
        id: String,
        name: String,
    ) = """{"id":"drive-of-$id","driveType":"personal","owner":{"user":{"id":"$id","displayName":"$name"}}}"""

    private fun signIn(meJson: String?): CompleteAuthResult =
        runBlocking {
            val f = factory(meJson)
            val begin = f.beginInteractiveAuth(profileDir)
            f.completeInteractiveAuth(profileDir, begin.continuationHandle)
        }

    private val oldToken = """{"accessToken":"old","refreshToken":"rt-old","expiresAt":1,"tokenType":"Bearer"}"""

    /** A profile signed in as Ada, with her token, name and id. */
    private fun seedAda() {
        Files.writeString(profileDir.resolve("token.json"), oldToken)
        AccountName.write(profileDir, "Ada")
        AccountOwner.record(profileDir, "id-ada")
    }

    @Test
    fun `the first sign-in records the Graph user id`() {
        assertIs<CompleteAuthResult.Success>(signIn(me("id-ada", "Ada")))

        assertEquals("id-ada", AccountOwner.read(profileDir))
    }

    @Test
    fun `the same user signs in again and the token is replaced`() {
        seedAda()

        assertEquals(CompleteAuthResult.Success("Ada"), signIn(me("id-ada", "Ada")))

        assertNotEquals(oldToken, Files.readString(profileDir.resolve("token.json")), "the new token is stored")
        assertEquals("id-ada", AccountOwner.read(profileDir))
    }

    @Test
    fun `another user with the same display name is refused and nothing is stored`() {
        seedAda()

        val result = signIn(me("id-other", "Ada"))

        assertIs<CompleteAuthResult.Failure>(result)
        assertEquals(CompleteAuthResult.Reason.ACCOUNT_CHANGED, result.reason)
        assertEquals(oldToken, Files.readString(profileDir.resolve("token.json")), "the old token is unchanged")
        assertEquals("Ada", AccountName.read(profileDir))
        assertEquals("id-ada", AccountOwner.read(profileDir))
        assertFalse(Files.exists(profileDir.resolve("device-flow.json")), "the spent flow is forgotten")
    }

    @Test
    fun `a profile without a stored id adopts the first sign-in's user`() {
        Files.writeString(profileDir.resolve("token.json"), oldToken)
        AccountName.write(profileDir, "Ada")

        assertEquals(CompleteAuthResult.Success("Bob"), signIn(me("id-bob", "Bob")))

        assertEquals("id-bob", AccountOwner.read(profileDir))
    }

    @Test
    fun `a failed identity lookup is refused on a profile with a stored user`() {
        seedAda()

        val result = signIn(null)

        assertIs<CompleteAuthResult.Failure>(result)
        assertEquals(CompleteAuthResult.Reason.ACCOUNT_UNVERIFIED, result.reason)
        assertEquals(oldToken, Files.readString(profileDir.resolve("token.json")))
    }

    @Test
    fun `a drive answer without an owner records nothing and does not refuse a profile without an id`() {
        Files.writeString(profileDir.resolve("token.json"), oldToken)

        val result = signIn("""{"id":"d1","driveType":"personal"}""")

        assertIs<CompleteAuthResult.Success>(result)
        assertEquals(null, AccountOwner.read(profileDir))
    }

    @Test
    fun `a drive answer without an owner is unverified on a profile with a stored user`() {
        seedAda()

        val result = signIn("""{"id":"d1","driveType":"personal"}""")

        assertIs<CompleteAuthResult.Failure>(result)
        assertEquals(CompleteAuthResult.Reason.ACCOUNT_UNVERIFIED, result.reason)
        assertEquals(oldToken, Files.readString(profileDir.resolve("token.json")))
    }

    @Test
    fun `the console device-code sign-in refuses another user before it saves the token`() {
        seedAda()
        val config = OneDriveConfig(tokenPath = profileDir)
        val manager = TokenManager(config, OAuthService(config, HttpClient(authEngine)), identityOf = { "id-bob" })

        assertFailsWith<AccountChangedException> { runBlocking { manager.authenticateWithDeviceCode() } }

        assertEquals(oldToken, Files.readString(profileDir.resolve("token.json")))
        assertEquals("id-ada", AccountOwner.read(profileDir))
    }

    @Test
    fun `the console device-code sign-in records the user of a new profile`() {
        val config = OneDriveConfig(tokenPath = profileDir)
        val manager = TokenManager(config, OAuthService(config, HttpClient(authEngine)), identityOf = { "id-ada" })

        runBlocking { manager.authenticateWithDeviceCode() }

        assertEquals("id-ada", AccountOwner.read(profileDir))
    }
}
