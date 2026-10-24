package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.http.ErrorBodyExcerpt
import org.krost.unidrive.http.HttpRetryBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What an Internxt error text says of the server's answer. The text of an [InternxtApiException], of a 401's
 * [AuthenticationException] and of an [InternxtUnreadableResponse] reaches the log, the IPC replies and the
 * notifications, its cause chain too: it names the status and at most a short, masked excerpt of the body, never a
 * secret, an id or the whole body a gateway echoed.
 */
class InternxtErrorTextTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun serviceOn(engine: MockEngine) =
        InternxtApiService(
            InternxtConfig(credentialProtector = null),
            credentialsProvider = { _ -> fakeCredentials() },
            driveBudget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0),
            httpClient = HttpClient(engine),
        )

    // An error body as a gateway may send it: a reason, an echoed token, an id and a long tail.
    private val echoingBody =
        """{"message":"rejected","echo":{"token":"SECRET-TOKEN","bearer":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJTRUNSRVQifQ.c2ln"},""" +
            """"folder":"$FOLDER","detail":"${"padding ".repeat(2000)}"}"""

    private fun Throwable.chainText() = generateSequence(this) { it.cause }.joinToString("\n") { "${it.javaClass.name}: ${it.message}" }

    private fun assertClean(text: String) {
        assertFalse("SECRET" in text, text)
        assertFalse("eyJhbGci" in text, text)
        assertFalse(FOLDER in text, text)
        assertTrue(text.length <= 400, "bounded, was ${text.length} chars")
    }

    @Test
    fun `an API error names the status and a short masked excerpt of the body`() =
        runTest {
            val service = serviceOn(MockEngine { respond(echoingBody, HttpStatusCode.BadRequest, json) })

            val e = assertFailsWith<InternxtApiException> { service.listFiles() }

            assertTrue(e.message!!.startsWith("API error: 400 Bad Request - {\"message\":\"rejected\""), e.message)
            assertClean(e.chainText())
            service.close()
        }

    @Test
    fun `a 401 names the status and a short masked excerpt of the body`() =
        runTest {
            val service = serviceOn(MockEngine { respond(echoingBody, HttpStatusCode.Unauthorized, json) })

            val e = assertFailsWith<AuthenticationException> { service.listFiles() }

            assertTrue(e.message!!.startsWith("Authentication failed (401): "), e.message)
            assertClean(e.chainText())
            service.close()
        }

    @Test
    fun `the body's retry_after hint is honoured however long the body is`() =
        runTest {
            val body = """{"detail":"${"padding ".repeat(2000)}","retry_after":7}"""
            val service = serviceOn(MockEngine { respond(body, HttpStatusCode.TooManyRequests, json) })

            val e = assertFailsWith<InternxtApiException> { service.listFiles() }

            assertEquals(7_000L, e.retryAfterMs, "read from the body, not from the cut text")
            service.close()
        }

    @Test
    fun `a folder meta that cannot be read names neither the body nor keeps the decoder's quote of it as the cause`() =
        runTest {
            // A field of the wrong type deep in the body: the decoder's message quotes the body around it.
            val body = """{"uuid":"$FOLDER","plainName":"SECRET-NAME","parentId":"not-a-number","bucket":"SECRET-BUCKET"}"""
            val service = serviceOn(MockEngine { respond(body, HttpStatusCode.OK, headersOf("x-request-id", "srv-9")) })

            val e = assertFailsWith<InternxtUnreadableResponse> { service.getFolderMeta(FOLDER) }

            assertNull(e.cause, "the decoder's exception quotes the body")
            assertTrue(FOLDER in e.message!!, "the folder that was asked for is named: ${e.message}")
            assertEquals("srv-9", e.requestId)
            val text = e.chainText()
            assertFalse("SECRET" in text, text)
            assertFalse("JSON input" in text, text)
            assertFalse("not-a-number" in text, text)
            service.close()
        }

    @Test
    fun `a folder meta without a required field names the field`() =
        runTest {
            val service = serviceOn(MockEngine { respond("""{"plainName":"SECRET-NAME"}""", HttpStatusCode.OK, json) })

            val e = assertFailsWith<InternxtUnreadableResponse> { service.getFolderMeta(FOLDER) }

            assertTrue("uuid" in e.message!!, e.message)
            assertFalse("SECRET" in e.message!!, e.message)
            service.close()
        }

    @Test
    fun `the excerpt keeps what the upload path reads from a 409`() {
        val body = """{"statusCode":409,"message":"Missing uploads","error":"MissingUploadsError"}"""
        assertTrue("MissingUploadsError" in ErrorBodyExcerpt.of(body))
    }

    private companion object {
        const val FOLDER = "7a1c0e52-3b6d-4f0a-9d84-5e2f6b1c9a10"
    }
}
