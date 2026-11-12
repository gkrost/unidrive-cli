package org.krost.unidrive.s3

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MultipartStoreTest {
    private val dir = Files.createTempDirectory("s3-uploads")
    private val store = MultipartStore(dir)

    private fun pending(key: String) = MultipartStore.Pending(key, "id-$key", "/local/$key", 100, 1, 16, "proof")

    @Test
    fun `an entry is found by its key among many others`() {
        repeat(50) { store.save(pending("k$it")) }

        assertEquals(pending("k17"), store.find("k17"))
        assertNull(store.find("missing"))
        assertEquals(50, store.all().size)
    }

    @Test
    fun `a torn record is ignored and does not hide the others`() {
        store.save(pending("a"))
        store.save(pending("b"))
        Files.writeString(dir.resolve("0123456789abcdef0123456789abcdef.upload"), "key=x\nsize=not-a-number")

        assertEquals(pending("b"), store.find("b"))
        assertEquals(setOf("a", "b"), store.all().map { it.key }.toSet())
    }

    @Test
    fun `a record under the key's file name that names another key is not handed out`() {
        store.save(pending("a"))
        val file = Files.list(dir).use { it.toList() }.single()
        Files.writeString(file, Files.readString(file).replace("key=a", "key=other"))

        assertNull(store.find("a"))
    }

    @Test
    fun `a removed entry is gone`() {
        store.save(pending("a"))
        store.remove("a")

        assertNull(store.find("a"))
    }
}
