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
}
