package org.krost.unidrive.cli

import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test drive 2: the first `sync` of a fresh mirror profile whose `sync_path` is one folder ends with exit 0 when every
 * file came down. Exit 2 means a per-action failure; a clean first sync has none. The sync ends with System.exit, so it
 * runs in a forked JVM, with HOME in a scratch folder (logs, conflict backups, IPC socket).
 */
class FirstSyncExitCodeTest {
    private fun sync(base: Path): Pair<Int, String> {
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java")
        val pb =
            ProcessBuilder(
                javaBin.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "org.krost.unidrive.cli.MainKt",
                "-c",
                base.resolve("cfg").toString(),
                "-p",
                "first",
                "sync",
            ).redirectErrorStream(true)
        pb.environment()["HOME"] = base.resolve("home").toString()
        pb.environment()["XDG_RUNTIME_DIR"] = Files.createDirectories(base.resolve("run")).toString()
        val process = pb.start()
        val out = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the sync ended")
        return process.exitValue() to out
    }

    @Test
    fun `the first sync of a fresh mirror profile scoped to one folder exits 0`() {
        val base = TestScratch.directory("first-sync-exit")
        val remote = Files.createDirectories(base.resolve("remote"))
        val folder = Files.createDirectories(remote.resolve("drive2").resolve("F2"))
        for (n in listOf("a", "b", "c")) Files.writeString(folder.resolve("$n.txt"), "$n bytes")
        Files.writeString(remote.resolve("drive2").resolve("outside.txt"), "not in scope")
        val syncRoot = Files.createDirectories(base.resolve("root"))
        Files.createDirectories(base.resolve("home"))
        Files.writeString(
            Files.createDirectories(base.resolve("cfg")).resolve("config.toml"),
            """
            [providers.first]
            type = "localfs"
            mode = "mirror"
            sync_root = "${syncRoot.toString().replace('\\', '/')}"
            root_path = "${remote.toString().replace('\\', '/')}"
            sync_path = ["/drive2/F2"]
            """.trimIndent() + "\n",
        )

        val (exit, out) = sync(base)

        assertTrue("Sync complete: 3 downloaded" in out, out)
        assertEquals(0, exit, "a clean first sync exits 0; output:\n$out")
        val (again, out2) = sync(base)
        assertEquals(0, again, "and so does the next one; output:\n$out2")
    }
}
