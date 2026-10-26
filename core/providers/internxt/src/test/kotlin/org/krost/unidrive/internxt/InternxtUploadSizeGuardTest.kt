package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentUploadFailureException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Candidate P2 of the audit: the size a file is created with must be the size of the bytes that were sent. The upload reads
 * the size first, then encrypts the file as it is when it gets there (a stream cipher: the ciphertext is as long as the bytes
 * it read). A source that grows in between would be stored with the old size and the longer ciphertext, and every download
 * of it is refused as incomplete.
 */
class InternxtUploadSizeGuardTest {
    @Test
    fun `a source that grows between sizing and encrypting is not created with the old size`() =
        runTest {
            val tmp = Files.createTempDirectory("ud-size-guard-")
            try {
                val local = Files.createTempFile(tmp, "src-", ".bin").also { Files.write(it, ByteArray(16) { b -> b.toByte() }) }
                val drive = UploadDrive()
                val provider = rootedInternxtProvider(tmp, drive.engine)
                try {
                    var grown = false
                    // The progress call (0, size) comes after the size is read and before the file is encrypted.
                    val outcome =
                        runCatching {
                            provider.upload(local, "/growing.bin", existingRemoteId = null, onProgress = { sent, _ ->
                                if (!grown && sent == 0L) {
                                    grown = true
                                    Files.write(local, ByteArray(16) { 7 }, StandardOpenOption.APPEND)
                                }
                            })
                        }
                    assertTrue(grown, "the test did not change the source")
                    assertEquals(32L, Files.size(local))
                    val declared = drive.createSizes.toList()
                    val sentLengths = drive.shardPutLengths.toList()
                    if (outcome.isSuccess) {
                        assertEquals(1, declared.size)
                        assertTrue(
                            sentLengths.all { it == declared.single() },
                            "created with size $declared but the shard PUT carried $sentLengths bytes: the stored size and the stored object differ",
                        )
                    } else {
                        // A refusal is fine, but it must be one that the next attempt can get past: the file is not at fault.
                        assertFalse(outcome.exceptionOrNull() is PermanentUploadFailureException, "refused for good: ${outcome.exceptionOrNull()}")
                        assertTrue(drive.files.isEmpty(), "a file was created although the upload failed: ${drive.files}")
                    }
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
}
