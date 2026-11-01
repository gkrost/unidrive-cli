package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#24: the first spelling a client used for a name is kept in state.db, so the name_clash guard of
 * PR #119 survives a daemon restart. A restart is a fresh [NameSpellings] over the same database: a pair whose first
 * mutation after the restart is the decomposed member used to overwrite the composed twin's cloud content.
 * Names are built from code points so the source stays ASCII.
 */
class HydrationIpcHandlerNameClashPersistenceTest {
    private val nfc = "caf" + String(intArrayOf(0xE9), 0, 1) + ".txt"
    private val nfd = "cafe" + String(intArrayOf(0x301), 0, 1) + ".txt"

    private fun json(s: String) = s.replace("\\", "\\\\")

    private class Rig(val env: HydrationTestEnv) {
        // A fresh handler over the same env is a daemon restart: a new NameSpellings, the same state.db.
        fun handlerAfterRestart() = HydrationIpcHandler(env.hydration, spellingsStore = env.stateDb.nameSpellingStore())

        suspend fun call(
            handler: HydrationIpcHandler,
            verb: String,
            vararg fields: Pair<String, String>,
        ): String {
            val body = fields.joinToString(",") { "\"${it.first}\":\"${it.second}\"" }
            return handler.handle("conn1", """{"verb":"hydration.$verb",$body}""")
        }
    }

    private fun rig(scope: kotlinx.coroutines.CoroutineScope) = Rig(HydrationTestEnv(recoveryUploadScope = scope))

    @Test
    fun `a restart does not free the spelling for the other twin`() = runTest {
        val r = rig(this)
        val first = HydrationIpcHandler(r.env.hydration, spellingsStore = r.env.stateDb.nameSpellingStore())
        assertTrue(r.call(first, "create", "path" to "/$nfc").contains("\"ok\":true"))

        val cache = r.env.syncEngine.resolveCachePath("/$nfc")
        Files.writeString(cache, "bytes of the composed file")
        assertTrue(r.call(first, "open_write", "path" to "/$nfc", "cache_path" to json(cache.toString())).contains("\"ok\":true"))
        advanceUntilIdleForUploads()
        val uploadsBefore = r.env.syncEngine.uploadAttempts()
        Files.writeString(cache, "bytes of the decomposed twin")

        val reply = r.call(r.handlerAfterRestart(), "open_write", "path" to "/$nfd", "cache_path" to json(cache.toString()))

        assertEquals("""{"ok":false,"error":"name_clash"}""", reply.trim())
        assertEquals(uploadsBefore, r.env.syncEngine.uploadAttempts(), "nothing was queued for the twin after the restart")
    }

    @Test
    fun `a create after a restart records its spelling for the next restart`() = runTest {
        val r = rig(this)
        val first = HydrationIpcHandler(r.env.hydration, spellingsStore = r.env.stateDb.nameSpellingStore())
        assertTrue(r.call(first, "create", "path" to "/$nfd").contains("\"ok\":true"))

        assertEquals("""{"ok":false,"error":"name_clash"}""", r.call(r.handlerAfterRestart(), "create", "path" to "/$nfc").trim())
    }

    @Test
    fun `an unlink frees the spelling across a restart`() = runTest {
        val r = rig(this)
        val first = HydrationIpcHandler(r.env.hydration, spellingsStore = r.env.stateDb.nameSpellingStore())
        assertTrue(r.call(first, "create", "path" to "/$nfc").contains("\"ok\":true"))
        assertTrue(r.call(first, "unlink", "path" to "/$nfc").contains("\"ok\":true"))

        assertTrue(r.call(r.handlerAfterRestart(), "create", "path" to "/$nfd").contains("\"ok\":true"))
    }

    @Test
    fun `a rename frees the old name across a restart`() = runTest {
        val r = rig(this)
        val dir = "d" + String(intArrayOf(0xE9), 0, 1)
        val dirNfd = "de" + String(intArrayOf(0x301), 0, 1)
        val first = HydrationIpcHandler(r.env.hydration, spellingsStore = r.env.stateDb.nameSpellingStore())
        assertTrue(r.call(first, "mkdir", "path" to "/$dir").contains("\"ok\":true"))
        assertTrue(r.call(first, "rename", "old_path" to "/$dir", "new_path" to "/plain").contains("\"ok\":true"))

        // The old name's spelling was forgotten — persistently, not only in this handler's memory.
        assertTrue(r.call(r.handlerAfterRestart(), "mkdir", "path" to "/$dirNfd").contains("\"ok\":true"))
    }

    private fun kotlinx.coroutines.test.TestScope.advanceUntilIdleForUploads() = testScheduler.advanceUntilIdle()
}
