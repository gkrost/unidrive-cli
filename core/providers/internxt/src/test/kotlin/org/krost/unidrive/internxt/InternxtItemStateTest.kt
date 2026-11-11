package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ItemRemoteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * gkrost/unidrive-cli#632: [InternxtProvider.itemStateById] asks the file's meta first, then the folder's. The same
 * words as [InternxtProvider.folderStateById] reads: `TRASHED`/`removed` is the trash, `DELETED`/`deleted` is gone
 * for good, everything else is live; a call that failed (a 5xx) is not an answer and propagates.
 */
class InternxtItemStateTest {
    private val json = headersOf("Content-Type", "application/json")
    private val FILE = "file-uuid"
    private val FOLDER = "folder-uuid"

    /** A file meta body; the fields itemStateById reads plus the ones the decoder requires. */
    private fun fileMeta(
        status: String = "EXISTS",
        removed: Boolean = false,
        deleted: Boolean = false,
    ) = """{"uuid":"$FILE","plainName":"a.txt","status":"$status","removed":$removed,"deleted":$deleted}"""

    private fun folderMeta(
        status: String = "EXISTS",
        removed: Boolean = false,
        deleted: Boolean = false,
    ) = """{"uuid":"$FOLDER","plainName":"d","parentUuid":"root","status":"$status","removed":$removed,"deleted":$deleted}"""

    private fun providerAnswering(fileStatus: HttpStatusCode, fileBody: String, folderStatus: HttpStatusCode, folderBody: String): InternxtProvider =
        internxtProviderOn(
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/files/$FILE/meta") -> respond(fileBody, fileStatus, json)
                    request.url.encodedPath.endsWith("/folders/$FOLDER/meta") -> respond(folderBody, folderStatus, json)
                    else -> respond("""{"error":"unexpected ${request.url}"}""", HttpStatusCode.NotFound, json)
                }
            },
        )

    @Test
    fun `a live file is Live(false)`() = runTest {
        val p = providerAnswering(HttpStatusCode.OK, fileMeta(), HttpStatusCode.OK, folderMeta())
        assertEquals(ItemRemoteState.Live(false), p.itemStateById(FILE))
    }

    @Test
    fun `a trashed file is Trashed, by status or by the removed flag`() = runTest {
        val p = providerAnswering(HttpStatusCode.OK, fileMeta(status = "TRASHED"), HttpStatusCode.OK, folderMeta())
        assertEquals(ItemRemoteState.Trashed(false), p.itemStateById(FILE))
        val p2 = providerAnswering(HttpStatusCode.OK, fileMeta(removed = true), HttpStatusCode.OK, folderMeta())
        assertEquals(ItemRemoteState.Trashed(false), p2.itemStateById(FILE))
    }

    @Test
    fun `a file deleted for good is NotFound`() = runTest {
        val p = providerAnswering(HttpStatusCode.OK, fileMeta(deleted = true), HttpStatusCode.OK, folderMeta())
        assertEquals(ItemRemoteState.NotFound, p.itemStateById(FILE))
    }

    @Test
    fun `a 404 file falls through to the folder, live and trashed`() = runTest {
        val live = providerAnswering(HttpStatusCode.NotFound, """{"error":"no file"}""", HttpStatusCode.OK, folderMeta())
        assertEquals(ItemRemoteState.Live(true), live.itemStateById(FOLDER))
        val trashed = providerAnswering(HttpStatusCode.NotFound, """{"error":"no file"}""", HttpStatusCode.OK, folderMeta(status = "TRASHED"))
        assertEquals(ItemRemoteState.Trashed(true), trashed.itemStateById(FOLDER))
    }

    @Test
    fun `neither a file nor a folder is NotFound`() = runTest {
        val p = providerAnswering(HttpStatusCode.NotFound, """{"error":"no file"}""", HttpStatusCode.NotFound, """{"error":"no folder"}""")
        assertEquals(ItemRemoteState.NotFound, p.itemStateById("neither"))
    }

    @Test
    fun `a gateway failure propagates, it proves nothing`() = runTest {
        val p = providerAnswering(HttpStatusCode.BadGateway, """{"error":"bad gateway"}""", HttpStatusCode.BadGateway, """{"error":"bad gateway"}""")
        assertFails { p.itemStateById(FILE) }
    }
}
