package org.krost.unidrive.engine

import org.krost.unidrive.sync.StateDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** QuotaState stores and parses the cached quota tuple the way the string-keyed calls did (gkrost/unidrive-cli#269). */
class QuotaStateTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private lateinit var state: QuotaState

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-quota-state")
        db = StateDatabase(tmp.resolve("state.db"))
        db.initialize()
        state = QuotaState(db)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `every key is absent on a new database`() {
        assertNull(state.used)
        assertNull(state.total)
        assertNull(state.remaining)
        assertNull(state.fetchedAtText)
        assertNull(state.fetchedAt)
    }

    @Test
    fun `cache writes the tuple under the stored names and reads it back`() {
        val at = Instant.parse("2026-11-08T10:15:30Z")
        state.cache(used = 100, total = 200, remaining = 50, fetchedAt = at)

        assertEquals("100", db.getSyncState("quota_used"))
        assertEquals("200", db.getSyncState("quota_total"))
        assertEquals("50", db.getSyncState("quota_remaining"))
        assertEquals("2026-11-08T10:15:30Z", db.getSyncState("quota_fetched_at"))

        assertEquals(100, state.used)
        assertEquals(200, state.total)
        assertEquals(50, state.remaining)
        assertEquals(at, state.fetchedAt)
    }

    @Test
    fun `a stored value that is not a number reads as absent`() {
        db.setSyncState("quota_used", "12abc")
        db.setSyncState("quota_total", "")
        assertNull(state.used)
        assertNull(state.total)
    }

    @Test
    fun `an unparsable stamp reads as absent typed but keeps its text`() {
        // Doctor distinguishes "never cached" (no text) from "cached, stamp unparsable" (text, null instant).
        db.setSyncState("quota_fetched_at", "not-a-date")
        assertNull(state.fetchedAt)
        assertEquals("not-a-date", state.fetchedAtText)
    }

    @Test
    fun `cacheValues is the idle-batch map in persist order, remaining only when known`() {
        assertEquals(
            linkedMapOf(
                "quota_used" to "100",
                "quota_total" to "200",
                "quota_fetched_at" to "1970-01-01T00:00:01Z",
                "quota_remaining" to "50",
            ),
            QuotaState.cacheValues(usedBytes = 100, totalBytes = 200, remainingBytes = 50, fetchedAtMs = 1_000),
        )
        assertEquals(
            linkedMapOf(
                "quota_used" to "100",
                "quota_total" to "200",
                "quota_fetched_at" to "1970-01-01T00:00:01Z",
            ),
            QuotaState.cacheValues(usedBytes = 100, totalBytes = 200, remainingBytes = null, fetchedAtMs = 1_000),
        )
    }

    @Test
    fun `cacheValues persists nothing when used, total or the stamp is absent`() {
        assertNull(QuotaState.cacheValues(usedBytes = null, totalBytes = 200, remainingBytes = 50, fetchedAtMs = 1_000))
        assertNull(QuotaState.cacheValues(usedBytes = 100, totalBytes = null, remainingBytes = 50, fetchedAtMs = 1_000))
        assertNull(QuotaState.cacheValues(usedBytes = 100, totalBytes = 200, remainingBytes = 50, fetchedAtMs = null))
    }
}
