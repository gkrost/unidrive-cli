package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs against a temp dir only: the real Startup folder and %LOCALAPPDATA%\unidrive are never touched. */
class LegacyLogonEntryTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("legacy-logon-entry")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private companion object {
        // Byte for byte what the deploy task wrote before the tray took autostart over.
        const val DEPLOYED_VBS =
            "Set ws = CreateObject(\"WScript.Shell\")\r\n" +
                "ws.Run \"\"\"\" & \"C:\\\\Users\\\\alice\\\\AppData\\\\Local\\\\unidrive\\\\unidrive-watch.cmd\" & \"\"\"\", 0, False\r\n"

        const val DEPLOYED_WATCH =
            "@echo off\r\n" +
                ":loop\r\n" +
                "powershell -NoProfile -ExecutionPolicy Bypass -File \"C:\\Users\\alice\\AppData\\Local\\unidrive\\unidrive.ps1\" autostart\r\n" +
                "if exist \"C:\\Users\\alice\\AppData\\Local\\unidrive\\stop\" (\r\n" +
                "    del \"C:\\Users\\alice\\AppData\\Local\\unidrive\\stop\"\r\n" +
                "    exit /b 0\r\n" +
                ")\r\n" +
                "set \"exit_code=%ERRORLEVEL%\"\r\n" +
                "if \"%exit_code%\"==\"78\" exit /b 78\r\n" +
                "timeout /t 30 /nobreak >nul\r\n" +
                "goto loop\r\n"
    }

    @Test
    fun `the vbs an earlier deploy wrote is removed and the rest of the Startup folder stays`() {
        val vbs = dir.resolve("unidrive.vbs")
        Files.writeString(vbs, DEPLOYED_VBS)
        val neighbour = dir.resolve("other-app.lnk")
        Files.writeString(neighbour, "x")

        assertEquals(LegacyLogonEntry.Outcome.REMOVED, LegacyLogonEntry.removeFromStartup(dir))

        assertFalse(Files.exists(vbs))
        assertTrue(Files.exists(neighbour))
    }

    @Test
    fun `a unidrive vbs with other content is not ours and is kept`() {
        val vbs = dir.resolve("unidrive.vbs")
        val edited = DEPLOYED_VBS + "WScript.Echo \"hello\"\r\n"
        Files.writeString(vbs, edited)

        assertEquals(LegacyLogonEntry.Outcome.KEPT_FOREIGN, LegacyLogonEntry.removeFromStartup(dir))

        assertEquals(edited, Files.readString(vbs))
    }

    @Test
    fun `a vbs that starts another program is kept`() {
        val vbs = dir.resolve("unidrive.vbs")
        val other = DEPLOYED_VBS.replace("unidrive-watch.cmd", "something-else.cmd")
        Files.writeString(vbs, other)

        assertEquals(LegacyLogonEntry.Outcome.KEPT_FOREIGN, LegacyLogonEntry.removeFromStartup(dir))

        assertEquals(other, Files.readString(vbs))
    }

    @Test
    fun `no vbs and no Startup folder are both fine`() {
        assertEquals(LegacyLogonEntry.Outcome.ABSENT, LegacyLogonEntry.removeFromStartup(dir))
        assertEquals(LegacyLogonEntry.Outcome.ABSENT, LegacyLogonEntry.removeFromStartup(dir.resolve("missing")))
    }

    @Test
    fun `the restart loop wrapper deploy wrote is removed, an edited one is kept`() {
        val watch = dir.resolve("unidrive-watch.cmd")
        Files.writeString(watch, DEPLOYED_WATCH)
        assertEquals(LegacyLogonEntry.Outcome.REMOVED, LegacyLogonEntry.removeWatchWrapper(dir))
        assertFalse(Files.exists(watch))

        val edited = DEPLOYED_WATCH.replace("autostart", "sync --watch")
        Files.writeString(watch, edited)
        assertEquals(LegacyLogonEntry.Outcome.KEPT_FOREIGN, LegacyLogonEntry.removeWatchWrapper(dir))
        assertEquals(edited, Files.readString(watch))
    }
}
