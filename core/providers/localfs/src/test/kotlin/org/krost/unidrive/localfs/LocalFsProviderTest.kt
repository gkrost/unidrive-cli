package org.krost.unidrive.localfs

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CaseSensitivity
import org.krost.unidrive.ProviderException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalFsProviderTest {
    private fun newRoot(): Path = Files.createTempDirectory("localfs-remote")

    @Test
    fun upload_and_download_preserve_source_mtime_and_full_size() =
        runTest {
            val p = LocalFsProvider(newRoot())
            p.authenticate()
            val mtime = FileTime.fromMillis(1_600_000_000_000L)
            val src = Files.createTempFile("src", ".bin")
            Files.write(src, ByteArray(100_000) { it.toByte() })
            Files.setLastModifiedTime(src, mtime)

            val item = p.upload(src, "/a.bin")
            assertEquals(100_000L, item.size)
            assertEquals(mtime.toInstant(), item.modified)

            val dest = Files.createTempDirectory("out").resolve("a.bin")
            assertEquals(100_000L, p.download("/a.bin", dest))
            assertEquals(mtime, Files.getLastModifiedTime(dest))
        }

    @Test
    fun upload_then_list_and_download_roundtrips() =
        runTest {
            val p = LocalFsProvider(newRoot())
            p.authenticate()
            assertTrue(p.isAuthenticated)

            val src = Files.createTempFile("src", ".txt")
            Files.writeString(src, "hello")
            val item = p.upload(src, "/dir/a.txt")
            assertEquals("/dir/a.txt", item.path)
            assertFalse(item.isFolder)
            assertEquals(5L, item.size)

            assertEquals(listOf("a.txt"), p.listChildren("/dir").map { it.name })

            val dest = Files.createTempDirectory("out").resolve("out.txt")
            assertEquals(5L, p.download("/dir/a.txt", dest))
            assertEquals("hello", Files.readString(dest))
        }

    @Test
    fun the_case_policy_is_the_one_of_the_file_system_that_holds_the_root() {
        // archive#309: the drive is a directory, so it folds case exactly where its file system does.
        val root = newRoot()
        assertEquals(CaseSensitivity.ofDirectory(root), LocalFsProvider(root).caseSensitivity)
    }

    @Test
    fun a_move_that_only_changes_the_case_renames_the_item() =
        runTest {
            // archive#309: on a case-insensitive file system (NTFS) the new name is the same file, so a plain move refused it
            // as "the target exists already". On a case-sensitive one it is an ordinary rename.
            val root = newRoot()
            val p = LocalFsProvider(root)
            p.authenticate()
            Files.createDirectories(root.resolve("docs"))
            Files.writeString(root.resolve("docs/case1.txt"), "bytes")

            val file = p.move("/docs/case1.txt", "/docs/CASE1.txt")
            assertEquals("/docs/CASE1.txt", file.path)
            assertEquals(listOf("CASE1.txt"), Files.list(root.resolve("docs")).use { s -> s.map { it.fileName.toString() }.toList() })
            assertEquals("bytes", Files.readString(root.resolve("docs/CASE1.txt")))

            p.move("/docs", "/DOCS")
            assertEquals(listOf("DOCS"), Files.list(root).use { s -> s.map { it.fileName.toString() }.toList() })
            assertEquals("bytes", Files.readString(root.resolve("DOCS/CASE1.txt")))
        }

    @Test
    fun a_move_onto_another_existing_item_is_still_refused() =
        runTest {
            val root = newRoot()
            val p = LocalFsProvider(root)
            p.authenticate()
            Files.writeString(root.resolve("a.txt"), "a")
            Files.writeString(root.resolve("b.txt"), "b")

            assertFailsWith<ProviderException> { p.move("/a.txt", "/b.txt") }
            assertEquals("b", Files.readString(root.resolve("b.txt")))
        }

    @Test
    fun delta_declares_that_every_call_is_a_full_listing() {
        // delta() walks the tree and sends no tombstones, so the engine must run its absence sweep on
        // every pass; without this declaration a remote deletion never reaches the sync root.
        assertTrue(LocalFsProvider(newRoot()).deltaIsFullListing)
    }

    @Test
    fun delta_reports_a_root_that_is_gone_as_an_incomplete_listing() =
        runTest {
            val root = newRoot()
            val p = LocalFsProvider(root)
            p.authenticate()
            Files.delete(root)

            val page = p.delta(null)
            assertTrue(page.items.isEmpty())
            assertFalse(
                page.complete,
                "a vanished root (an unmounted drive) is not an empty drive; read as complete, the engine deletes every mirrored file",
            )
        }

    @Test
    fun delta_returns_full_inventory() =
        runTest {
            val p = LocalFsProvider(newRoot())
            p.authenticate()
            p.createFolder("/sub")
            val src = Files.createTempFile("x", ".txt")
            Files.writeString(src, "y")
            p.upload(src, "/sub/x.txt")

            val page = p.delta(null)
            assertTrue(page.complete)
            assertFalse(page.hasMore)
            val paths = page.items.map { it.path }.toSet()
            assertTrue(paths.contains("/sub"), "expected the folder; got $paths")
            assertTrue(paths.contains("/sub/x.txt"), "expected the file; got $paths")
        }

    @Test
    fun deletePermanently_removes_a_file_and_a_folder_tree_and_is_declared() =
        runTest {
            val p = LocalFsProvider(newRoot())
            p.authenticate()
            assertTrue(p.capabilities().any { it is org.krost.unidrive.Capability.PermanentDelete })
            val src = Files.createTempFile("src", ".txt")
            Files.writeString(src, "x")
            p.upload(src, "/d/a.txt")
            p.upload(src, "/top.txt")

            p.deletePermanently("/top.txt")
            p.deletePermanently("/d")

            assertTrue(p.listChildren("/").isEmpty())
        }

    @Test
    fun delete_removes_file() =
        runTest {
            val p = LocalFsProvider(newRoot())
            p.authenticate()
            val src = Files.createTempFile("d", ".txt")
            Files.writeString(src, "z")
            p.upload(src, "/d.txt")
            assertTrue((p.verifyItemExists("/d.txt") as CapabilityResult.Success).value)
            p.delete("/d.txt")
            assertFalse((p.verifyItemExists("/d.txt") as CapabilityResult.Success).value)
        }

    @Test
    fun traversal_outside_root_is_rejected() =
        runTest {
            val p = LocalFsProvider(newRoot())
            p.authenticate()
            assertFailsWith<IllegalArgumentException> { p.getMetadata("/../escape.txt") }
        }

    @Test
    fun rejects_paths_through_a_symlink_escaping_root() =
        runTest {
            val root = newRoot()
            val outside = Files.createTempDirectory("localfs-outside")
            Files.writeString(outside.resolve("secret.txt"), "secret")
            val link = root.resolve("link")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (e: Exception) {
                // Windows without the symlink privilege / Developer Mode can't create one — skip.
                org.junit.Assume.assumeNoException("symlinks unsupported in this environment", e)
            }
            val p = LocalFsProvider(root)
            p.authenticate()
            assertFalse(p.delta(null).items.any { it.path == "/link" }, "delta must not emit an escaping symlink")
            assertFalse(p.listChildren("/").any { it.path == "/link" }, "listChildren must not emit an escaping symlink")
            assertFailsWith<IllegalArgumentException> { p.getMetadata("/link/secret.txt") }
        }

    @Test
    fun factory_reports_unhealthy_without_root_path() {
        val factory = LocalFsProviderFactory()
        val dir = Files.createTempDirectory("localfs-auth")
        assertFalse(factory.isAuthenticated(emptyMap(), dir))
        assertTrue(factory.isAuthenticated(mapOf("root_path" to "/some/dir"), dir))
    }

    @Test
    fun delete_does_not_follow_symlinks_out_of_root() =
        runTest {
            val root = newRoot()
            val outside = Files.createTempDirectory("localfs-outside-del")
            Files.writeString(outside.resolve("keep.txt"), "keep")
            val p = LocalFsProvider(root)
            p.authenticate()
            p.createFolder("/sub")
            try {
                Files.createSymbolicLink(root.resolve("sub").resolve("lnk"), outside)
            } catch (e: Exception) {
                org.junit.Assume.assumeNoException("symlinks unsupported in this environment", e)
            }
            p.delete("/sub")
            assertTrue(Files.exists(outside.resolve("keep.txt")), "delete must not follow a symlink outside root")
        }

    @Test
    fun delta_skips_an_unreadable_directory_and_reports_the_listing_incomplete() =
        runTest {
            val root = newRoot()
            val p = LocalFsProvider(root)
            p.authenticate()
            Files.writeString(root.resolve("ok.txt"), "readable")
            val locked = Files.createDirectories(root.resolve("locked"))
            Files.writeString(locked.resolve("hidden.txt"), "unreachable")
            val restore = denyRead(locked)
            org.junit.Assume.assumeTrue("this platform cannot deny access to a directory", restore != null)

            try {
                val page = p.delta(null)

                assertFalse(
                    page.complete,
                    "a walk that skipped an unreadable directory must not read as complete, or the engine infers deletions for it",
                )
                val paths = page.items.map { it.path }.toSet()
                assertTrue("/ok.txt" in paths, "the readable part must still be listed; got $paths")
                assertTrue(
                    page.items.none { it.path.startsWith("/locked") },
                    "nothing under the unreadable directory may be listed; got $paths",
                )
                assertTrue(
                    p.lastDeltaSkippedPaths.any { it.endsWith("locked") },
                    "the skipped path must be named for the operator; got ${p.lastDeltaSkippedPaths}",
                )
                assertTrue(
                    page.skippedPaths.any { it.endsWith("locked") },
                    "the page must name the unreadable path for the status (#1234); got ${page.skippedPaths}",
                )
            } finally {
                restore?.invoke()
                locked.toFile().deleteRecursively()
            }
        }

    @Test
    fun delta_skips_the_windows_system_folders_by_policy_and_stays_complete() =
        runTest {
            val root = newRoot()
            val p = LocalFsProvider(root)
            p.authenticate()
            Files.writeString(root.resolve("mine.txt"), "mine")
            Files.createDirectories(root.resolve("\$RECYCLE.BIN")).resolve("x").let { Files.writeString(it, "r") }
            Files.createDirectories(root.resolve("System Volume Information")).resolve("y").let { Files.writeString(it, "s") }

            val page = p.delta(null)

            assertTrue(page.complete, "a policy skip is deliberate; it must not flip the page incomplete")
            assertTrue(page.skippedPaths.isEmpty(), "a policy skip is not a blocked folder; got ${page.skippedPaths}")
            val paths = page.items.map { it.path }.toSet()
            assertTrue("/mine.txt" in paths)
            assertTrue(paths.none { it.contains("RECYCLE") || it.contains("System Volume") }, "system folders must not sync; got $paths")
        }

    @Test
    fun authenticate_refuses_a_root_it_cannot_read() =
        runTest {
            val root = newRoot()
            Files.writeString(root.resolve("x.txt"), "x")
            val restore = denyRead(root)
            org.junit.Assume.assumeTrue("this platform cannot deny access to a directory", restore != null)

            try {
                val p = LocalFsProvider(root)
                val e = assertFailsWith<ProviderException> { p.authenticate() }
                assertTrue(
                    "root_not_readable" in (e.message ?: ""),
                    "the refusal must carry the wizard's token; got: ${e.message}",
                )
                assertFalse(p.isAuthenticated, "a failed authenticate must not leave the provider authenticated")
            } finally {
                restore?.invoke()
            }
        }

    // ---- archive#1057: a copy goes through a staging file, so a failed one leaves the destination alone ----

    /**
     * Runs [body] while [file] cannot be opened for reading: a handle with no sharing on Windows (a sharing
     * violation, as with any process that keeps a file open), chmod 000 elsewhere (skipped when the account reads
     * it anyway, as root does). Its attributes stay readable, as in the real case.
     */
    private inline fun whileUnreadable(
        file: Path,
        body: () -> Unit,
    ) {
        if (isWindows()) {
            java.nio.channels.FileChannel
                .open(
                    file,
                    java.nio.file.StandardOpenOption.READ,
                    com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ,
                    com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE,
                    com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE,
                ).use { body() }
        } else {
            val original = Files.getPosixFilePermissions(file)
            Files.setPosixFilePermissions(file, emptySet())
            try {
                org.junit.Assume.assumeFalse("the account reads a chmod 000 file (root)", Files.isReadable(file))
                body()
            } finally {
                Files.setPosixFilePermissions(file, original)
            }
        }
    }

    @Test
    fun a_read_only_file_is_copied_both_ways_and_over_its_own_earlier_copy() =
        runTest {
            val root = newRoot()
            val p = LocalFsProvider(root)
            p.authenticate()
            val src = Files.createTempFile("ro", ".txt")
            Files.writeString(src, "v1")
            Files.setLastModifiedTime(src, FileTime.fromMillis(1_600_000_000_000L))
            val cloud = root.resolve("c.txt")
            Files.writeString(cloud, "from the cloud")
            val dest = Files.createTempDirectory("out").resolve("c.txt")
            try {
                // COPY_ATTRIBUTES carries the read-only attribute along; the copy must still complete, and the
                // next copy must still be able to replace it.
                src.toFile().setReadOnly()
                cloud.toFile().setReadOnly()

                p.upload(src, "/ro.txt")
                p.upload(src, "/ro.txt")
                p.download("/c.txt", dest)
                p.download("/c.txt", dest)

                assertEquals("v1", Files.readString(root.resolve("ro.txt")))
                assertEquals("from the cloud", Files.readString(dest))
                assertEquals(1_600_000_000_000L, Files.getLastModifiedTime(root.resolve("ro.txt")).toMillis(), "the source mtime is kept")
            } finally {
                src.toFile().setWritable(true)
                cloud.toFile().setWritable(true)
            }
        }

    @Test
    fun a_download_that_fails_leaves_the_existing_file_and_no_staging_file() =
        runTest {
            val root = newRoot()
            val p = LocalFsProvider(root)
            p.authenticate()
            val cloud = root.resolve("c.txt")
            Files.writeString(cloud, "from the cloud")
            val outDir = Files.createTempDirectory("out")
            val dest = outDir.resolve("c.txt")
            Files.writeString(dest, "the file the user has")

            whileUnreadable(cloud) {
                assertFailsWith<java.io.IOException> { p.download("/c.txt", dest) }
            }

            assertEquals("the file the user has", Files.readString(dest), "a failed copy must not destroy what it was to replace")
            Files.list(outDir).use { names -> assertEquals(listOf("c.txt"), names.map { it.fileName.toString() }.toList()) }
        }

    @Test
    fun an_upload_that_fails_leaves_the_existing_remote_file() =
        runTest {
            val root = newRoot()
            val p = LocalFsProvider(root)
            p.authenticate()
            Files.writeString(root.resolve("r.txt"), "the old remote")
            val src = Files.createTempFile("src", ".txt")
            Files.writeString(src, "the new local")

            whileUnreadable(src) {
                assertFailsWith<java.io.IOException> { p.upload(src, "/r.txt") }
            }

            assertEquals("the old remote", Files.readString(root.resolve("r.txt")))
            Files.list(root).use { names -> assertEquals(listOf("r.txt"), names.map { it.fileName.toString() }.toList()) }
        }

    // ---- archive#1057: a move never overwrites ----

    @Test
    fun move_onto_an_existing_target_is_refused_and_changes_nothing() =
        runTest {
            val root = newRoot()
            val p = LocalFsProvider(root)
            p.authenticate()
            Files.writeString(root.resolve("a.txt"), "a")
            Files.writeString(root.resolve("b.txt"), "b")

            val e = assertFailsWith<ProviderException> { p.move("/a.txt", "/b.txt") }

            assertTrue("exists already" in (e.message ?: ""), e.message)
            assertEquals("a", Files.readString(root.resolve("a.txt")))
            assertEquals("b", Files.readString(root.resolve("b.txt")))
        }

    @Test
    fun move_to_a_free_target_moves_a_file_and_a_folder() =
        runTest {
            val root = newRoot()
            val p = LocalFsProvider(root)
            p.authenticate()
            Files.writeString(root.resolve("a.txt"), "a")
            Files.createDirectories(root.resolve("dir"))
            Files.writeString(root.resolve("dir/in.txt"), "in")

            assertEquals("/sub/b.txt", p.move("/a.txt", "/sub/b.txt").path)
            assertEquals("/moved", p.move("/dir", "/moved").path)

            assertEquals("a", Files.readString(root.resolve("sub/b.txt")))
            assertEquals("in", Files.readString(root.resolve("moved/in.txt")))
            assertFalse(Files.exists(root.resolve("a.txt")))
            assertFalse(Files.exists(root.resolve("dir")))
        }
}
