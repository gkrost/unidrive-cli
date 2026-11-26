package org.krost.unidrive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StagedRowsTest {
    private fun row(i: Int) = CloudItem("id%06d".format(i), "n$i", "/n$i", 1, false, null, null, null, null)

    /** The staging as a test double: [total] rows in id order, every read recorded. */
    private class Staging(
        val total: Int,
        val row: (Int) -> CloudItem,
    ) {
        val reads = mutableListOf<Int>()
        var failOnRead: Int? = null

        fun load(
            after: String?,
            limit: Int,
        ): List<CloudItem> {
            check(failOnRead != reads.size + 1) { "read ${reads.size + 1} failed" }
            val from = if (after == null) 0 else after.removePrefix("id").toInt() + 1
            val page = (from until minOf(total, from + limit)).map(row)
            reads += page.size
            return page
        }
    }

    @Test
    fun `resuming 50k staged rows never holds more than one chunk of items at a time`() {
        val staging = Staging(50_000, ::row)
        val chunk = 1000
        val rows = stagedRowsOf(chunk, staging::load)
        assertEquals(0, staging.reads.size, "nothing is read until the rows are asked for")

        var seen = 0
        for (item in rows) {
            assertEquals(seen / chunk + 1, staging.reads.size, "at row $seen the reader has read exactly the chunks it needed")
            assertEquals(row(seen).id, item.id)
            seen++
        }

        assertEquals(50_000, seen)
        assertTrue(staging.reads.all { it <= chunk }, "no read returned more than $chunk rows")
        assertEquals(50, staging.reads.count { it == chunk })
    }

    @Test
    fun `a resumed listing yields exactly the staged rows, also when the last chunk is full`() {
        for (total in listOf(0, 1, 9, 10, 11, 20, 95)) {
            val staging = Staging(total, ::row)
            assertEquals((0 until total).map(::row), stagedRowsOf(10, staging::load).toList(), "$total rows")
        }
    }

    @Test
    fun `a reader that stops early or fails leaves no read open and reads no further`() {
        val early = Staging(100, ::row)
        assertEquals(3, stagedRowsOf(10, early::load).take(3).toList().size)
        assertEquals(listOf(10), early.reads, "three rows cost one chunk")

        val failing = Staging(100, ::row).also { it.failOnRead = 3 }
        assertFailsWith<IllegalStateException> { stagedRowsOf(10, failing::load).forEach { } }
        assertEquals(listOf(10, 10), failing.reads, "the failed read is the last one; every read before it returned in full")
    }

    @Test
    fun `the rows can be iterated more than once and each pass reads the staging anew`() {
        val staging = Staging(25, ::row)
        val rows = stagedRowsOf(10, staging::load)

        assertEquals(rows.toList(), rows.toList())
        assertEquals(listOf(10, 10, 5, 10, 10, 5), staging.reads)
    }

    @Test
    fun `a chunk of zero or less is refused`() {
        for (chunk in listOf(0, -1)) assertFailsWith<IllegalArgumentException> { stagedRowsOf(chunk) { _, _ -> emptyList() } }
    }

    @Test
    fun `a load that does not advance past the last row fails instead of looping forever`() {
        val stuck = stagedRowsOf(2) { _, _ -> listOf(row(0), row(1)) }
        assertFailsWith<IllegalStateException> { stuck.forEach { } }
    }

    @Test
    fun `a load that goes back to an earlier id fails instead of cycling`() {
        val pages = listOf(listOf(row(1), row(2)), listOf(row(3), row(0)), listOf(row(1), row(2)))
        var n = 0
        val cycling = stagedRowsOf(2) { _, _ -> pages[n++ % pages.size] }
        assertFailsWith<IllegalStateException> { cycling.forEach { } }
    }

    @Test
    fun `ids are compared in the staging's bytewise order, not UTF-16 order`() {
        // U+FF5E sorts below U+1F600 bytewise (EF.. < F0..) but above it as UTF-16 (FF5E > D83D).
        val low = CloudItem("\uFF5E", "a", "/a", 1, false, null, null, null, null)
        val high = CloudItem("\uD83D\uDE00", "b", "/b", 1, false, null, null, null, null)
        val pages = listOf(listOf(low), listOf(high), emptyList())
        var n = 0
        assertEquals(listOf(low, high), stagedRowsOf(1) { _, _ -> pages[n++] }.toList())
    }
}
