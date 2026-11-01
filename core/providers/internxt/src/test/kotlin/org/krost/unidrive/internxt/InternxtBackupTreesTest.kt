package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.PermanentUploadFailureException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.internxt.model.InternxtFolder
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The account's backup devices (`GET /backup/deviceAsFolder`): a tree of its own whose top folder the account lists as a
 * device is a backup tree, and its items are backup items ([CloudItem.backup]). Any other tree of its own (a photos tree,
 * something unknown) is not. The listing still leaves every such tree out, as before; what changes is that the provider
 * knows which of them are backups, asks for the device list once per full gather, and refuses a write into a device's
 * tree from the device set alone, without climbing the folder chain.
 */
class InternxtBackupTreesTest {
    // /Docs (a1) with readme. Two devices that only their metas know: DEV1 with the backed-up folder Documents (B1) and a
    // file in each, DEV2 with one file. PH is a tree of its own the device list does not name, with one file.
    private fun drive(devices: List<String>? = listOf("DEV1", "DEV2")) =
        FakeDrive(
            listOf(FakeFolder("a1", "Docs", "root", fakeAt("19:30:00")), FakeFolder("B1", "Documents", "DEV1", fakeAt("19:35:00"))),
            listOf(
                FakeFile("f1", "readme", "a1", fakeAt("19:10:00")),
                FakeFile("b0", "top", "DEV1", fakeAt("19:20:00")),
                FakeFile("b1", "report", "B1", fakeAt("19:21:00")),
                FakeFile("b2", "notes", "DEV2", fakeAt("19:22:00")),
                FakeFile("p1", "holiday", "PH", fakeAt("19:23:00")),
            ),
            pageSize = 100,
            metaOnly =
                listOf(
                    FakeFolder("DEV1", "Laptop", null, fakeAt("19:00:00")),
                    FakeFolder("DEV2", "Tower", null, fakeAt("19:01:00")),
                    FakeFolder("PH", "Camera", null, fakeAt("19:02:00")),
                ),
            devices = devices,
        )

    private fun paths(page: DeltaPage) = page.items.map { it.path }.toSet()

    // The provider's "outside the drive" lines of what [run] did, at INFO.
    private fun outsideLines(run: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(InternxtProvider::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val before = logger.level
        logger.level = Level.INFO
        logger.addAppender(appender)
        try {
            runTest { run() }
            return appender.list.map { it.formattedMessage }.filter { "outside the drive" in it }
        } finally {
            logger.detachAppender(appender)
            logger.level = before
        }
    }

    @Test
    fun `only the items of a device tree are backup items`() {
        fun folder(uuid: String, parent: String?) = InternxtFolder(uuid = uuid, parentUuid = parent)
        val folderMap = listOf(folder("a1", "root"), folder("DEV1", null), folder("B1", "DEV1"), folder("DEV2", null), folder("PH", null)).associateBy { it.uuid }
        fun item(id: String, parent: String?, isFolder: Boolean = false) = CloudItem(id, id, "/$id", 0, isFolder, null, null, null, null, parentId = parent)
        val items =
            listOf(
                item("a1", "root", isFolder = true),
                item("f1", "a1"),
                item("DEV1", null, isFolder = true),
                item("b0", "DEV1"),
                item("B1", "DEV1", isFolder = true),
                item("b1", "B1"),
                item("b2", "DEV2"),
                item("p1", "PH"),
                item("lost", "unknown-folder"),
            )

        val marked = BackupTrees(setOf("DEV1", "DEV2"), folderMap, "root").mark(items)

        assertEquals(setOf("DEV1", "b0", "B1", "b1", "b2"), marked.filter { it.backup }.map { it.id }.toSet())
        assertEquals(items.map { it.id }, marked.map { it.id }, "the order and the items are kept")
        assertEquals(items.map { it.copy(backup = false) }, marked.map { it.copy(backup = false) }, "nothing but the flag changes")
    }

    @Test
    fun `a full gather tells the device trees from other trees and still leaves both out`() {
        val drive = drive()
        var page: DeltaPage? = null

        val lines = outsideLines { page = internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context()) }

        assertEquals(setOf("/Docs", "/Docs/readme.txt"), paths(page!!), "placement is unchanged: nothing outside the drive is listed")
        assertEquals(setOf("b0", "b1", "B1", "b2", "p1"), page!!.unreachableIds)
        assertTrue(page!!.complete)
        val line = lines.single()
        assertTrue("5 item(s) sit below 3 folder(s) outside the drive" in line, line)
        assertTrue("4 of them in 2 backup device(s)" in line, line)
        for (name in listOf("Laptop", "Tower", "Camera", "Documents", "report", "/")) {
            assertTrue(name !in line, "no name and no path in the line: $line")
        }
    }

    @Test
    fun `a full gather costs the listing, one meta per tree top and one device list`() =
        runTest {
            val drive = drive()

            internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context())

