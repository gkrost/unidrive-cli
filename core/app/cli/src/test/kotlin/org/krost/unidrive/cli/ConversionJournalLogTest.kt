package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#749 finding 3: a retire conversion journals each moved file as one appended, synced line of the
 * conversion log, not as a whole rewrite of the journal, and folds the log into the journal once. The crash-resume
 * contract stays: a move is durable before its source is given up, and a resume finds it at its recorded destination.
 */
class ConversionJournalLogTest {
    private data class Fixture(val config: Path, val profileDir: Path, val syncRoot: Path)

    private fun fixture(): Fixture {
        val configDir = TestScratch.directory("jl-config")
        val profileDir = TestScratch.directory("jl-profile")
        val syncRoot = TestScratch.directory("jl-root")
        val config = configDir.resolve("config.toml")
        config.writeText("[general]\n\n[providers.p]\ntype = \"localfs\"\nsync_root = \"${syncRoot.toString().replace("\\", "\\\\")}\"\n")
        return Fixture(config, profileDir, syncRoot)
    }

    private fun setup(f: Fixture) =
        LegacyConversion.Setup(
            profileName = "p",
            providerType = "localfs",
            profileDir = f.profileDir,
            syncRoot = f.syncRoot,
            dbPath = f.profileDir.resolve("state.db"),
            configFile = f.config,
            engineVersion = "0.0.1",
            cacheDir = TestScratch.directory("jl-cache"),
        )

    private fun convert(f: Fixture): Int = LegacyConversion.execute(setup(f), "mount", "retire", restart = false, verbose = false)

    private fun populate(
        f: Fixture,
        n: Int,
    ) {
        for (i in 1..n) f.syncRoot.resolve("f$i.txt").writeText("bytes of file $i")
    }

    /** The writes a whole conversion of [n] files makes, by kind. */
    private fun writesFor(n: Int): Map<String, List<Int>> {
        val f = fixture()
        populate(f, n)
        val writes = mutableMapOf<String, MutableList<Int>>()
        ConversionJournalLog.observeWrite = { kind, bytes -> writes.getOrPut(kind) { mutableListOf() } += bytes }
        try {
            assertEquals(0, convert(f))
        } finally {
            ConversionJournalLog.observeWrite = null
        }
        return writes
    }

    /** The moves the journal FILE holds, read past [LegacyConversion.Journal.load] (which folds the log in). */
    private fun movesInJournalFile(profileDir: Path): Int =
        Json.parseToJsonElement(Files.readString(LegacyConversion.Journal.file(profileDir))).jsonObject["moves"]?.jsonArray?.size ?: 0

    @AfterTest
    fun clearSeams() {
        LegacyConversion.afterMove = null
        ConversionJournalLog.observeWrite = null
    }

    @Test
    fun `whole journal rewrites per conversion do not grow with the number of moved files`() {
        val few = writesFor(5)["journal"].orEmpty()
        val many = writesFor(40)["journal"].orEmpty()

        assertEquals(few.size, many.size, "a moved file must not cost a whole rewrite: 5 files -> ${few.size}, 40 files -> ${many.size}")
    }

    @Test
    fun `every moved file costs exactly one appended log line of bounded size`() {
        val lines = writesFor(40)["log"].orEmpty()

        assertEquals(40, lines.size, "one line per moved file")
        assertTrue(lines.all { it < 2048 }, "a line holds one move, not the journal: $lines")
    }

    @Test
    fun `a move journaled in the log but not yet folded survives a kill and is recorded once on resume`() {
        val f = fixture()
        populate(f, 2)
        var moved = 0
        LegacyConversion.afterMove = { if (++moved == 1) error("simulated kill") }
        assertFailsWith<IllegalStateException> { convert(f) }
        LegacyConversion.afterMove = null

        assertEquals(0, movesInJournalFile(f.profileDir), "the move is not folded into the journal file yet")
        assertTrue(Files.exists(ConversionJournalLog.file(f.profileDir)), "it lives in the log")
        val interrupted = LegacyConversion.Journal.load(f.profileDir)!!
        assertEquals(1, interrupted.moves.size, "load folds the logged move in: ${interrupted.moves}")
        val gone = Path.of(interrupted.moves.single().from)
        assertFalse(Files.exists(gone), "its source was given up")
        assertTrue(Files.isRegularFile(Path.of(interrupted.moves.single().to)), "its copy is at the recorded destination")

        assertEquals(0, convert(f))

        val resumed = LegacyConversion.Journal.load(f.profileDir)!!
        assertEquals(listOf("/f1.txt", "/f2.txt"), resumed.quarantined.map { it.remotePath }.sorted(), "neither lost nor repeated")
        assertEquals(2, resumed.moves.map { it.from }.toSet().size)
        assertEquals(2, resumed.moves.size)
        assertTrue(resumed.quarantined.all { Files.isRegularFile(Path.of(it.quarantinePath)) })
        assertEquals(2, movesInJournalFile(f.profileDir), "the finished conversion folded the log into the journal")
        assertFalse(Files.exists(ConversionJournalLog.file(f.profileDir)), "and removed the log")
    }

