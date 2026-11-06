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

    @Test
    fun `relocate end to end keeps siblings and deletes only the migrated subtree when the switch is on`() {
        // The full command path is only reachable once the re-enable flips RELOCATE_ENABLED
        // (gkrost/unidrive-cli#61 stays behind the #56/#62/#63 quarantine until then); until that
        // this wiring test skips itself instead of failing on the refusal.
        if (!RELOCATE_ENABLED) {
            println("skipped: relocate is switched off; this pins the wiring for the re-enable")
            return
        }
        val base = java.nio.file.Files.createTempDirectory("ud-relocate-61-e2e")
        try {
            val srcRoot = java.nio.file.Files.createDirectories(base.resolve("src"))
            val dstRoot = java.nio.file.Files.createDirectories(base.resolve("dst"))
            val configDir = java.nio.file.Files.createDirectories(base.resolve("config"))
            java.nio.file.Files.createDirectories(srcRoot.resolve("docs"))
            java.nio.file.Files.createDirectories(srcRoot.resolve("docs-old"))
            java.nio.file.Files.writeString(srcRoot.resolve("docs/a.txt"), "a")
            java.nio.file.Files.writeString(srcRoot.resolve("docs-old/b.txt"), "b")
            java.nio.file.Files.writeString(srcRoot.resolve("keep.txt"), "k")
            java.nio.file.Files.writeString(
                configDir.resolve("config.toml"),
                """
                |[providers.src]
                |type = "localfs"
                |root_path = "${toml(srcRoot)}"
                |
                |[providers.dst]
                |type = "localfs"
                |root_path = "${toml(dstRoot)}"
                """.trimMargin() + "\n",
            )
            val out = java.io.ByteArrayOutputStream()
            val err = java.io.ByteArrayOutputStream()
            val origOut = System.out
            val origErr = System.err
            val origIn = System.`in`
            val exit =
                try {
                    System.setOut(java.io.PrintStream(out, true, Charsets.UTF_8))
                    System.setErr(java.io.PrintStream(err, true, Charsets.UTF_8))
                    System.setIn(java.io.ByteArrayInputStream("yes\n".toByteArray()))
                    val c = CommandLine(Main())
                    c.executionExceptionHandler = cliExecutionExceptionHandler
                    c.execute(
                        "-c", configDir.toString(),
                        "relocate", "--from", "src", "--to", "dst",
                        "--source-path", "/docs/", "--target-path", "/docs", "--delete-source",
                    )
                } finally {
                    System.setOut(origOut)
                    System.setErr(origErr)
                    System.setIn(origIn)
                }

            assertEquals(
                0,
                exit,
                "relocate exits 0; stderr: ${err.toString(Charsets.UTF_8)}; stdout: ${out.toString(Charsets.UTF_8)}",
            )
            assertTrue(java.nio.file.Files.exists(dstRoot.resolve("docs/a.txt")), "the target received the migration")
            assertTrue(!java.nio.file.Files.exists(srcRoot.resolve("docs")), "the migrated subtree is gone from the source")
            assertTrue(
                java.nio.file.Files.exists(srcRoot.resolve("docs-old/b.txt")),
                "a sibling whose name only extends the source name survives",
            )
            assertTrue(java.nio.file.Files.exists(srcRoot.resolve("keep.txt")), "a file outside the subtree survives")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    // -- relocate is switched off (gkrost/unidrive-cli#56, #61, #62, #63) -----------------------------------------

    @Test
    fun `relocate refuses to run with a clear message, a non-zero exit and nothing touched`() {
        val dir = java.nio.file.Files.createTempDirectory("ud-relocate-refuse")
        try {
            val src = java.nio.file.Files.createDirectories(dir.resolve("src"))
            val keep = java.nio.file.Files.writeString(src.resolve("keep.txt"), "must survive")
            val before = java.nio.file.Files.walk(dir).use { it.map { p -> dir.relativize(p).toString() }.sorted().toList() }
            val out = java.io.ByteArrayOutputStream()
            val err = java.io.ByteArrayOutputStream()
            val origOut = System.out
            val origErr = System.err
            val exit =
                try {
                    System.setOut(java.io.PrintStream(out, true, Charsets.UTF_8))
                    System.setErr(java.io.PrintStream(err, true, Charsets.UTF_8))
                    val c = CommandLine(Main())
                    c.executionExceptionHandler = cliExecutionExceptionHandler
                    c.execute("-c", dir.toString(), "relocate", "--from", "a", "--to", "b", "--delete-source", "--force")
                } finally {
                    System.setOut(origOut)
                    System.setErr(origErr)
                }

            assertTrue(exit != 0, "relocate must exit non-zero while it is disabled")
            val message = err.toString(Charsets.UTF_8)
            assertTrue("relocate is disabled" in message, "the refusal must say so; got: $message")
            assertTrue("#56" in message && "#61" in message && "#62" in message && "#63" in message, "names the issues; got: $message")
            assertEquals("", out.toString(Charsets.UTF_8), "no pre-flight output: nothing was started")
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
