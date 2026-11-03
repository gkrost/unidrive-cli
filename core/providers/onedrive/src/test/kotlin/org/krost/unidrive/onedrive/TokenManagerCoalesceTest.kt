package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.onedrive.model.Token
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The forced refresh coalesces the concurrent 401s it answers (#215): the "stale" token was snapshotted
 * when the forced refresh was asked for, so a caller whose request went out with the old token and whose
 * replay arrives after another caller's refresh committed read the fresh token as "stale" and rotated it
 * again — one extra refresh per concurrent 401. The forced refresh names the token its rejected request
 * carried instead, and a stored token that already differs from it is taken as is.
 */
class TokenManagerCoalesceTest {
    private fun token(accessToken: String): Token =
        Token(
            accessToken = accessToken,
            tokenType = "Bearer",
            expiresAt = System.currentTimeMillis() + 3_600_000,
            refreshToken = "r".repeat(80),
            scope = "openid Files.ReadWrite.All",
        )

    @Test
    fun `a forced refresh naming the rejected token takes the stored one instead of rotating again`() =
        runTest {
            var refreshes = 0
            val engine =
                MockEngine {
                    refreshes++
                    respond(
                        content = """{"access_token":"${"B".repeat(64)}","token_type":"Bearer","expires_in":3600,"refresh_token":"${"r".repeat(80)}","scope":"openid Files.ReadWrite.All"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val manager = TokenManager(OneDriveConfig(), OAuthService(OneDriveConfig(), httpClient = HttpClient(engine)))
            manager.token = token("A".repeat(64))

            // The first rotation replaces the rejected token before the second caller's replay arrives.
            manager.getValidToken(forceRefresh = true)

            val again = manager.getValidToken(forceRefresh = true, rejectedToken = "A".repeat(64))

            assertEquals("B".repeat(64), again.accessToken)
            assertEquals(1, refreshes, "the stored token already answers the rejected one; no second refresh")
        }

    @Test
    fun `a forced refresh without a rejected token still rotates as before`() =
        runTest {
            var refreshes = 0
            val engine =
                MockEngine {
                    refreshes++
                    respond(
                        content = """{"access_token":"${"B".repeat(64)}","token_type":"Bearer","expires_in":3600,"refresh_token":"${"r".repeat(80)}","scope":"openid Files.ReadWrite.All"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val manager = TokenManager(OneDriveConfig(), OAuthService(OneDriveConfig(), httpClient = HttpClient(engine)))
            manager.token = token("A".repeat(64))

            manager.getValidToken(forceRefresh = true)
            manager.getValidToken(forceRefresh = true)

            assertEquals(2, refreshes, "no name, no proof of freshness: the forced refresh rotates")
        }
}
