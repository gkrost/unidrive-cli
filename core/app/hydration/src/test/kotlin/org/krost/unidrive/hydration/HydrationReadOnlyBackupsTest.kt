package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.BackupsNamespace
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The backups namespace as the mount serves it: the root folder shows the account's backup devices and everything below
 * them. The listing says so (`read_only`, and `backups_root` for the root), every verb that would write answers
 * `read_only` and changes nothing, and reading and hydrating work as everywhere else. The trash beside it keeps its marks.
 */
class HydrationReadOnlyBackupsTest {
    // The namespace exists once the gather has placed a backup item; its rows are the engine's own.
    private fun HydrationTestEnv.withBackups() {
        backups.place(
            listOf(
                CloudItem("dev", "Laptop", "/Laptop", 0, true, null, null, null, null, backup = true),
                CloudItem("b1", "a.txt", "/Laptop/a.txt", 1, false, null, null, null, null, parentId = "dev", backup = true),
            ),
        ) { emptyList() }
        stateDb.insertFolderEntry("/Backups")
        stateDb.insertFolderEntry("/Backups/Laptop")
        stateDb.insertUnhydratedEntry("/Backups/Laptop/a.txt", 10)
        stateDb.insertHydratedEntry("/Backups/Laptop/h.txt", 5)
        stateDb.insertUnhydratedEntry("/Live.txt", 3)
    }

    private fun tokenOf(result: Any?): String =
        when (result) {
            is OpenResult.Failed -> result.error.message
            is MkdirResult.Failed -> result.error.message
            is CreateResult.Failed -> result.error.message
            is RenameResult.Failed -> result.error.message
            is UnlinkResult.Failed -> result.error.message
            is RmdirResult.Failed -> result.error.message
            else -> error("did not fail: $result")
        }

    @Test
    fun `the listing marks the root and everything below it read-only, and the root as the backups root`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this, backups = BackupsNamespace(enabled = true, ignoreCase = false))
            env.withBackups()

            val top = assertIs<ListResult.Ok>(env.hydration.list("/")).entries.associateBy { it.path }
            val device = assertIs<ListResult.Ok>(env.hydration.list("/Backups/Laptop")).entries.associateBy { it.path }

            val root = top.getValue("/Backups")
            assertTrue(root.readOnly && root.backupsRoot)
            assertFalse(root.trashRoot, "the backups root is not the trash root")
            assertFalse(top.getValue("/Live.txt").readOnly)
            assertFalse(top.getValue("/Live.txt").backupsRoot)
            val laptop = assertIs<ListResult.Ok>(env.hydration.list("/Backups")).entries.single()
            assertTrue(laptop.readOnly && !laptop.backupsRoot, "a device is read-only and not the root")
            assertTrue(device.getValue("/Backups/Laptop/a.txt").readOnly)
            assertFalse(device.getValue("/Backups/Laptop/a.txt").backupsRoot)
        }

    @Test
    fun `every verb that would write is refused and changes nothing`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this, backups = BackupsNamespace(enabled = true, ignoreCase = false))
            env.withBackups()
            env.syncEngine.seedCacheContent("/Backups/Laptop/h.txt", "edited by the user")
            val cache = env.syncEngine.resolveCachePath("/Backups/Laptop/h.txt")

            assertEquals("read_only", tokenOf(env.hydration.mkdir("/Backups/Laptop/new")))
            assertEquals("read_only", tokenOf(env.hydration.mkdir("/Backups/Other")))
            assertEquals("read_only", tokenOf(env.hydration.create("c1", "h1", "/Backups/Laptop/new.txt")))
            assertEquals("read_only", tokenOf(env.hydration.unlink("/Backups/Laptop/a.txt")))
            assertEquals("read_only", tokenOf(env.hydration.rmdir("/Backups/Laptop")))
            assertEquals("read_only", tokenOf(env.hydration.rmdir("/Backups")))
            assertEquals("read_only", tokenOf(env.hydration.rename("/Backups/Laptop/a.txt", "/Out.txt")))
            assertEquals("read_only", tokenOf(env.hydration.rename("/Live.txt", "/Backups/Laptop/Live.txt")))
            assertEquals("read_only", tokenOf(env.hydration.openWriteBegin("conn", "/Backups/Laptop/h.txt", "h1")))
            assertEquals("read_only", tokenOf(env.hydration.openForWrite("conn", "h2", "/Backups/Laptop/h.txt", cache)))

            assertEquals(10L, env.stateDb.remoteSizeOf("/Backups/Laptop/a.txt"))
            assertNotNull(env.stateDb.remoteSizeOf("/Backups/Laptop"))
            assertEquals("edited by the user", Files.readString(cache), "a refusal is not a delete")
            assertEquals(emptyList(), env.syncEngine.createdFolders())
            assertEquals(emptyList(), env.syncEngine.deletedPaths())
            assertEquals(emptyList(), env.syncEngine.movedPairs())
            assertEquals(0, env.syncEngine.uploadAttempts())
        }

    @Test
    fun `an item in the namespace can be read and hydrated`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this, backups = BackupsNamespace(enabled = true, ignoreCase = false))
            env.withBackups()
            env.syncEngine.seedRemoteContent("/Backups/Laptop/a.txt", "content of a")

            assertEquals(HydrateResult.Ok, env.hydration.hydrate("/Backups/Laptop/a.txt"))
            val ok = assertIs<OpenResult.Ok>(env.hydration.openForRead("conn", "h1", "/Backups/Laptop/a.txt"))
            assertEquals("content of a", Files.readString(ok.cachePath), "copying out works")
        }
}
