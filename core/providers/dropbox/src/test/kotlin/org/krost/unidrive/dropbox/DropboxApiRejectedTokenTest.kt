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

    /**
     * Only the replay of a 401 names a rejected token. `401(A) -> 429(B) -> 200`: B was throttled, not refused,
     * so the third request neither forces a refresh nor names B as rejected, and carries B like the second.
     */
    @Test
    fun `a throttle after the 401 replay does not force a second refresh`() =
        runTest {
            val fake = FakeDropbox()
            fake.on("users/get_current_account") { FakeDropbox.Answer(401, """{"error_summary":"expired_access_token/.."}""") }
            fake.on("users/get_current_account") { FakeDropbox.Answer(429, """{"error_summary":"too_many_requests/.."}""", headers = mapOf("Retry-After" to "1")) }
            fake.on("users/get_current_account") { FakeDropbox.Answer(body = """{"account_id":"dbid:a"}""") }
            val asks = mutableListOf<Pair<Boolean, String?>>()
            var stored = "A"
            val api =
                DropboxApi(
                    DropboxConfig(appKey = "key", tokenPath = Files.createTempDirectory("dbx-rejected")),
                    { forceRefresh, rejectedToken ->
                        asks += forceRefresh to rejectedToken
                        if (forceRefresh) stored = "B"
                        stored
                    },
                    engine = fake.engine,
                )

            api.rpc("users/get_current_account")
            api.close()

            assertEquals(listOf<Pair<Boolean, String?>>(false to null, true to "A", false to null), asks, "one forced refresh, naming A")
            assertEquals(
                listOf("Bearer A", "Bearer B", "Bearer B"),
                fake.callsTo("users/get_current_account").map { it.headers.entries.first { (k, _) -> k.equals("Authorization", true) }.value },
            )
        }

    @Test
    fun `a download throttled after the 401 replay does not force a second refresh`() =
        runTest {
            val dir = Files.createTempDirectory("dbx-rejected-dl")
            val fake = FakeDropbox()
            val meta = FakeDropbox.file("/a.bin", "id:1", size = 4)
            fake.on("files/download") { FakeDropbox.Answer(401, """{"error_summary":"expired_access_token/.."}""") }
            fake.on("files/download") { FakeDropbox.Answer(429, """{"error_summary":"too_many_requests/.."}""", headers = mapOf("Retry-After" to "1")) }
            fake.on("files/download") { FakeDropbox.Answer(bytes = ByteArray(4), headers = mapOf(DropboxApi.API_RESULT to meta)) }
            val asks = mutableListOf<Pair<Boolean, String?>>()
            var stored = "A"
            val api =
                DropboxApi(
                    DropboxConfig(appKey = "key", tokenPath = dir),
                    { forceRefresh, rejectedToken ->
                        asks += forceRefresh to rejectedToken
                        if (forceRefresh) stored = "B"
                        stored
                    },
                    engine = fake.engine,
                )

            api.download("/a.bin", dir.resolve("a.bin"))
            api.close()

            assertEquals(listOf<Pair<Boolean, String?>>(false to null, true to "A", false to null), asks, "one forced refresh, naming A")
            assertEquals(
                listOf("Bearer A", "Bearer B", "Bearer B"),
                fake.callsTo("files/download").map { it.headers.entries.first { (k, _) -> k.equals("Authorization", true) }.value },
            )
        }
}
