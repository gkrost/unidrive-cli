package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The backups namespace on its own: the device folders and the items below them that a provider marks [CloudItem.backup]
 * are placed under one read-only root, `/Backups/<device>/...`, with their ids; the root exists only while something is in
 * it, takes `(2)` where a live top-level item or the trash has its name, and adds no folders on the way down.
 */
class BackupsNamespaceTest {
    private fun item(
        id: String,
        path: String,
        isFolder: Boolean = false,
        parentId: String? = null,
        backup: Boolean = false,
        trashed: Boolean = false,
    ) = CloudItem(id, path.substringAfterLast('/'), path, if (isFolder) 0 else 3, isFolder, null, null, null, null, parentId = parentId, backup = backup, trashed = trashed)

    // Two devices, one backed-up folder with a file below the first, a file right in the second; a live drive folder.
    private fun page() =
        listOf(
            item("docs", "/Docs", isFolder = true),
            item("dev1", "/Laptop", isFolder = true, backup = true),
            item("bf", "/Laptop/Documents", isFolder = true, parentId = "dev1", backup = true),
            item("b1", "/Laptop/Documents/report.txt", parentId = "bf", backup = true),
            item("dev2", "/Tower", isFolder = true, backup = true),
            item("b2", "/Tower/notes.txt", parentId = "dev2", backup = true),
        )

    @Test
    fun `backup items are placed under the root with their ids and the devices hang right below it`() {
        val backups = BackupsNamespace(enabled = true, ignoreCase = false)

        val placed = backups.place(page()) { emptyList() }

        assertEquals(
            listOf("/Backups", "/Docs", "/Backups/Laptop", "/Backups/Laptop/Documents", "/Backups/Laptop/Documents/report.txt", "/Backups/Tower", "/Backups/Tower/notes.txt"),
            placed.map { it.path },
        )
        val root = placed.first()
        assertEquals(BackupsNamespace.ROOT_ID, root.id)
        assertTrue(root.isFolder && root.backup && !root.trashed)
        val byId = placed.associateBy { it.id }
        assertEquals(BackupsNamespace.ROOT_ID, byId.getValue("dev1").parentId, "a device hangs below the root")
        assertEquals("dev1", byId.getValue("bf").parentId, "the rest keeps the parent the provider gave")
        assertEquals("bf", byId.getValue("b1").parentId)
        assertEquals("/Docs", byId.getValue("docs").path, "the drive is not touched")
        assertEquals("/Backups", backups.rootPath())
        for (path in listOf("/Backups", "/Backups/Laptop", "/Backups/Tower/notes.txt")) assertTrue(backups.contains(path), path)
        assertFalse(backups.contains("/Docs"))
        assertFalse(backups.contains("/BackupsX"))
        assertTrue(backups.isRoot("/Backups"))
        for (id in listOf("dev1", "bf", "b1", "dev2", "b2", BackupsNamespace.ROOT_ID)) assertTrue(backups.holdsId(id), id)
        assertFalse(backups.holdsId("docs"))
    }

    @Test
    fun `a page without backup items comes back as it is and makes no root`() {
        val backups = BackupsNamespace(enabled = true, ignoreCase = false)
        val items = listOf(item("docs", "/Docs", isFolder = true))

        assertSame(items, backups.place(items) { emptyList() })
        assertNull(backups.rootPath())
    }

    // The provider lists each device folder with its items: nothing is invented between the root and a device, even on a
    // page that carries an item without its device (a poll, a later chunk of a gather).
    @Test
    fun `no folder is added on the way down`() {
        val backups = BackupsNamespace(enabled = true, ignoreCase = false)

        val placed = backups.place(listOf(item("b1", "/Laptop/Documents/report.txt", parentId = "bf", backup = true))) { emptyList() }

        assertEquals(listOf("/Backups", "/Backups/Laptop/Documents/report.txt"), placed.map { it.path })
        assertEquals("bf", placed.last().parentId)
    }

    // A trashed item of a backup device is the trash's: it is not placed here, whatever else the page carries.
    @Test
    fun `a trashed backup item is left to the trash`() {
        val backups = BackupsNamespace(enabled = true, ignoreCase = false)
        val trashed = item("old", "/Laptop/old.txt", parentId = "dev1", backup = true, trashed = true)

        val placed = backups.place(listOf(item("dev1", "/Laptop", isFolder = true, backup = true), trashed)) { emptyList() }

        assertEquals(trashed, placed.single { it.id == "old" })
        assertFalse(backups.holdsId("old"))
    }

    @Test
    fun `a live top-level item named Backups keeps its name and the root takes the next free one`() {
        val backups = BackupsNamespace(enabled = true, ignoreCase = true)

        val placed = backups.place(page() + item("mine", "/backups", isFolder = true)) { emptyList() }

        assertEquals("/Backups (2)", backups.rootPath())
        assertEquals("/backups", placed.single { it.id == "mine" }.path)
        assertEquals("/Backups (2)/Laptop", placed.single { it.id == "dev1" }.path)
        assertEquals("Backups", backups.configuredName, "the name asked for stays what it was")
    }

    @Test
    fun `the backups yield to the trash when both have the same name`() {
        val backups = BackupsNamespace(configuredName = "Papierkorb", enabled = true, ignoreCase = false)

        backups.place(page(), yieldTo = { listOf("Papierkorb") }) { emptyList() }
        assertEquals("/Papierkorb (2)", backups.rootPath())

        // The trash comes later, on a page without a backup item: the root still moves aside.
        val later = BackupsNamespace(configuredName = "Papierkorb", enabled = true, ignoreCase = false)
        later.place(page()) { emptyList() }
        assertEquals("/Papierkorb", later.rootPath())
        later.place(listOf(item("docs", "/Docs", isFolder = true)), yieldTo = { listOf("Papierkorb") }) { emptyList() }
        assertEquals("/Papierkorb (2)", later.rootPath())
        assertTrue(later.contains("/Papierkorb/Laptop"), "the rows below the old place stay read-only until the move lands")
    }

    @Test
    fun `an unusable configured name falls back to the default`() {
        assertEquals("Backups", BackupsNamespace(configuredName = "a/b").configuredName)
        assertEquals("Sicherungen", BackupsNamespace(configuredName = " Sicherungen ").configuredName)
    }

    @Test
    fun `the keys of both roots are told apart from the provider's ids`() {
        assertTrue(PlacedNamespace.isRootId(BackupsNamespace.ROOT_ID))
        assertTrue(PlacedNamespace.isRootId(TrashNamespace.ROOT_ID))
        assertFalse(PlacedNamespace.isRootId("unidrive:backups/Laptop"))
        assertTrue(PlacedNamespace.isAnyOwnKey("unidrive:trash/Inbox"))
        assertTrue(PlacedNamespace.isAnyOwnKey(BackupsNamespace.ROOT_ID))
        assertFalse(PlacedNamespace.isAnyOwnKey("unidrive:backupsX"))
        assertFalse(PlacedNamespace.isAnyOwnKey("dev1"))
    }
}
