package org.krost.unidrive.s3

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/** Writes a record the way the pre-#441-14 store did: one `<sha256(key)[0,32]>.upload` properties file per upload. */
internal fun writeLegacyRecord(
    dir: Path,
    pending: MultipartStore.Pending,
    savedAt: Instant = Instant.now(),
): Path {
    Files.createDirectories(dir)
    val props =
        Properties().apply {
            setProperty("key", pending.key)
            setProperty("uploadId", pending.uploadId)
            setProperty("localPath", pending.localPath)
            setProperty("size", pending.size.toString())
            setProperty("mtimeMillis", pending.mtimeMillis.toString())
            setProperty("partSize", pending.partSize.toString())
            pending.proofSha256?.let { setProperty("proofSha256", it) }
        }
    val file = dir.resolve(SigV4Signer.sha256Hex(pending.key.toByteArray(Charsets.UTF_8)).take(32) + ".upload")
    Files.newOutputStream(file).use { props.store(it, null) }
    Files.setLastModifiedTime(file, FileTime.from(savedAt))
    return file
}

class MultipartStoreTest {
    private val dir = TestScratch.directory("s3-uploads")
    private val store = MultipartStore(dir)

    private fun pending(key: String) = MultipartStore.Pending(key, "id-$key", "/local/$key", 100, 1, 16, "proof")

    private fun legacyFiles() = Files.list(dir).use { it.toList() }.filter { it.fileName.toString().endsWith(".upload") }

    // One record per upload: <sha256(key)[0,32]>.json, a ResumeSessionStore file holding that one entry.
    private fun recordFile(key: String) = dir.resolve(SigV4Signer.sha256Hex(key.toByteArray(Charsets.UTF_8)).take(32) + ".json")

    @Test
    fun `an entry is found by its key among many others`() {
        repeat(50) { store.save(pending("k$it")) }

        assertEquals(pending("k17"), store.find("k17"))
        assertNull(store.find("missing"))
        assertEquals(50, store.all().size)
    }

    @Test
    fun `a removed entry is gone`() {
        store.save(pending("a"))
        store.remove("a")

        assertNull(store.find("a"))
        assertNull(MultipartStore(dir).find("a"), "also after a reopen")
    }

    @Test
    fun `a saved entry survives a reopen with its age`() {
        store.save(pending("a"))

        val reopened = MultipartStore(dir)
        assertEquals(pending("a"), reopened.find("a"))
        assertEquals(listOf(pending("a")), reopened.olderThan(Instant.now().plusSeconds(60)))
        assertTrue(reopened.olderThan(Instant.now().minusSeconds(3600)).isEmpty())
    }

    @Test
    fun old_format_multipart_record_resumes_after_migration() {
        val legacy = writeLegacyRecord(dir, pending("big.bin"), Instant.parse("2026-11-01T00:00:00Z"))

        val migrated = MultipartStore(dir)

        assertEquals(pending("big.bin"), migrated.find("big.bin"), "the open upload is found by its key")
        assertTrue(Files.exists(recordFile("big.bin")), "the record now lives in its own shared-store file")
        assertTrue(Files.notExists(legacy), "the old file goes once its record is converted")
        assertEquals(pending("big.bin"), MultipartStore(dir).find("big.bin"), "and it is still there after another restart")
        assertEquals(
            listOf(pending("big.bin")),
            MultipartStore(dir).olderThan(Instant.parse("2026-11-02T00:00:00Z")),
            "the record keeps the age of its old file, so the stale sweep still ends it on time",
        )
    }

    @Test
    fun transient_legacy_read_failure_retries_the_same_upload() {
        val record = pending("held")
        val legacy = writeLegacyRecord(dir, record)
        var fail = true
        val reopening = MultipartStore(dir) { file ->
            if (fail) {
                fail = false
                throw AccessDeniedException(file.toString())
            }
            Files.newInputStream(file)
        }

        assertFailsWith<AccessDeniedException> { reopening.find(record.key) }
        assertTrue(Files.exists(legacy))
        assertTrue(Files.notExists(recordFile(record.key)))
        assertEquals(record, reopening.find(record.key))
        assertTrue(Files.notExists(legacy))
    }

