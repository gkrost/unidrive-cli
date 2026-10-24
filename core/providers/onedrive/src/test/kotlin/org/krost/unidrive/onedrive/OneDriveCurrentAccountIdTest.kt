package org.krost.unidrive.onedrive

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AccountOwner
import org.krost.unidrive.onedrive.model.Token
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The start-up recording (`CloudProvider.currentAccountId`) reads the same field as the sign-in check, the owner of
 * `GET /me/drive`, because the app's file-only scopes get 403 from `GET /me` (archive#1247).
 */
class OneDriveCurrentAccountIdTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("od-curid-")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun provider(driveJson: String): OneDriveProvider =
        runBlocking {
            OAuthService(OneDriveConfig(tokenPath = dir)).use {
                it.saveToken(Token("a".repeat(64), "Bearer", System.currentTimeMillis() + 3_600_000L, "rt"))
            }
            OneDriveProvider(
                OneDriveConfig(tokenPath = dir),
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/me")) {
                        respond("", HttpStatusCode.Forbidden)
                    } else {
                        respond(driveJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                },
            ).also { it.reloadCredentials() }
        }

    private fun drive(owner: String) = """{"id":"d1","driveType":"personal"$owner}"""

    private val ada = drive(""","owner":{"user":{"id":"id-ada","displayName":"Ada"}}""")

    @Test
    fun `the id is the drive owner's, though Graph me answers 403`() =
        runBlocking {
            assertEquals("id-ada", provider(ada).currentAccountId())
        }

    @Test
    fun `start-up records the owner id when none is stored`() =
        runBlocking {
            AccountOwner.recordIfMissing(dir, 5_000) { provider(ada).currentAccountId() }

            assertEquals("id-ada", AccountOwner.read(dir))
        }

    @Test
    fun `a drive answer without an owner gives no id and records nothing`() =
        runBlocking {
            val p = provider(drive(""))
            assertNull(p.currentAccountId())
            AccountOwner.recordIfMissing(dir, 5_000) { p.currentAccountId() }

            assertNull(AccountOwner.read(dir))
        }
}
