package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// A move into another folder under a new name is two calls on Internxt (a move and a rename), and each is refused with 409
// when its folder already holds the name the item has at that moment. The order of the two must follow from what is free, so
// a move whose final place is free never fails on the way; a final name that is taken is refused before anything changes.
class InternxtMoveRenameOrderTest {
    private fun folder(
        uuid: String,
        name: String,
        parent: String = "root",
    ) = FakeMoveItem(uuid, name, parent, isFolder = true)

    private fun file(
        uuid: String,
        name: String,
        parent: String,
    ): FakeMoveItem {
        val dot = name.lastIndexOf('.')
        // No dot, or only a leading one: the leaf has no type. substringAfterLast would otherwise
        // take the whole name as the type (`README` → type `README`).
        return if (dot <= 0) {
            FakeMoveItem(uuid, name, parent, isFolder = false, type = null)
        } else {
            FakeMoveItem(uuid, name.substring(0, dot), parent, isFolder = false, type = name.substring(dot + 1))
        }
    }

    private fun providerOn(drive: FakeMoveDrive) = internxtProviderOn(drive.engine).also { it.modifiedRereadDelaysMs = emptyList() }

    @Test
    fun `a file moved under a new name into a folder holding its old name is renamed first`() =
        runTest {
            val drive = FakeMoveDrive(listOf(folder("A", "A"), folder("B", "B"), file("x", "f.txt", "A"), file("y", "f.txt", "B")))

            val moved = providerOn(drive).moveById("x", "/A/f.txt", "/B/g.txt")

            assertEquals("/B/g.txt", moved.path)
            assertEquals(setOf("/A", "/B", "/B/g.txt", "/B/f.txt"), drive.paths())
            assertEquals(listOf("rename x -> g.txt", "move x -> B"), drive.legs, "one rename, then one move")
            assertEquals(emptyList(), drive.refused, "no leg is refused on the way")
        }

    @Test
    fun `the path entry point takes the same order`() =
        runTest {
            val drive = FakeMoveDrive(listOf(folder("A", "A"), folder("B", "B"), file("x", "f.txt", "A"), file("y", "f.txt", "B")))

            providerOn(drive).move("/A/f.txt", "/B/g.txt")

            assertEquals(setOf("/A", "/B", "/B/g.txt", "/B/f.txt"), drive.paths())
            assertEquals(emptyList(), drive.refused)
        }

    @Test
    fun `a move whose destination does not hold the old name still moves first`() =
        runTest {
            val drive = FakeMoveDrive(listOf(folder("A", "A"), folder("B", "B"), file("x", "f.txt", "A")))

            providerOn(drive).moveById("x", "/A/f.txt", "/B/g.txt")

            assertEquals(listOf("move x -> B", "rename x -> g.txt"), drive.legs)
        }

    @Test
    fun `when both names are taken on the way the file goes through a temporary name`() =
        runTest {
            // B holds the old name, A holds the new one: neither order is free, the final place is.
            val drive =
                FakeMoveDrive(
                    listOf(folder("A", "A"), folder("B", "B"), file("x", "f.txt", "A"), file("y", "f.txt", "B"), file("z", "g.txt", "A")),
                )

            val moved = providerOn(drive).moveById("x", "/A/f.txt", "/B/g.txt")

            assertEquals("/B/g.txt", moved.path)
            assertEquals(setOf("/A", "/B", "/A/g.txt", "/B/f.txt", "/B/g.txt"), drive.paths(), "nothing else moved or renamed")
            assertEquals(3, drive.legs.size, "rename to a temporary name, move, rename: ${drive.legs}")
            assertTrue(drive.legs[0].startsWith("rename x -> .unidrive-move-"), drive.legs.toString())
            assertEquals(listOf("move x -> B", "rename x -> g.txt"), drive.legs.drop(1))
            assertEquals(emptyList(), drive.refused)
        }

