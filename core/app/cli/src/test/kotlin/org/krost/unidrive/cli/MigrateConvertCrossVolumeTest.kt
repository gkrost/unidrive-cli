package org.krost.unidrive.cli

import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The retire move when the sync root lies on another volume than the profile folder: no atomic move exists
 * there, so the conversion must copy the file whole and only then give up the source — a failed copy must
 * never take the only differing copy of the bytes with it.
 */
class MigrateConvertCrossVolumeTest {
    private fun setup(
        config: Path,
        profileDir: Path,
        syncRoot: Path,
    ) = LegacyConversion.Setup(
        profileName = "p",
        providerType = "localfs",
        profileDir = profileDir,
        syncRoot = syncRoot,
        dbPath = profileDir.resolve("state.db"),
        configFile = config,
        engineVersion = "0.0.1",
        cacheDir = null,
    )

    @Test
    fun `a sync root on another volume is retired whole`() {
        val tmp = Files.createTempDirectory("migrate-cross-volume")
        try {
            val uri = URI.create("jar:" + tmp.resolve("root.zip").toUri())
            FileSystems.newFileSystem(uri, mapOf("create" to "true")).use { fs ->
                val syncRoot = fs.getPath("/root")
                Files.createDirectories(syncRoot.resolve("sub"))
                Files.writeString(syncRoot.resolve("keep.txt"), "one")
                Files.writeString(syncRoot.resolve("sub").resolve("dir.txt"), "two-22")

                val config = tmp.resolve("config.toml")
                config.writeText("[general]\n\n[providers.p]\ntype = \"localfs\"\n")
                val profileDir = Files.createDirectory(tmp.resolve("profile"))

                val code =
                    LegacyConversion.execute(
                        setup(config, profileDir, syncRoot),
                        "mount",
                        "retire",
                        restart = false,
                        verbose = false,
                    )

                assertEquals(0, code, "the conversion survives the missing atomic move")
                assertFalse(Files.exists(syncRoot.resolve("keep.txt")), "the source is gone from the sync root")

                val conversionDir =
                    Files.list(profileDir).use { s ->
                        s.filter { it.fileName.toString().startsWith("conversion-") && Files.isDirectory(it) }.findFirst().orElseThrow()
                    }
                assertEquals("one", Files.readString(conversionDir.resolve("quarantine/keep.txt")))
                assertEquals("two-22", Files.readString(conversionDir.resolve("quarantine/sub/dir.txt")))

                val journal = LegacyConversion.Journal.load(profileDir)!!
                assertEquals(2, journal.quarantined.size, "both files are named for the explicit upload")
                assertTrue(journal.moves.all { Files.exists(Path.of(it.to)) }, "every recorded move landed whole")
            }
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `a copy that falls short is caught before the source is given up`() {
        val tmp = Files.createTempDirectory("migrate-fell-short")
        try {
            val source = tmp.resolve("file.txt")
            Files.write(source, "12345".toByteArray())
            val destination = tmp.resolve("aside").resolve("file.txt")

            val e =
                assertFailsWith<IllegalStateException> {
                    LegacyConversion.copyAside(source, destination, expectedBytes = 99L)
                }

            assertTrue("fell short" in e.message!!, "the refusal names the shortfall: ${e.message}")
            assertEquals("12345", Files.readString(source), "the source is not given up on a short copy")
            assertFalse(Files.exists(destination), "nothing lands at the destination")
            assertFalse(
                Files.list(tmp.resolve("aside")).use { s -> s.anyMatch { it.fileName.toString().endsWith(".unidrive-tmp") } },
                "no staging corpse is left behind",
            )
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    @Test
    fun `a verified copy is renamed whole before the source is deleted`() {
        val tmp = Files.createTempDirectory("migrate-verified-copy")
        try {
            val source = tmp.resolve("file.txt")
            Files.write(source, "carry me over".toByteArray())
            val destination = tmp.resolve("aside").resolve("file.txt")

            LegacyConversion.copyAside(source, destination, expectedBytes = 13L)

            assertEquals("carry me over", Files.readString(destination), "the verified bytes are what landed")
            assertFalse(Files.exists(source), "the source is given up only after the verification passed")
            assertFalse(
                Files.list(tmp.resolve("aside")).use { s -> s.anyMatch { it.fileName.toString().endsWith(".unidrive-tmp") } },
                "the staging name is consumed by the rename",
            )
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
