package org.krost.unidrive.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import picocli.CommandLine

/**
 * #500: the `trash purge --remote` refusals that fire before any profile or provider is touched — the remote
 * surface needs a target, and its knobs do not mix with the local emulation's. The happy path lives behind the
 * provider's own tests (InternxtTrashTest); a live purge asks on the console and is the owner's to run.
 */
class TrashRemoteCliTest {
    private fun runPurge(vararg args: String): Pair<Int, String> {
        val configDir = Files.createTempDirectory("trash-cli-test")
        val err = ByteArrayOutputStream()
        val originalErr = System.err
        return try {
            System.setErr(PrintStream(err, true, Charsets.UTF_8))
            val exit = CommandLine(Main()).execute("-c", configDir.toString(), *args)
            exit to err.toString(Charsets.UTF_8)
        } finally {
            System.setErr(originalErr)
            configDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `purge --remote without a target is refused`() {
        val (exit, err) = runPurge("trash", "purge", "--remote")
        assertEquals(2, exit, err)
        assertTrue(err.contains("--remote needs a target"), err)
    }

    @Test
    fun `purge --remote --all with ids is refused (one target kind at a time)`() {
        val (exit, err) = runPurge("trash", "purge", "--remote", "--all", "--id", "uuid-1")
        assertEquals(2, exit, err)
        assertTrue(err.contains("--all empties the whole remote trash"), err)
    }

    @Test
    fun `purge --id without --remote is refused (the ids are the remote trash's)`() {
        val (exit, err) = runPurge("trash", "purge", "--id", "uuid-1")
        assertEquals(2, exit, err)
        assertTrue(err.contains("--id/--path act on the provider's trash"), err)
    }

    @Test
    fun `purge --remote --retention-days is refused (the retention is the emulation's knob)`() {
        val (exit, err) = runPurge("trash", "purge", "--remote", "--all", "--retention-days", "5")
        assertEquals(2, exit, err)
        assertTrue(err.contains("the local emulation's knob"), err)
    }
}
