package org.krost.unidrive.dropbox

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The 401 replay asks the token supplier for a forced refresh *naming the token the refused request
 * carried*: the auth layer can then take a stored token that already differs from it as fresh (another
 * caller's refresh answered the 401) instead of rotating the token a second time (#215).
 */
class DropboxApiRejectedTokenTest {
    @Test
    fun `the 401 replay names the token the refused request carried`() =
        runTest {
            val fake = FakeDropbox()
            fake.on("users/get_current_account") { FakeDropbox.Answer(401, """{"error_summary":"expired_access_token/.."}""") }
            fake.on("users/get_current_account") { FakeDropbox.Answer(body = """{"account_id":"dbid:a"}""") }
            val asks = mutableListOf<Pair<Boolean, String?>>()
            var tokens = 0
            val api =
                DropboxApi(
                    DropboxConfig(appKey = "key", tokenPath = Files.createTempDirectory("dbx-rejected")),
                    { forceRefresh, rejectedToken ->
                        asks += forceRefresh to rejectedToken
                        if (tokens++ == 0) "tok-1" else "tok-2"
                    },
                    engine = fake.engine,
                )

            api.rpc("users/get_current_account")
            api.close()

            assertEquals(listOf<Pair<Boolean, String?>>(false to null, true to "tok-1"), asks, "the replay names the token the refused request carried")
        }
}
