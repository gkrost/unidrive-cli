package org.krost.unidrive.sync

import org.junit.Assume.assumeTrue
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// The staging file holds the drive's listing (names, sizes, ids), so it, its WAL and the WAL's index are the current
// user's only, like state.db: restricted on desktop once the first use has created them.
class StateDatabaseStagingPermissionsTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-staging-acl")
        dbFile = dir.resolve("state.db")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val stagingFiles get() = StateDatabase.stagingFileNames().map { dir.resolve(it) }

    private fun sids(path: Path): Set<String> = WindowsAclProbe.aces(WindowsAclProbe.dacl(path)).map { it.substringAfterLast(';') }.toSet()

    private val ownerOnlySids get() = setOf(WindowsAclProbe.userSddlSid, "SY", "BA")

    private fun mode(p: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

    private val posix get() = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    // Windows: the folder grants BUILTIN\Users read to what is created in it. POSIX: the staging file exists readable by
    // others (SQLite gives its -wal and -shm the same mode).
    private fun openToOthers() {
        if (WindowsAclProbe.isWindows) {
            WindowsAclProbe.grantInheritableRead(dir, WindowsAclProbe.USERS_SID)
        } else {
            val staging = dir.resolve(StateDatabase.SCAN_STAGING_FILE_NAME)
            Files.createFile(staging)
            Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("rw-r--r--"))
        }
    }

    private fun staged(connector: StateDatabaseConnector = JdbcStateDatabaseConnector): StateDatabase =
        StateDatabase(dbFile, connector = connector).also {
            it.initialize()
            val scanId = it.beginScan(initialMarker = null)
            it.persistScanPage(scanId, StateDatabaseScanStagingTest.items(0, 10), marker = "page-0")
        }

    private fun assertOwnerOnly(file: Path) {
        assertTrue(Files.exists(file), "$file exists while a scan is staged")
        if (WindowsAclProbe.isWindows) {
            val dacl = WindowsAclProbe.dacl(file)
            assertTrue(dacl.startsWith("D:P"), "$file: nothing inherited from the folder: $dacl")
            assertEquals(ownerOnlySids, sids(file), dacl)
        } else {
            assertEquals("rw-------", mode(file), "$file")
        }
    }

    @Test
    fun `the staging file, its WAL and its index are the current user's only`() {
        assumeTrue("Windows ACLs or POSIX permissions", WindowsAclProbe.isWindows || posix)
        openToOthers()
        val db = staged()
        try {
            for (file in stagingFiles) assertOwnerOnly(file)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a reopened staging restricts the WAL and its index it creates again`() {
        assumeTrue("Windows ACLs or POSIX permissions", WindowsAclProbe.isWindows || posix)
        openToOthers()
        staged().close()
        for (file in stagingFiles.drop(1)) assertTrue(!Files.exists(file), "precondition: the last close removes $file")
        val db = StateDatabase(dbFile).also { it.initialize() }
        try {
            assertNotNull(db.getActiveScan(Duration.ofDays(1)), "the staged scan is still there")
            for (file in stagingFiles) assertOwnerOnly(file)
        } finally {
            db.close()
        }
    }

    // A connector of its own (Android's) restricts nothing unless it says so: the engine does not call OwnerOnly there.
    @Test
    fun `a connector that leaves the restriction to its platform gets the files as SQLite made them`() {
        assumeTrue("Windows ACLs or POSIX permissions", WindowsAclProbe.isWindows || posix)
        openToOthers()
        val platformOwn =
            object : StateDatabaseConnector {
                override fun open(path: Path?) = JdbcStateDatabaseConnector.open(path)

                override fun openReadOnly(path: Path) = JdbcStateDatabaseConnector.openReadOnly(path)
            }
        val db = staged(platformOwn)
        try {
            for (file in stagingFiles) {
                if (WindowsAclProbe.isWindows) assertTrue(WindowsAclProbe.USERS_SID in sids(file) || "BU" in sids(file), "$file")
                else assertEquals("rw-r--r--", mode(file), "$file")
            }
        } finally {
            db.close()
        }
    }
}
