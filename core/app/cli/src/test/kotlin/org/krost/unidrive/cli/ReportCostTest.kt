package org.krost.unidrive.cli

import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.StateDatabaseCallTap
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #845 (#749 finding 8, PR #844): the read-only reports pin their cost, not only their results. Each report is run and the
 * calls it makes into its [StateDatabase] are listed ([StateDatabaseCallTap]): none is the whole-table read
 * (`getAllEntries` builds one object per row, the cost #749-8 removed). If one of these tests is removed or loosened, a report
 * can go back to loading the whole table and every result-only test still passes.
 */
class ReportCostTest {
    private lateinit var configDir: Path
    private lateinit var stateDb: Path
    private val originalOut = System.out
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val t0 = Instant.parse("2026-03-28T12:00:00Z")

    @BeforeTest
    fun setUp() {
        configDir = TestScratch.directory("ud-report-cost-cfg")
        val syncRoot = TestScratch.directory("ud-report-cost-local")
        val remoteRoot = TestScratch.directory("ud-report-cost-remote")
        Files.writeString(
            configDir.resolve("config.toml"),
            """
            |[providers.localtest]
            |type = "localfs"
            |sync_root = "${syncRoot.toString().replace('\\', '/')}"
            |root_path = "${remoteRoot.toString().replace('\\', '/')}"
            """.trimMargin(),
        )
        stateDb = configDir.resolve("localtest").resolve("state.db")
        Files.createDirectories(stateDb.parent)
        val db = StateDatabase(stateDb)
        db.initialize()
        try {
            db.batch { for (i in 0 until 50) db.upsertEntry(row("/f$i", hydrated = i % 2 == 0)) }
        } finally {
            db.close()
        }
        System.setOut(PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8))
        StateDatabaseCallTap.observer = { calls += it }
    }

    @AfterTest
    fun tearDown() {
        StateDatabaseCallTap.observer = null
        System.setOut(originalOut)
    }

    private fun row(
        path: String,
        hydrated: Boolean,
    ) = SyncEntry(
        path = path,
        remoteId = "id$path",
        remoteHash = null,
        remoteSize = 1,
        remoteModified = t0,
        localMtime = null,
        localSize = null,
        isFolder = false,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = t0.plusSeconds(path.removePrefix("/f").toLong()),
    )

    private fun run(vararg args: String) {
        val exit = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "localtest", *args)
        assertEquals(0, exit, "${args.toList()} must succeed for its cost to mean anything")
    }

    private fun assertNoWholeTableRead() = assertTrue("getAllEntries" !in calls, "whole-table read among $calls")

    @Test
    fun `status --pending streams the rows and never reads the whole table`() {
        run("status", "--pending")
        assertNoWholeTableRead()
        assertTrue("scanAliveRows" in calls, "streams through scanAliveRows: $calls")
    }

    @Test
    fun `status --audit asks one aggregate query for the file totals and never reads the whole table`() {
        run("status", "--audit")
        assertNoWholeTableRead()
        assertEquals(1, calls.count { it == "fileTotals" }, "one aggregate query: $calls")
        assertTrue("scanAliveRows" !in calls, "no scan of the table: $calls")
    }

    @Test
    fun `log reads the table in steps that keep the rows shown and never reads the whole table`() {
        run("log")
        assertNoWholeTableRead()
        assertTrue("scanAliveRows" in calls, "one pass of scanAliveRows: $calls")
    }

    @Test
    fun `doctor hydration drift reads its sample through one limited query and never reads the whole table`() {
        val db = StateDatabase(stateDb, readOnly = true)
        db.initialize()
        try {
            calls.clear()
            DoctorCommand().checkHydrationDrift(db, configDir.resolve("root"), full = false)
            assertEquals(listOf("hydratedFiles"), calls.toList(), "one limited query and nothing else")
            calls.clear()
            DoctorCommand().checkHydrationDrift(db, configDir.resolve("root"), full = true)
            assertEquals(listOf("hydratedFiles"), calls.toList(), "under full too: one query, never getAllEntries")
        } finally {
            db.close()
        }
    }
}
