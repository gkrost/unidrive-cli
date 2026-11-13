package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.internxt.model.SyncPage
import kotlin.test.Test
import kotlin.test.assertEquals

/** A server that mints a fresh cursor for every page cannot be caught by the asked-cursor guard; these guards end it. */
class CursorStreamGuardTest {
    @Test
    fun `the same page again under a fresh cursor ends the stream short, its position not advanced`() =
        runTest {
            var n = 0
            val positions = mutableListOf<StreamPosition>()

            val end =
                pageCursorStream(
                    stream = "files",
                    start = StreamPosition(),
                    fetch = { SyncPage(listOf("a", "b"), "cursor-${n++}") },
                    onPage = { _, position -> positions += position },
                )

            assertEquals(StreamEnd.CUT_SHORT, end)
            assertEquals(2, n, "the repeat is seen on the second page")
            assertEquals(listOf(StreamPosition(cursor = "cursor-0")), positions)
        }

    @Test
    fun `a stream that never ends is cut at the page cap`() =
        runTest {
            var n = 0

            val end =
                pageCursorStream(
                    stream = "files",
                    start = StreamPosition(),
                    fetch = { SyncPage(listOf("item-$n"), "cursor-${n++}") },
                    onPage = { _, _ -> },
                    maxPages = 25,
                )

            assertEquals(StreamEnd.CUT_SHORT, end)
            assertEquals(25, n)
        }

    @Test
    fun `distinct pages to the last one complete the stream`() =
        runTest {
            var n = 0
            val seen = mutableListOf<String>()

            val end =
                pageCursorStream(
                    stream = "files",
                    start = StreamPosition(),
                    fetch = { SyncPage(listOf("item-$n"), if (n++ < 4) "cursor-$n" else null) },
                    onPage = { items, _ -> seen += items },
                    maxPages = 5,
                )

            assertEquals(StreamEnd.COMPLETE, end)
            assertEquals((0..4).map { "item-$it" }, seen)
        }
}
