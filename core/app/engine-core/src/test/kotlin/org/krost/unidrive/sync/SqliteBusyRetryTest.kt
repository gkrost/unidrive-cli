package org.krost.unidrive.sync

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The contract of [retryOnSqliteBusy]: a busy failure is retried a bounded number of times with the backoff
 * between attempts, a non-busy failure (a refused schema, a constraint) propagates at once, and a run that never
 * lands reports its first failure with every later one attached.
 */
class SqliteBusyRetryTest {
    private class Busy : SQLException("[SQLITE_BUSY] The database file is locked (database is locked)")

    private class NotBusy : SQLException("[SQLITE_CONSTRAINT] a refusal the retry must not loop on")

    @Test
    fun `a busy failure is retried until the action succeeds`() {
        var attempts = 0
        val backoffs = mutableListOf<Long>()

        val result =
            retryOnSqliteBusy(sleeper = { backoffs += it }) {
                attempts += 1
                if (attempts < 3) throw Busy()
                "landed"
            }

        assertEquals("landed", result)
        assertEquals(3, attempts)
        assertEquals(List(2) { SQLITE_BUSY_BACKOFF_MS }, backoffs)
    }

    @Test
    fun `a non-busy failure propagates at once`() {
        var attempts = 0
        val backoffs = mutableListOf<Long>()

        assertFailsWith<NotBusy> {
            retryOnSqliteBusy(sleeper = { backoffs += it }) {
                attempts += 1
                throw NotBusy()
            }
        }

        assertEquals(1, attempts, "a real error must not be retried")
        assertTrue(backoffs.isEmpty(), "a real error must not wait out a backoff")
    }

    @Test
    fun `a non-busy failure after busy ones propagates with the busy ones attached`() {
        var attempts = 0

        val failure =
            assertFailsWith<NotBusy> {
                retryOnSqliteBusy(sleeper = {}) {
                    attempts += 1
                    if (attempts < 3) throw Busy() else throw NotBusy()
                }
            }

        assertEquals(3, attempts)
        val first = failure.suppressed.single()
        assertTrue(first is Busy, "the first busy failure must be attached to the real error")
        assertEquals(1, first.suppressed.size, "the later busy failure must stay attached to the first")
    }

    @Test
    fun `a busy failure that never clears fails on the last attempt with the earlier ones attached`() {
        var attempts = 0
        val backoffs = mutableListOf<Long>()

        val failure =
            assertFailsWith<Busy> {
                retryOnSqliteBusy(sleeper = { backoffs += it }) {
                    attempts += 1
                    throw Busy()
                }
            }

        assertEquals(SQLITE_BUSY_ATTEMPTS, attempts)
        assertEquals(SQLITE_BUSY_ATTEMPTS - 1, backoffs.size)
        assertEquals(SQLITE_BUSY_ATTEMPTS - 1, failure.suppressed.size, "every earlier attempt must be reported")
    }
}
