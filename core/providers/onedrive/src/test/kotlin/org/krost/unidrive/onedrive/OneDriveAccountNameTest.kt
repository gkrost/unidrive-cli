package org.krost.unidrive.onedrive

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AccountName
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.onedrive.model.Token
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sign-in keeps the account's display name (from a fake Graph `/me/drive`) next to the token and says it in the result. */
class OneDriveAccountNameTest {
    private lateinit var profileDir: Path

    @BeforeTest
    fun setUp() {
        profileDir = Files.createTempDirectory("od-account-")
    }

    @AfterTest
    fun tearDown() {
        profileDir.toFile().deleteRecursively()
    }

    private val jsonHeaders = headersOf("Content-Type" to listOf("application/json"))

    private val authEngine =
        MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/devicecode") ->
                    respond(
                        ByteReadChannel(
                            """{"device_code":"DC-1","user_code":"USR-1","verification_uri":"https://microsoft.com/devicelogin","expires_in":900,"interval":5,"message":"go"}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                else ->
                    respond(
                        ByteReadChannel(
                            """{"access_token":"${"x".repeat(200)}","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-1","scope":"Files.ReadWrite.All offline_access"}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
            }
        }

    private fun factory(me: (() -> io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData)? = null) =
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
                            check(request.url.encodedPath.endsWith("/me/drive")) { "unexpected ${request.url}" }
                            if (me == null) respond(ByteReadChannel(""), HttpStatusCode.Forbidden) else me()()
                        },
                ) { "access" }
        }

    private fun meBody(json: String): () -> io.ktor.client.engine.mock.MockRequestHandleScope.() -> io.ktor.client.request.HttpResponseData =
        { { respond(ByteReadChannel(json), HttpStatusCode.OK, jsonHeaders) } }

    private fun driveOf(name: String) = """{"id":"d1","driveType":"personal","owner":{"user":{"id":"user-id-1","displayName":"$name"}}}"""

    private fun signIn(factory: OneDriveProviderFactory): CompleteAuthResult =
        runBlocking {
            val begin = factory.beginInteractiveAuth(profileDir)
            factory.completeInteractiveAuth(profileDir, begin.continuationHandle)
        }

    @Test
    fun `sign-in reports and keeps the display name`() {
        val result = signIn(factory(meBody(driveOf("Ada Lovelace"))))

        assertEquals(CompleteAuthResult.Success("Ada Lovelace"), result)
        assertEquals("Ada Lovelace", AccountName.read(profileDir))
    }

    @Test
    fun `the name never depends on Graph me, which a file-only app is refused`() {
        val result = signIn(factory(meBody("""{"id":"d1","driveType":"personal","owner":{"user":{"id":"user-id-1"}}}""")))

        assertIs<CompleteAuthResult.Success>(result)
        assertNull(result.account)
    }

    @Test
    fun `a failed lookup still signs in, with no name and without an earlier account's name`() {
        AccountName.write(profileDir, "Previous Account")

        val result = signIn(factory())

        assertIs<CompleteAuthResult.Success>(result)
        assertNull(result.account)
        assertNull(AccountName.read(profileDir))
        assertTrue(Files.exists(profileDir.resolve("token.json")))
    }

    @Test
    fun `the name never reaches a log line, even at debug`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val previous = root.level
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.level = Level.DEBUG
        root.addAppender(appender)
        try {
            signIn(factory(meBody(driveOf("Zz Marker Name"))))
        } finally {
            root.detachAppender(appender)
            root.level = previous
        }

        val text = appender.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        assertFalse("Marker Name" in text, "display name in log: $text")
        assertFalse("zz.marker" in text, "sign-in name in log: $text")
    }
}
