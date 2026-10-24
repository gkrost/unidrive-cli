package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.krost.unidrive.AccountName
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.NotSignedInException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `checkCredentials()` (archive#1240): one authenticated GET of the usage figure with the stored session, no console prompt
 * whatever is stored. The server's answers are scripted on a MockEngine; every value is made up.
 */
class InternxtCheckCredentialsTest {
    private lateinit var dir: Path
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ix-check-")
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

    private fun provider(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>): InternxtProvider =
        InternxtProvider(
            InternxtConfig(credentialProtector = null, tokenPath = dir),
            HttpClient(
                MockEngine { request ->
                    requests += request
                    val (status, body) = handler(request)
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ),
        )

    @Test
    fun `an accepted session is one GET of the usage figure, with the e-mail as the account`() =
        runBlocking {
            store()
            val provider = provider { HttpStatusCode.OK to """{"drive":1}""" }
            try {
                val proof = provider.checkCredentials()

                assertEquals("user@example.invalid", proof.account)
                assertEquals(1, requests.size, "one authenticated call")
                assertTrue(requests.single().url.encodedPath.endsWith("/users/usage"), requests.single().url.toString())
                assertTrue(requests.single().headers[HttpHeaders.Authorization].orEmpty().startsWith("Bearer header."))
            } finally {
                provider.close()
            }
        }

    @Test
    fun `the name stored at sign-in wins over the e-mail`() =
        runBlocking {
            store()
            AccountName.write(dir, "stored@example.test")
            val provider = provider { HttpStatusCode.OK to """{"drive":1}""" }
            try {
                assertEquals("stored@example.test", provider.checkCredentials().account)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `nothing stored is not signed in, with no request and no prompt`() =
        runBlocking<Unit> {
            val provider = provider { error("no request may be made without credentials") }
            try {
                assertFailsWith<NotSignedInException> { provider.checkCredentials() }
                assertTrue(requests.isEmpty())
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a session the server refuses is an authentication failure`() =
        runBlocking<Unit> {
            store()
            val provider = provider { HttpStatusCode.Unauthorized to """{"message":"Unauthorized"}""" }
            try {
                assertFailsWith<AuthenticationException> { provider.checkCredentials() }
            } finally {
                provider.close()
            }
        }

    @Test
    fun `an answer that is not usable keeps its status`() =
        runBlocking<Unit> {
            store()
            val provider = provider { HttpStatusCode.Forbidden to """{"message":"Forbidden"}""" }
            try {
                val e = assertFailsWith<ProviderException> { provider.checkCredentials() }
                assertEquals(403, e.httpStatus)
            } finally {
                provider.close()
            }
        }
}
