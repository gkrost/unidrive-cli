package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.BatchDeleteProvider
import org.krost.unidrive.DeleteRequest
import org.krost.unidrive.deleteManyOrEach
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#831: a burst of deletes by id of listed files is ⌈N/50⌉ `POST /storage/trash/add` calls, not N. The
 * fake drive counts the calls and the items each one names; the same drive answers the per-item loop (the before) for the
 * comparison the issue asks for.
 */
class InternxtDeleteManyTest {
    private fun drive(n: Int) =
        FakeDrive(
            listOf(FakeFolder("a1", "Docs", "root", fakeAt("19:30:00"))),
            (1..n).map { FakeFile("f$it", "file$it", "a1", fakeAt("19:10:00")) },
            pageSize = 500,
        )

    private fun requests(n: Int) = (1..n).map { DeleteRequest("f$it", "/Docs/file$it.txt", isFolder = false) }

    @Test
    fun `the provider takes batched deletes`() {
        val provider = internxtProviderOn(drive(0).engine)
        try {
            assertTrue(provider is BatchDeleteProvider)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `120 delta-listed files are three trash calls naming 50, 50 and 20 items, and no meta`() =
        runTest {
            val drive = drive(120)
            val provider = internxtProviderOn(drive.engine)
            try {
                provider.delta(null, null, FakeStaging().context())
                drive.requests.clear()

                val answers = provider.deleteManyOrEach(requests(120))

                assertTrue(answers.all { it == null }, "every item deleted: $answers")
                assertEquals(listOf(50, 50, 20), drive.trashCalls.map { it.size })
                assertEquals((1..120).map { "f$it" }, drive.trashCalls.flatten())
                assertEquals(List(3) { "POST /drive/storage/trash/add" }, drive.requests, "no meta, no climb: the delta proved the parents")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `measured - trash calls per 100 and per 2729 deletes, per-item loop against the batch`() =
        runTest {
            for (n in listOf(100, 2729)) {
                val before = trashCalls(n) { provider, items -> items.forEach { provider.deleteById(it.remoteId, it.remotePath, it.isFolder) } }
                val after = trashCalls(n) { provider, items -> provider.deleteManyOrEach(items) }
                println("#831 measured: $n deletes by id of listed files: per-item loop $before trash calls, deleteMany $after")
                assertEquals(n, before)
                assertEquals(ceil(n / 50.0).toInt(), after)
            }
        }

    private suspend fun trashCalls(
        n: Int,
        run: suspend (InternxtProvider, List<DeleteRequest>) -> Unit,
    ): Int {
        val drive = drive(n)
        val provider = internxtProviderOn(drive.engine)
        try {
            provider.delta(null, null, FakeStaging().context())
            drive.requests.clear()
            run(provider, requests(n))
            assertEquals(n, drive.trashCalls.sumOf { it.size }, "every item trashed once")
            assertEquals(drive.trashCalls.size, drive.requests.size, "trash calls only")
            return drive.trashCalls.size
        } finally {
            provider.close()
        }
    }
}
