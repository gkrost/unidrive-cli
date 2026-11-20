package org.krost.unidrive.engine

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.aliveNameBlockRowsAfter
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #845 (#744 finding 6): the strict-name sweep reads the table in steps and commits once per step with a change. Three named
 * invariants: the verdicts are the sweep's old ones, the commits are per step and not per row, and no step holds more rows than
 * its size. If one of these tests is removed or loosened, the matching regression (a commit per changed row, the whole table
 * loaded at once, a changed verdict at a step border) goes unseen.
 */
class StrictNameBlocksChunkedTest {
    private lateinit var db: StateDatabase
    private val issues = mutableMapOf<String, String>()
    private var mode: Boolean? = true
    private val provider: CloudProvider =
        object : CloudProvider by FakeViewProvider() {
            override val strictNameMode: Boolean? get() = mode

            override fun existingNameIssue(path: String): String? = issues[path]
        }

    private val refused = "name_incompatible: windows_character"

    @BeforeTest
    fun setUp() {
        db = StateDatabase(TestScratch.directory("unidrive-strict-chunked").resolve("state.db")).also { it.initialize() }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun row(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = null,
            remoteSize = 1,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = null,
            localSize = null,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.now(),
        )

    private fun fill(count: Int) {
        db.batch { for (i in 1..count) db.upsertEntry(row("/f%03d.txt".format(i))) }
    }

    @Test
    fun `sweep_verdicts_are_the_same_at_every_step_size`() {
        fill(25)
        // a mix: refused rows to mark, a row of its own kind to clear, one to re-mark, one block of another kind to leave
        for (i in listOf(1, 7, 8, 14, 25)) issues["/f%03d.txt".format(i)] = refused
        db.markNameBlocked("/f002.txt", refused, null) // mode no longer refuses it: cleared
        db.markNameBlocked("/f014.txt", "name_incompatible: other", null) // refused for another reason: re-marked
        db.markNameBlocked("/f003.txt", "similar_name", "F003.txt") // another kind: untouched

        assertEquals(6, StrictNameBlocks.reconcileAll(db, provider, chunkRows = 4))

        for (i in listOf(1, 7, 8, 14, 25)) assertEquals(refused, db.nameBlock("/f%03d.txt".format(i))?.reason, "row $i")
        assertNull(db.nameBlock("/f002.txt"))
        assertEquals("similar_name", db.nameBlock("/f003.txt")?.reason)
        assertEquals(0, StrictNameBlocks.reconcileAll(db, provider, chunkRows = 4), "a second run changes nothing")
        assertEquals(0, StrictNameBlocks.reconcileAll(db, provider), "nor does one with the default step")
    }

    @Test
    fun `sweep_commits_once_per_step_with_a_change_not_once_per_row`() {
        fill(40)
        for (i in 1..40) issues["/f%03d.txt".format(i)] = refused // 40 changed rows, steps of 10: 4 commits
        var commits = 0
        db.batchCommitHook = { commits++ }
        try {
            assertEquals(40, StrictNameBlocks.reconcileAll(db, provider, chunkRows = 10))
        } finally {
            db.batchCommitHook = null
        }
        assertEquals(4, commits, "one transaction per step of 10 rows, not 40 single-row commits")
    }

    @Test
    fun `sweep_reads_no_more_rows_than_one_step_at_a_time`() {
        fill(25)
        var after = Long.MIN_VALUE
        val sizes = mutableListOf<Int>()
        while (true) {
            val rows = db.aliveNameBlockRowsAfter(after, 10)
            if (rows.isEmpty()) break
            sizes += rows.size
            after = rows.last().rowid
        }
        assertEquals(listOf(10, 10, 5), sizes, "every step holds at most its limit, and the steps cover the table once")
        assertTrue(sizes.all { it <= 10 })
    }
}
