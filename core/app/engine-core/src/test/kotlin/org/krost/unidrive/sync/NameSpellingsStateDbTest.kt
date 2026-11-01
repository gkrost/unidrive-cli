package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#24: the `name_spellings` table keeps the first spelling a client used for a name across a close
 * and reopen of state.db — the daemon restart of the name_clash guard. Real database files, no fakes.
 */
class NameSpellingsStateDbTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path
    private lateinit var db: StateDatabase

    private val nfc = "caf" + String(intArrayOf(0xE9), 0, 1) + ".txt"
    private val nfd = "cafe" + String(intArrayOf(0x301), 0, 1) + ".txt"

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-name-spellings")
        dbFile = dir.resolve("state.db")
        db = StateDatabase(dbFile).also { it.initialize() }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the first spelling survives a close and reopen`() {
        db.recordNameSpelling("/$nfc", "/$nfd")
        db.recordNameSpelling("/$nfc", "/$nfc") // INSERT OR IGNORE: the first one wins

        db.close()
        db = StateDatabase(dbFile).also { it.initialize() }

        assertEquals(mapOf("/$nfc" to "/$nfd"), db.loadNameSpellings())
    }

    @Test
    fun `forgetting a name takes its subtree and only its subtree`() {
        val folder = "d" + String(intArrayOf(0xE9), 0, 1)
        db.recordNameSpelling("/$folder", "/d-raw")
        db.recordNameSpelling("/$folder/$nfc", "/d-raw2")
        db.recordNameSpelling("/other/$nfc", "/other-raw")

        db.forgetNameSpellings("/$folder")

        assertEquals(mapOf("/other/$nfc" to "/other-raw"), db.loadNameSpellings())
    }

    @Test
    fun `an empty table loads empty and forgetting an unknown name is a no-op`() {
        assertTrue(db.loadNameSpellings().isEmpty())
        db.forgetNameSpellings("/$nfc")
        assertEquals(emptyMap(), db.loadNameSpellings())
    }
}
