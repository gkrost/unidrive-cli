package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.localfs.LocalFsProvider
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.SyncEngine
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #218: `sync` and the commands that read `exclude_patterns` take the same accepted set, so they agree on what is
 * excluded. `sync` checked the patterns with the JDK's glob and dropped `My [Draft*` ("Missing ']'"), uploading the
 * file, while `status`, `verify`, `doctor` and the `get`/`ls` guard matched it unchecked and treated it as excluded.
 * `*.{tmp,bak}` is no alternation in the engine's syntax: every consumer matches it as written.
 *
 * `sync` itself ends its JVM (System.exit), so the test builds the engine the way the command does (the profile's
 * effective patterns, root and scope) and runs `status` and `verify` as commands.
 */
class ExcludePatternAgreementTest {
    private lateinit var base: Path
    private lateinit var syncRoot: Path
    private lateinit var remote: Path
    private val profile = "exclude-agree-${System.nanoTime()}"

    // Paths below the sync root; the scope is /In, so the top-level ones are out of scope.
    private val localFiles =
        listOf("/In/My [Draft 1.txt", "/In/keep.txt", "/In/x.{tmp,bak}", "/In/y.bak", "/My [Draft 2.txt", "/stray.txt")

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("ud-exclude-agree")
        syncRoot = Files.createDirectories(base.resolve("sync-root"))
        remote = Files.createDirectories(base.resolve("remote"))
        Files.createDirectories(base.resolve(profile))
        for (path in localFiles) {
            val file = syncRoot.resolve(path.removePrefix("/"))
            Files.createDirectories(file.parent)
            Files.writeString(file, path)
        }
        Files.writeString(
            base.resolve("config.toml"),
            """
            [providers.$profile]
            type = "localfs"
            mode = "mirror"
            sync_root = "${syncRoot.toString().replace('\\', '/')}"
            root_path = "${remote.toString().replace('\\', '/')}"
            sync_path = ["/In"]
            exclude_patterns = ["My [Draft*", "*.{tmp,bak}"]
            """.trimIndent() + "\n",
        )
    }

    @AfterTest
    fun tearDown() {
        runCatching { base.toFile().deleteRecursively() }
    }

    /** One pass of the engine as `sync` builds it (SyncCommand): the profile's effective patterns, root and scope. */
    private fun sync(config: SyncConfig) {
        val db = StateDatabase(base.resolve(profile).resolve("state.db")).also { it.initialize() }
        try {
            val provider = LocalFsProvider(remote)
            runBlocking { provider.authenticate() }
            val engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = config.syncRoot,
                    conflictPolicy = config.conflictPolicy,
                    excludePatterns = config.effectiveExcludePatterns(profile),
                    reporter = ProgressReporter.Silent,
                    syncPaths = config.syncPaths,
                    standingScope = config.syncPaths,
                    cacheRoot = Files.createDirectories(base.resolve("cache")),
                )
            runBlocking { engine.syncOnce() }
        } finally {
            db.close()
        }
    }

    /** Runs one command in-process and returns its exit code and what it printed on stdout. */
    private fun run(vararg args: String): Pair<Int, String> {
        val originalOut = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, "UTF-8"))
        val exit =
            try {
                CommandLine(Main()).execute("-c", base.toString(), "-p", profile, *args)
            } finally {
                System.setOut(originalOut)
            }
        return exit to captured.toString("UTF-8")
    }

    private fun remoteFiles(): Set<String> =
        Files.walk(remote).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { "/" + remote.relativize(it).toString().replace('\\', '/') }
                .toList()
                .toSet()
        }

    @Test
    fun `sync, status, verify, doctor and the get-ls guard agree on bracket and brace patterns`() {
        val config = SyncConfig.load(base.resolve("config.toml"), profile)
        val patterns = config.effectiveExcludePatterns(profile)
        sync(config)
        // The engine excludes what the patterns say as written: `My [Draft*` holds back the draft, `*.{tmp,bak}` only
        // the name that ends in `.{tmp,bak}` (y.bak is no match).
        assertEquals(setOf("/In/keep.txt", "/In/y.bak"), remoteFiles(), "what sync uploaded")
        val syncExcluded = setOf("/In/My [Draft 1.txt", "/In/x.{tmp,bak}")

        // status: the excluded draft outside the scope is not reported as left behind; the plain file is.
        val (statusExit, statusOut) = run("status")
        assertEquals(0, statusExit, statusOut)
        assertTrue("Out of scope on disk (left untouched): /stray.txt" in statusOut, statusOut)
        assertFalse("My [Draft 2.txt" in statusOut, statusOut)

        // verify: local, state and cloud cut by the same patterns, so the held-back files are no divergence.
        val (verifyExit, verifyOut) = run("verify")
        assertEquals(0, verifyExit, verifyOut)

        // get / ls (and, through the same guard, the pending-upload gate): excluded exactly where sync held back.
        val guard = cliExclusionGuard(config, profile, ProfileMode.MIRROR, inCloud = { false })
        val keepsLocal = PendingGate.keepsLocalRule(patterns, config.syncPaths)
        for (path in localFiles.filter { it.startsWith("/In/") }) {
            assertEquals(path in syncExcluded, guard.isExcludedPath(path), "get/ls guard on $path")
            assertEquals(path in syncExcluded, keepsLocal(path), "pending gate on $path")
        }

        // doctor: a file with no state row is an orphan unless the patterns exclude it, so only the plain stray counts.
        val db = StateDatabase(base.resolve(profile).resolve("state.db"), readOnly = true)
        db.initialize()
        val orphans =
            try {
                DoctorCommand().checkLocalOrphans(db, syncRoot, full = true, excludePatterns = patterns)
            } finally {
                db.close()
            }
        assertEquals(listOf("  /stray.txt"), orphans.detail.drop(1), "doctor's orphans: ${orphans.detail}")
    }
}
