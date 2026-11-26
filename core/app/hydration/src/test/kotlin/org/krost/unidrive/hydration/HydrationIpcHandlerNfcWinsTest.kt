package org.krost.unidrive.hydration

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Owner decision (2026-11-26) on gkrost/unidrive-cli#948 wanted 2: of two names that differ only in NFC/NFD form, the NFC
 * spelling always wins, whichever arrived first. An NFC arrival replaces an earlier decomposed twin; a decomposed arrival after
 * an NFC one is refused, naming the NFC sibling. A decomposed twin that holds bytes the cloud has not seen is never replaced:
 * the NFC arrival is refused and the refusal says why and names the twin.
 */
class HydrationIpcHandlerNfcWinsTest {
    private val nfc = "/café.txt"
    private val nfd = "/café.txt"

    private fun siblingOf(reply: String): String? =
        Json.parseToJsonElement(reply).jsonObject["sibling"]?.jsonPrimitive?.content

    private fun json(s: String) = s.replace("\\", "\\\\")

    private class Rig(val env: HydrationTestEnv) {
        val handler = HydrationIpcHandler(env.hydration, spellingsStore = env.stateDb.nameSpellingStore())

        suspend fun call(verb: String, vararg fields: Pair<String, String>): String =
            handler.handle("conn1", """{"verb":"hydration.$verb",${fields.joinToString(",") { "\"${it.first}\":\"${it.second}\"" }}}""")
    }

    private fun rig(scope: kotlinx.coroutines.CoroutineScope) = Rig(HydrationTestEnv(recoveryUploadScope = scope))

    // A file created and written under [path] with [bytes].
    private suspend fun Rig.written(path: String, bytes: String) {
        val created = call("create", "path" to path)
        assertTrue(created.contains("\"ok\":true"), created)
        val cache = java.nio.file.Paths.get(Json.parseToJsonElement(created).jsonObject.getValue("cache_path").jsonPrimitive.content)
        Files.writeString(cache, bytes)
        val opened = call("open_write", "path" to path, "cache_path" to json(cache.toString()))
        assertTrue(opened.contains("\"ok\":true"), opened)
    }

    @Test
    fun `an NFC arrival replaces an earlier NFD twin and its row has its own size and content`() = runTest {
        val r = rig(this)
        r.written(nfd, "decomposed")
        advanceUntilIdle()

        r.written(nfc, "composed, and longer")
        advanceUntilIdle()

        assertEquals("composed, and longer".length.toLong(), r.env.stateDb.remoteSizeOf(nfc), "the row holds the NFC file's size")
        assertEquals("composed, and longer", Files.readString(r.env.syncEngine.resolveCachePath(nfc)))
        assertTrue(r.call("create", "path" to nfd).contains("name_clash"), "the decomposed spelling is the refused one now")
    }

    @Test
    fun `a decomposed arrival after an NFC one is refused and names the NFC sibling`() = runTest {
        val r = rig(this)
        r.written(nfc, "composed")
        advanceUntilIdle()

        val reply = r.call("create", "path" to nfd)

        assertTrue(reply.contains("\"error\":\"name_clash\""), reply)
        assertEquals(nfc, siblingOf(reply), "names the NFC sibling: $reply")
        assertEquals("composed".length.toLong(), r.env.stateDb.remoteSizeOf(nfc), "the NFC row is untouched")
    }

    @Test
    fun `an NFC arrival is refused while the earlier NFD twin holds bytes the cloud has not seen`() = runTest {
        val r = rig(this)
        assertTrue(r.call("create", "path" to nfd).contains("\"ok\":true")) // never uploaded: the row has no remote

        val reply = r.call("create", "path" to nfc)

        assertTrue(reply.contains("\"error\":\"name_clash\""), reply)
        assertTrue(reply.contains("\"reason\":\"unsynced_bytes\""), "says why: $reply")
        assertEquals(nfd, siblingOf(reply), "names the decomposed twin: $reply")
        assertNotNull(r.env.stateDb.remoteSizeOf(nfc), "the decomposed twin's row is still there")
    }
}

/** A name_clash refusal; it may name the sibling spelling and the reason (owner decision 2026-11-26, gkrost/unidrive-cli#948). */
internal fun assertNameClash(reply: String) = assertTrue(reply.trim().startsWith("""{"ok":false,"error":"name_clash""""), reply)
