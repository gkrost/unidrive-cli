package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// gkrost/unidrive-cli#438: one lock per path, kept in memory only while somebody holds or waits for it.
class PathLocksTest {
    @Test
    fun `one path is held by one caller at a time, and its entry goes with the last of them`() =
        runTest {
            val locks = PathLocks()
            val release = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val first = launch { locks.withLock("/a") { order += "first in"; release.await(); order += "first out" } }
            runCurrent()
            val second = launch { locks.withLock("/a") { order += "second" } }
            val other = launch { locks.withLock("/b") { order += "other" } }
            runCurrent()

            assertEquals(listOf("first in", "other"), order, "another path does not wait; the same path does")
            assertEquals(1, locks.size, "the held path stays while one holds it and one waits")

            release.complete(Unit)
            first.join()
            second.join()
            other.join()
            assertEquals(listOf("first in", "other", "first out", "second"), order)
            assertEquals(0, locks.size, "nothing is kept once the path is free")
        }

    @Test
    fun `a block that throws releases the path`() =
        runTest {
            val locks = PathLocks()
            assertFailsWith<IllegalStateException> { locks.withLock("/a") { error("boom") } }
            assertEquals(0, locks.size)
            assertEquals(7, locks.withLock("/a") { 7 }, "the path can be taken again")
        }
}
