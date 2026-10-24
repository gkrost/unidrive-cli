package org.krost.unidrive.cli

import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ScanContext
import org.krost.unidrive.localfs.LocalFsProvider
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `sync` that cannot go on must end. The IPC server is already listening by then, and its accept loop is a child
 * of the command's runBlocking: blocked in a native accept that cancellation does not interrupt, it kept runBlocking
 * from returning, and `sync` sat silent forever. Every way out of the body has to close the server first: a throw
 * (a provider that refuses to authenticate) and a plain return (the watch loop stopping on an authentication error).
 */
class SyncEarlyFailureTest {
    private lateinit var base: Path

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("ud-sync-early-failure")
    }

    @AfterTest
    fun tearDown() {
        runCatching { base.toFile().deleteRecursively() }
    }

    /** The outcome of a `sync` run in-process on a thread of its own, joined with a limit: a hang is a failed assertion. */
    private class Outcome(
        val ended: Boolean,
        val exit: Int,
        val failure: Throwable?,
    )

    private fun writeConfig(
        profile: String,
        remote: Path,
    ) {
        val syncRoot = Files.createDirectories(base.resolve("sync-root"))
        Files.writeString(
            base.resolve("config.toml"),
            """
            [providers.$profile]
            type = "localfs"
            mode = "mirror"
            sync_root = "${syncRoot.toString().replace('\\', '/')}"
            root_path = "${remote.toString().replace('\\', '/')}"
            """.trimIndent(),
        )
    }

    private fun runSync(
        profile: String,
        provider: CloudProvider,
        vararg args: String,
    ): Outcome {
        val main =
            object : Main() {
                override fun createProvider(): CloudProvider = provider
            }
        val failure = AtomicReference<Throwable?>()
        val exit = AtomicInteger(-1)
        val run =
            Thread {
                val cli = CommandLine(main)
                cli.executionExceptionHandler =
                    CommandLine.IExecutionExceptionHandler { ex, _, _ ->
                        failure.set(ex)
                        1
                    }
                exit.set(cli.execute("-c", base.toString(), "-p", profile, "sync", *args))
            }.apply { isDaemon = true }
        run.start()
        run.join(30_000)
        return Outcome(!run.isAlive, exit.get(), failure.get())
    }

    @Test
    fun `a sync whose provider refuses to authenticate ends with the refusal instead of hanging`() {
        val profile = "early-fail-${System.nanoTime()}"
        val profileDir = Files.createDirectories(base.resolve(profile))
        // A localfs profile whose root has vanished after it was used: authenticate refuses it.
        val marker = Files.createFile(profileDir.resolve("localfs-root-seen"))
        writeConfig(profile, base.resolve("gone"))

        val outcome = runSync(profile, LocalFsProvider(base.resolve("gone"), marker))

        assertTrue(outcome.ended, "sync must end, not wait forever on the IPC accept loop")
        assertEquals(1, outcome.exit)
        assertTrue("root_not_creatable" in (outcome.failure?.message ?: ""), "got: ${outcome.failure}")
        assertFalse(Files.exists(base.resolve("gone")), "the vanished root must not be re-created")
    }

    // The watch loop answers an authentication error with a plain return, not a throw: the body is left without an
    // exception, so only a close on every way out (not on a throw alone) lets runBlocking finish.
    @Test
    fun `a watching sync that stops on an authentication error ends instead of hanging`() {
        val profile = "early-auth-${System.nanoTime()}"
        Files.createDirectories(base.resolve(profile))
        val remote = Files.createDirectories(base.resolve("remote"))
        writeConfig(profile, remote)

        val outcome = runSync(profile, RejectingProvider(LocalFsProvider(remote)), "--watch")

        assertTrue(outcome.ended, "sync --watch must end, not wait forever on the IPC accept loop")
        assertEquals(1, outcome.exit, "a watch that stopped on an authentication error is a failure for a supervisor")
    }

    /** A provider that signs in and then has every enumeration refused, as an expired token does. */
    private class RejectingProvider(
        private val inner: CloudProvider,
    ) : CloudProvider by inner {
        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = throw AuthenticationException("the token was rejected")

        override suspend fun listChildren(path: String): List<CloudItem> = throw AuthenticationException("the token was rejected")
    }
}
