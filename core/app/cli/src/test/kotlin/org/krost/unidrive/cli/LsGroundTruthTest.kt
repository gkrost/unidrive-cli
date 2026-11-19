package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ProviderException
import org.krost.unidrive.cli.LsCommand.Companion.DaemonLsView
import org.krost.unidrive.cli.LsCommand.Companion.ViewEntryParsed
import org.krost.unidrive.localfs.LocalFsProvider
import org.krost.unidrive.testing.TestScratch
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #825: `ls` says when it serves the daemon view, and `ls --live` words a missing path as its own. */
class LsGroundTruthTest {
    private lateinit var dir: Path
    private val originalOut = System.out
    private val originalErr = System.err
    private lateinit var out: ByteArrayOutputStream
    private lateinit var err: ByteArrayOutputStream

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("ls-ground-truth")
        out = ByteArrayOutputStream()
        err = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    /** Answers a folder walk through a missing or non-folder segment the way Internxt does. */
    private class InternxtShapedProvider(
        private val inner: LocalFsProvider,
        private val root: Path,
    ) : CloudProvider by inner {
        override suspend fun listChildren(path: String): List<CloudItem> {
            if (path != "/" && !Files.isDirectory(root.resolve(path.removePrefix("/")))) {
                val seg = path.substringAfterLast('/')
                throw ProviderException("Folder not found: $seg in $path")
            }
            return inner.listChildren(path)
        }

        override suspend fun getMetadata(path: String): CloudItem {
            if (!Files.exists(root.resolve(path.removePrefix("/")))) throw ProviderException("Item not found: $path")
            return inner.getMetadata(path)
        }
    }

    private fun internxtShaped() = InternxtShapedProvider(LocalFsProvider(dir), dir)

    @Test
    fun `a missing path under live gives no such remote path, not the provider error`() =
        runBlocking {
            assertNull(LsCommand.listLive(internxtShaped(), "/Nope"))
        }

    @Test
    fun `a file the folder walk refuses still lists under live`() =
        runBlocking {
            Files.writeString(dir.resolve("a.txt"), "hi")
            assertEquals(listOf("a.txt"), LsCommand.listLive(internxtShaped(), "/a.txt")?.map { it.name })
        }

    @Test
    fun `a listing failure that is not a not-found still propagates`() {
        val failing =
            object : CloudProvider by LocalFsProvider(dir) {
                override suspend fun listChildren(path: String): List<CloudItem> = throw ProviderException("HTTP 503", httpStatus = 503)
            }
        assertFailsWith<ProviderException> { runBlocking { LsCommand.listLive(failing, "/x") } }
    }

    @Test
    fun `ls --live on a missing path exits 1 with the command's own wording`() {
        val remote = TestScratch.directory("ls-ground-truth-remote")
        Files.writeString(
            dir.resolve("config.toml"),
            """
            |[providers.lstest]
            |type = "localfs"
            |sync_root = "${dir.resolve("root").toString().replace('\\', '/')}"
            |root_path = "${remote.toString().replace('\\', '/')}"
            """.trimMargin(),
        )
        val code = CommandLine(Main()).execute("-c", dir.toString(), "-p", "lstest", "ls", "--live", "/missing")
        assertEquals(1, code)
        assertTrue(err.toString(Charsets.UTF_8).contains("ls: no such remote path: /missing"), err.toString(Charsets.UTF_8))
        assertFalse(err.toString(Charsets.UTF_8).contains("Error:"), err.toString(Charsets.UTF_8))
    }

    @Test
    fun `the daemon-view note goes to stderr only`() {
        val view = DaemonLsView.Listing(listOf(ViewEntryParsed("/a.txt", 5, 1000, isFolder = false)))
        assertEquals(0, LsCommand().serveDaemonView("/", view))
        val stdout = out.toString(Charsets.UTF_8)
        assertTrue(stdout.startsWith("a.txt"), "stdout is the listing alone: $stdout")
        assertFalse(stdout.contains("daemon view"), stdout)
        assertEquals(LsCommand.DAEMON_VIEW_NOTE, err.toString(Charsets.UTF_8).trim())
    }

    @Test
    fun `the daemon view's missing path also carries the note`() {
        assertEquals(1, LsCommand().serveDaemonView("/gone", DaemonLsView.NoSuchPath))
        val stderr = err.toString(Charsets.UTF_8)
        assertTrue(stderr.contains(LsCommand.DAEMON_VIEW_NOTE) && stderr.contains("ls: no such remote path: /gone"), stderr)
        assertEquals("", out.toString(Charsets.UTF_8))
    }

    @Test
    fun `an undecidable daemon view prints nothing and falls through to live`() {
        assertNull(LsCommand().serveDaemonView("/x", DaemonLsView.Undecidable))
        assertEquals("", err.toString(Charsets.UTF_8) + out.toString(Charsets.UTF_8))
    }
}
