package org.krost.unidrive.dropbox

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.TransientNetworkException
import org.krost.unidrive.dropbox.FakeDropbox.Answer
import java.io.EOFException
import java.io.IOException
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * archive#1256 B: a throttle or a server error from the token endpoint says nothing about the refresh token. Only the
 * network errors were classified transient; any non-2xx answer became "Dropbox refused the sign-in" and the daemon stopped.
 */
class DropboxRefreshStatusTest {
    // The access token is about to expire, so the first call refreshes; the token endpoint answers [answer].
    private fun providerWhoseRefreshAnswers(answer: Answer): DropboxProvider {
        val fake = FakeDropbox()
        fake.on("oauth2/token") { answer }
        return FakeDropbox.provider(fake, Files.createTempDirectory("dropbox-refresh-status"), expiresAt = System.currentTimeMillis() + 1_000)
    }

    @Test
    fun `a throttle or server error from the token endpoint is retryable, not a lost sign-in`() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            val provider = providerWhoseRefreshAnswers(Answer(status, """{"error":"temporarily_unavailable"}"""))
            runBlocking {
                assertFailsWith<TransientNetworkException>("HTTP $status") { provider.getMetadata("/a.txt") }
            }
        }
    }

    // The shapes of the shipping engine (OkHttp): a name that does not resolve is an UnknownHostException (CIO threw an
    // UnresolvedAddressException), a connection the server closed before it answered an IOException around an EOFException.
    @Test
    fun `the network failures of the shipping engine are retryable, not a lost sign-in`() {
        val shapes: Map<String, () -> Throwable> =
            mapOf(
                "UnknownHostException" to { UnknownHostException("api.example.test") },
                "closed before an answer" to { IOException("unexpected end of stream on https://api.example.test/...", EOFException("\n not found: limit=0")) },
            )
        for ((name, failure) in shapes) {
            val fake = FakeDropbox()
            fake.on("oauth2/token") { throw failure() }
            val provider = FakeDropbox.provider(fake, Files.createTempDirectory("dropbox-refresh-network"), expiresAt = System.currentTimeMillis() + 1_000)
            runBlocking {
                assertFailsWith<TransientNetworkException>(name) { provider.getMetadata("/a.txt") }
            }
        }
    }

    @Test
    fun `a revoked refresh token is still a lost sign-in`() {
        val provider =
            providerWhoseRefreshAnswers(
                Answer(400, """{"error":"invalid_grant","error_description":"refresh token is invalid or revoked"}"""),
            )
        runBlocking {
            assertFailsWith<AuthenticationException> { provider.getMetadata("/a.txt") }
        }
    }
}
