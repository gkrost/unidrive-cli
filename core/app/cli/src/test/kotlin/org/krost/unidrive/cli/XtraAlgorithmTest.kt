package org.krost.unidrive.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

class XtraAlgorithmTest {

    // `profile set`'s key_missing guard and the provider wiring both read the table: the file the guard checks is
    // the file the daemon loads, for every algorithm.
    @Test
    fun `the key file profile set guards is the table's key file for every algorithm`() {
        val dir = TestScratch.directory("xtra-algorithm-table")
        XtraAlgorithm.entries.forEach {
            assertEquals(dir.resolve(it.keyFileName), ProfileSettings.xtraKeyFile(dir, it.configName))
        }
        assertEquals(dir.resolve("xtra.key"), ProfileSettings.xtraKeyFile(dir, "aes-gcm"))
        assertEquals(dir.resolve("xtra-sym.key"), ProfileSettings.xtraKeyFile(dir, "aes-gcm-sym"))
        assertEquals(dir.resolve("xtra-age.key"), ProfileSettings.xtraKeyFile(dir, "age-v1"))
        assertEquals(dir.resolve("xtra-tink.key"), ProfileSettings.xtraKeyFile(dir, "tink-aes-gcm"))
    }

    // One shared passphrase: a change on one key file must name every other key file still under the old one.
    @Test
    fun `a passphrase change reminds of every other key file present, and only those`() {
        val dir = TestScratch.directory("xtra-algorithm-reminders")
        Files.writeString(dir.resolve("xtra.key"), "k")
        Files.writeString(dir.resolve("xtra-tink.key"), "k")

        val afterAes = XtraAlgorithm.sharedPassphraseReminders(dir, XtraAlgorithm.AES_GCM)
        assertEquals(1, afterAes.size)
        assertTrue(afterAes.single().contains("xtra-tink.key") && afterAes.single().contains("unidrive xtra change-passphrase-tink"))

        val afterTink = XtraAlgorithm.sharedPassphraseReminders(dir, XtraAlgorithm.TINK_AES_GCM)
        assertEquals(1, afterTink.size)
        assertTrue(afterTink.single().contains("xtra.key") && afterTink.single().contains("unidrive xtra change-passphrase"))

        assertEquals(2, XtraAlgorithm.sharedPassphraseReminders(dir, XtraAlgorithm.AGE_V1).size)

        Files.writeString(dir.resolve("xtra-sym.key"), "k")
        val afterRsa = XtraAlgorithm.sharedPassphraseReminders(dir, XtraAlgorithm.AES_GCM)
        assertTrue(afterRsa.any { it.contains("xtra-sym.key") && it.contains("unidrive xtra change-passphrase --algorithm aes-gcm-sym") }, "$afterRsa")
        assertEquals(2, XtraAlgorithm.sharedPassphraseReminders(dir, XtraAlgorithm.AES_GCM_SYM).size)
    }

    // Only the symmetric algorithms say they hold against a quantum attacker; the README and config.example.toml say the same.
    @Test
    fun `only the symmetric algorithms are quantum-resistant`() {
        assertEquals(
            setOf(XtraAlgorithm.AES_GCM_SYM, XtraAlgorithm.TINK_AES_GCM),
            XtraAlgorithm.entries.filter { it.quantumResistant }.toSet(),
        )
        assertEquals(XtraAlgorithm.AES_GCM_SYM, XtraAlgorithm.NEW_KEY_DEFAULT)
    }

    // The vault's `.xtra` entry is not a key file: its update (or reminder) belongs to the passphrase-change command.
    @Test
    fun `the shared-passphrase reminders name key files only, vault or not`() {
        val dir = TestScratch.directory("xtra-algorithm-vault-reminder")
        Files.writeString(dir.resolve("vault.enc"), "v")
        assertEquals(emptyList(), XtraAlgorithm.sharedPassphraseReminders(dir, XtraAlgorithm.AES_GCM))
    }
}
