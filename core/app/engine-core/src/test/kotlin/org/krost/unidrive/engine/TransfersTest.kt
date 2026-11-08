package org.krost.unidrive.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.DownloadReceipt
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The bookkeeping of a download's end: a cancellation is not a failed attempt (the tally would inflate
 * the retry history of a file the caller gave up on, not one the provider refused), while a download the
 * provider failed still counts as one.
 */
class TransfersTest {
    @Test
    fun `a cancelled download propagates as cancellation and is not recorded as a failed one`() =
        runTest {
            val tally = FileEventTally()

            val thrown =
                assertFailsWith<CancellationException> {
                    Transfers.downloadByIdOrPathWithHash(
                        DownloadThrowing { throw CancellationException("the caller stopped") },
                        item(),
                        remotePath = "/d/file.bin",
                        destination = destination(),
                        tally = tally,
                    )
                }

            assertEquals("the caller stopped", thrown.message)
            assertEquals(0, tally.attemptsFailed("/d/file.bin"), "a cancelled download is not a failed attempt")
            val summary = tally.summaryOrNull("cancelled")
            assertTrue(summary?.contains("download_failed_count") != true, "no download_failed in the summary: $summary")
        }

    @Test
    fun `a download the provider fails is still recorded as a failed one`() =
        runTest {
            val tally = FileEventTally()

            assertFailsWith<ProviderException> {
                Transfers.downloadByIdOrPathWithHash(
                    DownloadThrowing { throw ProviderException("no such object") },
                    item(),
                    remotePath = "/d/file.bin",
                    destination = destination(),
                    tally = tally,
                )
            }

            assertEquals(1, tally.attemptsFailed("/d/file.bin"), "a failed download counts against the retry history")
            val summary = tally.summaryOrNull("failed")
            assertTrue(summary?.contains("download_failed_count=1") == true, "the failure is in the summary: $summary")
        }

    private fun item() =
        CloudItem(
            id = "id-1",
            name = "file.bin",
            path = "/d/file.bin",
            size = 10,
            isFolder = false,
            modified = null,
            created = null,
            hash = null,
            mimeType = null,
        )

    private fun destination(): Path = Files.createTempDirectory("transfers-test").resolve("file.bin")
}

/** A provider whose download ends the way [thrown] says; everything else is out of scope for these tests. */
private class DownloadThrowing(
    private val thrown: () -> Nothing,
) : CloudProvider {
    override val id = "throwing"
    override val displayName = "throwing"
    override var isAuthenticated = true

    override fun capabilities(): Set<Capability> = emptySet()

    override suspend fun authenticate() {}

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage = error("not used")

    override suspend fun listChildren(path: String): List<CloudItem> = error("not used")

    override suspend fun getMetadata(path: String): CloudItem = error("not used")

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long = error("not used")

    override suspend fun downloadByIdWithHash(
        remoteId: String,
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): DownloadReceipt = thrown()

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem = error("not used")

    override suspend fun delete(
        remotePath: String,
        ifMatchETag: String?,
    ) = error("not used")

    override suspend fun createFolder(path: String): CloudItem = error("not used")

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem = error("not used")

    override suspend fun quota(): QuotaInfo = error("not used")
}