            assertEquals(1, drive.deviceRequests.get(), "the device list is asked once per gather, for the census and the flags alike")
            assertEquals(listOf("DEV1", "DEV2", "PH"), drive.metaRequests.sorted(), "one meta per tree top, none per backup item")
            val rest = drive.requests.filterNot { it.endsWith("/backup/deviceAsFolder") || it.endsWith("/meta") }
            val baseline = listOf("/folders/sync", "/files/sync", "/users/usage")
            assertTrue(rest.all { request -> baseline.any { request.endsWith(it) } }, "only the baseline of a gather besides: $rest")
        }

    @Test
    fun `an account without backups has no backup items and the gather does not fail`() {
        val drive = drive(devices = null)
        var page: DeltaPage? = null

        val lines = outsideLines { page = internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context()) }

        assertTrue(page!!.complete, "the 400 on the device list is no failure")
        assertEquals(setOf("b0", "b1", "B1", "b2", "p1"), page!!.unreachableIds, "every tree of its own stays out")
        assertTrue("0 of them in 0 backup device(s)" in lines.single(), lines.single())
    }

    // Should not happen: the device list names a folder that hangs below the drive root. The drive root wins: its items
    // are the drive's, listed as before, not backup items, and a write to them is not refused.
    @Test
    fun `a listed device that the drive root reaches stays part of the drive`() {
        val drive = drive(devices = listOf("a1", "DEV1"))
        val provider = internxtProviderOn(drive.engine)
        var page: DeltaPage? = null

        val lines =
            outsideLines {
                page = provider.delta(null, null, FakeStaging().context())
                provider.deleteById("f1", "/Docs/readme.txt")
            }

        assertEquals(setOf("/Docs", "/Docs/readme.txt"), paths(page!!))
        assertTrue("3 of them in 1 backup device(s)" in lines.single(), lines.single())
        assertEquals(listOf("POST /drive/storage/trash/add"), drive.writes.toList(), "the drive's file is still trashed")
    }

    @Test
    fun `a write into a device tree is refused from the device set before anything is sent`() =
        runTest {
            val drive = drive()
            val provider = internxtProviderOn(drive.engine)
            provider.delta(null, null, FakeStaging().context())
            val local = Files.createTempFile("ud-backup", ".txt")
            try {
                Files.writeString(local, "edited")

                // The device folder itself: no request at all.
                val sent = drive.requests.size
                val device = assertFailsWith<ProviderException> { provider.deleteById("DEV1", "/Laptop") }
                assertEquals(sent, drive.requests.size, "the device set answers alone: ${drive.requests.drop(sent)}")
                assertTrue("backup device" in device.message.orEmpty(), device.message)

                // Directly below a device: the item's own meta, and no folder is climbed.
                drive.metaRequests.clear()
                for (refused in listOf(
                    runCatching { provider.deleteById("b0", "/Laptop/top.txt") },
                    runCatching { provider.moveById("B1", "/Laptop/Documents", "/Docs/Documents") },
                    runCatching { provider.deletePermanentlyById("b2", "/Tower/notes.txt") },
                    runCatching { provider.upload(local, "/Laptop/top.txt", existingRemoteId = "b0", onProgress = null) },
                )) {
                    val e = refused.exceptionOrNull()
                    assertTrue(e is ProviderException || e is PermanentUploadFailureException, "refused: $e")
                    assertTrue("backup device" in e!!.message.orEmpty(), e.message)
                }
                assertEquals(listOf("B1"), drive.metaRequests.toList(), "only the moved folder's own meta: nothing above an item whose parent is a device is climbed")

                // Deeper in a device tree: the chain is climbed once, and the refusal still names the backup.
                val deep = assertFailsWith<ProviderException> { provider.deleteById("b1", "/Laptop/Documents/report.txt") }
                assertTrue("backup device" in deep.message.orEmpty(), deep.message)

                // A tree of its own that is no device is refused as before, as not part of the drive.
                val other = assertFailsWith<ProviderException> { provider.deleteById("p1", "/Camera/holiday.txt") }
                assertFalse("backup device" in other.message.orEmpty(), other.message)
                assertTrue("Not part of the drive" in other.message.orEmpty(), other.message)

                assertEquals(emptyList(), drive.writes.toList())
            } finally {
                Files.deleteIfExists(local)
            }
        }

    // A poll asks for no device list; the devices the last full gather confirmed still tell its items apart.
    @Test
    fun `an incremental delta uses the devices of the last full gather`() {
        val drive = drive()
        val provider = internxtProviderOn(drive.engine)
        val lines =
            outsideLines {
                provider.delta(null, null, FakeStaging().context())
                provider.delta("2026-07-28T18:00:00.000Z", null, FakeStaging().context())
            }

        assertEquals(1, drive.deviceRequests.get(), "the poll asked for no device list")
        assertEquals(2, lines.size, lines.toString())
        assertTrue("4 of them in 2 backup device(s)" in lines.last(), lines.last())
    }

    // A device folder made by an old desktop app may have no plain name (its name is encrypted); its row still reads.
    @Test
    fun `the device list reads rows with and without a plain name and an answer of 400 or 404 is no backups`() =
        runTest {
            var answer: Pair<Int, String> = 200 to
                """[{"id":1,"uuid":"DEV1","plainName":"Laptop","name":"enc","bucket":"b","parentId":null,"parentUuid":null,"status":"EXISTS",""" +
                """"removed":false,"deleted":false,"hasBackups":true,"lastBackupAt":"2026-07-28T10:00:00.000Z"},""" +
                """{"id":2,"uuid":"DEV2","name":"legacy-encrypted-name",""" +
                """"bucket":"b","parentId":null,"parentUuid":null,"status":"EXISTS","removed":true,"deleted":false,"hasBackups":false}]"""
            val engine =
                MockEngine { request ->
                    check(request.url.encodedPath.endsWith("/drive/backup/deviceAsFolder")) { "unexpected request: ${request.url}" }
                    respond(answer.second, HttpStatusCode.fromValue(answer.first), headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val provider = internxtProviderOn(engine)
            val api = InternxtProvider::class.java.getDeclaredField("api").apply { isAccessible = true }.get(provider) as InternxtApiService

            val devices = api.getBackupDevices()!!
            assertEquals(listOf("DEV1", "DEV2"), devices.map { it.uuid })
            assertEquals(listOf("Laptop", null), devices.map { it.plainName })

            for (status in listOf(400, 404)) {
                answer = status to """{"statusCode":$status,"message":"Backups is not enabled for this user"}"""
                assertEquals(null, api.getBackupDevices(), "HTTP $status")
            }
        }
}