    @Test
    fun crash_mid_conversion_converts_again_without_losing_records() {
        val records = listOf("a", "b", "c").map(::pending)
        records.forEach { writeLegacyRecord(dir, it) }
        MultipartStore(dir).all() // a full conversion, to have the store file a crashed run would have written

        // Crash after the records were written, before any old file was removed: all three old files are still there.
        records.forEach { writeLegacyRecord(dir, it) }
        assertEquals(3, legacyFiles().size)
        assertEquals(records.toSet(), MultipartStore(dir).all().toSet())
        assertEquals(0, legacyFiles().size)

        // Crash half-way through removing the old files: one is left, and a temp file of the interrupted write.
        writeLegacyRecord(dir, pending("b"))
        Files.writeString(dir.resolve("${recordFile("b").fileName}.crashed.tmp"), "{ half")
        assertEquals(records.toSet(), MultipartStore(dir).all().toSet())
        assertEquals(0, legacyFiles().size)

        // Crash before a record was written: its old file is the only copy.
        Files.delete(recordFile("a"))
        writeLegacyRecord(dir, pending("a"))
        assertEquals(records.toSet(), MultipartStore(dir).all().toSet())

        // Crash that left a torn new record next to its old file: the torn one is moved aside, the old file converts again.
        Files.writeString(recordFile("c"), "{ torn")
        writeLegacyRecord(dir, pending("c"))
        assertEquals(records.toSet(), MultipartStore(dir).all().toSet())
        assertEquals(0, legacyFiles().size)
    }

    @Test
    fun corrupt_old_record_is_reported_and_skipped_not_fatal() {
        writeLegacyRecord(dir, pending("good"))
        val bad = dir.resolve("0123456789abcdef0123456789abcdef.upload")
        Files.writeString(bad, "key=x\nsize=not-a-number")

        val migrated = MultipartStore(dir)

        assertEquals(pending("good"), migrated.find("good"), "the readable record converts")
        assertEquals(listOf(pending("good")), migrated.all())
        val report = assertNotNull(migrated.migration, "the migration says what it did")
        assertEquals(1, report.converted)
        assertEquals(listOf("0123456789abcdef0123456789abcdef.upload"), report.unreadable)
        assertTrue(Files.notExists(bad), "the unreadable file is not parsed again at every start")
        assertTrue(
            Files.list(dir).use { it.toList() }.any { it.fileName.toString().startsWith("0123456789abcdef0123456789abcdef.upload.corrupt") },
            "its bytes stay for the post-mortem",
        )
        assertNull(MultipartStore(dir).migration, "nothing is left to convert the next time")
    }

    @Test
    fun resume_lookup_stays_constant_time() {
        repeat(300) { store.save(pending("k$it")) }
        // A lookup reads its own record and no other: with every other record damaged, and left untouched on disk
        // (not parsed, not moved aside), it still answers. An O(n) re-listing (the pre-#676 find) could not.
        val others = (0 until 300).filter { it != 17 }.map { recordFile("k$it") }
        others.forEach { Files.writeString(it, "{ torn") }

        val reopened = MultipartStore(dir)
        assertEquals(pending("k17"), reopened.find("k17"))
        assertNull(reopened.find("missing"))
        assertTrue(others.all { Files.readString(it) == "{ torn" }, "no other record was read or moved aside")
    }

    @Test
    fun one_torn_record_leaves_the_others_resumable() {
        listOf("a", "b", "c").forEach { store.save(pending(it)) }
        Files.writeString(recordFile("b"), "{ torn")

        val reopened = MultipartStore(dir)

        assertEquals(pending("a"), reopened.find("a"))
        assertEquals(pending("c"), reopened.find("c"))
        assertNull(reopened.find("b"), "the damaged record costs its own upload only")
        assertEquals(setOf(pending("a"), pending("c")), reopened.all().toSet())
        assertEquals(1, Files.list(dir).use { it.toList() }.count { it.fileName.toString().startsWith("${recordFile("b").fileName}.torn-") }, "its bytes are kept aside")
    }

    @Test
    fun migration_is_idempotent() {
        listOf("a", "b").forEach { writeLegacyRecord(dir, pending(it)) }
        MultipartStore(dir).all()
        val after = Files.readAllBytes(recordFile("a"))

        val again = MultipartStore(dir)
        assertEquals(setOf(pending("a"), pending("b")), again.all().toSet())
        assertNull(again.migration, "a converted profile has nothing to convert")
        assertTrue(after.contentEquals(Files.readAllBytes(recordFile("a"))), "and the records are not rewritten")

        // A leftover old file of an already converted record (a crash after the write) must not undo a newer save.
        MultipartStore(dir).save(pending("a").copy(uploadId = "newer"))
        writeLegacyRecord(dir, pending("a"), Instant.parse("2026-01-01T00:00:00Z"))
        assertEquals("newer", MultipartStore(dir).find("a")?.uploadId)
        assertEquals(0, legacyFiles().size)
    }
}