    @Test
    fun `a torn last log line is ignored and the next run appends after it cleanly`() {
        val f = fixture()
        populate(f, 2)
        var moved = 0
        LegacyConversion.afterMove = { if (++moved == 1) error("simulated kill") }
        assertFailsWith<IllegalStateException> { convert(f) }
        LegacyConversion.afterMove = null
        // A kill in the middle of the second append: half a line, no newline.
        Files.writeString(ConversionJournalLog.file(f.profileDir), "{\"run\":\"x\",\"move\":{\"fr", java.nio.file.StandardOpenOption.APPEND)

        assertEquals(1, LegacyConversion.Journal.load(f.profileDir)!!.moves.size, "the torn tail is not a record")
        assertEquals(0, convert(f))

        val resumed = LegacyConversion.Journal.load(f.profileDir)!!
        assertEquals(listOf("/f1.txt", "/f2.txt"), resumed.quarantined.map { it.remotePath }.sorted())
    }

    @Test
    fun `a torn line that is not the last is corruption and refuses to load`() {
        val f = fixture()
        populate(f, 2)
        var moved = 0
        LegacyConversion.afterMove = { if (++moved == 2) error("simulated kill") }
        assertFailsWith<IllegalStateException> { convert(f) }
        LegacyConversion.afterMove = null
        val log = ConversionJournalLog.file(f.profileDir)
        val lines = Files.readAllLines(log)
        Files.writeString(log, lines[0].take(10) + "\n" + lines[1] + "\n")

        assertFailsWith<IllegalStateException> { LegacyConversion.Journal.load(f.profileDir) }
    }

    @Test
    fun `log lines of an earlier run are not folded into a restarted journal`() {
        val f = fixture()
        val journal =
            LegacyConversion.Journal(
                engineVersion = "0.0.1",
                startedAt = "2026-11-20T10:00:00Z",
                profileName = "p",
                targetMode = "mount",
                disposition = "retire",
                phases = listOf("PLAN"),
            )
        LegacyConversion.Journal.save(f.profileDir, journal)
        // A crash between the restarted journal's save and the removal of the earlier run's log.
        Files.writeString(
            ConversionJournalLog.file(f.profileDir),
            "{\"run\":\"2026-11-19T09:00:00Z\",\"move\":{\"from\":\"/old/a\",\"to\":\"/old/q/a\",\"bytes\":1,\"bucket\":\"quarantine\"}}\n",
        )

        val loaded = LegacyConversion.Journal.load(f.profileDir)!!

        assertEquals(emptyList(), loaded.moves, "another run's move is not this journal's")
    }

    @Test
    fun `a journal written before the log keeps its in-file moves and resumes past them`() {
        val f = fixture()
        f.syncRoot.resolve("moved.txt").writeText("aside already")
        f.syncRoot.resolve("left.txt").writeText("still here")
        val conversionDir = Files.createDirectories(f.profileDir.resolve("conversion-old"))
        val dest = conversionDir.resolve("quarantine/moved.txt")
        Files.createDirectories(dest.parent)
        val src = f.syncRoot.resolve("moved.txt")
        Files.move(src, dest)
        fun esc(p: Path) = p.toString().replace("\\", "\\\\")
        // The pre-log format: every move inside the journal file, no log beside it.
        Files.writeString(
            LegacyConversion.Journal.file(f.profileDir),
            """
            {
                "version": 1,
                "engineVersion": "0.0.1",
                "startedAt": "2026-10-08T00:00:00Z",
                "profileName": "p",
                "targetMode": "mount",
                "disposition": "retire",
                "phases": ["PLAN", "INVENTORY"],
                "moves": [{"from": "${esc(src)}", "to": "${esc(dest)}", "bytes": 13, "bucket": "quarantine"}],
                "quarantined": [{"remotePath": "/moved.txt", "quarantinePath": "${esc(dest)}", "bytes": 13}],
                "conversionDir": "${esc(conversionDir)}"
            }
            """.trimIndent(),
        )

        assertEquals(0, convert(f))

        val journal = LegacyConversion.Journal.load(f.profileDir)!!
        assertEquals(listOf("/left.txt", "/moved.txt"), journal.quarantined.map { it.remotePath }.sorted())
        assertEquals(2, journal.moves.size)
        assertEquals("aside already", Files.readString(dest))
    }
}
