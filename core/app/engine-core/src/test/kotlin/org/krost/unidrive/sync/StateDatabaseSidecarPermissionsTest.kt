package org.krost.unidrive.sync

import org.junit.Assume.assumeTrue
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The WAL and its index hold what state.db holds (rows not yet checkpointed), so they are the current user's only,
// like the file itself: restricted on desktop once the open has created them.
class StateDatabaseSidecarPermissionsTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-sidecar-acl")
        dbFile = dir.resolve("state.db")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val sidecars get() = listOf(dir.resolve("state.db-wal"), dir.resolve("state.db-shm"))

    private fun sids(path: Path): Set<String> = WindowsAclProbe.aces(WindowsAclProbe.dacl(path)).map { it.substringAfterLast(';') }.toSet()

    private val ownerOnlySids get() = setOf(WindowsAclProbe.userSddlSid, "SY", "BA")

    private fun mode(p: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

    private val posix get() = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private fun openAndWrite(connector: StateDatabaseConnector = JdbcStateDatabaseConnector): StateDatabase =
        StateDatabase(dbFile, connector = connector).also {
            it.initialize()
            it.setSyncState("probe", "1")
        }

    @Test
    fun `the WAL and its index are owner-only on Windows, though the folder grants more`() {
        assumeTrue("Windows ACLs only", WindowsAclProbe.isWindows)
        WindowsAclProbe.grantInheritableRead(dir, WindowsAclProbe.USERS_SID)
        val db = openAndWrite()
        try {
            for (side in sidecars) {
                assertTrue(Files.exists(side), "$side exists while the database is open")
                val dacl = WindowsAclProbe.dacl(side)
                assertTrue(dacl.startsWith("D:P"), "$side: nothing inherited from the folder: $dacl")
                assertEquals(ownerOnlySids, sids(side), dacl)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `the WAL and its index are 0600 on POSIX, though the file was readable by others`() {
        assumeTrue("POSIX permissions only", !WindowsAclProbe.isWindows && posix)
        Files.createFile(dbFile)
        Files.setPosixFilePermissions(dbFile, PosixFilePermissions.fromString("rw-r--r--"))
        val db = openAndWrite()
        try {
            for (side in sidecars) assertEquals("rw-------", mode(side), "$side")
        } finally {
            db.close()
        }
    }

    // A connector of its own (Android's) restricts nothing unless it says so: the engine does not call OwnerOnly there.
    @Test
    fun `a connector that leaves the restriction to its platform gets the files as SQLite made them`() {
        assumeTrue("Windows ACLs or POSIX permissions", WindowsAclProbe.isWindows || posix)
        if (WindowsAclProbe.isWindows) {
            WindowsAclProbe.grantInheritableRead(dir, WindowsAclProbe.USERS_SID)
        } else {
            Files.createFile(dbFile)
            Files.setPosixFilePermissions(dbFile, PosixFilePermissions.fromString("rw-r--r--"))
        }
        val platformOwn =
            object : StateDatabaseConnector {
                override fun open(path: Path?) = JdbcStateDatabaseConnector.open(path)

                override fun openReadOnly(path: Path) = JdbcStateDatabaseConnector.openReadOnly(path)
            }
        val db = openAndWrite(platformOwn)
        try {
            for (side in sidecars) {
                if (WindowsAclProbe.isWindows) assertTrue(WindowsAclProbe.USERS_SID in sids(side) || "BU" in sids(side), "$side")
                else assertEquals("rw-r--r--", mode(side), "$side")
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a reopened file restricts the WAL and its index it creates again`() {
        assumeTrue("Windows ACLs or POSIX permissions", WindowsAclProbe.isWindows || posix)
        if (WindowsAclProbe.isWindows) {
            WindowsAclProbe.grantInheritableRead(dir, WindowsAclProbe.USERS_SID)
        } else {
            Files.createFile(dbFile)
            Files.setPosixFilePermissions(dbFile, PosixFilePermissions.fromString("rw-r--r--"))
        }
        openAndWrite().close()
        for (side in sidecars) assertTrue(!Files.exists(side), "precondition: the last close removes $side")
        val db = StateDatabase(dbFile).also { it.initialize() }
        try {
            for (side in sidecars) {
                assertTrue(Files.exists(side), "$side is back after the reopen")
                if (WindowsAclProbe.isWindows) assertEquals(ownerOnlySids, sids(side), WindowsAclProbe.dacl(side))
                else assertEquals("rw-------", mode(side), "$side")
            }
        } finally {
            db.close()
        }
    }
}
