package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The routine delete (InternxtProvider.delete) is getMetadata plus api.trashItems with no reconcile behind it, so a
 * transient 500/502/504 on the trash must be repeated, and the 400 a repeat of an already-committed trash gets must
 * not fail the delete.
 */
class InternxtProviderDeleteRetryTest {
    private val folderUuid = "x-folder-uuid"
    private val reportJson =
        """{"uuid":"report-uuid","plainName":"report","type":"txt","size":"42",
            "folderUuid":"$folderUuid","status":"EXISTS"}"""

    private fun engine(
        trashAnswers: List<HttpStatusCode>,
        trashRequests: AtomicInteger,
    ) = MockEngine { request ->
        val url = request.url.toString()
        when {
            url.contains("/drive/folders/content/root-folder-uuid") ->
                respond(
                    """{"children":[{"uuid":"$folderUuid","plainName":"x","parentUuid":"root-folder-uuid","status":"EXISTS"}],"files":[]}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            url.contains("/drive/folders/content/$folderUuid") ->
                respond(
                    """{"children":[],"files":[$reportJson]}""",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            url.endsWith("/storage/trash/add") -> {
                val status = trashAnswers[minOf(trashRequests.getAndIncrement(), trashAnswers.size - 1)]
                if (status == HttpStatusCode.OK) {
                    respond("{}", status, headersOf("Content-Type", "application/json"))
                } else {
                    respond(
                        """{"statusCode":${status.value},"message":"x"}""",
                        status,
                        headersOf("Content-Type", "application/json"),
                    )
                }
            }
            else -> error("unexpected URL: $url")
        }
    }

    private suspend fun deleteWith(vararg trashAnswers: HttpStatusCode): Int {
        val trashRequests = AtomicInteger(0)
        val tmp = java.nio.file.Files.createTempDirectory("ud-delete-retry-")
        try {
            val payloadB64 =
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("""{"exp":9999999999}""".toByteArray())
            val provider =
                InternxtProvider(
                    InternxtConfig(credentialProtector = null, tokenPath = tmp),
                    credentials =
                        org.krost.unidrive.internxt.model.InternxtCredentials(
                            jwt = "header.$payloadB64.signature",
                            mnemonic =
                                "abandon abandon abandon abandon abandon abandon " +
                                    "abandon abandon abandon abandon abandon about",
                            rootFolderId = "root-folder-uuid",
                            email = "test@example.invalid",
                            bridgeUser = "bridge-user",
                            bridgeUserId = "bridge-secret",
                            bucket = "6928426c1a2316b856c9ab81",
                        ),
                    httpClient = io.ktor.client.HttpClient(engine(trashAnswers.toList(), trashRequests)),
                )
            try {
                provider.delete("/x/report.txt")
            } finally {
                provider.close()
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
        return trashRequests.get()
    }

    @Test
    fun `delete repeats a trash that answered 502 once and succeeds on 200`() =
        runTest {
            assertEquals(2, deleteWith(HttpStatusCode.BadGateway, HttpStatusCode.OK))
        }

    @Test
    fun `delete succeeds when the repeat of a 502 trash answers 400 because the first attempt committed`() =
        runTest {
            assertEquals(2, deleteWith(HttpStatusCode.BadGateway, HttpStatusCode.BadRequest))
        }
}
