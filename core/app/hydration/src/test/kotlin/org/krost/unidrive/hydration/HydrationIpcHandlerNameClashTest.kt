package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#24: two names that differ only in Unicode normalisation (`café` composed / decomposed) are one row and
 * one cloud path for the engine. Owner decision: refuse the pair. A client that does not guard used to be able to open_write
 * the decomposed name and replace the composed twin's content in the cloud. The engine now answers `name_clash` to a
 * mutating verb that names a key by another spelling than the one a client used for it first.
 * Names are built from code points so the source stays ASCII.
 */
class HydrationIpcHandlerNameClashTest {
    private val nfc = "caf" + String(intArrayOf(0xE9), 0, 1) + ".txt"
    private val nfd = "cafe" + String(intArrayOf(0x301), 0, 1) + ".txt"

    private fun json(s: String) = s.replace("\\", "\\\\")

    private class Rig(val env: HydrationTestEnv) {
        val handler = HydrationIpcHandler(env.hydration)

        suspend fun call(verb: String, vararg fields: Pair<String, String>): String {
            val body = fields.joinToString(",") { "\"${it.first}\":\"${it.second}\"" }
            return handler.handle("conn1", """{"verb":"hydration.$verb",$body}""")
        }
    }

    private fun rig(scope: kotlinx.coroutines.CoroutineScope) = Rig(HydrationTestEnv(recoveryUploadScope = scope))

    @Test
    fun `creating the decomposed twin of a composed name is a name clash`() = runTest {
        assertTrue(nfc != nfd)
        val r = rig(this)
        assertTrue(r.call("create", "path" to "/$nfc").contains("\"ok\":true"))

        val reply = r.call("create", "path" to "/$nfd")

        assertEquals("""{"ok":false,"error":"name_clash"}""", reply.trim())
    }

    @Test
    fun `an open_write on the decomposed twin cannot replace the content of the composed one`() = runTest {
        val r = rig(this)
        val created = r.call("create", "path" to "/$nfc")
        assertTrue(created.contains("\"ok\":true"), created)
        val cache = r.env.syncEngine.resolveCachePath("/$nfc")
        Files.writeString(cache, "bytes of the composed file")
        assertTrue(r.call("open_write", "path" to "/$nfc", "cache_path" to json(cache.toString())).contains("\"ok\":true"))
        advanceUntilIdleForUploads()
        val uploadsBefore = r.env.syncEngine.uploadAttempts()
        Files.writeString(cache, "bytes of the decomposed file")

        val reply = r.call("open_write", "path" to "/$nfd", "cache_path" to json(cache.toString()))

        assertEquals("""{"ok":false,"error":"name_clash"}""", reply.trim())
        assertEquals(uploadsBefore, r.env.syncEngine.uploadAttempts(), "nothing was queued for the twin")
    }

    @Test
    fun `the order does not matter - composed after decomposed is refused too`() = runTest {
        val r = rig(this)
        assertTrue(r.call("create", "path" to "/$nfd").contains("\"ok\":true"))

        assertEquals("""{"ok":false,"error":"name_clash"}""", r.call("create", "path" to "/$nfc").trim())
    }

    @Test
    fun `deleting by the other spelling does not delete the twin`() = runTest {
        val r = rig(this)
        assertTrue(r.call("create", "path" to "/$nfc").contains("\"ok\":true"))

        val reply = r.call("unlink", "path" to "/$nfd")

        assertEquals("""{"ok":false,"error":"name_clash"}""", reply.trim())
        assertNotNull(r.env.stateDb.remoteSizeOf("/$nfc"), "the composed file is still there")
    }

    @Test
    fun `a folder spelled both ways clashes for the files below it`() = runTest {
        val r = rig(this)
        val dirNfc = "/dir" + String(intArrayOf(0xE9), 0, 1)
        val dirNfd = "/dire" + String(intArrayOf(0x301), 0, 1)
        assertTrue(r.call("mkdir", "path" to dirNfc).contains("\"ok\":true"))

        assertEquals("""{"ok":false,"error":"name_clash"}""", r.call("create", "path" to "$dirNfd/a.txt").trim())
        assertTrue(r.call("create", "path" to "$dirNfc/a.txt").contains("\"ok\":true"), "the first spelling keeps working")
    }

    @Test
    fun `renaming onto the other spelling is a name clash`() = runTest {
        val r = rig(this)
        assertTrue(r.call("create", "path" to "/$nfc").contains("\"ok\":true"))
        assertTrue(r.call("create", "path" to "/plain.txt").contains("\"ok\":true"))

        val reply = r.call("rename", "old_path" to "/plain.txt", "new_path" to "/$nfd")

        assertEquals("""{"ok":false,"error":"name_clash"}""", reply.trim())
        assertNotNull(r.env.stateDb.remoteSizeOf("/plain.txt"), "the source stays where it was")
    }

    @Test
    fun `the same spelling again and plain ascii names are never refused`() = runTest {
        val r = rig(this)
        assertTrue(r.call("create", "path" to "/$nfd").contains("\"ok\":true"))
        val again = r.call("create", "path" to "/$nfd")
        assertEquals("""{"ok":false,"error":"path_exists"}""", again.trim(), "the same spelling is the ordinary answer")
        assertTrue(r.call("create", "path" to "/plain.txt").contains("\"ok\":true"))
        assertEquals("""{"ok":false,"error":"path_exists"}""", r.call("create", "path" to "/plain.txt").trim())
    }

    @Test
    fun `once the first member is deleted the other spelling may be used`() = runTest {
        val r = rig(this)
        assertTrue(r.call("create", "path" to "/$nfc").contains("\"ok\":true"))
        assertEquals("""{"ok":false,"error":"name_clash"}""", r.call("create", "path" to "/$nfd").trim())

        assertTrue(r.call("unlink", "path" to "/$nfc").contains("\"ok\":true"))

        assertTrue(r.call("create", "path" to "/$nfd").contains("\"ok\":true"), "the clash is resolved")
    }

    private fun kotlinx.coroutines.test.TestScope.advanceUntilIdleForUploads() = testScheduler.advanceUntilIdle()
}
