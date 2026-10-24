package org.krost.unidrive.internxt

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * archive#1203: on Windows the rename of the sidecar over an existing one fails with AccessDeniedException while another handle holds the
 * target; the write tries a few times before the caller sees it, and a write that finally fails leaves no temp file behind.
 */
class UploadTombstoneStoreWriteTest {
    private val dir: Path = Files.createTempDirectory("ud-tomb-write-")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun tombstone(
        stage: UploadTombstone.Stage = UploadTombstone.Stage.PUT_PENDING,
        bridgeFileId: String? = null,
    ) = UploadTombstone(
        localPath = "/x/a.bin",
        localMtimeMillis = 1L,
        localSize = 10L,
        bucket = "bucket",
        folderUuid = "folder",
        plainName = "a",
        ext = "bin",
        indexBytesHex = "aa".repeat(32),
        contentFingerprintHex = "ff",
        stage = stage,
        startedAtMillis = 1L,
        tombstoneWrittenAtMillis = 2L,
        bridgeFileId = bridgeFileId,
    )

    private fun realMove(
        tmp: Path,
        target: Path,
    ) {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    @Test
    fun `a rename that is refused twice is tried again and the write lands`() {
        var tries = 0
        val pauses = mutableListOf<Long>()
        val store =
            UploadTombstoneStore(
                dir,
                move = { tmp, target ->
                    if (++tries <= 2) throw AccessDeniedException(tmp.toString(), target.toString(), null)
                    realMove(tmp, target)
                },
                pause = { pauses += it },
            )

        store.write("h1", tombstone())

        assertEquals(3, tries)
        assertEquals(listOf(20L, 40L), pauses, "the pauses grow, the caller is not held for long")
        assertNotNull(store.read("h1"), "the sidecar is there")
        assertFalse(Files.exists(dir.resolve("h1.json.tmp")), "the temp file went with the successful rename")
    }

    @Test
    fun `a rename that never works throws after five tries and removes the temp file`() {
        var tries = 0
        val pauses = mutableListOf<Long>()
        val store =
            UploadTombstoneStore(
                dir,
                move = { tmp, target ->
                    tries++
                    throw AccessDeniedException(tmp.toString(), target.toString(), null)
                },
                pause = { pauses += it },
            )

        assertFailsWith<AccessDeniedException> { store.write("h2", tombstone()) }

        assertEquals(5, tries)
        assertEquals(listOf(20L, 40L, 60L, 80L), pauses)
        assertFalse(Files.exists(dir.resolve("h2.json.tmp")), "a failed write leaves no half-written temp file")
        assertNull(store.read("h2"), "and no sidecar")
    }

    @Test
    fun `a failure that is not a file system error is not retried`() {
        var tries = 0
        val store = UploadTombstoneStore(dir, move = { _, _ -> tries++; error("not ours") }, pause = {})

        assertFailsWith<IllegalStateException> { store.write("h3", tombstone()) }

        assertEquals(1, tries)
    }

    @Test
    fun `the file id of the finished commit survives the round trip and an older sidecar reads it as absent`() {
        val store = UploadTombstoneStore(dir)

        store.write("h4", tombstone(UploadTombstone.Stage.FINISH_DONE, bridgeFileId = "bridge-42"))
        assertEquals("bridge-42", store.read("h4")?.bridgeFileId)
        assertEquals(UploadTombstone.Stage.FINISH_DONE, store.read("h4")?.stage)

        // A sidecar written before the field existed.
        val old =
            Files.readString(dir.resolve("h4.json")).let { raw ->
                raw.replace(Regex(""",?\s*"bridgeFileId"\s*:\s*"bridge-42""""), "")
            }
        assertTrue("bridgeFileId" !in old, "the test removed the field")
        Files.writeString(dir.resolve("h5.json"), old)
        assertNull(store.read("h5")?.bridgeFileId)
        assertNotNull(store.read("h5"), "the older sidecar is still readable")
    }
}
