package org.krost.unidrive.cli

import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A command that cannot do what it was asked ends with a non-zero exit code, so a script does not take the
 * refusal for success. Every command below printed its message and returned 0.
 */
class CliFailureExitCodeTest {
    private class Run(val exit: Int, val out: String, val err: String)

    private class Profile(val dir: Path, val syncRoot: Path, val remote: Path) {
        fun run(vararg args: String): Run {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val originalOut = System.out
            val originalErr = System.err
            try {
                System.setOut(PrintStream(out, true, Charsets.UTF_8))
                System.setErr(PrintStream(err, true, Charsets.UTF_8))
                val exit = CommandLine(Main()).execute("-c", dir.toString(), "-p", "test", *args)
                return Run(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }
        }

        fun seed(vararg rows: SyncEntry) {
            val db = StateDatabase(Files.createDirectories(dir.resolve("test")).resolve("state.db"))
            db.initialize()
            try {
                rows.forEach { db.upsertEntry(it) }
            } finally {
                db.close()
            }
        }
    }

    private fun withProfile(body: (Profile) -> Unit) {
        val dir = Files.createTempDirectory("cli-exit-codes")
        try {
            val syncRoot = Files.createDirectories(dir.resolve("local"))
            val remote = Files.createDirectories(dir.resolve("remote"))
            Files.writeString(
                dir.resolve("config.toml"),
                """
                [providers.test]
                type = "localfs"
                sync_root = "${syncRoot.toString().replace('\\', '/')}"
                root_path = "${remote.toString().replace('\\', '/')}"
                """.trimIndent() + "\n",
            )
            body(Profile(dir, syncRoot, remote))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun row(
        path: String,
        isFolder: Boolean = false,
        hydrated: Boolean = false,
        localMtime: Long? = null,
        remoteId: String? = "r-$path",
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = 1,
        remoteModified = null,
        localMtime = localMtime,
        localSize = if (hydrated) 1 else null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = Instant.now(),
    )

    @Test
    fun `trash restore of a path that is not in the trash exits 1`() =
        withProfile { p ->
            val r = p.run("trash", "restore", "/report.txt")
            assertEquals(1, r.exit, r.err)
            assertTrue("Not found in trash" in r.err, r.err)
        }

    @Test
    fun `trash restore of a trashed path exits 0`() =
        withProfile { p ->
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
            val trashed = p.syncRoot.resolve(".unidrive-trash").resolve(stamp).resolve("report.txt")
            Files.createDirectories(trashed.parent)
            Files.writeString(trashed, "x")
            val r = p.run("trash", "restore", "/report.txt")
            assertEquals(0, r.exit, r.err)
            assertTrue(Files.exists(p.syncRoot.resolve("report.txt")))
        }

    @Test
    fun `versions restore of a version that does not exist exits 1`() =
        withProfile { p ->
            val r = p.run("versions", "restore", "/report.txt", "2026-04-13T18:43:12Z")
            assertEquals(1, r.exit, r.err)
            assertTrue("Version not found" in r.err, r.err)
        }

    @Test
    fun `free of an untracked path exits 1`() =
        withProfile { p ->
            val r = p.run("free", "/untracked.txt")
            assertEquals(1, r.exit, r.err)
            assertTrue("Not tracked" in r.err, r.err)
        }

    @Test
    fun `free of a file modified since the last sync exits 1 and leaves it alone`() =
        withProfile { p ->
            val file = p.syncRoot.resolve("a.txt")
            Files.writeString(file, "x")
            val mtime = Files.getLastModifiedTime(file).toMillis()
            p.seed(row("/a.txt", hydrated = true, localMtime = mtime - 60_000))
            val r = p.run("free", "/a.txt")
            assertEquals(1, r.exit, r.err)
            assertTrue("modified since last sync" in r.err, r.err)
            assertEquals("x", Files.readString(file))
        }

    // archive#1047: a row written for a file that was never uploaded (no cloud id, hydrated) passes the mtime check;
    // dehydrating it truncated the only copy.
    @Test
    fun `free refuses a file that was never uploaded and leaves its bytes`() =
        withProfile { p ->
            val file = p.syncRoot.resolve("new.txt")
            Files.writeString(file, "only copy")
            p.seed(row("/new.txt", hydrated = true, localMtime = Files.getLastModifiedTime(file).toMillis(), remoteId = null))
            val r = p.run("free", "/new.txt")
            assertEquals(1, r.exit, r.err)
            assertTrue("Not uploaded yet" in r.err, r.err)
            assertEquals("only copy", Files.readString(file))
        }

    @Test
    fun `free of a path that is already dehydrated exits 0`() =
        withProfile { p ->
            p.seed(row("/a.txt", hydrated = false))
            val r = p.run("free", "/a.txt")
            assertEquals(0, r.exit, r.err)
            assertTrue("Already dehydrated" in r.out, r.out)
        }

    @Test
    fun `get on a folder whose downloads were skipped exits 1`() =
        withProfile { p ->
            // The remote holds neither file, so each download fails and is reported as skipped.
            p.seed(row("/docs", isFolder = true), row("/docs/a.txt"))
            val r = p.run("get", "/docs")
            assertTrue("1 skipped" in r.out, r.out)
            assertEquals(1, r.exit, r.err)
        }
}
