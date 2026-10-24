package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.http.HttpRetryBudget
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A full gather says in one line how much hangs below each folder without a parent other than the drive root, by the top
 * folder's uuid, and which of those tops the account lists as backup devices: what is needed to tell device backups
 * from other trees of their own before anything is moved. The line never names a folder or a path, and the listing
 * itself is unchanged. A failure to read the device list is a clause of that line: it does not wait the enumeration's
 * connect-outage budget and it does not record a drive throttle.
 */
class InternxtTreeTopsCensusTest {
    // /Docs (a1) with readme. Two trees of their own that only their metas know: the device DEV with the backed-up folder
    // Documents (B1) and two files, and PH, which the device list does not name, with one file.
    private fun drive(
        devices: List<String>? = listOf("DEV"),
        deviceStatus: Int? = null,
    ) = FakeDrive(
        listOf(FakeFolder("a1", "Docs", "root", fakeAt("19:30:00")), FakeFolder("B1", "Documents", "DEV", fakeAt("19:35:00"))),
        listOf(
            FakeFile("f1", "readme", "a1", fakeAt("19:10:00")),
            FakeFile("b0", "top", "DEV", fakeAt("19:20:00")),
            FakeFile("b1", "report", "B1", fakeAt("19:21:00")),
            FakeFile("p1", "holiday", "PH", fakeAt("19:22:00")),
        ),
        pageSize = 100,
        metaOnly = listOf(FakeFolder("DEV", "Laptop", null, fakeAt("19:00:00")), FakeFolder("PH", "Camera", null, fakeAt("19:01:00"))),
        devices = devices,
        deviceStatus = deviceStatus,
    )

    private fun censusOf(run: suspend () -> DeltaPage): Pair<DeltaPage, List<String>> {
        val (page, lines, _) = censusTimed(run)
        return page to lines
    }

    private fun censusTimed(run: suspend () -> DeltaPage): Triple<DeltaPage, List<String>, Long> {
        val logger = LoggerFactory.getLogger(InternxtProvider::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val before = logger.level
        logger.level = Level.INFO
        logger.addAppender(appender)
        try {
            var page: DeltaPage? = null
            var elapsed = 0L
            runTest {
                page = run()
                elapsed = testScheduler.currentTime
            }
            val lines = appender.list.map { it.formattedMessage }.filter { it.startsWith("Internxt full gather:") }
            return Triple(page!!, lines, elapsed)
        } finally {
            logger.detachAppender(appender)
            logger.level = before
        }
    }

    private fun driveBudgetOf(provider: InternxtProvider): HttpRetryBudget {
        val api = InternxtProvider::class.java.getDeclaredField("api").apply { isAccessible = true }.get(provider)
        return InternxtApiService::class.java.getDeclaredField("driveBudget").apply { isAccessible = true }.get(api) as HttpRetryBudget
    }

    @Test
    fun `a full gather counts what hangs below each parentless top and how many tops are backup devices`() {
        val drive = drive()

        val (page, lines) = censusOf { internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context()) }

        assertEquals(1, lines.size, "one line per full gather: $lines")
        val line = lines.single()
        assertTrue("3 file(s) and 1 folder(s) hang below 2 folder(s)" in line, line)
        assertTrue("[DEV: 2 files, 1 folders, PH: 1 files, 0 folders]" in line, line)
        // The count alone does not say which top was in the device set.
        assertTrue("1 of the tops are among the 1 backup device folder(s) the account lists: DEV" in line, line)
        assertTrue("lists: PH" !in line && "DEV, PH" !in line, line)
        for (name in listOf("Laptop", "Camera", "Documents", "report", "holiday", "/")) {
            assertTrue(name !in line, "no folder or file name and no path in the line: $line")
        }
        assertEquals(1, drive.deviceRequests.get(), "the device list is asked once")
        // Measured only: the listing is what it was.
        assertEquals(setOf("/Docs", "/Docs/readme.txt"), page.items.map { it.path }.toSet())
        assertEquals(setOf("b0", "b1", "B1", "p1"), page.unreachableIds)
        assertTrue(page.complete)
    }

    // A device-list failure stays a clause of the census: one attempt, no outage wait, no drive throttle. The listing
    // is what it was.
    @Test
    fun `a device list that cannot be read does not stall the gather or record a throttle`() {
        for (status in listOf(0, 429)) {
            val drive = drive(deviceStatus = status)
            val provider = internxtProviderOn(drive.engine)
            val budget = driveBudgetOf(provider)

            val (page, lines, elapsed) = censusTimed { provider.delta(null, null, FakeStaging().context()) }

            val line = lines.single()
            val reported = if (status == 0) "HTTP 0" else "HTTP $status"
            assertTrue("the backup devices could not be listed ($reported)" in line, line)
            assertEquals(0L, elapsed, "status $status waited $elapsed ms")
            assertEquals(0L, budget.currentSpacingMs(), "status $status recorded a throttle")
            assertEquals(0, budget.ioRetryCount(), "status $status counted an I/O retry")
            assertEquals(1, drive.deviceRequests.get(), "status $status was asked more than once")
            assertTrue(page.complete, "status $status failed the gather")
            assertEquals(setOf("/Docs", "/Docs/readme.txt"), page.items.map { it.path }.toSet())
            assertEquals(setOf("b0", "b1", "B1", "p1"), page.unreachableIds)
            for (leak in listOf("Laptop", "Camera", "Connection refused", "report", "holiday")) {
                assertTrue(leak !in line, "no name, path, or exception text in the line: $line")
            }
        }
    }

    @Test
    fun `an account without backups is said so and the gather is unchanged`() {
        val drive = drive(devices = null)

        val (page, lines) = censusOf { internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context()) }

        assertTrue(lines.single().endsWith("; backups are not enabled for the account"), lines.single())
        assertTrue(page.complete, "the 400 on the device list is not a failure of the gather")
        assertEquals(setOf("b0", "b1", "B1", "p1"), page.unreachableIds)
    }

    @Test
    fun `a drive without parentless tops says so and asks for no device list`() {
        val drive =
            FakeDrive(
                listOf(FakeFolder("a1", "Docs", "root", fakeAt("19:30:00"))),
                listOf(FakeFile("f1", "readme", "a1", fakeAt("19:10:00"))),
                pageSize = 100,
                devices = listOf(),
            )

        val (_, lines) = censusOf { internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context()) }

        assertEquals(listOf("Internxt full gather: no item hangs below a folder without a parent other than the drive root"), lines)
        assertEquals(0, drive.deviceRequests.get())
    }

    @Test
    fun `an incremental delta logs no census and asks for no device list`() {
        val drive = drive()

        val (_, lines) = censusOf { internxtProviderOn(drive.engine).delta("2026-07-28T18:00:00.000Z", null, FakeStaging().context()) }

        assertEquals(emptyList(), lines)
        assertEquals(0, drive.deviceRequests.get())
    }
}