    @Test
    fun `a neither-free move onto a name without a dot sends the temporary plain name whole`() =
        runTest {
            // B holds the old name and A holds the new one, so the file must pass through a temporary
            // name, and that name's only dot is the leading one. An empty plain name must not be sent,
            // and the file must finish at the extensionless leaf.
            val id = "0199a1b2-c3d4-7e5f-8000-0000000000aa"
            val drive =
                FakeMoveDrive(
                    listOf(folder("A", "A"), folder("B", "B"), file(id, "notes.txt", "A"), file("y", "notes.txt", "B"), file("z", "README", "A")),
                )

            val moved = providerOn(drive).moveById(id, "/A/notes.txt", "/B/README")

            val tempPlain = drive.renamePlains.first()
            assertTrue(tempPlain.isNotEmpty(), "the temporary plain name is not empty: ${drive.renamePlains}")
            assertEquals(".unidrive-move-$id", tempPlain, "the plain name is the occupancy token, keyed off the whole id")
            assertEquals("rename $id -> $tempPlain", drive.legs.first(), "the stored name is that plain name, not a type glued onto an empty one")
            assertEquals(listOf("README"), drive.renamePlains.drop(1), "the final rename is plain README")
            assertEquals("/B/README", moved.path)
            assertEquals(setOf("/A", "/B", "/A/README", "/B/notes.txt", "/B/README"), drive.paths(), "nothing else moved or renamed")
            assertEquals(listOf("move $id -> B", "rename $id -> README"), drive.legs.drop(1))
            assertEquals(emptyList(), drive.refused)
        }

    @Test
    fun `a final name that is taken in the destination is a 409 and changes nothing`() =
        runTest {
            val drive = FakeMoveDrive(listOf(folder("A", "A"), folder("B", "B"), file("x", "f.txt", "A"), file("w", "g.txt", "B")))

            val e = assertFailsWith<InternxtApiException> { providerOn(drive).moveById("x", "/A/f.txt", "/B/g.txt") }

            assertEquals(409, e.statusCode)
            assertEquals(409, e.httpStatus)
            assertEquals(emptyList(), drive.legs, "the item is not moved half way")
            assertEquals(setOf("/A", "/B", "/A/f.txt", "/B/g.txt"), drive.paths())
        }

    @Test
    fun `a rename leg that fails after the move is finished by a retry by id`() =
        runTest {
            val drive = FakeMoveDrive(listOf(folder("A", "A"), folder("B", "B"), file("x", "f.txt", "A")))
            drive.failNext["rename x"] = 409
            val provider = providerOn(drive)

            assertFailsWith<InternxtApiException> { provider.moveById("x", "/A/f.txt", "/B/g.txt") }
            assertEquals(setOf("/A", "/B", "/B/f.txt"), drive.paths(), "the move leg landed, the rename did not")

            val moved = provider.moveById("x", "/A/f.txt", "/B/g.txt")

            assertEquals("/B/g.txt", moved.path)
            assertEquals(setOf("/A", "/B", "/B/g.txt"), drive.paths())
            assertEquals(listOf("move x -> B", "rename x -> g.txt"), drive.legs, "the retry renames only")
        }

    @Test
    fun `a retry by id of a move that already landed is a no-op`() =
        runTest {
            val drive = FakeMoveDrive(listOf(folder("A", "A"), folder("B", "B"), file("x", "g.txt", "B")))

            val moved = providerOn(drive).moveById("x", "/A/f.txt", "/B/g.txt")

            assertEquals("/B/g.txt", moved.path)
            assertEquals(emptyList(), drive.legs)
        }

    @Test
    fun `a folder moved under a new name into a folder holding a folder of its old name is renamed first`() =
        runTest {
            val drive =
                FakeMoveDrive(listOf(folder("A", "A"), folder("B", "B"), folder("d", "Docs", "A"), folder("e", "Docs", "B"), file("x", "r.txt", "d")))

            val moved = providerOn(drive).moveById("d", "/A/Docs", "/B/Papers")

            assertEquals("/B/Papers", moved.path)
            assertEquals(setOf("/A", "/B", "/B/Docs", "/B/Papers", "/B/Papers/r.txt"), drive.paths())
            assertEquals(listOf("rename d -> Papers", "move d -> B"), drive.legs)
            assertEquals(emptyList(), drive.refused)
        }
}
