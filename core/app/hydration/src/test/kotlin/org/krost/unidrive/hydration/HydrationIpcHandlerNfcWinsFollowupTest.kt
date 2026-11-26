package org.krost.unidrive.hydration

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.engine.DuplicateNames
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review findings on #980 (gkrost/unidrive-cli#948, "the NFC spelling always wins"): the settlement of an earlier decomposed
 * twin happens only for a request that passed validation and that the verb commits, never for a folder, and an NFC write over
 * a decomposed cloud name does not keep the decomposed name on the row.
 */
class HydrationIpcHandlerNfcWinsFollowupTest {
    private val nfc = "/café.txt"
    private val nfd = "/café.txt"

    private fun json(s: String) = s.replace("\\", "\\\\")

    private class Rig(val env: HydrationTestEnv, val store: NameSpellingStore = env.stateDb.nameSpellingStore()) {
        val handler = HydrationIpcHandler(env.hydration, spellingsStore = store)

        suspend fun call(verb: String, vararg fields: Pair<String, String>): String =
            handler.handle("conn1", """{"verb":"hydration.$verb",${fields.joinToString(",") { "\"${it.first}\":\"${it.second}\"" }}}""")
    }

    private fun rig(scope: kotlinx.coroutines.CoroutineScope) = Rig(HydrationTestEnv(recoveryUploadScope = scope))

    private suspend fun Rig.syncedTwin(path: String, bytes: String) {
        val created = call("create", "path" to path)
        assertTrue(created.contains("\"ok\":true"), created)
        val cache = Paths.get(Json.parseToJsonElement(created).jsonObject.getValue("cache_path").jsonPrimitive.content)
        Files.writeString(cache, bytes)
        val opened = call("open_write", "path" to path, "cache_path" to json(cache.toString()))
        assertTrue(opened.contains("\"ok\":true"), opened)
    }

    @Test
    fun `a request that fails validation leaves the earlier decomposed twin and its record alone`() = runTest {
        val r = rig(this)
        r.syncedTwin(nfd, "decomposed")
        advanceUntilIdle()

        // A handle id the daemon reserves for its own: refused before the verb runs.
        val refused = r.call("create", "handle_id" to "dh-0123456789ab-9", "path" to nfc)

        assertTrue(refused.contains("invalid_handle_id"), refused)
        assertEquals("decomposed".length.toLong(), r.env.stateDb.remoteSizeOf(nfc), "the decomposed twin's row is still there")
        assertTrue(r.call("create", "path" to nfc).contains("\"ok\":true"), "and the NFC arrival still replaces it once it is a valid request")
    }

    @Test
    fun `an NFC arrival never settles the spellings of a folder`() = runTest {
        val r = rig(this)
        val dirNfd = "/diré"
        val dirNfc = "/diré"
        assertTrue(r.call("mkdir", "path" to dirNfd).contains("\"ok\":true"))
        assertTrue(r.call("create", "path" to "$dirNfd/a.txt").contains("\"ok\":true"))

        r.call("open_write_begin", "path" to dirNfc) // a write verb at the folder's NFC name: the folder is no file to replace

        assertTrue(r.call("create", "path" to "$dirNfc/b.txt").contains("name_clash"), "the folder keeps its first spelling")
    }

    @Test
    fun `an NFC write over a decomposed cloud name does not keep the decomposed name on the row`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val now = Instant.now()
        env.stateDb.upsert(
            SyncEntry(
                path = nfc, remotePath = nfd, remoteId = "id-1", remoteHash = "h", remoteSize = 5, remoteModified = now, localMtime = null,
                localSize = null, isFolder = false, isPinned = false, isHydrated = false, lastSynced = now,
            ),
        )
        val store = env.stateDb.nameSpellingStore()
        store.record(nfc, nfd) // a client named the file by its decomposed spelling first
        val r = Rig(env, store)
        val cache = env.syncEngine.resolveCachePath(nfc)
        Files.createDirectories(cache.parent)
        Files.writeString(cache, "composed bytes")

        val reply = r.call("open_write", "path" to nfc, "cache_path" to json(cache.toString()))
        advanceUntilIdle()

        assertTrue(reply.contains("\"ok\":true"), reply)
        assertNull(env.stateDb.remotePathOf(nfc), "the row no longer addresses the cloud item by the decomposed name")
        assertNotNull(env.stateDb.remoteSizeOf(nfc))
    }

    @Test
    fun `a twin pair in a folder with many other rows is found through the folder listing`() = runTest {
        val env = HydrationTestEnv(recoveryUploadScope = this)
        val dir = "/hazards/nfc-nfd-twins"
        env.stateDb.insertUnhydratedEntry("/hazards", remoteSize = 0, isFolder = true)
        env.stateDb.insertUnhydratedEntry(dir, remoteSize = 0, isFolder = true)
        for (i in 1..50) env.stateDb.insertUnhydratedEntry("/other$i.txt", remoteSize = 1)
        env.stateDb.insertUnhydratedEntry("$dir/café.txt", remoteSize = 88)
        val now = Instant.now()
        env.stateDb.upsert(
            SyncEntry(
                path = DuplicateNames.decorate("$dir/café.txt", "twin-id"), remotePath = "$dir/café.txt", remoteId = "twin-id",
                remoteHash = "t", remoteSize = 86, remoteModified = now, localMtime = null, localSize = null, isFolder = false,
                isPinned = false, isHydrated = false, lastSynced = now,
            ),
        )

        assertEquals("$dir/café.txt", env.stateDb.nameSpellingStore().known("$dir/café.txt"))
        assertNull(env.stateDb.nameSpellingStore().known("/other1.txt"), "a name with no twin is no pair")
    }
}
