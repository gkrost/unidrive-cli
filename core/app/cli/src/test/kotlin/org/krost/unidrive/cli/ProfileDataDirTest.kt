package org.krost.unidrive.cli

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One rule for the per-profile data folder (conflict backups) that `sync`, `conflicts`, the tracking engine and the daemon
 * share: LOCALAPPDATA on Windows, ~/.local/share elsewhere, each falling back to user.home.
 */
class ProfileDataDirTest {
    private val env = mapOf("HOME" to "/home/u", "LOCALAPPDATA" to "C:\\Users\\u\\AppData\\Local")

    @Test
    fun `elsewhere the profile data folder is under HOME's local share`() {
        assertEquals(Paths.get("/home/u", ".local", "share", "unidrive", "work"), profileDataDir("work", windows = false, env = env::get, userHome = "/x"))
    }

    @Test
    fun `on Windows the profile data folder is under LOCALAPPDATA`() {
        assertEquals(Paths.get("C:\\Users\\u\\AppData\\Local", "unidrive", "work"), profileDataDir("work", windows = true, env = env::get, userHome = "/x"))
    }

    @Test
    fun `without the variable the user home stands in`() {
        val none: (String) -> String? = { null }
        assertEquals(Paths.get("/x", ".local", "share", "unidrive", "work"), profileDataDir("work", windows = false, env = none, userHome = "/x"))
        assertEquals(Paths.get("/x", "unidrive", "work"), profileDataDir("work", windows = true, env = none, userHome = "/x"))
    }
}
