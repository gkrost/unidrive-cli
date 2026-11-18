package org.krost.unidrive.xtra

import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kage.crypto.x25519.X25519Identity
import org.krost.unidrive.testing.TestScratch

class XtraAgeKeyStoreTest {
    // The verbs' contract, at the store level: the export is the recovery story (a standard age identity that works
    // on any installation and with the standard `age` tool), and an import or a wrong file never silently replaces
    // an existing identity — the local key stays exactly as it was.
    private fun store(name: String): XtraAgeKeyStore =
        XtraAgeKeyStore(TestScratch.directory("xtra-age-store-$name").resolve("xtra-age.key"))

    private fun retiredCopyOf(store: XtraAgeKeyStore): Path? =
        Files.list(store.path.parent).use { dir ->
            dir.filter { it.fileName.toString().startsWith(store.path.fileName.toString() + ".bak-") }
                .findFirst()
                .orElse(null)
        }

    @Test
    fun `an export is the standard age identity string`() {
        val store = store("export")
        store.generate(PASSPHRASE)

        val exported = store.exportIdentity(PASSPHRASE)

        assertTrue(exported.startsWith("AGE-SECRET-KEY-1"), "the export must be an age identity file as-is: $exported")
        assertNotNull(X25519Identity.Companion.decode(exported))
    }

    @Test
    fun `an identity exported from one installation works on another`() {
        val a = store("a")
        a.generate(PASSPHRASE)
        val exported = a.exportIdentity(PASSPHRASE)

        val b = store("b")
        b.probeIdentity(exported)
        b.importIdentity(exported, OTHER_PASSPHRASE)

        assertEquals(exported, b.exportIdentity(OTHER_PASSPHRASE))
    }

    @Test
    fun `change-passphrase re-wraps the same identity`() {
        val store = store("change")
        store.generate(PASSPHRASE)
        val before = store.exportIdentity(PASSPHRASE)

        store.changePassphrase(PASSPHRASE, OTHER_PASSPHRASE)

        assertFailsWith<AEADBadTagException> { store.load(PASSPHRASE) }
        assertEquals(before, store.exportIdentity(OTHER_PASSPHRASE))
    }

    // A short passphrase never wraps an imported identity, and a forced import of one does not retire the old file.
    @Test
    fun `an import refuses a short passphrase before it writes or retires an identity`() {
        val source = store("import-floor-source")
        source.generate(PASSPHRASE)
        val exported = source.exportIdentity(PASSPHRASE)
        val shortPass = "sevench".toCharArray()

        val fresh = store("import-floor-fresh")
        assertFailsWith<IllegalArgumentException> { fresh.importIdentity(exported, shortPass) }
        assertTrue(!fresh.exists(), "a short passphrase must not create an identity file")

        val local = store("import-floor-force")
        local.generate(PASSPHRASE)
        val bytesBefore = Files.readAllBytes(local.path)
        assertFailsWith<IllegalArgumentException> { local.importIdentity(exported, shortPass, force = true) }
        assertContentEquals(bytesBefore, Files.readAllBytes(local.path))
        assertNull(retiredCopyOf(local), "a refused import must not retire the identity")
    }

    @Test
    fun `change-passphrase keeps the passphrase floor`() {
        val store = store("floor")
        store.generate(PASSPHRASE)

        assertFailsWith<IllegalArgumentException> { store.changePassphrase(PASSPHRASE, "short".toCharArray()) }
    }

    @Test
    fun `an import refuses to replace an existing identity without force`() {
        val local = store("local")
        local.generate(PASSPHRASE)
        val before = local.exportIdentity(PASSPHRASE)
        val bytesBefore = Files.readAllBytes(local.path)
        val foreign = store("foreign")
        foreign.generate(PASSPHRASE)

        assertFailsWith<IllegalStateException> { local.importIdentity(foreign.exportIdentity(PASSPHRASE), PASSPHRASE) }

        assertEquals(before, local.exportIdentity(PASSPHRASE))
        assertContentEquals(bytesBefore, Files.readAllBytes(local.path))
    }

    @Test
    fun `a forced import retires the replaced identity beside the new one`() {
        val local = store("retire")
        local.generate(PASSPHRASE)
        val oldExport = local.exportIdentity(PASSPHRASE)
        val oldBytes = Files.readAllBytes(local.path)
        val foreign = store("foreign-force")
        foreign.generate(PASSPHRASE)
        val exported = foreign.exportIdentity(PASSPHRASE)

        local.importIdentity(exported, OTHER_PASSPHRASE, force = true)

        assertEquals(exported, local.exportIdentity(OTHER_PASSPHRASE))
        val retired = assertNotNull(retiredCopyOf(local))
        assertContentEquals(oldBytes, Files.readAllBytes(retired))
        // The retired copy is the backup the rule promises: it still loads, under the passphrase it was written with.
        assertEquals(oldExport, XtraAgeKeyStore(retired).exportIdentity(PASSPHRASE))
    }

    @Test
    fun `a wrong import file never replaces the local identity`() {
        val local = store("wrong")
        local.generate(PASSPHRASE)
        val before = local.exportIdentity(PASSPHRASE)
        val bytesBefore = Files.readAllBytes(local.path)

        assertFailsWith<Exception> { local.probeIdentity("not an age identity") }
        assertFailsWith<Exception> { local.importIdentity("not an age identity", PASSPHRASE) }

        assertEquals(before, local.exportIdentity(PASSPHRASE))
        assertContentEquals(bytesBefore, Files.readAllBytes(local.path))
    }

    private companion object {
        private val PASSPHRASE = "testpass1234".toCharArray()
        private val OTHER_PASSPHRASE = "otherpass5678".toCharArray()
    }
}
