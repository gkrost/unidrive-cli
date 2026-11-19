package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.krost.unidrive.ProviderException
import org.krost.unidrive.testing.TestScratch

/**
 * The delete fast path (#634, #831): a delete by id of an item a listing already named runs the outside-drive
 * refusal from the remembered parent and kind, so the trash call is the item's only round-trip — a mount-side
 * delete burst is one call per file, not meta plus trash. The meta-first path stays for an unknown id, and a
 * first-attempt 400 of the trash falls back to the meta to tell the already-gone shapes apart.
 */
class InternxtDeleteFastPathTest {

    // ── the delta-driven fast path, on the full fake drive ───────────────────────────────────────────────

    @Test
    fun `a delete by id of a delta-listed file is one trash call and no meta`() = runTest {
        val drive =
            FakeDrive(
                listOf(FakeFolder("a1", "Docs", "root", fakeAt("19:30:00"))),
                listOf(
                    FakeFile("f1", "readme", "a1", fakeAt("19:10:00")),
                    FakeFile("f2", "note", "a1", fakeAt("19:11:00")),
                ),
                pageSize = 100,
            )
        val provider = internxtProviderOn(drive.engine)
        try {
            provider.delta(null, null, FakeStaging().context())
            drive.requests.clear()

            provider.deleteById("f1", "/Docs/readme.txt")
            provider.deleteById("f2", "/Docs/note.txt")

            // The burst shape: one trash call per file, no per-item meta, not even one climb (the delta proved
            // the parent chain).
            assertEquals(listOf("POST /drive/storage/trash/add", "POST /drive/storage/trash/add"), drive.requests)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `a delete by id of a detached tree's item is refused from the listing alone`() = runTest {
        val drive =
            FakeDrive(
                listOf(
                    FakeFolder("a1", "Docs", "root", fakeAt("19:30:00")),
                    FakeFolder("B1", "Documents", "DEV", fakeAt("19:35:00")),
                ),
                listOf(
                    FakeFile("f1", "readme", "a1", fakeAt("19:10:00")),
                    FakeFile("b1", "report", "B1", fakeAt("19:21:00")),
                ),
                pageSize = 100,
                metaOnly = listOf(FakeFolder("DEV", "Laptop", null, fakeAt("19:00:00"))),
            )
        val provider = internxtProviderOn(drive.engine)
        try {
            provider.delta(null, null, FakeStaging().context())
            drive.requests.clear()

            val refused = assertFailsWith<ProviderException> { provider.deleteById("b1", "/Documents/report.txt") }
            assertTrue(refused.message!!.contains("Not part of the drive"), refused.message)
            assertEquals(emptyList(), drive.writes, "nothing is sent for a tree of its own")
            assertTrue(drive.requests.none { it.contains("/files/$fileUuid") || it.contains("/files/b1") })
        } finally {
            provider.close()
        }
    }

    // ── the meta path and the 400 fallback, on a scripted drive ──────────────────────────────────────────

    private class Scripted(
        val fileMetaStatuses: List<Int>,
        val trashStatuses: List<Int>,
    ) {
        val fileMetaRequests = AtomicInteger(0)
        val trashRequests = AtomicInteger(0)

        val engine =
            MockEngine { request ->
                val url = request.url.toString()
                fun answer(status: Int) =
                    if (status == 200) {
                        respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    } else {
                        respond("""{"statusCode":$status,"message":"x"}""", HttpStatusCode.fromValue(status), headersOf("Content-Type", "application/json"))
                    }
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
                    url.endsWith("/drive/files/$fileUuid/meta") -> {
                        val status = fileMetaStatuses[minOf(fileMetaRequests.getAndIncrement(), fileMetaStatuses.size - 1)]
                        if (status == 200) {
                            respond(reportJson, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                        } else {
                            answer(status)
                        }
                    }
                    url.endsWith("/storage/trash/add") -> answer(trashStatuses[minOf(trashRequests.getAndIncrement(), trashStatuses.size - 1)])
                    url.endsWith("/meta") -> respond("""{"statusCode":404}""", HttpStatusCode.NotFound, headersOf("Content-Type", "application/json"))
                    else -> error("unexpected URL: $url")
                }
            }
    }

    private suspend fun scriptedProvider(scripted: Scripted): InternxtProvider {
        val tmp = TestScratch.directory("ud-delete-fastpath-")
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("""{"exp":9999999999}""".toByteArray())
        return InternxtProvider(
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
            httpClient = HttpClient(scripted.engine),
        )
    }

    @Test
    fun `an id no listing named keeps the meta-first delete`() = runTest {
        val scripted = Scripted(fileMetaStatuses = listOf(200), trashStatuses = listOf(200))
        val provider = scriptedProvider(scripted)
        try {
            provider.deleteById(fileUuid, "/x/report.txt")
        } finally {
            provider.close()
        }
        assertEquals(1, scripted.fileMetaRequests.get(), "the unknown id is asked for its meta")
        assertEquals(1, scripted.trashRequests.get())
    }

    @Test
    fun `a first-attempt trash 400 of a listed item settles as not found when the meta says gone`() = runTest {
        val scripted = Scripted(fileMetaStatuses = listOf(404), trashStatuses = listOf(400))
        val provider = scriptedProvider(scripted)
        try {
            provider.getMetadata("/x/report.txt") // the listing that names the item's parent and kind
            val gone = assertFailsWith<ProviderException> { provider.deleteById(fileUuid, "/x/report.txt") }
            assertTrue(gone.message!!.contains("Item not found"), gone.message)
        } finally {
            provider.close()
        }
        assertEquals(1, scripted.trashRequests.get())
        // The fallback meta: the file's 404, then the folder probe's 404, both answered by the scripted engine.
        assertEquals(1, scripted.fileMetaRequests.get())
    }

    @Test
    fun `a first-attempt trash 400 of a live listed item rethrows as the meta-first delete would`() = runTest {
        val scripted = Scripted(fileMetaStatuses = listOf(200), trashStatuses = listOf(400))
        val provider = scriptedProvider(scripted)
        try {
            provider.getMetadata("/x/report.txt")
            val refused = assertFailsWith<InternxtApiException> { provider.deleteById(fileUuid, "/x/report.txt") }
            assertEquals(400, refused.statusCode)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `a listed item's delete retries a 502 trash and succeeds, paying no meta`() = runTest {
        val scripted = Scripted(fileMetaStatuses = listOf(200), trashStatuses = listOf(502, 200))
        val provider = scriptedProvider(scripted)
        try {
            provider.getMetadata("/x/report.txt")
            provider.deleteById(fileUuid, "/x/report.txt")
        } finally {
            provider.close()
        }
        assertEquals(0, scripted.fileMetaRequests.get(), "the retry stays on the fast path")
        assertEquals(2, scripted.trashRequests.get())
    }

    companion object {
        private const val folderUuid = "x-folder-uuid"
        private const val fileUuid = "report-uuid"
        private val reportJson =
            """{"uuid":"$fileUuid","plainName":"report","type":"txt","size":"42",
                "folderUuid":"$folderUuid","status":"EXISTS"}"""
    }
}
