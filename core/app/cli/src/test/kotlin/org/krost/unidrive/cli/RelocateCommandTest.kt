package org.krost.unidrive.cli

import kotlinx.coroutines.flow.toList
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.CloudRelocator
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.MigrateEvent
import org.krost.unidrive.sync.MigrationManifest
import org.krost.unidrive.sync.RelocateMdc
import org.slf4j.MDC
import picocli.CommandLine
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelocateCommandTest {
    private val cmd = CommandLine(Main())
    private val relocateCmd get() = cmd.subcommands["relocate"]!!

    // -- Command registration ---------------------------------------------------

    @Test
    fun `relocate command is registered`() {
        assertNotNull(cmd.subcommands["relocate"], "relocate subcommand should be registered")
    }

    // -- Option flags -----------------------------------------------------------

    @Test
    fun `--from option is registered and required`() {
        val spec = relocateCmd.commandSpec
        val opt = spec.options().first { "--from" in it.names() }
        assertTrue(opt.required(), "--from must be required")
    }

    @Test
    fun `--from has -f short alias`() {
        val allNames = relocateCmd.commandSpec.options().flatMap { it.names().toList() }
        assertTrue("-f" in allNames)
    }

    @Test
    fun `--to option is registered and required`() {
        val spec = relocateCmd.commandSpec
        val opt = spec.options().first { "--to" in it.names() }
        assertTrue(opt.required(), "--to must be required")
    }

    @Test
    fun `--to has -t short alias`() {
        val allNames = relocateCmd.commandSpec.options().flatMap { it.names().toList() }
        assertTrue("-t" in allNames)
    }

    @Test
    fun `--delete-source flag is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--delete-source" in options)
    }

    @Test
    fun `--delete-source defaults to false`() {
        val opt = relocateCmd.commandSpec.options().first { "--delete-source" in it.names() }
        assertEquals(false, opt.defaultValue()?.toBooleanStrictOrNull() ?: false)
    }

    @Test
    fun `--source-path option is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--source-path" in options)
    }

    @Test
    fun `--target-path option is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--target-path" in options)
    }


    @Test
    fun `--force flag is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--force" in options)
    }

    // -- UD-271: parent --verbose visible on every subcommand spec -------------
    //
    // scope = ScopeType.INHERIT on the top-level @Option propagates the flag
    // to every subcommand at picocli parse time. Pre-fix `unidrive auth -v`
    // and `unidrive relocate -v --from X` rejected the flag with a misleading
    // "Possible solutions: --version" hint. The tests below pin the post-fix
    // contract: the option is in the subcommand's effective option list.

    @Test
    fun `UD-271 - relocate subcommand inherits --verbose from parent`() {
        val names = relocateCmd.commandSpec.options().flatMap { it.names().toList() }
        assertTrue("--verbose" in names, "relocate should accept --verbose; got=$names")
        assertTrue("-v" in names, "relocate should accept -v; got=$names")
    }

    @Test
    fun `UD-271 - --verbose parses successfully on relocate subcommand`() {
        // CommandLine.parseArgs returns successfully when the flag is in the
        // subcommand's spec; throws UnmatchedArgumentException otherwise.
        // Pre-fix this threw "Unknown option: '--verbose'".
        val parsed =
            cmd.parseArgs(
                "relocate",
                "--from",
                "src",
                "--to",
                "dst",
                "--verbose",
            )
        assertNotNull(parsed, "parseArgs should succeed when --verbose is on the subcommand")
    }

    @Test
    fun `--force defaults to false`() {
        val opt = relocateCmd.commandSpec.options().first { "--force" in it.names() }
        assertEquals(false, opt.defaultValue()?.toBooleanStrictOrNull() ?: false)
    }

    // -- formatSize helper ------------------------------------------------------

    @Test
    fun `formatSize formats bytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("0 B", method.invoke(cmd, 0L))
        assertEquals("512 B", method.invoke(cmd, 512L))
    }

    @Test
    fun `formatSize formats kibibytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("1.0 KiB", method.invoke(cmd, 1024L))
        assertEquals("1.5 KiB", method.invoke(cmd, 1536L))
    }

    @Test
    fun `formatSize formats mebibytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("1.0 MiB", method.invoke(cmd, 1024L * 1024))
    }

    @Test
    fun `formatSize formats gibibytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("1.0 GiB", method.invoke(cmd, 1024L * 1024 * 1024))
    }

    @Test
    fun `formatSize formats tebibytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("1.0 TiB", method.invoke(cmd, 1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun `formatSize prints a negative count as plain bytes, as it always did`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("-5000 B", method.invoke(cmd, -5000L))
        assertEquals("-1 B", method.invoke(cmd, -1L))
    }

    // -- UD-294: relocate seeds profile + scan MDC ------------------------------
    //
    // Pre-fix: relocate never put `profile` or `scan` into MDC, so the
    // runBlocking(MDCContext()) snapshots captured an empty value and every
    // log line rendered [<sha>] [*] [-------] (132 k unfilterable lines on
    // the 2026-04-29 baseline). UD-284 fixed MDCContext propagation; this
    // ticket fills the snapshot that propagates.

    @BeforeTest
    fun clearRelocateMdc() {
        MDC.remove("profile")
        MDC.remove("scan")
    }

    @AfterTest
    fun clearRelocateMdcAfter() {
        MDC.remove("profile")
        MDC.remove("scan")
    }

    @Test
    fun `UD-294 - newMigId returns mig-prefix and 8 hex chars`() {
        val migId = RelocateMdc.newMigId()
        assertTrue(
            migId.matches(Regex("mig-[0-9a-f]{8}")),
            "expected `mig-<8 hex chars>`; was '$migId'",
        )
    }

    @Test
    fun `UD-294 - newMigId is unique across calls`() {
        // 8 hex chars = 32 bits = collision probability over 100 calls is
        // ~5.7e-7. We assert disjoint here as a smoke test that the UUID
        // randomness is wired up.
        val ids = (1..100).map { RelocateMdc.newMigId() }.toSet()
        assertEquals(100, ids.size, "expected all 100 mig ids unique")
    }

    @Test
    fun `UD-294 - withRelocateMdc seeds profile=from+to and scan=migId during block`() {
        var capturedProfile: String? = null
        var capturedScan: String? = null
        RelocateMdc.withRelocateMdc("src-fs", "dst-webdav", "mig-deadbeef") {
            capturedProfile = MDC.get("profile")
            capturedScan = MDC.get("scan")
        }
        assertEquals("src-fs+dst-webdav", capturedProfile)
        assertEquals("mig-deadbeef", capturedScan)
    }

    @Test
    fun `UD-294 - withRelocateMdc clears MDC after block when no prior values`() {
        RelocateMdc.withRelocateMdc("a", "b", "mig-00000000") { }
        assertNull(MDC.get("profile"), "profile must be cleared post-block")
        assertNull(MDC.get("scan"), "scan must be cleared post-block")
    }

    @Test
    fun `UD-294 - withRelocateMdc restores prior MDC values on normal exit`() {
        MDC.put("profile", "outer-profile")
        MDC.put("scan", "outer-scan")
        try {
            RelocateMdc.withRelocateMdc("a", "b", "mig-11111111") { }
            assertEquals("outer-profile", MDC.get("profile"), "outer profile must be restored")
            assertEquals("outer-scan", MDC.get("scan"), "outer scan must be restored")
        } finally {
            MDC.remove("profile")
            MDC.remove("scan")
        }
    }

    @Test
    fun `UD-294 - withRelocateMdc restores prior MDC values when block throws`() {
        MDC.put("profile", "outer-profile")
        MDC.put("scan", "outer-scan")
        try {
            try {
                RelocateMdc.withRelocateMdc("a", "b", "mig-22222222") {
                    throw RuntimeException("boom")
                }
            } catch (e: RuntimeException) {
                // expected
            }
            assertEquals("outer-profile", MDC.get("profile"), "outer profile must be restored on throw")
            assertEquals("outer-scan", MDC.get("scan"), "outer scan must be restored on throw")
        } finally {
            MDC.remove("profile")
            MDC.remove("scan")
        }
    }

    // -- gkrost/unidrive-cli#61: the delete pass is manifest-driven -------------------------------
    //
    // Pre-fix, `deleteSourceRecursive` walked the whole source drive with delta() and deleted
    // everything under a string prefix: a source path of /docs also removed /docs-old, and a
    // file whose upload failed was deleted with the rest. The pass now deletes exactly what
    // the migration recorded in the MigrationManifest, by id, behind a gate that refuses the
    // whole pass unless the migration completed cleanly.

    @Test
    fun `delete pass keeps docs-old when the migrated root is docs`() {
        val source = FakeCloudProvider()
        val target = FakeCloudProvider()
        source.seedFolder("/docs-old")
        source.seedFile("/docs/a.txt", "a")
        source.seedFile("/docs-old/b.txt", "b")
        source.seedFile("/keep.txt", "k")
        source.metadataOverride["/docs"] = folderItem("/docs", id = "root-docs")

        val (manifest, outcome) = migrateWithManifest(source, target)
        deletePass(source, manifest, "/docs", outcome)

        assertEquals(
            listOf("/docs/a.txt", "/docs"),
            source.deletedPaths,
            "exactly the migrated file and its root folder are deleted, root last",
        )
        assertTrue(target.deletedPaths.isEmpty(), "nothing is deleted on the target")
    }

    @Test
    fun `delete pass keeps Documents when the migrated root is Docs`() {
        val source = FakeCloudProvider()
        val target = FakeCloudProvider()
        source.seedFile("/Docs/a.txt", "a")
        source.seedFolder("/Documents")
        source.seedFile("/Documents/b.txt", "b")
        source.metadataOverride["/Docs"] = folderItem("/Docs", id = "root-docs")

        val (manifest, outcome) = migrateWithManifest(source, target, sourcePath = "/Docs")
        deletePass(source, manifest, "/Docs", outcome)

        assertEquals(setOf("/Docs/a.txt", "/Docs"), source.deletedPaths.toSet())
        assertTrue(
            "/Documents/b.txt" !in source.deletedPaths,
            "a sibling whose name only extends the source name stays",
        )
    }

    @Test
    fun `delete pass is refused with nothing deleted when a transfer failed`() {
        val source = FakeCloudProvider()
        val target = FakeCloudProvider()
        source.seedFile("/docs/good.txt", "good")
        source.seedFile("/docs/bad.txt", "bad")
        target.uploadThrowByPath["/docs/bad.txt"] = ProviderException("network down")
        source.metadataOverride["/docs"] = folderItem("/docs")

        val (manifest, outcome) = migrateWithManifest(source, target)
        val ex = assertFailsWith<IllegalStateException> { deletePass(source, manifest, "/docs", outcome) }

        assertTrue(
            "/docs/bad.txt" in (ex.message ?: ""),
            "the refusal names the file that failed to migrate; got: ${ex.message}",
        )
        assertTrue(source.deletedPaths.isEmpty(), "nothing is deleted when the gate refuses")
        assertTrue("/docs/bad.txt" !in target.uploadedPaths, "the target never received the failed file")
    }

    @Test
    fun `delete pass is refused when a skip was size-only, naming the hash-equivalence issue`() {
        val source = FakeCloudProvider()
        val target = FakeCloudProvider()
        source.seedFile("/docs/a.txt", "0123456789")
        target.childrenByParent["/docs"] = listOf(fileItem("/docs/a.txt", size = 10, hash = null, id = "tgt-1"))
        source.metadataOverride["/docs"] = folderItem("/docs")

        val (manifest, outcome) = migrateWithManifest(source, target)
        val ex = assertFailsWith<IllegalStateException> { deletePass(source, manifest, "/docs", outcome) }

        assertTrue(
            "#62" in (ex.message ?: ""),
            "the refusal points at the hash-comparison issue; got: ${ex.message}",
        )
        assertTrue(source.deletedPaths.isEmpty(), "nothing is deleted when the gate refuses")
    }

    @Test
    fun `delete pass treats a hash-equivalent skip as migrated`() {
        val source = FakeCloudProvider()
        val target = FakeCloudProvider()
        source.childrenByParent["/docs"] = listOf(fileItem("/docs/a.txt", size = 10, hash = "same"))
        source.files["/docs/a.txt"] = ByteArray(10)
        target.childrenByParent["/docs"] = listOf(fileItem("/docs/a.txt", size = 10, hash = "same", id = "tgt-1"))
        source.metadataOverride["/docs"] = folderItem("/docs")

        val (manifest, outcome) = migrateWithManifest(source, target)
        deletePass(source, manifest, "/docs", outcome)

        assertTrue("/docs/a.txt" in source.deletedPaths, "a hash-proven equivalent was migrated; its source copy goes")
    }

    @Test
    fun `delete pass deletes by id and never walks the drive again`() {
        val source = FakeCloudProvider()
        val target = FakeCloudProvider()
        source.seedFile("/docs/a.txt", "a")
        source.metadataOverride["/docs"] = folderItem("/docs", id = "root-docs")

        val (manifest, outcome) = migrateWithManifest(source, target)
        val deltaCallsBefore = source.deltaCalls
        deletePass(source, manifest, "/docs", outcome)

        assertEquals(deltaCallsBefore, source.deltaCalls, "the delete pass must not re-walk the drive")
        assertEquals(
            listOf("src-/docs/a.txt" to "/docs/a.txt", "root-docs" to "/docs"),
            source.deleteByIdCalls,
            "every delete is addressed by the id the migration recorded",
        )
    }

    @Test
    fun `delete pass empties folders deepest-first and removes the source root last`() {
        val source = FakeCloudProvider()
        val target = FakeCloudProvider()
        source.seedFolder("/docs/sub")
        source.seedFolder("/docs/sub/deeper")
        source.seedFile("/docs/a.txt", "a")
        source.seedFile("/docs/sub/b.txt", "b")
        source.seedFile("/docs/sub/deeper/c.txt", "c")
        source.metadataOverride["/docs"] = folderItem("/docs", id = "root-docs")

        val (manifest, outcome) = migrateWithManifest(source, target)
        deletePass(source, manifest, "/docs", outcome)

        val order = source.deletedPaths
        assertEquals("/docs", order.last(), "the source root folder is removed last")
        val deepest = order.indexOf("/docs/sub/deeper")
        val mid = order.indexOf("/docs/sub")
        assertTrue(deepest in order.indices, "the deepest folder is deleted; order=$order")
        assertTrue(mid > deepest, "folders go deepest-first; order=$order")
        for (file in listOf("/docs/a.txt", "/docs/sub/b.txt", "/docs/sub/deeper/c.txt")) {
            assertTrue(
                order.indexOf(file) in 0 until deepest,
                "files are deleted before any folder; order=$order",
            )
        }
    }

    @Test
    fun `delete pass survives a trailing slash in the source path`() {
        val source = FakeCloudProvider()
        val target = FakeCloudProvider()
        source.seedFile("/docs/a.txt", "a")
        source.metadataOverride["/docs"] = folderItem("/docs", id = "root-docs")

        val (manifest, outcome) = migrateWithManifest(source, target)
        deletePass(source, manifest, "/docs/", outcome)

        assertEquals(setOf("/docs/a.txt", "/docs"), source.deletedPaths.toSet())
    }

    @Test
    fun `delete pass uses the provider-reported root path, not the typed string`() {
        val source = FakeCloudProvider()
        val target = FakeCloudProvider()
        // The walk asks for /docs and the provider reports the item at its own spelling /Docs/a.txt.
        source.childrenByParent["/docs"] = listOf(fileItem("/Docs/a.txt", size = 1))
        source.files["/docs/a.txt"] = "a".toByteArray()
        source.metadataOverride["/docs"] = folderItem("/Docs", id = "root-docs")

        val (manifest, outcome) = migrateWithManifest(source, target, sourcePath = "/docs")
        deletePass(source, manifest, "/docs", outcome)

        assertEquals(
            setOf("/Docs/a.txt", "/Docs"),
            source.deletedPaths.toSet(),
            "boundary and deletes follow the provider's own spelling of the subtree",
        )
    }

    @Test
    fun `a failed file delete is reported per path and the rest still deletes`() {
        val source = FakeCloudProvider()
        val target = FakeCloudProvider()
        source.seedFile("/docs/a.txt", "a")
        source.seedFile("/docs/b.txt", "b")
        source.deleteThrowByPath["/docs/a.txt"] = ProviderException("412 precondition failed")
        source.metadataOverride["/docs"] = folderItem("/docs", id = "root-docs")

        val (manifest, outcome) = migrateWithManifest(source, target)
        val ex = assertFailsWith<IllegalStateException> { deletePass(source, manifest, "/docs", outcome) }

        assertTrue(
            "/docs/a.txt" in (ex.message ?: ""),
            "the report names the path that could not be deleted; got: ${ex.message}",
        )
        assertTrue(source.deletedPaths.contains("/docs/b.txt"), "the other files are still deleted")
    }

    @Test
    fun `delete pass deletes the whole migrated subtree and only it`() {
        val srcRoot = java.nio.file.Files.createTempDirectory("ud-relocate-61-src")
        val dstRoot = java.nio.file.Files.createTempDirectory("ud-relocate-61-dst")
        try {
            java.nio.file.Files.createDirectories(srcRoot.resolve("docs/sub"))
            java.nio.file.Files.createDirectories(srcRoot.resolve("docs-old"))
            java.nio.file.Files.writeString(srcRoot.resolve("docs/a.txt"), "a")
            java.nio.file.Files.writeString(srcRoot.resolve("docs/sub/b.txt"), "b")
            java.nio.file.Files.writeString(srcRoot.resolve("docs-old/c.txt"), "c")
            java.nio.file.Files.writeString(srcRoot.resolve("keep.txt"), "outside the prefix")
            val source = org.krost.unidrive.localfs.LocalFsProvider(srcRoot)
            val target = org.krost.unidrive.localfs.LocalFsProvider(dstRoot)

            val (manifest, outcome) = migrateWithManifest(source, target, sourcePath = "/docs")
            deletePass(source, manifest, "/docs", outcome)

            assertTrue(java.nio.file.Files.exists(dstRoot.resolve("docs/a.txt")), "the target received the migration")
            assertTrue(java.nio.file.Files.exists(dstRoot.resolve("docs/sub/b.txt")))
            assertTrue(!java.nio.file.Files.exists(srcRoot.resolve("docs")), "the migrated subtree is gone from the source")
            assertTrue(
                java.nio.file.Files.exists(srcRoot.resolve("docs-old/c.txt")),
                "a sibling whose name only extends the source name survives",
            )
            assertTrue(java.nio.file.Files.exists(srcRoot.resolve("keep.txt")), "a file outside the subtree survives")
        } finally {
            srcRoot.toFile().deleteRecursively()
            dstRoot.toFile().deleteRecursively()
        }
    }

    // -- the full command path (gkrost/unidrive-cli#653): relocate is on again, localfs to localfs, no cloud ------------

    private class CliRun(
        val exit: Int,
        val out: String,
        val err: String,
    )

    /** Runs `unidrive -c <configDir> <args>` in process with `stdin` as the confirmation answer. */
    private fun runCli(
        configDir: java.nio.file.Path,
        stdin: String,
        vararg args: String,
    ): CliRun {
        val out = java.io.ByteArrayOutputStream()
        val err = java.io.ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        val origIn = System.`in`
        val exit =
            try {
                System.setOut(java.io.PrintStream(out, true, Charsets.UTF_8))
                System.setErr(java.io.PrintStream(err, true, Charsets.UTF_8))
                System.setIn(java.io.ByteArrayInputStream(stdin.toByteArray()))
                val c = CommandLine(Main())
                c.executionExceptionHandler = cliExecutionExceptionHandler
                c.execute("-c", configDir.toString(), *args)
            } finally {
                System.setOut(origOut)
                System.setErr(origErr)
                System.setIn(origIn)
            }
        return CliRun(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    /** A temp dir with `src/`, `dst/` and a config naming them as the localfs profiles `src` and `dst`. */
    private fun withLocalProfiles(body: (src: java.nio.file.Path, dst: java.nio.file.Path, config: java.nio.file.Path) -> Unit) {
        val base = java.nio.file.Files.createTempDirectory("ud-relocate-653")
        try {
            val src = java.nio.file.Files.createDirectories(base.resolve("src"))
            val dst = java.nio.file.Files.createDirectories(base.resolve("dst"))
            val config = java.nio.file.Files.createDirectories(base.resolve("config"))
            java.nio.file.Files.writeString(
                config.resolve("config.toml"),
                """
                |[providers.src]
                |type = "localfs"
                |root_path = "${toml(src)}"
                |
                |[providers.dst]
                |type = "localfs"
                |root_path = "${toml(dst)}"
                """.trimMargin() + "\n",
            )
            body(src, dst, config)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun write(
        root: java.nio.file.Path,
        rel: String,
        content: String,
    ) {
        val f = root.resolve(rel)
        java.nio.file.Files.createDirectories(f.parent)
        java.nio.file.Files.writeString(f, content)
    }

    @Test
    fun `relocate is switched on`() {
        assertTrue(RELOCATE_ENABLED, "the four data-loss defects (#56, #61, #62, #63) are fixed, see #653")
    }

    @Test
    fun `relocate end to end keeps siblings and deletes only the migrated subtree`() {
        withLocalProfiles { src, dst, config ->
            write(src, "docs/a.txt", "a")
            write(src, "docs-old/b.txt", "b")
            write(src, "keep.txt", "k")

            val r =
                runCli(
                    config,
                    "yes\n",
                    "relocate",
                    "--from",
                    "src",
                    "--to",
                    "dst",
                    "--source-path",
                    "/docs/",
                    "--target-path",
                    "/docs",
                    "--delete-source",
                )

            assertEquals(0, r.exit, "relocate exits 0; stderr: ${r.err}; stdout: ${r.out}")
            assertEquals("a", java.nio.file.Files.readString(dst.resolve("docs/a.txt")), "the target received the migration")
            assertTrue(!java.nio.file.Files.exists(src.resolve("docs")), "the migrated subtree is gone from the source")
            assertTrue(
                java.nio.file.Files.exists(src.resolve("docs-old/b.txt")),
                "a sibling whose name only extends the source name survives (#61)",
            )
            assertTrue(java.nio.file.Files.exists(src.resolve("keep.txt")), "a file outside the subtree survives")
        }
    }

    @Test
    fun `relocate without --delete-source copies and leaves the source untouched`() {
        withLocalProfiles { src, dst, config ->
            write(src, "x/one.txt", "1")
            write(src, "two.txt", "22")

            val r = runCli(config, "", "relocate", "--from", "src", "--to", "dst")

            assertEquals(0, r.exit, "stderr: ${r.err}; stdout: ${r.out}")
            assertEquals("1", java.nio.file.Files.readString(dst.resolve("x/one.txt")))
            assertEquals("22", java.nio.file.Files.readString(dst.resolve("two.txt")))
            assertEquals("1", java.nio.file.Files.readString(src.resolve("x/one.txt")))
            assertEquals("22", java.nio.file.Files.readString(src.resolve("two.txt")))
        }
    }

    @Test
    fun `a declined confirmation aborts the delete pass and nothing is deleted from the source`() {
        withLocalProfiles { src, dst, config ->
            write(src, "docs/a.txt", "a")
            write(src, "keep.txt", "k")

            val r = runCli(config, "no\n", "relocate", "--from", "src", "--to", "dst", "--delete-source")

            assertTrue(r.out.contains("Aborted"), "the run says it aborted; stdout: ${r.out}; stderr: ${r.err}")
            assertTrue(!r.out.contains("Deleting source files"), "the delete pass never starts; stdout: ${r.out}")
            assertEquals("a", java.nio.file.Files.readString(src.resolve("docs/a.txt")), "the source copy survives")
            assertEquals("k", java.nio.file.Files.readString(src.resolve("keep.txt")), "the source copy survives")
            assertEquals("a", java.nio.file.Files.readString(dst.resolve("docs/a.txt")), "the copy itself was made")
        }
    }

    @Test
    fun `a run over a partly migrated target completes it without duplicates`() {
        withLocalProfiles { src, dst, config ->
            write(src, "x/one.txt", "1")
            write(src, "x/two.txt", "22")
            write(src, "three.txt", "333")
            // An interrupted earlier run left one file behind.
            write(dst, "x/one.txt", "1")

            val r = runCli(config, "", "relocate", "--from", "src", "--to", "dst")

            assertEquals(0, r.exit, "stderr: ${r.err}; stdout: ${r.out}")
            val arrived =
                java.nio.file.Files.walk(dst).use { s ->
                    s.filter { java.nio.file.Files.isRegularFile(it) }.map { dst.relativize(it).toString().replace('\\', '/') }.sorted().toList()
                }
            assertEquals(listOf("three.txt", "x/one.txt", "x/two.txt"), arrived, "the target holds each file once, nothing extra")
            assertEquals("1", java.nio.file.Files.readString(dst.resolve("x/one.txt")))
            assertEquals("22", java.nio.file.Files.readString(dst.resolve("x/two.txt")))
            assertEquals("333", java.nio.file.Files.readString(dst.resolve("three.txt")))
        }
    }

    @Test
    fun `63 - same-named files in different folders each arrive with their own bytes and the source is then deleted`() {
        withLocalProfiles { src, dst, config ->
            // Same leaf, same size, different bytes: a shared scratch name would let one overwrite the other.
            write(src, "a/same.bin", "AAAAAAAA")
            write(src, "b/same.bin", "BBBBBBBB")
            write(src, "c/same.bin", "CCCCCCCC")

            val r = runCli(config, "yes\n", "relocate", "--from", "src", "--to", "dst", "--delete-source")

            assertEquals(0, r.exit, "stderr: ${r.err}; stdout: ${r.out}")
            assertEquals("AAAAAAAA", java.nio.file.Files.readString(dst.resolve("a/same.bin")))
            assertEquals("BBBBBBBB", java.nio.file.Files.readString(dst.resolve("b/same.bin")))
            assertEquals("CCCCCCCC", java.nio.file.Files.readString(dst.resolve("c/same.bin")))
            assertTrue(!java.nio.file.Files.exists(src.resolve("a/same.bin")), "a transferred file is deleted from the source")
        }
    }

    @Test
    fun `62 - a target file of the same size and other bytes is not taken for the migrated file, the source is kept`() {
        withLocalProfiles { src, dst, config ->
            write(src, "f.txt", "source-bytes")
            write(dst, "f.txt", "target-bytes") // same size (12), different content
            write(src, "g.txt", "g")

            val r = runCli(config, "yes\n", "relocate", "--from", "src", "--to", "dst", "--delete-source")

            assertTrue(r.exit != 0, "the delete pass must be refused; stdout: ${r.out}")
            assertEquals("source-bytes", java.nio.file.Files.readString(src.resolve("f.txt")), "the source copy survives")
            assertEquals("g", java.nio.file.Files.readString(src.resolve("g.txt")), "nothing else was deleted either")
            assertEquals("target-bytes", java.nio.file.Files.readString(dst.resolve("f.txt")), "the target's own file is not overwritten")
        }
    }

    @Test
    fun `61 - a failed transfer refuses the delete pass and nothing is deleted from the source`() {
        withLocalProfiles { src, dst, config ->
            write(src, "ok.txt", "ok")
            write(src, "blocked.txt", "blocked")
            // A non-empty directory where the target file must go makes that one upload fail.
            write(dst, "blocked.txt/inner/x", "x")

            val r = runCli(config, "yes\n", "relocate", "--from", "src", "--to", "dst", "--delete-source", "--force")

            assertTrue(r.exit != 0, "relocate must fail; stdout: ${r.out}; stderr: ${r.err}")
            assertEquals("ok", java.nio.file.Files.readString(src.resolve("ok.txt")), "no source file is deleted")
            assertEquals("blocked", java.nio.file.Files.readString(src.resolve("blocked.txt")), "no source file is deleted")
        }
    }

    @Test
    fun `56 - a remote name with backslashes and dots stays below the scratch dir and arrives under its own name`() {
        // A name with a backslash is only a legal file name on POSIX; on Windows the host cannot hold it.
        if (System.getProperty("os.name").lowercase().contains("win")) {
            println("skipped: a backslash cannot be part of a Windows file name; CloudRelocatorTest covers the staging name")
            return
        }
        val tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
        val escapeName = "unidrive-653-escape-${System.nanoTime()}.txt"
        withLocalProfiles { src, dst, config ->
            val hostile = "..\\..\\$escapeName"
            write(src, hostile, "payload")

            val r = runCli(config, "yes\n", "relocate", "--from", "src", "--to", "dst", "--delete-source")

            assertEquals(0, r.exit, "stderr: ${r.err}; stdout: ${r.out}")
            assertEquals("payload", java.nio.file.Files.readString(dst.resolve(hostile)))
            assertTrue(!java.nio.file.Files.exists(tmp.resolve(escapeName)), "nothing was written outside the scratch dir")
            assertTrue(!java.nio.file.Files.exists(tmp.parent.resolve(escapeName)), "nothing was written outside the scratch dir")
        }
    }

    @Test
    fun `relocate with an unknown profile fails with a non-zero exit and creates nothing`() {
        val dir = java.nio.file.Files.createTempDirectory("ud-relocate-unknown")
        try {
            val keep = java.nio.file.Files.writeString(dir.resolve("keep.txt"), "must survive")
            val before = java.nio.file.Files.walk(dir).use { it.map { p -> dir.relativize(p).toString() }.sorted().toList() }

            val r = runCli(dir, "", "relocate", "--from", "a", "--to", "b", "--delete-source", "--force")

            assertTrue(r.exit != 0, "relocate must exit non-zero for unknown profiles")
            assertEquals("must survive", java.nio.file.Files.readString(keep))
            val after = java.nio.file.Files.walk(dir).use { it.map { p -> dir.relativize(p).toString() }.sorted().toList() }
            assertEquals(before, after, "no file was created or removed")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // -- helpers for the manifest-driven delete-pass tests (gkrost/unidrive-cli#61) --------------

    private fun fileItem(
        path: String,
        size: Long,
        hash: String? = null,
        id: String = "src-$path",
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = hash,
        mimeType = "application/octet-stream",
    )

    private fun folderItem(
        path: String,
        id: String = "src-$path",
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast("/"),
        path = path,
        size = 0,
        isFolder = true,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = null,
        mimeType = null,
    )

    private fun FakeCloudProvider.seedFile(
        path: String,
        content: String,
    ) {
        files[path] = content.toByteArray()
        val parent = path.substringBeforeLast("/", "")
        if (parent.isNotEmpty()) {
            childrenByParent[parent] = (childrenByParent[parent] ?: emptyList()) + fileItem(path, size = content.length.toLong())
        }
    }

    private fun FakeCloudProvider.seedFolder(path: String) {
        val parent = path.substringBeforeLast("/", "")
        if (parent.isNotEmpty()) {
            childrenByParent[parent] = (childrenByParent[parent] ?: emptyList()) + folderItem(path)
        }
        childrenByParent.putIfAbsent(path, emptyList())
    }

    private fun migrateWithManifest(
        source: org.krost.unidrive.CloudProvider,
        target: org.krost.unidrive.CloudProvider,
        sourcePath: String = "/docs",
    ): Pair<MigrationManifest, MigrationOutcome> {
        val manifest = MigrationManifest()
        val relocator = CloudRelocator(source, target)
        val events =
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(30_000) {
                    relocator.migrate(sourcePath, sourcePath, manifest = manifest).toList()
                }
            }
        val completed = events.filterIsInstance<MigrateEvent.Completed>().first()
        return manifest to MigrationOutcome(completed, events.filterIsInstance<MigrateEvent.Error>())
    }

    private fun deletePass(
        provider: org.krost.unidrive.CloudProvider,
        manifest: MigrationManifest,
        sourcePath: String,
        outcome: MigrationOutcome,
    ) = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(10_000) { RelocateCommand().deleteMigratedSource(provider, manifest, sourcePath, outcome) }
    }

    private fun toml(p: java.nio.file.Path) = p.toString().replace('\\', '/')
}
