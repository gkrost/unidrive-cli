package org.krost.unidrive.sync

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.krost.unidrive.*
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class CloudRelocatorTest {
    private lateinit var source: FakeProvider
    private lateinit var target: FakeProvider

    @BeforeTest
    fun setUp() {
        source = FakeProvider("source")
        target = FakeProvider("target")
    }

    // -- preFlightCheck ---------------------------------------------------------

    @Test
    fun `preFlightCheck returns zero for empty source`() =
        runTest {
            val relocator = CloudRelocator(source, target)
            val (size, count) = relocator.preFlightCheck("/")
            assertEquals(0L, size)
            assertEquals(0, count)
        }

    @Test
    fun `preFlightCheck sums file sizes and counts`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/a.txt", size = 100),
                    cloudItem("/b.txt", size = 200),
                )
            val relocator = CloudRelocator(source, target)
            val (size, count) = relocator.preFlightCheck("/")
            assertEquals(300L, size)
            assertEquals(2, count)
        }

    @Test
    fun `preFlightCheck recurses into folders`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/docs", isFolder = true),
                    cloudItem("/top.txt", size = 50),
                )
            source.children["/docs"] =
                listOf(
                    cloudItem("/docs/readme.md", size = 150),
                )
            val relocator = CloudRelocator(source, target)
            val (size, count) = relocator.preFlightCheck("/")
            assertEquals(200L, size)
            assertEquals(2, count)
        }

    @Test
    fun `preFlightCheck ignores folders in count`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/folder", isFolder = true),
                )
            source.children["/folder"] = emptyList()
            val relocator = CloudRelocator(source, target)
            val (_, count) = relocator.preFlightCheck("/")
            assertEquals(0, count)
        }

    // -- migrate: copies files from source to target ----------------------------

    @Test
    fun `migrate copies files from source to target`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/file.txt", size = 10),
                )
            source.fileContents["/file.txt"] = "hello".toByteArray()

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            assertTrue(
                target.uploadedPaths.contains("/file.txt"),
                "target should have received /file.txt",
            )
            val completed = events.filterIsInstance<MigrateEvent.Completed>()
            assertEquals(1, completed.size)
            assertEquals(1, completed[0].doneFiles)
            assertEquals(0, completed[0].errorCount)
        }

    @Test
    fun `migrate creates folders on target`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/sub", isFolder = true),
                )
            source.children["/sub"] =
                listOf(
                    cloudItem("/sub/data.bin", size = 5),
                )
            source.fileContents["/sub/data.bin"] = ByteArray(5)

            val relocator = CloudRelocator(source, target)
            relocator.migrate("/", "/").toList()

            assertTrue(target.createdFolders.contains("/sub"))
            assertTrue(target.uploadedPaths.contains("/sub/data.bin"))
        }

    @Test
    fun `migrate emits Started event with correct totals`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/a.txt", size = 100),
                    cloudItem("/b.txt", size = 200),
                )
            source.fileContents["/a.txt"] = ByteArray(100)
            source.fileContents["/b.txt"] = ByteArray(200)

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            val started = events.filterIsInstance<MigrateEvent.Started>()
            assertEquals(1, started.size)
            assertEquals(2, started[0].totalFiles)
            assertEquals(300L, started[0].totalSize)
        }

    @Test
    fun `migrate emits FileProgressEvent with non-zero totals`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/a.txt", size = 100),
                    cloudItem("/b.txt", size = 200),
                )
            source.fileContents["/a.txt"] = ByteArray(100)
            source.fileContents["/b.txt"] = ByteArray(200)

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            val progress = events.filterIsInstance<MigrateEvent.FileProgressEvent>()
            assertTrue(progress.isNotEmpty(), "should emit at least one FileProgressEvent")
            for (event in progress) {
                assertEquals(2, event.totalFiles, "totalFiles must be threaded from preFlightCheck")
                assertEquals(300L, event.totalSize, "totalSize must be threaded from preFlightCheck")
            }
            // doneSize grows monotonically toward totalSize
            assertEquals(300L, progress.last().doneSize)
            assertEquals(2, progress.last().doneFiles)
        }

    @Test
    fun `migrate maps source subtree to target subtree`() =
        runTest {
            source.children["/src"] =
                listOf(
                    cloudItem("/src/f.txt", size = 10),
                )
            source.fileContents["/src/f.txt"] = ByteArray(10)

            val relocator = CloudRelocator(source, target)
            relocator.migrate("/src", "/dst").toList()

            assertTrue(
                target.uploadedPaths.contains("/dst/f.txt"),
                "file should be uploaded under target subtree",
            )
        }

    // -- skip-if-exists ---------------------------------------------------------

    @Test
    fun `migrate skips file when target has matching size`() =
        runTest {
            source.children["/"] =
                listOf(cloudItem("/a.txt", size = 100))
            source.fileContents["/a.txt"] = ByteArray(100)
            target.children["/"] =
                listOf(cloudItem("/a.txt", size = 100))

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            assertTrue(
                "/a.txt" !in target.uploadedPaths,
                "target should not receive upload for equivalent file",
            )
            val completed = events.filterIsInstance<MigrateEvent.Completed>().first()
            assertEquals(1, completed.skippedFiles)
            assertEquals(100L, completed.skippedSize)
            // doneFiles includes skipped
            assertEquals(1, completed.doneFiles)
        }

    @Test
    fun `migrate skips file when hashes match even if sizes differ`() =
        runTest {
            source.children["/"] =
                listOf(cloudItem("/a.txt", size = 100))
            source.fileContents["/a.txt"] = ByteArray(100)
            // Different size, same hash — hash wins
            target.children["/"] =
                listOf(cloudItem("/a.txt", size = 200))

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            assertTrue("/a.txt" !in target.uploadedPaths)
            val completed = events.filterIsInstance<MigrateEvent.Completed>().first()
            assertEquals(1, completed.skippedFiles)
        }

    @Test
    fun `migrate transfers file when size differs and hashes absent`() =
        runTest {
            source.children["/"] =
                listOf(cloudItem("/a.txt", size = 100, hash = null))
            source.fileContents["/a.txt"] = ByteArray(100)
            target.children["/"] =
                listOf(cloudItem("/a.txt", size = 200, hash = null))

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            assertTrue("/a.txt" in target.uploadedPaths)
            val completed = events.filterIsInstance<MigrateEvent.Completed>().first()
            assertEquals(0, completed.skippedFiles)
            assertEquals(1, completed.doneFiles)
        }

    @Test
    fun `migrate transfers everything when skipExisting is false`() =
        runTest {
            source.children["/"] =
                listOf(cloudItem("/a.txt", size = 100))
            source.fileContents["/a.txt"] = ByteArray(100)
            target.children["/"] =
                listOf(cloudItem("/a.txt", size = 100))

            val relocator = CloudRelocator(source, target, skipExisting = false)
            val events = relocator.migrate("/", "/").toList()

            assertTrue("/a.txt" in target.uploadedPaths, "force flag should transfer despite match")
            val completed = events.filterIsInstance<MigrateEvent.Completed>().first()
            assertEquals(0, completed.skippedFiles)
        }

    @Test
    fun `migrate skips and transfers in mixed set`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/same.txt", size = 50),
                    cloudItem("/different.txt", size = 50, hash = null),
                    cloudItem("/new.txt", size = 50),
                )
            source.fileContents["/same.txt"] = ByteArray(50)
            source.fileContents["/different.txt"] = ByteArray(50)
            source.fileContents["/new.txt"] = ByteArray(50)
            target.children["/"] =
                listOf(
                    cloudItem("/same.txt", size = 50),
                    cloudItem("/different.txt", size = 99, hash = null),
                )

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            assertTrue("/same.txt" !in target.uploadedPaths)
            assertTrue("/different.txt" in target.uploadedPaths)
            assertTrue("/new.txt" in target.uploadedPaths)
            val completed = events.filterIsInstance<MigrateEvent.Completed>().first()
            assertEquals(1, completed.skippedFiles)
            assertEquals(50L, completed.skippedSize)
            // 2 transferred + 1 skipped
            assertEquals(3, completed.doneFiles)
            assertEquals(150L, completed.doneSize)
        }

    // -- error handling ---------------------------------------------------------

    @Test
    fun `migrate emits Error event on download failure`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/fail.txt", size = 10),
                )
            source.downloadShouldFail = true

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            val errors = events.filterIsInstance<MigrateEvent.Error>()
            assertTrue(errors.isNotEmpty(), "should emit at least one Error event")
            val completed = events.filterIsInstance<MigrateEvent.Completed>()
            assertTrue(completed[0].errorCount > 0)
        }

    @Test
    fun `migrate continues after single file failure`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/good.txt", size = 10),
                    cloudItem("/bad.txt", size = 10),
                )
            source.fileContents["/good.txt"] = ByteArray(10)
            source.failPaths.add("/bad.txt")

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            val completed = events.filterIsInstance<MigrateEvent.Completed>()
            assertEquals(1, completed[0].doneFiles, "good file should still be migrated")
            assertEquals(1, completed[0].errorCount, "bad file should be counted as error")
        }

    @Test
    fun `migrate emits Completed with duration`() =
        runTest {
            source.children["/"] = emptyList()

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            val completed = events.filterIsInstance<MigrateEvent.Completed>()
            assertEquals(1, completed.size)
            assertTrue(completed[0].durationMs >= 0)
        }

    // -- UD-273: single-scan when totals supplied ------------------------------

    @Test
    fun `migrate with pre-computed totals scans source exactly once`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/sub", isFolder = true),
                    cloudItem("/top.txt", size = 10),
                )
            source.children["/sub"] =
                listOf(
                    cloudItem("/sub/a.txt", size = 20),
                )
            source.fileContents["/top.txt"] = ByteArray(10)
            source.fileContents["/sub/a.txt"] = ByteArray(20)

            val relocator = CloudRelocator(source, target)
            relocator.migrate("/", "/", knownTotalSize = 30L, knownTotalFiles = 2).toList()

            // 2 source folders → with known totals, preFlightCheck is skipped.
            // walk calls source.listChildren exactly once per folder.
            assertEquals(2, source.listChildrenCount, "source scanned once (no redundant preFlightCheck)")
        }

    @Test
    fun `migrate with pre-computed totals emits Started with those exact values`() =
        runTest {
            source.children["/"] = listOf(cloudItem("/a.txt", size = 10))
            source.fileContents["/a.txt"] = ByteArray(10)

            val relocator = CloudRelocator(source, target)
            val events =
                relocator.migrate("/", "/", knownTotalSize = 999L, knownTotalFiles = 42).toList()

            val started = events.filterIsInstance<MigrateEvent.Started>().first()
            assertEquals(999L, started.totalSize)
            assertEquals(42, started.totalFiles)
        }

    // -- UD-286: cancellation hygiene + log-on-failure -------------------------
    //
    // CloudRelocator had four `catch (e: Exception)` blocks that silently
    // absorbed `CancellationException` (breaking structured concurrency) and
    // dropped the exception's class + stack from the log (the live unidrive.log
    // contained zero ERROR/WARN lines for active relocates). These tests pin
    // the post-fix contract: cancellation propagates promptly, MigrateEvent.Error
    // carries the exception class name, and per-file failures don't halt the
    // walk.

    @Test
    fun `UD-286 - per-file CancellationException propagates instead of being absorbed`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/cancelled.txt", size = 10),
                    cloudItem("/never-reached.txt", size = 10),
                )
            source.fileContents["/never-reached.txt"] = ByteArray(10)
            source.cancellationPaths.add("/cancelled.txt")

            // UD-817: maxConcurrentTransfers=1 forces sequential per-file processing,
            // so cancelled.txt's CE always fires before never-reached.txt's launch.
            // Default 4 lets both files race in parallel, never-reached can complete
            // upload before outerScope.cancel(e) propagates from cancelled.txt's CE.
            val relocator = CloudRelocator(source, target, maxConcurrentTransfers = 1)

            assertFailsWith<CancellationException> {
                relocator.migrate("/", "/").toList()
            }

            // Walk halted at the cancelled file — never-reached.txt was not
            // uploaded. (If the catch block had absorbed the cancellation, the
            // walk would have continued and uploaded the second file.)
            assertTrue(
                target.uploadedPaths.none { it.contains("never-reached") },
                "walk should halt on cancellation; uploaded=${target.uploadedPaths}",
            )
        }

    @Test
    fun `UD-286 - listChildren CancellationException propagates from targetIndex`() =
        runTest {
            source.children["/"] = listOf(cloudItem("/a.txt", size = 10))
            source.fileContents["/a.txt"] = ByteArray(10)
            // Target's listChildren is invoked via targetIndex(). If the catch
            // there absorbed cancellation, migrate() would proceed to upload
            // the file silently.
            target.cancellationPaths.add("/")

            val relocator = CloudRelocator(source, target)

            assertFailsWith<CancellationException> {
                relocator.migrate("/", "/").toList()
            }

            assertTrue(
                target.uploadedPaths.isEmpty(),
                "no upload should happen after cancellation in targetIndex; uploaded=${target.uploadedPaths}",
            )
        }

    @Test
    fun `UD-286 - per-file Error event carries exception class name`() =
        runTest {
            source.children["/"] =
                listOf(cloudItem("/bad.txt", size = 10))
            source.failPaths.add("/bad.txt")

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            val errors = events.filterIsInstance<MigrateEvent.Error>()
            assertEquals(1, errors.size, "expected exactly one Error event")
            // Pre-UD-286 the message was "Failed to migrate /bad.txt: Simulated …".
            // Post-fix it includes the exception class name so the user (and
            // postmortem) can distinguish IOException vs HttpRequestTimeoutException
            // vs WSAECONNABORTED at a glance.
            assertTrue(
                errors[0].message.contains("RuntimeException"),
                "Error message should include exception class name; was: '${errors[0].message}'",
            )
            assertTrue(
                errors[0].message.contains("/bad.txt"),
                "Error message should include the failing path; was: '${errors[0].message}'",
            )
        }

    @Test
    fun `UD-286 - per-file failure does not halt the walk (UD-222 contract preserved)`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/good-1.txt", size = 10),
                    cloudItem("/bad.txt", size = 10),
                    cloudItem("/good-2.txt", size = 10),
                )
            source.fileContents["/good-1.txt"] = ByteArray(10)
            source.fileContents["/good-2.txt"] = ByteArray(10)
            source.failPaths.add("/bad.txt")

            val relocator = CloudRelocator(source, target)
            val events = relocator.migrate("/", "/").toList()

            val completed = events.filterIsInstance<MigrateEvent.Completed>()
            assertEquals(1, completed.size)
            assertEquals(2, completed[0].doneFiles, "both good files should migrate")
            assertEquals(1, completed[0].errorCount)
            // Both good files reached the target; the bad one didn't halt the walk.
            assertTrue(
                target.uploadedPaths.contains("/good-1.txt") &&
                    target.uploadedPaths.contains("/good-2.txt"),
                "uploadedPaths=${target.uploadedPaths}",
            )
        }

    // -- gkrost/unidrive-cli#61: a walk failure must mark the run failed --------------------------
    //
    // Pre-fix the walk-failure catch emitted one Error event but did NOT increment
    // errorCount, so a run that lost half its tree to a mid-walk listChildren failure
    // reported errorCount == 0 and the delete pass saw a clean migration.

    @Test
    fun `migrate marks the run failed when the walk itself fails midway`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/a.txt", size = 10),
                    cloudItem("/sub", isFolder = true),
                )
            source.fileContents["/a.txt"] = ByteArray(10)
            source.children["/sub"] =
                listOf(
                    cloudItem("/sub/deep.txt", size = 10),
                )
            source.fileContents["/sub/deep.txt"] = ByteArray(10)
            source.listChildrenFailPaths.add("/sub")

            val relocator = CloudRelocator(source, target)
            // Known totals skip migrate's internal preFlightCheck (which walks the same tree
            // and would fail first); this is how RelocateCommand calls migrate.
            val events = relocator.migrate("/", "/", knownTotalSize = 20L, knownTotalFiles = 2).toList()

            val errors = events.filterIsInstance<MigrateEvent.Error>()
            assertTrue(errors.isNotEmpty(), "the walk failure must be reported as an Error event")
            val completed = events.filterIsInstance<MigrateEvent.Completed>().first()
            assertTrue(
                completed.errorCount > 0,
                "a walk failure must mark the run failed, not leave errorCount at 0; got ${completed.errorCount}",
            )
            assertTrue(
                completed.doneFiles < completed.totalFiles,
                "the files behind the failed walk were never processed; doneFiles=${completed.doneFiles}, totalFiles=${completed.totalFiles}",
            )
        }

    // -- gkrost/unidrive-cli#61: the migration manifest ------------------------------------------
    //
    // The delete pass must delete exactly what the migration transferred, by id. The
    // manifest is that record: source path + id, target id from the upload's return
    // value, and whether a skip was proven equivalent by hash or only by size.

    @Test
    fun `migrate records a manifest entry with source and target ids for every transferred file`() =
        runTest {
            source.children["/"] =
                listOf(
                    CloudItem(
                        id = "src-1",
                        name = "a.txt",
                        path = "/a.txt",
                        size = 10,
                        isFolder = false,
                        modified = Instant.parse("2026-03-28T12:00:00Z"),
                        created = Instant.parse("2026-03-28T10:00:00Z"),
                        hash = "hash-a",
                        mimeType = "application/octet-stream",
                    ),
                    cloudItem("/bad.txt", size = 10),
                )
            source.fileContents["/a.txt"] = ByteArray(10)
            source.failPaths.add("/bad.txt")

            val manifest = MigrationManifest()
            val relocator = CloudRelocator(source, target)
            relocator.migrate("/", "/", manifest = manifest).toList()

            val entry = manifest.entries["/a.txt"]
            assertNotNull(entry, "the transferred file must have a manifest entry; entries=${manifest.entries.keys}")
            assertEquals("src-1", entry.sourceId, "the source id comes from the listing item")
            assertEquals("/a.txt", entry.targetPath)
            assertEquals("id-/a.txt", entry.targetId, "the target id comes from the upload's return value")
            assertEquals(MigrationManifest.Outcome.TRANSFERRED, entry.outcome)
            assertNull(
                manifest.entries["/bad.txt"],
                "a failed upload produces no manifest entry",
            )
        }

    @Test
    fun `migrate records skipped-equivalent entries and whether the equivalence was hash-backed`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/hash-equal.txt", size = 100, hash = "same"),
                    cloudItem("/size-only.txt", size = 100, hash = null),
                )
            source.fileContents["/hash-equal.txt"] = ByteArray(100)
            source.fileContents["/size-only.txt"] = ByteArray(100)
            target.children["/"] =
                listOf(
                    cloudItem("/hash-equal.txt", size = 100, hash = "same"),
                    cloudItem("/size-only.txt", size = 100, hash = null),
                )

            val manifest = MigrationManifest()
            val relocator = CloudRelocator(source, target)
            relocator.migrate("/", "/", manifest = manifest).toList()

            val hashEntry = manifest.entries["/hash-equal.txt"]
            assertNotNull(hashEntry)
            assertEquals(MigrationManifest.Outcome.SKIPPED_EQUIVALENT, hashEntry.outcome)
            assertTrue(hashEntry.hashEquivalent, "a hash match is a proven equivalence")
            assertNull(hashEntry.targetId, "no upload happened for a skipped file")

            val sizeEntry = manifest.entries["/size-only.txt"]
            assertNotNull(sizeEntry)
            assertEquals(MigrationManifest.Outcome.SKIPPED_EQUIVALENT, sizeEntry.outcome)
            assertTrue(!sizeEntry.hashEquivalent, "a size-only skip is not a proven equivalence")
        }

    @Test
    fun `migrate manifest lists every visited source folder with its id`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/sub", isFolder = true),
                )
            source.children["/sub"] =
                listOf(
                    cloudItem("/sub/deep", isFolder = true),
                    cloudItem("/sub/data.bin", size = 5),
                )
            source.children["/sub/deep"] = emptyList()
            source.fileContents["/sub/data.bin"] = ByteArray(5)

            val manifest = MigrationManifest()
            val relocator = CloudRelocator(source, target)
            relocator.migrate("/", "/", manifest = manifest).toList()

            assertEquals("id-/sub", manifest.folders["/sub"], "visited folders are recorded with their listing id")
            assertEquals("id-/sub/deep", manifest.folders["/sub/deep"])
            assertEquals(2, manifest.folders.size)
        }

    // -- gkrost/unidrive-cli#62: a hash mismatch is not an equivalence ----------------------------

    @Test
    fun `isEquivalent is false when both providers report the same algorithm and the hashes differ`() {
        source.fakeHashAlgorithm = HashAlgorithm.Sha256Hex
        target.fakeHashAlgorithm = HashAlgorithm.Sha256Hex
        val relocator = CloudRelocator(source, target)

        assertFalse(relocator.isEquivalent(cloudItem("/a.txt", size = 10, hash = "A"), cloudItem("/a.txt", size = 10, hash = "B")))
        assertEquals(
            CloudRelocator.Equivalence.CONFLICT,
            relocator.compare(cloudItem("/a.txt", size = 10, hash = "A"), cloudItem("/a.txt", size = 10, hash = "B")),
        )
    }

    @Test
    fun `isEquivalent is true for equal hashes and for equal sizes when no hash is comparable`() {
        source.fakeHashAlgorithm = HashAlgorithm.Sha256Hex
        target.fakeHashAlgorithm = HashAlgorithm.Sha256Hex
        val relocator = CloudRelocator(source, target)

        assertEquals(
            CloudRelocator.Equivalence.HASH,
            relocator.compare(cloudItem("/a.txt", size = 10, hash = "A"), cloudItem("/a.txt", size = 99, hash = "A")),
        )
        // One side reports no hash: size decides, and it is the weaker claim.
        assertEquals(
            CloudRelocator.Equivalence.SIZE_ONLY,
            relocator.compare(cloudItem("/a.txt", size = 10, hash = "A"), cloudItem("/a.txt", size = 10, hash = null)),
        )
        assertEquals(
            CloudRelocator.Equivalence.DIFFERENT,
            relocator.compare(cloudItem("/a.txt", size = 10, hash = "A"), cloudItem("/a.txt", size = 11, hash = null)),
        )
    }

    @Test
    fun `different algorithms or none make unequal hashes incomparable, so size decides`() {
        val a = cloudItem("/a.txt", size = 10, hash = "A")
        val b = cloudItem("/a.txt", size = 10, hash = "B")

        source.fakeHashAlgorithm = HashAlgorithm.Sha256Hex
        target.fakeHashAlgorithm = HashAlgorithm.QuickXor
        assertEquals(CloudRelocator.Equivalence.SIZE_ONLY, CloudRelocator(source, target).compare(a, b))

        source.fakeHashAlgorithm = null
        target.fakeHashAlgorithm = null
        assertEquals(CloudRelocator.Equivalence.SIZE_ONLY, CloudRelocator(source, target).compare(a, b))
    }

    @Test
    fun `a different size with differing hashes stays a plain transfer, as before`() {
        source.fakeHashAlgorithm = HashAlgorithm.Sha256Hex
        target.fakeHashAlgorithm = HashAlgorithm.Sha256Hex
        assertEquals(
            CloudRelocator.Equivalence.DIFFERENT,
            CloudRelocator(source, target).compare(
                cloudItem("/a.txt", size = 10, hash = "A"),
                cloudItem("/a.txt", size = 5, hash = "B"),
            ),
        )
    }

    @Test
    fun `migrate reports a hash conflict, keeps the target and records nothing for the source to be deleted by`() =
        runTest {
            source.fakeHashAlgorithm = HashAlgorithm.Sha256Hex
            target.fakeHashAlgorithm = HashAlgorithm.Sha256Hex
            source.children["/"] = listOf(cloudItem("/a.txt", size = 100, hash = "source-bytes"))
            source.fileContents["/a.txt"] = ByteArray(100)
            target.children["/"] = listOf(cloudItem("/a.txt", size = 100, hash = "other-bytes"))

            val manifest = MigrationManifest()
            val events = CloudRelocator(source, target).migrate("/", "/", manifest = manifest).toList()

            assertTrue("/a.txt" !in target.uploadedPaths, "the conflicting target must not be overwritten")
            val errors = events.filterIsInstance<MigrateEvent.Error>()
            assertEquals(1, errors.size, "the conflict is reported once")
            assertTrue("Conflict" in errors.single().message && "/a.txt" in errors.single().message, errors.single().message)
            val completed = events.filterIsInstance<MigrateEvent.Completed>().single()
            assertEquals(1, completed.errorCount)
            assertEquals(0, completed.skippedFiles, "a conflict is not a skip")
            assertEquals(0, completed.doneFiles)
            assertNull(manifest.entries["/a.txt"], "no manifest entry: the source copy is never in the set a delete pass may delete")
        }

    @Test
    fun `a size-only skip is logged as such and stays unproven in the manifest`() =
        runTest {
            source.children["/"] = listOf(cloudItem("/a.txt", size = 100, hash = null))
            source.fileContents["/a.txt"] = ByteArray(100)
            target.children["/"] = listOf(cloudItem("/a.txt", size = 100, hash = null))

            val logger = LoggerFactory.getLogger(CloudRelocator::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            val manifest = MigrationManifest()
            try {
                CloudRelocator(source, target).migrate("/", "/", manifest = manifest).toList()
            } finally {
                logger.detachAppender(appender)
            }

            assertTrue(
                appender.list.any { it.level == Level.WARN && "size only" in it.formattedMessage },
                "a size-only skip says so; got ${appender.list.map { it.formattedMessage }}",
            )
            assertFalse(manifest.entries.getValue("/a.txt").hashEquivalent)
        }

    // -- UD-269: stale tempdir GC ---------------------------------------------

    @Test
    fun `UD-269 - cleanStaleTempDirs deletes dirs older than maxAge`() {
        val tmp = Files.createTempDirectory("ud269-gc-test-")
        try {
            val stale = tmp.resolve("unidrive-relocate-stale-001")
            Files.createDirectory(stale)
            // Plant a file inside so we exercise the recursive-delete path.
            Files.writeString(stale.resolve("orphan.txt"), "leftover")
            // Backdate to 48h ago — older than the default 24h cutoff.
            val twoDaysAgo =
                java.nio.file.attribute.FileTime
                    .fromMillis(System.currentTimeMillis() - 48L * 3600 * 1000)
            Files.setLastModifiedTime(stale, twoDaysAgo)

            val deleted = CloudRelocator.cleanStaleTempDirs(tmpDir = tmp)
            assertEquals(1, deleted)
            assertEquals(false, Files.exists(stale), "stale dir should have been deleted")
        } finally {
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `UD-269 - cleanStaleTempDirs leaves fresh dirs alone`() {
        val tmp = Files.createTempDirectory("ud269-gc-fresh-test-")
        try {
            val fresh = tmp.resolve("unidrive-relocate-fresh-001")
            Files.createDirectory(fresh)
            // mtime defaults to now; well under the 24h cutoff.

            val deleted = CloudRelocator.cleanStaleTempDirs(tmpDir = tmp)
            assertEquals(0, deleted)
            assertTrue(Files.exists(fresh), "fresh dir should NOT be deleted")
        } finally {
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `UD-269 - cleanStaleTempDirs ignores non-relocate dirs`() {
        val tmp = Files.createTempDirectory("ud269-gc-other-test-")
        try {
            // A directory that's old but doesn't match the prefix — must not
            // get deleted (might be another tool's working dir).
            val unrelated = tmp.resolve("some-other-tool-cache-12345")
            Files.createDirectory(unrelated)
            val twoDaysAgo =
                java.nio.file.attribute.FileTime
                    .fromMillis(System.currentTimeMillis() - 48L * 3600 * 1000)
            Files.setLastModifiedTime(unrelated, twoDaysAgo)

            val deleted = CloudRelocator.cleanStaleTempDirs(tmpDir = tmp)
            assertEquals(0, deleted, "non-relocate dirs must be left alone")
            assertTrue(Files.exists(unrelated))
        } finally {
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    @Test
    fun `UD-269 - cleanStaleTempDirs returns 0 when tmpdir does not exist`() {
        // Pointing at a non-existent dir must not throw — the helper is
        // called silently at every relocate startup.
        val nonexistent = Files.createTempDirectory("ud269-nx-").resolve("nope")
        assertEquals(0, CloudRelocator.cleanStaleTempDirs(tmpDir = nonexistent))
    }

    // -- UD-327: WebDAV-style per-file size cap pre-flight ---------------------

    @Test
    fun `UD-327 - preflightOversized returns empty when target has no cap`() =
        runTest {
            source.children["/"] =
                listOf(
                    cloudItem("/big.mp4", size = 5L * 1024 * 1024 * 1024),
                )
            target.fakeMaxFileSizeBytes = null
            val relocator = CloudRelocator(source, target)
            assertEquals(emptyList<Pair<String, Long>>(), relocator.preflightOversized("/"))
        }

    @Test
    fun `UD-327 - preflightOversized returns files exceeding the cap`() =
        runTest {
            val fourGiB = 4L * 1024 * 1024 * 1024
            source.children["/"] =
                listOf(
                    cloudItem("/small.txt", size = 100),
                    cloudItem("/big.mp4", size = fourGiB + 1),
                    cloudItem("/giant.mp4", size = 10L * 1024 * 1024 * 1024),
                )
            target.fakeMaxFileSizeBytes = fourGiB
            val relocator = CloudRelocator(source, target)
            val oversized = relocator.preflightOversized("/")
            assertEquals(2, oversized.size)
            assertEquals("/big.mp4", oversized[0].first)
            assertEquals(fourGiB + 1, oversized[0].second)
            assertEquals("/giant.mp4", oversized[1].first)
        }

    @Test
    fun `UD-327 - preflightOversized recurses into subdirectories`() =
        runTest {
            val cap = 1L * 1024 * 1024 // 1 MiB
            source.children["/"] =
                listOf(
                    cloudItem("/sub", isFolder = true),
                )
            source.children["/sub"] =
                listOf(
                    cloudItem("/sub/big.bin", size = cap + 1),
                    cloudItem("/sub/ok.bin", size = cap),
                )
            target.fakeMaxFileSizeBytes = cap
            val relocator = CloudRelocator(source, target)
            val oversized = relocator.preflightOversized("/")
            assertEquals(1, oversized.size)
            assertEquals("/sub/big.bin", oversized[0].first)
        }

    @Test
    fun `UD-327 - file exactly at cap is NOT oversized`() =
        runTest {
            val cap = 1024L
            source.children["/"] =
                listOf(
                    cloudItem("/at-cap.bin", size = cap),
                    cloudItem("/over.bin", size = cap + 1),
                )
            target.fakeMaxFileSizeBytes = cap
            val relocator = CloudRelocator(source, target)
            val oversized = relocator.preflightOversized("/")
            assertEquals(1, oversized.size)
            assertEquals("/over.bin", oversized[0].first)
        }

    // -- UD-274: per-file failure writes WARN line to slf4j logger -------------
    //
    // Pre-fix: per-file failures emitted MigrateEvent.Error which the CLI
    // printed to stderr — but no slf4j call ever reached unidrive.log. Field
    // observation 2026-04-21: a relocate run with dozens of "Connection
    // closed by peer" stderr messages produced zero matching lines in
    // unidrive.log. UD-286 added the log.warn at CloudRelocator.kt:249;
    // this test pins it so a future refactor can't silently drop it.

    @Test
    fun `UD-274 - per-file failure writes WARN line capturing path and class`() =
        runTest {
            source.children["/"] = listOf(cloudItem("/bad.txt", size = 10))
            source.failPaths.add("/bad.txt")

            val logger = LoggerFactory.getLogger(CloudRelocator::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)

            try {
                val relocator = CloudRelocator(source, target)
                relocator.migrate("/", "/").toList()
            } finally {
                logger.detachAppender(appender)
            }

            val warns = appender.list.filter { it.level == Level.WARN }
            assertTrue(
                warns.isNotEmpty(),
                "expected at least one WARN line for the per-file failure; got=${appender.list.size} events",
            )
            val msg = warns.first().formattedMessage
            assertTrue(
                msg.contains("/bad.txt"),
                "WARN line must include the failing path; was: '$msg'",
            )
            assertTrue(
                msg.contains("RuntimeException"),
                "WARN line must include the exception class so postmortem can " +
                    "distinguish IOException vs HttpRequestTimeoutException; was: '$msg'",
            )
        }

    // -- UD-284: MDC propagation into flow log lines ---------------------------
    //
    // Pre-fix: RelocateCommand's `runBlocking { migrate().collect { ... } }`
    // had no MDCContext, so the `build`, `profile`, and `scan` MDC keys set
    // on the calling thread by Main.main / Main.resolveCurrentProfile didn't
    // reach Dispatchers.IO worker threads. Log lines from CloudRelocator's
    // catch blocks rendered as `[???????] [*] [-------]` (the logback fallback
    // values from logback.xml line 31). Postmortem against unidrive.log was
    // impossible — couldn't filter on profile, couldn't pin a build SHA.
    //
    // This test verifies the underlying claim: when migrate() is collected
    // inside an MDCContext scope, the catch-block log.warn lines carry the
    // caller's MDC values across the `.flowOn(Dispatchers.IO)` boundary.

    @Test
    fun `UD-284 - migrate flow preserves caller MDC into log lines from catch blocks`() =
        runTest {
            // Setup: a failure on listChildren triggers the targetIndex catch
            // (UD-286) which emits log.warn. We assert that warn line carries
            // the MDC values set on the caller's thread.
            source.children["/"] = listOf(cloudItem("/file.txt", size = 10))
            source.fileContents["/file.txt"] = ByteArray(10)
            // Make target's listChildren throw on "/" so CloudRelocator's
            // targetIndex catch emits log.warn (the line whose MDC we assert).
            target.listChildrenFailPaths.add("/")

            val logger = LoggerFactory.getLogger(CloudRelocator::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)

            MDC.put("build", "abc1234")
            MDC.put("profile", "test-profile")
            try {
                withContext(MDCContext()) {
                    val relocator = CloudRelocator(source, target)
                    relocator.migrate("/", "/").toList()
                }
            } finally {
                MDC.remove("build")
                MDC.remove("profile")
                logger.detachAppender(appender)
            }

            val warns = appender.list.filter { it.level == Level.WARN }
            assertTrue(warns.isNotEmpty(), "expected at least one WARN line from targetIndex catch")
            val firstWarn = warns.first()
            // The MDC propertyMap on the captured event MUST contain the
            // caller's `build` + `profile` values — that's the post-fix
            // contract that breaks if the runBlocking forgets MDCContext().
            assertEquals(
                "abc1234",
                firstWarn.mdcPropertyMap["build"],
                "build MDC must propagate to catch-block log line; mdcMap=${firstWarn.mdcPropertyMap}",
            )
            assertEquals(
                "test-profile",
                firstWarn.mdcPropertyMap["profile"],
                "profile MDC must propagate to catch-block log line; mdcMap=${firstWarn.mdcPropertyMap}",
            )
        }

    // -- helpers ----------------------------------------------------------------

    private fun cloudItem(
        path: String,
        size: Long = 100,
        isFolder: Boolean = false,
        hash: String? = "hash-$path",
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = hash,
        mimeType = "application/octet-stream",
    )

    class FakeProvider(
        override val id: String,
    ) : CloudProvider {
        override val displayName = id
        override var isAuthenticated = true

        override fun capabilities(): Set<org.krost.unidrive.Capability> = setOf(org.krost.unidrive.Capability.Delta)

        val children = mutableMapOf<String, List<CloudItem>>()
        val fileContents = mutableMapOf<String, ByteArray>()
        val uploadedPaths = mutableListOf<String>()
        val createdFolders = mutableListOf<String>()
        var downloadShouldFail = false
        val failPaths = mutableSetOf<String>()

        // UD-327: per-file size cap for the preflightOversized regression test.
        // Null mirrors generic WebDAV / OneDrive (no cap configured).
        var fakeMaxFileSizeBytes: Long? = null

        // UD-286: paths whose download or listChildren should throw
        // CancellationException — used by the regression test to verify the
        // catch blocks in CloudRelocator re-throw cancellation cleanly
        // instead of absorbing it.
        val cancellationPaths = mutableSetOf<String>()

        // UD-284: paths whose listChildren should throw a generic exception
        // (so the targetIndex catch in CloudRelocator emits log.warn — used
        // by the MDC-propagation regression test).
        val listChildrenFailPaths = mutableSetOf<String>()

        var listChildrenCount = 0

        override suspend fun authenticate() {}

        override suspend fun logout() {}

        override fun maxFileSizeBytes(): Long? = fakeMaxFileSizeBytes

        // gkrost/unidrive-cli#62: the algorithm this provider reports for its items' hashes. Null (the default) is a
        // provider that names none, as the real ones without a verifiable hash do.
        var fakeHashAlgorithm: HashAlgorithm? = null

        override fun hashAlgorithm(): HashAlgorithm? = fakeHashAlgorithm

        override suspend fun listChildren(path: String): List<CloudItem> {
            listChildrenCount++
            if (path in cancellationPaths) {
                throw CancellationException("Simulated cancellation on listChildren($path)")
            }
            if (path in listChildrenFailPaths) {
                throw RuntimeException("Simulated listChildren failure for $path")
            }
            return children[path] ?: emptyList()
        }

        override suspend fun getMetadata(path: String): CloudItem = throw UnsupportedOperationException("not needed for relocator tests")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            if (remotePath in cancellationPaths) {
                throw CancellationException("Simulated cancellation on download($remotePath)")
            }
            if (downloadShouldFail || remotePath in failPaths) {
                throw RuntimeException("Simulated download failure for $remotePath")
            }
            val content = fileContents[remotePath] ?: ByteArray(0)
            Files.createDirectories(destination.parent)
            Files.write(destination, content)
            return content.size.toLong()
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploadedPaths.add(remotePath)
            val size = Files.size(localPath)
            return CloudItem(
                id = "id-$remotePath",
                name = remotePath.substringAfterLast("/"),
                path = remotePath,
                size = size,
                isFolder = false,
                modified = Instant.now(),
                created = Instant.now(),
                hash = "uploaded",
                mimeType = null,
            )
        }

        override suspend fun delete(remotePath: String, ifMatchETag: String?) {}

        override suspend fun createFolder(path: String): CloudItem {
            createdFolders.add(path)
            return CloudItem(
                id = "id-$path",
                name = path.substringAfterLast("/"),
                path = path,
                size = 0,
                isFolder = true,
                modified = Instant.now(),
                created = Instant.now(),
                hash = null,
                mimeType = null,
            )
        }

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ) = createFolder(toPath)

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ) = DeltaPage(items = emptyList(), cursor = "c", hasMore = false)

        override suspend fun quota() = QuotaInfo(total = 10_000_000, used = 0, remaining = 10_000_000)
    }
}
