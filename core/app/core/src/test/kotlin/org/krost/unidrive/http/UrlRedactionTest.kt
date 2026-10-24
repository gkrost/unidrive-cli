package org.krost.unidrive.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.SocketTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.url
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #1060: Ktor's timeout exceptions quote the full request URL, query included, and the URLs of a Graph
 * download or upload, an S3 presigned request or an Internxt shard carry their capability in it.
 */
class UrlRedactionTest {
    private val secretUrl = "https://x/y?tempauth=SECRET"

    @Test
    fun `a request timeout of a real Ktor client quotes the secret and the redaction takes it out`() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        delay(2_000)
                        respond("")
                    },
                ) {
                    install(HttpTimeout) { requestTimeoutMillis = 50 }
                }
            val e = assertFailsWith<HttpRequestTimeoutException> { client.get(secretUrl) }
            assertTrue("SECRET" in e.message.orEmpty(), "precondition: Ktor's own message carries the query: ${e.message}")

            assertEquals(
                "Request timeout has expired [url=https://x/y?<redacted>, request_timeout=50 ms]",
                UrlRedaction.redact(e.message.orEmpty()),
            )
        }

    @Test
    fun `connect and socket timeouts of Ktor lose the query too`() {
        val request = HttpRequestBuilder().apply { url(secretUrl) }.build()
        val messages =
            listOf(
                ConnectTimeoutException(secretUrl, 1_000L, null).message.orEmpty(),
                ConnectTimeoutException(request, null).message.orEmpty(),
                SocketTimeoutException(request, null).message.orEmpty(),
            )
        for (message in messages) {
            assertTrue("SECRET" in message, "precondition: $message")
            val redacted = UrlRedaction.redact(message)
            assertFalse("SECRET" in redacted, redacted)
            assertTrue("[url=https://x/y?<redacted>, " in redacted, redacted)
        }
    }

    @Test
    fun `scheme host and path stay, query and fragment go`() {
        assertEquals("https://x/y?<redacted>", UrlRedaction.redact("https://x/y?a=1&b=2"))
        assertEquals("https://x/y#<redacted>", UrlRedaction.redact("https://x/y#access_token=SECRET"))
        assertEquals("https://x/y?<redacted>", UrlRedaction.redact("https://x/y?a=1#frag"))
        assertEquals("https://x/y/z.txt", UrlRedaction.redact("https://x/y/z.txt"))
        assertEquals("https://x", UrlRedaction.redact("https://x"))
    }

    @Test
    fun `presigned S3 and SAS shaped URLs`() {
        val s3 =
            "https://bucket.s3.eu-west-1.amazonaws.com/key?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
                "&X-Amz-Credential=AKIAEXAMPLE%2F20261019%2Feu-west-1%2Fs3%2Faws4_request&X-Amz-Signature=SECRET"
        assertEquals("https://bucket.s3.eu-west-1.amazonaws.com/key?<redacted>", UrlRedaction.redact(s3))
        assertEquals("https://acct.blob.core.windows.net/c/b?<redacted>", UrlRedaction.redact("https://acct.blob.core.windows.net/c/b?sv=1&sig=SECRET"))
    }

    @Test
    fun `the OneDrive socket io notification URL loses its token and application id`() {
        // GET /me/drive/root/subscriptions/socketIo answers a notificationUrl whose query authorises the change stream.
        val endpoint = "https://f3hb0mpua.svc.ms/zbaehwg?token=SECRET-TOKEN&applicationId=APP-ID"
        for (text in listOf(endpoint, "connect_error on $endpoint: timeout", "wss://f3hb0mpua.svc.ms/socket.io/?token=SECRET-TOKEN&EIO=4")) {
            val redacted = UrlRedaction.redact(text)
            assertFalse("SECRET-TOKEN" in redacted, redacted)
            assertFalse("APP-ID" in redacted, redacted)
            assertTrue("f3hb0mpua.svc.ms/" in redacted, redacted)
        }
        assertEquals("token=<redacted>&applicationId=x", UrlRedaction.redact("token=SECRET-TOKEN&applicationId=x"))
    }

    @Test
    fun `user info and a token path segment go`() {
        assertEquals("https://host/p", UrlRedaction.redact("https://user:SECRET@host/p"))
        assertEquals(
            "https://api.example.com/rup/ABC/<redacted>?<redacted>",
            UrlRedaction.redact("https://api.example.com/rup/ABC/eyJSZXNvdXJjZUlEIjoiU0VDUkVUIn0?guid=1"),
        )
        assertEquals("https://x/eyJ/short", UrlRedaction.redact("https://x/eyJ/short"))
    }

    @Test
    fun `text around the URL is kept, and so is the punctuation after it`() {
        assertEquals(
            "Client request(GET https://x/y?<redacted>) invalid: 404 Not Found",
            UrlRedaction.redact("Client request(GET https://x/y?sig=SECRET) invalid: 404 Not Found"),
        )
        assertEquals("see https://x/y?<redacted>.", UrlRedaction.redact("see https://x/y?sig=SECRET."))
        assertEquals("""{"downloadUrl":"https://x/y?<redacted>","n":1}""", UrlRedaction.redact("""{"downloadUrl":"https://x/y?tempauth=SECRET","n":1}"""))
        assertEquals(
            "a https://x/1?<redacted> and b https://x/2?<redacted>",
            UrlRedaction.redact("a https://x/1?sig=ONE and b https://x/2?sig=TWO"),
        )
    }

    @Test
    fun `a credential pair quoted without its URL is masked`() {
        assertEquals("query was tempauth=<redacted>&x=1", UrlRedaction.redact("query was tempauth=SECRET&x=1"))
        assertEquals("X-Amz-Signature=<redacted>", UrlRedaction.redact("X-Amz-Signature=SECRET"))
        assertEquals("access_token=<redacted> failed", UrlRedaction.redact("access_token=SECRET failed"))
    }

    @Test
    fun `text without a URL or a credential comes back as it is`() {
        for (text in listOf("", "plain failure", "C:\\Users\\a\\b.txt", "size=10 mtime=5", "ratio 1=1", "a:b/c?d")) {
            assertEquals(text, UrlRedaction.redact(text))
        }
    }

    @Test
    fun `redacting twice changes nothing`() {
        val once = UrlRedaction.redact("[url=https://u:p@x/y/eyJhbGciOiJIUzI1NiJ9abc?tempauth=SECRET#f, request_timeout=5 ms] token=SECRET")
        assertEquals(once, UrlRedaction.redact(once))
        assertFalse("SECRET" in once, once)
    }
}
