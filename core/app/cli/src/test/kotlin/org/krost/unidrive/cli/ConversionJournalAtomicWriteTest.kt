package org.krost.unidrive.cli

import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConversionJournalAtomicWriteTest {
    private fun journal(phase: String) =
        LegacyConversion.Journal(
            engineVersion = "0.0.1",
            startedAt = "2026-01-01T00:00:00Z",
            profileName = "p",
            targetMode = "mirror",
            disposition = "retire",
            phases = listOf(phase),
        )

    @Test
    fun `journal save replaces the file whole so a crash never leaves a truncated journal`() {
        val dir = TestScratch.directory("journal-atomic")
        LegacyConversion.Journal.save(dir, journal("first"))
        val file = LegacyConversion.Journal.file(dir)
        // A second name for the first journal's bytes: an in-place rewrite would change what it sees.
        val witness = dir.resolve("witness")
        Files.createLink(witness, file)
        val before = Files.readString(witness)

        LegacyConversion.Journal.save(dir, journal("second"))

        assertEquals(before, Files.readString(witness), "the old journal must stay whole until the new one is renamed over it")
        assertNotEquals(before, Files.readString(file))
        assertEquals(listOf("second"), LegacyConversion.Journal.load(dir)!!.phases)
        assertTrue(Files.list(dir).use { s -> s.allMatch { it == file || it == witness } }, "no temp file left behind")
    }
}
