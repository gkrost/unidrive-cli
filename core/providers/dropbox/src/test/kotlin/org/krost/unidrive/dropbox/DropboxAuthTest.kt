package org.krost.unidrive.dropbox

import io.ktor.http.Url
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The sign-in request (PKCE, offline access, the loopback redirect) and what the factory asks of a profile. */
class DropboxAuthTest {
    private val dir = Files.createTempDirectory("dropbox-auth")

    // The profile's non-secret config the CLI hands to begin and complete.
    private val profile = mapOf("client_id" to "app-key-from-profile")

    @Test
    fun `the authorization url asks for offline access with a PKCE challenge`() {
        val auth = DropboxAuth(DropboxConfig(appKey = "key", tokenPath = dir, oauthCallbackPort = 8123), FakeDropbox().let { io.ktor.client.HttpClient(it.engine) })

        val url = Url(auth.authorizationUrl(state = "st", challenge = "ch"))

        assertEquals("www.dropbox.com", url.host)
        assertEquals("key", url.parameters["client_id"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("offline", url.parameters["token_access_type"])
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals("ch", url.parameters["code_challenge"])
        assertEquals("st", url.parameters["state"])
        assertEquals("http://localhost:8123/callback", url.parameters["redirect_uri"])
    }

    @Test
    fun `a profile without an app key is refused with how to get one`() {
        if (System.getenv(DropboxConfig.CLIENT_ID_ENV) != null) return
        val e = assertFailsWith<ConfigurationException> { DropboxProviderFactory().create(emptyMap(), dir) }
        assertTrue(e.message.contains("client_id"))
        assertIs<CredentialHealth.Missing>(DropboxProviderFactory().checkCredentialHealth(emptyMap(), dir))
    }

    @Test
    fun `a stored refresh token makes the credential healthy`() {
        Files.writeString(
            dir.resolve(DropboxAuth.TOKEN_FILE),
            org.krost.unidrive.UnidriveJson.encodeToString(DropboxToken.serializer(), DropboxToken("a".repeat(40), 0, "r")),
        )
        val factory = DropboxProviderFactory()

        assertEquals(CredentialHealth.Ok, factory.checkCredentialHealth(mapOf("client_id" to "k"), dir))
        assertTrue(factory.isAuthenticated(mapOf("client_id" to "k"), dir))
    }

    // -- archive#866: the no-redirect code flow ------------------------------------------

    @Test
    fun `the no-redirect url carries no redirect uri`() =
        kotlinx.coroutines.runBlocking<Unit> {
            val auth = DropboxAuth(DropboxConfig(appKey = "key", tokenPath = dir), FakeDropbox().let { io.ktor.client.HttpClient(it.engine) })

            val url = Url(auth.authorizationUrl(state = "st", challenge = "ch", redirect = false))

            assertEquals("key", url.parameters["client_id"])
            assertEquals("code", url.parameters["response_type"])
            assertEquals("offline", url.parameters["token_access_type"])
            assertEquals("ch", url.parameters["code_challenge"])
            assertEquals(null, url.parameters["redirect_uri"], "no redirect: Dropbox shows a code to paste")
        }

    @Test
    fun `begin hands out the url, complete exchanges the pasted code, a repeat is refused`() =
        kotlinx.coroutines.runBlocking<Unit> {
            val fake = FakeDropbox()
            fake.json(
                "oauth2/token",
                """{"access_token":"${"a".repeat(40)}","expires_in":14400,"refresh_token":"r1","account_id":"dbid:a"}""",
            )
            val factory =
                object : DropboxProviderFactory() {
                    override fun newHttpClientForBegin() = io.ktor.client.HttpClient(fake.engine)
                }

            val begun = factory.beginInteractiveAuth(dir, profile)
            val authorizeUrl = Url(begun.fields.getValue("authorize_url").jsonPrimitive.content)
            assertEquals("www.dropbox.com", authorizeUrl.host)
            assertEquals("app-key-from-profile", authorizeUrl.parameters["client_id"], "the app key is the profile's")

            val outcome = factory.completeInteractiveAuth(dir, begun.continuationHandle, "the-pasted-code", profile)
            assertIs<CompleteAuthResult.Success>(outcome)
            assertTrue(Files.exists(dir.resolve(DropboxAuth.TOKEN_FILE)), "the grant is stored")
            val exchange = fake.callsTo("oauth2/token").single().body.toString(Charsets.UTF_8)
            assertTrue("code=the-pasted-code" in exchange, exchange)
            assertTrue("client_id=app-key-from-profile" in exchange, "the exchange names the profile's app key: $exchange")
            assertTrue("redirect_uri" !in exchange, "the no-redirect exchange names no redirect uri")

            assertIs<CompleteAuthResult.Failure>(factory.completeInteractiveAuth(dir, begun.continuationHandle, "again", profile), "the handle is spent")
        }

    @Test
    fun `a begin without an app key in the profile or the environment is refused and keeps no flow`() =
        kotlinx.coroutines.runBlocking<Unit> {
            if (System.getenv(DropboxConfig.CLIENT_ID_ENV) != null) return@runBlocking
            val factory =
                object : DropboxProviderFactory() {
                    override fun newHttpClientForBegin() = io.ktor.client.HttpClient(FakeDropbox().engine)
                }

            val e = assertFailsWith<ConfigurationException> { factory.beginInteractiveAuth(dir, mapOf("client_id" to " ")) }

            assertTrue(e.message.contains("client_id"))
            assertFalse(Files.exists(dir.resolve(DropboxProviderFactory.PENDING_FLOW_FILE)), "no handle without an app key")
        }

    @Test
    fun `a wrong code stores nothing and keeps the flow for another try`() =
        kotlinx.coroutines.runBlocking<Unit> {
            val fake = FakeDropbox()
            fake.json("oauth2/token", """{"error_summary":"invalid_grant/"}""", status = 400)
            val factory =
                object : DropboxProviderFactory() {
                    override fun newHttpClientForBegin() = io.ktor.client.HttpClient(fake.engine)
                }

            val begun = factory.beginInteractiveAuth(dir, profile)
            val outcome = factory.completeInteractiveAuth(dir, begun.continuationHandle, "wrong", profile)

            assertIs<CompleteAuthResult.Failure>(outcome)
            assertFalse(Files.exists(dir.resolve(DropboxAuth.TOKEN_FILE)), "a wrong code stores nothing")
            assertIs<CompleteAuthResult.Failure>(factory.completeInteractiveAuth(dir, begun.continuationHandle, "still-wrong", profile))
        }

    @Test
    fun `an expired flow refuses with expired`() =
        kotlinx.coroutines.runBlocking<Unit> {
            val fake = FakeDropbox()
            val factory =
                object : DropboxProviderFactory() {
                    override fun newHttpClientForBegin() = io.ktor.client.HttpClient(fake.engine)
                }
            val begun = factory.beginInteractiveAuth(dir, profile)
            // Age the pending flow past its window.
            val pending = dir.resolve(DropboxProviderFactory.PENDING_FLOW_FILE)
            Files.writeString(pending, Files.readString(pending).replace(Regex("\"expiresAt\":\"[^\"]+\""), "\"expiresAt\":\"2020-01-01T00:00:00Z\""))

            val outcome = factory.completeInteractiveAuth(dir, begun.continuationHandle, "code", profile)
            assertIs<CompleteAuthResult.Failure>(outcome)
            assertEquals(CompleteAuthResult.Reason.EXPIRED, outcome.reason)
        }
}
