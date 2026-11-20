package org.krost.unidrive.cli

import org.krost.unidrive.sync.SyncConfig
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
import kotlin.test.assertTrue

/**
 * #845 (#749 finding 12): the interactive `profile add` command path itself, not only [interactiveWriteRefusal]. A scripted
 * [WizardConsole] answers the prompts; [beforeAnswer] runs when the LAST prompt is asked, i.e. between the prompts and the locked
 * write, where another process can change config.toml. A refused write must exit 1: if these tests are removed or loosened,
 * `call()` can return 0 on a refusal again (as before ae9067ca) and the decision function's own tests still pass.
 */
class InteractiveProfileAddCommandTest {
    private lateinit var dir: Path
    private lateinit var config: Path
    private lateinit var syncRoot: Path
    private lateinit var remote: Path
    private val originalOut = System.out
    private val originalErr = System.err
    private val err = ByteArrayOutputStream()

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("ud-profile-add-cmd")
        config = dir.resolve("config.toml")
        syncRoot = dir.resolve("new-root")
        remote = Files.createDirectories(dir.resolve("remote"))
        System.setOut(PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    private fun fwd(p: Path) = p.toString().replace('\\', '/')

    private fun provider(
        name: String,
        root: Path,
    ) = "\n[providers.$name]\ntype = \"localfs\"\nmode = \"mirror\"\nroot_path = \"${fwd(remote)}\"\nsync_root = \"${fwd(root)}\"\n"

    // A default profile is set, so the last prompt is the optional allow_drive_root one.
    private fun writeBaseConfig() {
        Files.writeString(config, "[general]\ndefault_profile = \"other\"\n" + provider("other", dir.resolve("other-root")))
    }

    private class ScriptedConsole(
        private val answers: ArrayDeque<String>,
        private val beforeLast: () -> Unit,
    ) : WizardConsole {
        override fun readLine(): String? {
            if (answers.size == 1) beforeLast()
            return answers.removeFirstOrNull()
        }

        override fun readPassword(prompt: String): CharArray = CharArray(0)
    }

    /** Runs `profile add` against [config] and returns its exit code. [beforeAnswer] fires at the last prompt. */
    private fun interactiveAdd(beforeAnswer: () -> Unit): Int {
        val localfs = SyncConfig.KNOWN_TYPES.toList().sorted().indexOf("localfs") + 1
        assertTrue(localfs > 0)
        // type, profile name, mode 2 = mirror, sync root, localfs root_path, allow_drive_root (blank)
        val answers = ArrayDeque(listOf("$localfs", "work", "2", fwd(syncRoot), fwd(remote), ""))
        val cmd = CommandLine(Main())
        cmd.subcommands.getValue("profile").subcommands.getValue("add").getCommand<ProfileAddCommand>().consoleSource =
            { ScriptedConsole(answers, beforeAnswer) }
        return cmd.execute("-c", dir.toString(), "profile", "add")
    }

    private fun stderr() = err.toString(Charsets.UTF_8)

    @Test
    fun `interactive profile add exits 0 and writes the profile when nothing intervenes`() {
        writeBaseConfig()
        assertEquals(0, interactiveAdd {}, stderr())
        assertTrue("[providers.work]" in Files.readString(config))
    }

    @Test
    fun `interactive profile add exits 1 when the config became unparseable before the write`() {
        writeBaseConfig()
        assertEquals(1, interactiveAdd { Files.writeString(config, "[general\nnot toml") }, stderr())
        assertTrue("config.toml could not be parsed" in stderr(), stderr())
    }

    @Test
    fun `interactive profile add exits 1 when the profile name was taken before the write`() {
        writeBaseConfig()
        val code = interactiveAdd { Files.writeString(config, Files.readString(config) + provider("work", dir.resolve("elsewhere"))) }
        assertEquals(1, code, stderr())
        assertTrue("Profile 'work' already exists." in stderr(), stderr())
    }

    @Test
    fun `interactive profile add exits 1 when the sync root was taken before the write`() {
        writeBaseConfig()
        val code = interactiveAdd { Files.writeString(config, Files.readString(config) + provider("rival", syncRoot)) }
        assertEquals(1, code, stderr())
        assertTrue(stderr().contains("Error:"), stderr())
        assertTrue("[providers.work]" !in Files.readString(config), "the refused profile is not written")
    }
}
