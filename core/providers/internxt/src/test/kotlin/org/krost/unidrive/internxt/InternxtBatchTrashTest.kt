package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.DeleteRequest
import org.krost.unidrive.ProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#831: the batched trash. The listed items go up to 50 per call; each item is still answered as its own
 * delete would answer it: a refused call's items fall back one by one, a failed call fails only its own items, a refusal
 * or an unlisted item does not hold the others back.
 */
class InternxtBatchTrashTest {
    private class Drive(
        val listed: Set<String>,
        val refused: Set<String> = emptySet(),
        val badInBatch: Set<String> = emptySet(),
        val failCall: Int? = null,
    ) {
        val calls = mutableListOf<List<Pair<String, String>>>()
        val singles = mutableListOf<String>()
        val forgotten = mutableListOf<String>()

        suspend fun run(items: List<DeleteRequest>): List<Throwable?> =
            trashInBatches(
                items,
                listedKind = { item ->
                    if (item.remoteId in refused) throw ProviderException("refused: ${item.remotePath}")
                    if (item.remoteId in listed) (if (item.isFolder) "folder" else "file") else null
                },
                trash = { call ->
                    calls += call
                    if (calls.size == failCall) throw InternxtApiException("Server error", 503)
                    if (call.any { it.first in badInBatch }) throw InternxtApiException("Bad Request", 400)
                },
                forget = { forgotten += it },
                single = { item ->
                    singles += item.remoteId
                    if (item.remoteId in badInBatch) throw ProviderException("Item not found: ${item.remotePath}")
                },
            )
    }

    private fun files(n: Int, prefix: String = "f") = (1..n).map { DeleteRequest("$prefix$it", "/D/$prefix$it.txt", isFolder = false) }

    @Test
    fun `120 listed files are three trash calls of 50, 50 and 20, and no single delete`() = runTest {
        val items = files(120)
        val drive = Drive(listed = items.map { it.remoteId }.toSet())

        val answers = drive.run(items)

        assertEquals(listOf(50, 50, 20), drive.calls.map { it.size })
        assertEquals(items.map { it.remoteId to "file" }, drive.calls.flatten(), "every item once, in order, with its kind")
        assertTrue(drive.singles.isEmpty())
        assertTrue(answers.all { it == null })
        assertEquals(items.map { it.remoteId }, drive.forgotten)
    }

    @Test
    fun `a 400 for one bad uuid sends that call's items one by one and the other calls stay batched`() = runTest {
        val items = files(60)
        val drive = Drive(listed = items.map { it.remoteId }.toSet(), badInBatch = setOf("f7"))

        val answers = drive.run(items)

        assertEquals(listOf(50, 10), drive.calls.map { it.size })
        assertEquals((1..50).map { "f$it" }, drive.singles, "the refused call's items, each on its own")
        assertTrue(answers[6] is ProviderException, "the bad uuid gets its own single answer")
        assertTrue(answers.filterIndexed { i, _ -> i != 6 }.all { it == null }, "every other item is deleted")
    }

    @Test
    fun `a call that fails after its retries fails its own items only`() = runTest {
        val items = files(70)
        val drive = Drive(listed = items.map { it.remoteId }.toSet(), failCall = 1)

        val answers = drive.run(items)

        assertTrue(answers.take(50).all { (it as? InternxtApiException)?.statusCode == 503 })
        assertTrue(answers.drop(50).all { it == null })
        assertEquals((51..70).map { "f$it" }, drive.forgotten, "only the trashed items leave the listed-parents cache")
        assertTrue(drive.singles.isEmpty(), "a 5xx is no reason to try each item again")
    }

    @Test
    fun `a refused item and an unlisted item are answered on their own and do not hold the batch back`() = runTest {
        val items = files(5) + DeleteRequest("dir1", "/D/sub", isFolder = true)
        val drive = Drive(listed = setOf("f1", "f2", "f4", "dir1"), refused = setOf("f4"))

        val answers = drive.run(items)

        assertEquals(listOf(listOf("f1" to "file", "f2" to "file", "dir1" to "folder")), drive.calls)
        assertEquals(listOf("f3", "f5"), drive.singles, "the unlisted items take the meta path")
        assertTrue(answers[3] is ProviderException)
        assertNull(answers[0])
        assertNull(answers[2])
    }

    @Test
    fun `an id that comes twice is batched once and its repeat is answered by a single delete`() = runTest {
        val items = files(2) + DeleteRequest("f1", "/D/f1.txt", isFolder = false)
        val drive = Drive(listed = setOf("f1", "f2"))

        drive.run(items)

        assertEquals(listOf(listOf("f1" to "file", "f2" to "file")), drive.calls)
        assertEquals(listOf("f1"), drive.singles)
    }

    @Test
    fun `a refusal keeps its own exception`() = runTest {
        val items = files(1)
        val drive = Drive(listed = emptySet(), refused = setOf("f1"))
        val answers = drive.run(items)
        val refusal = answers.single()
        assertTrue(refusal is ProviderException)
        assertSame(refusal, answers[0])
        assertTrue(drive.calls.isEmpty() && drive.singles.isEmpty())
    }
}
