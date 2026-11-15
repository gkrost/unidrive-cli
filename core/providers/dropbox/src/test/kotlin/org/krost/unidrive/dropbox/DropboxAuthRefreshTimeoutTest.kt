package org.krost.unidrive.dropbox

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.TransientNetworkException
import org.krost.unidrive.auth.RefreshableTokenLatch
import org.krost.unidrive.UnidriveJson
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * #748 finding 1: a token refresh that runs past the latch's 60 s deadline (a token endpoint that accepted and
 * then stalls always loses to the latch) used to escape [DropboxAuth.accessToken] as a TimeoutCancellationException
 * — a CancellationException — which callers' `catch (CancellationException) { throw e }` blocks read as "this
 * caller was cancelled" and abort silently. The latch converts its deadline into a
 * [java.net.SocketTimeoutException], and the refresh's transient classification turns it into a retryable
 * [TransientNetworkException].
 */
class DropboxAuthRefreshTimeoutTest {
    @Test
    fun `a token endpoint that stalls past the latch deadline surfaces retryable, not a cancellation`() = runTest {
        val dir = Files.createTempDirectory("dropbox-refresh-timeout")
        // A grant near its expiry, so accessToken() must refresh.
        Files.writeString(
            dir.resolve(DropboxAuth.TOKEN_FILE),
            UnidriveJson.encodeToString(
                DropboxToken.serializer(),
                DropboxToken("a".repeat(40), System.currentTimeMillis() + 1_000, "r1"),
            ),
        )
        // The token endpoint accepted and then stalls: the refresh never answers, and the latch deadline (real
        // time, Dispatchers.Default) is the only thing that can end it.
        val auth =
            DropboxAuth(
                DropboxConfig(appKey = "key", tokenPath = dir),
                HttpClient(MockEngine { awaitCancellation() }),
                refreshLatch = RefreshableTokenLatch(timeout = 300.milliseconds),
            )
        auth.load()

        assertFailsWith<TransientNetworkException> { auth.accessToken() }
    }
}
