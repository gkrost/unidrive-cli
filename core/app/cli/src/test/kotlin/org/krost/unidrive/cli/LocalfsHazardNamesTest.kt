package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.krost.unidrive.engine.localNameIssue
import org.krost.unidrive.localfs.LocalFsProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The name hazards the golden unicode tree leaves out (reserved device names, a trailing dot or space, all-dots names,
 * the characters Windows reserves, the longest name a host takes, a path far beyond 260 characters), put on one side
 * of a `localfs` profile and synced with the real mirror engine.
 *
 * What the engine does with them depends on the host, and that is the contract asserted here:
 * - on Windows, a name `localNameIssue` refuses is not downloaded: none of its bytes reach the sync root, a warning
 *   names the path and the reason, the cloud copy is left alone, and a later sync does not retry it (that nothing at
 *   all is written under the name and the row stays quarantined is #287). A local file under such a name (Windows 11
 *   lets one exist) is skipped and never uploaded;
 * - on Linux every such name is a legal name, so it is synced like any other, both ways, byte for byte;
 * - on every host the ordinary siblings and the long names and long paths the host takes sync normally: there is no
 *   rule for paths of 260 characters or more (Java reaches long paths itself), only one of 255 per name, counted in
 *   UTF-16 units on Windows and in UTF-8 bytes on POSIX.
 *
 * A hazard is only seeded where the host can create it under exactly its name (a probe in a scratch folder first:
 * Windows drops a trailing dot, Java refuses a trailing space or a reserved character there); the others are listed
 * as skipped in the output and the test needs at least one hazard it could seed.
 */
