package org.krost.unidrive.hydration

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.engine.DuplicateNames
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#948 (wanted 3), within the owner's decision on #24: a pair of twins the cloud listing brought in (the
 * item spelled in NFC holds the name, its decomposed twin sits under a decorated name) has no spelling a client used first,
 * and the second spelling used to be taken for the first one: its write replaced the row of the surviving item, size and
 * content. The pair itself tells the spelling of the survivor, so a verb that names the name by the other spelling is a
 * name_clash. A cloud name that is decomposed and has no twin is not a pair: it still takes either spelling (#24, 2026-11-02).
 */
class HydrationIpcHandlerListedTwinClashTest {
    private val dir = "/hazards/nfc-nfd-twins"
    private val nfc = "$dir/café.txt"
    private val nfd = "$dir/café.txt"

    private fun json(s: String) = s.replace("\\", "\\\\")

    // The survivor at the NFC key (88 bytes, its own hash) and the decomposed twin under its decorated name, as a gather leaves them.
    private fun HydrationTestEnv.listedPair() {
        stateDb.insertUnhydratedEntry(dir, remoteSize = 0, isFolder = true)
        stateDb.insertUnhydratedEntry(nfc, remoteSize = 88, remoteHash = "hash-survivor")
        val now = Instant.now()
        stateDb.upsert(
            SyncEntry(
                path = DuplicateNames.decorate(nfc, "twin-id"),
                remotePath = nfd,
                remoteId = "twin-id",
                remoteHash = "hash-twin",
                remoteSize = 86,
                remoteModified = now,
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = now,
            ),
        )
    }

    @Test
    fun `a refused twin spelling never overwrites the surviving row's size or content`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            env.listedPair()
            val handler = HydrationIpcHandler(env.hydration, spellingsStore = env.stateDb.nameSpellingStore())
            val cache = env.syncEngine.resolveCachePath(nfc)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, "bytes of the decomposed twin, 86")
            val uploadsBefore = env.syncEngine.uploadAttempts()

            val reply = handler.handle("conn1", """{"verb":"hydration.open_write","path":"${nfd}","cache_path":"${json(cache.toString())}"}""")
            advanceUntilIdle()

            assertNameClash(reply.trim())
            assertEquals(uploadsBefore, env.syncEngine.uploadAttempts(), "nothing was queued for the second spelling")
            assertEquals(88L, env.stateDb.remoteSizeOf(nfc), "the survivor keeps its own size")
            assertEquals("hash-survivor", env.stateDb.remoteHashOf(nfc), "and its own hash")
        }

    @Test
    fun `the survivor of a listed pair is still written under its own spelling`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            env.listedPair()
            val handler = HydrationIpcHandler(env.hydration, spellingsStore = env.stateDb.nameSpellingStore())
            val cache = env.syncEngine.resolveCachePath(nfc)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, "new bytes of the survivor")

            val reply = handler.handle("conn1", """{"verb":"hydration.open_write","path":"${nfc}","cache_path":"${json(cache.toString())}"}""")

            assertTrue(reply.contains("\"ok\":true"), reply)
        }

    @Test
    fun `a decomposed cloud name without a twin takes either spelling`() =
        runTest {
            val env = HydrationTestEnv(recoveryUploadScope = this)
            env.stateDb.insertUnhydratedEntry(dir, remoteSize = 0, isFolder = true)
            env.stateDb.insertUnhydratedEntry(nfc, remoteSize = 88, remoteHash = "hash-lone")
            val handler = HydrationIpcHandler(env.hydration, spellingsStore = env.stateDb.nameSpellingStore())
            val cache = env.syncEngine.resolveCachePath(nfc)
            Files.createDirectories(cache.parent)
            Files.writeString(cache, "bytes")

            val reply = handler.handle("conn1", """{"verb":"hydration.open_write","path":"${nfd}","cache_path":"${json(cache.toString())}"}""")

            assertTrue(reply.contains("\"ok\":true"), "#24 residual stays as decided: $reply")
        }
}
