package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.testing.TestScratch
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#267: what `unidrive auth --device-code` prints is byte for byte what it printed before the sign-in's
 * console moved out of the provider. The expected text is a literal on purpose.
 */
class OneDriveSignInTextTest {
    private lateinit var profileDir: Path

    @BeforeTest
    fun setUp() {
        profileDir = TestScratch.directory("od-signin-text-")
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
                        """{"device_code":"DC-1","user_code":"USR-1","verification_uri":"https://microsoft.com/devicelogin",""" +
                            """"expires_in":900,"interval":1,"message":"To sign in, enter the code USR-1."}""",
                    ),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            } else {
                respond(
                    ByteReadChannel(
                        """{"access_token":"${"n".repeat(200)}","token_type":"Bearer","expires_in":3600,"refresh_token":"rt-new",""" +
                            """"scope":"Files.ReadWrite.All offline_access"}""",
                    ),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            }
        }

    @Test
    fun the_device_code_sign_in_prints_the_same_text() {
        val config = OneDriveConfig(tokenPath = profileDir)
        val manager = TokenManager(config, OAuthService(config, HttpClient(authEngine)), identityOf = { "id-ada" })
        val originalOut = System.out
        val out = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        try {
            runBlocking { manager.authenticateWithDeviceCode() }
        } finally {
            System.setOut(originalOut)
        }
        val nl = System.lineSeparator()
        assertEquals(
            "To sign in, enter the code USR-1.$nl$nl" +
                "Waiting for authentication...$nl" +
                "Authentication successful!$nl",
            out.toString(Charsets.UTF_8),
        )
    }
}
