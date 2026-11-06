package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `hydration.stat` (gkrost/unidrive-cli#415): one row's state without listing its folder — the fields of a
 * `hydration.list` entry, answered from the row store. A path with no row is `not_found` and the caller
 * falls back to a listing; a folder row answers like any row.
 */
class HydrationStatTest {
    private val env = HydrationTestEnv()

    @Test
    fun `a stored row answers with its list-entry fields`() = runTest {
        env.stateDb.insertUnhydratedEntry("/docs/report.txt", remoteSize = 42, remoteId = "id-report")

        val r = env.hydration.stat("/docs/report.txt")

        val ok = assertIs<StatResult.Ok>(r)
        assertEquals("/docs/report.txt", ok.entry.path)
        assertEquals(42L, ok.entry.size)
        assertEquals(false, ok.entry.isFolder)
        assertEquals("id-report", ok.entry.remoteId)
    }

    @Test
    fun `a folder row answers like any row`() = runTest {
        env.stateDb.insertUnhydratedEntry("/docs", remoteSize = 0, remoteId = "id-docs", isFolder = true)

        val r = env.hydration.stat("/docs")

        val ok = assertIs<StatResult.Ok>(r)
        assertTrue(ok.entry.isFolder)
        assertEquals("id-docs", ok.entry.remoteId)
    }

    @Test
    fun `a path with no row is refused as not_found`() = runTest {
        val r = env.hydration.stat("/missing.txt")

        val failed = assertIs<StatResult.Failed>(r)
        assertEquals(HydrationError.NotFound, failed.error)
    }

    @Test
    fun `a trailing slash names the same row`() = runTest {
        env.stateDb.insertUnhydratedEntry("/docs", remoteSize = 0, remoteId = "id-docs", isFolder = true)

        val ok = assertIs<StatResult.Ok>(env.hydration.stat("/docs/"))

        assertEquals("/docs", ok.entry.path)
    }
}
