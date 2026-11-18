package org.krost.unidrive.internxt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#747 finding 9: the byte progress of a multipart upload is the sum of every completed part and the
 * bytes in flight of every running part, reported in plaintext bytes. It never goes backwards (parts in flight report in any
 * order, a part retry or a fresh session restarts its count), parts a resume found complete count from the start, and the
 * total is reached only by the final report after the last part.
 */
class InternxtMultipartProgressTest {
    private val reports = mutableListOf<Long>()
    private val progress = MultipartProgress(fileSize = 1_000L, encryptedSize = 1_000L) { sent, _ -> reports += sent }

    private fun assertNonDecreasing() =
        assertTrue(reports.zipWithNext().all { (a, b) -> b >= a }, "progress went backwards: $reports")

    @Test
    fun `progress never goes backwards while parts stream concurrently`() {
        progress.startSession(emptyMap())
        progress.partSent(3, 200) // the last part streams first
        progress.partSent(0, 50)
        progress.partSent(1, 100)
        progress.partDone(3, 250)
        progress.partSent(0, 250)

        assertNonDecreasing()
        assertEquals(250L + 250L + 100L, reports.last(), "completed plus in-flight bytes: $reports")
    }

    @Test
    fun `parts a resume found complete count toward progress`() {
        progress.startSession(mapOf(1 to 500L))
        progress.partSent(0, 10)

        assertEquals(510L, reports.first(), "the completed half counts from the first report: $reports")
    }

    @Test
    fun `a part retry or a fresh session does not move progress backwards`() {
        progress.startSession(emptyMap())
        progress.partSent(0, 400)
        progress.partSent(0, 0) // the part's retry starts again at 0
        progress.startSession(emptyMap()) // a dead-URL restart re-puts every part
        progress.partSent(0, 100)
        progress.partSent(0, 450)

        assertNonDecreasing()
        assertEquals(450L, reports.last())
    }

    @Test
    fun `progress stays below the total until the final report`() {
        progress.startSession(emptyMap())
        progress.partDone(0, 500)
        progress.partDone(1, 500)

        assertTrue(reports.all { it < 1_000L }, "the total belongs to the final report: $reports")
    }
}