class LocalfsHazardNamesTest {
    private lateinit var base: Path
    private lateinit var local: Path
    private lateinit var remote: Path
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private val reporter = WarningReporter()

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("ud-hazards")
        local = Files.createDirectories(base.resolve("local"))
        remote = Files.createDirectories(base.resolve("remote"))
        db = StateDatabase(Files.createDirectories(base.resolve("profile")).resolve("state.db")).also { it.initialize() }
        val provider = LocalFsProvider(remote)
        engine = SyncEngine(provider = provider, db = db, syncRoot = local, reporter = reporter, cacheRoot = base.resolve("cache"))
        runBlocking { provider.authenticate() }
    }

    @AfterTest
    fun tearDown() {
        runCatching { db.close() }
        runCatching { base.toFile().deleteRecursively() }
    }

    @Test
    fun `hazard names in the cloud come down where the host takes them, are refused where it does not, and their siblings sync either way`() {
        val seeded = seed(remote, "from-cloud")
        put(remote, "from-cloud/ordinary.txt", "an ordinary sibling")
        put(remote, "elsewhere/ordinary.txt", "an ordinary file in another folder")

        sync()
        sync()

        assertEquals("an ordinary sibling", read(local, "from-cloud/ordinary.txt"))
        assertEquals("an ordinary file in another folder", read(local, "elsewhere/ordinary.txt"))
        for ((path, content) in seeded) {
            assertEquals(content, read(remote, path), "the cloud copy of ${ascii(path)} is left alone")
            val issue = localNameIssue("/$path")
            if (issue == null) {
                assertEquals(content, read(local, path), "${ascii(path)} is a legal name on this host and comes down")
                assertTrue(db.getEntry("/$path")?.isHydrated == true, "and its row is hydrated")
            } else {
                assertTrue(
                    !existsExactly(local, path) || Files.size(local.resolve(path)) == 0L,
                    "none of the cloud's bytes of ${ascii(path)} are written locally ($issue)",
                )
                assertTrue(
                    reporter.warnings.any { it.contains("/$path") && it.contains("cannot represent") },
                    "a warning names ${ascii(path)} and why: ${reporter.warnings}",
                )
            }
        }
        val warned = reporter.lastRunWarnings.filter { w -> seeded.keys.any { w.contains("/$it") } }
        assertEquals(emptyList(), warned, "the second sync does not try a refused name again")
    }

    // #287: on a Windows that can create these names, the refusal still writes a 0-byte stub under the refused name
    // (restoreToPlaceholder), and the next sync's remote merge clears the quarantine flag.
    @Test
    fun `a refused cloud name leaves nothing behind locally and its row stays quarantined`() {
        val refused = seed(remote, "from-cloud").keys.filter { localNameIssue("/$it") != null }
        assumeTrue("this host refuses none of the hazard names it can create", refused.isNotEmpty())

        sync()
        sync()

        for (path in refused) {
            assertTrue(!existsExactly(local, path), "nothing is written under the refused name ${ascii(path)}")
            val row = db.getEntry("/$path")
            assertTrue(row == null || row.downloadQuarantined, "the row of ${ascii(path)} stays quarantined: $row")
        }
    }

    @Test
    fun `hazard names on the local side are skipped on Windows and uploaded on Linux, and their siblings upload either way`() {
        val seeded = seed(local, "from-here")
        put(local, "from-here/ordinary.txt", "an ordinary sibling")

        sync()
        sync()

        assertEquals("an ordinary sibling", read(remote, "from-here/ordinary.txt"))
        for ((path, content) in seeded) {
            assertEquals(content, read(local, path), "the local file ${ascii(path)} is left alone")
            if (localNameIssue("/$path") == null) {
                assertEquals(content, read(remote, path), "${ascii(path)} is a legal name on this host and goes up")
            } else {
                assertTrue(!existsExactly(remote, path), "${ascii(path)} is never uploaded")
            }
        }
    }

    // #258: on POSIX a backslash is a name character, but the local scan and the localfs provider turn it into a
    // separator, so the file is not synced under its own name. Windows refuses the name (it is its separator), so
    // this is POSIX only.
    @Ignore("#258: a backslash in a name is read as a path separator on POSIX")
    @Test
    fun `a backslash in a name stays part of the name on POSIX`() {
        assumeTrue("a backslash is the separator on Windows", !System.getProperty("os.name", "").lowercase().contains("win"))
        put(local, "from-here/back\\slash.txt", "local backslash")
        put(remote, "from-cloud/back\\slash.txt", "cloud backslash")

        sync()
        sync()

        assertEquals("local backslash", read(remote, "from-here/back\\slash.txt"), "the local name goes up unchanged")
        assertEquals("cloud backslash", read(local, "from-cloud/back\\slash.txt"), "the cloud name comes down unchanged")
        assertTrue(!existsExactly(remote, "from-here/back") && !existsExactly(local, "from-cloud/back"), "and no folder back appears")
    }

    // ── the hazards ──────────────────────────────────────────────────────────

    /**
     * Writes every hazard below [folder] of [root] that the host can create under exactly its name; returns relative
     * path to content of those it wrote.
     */
    private fun seed(
        root: Path,
        folder: String,
    ): Map<String, String> {
        val probe = Files.createDirectories(base.resolve("probe"))
        val seeded = sortedMapOf<String, String>()
        val skipped = mutableListOf<String>()
        for ((i, hazard) in HAZARDS.withIndex()) {
            val segments = hazard.split('/')
            val content = "hazard ${ascii(hazard)}"
            if (!creatableExactly(Files.createDirectories(probe.resolve("p$i")), segments)) {
                skipped += ascii(hazard)
                continue
            }
            put(root, "$folder/$hazard", content)
            seeded["$folder/$hazard"] = content
        }
        println("seeded ${seeded.size} hazard names in $folder; the host cannot create: $skipped")
        assumeTrue("this host can create none of the hazard names", seeded.isNotEmpty())
        return seeded
    }

    // The host creates every segment under exactly its name (a trailing dot Windows drops does not count).
    private fun creatableExactly(
        dir: Path,
        segments: List<String>,
    ): Boolean =
        runCatching {
            var parent = dir
            for ((i, segment) in segments.withIndex()) {
                val next = parent.resolve(segment)
                if (i == segments.lastIndex) Files.writeString(next, "probe") else Files.createDirectory(next)
                val listed = Files.list(parent).use { s -> s.map { it.fileName.toString() }.toList() }
                if (listed != listOf(segment)) return@runCatching false
                parent = next
            }
            Files.readString(parent) == "probe"
        }.getOrDefault(false)

    private fun existsExactly(
        root: Path,
        rel: String,
    ): Boolean =
        runCatching {
            var parent = root
            for (segment in rel.split('/')) {
                val names = Files.list(parent).use { s -> s.map { it.fileName.toString() }.toList() }
                if (segment !in names) return@runCatching false
                parent = parent.resolve(segment)
            }
            true
        }.getOrDefault(false)

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun sync() {
        reporter.newRun()
        runBlocking { engine.syncOnce() }
    }

    private fun put(
        root: Path,
        rel: String,
        content: String,
    ) {
        val file = root.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun read(
        root: Path,
        rel: String,
    ): String {
        assertTrue(existsExactly(root, rel), "${ascii(rel)} exists under ${root.fileName}")
        return Files.readString(root.resolve(rel))
    }

    private fun ascii(s: String): String =
        buildString {
            s.codePoints().forEach { cp -> if (cp in 0x20..0x7E) appendCodePoint(cp) else append("{U+%04X}".format(cp)) }
        }

    private class WarningReporter : ProgressReporter {
        val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()
        val lastRunWarnings = java.util.concurrent.CopyOnWriteArrayList<String>()

        fun newRun() = lastRunWarnings.clear()

        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

        override fun onActionCount(
            total: Int,
            preFilterTotal: Int,
            filterReason: String?,
        ) {}

        override fun onActionProgress(
            index: Int,
            total: Int,
            action: String,
            path: String,
        ) {}

        override fun onTransferProgress(
            path: String,
            bytesTransferred: Long,
            totalBytes: Long,
        ) {}

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
            failed: Int,
        ) {}

        override fun onWarning(message: String) {
            warnings += message
            lastRunWarnings += message
        }
    }

    companion object {
        private const val CAT = "🐱" // U+1F431, 2 UTF-16 units, 4 UTF-8 bytes

        // Relative to the folder the hazards are seeded in; a '/' makes a folder of the segment before it.
        private val HAZARDS =
            listOf(
                // reserved device names, with and without an extension, any case, as a folder
                "CON",
                "PRN.txt",
                "aux.tar.gz",
                "NUL.csv",
                "com1",
                "LPT9.log",
                "COM¹.txt",
                "LPT1/inside.txt",
                // a trailing dot or space, all dots, as a folder
                "trailing-dot.txt.",
                "trailing-space.txt ",
                "....",
                "folder-with-dot./inside.txt",
                // the characters Windows reserves
                "a:b.txt",
                "what?.txt",
                "pipe|.txt",
                "lt<gt>.txt",
                "quote\".txt",
                "star*.txt",
                // the longest names a host takes: 255 ASCII characters; 62 emoji and an extension (252 UTF-8 bytes, under
                // POSIX's 255); 125 emoji and an extension (254 UTF-16 units: NTFS counts units, so its limit is 127
                // emoji, not 255, and this name is 504 UTF-8 bytes, more than ext4 takes)
                "a".repeat(251) + ".txt",
                CAT.repeat(62) + ".txt",
                CAT.repeat(125) + ".txt",
                // a path far beyond 260 characters: five folders of 60, then a file
                (1..5).joinToString("/") { "deep-$it-" + "x".repeat(53) } + "/deepest.txt",
            )
    }
}
