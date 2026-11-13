package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#707: the `hydration.open_read` ok reply names the `etag` of the version it served, the one `hydration.list`
 * hands out for the row, wherever the bytes at `cache_path` are that version, so a client knows the base of a file it only read
 * and may send it back as `base_etag`. A provider that names no bytes (no `content_id`) gets it too. Absent for a copy that holds
 * an unsynced edit.
 */
class HydrationOpenReadEtagTest {
    private val base: Path = Files.createTempDirectory("ud-open-read-etag")
    private val db = StateDatabase(base.resolve("state.db")).also { it.initialize() }
    private val drive = MemProvider()
    private val engine =
        SyncEngine(
            provider = drive,
            db = db,
            syncRoot = Files.createDirectories(base.resolve("root")),
            cacheRoot = base.resolve("cache"),
            cacheKey = "profile",
        )
    private val hydration = HydrationImpl(engine, db)
    private val ipc = HydrationIpcHandler(hydration)

    @AfterTest
    fun close() {
        db.close()
        base.toFile().deleteRecursively()
    }

    private suspend fun scan() = MountEngine.over(engine).enumerateRemoteIntoState(false)

    private suspend fun call(request: String): JsonObject = Json.parseToJsonElement(ipc.handle("client", request)).jsonObject

    private suspend fun openRead(path: String): JsonObject =
        call("""{"verb":"hydration.open_read","handle_id":"r-$path","path":"$path"}""").also { assertOk(it) }

    // The etag hydration.list gives the row of [path] now.
    private suspend fun listedEtag(path: String): String? {
        val reply = call("""{"verb":"hydration.list","prefix":"${path.substringBeforeLast('/').ifEmpty { "/" }}"}""")
        assertOk(reply)
        val entry = reply.getValue("entries").jsonArray.map { it.jsonObject }.single { it.getValue("path").jsonPrimitive.content == path }
        return (entry["etag"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun JsonObject.etag(): String? = this["etag"]?.jsonPrimitive?.content

    private fun assertOk(reply: JsonObject) = assertEquals("true", reply.getValue("ok").jsonPrimitive.content, reply.toString())

    // Another device saves [bytes] over [path]: a new version with a new token.
    private fun replaceInCloud(
        path: String,
        bytes: ByteArray,
        token: String,
    ) {
        drive.remote[path] = bytes
        val prior = drive.items.getValue(path)
        drive.items[path] = prior.copy(size = bytes.size.toLong(), hash = token, modified = prior.modified?.plusSeconds(60))
    }

    // A save through the mount of [bytes] at [path], based on [baseEtag]: the open_write reply, and the upload's completed event
    // when the write was accepted.
    private suspend fun write(
        path: String,
        bytes: ByteArray,
        baseEtag: String,
    ): Pair<JsonObject, HydrationEvent.Completed?> =
        coroutineScope {
            val handle = "w-$path"
            val done = async(start = CoroutineStart.UNDISPATCHED) { hydration.events.filterIsInstance<HydrationEvent.Completed>().first { it.handleId == handle } }
            val cache = engine.resolveCachePath(path)
            Files.write(cache, bytes)
            val reply =
                call(
                    """{"verb":"hydration.open_write","handle_id":"$handle","path":"$path",""" +
                        """"cache_path":${JsonPrimitive(cache.toString())},"base_etag":${JsonPrimitive(baseEtag)}}""",
                )
            val completed = if (reply.getValue("ok").jsonPrimitive.content == "true") withTimeout(TIMEOUT_MS) { done.await() } else null
            done.cancel()
            reply to completed
        }

    @Test
    fun `open_read answers the etag the listing gave the file, cold and warm`() =
        runBlocking {
            drive.seed("/a.txt", "hello".toByteArray())
            scan()
            val listed = assertNotNull(listedEtag("/a.txt"))

            val cold = openRead("/a.txt")
            val warm = openRead("/a.txt")

            assertEquals(listed, cold.etag(), "a cold read: $cold")
            assertEquals(listed, warm.etag(), "a warm read: $warm")
            assertNull(cold["content_id"], "this provider names no bytes, and still the etag is sent: $cold")
            assertEquals(listed, listedEtag("/a.txt"), "the same value the list gives the row now")
        }

    @Test
    fun `after a cloud change the engine has learned, a fresh read answers the new etag`() =
        runBlocking {
            drive.seed("/a.txt", "hello".toByteArray())
            scan()
            val first = assertNotNull(openRead("/a.txt").etag())

            replaceInCloud("/a.txt", "world, longer".toByteArray(), token = "h-new")
            scan()
            val fresh = openRead("/a.txt")

            assertEquals("world, longer", Files.readString(Path.of(fresh.getValue("cache_path").jsonPrimitive.content)))
            assertEquals("h-new", fresh.etag(), fresh.toString())
            assertEquals(listedEtag("/a.txt"), fresh.etag())
            assertTrue(first != fresh.etag())
        }

    @Test
    fun `a copy that holds an unsynced edit answers no etag`() =
        runBlocking {
            drive.seed("/a.txt", "hello".toByteArray())
            scan()
            openRead("/a.txt")
            assertOk(call("""{"verb":"hydration.open_write_begin","path":"/a.txt","handle_id":"w-1"}"""))

            val served = openRead("/a.txt")

            assertFalse("etag" in served, "the served bytes are the edit's: $served")
            assertTrue(listedEtag("/a.txt") != null, "the list still names the cloud's version")
        }

    // An edit no running daemon noted (one made while it was stopped, or killed before it could note it): the copy is not the
    // row's baseline any more, and is served as the edit it is, without the row's etag.
    @Test
    fun `a copy edited behind the row answers no etag`() =
        runBlocking {
            drive.seed("/a.txt", "hello".toByteArray())
            scan()
            openRead("/a.txt")
            val cache = engine.resolveCachePath("/a.txt")
            Files.write(cache, "hello, edited".toByteArray())
            Files.setLastModifiedTime(cache, FileTime.from(Instant.now().plusSeconds(120)))

            val served = openRead("/a.txt")

            assertEquals("hello, edited", Files.readString(cache), "the edit is served, not downloaded over")
            assertFalse("etag" in served, "the served bytes are the edit's: $served")
        }

    @Test
    fun `open_write based on the etag open_read returned is accepted while the cloud is unchanged`() =
        runBlocking {
            drive.seed("/a.txt", "hello".toByteArray())
            scan()
            val base = assertNotNull(openRead("/a.txt").etag())

            val (reply, completed) = write("/a.txt", "hello, edited".toByteArray(), base)

            assertOk(reply)
            assertTrue(assertNotNull(completed).ok, "the upload landed: $completed")
            assertEquals("hello, edited", String(drive.remote.getValue("/a.txt")))
        }

    @Test
    fun `open_write based on the etag of a read the cloud has moved past is refused as a conflict`() =
        runBlocking {
            drive.seed("/a.txt", "hello".toByteArray())
            scan()
            val base = assertNotNull(openRead("/a.txt").etag())
            replaceInCloud("/a.txt", "world".toByteArray(), token = "h-new")
            scan()

            val (reply, _) = write("/a.txt", "hello, edited".toByteArray(), base)

            assertEquals("conflict", reply["error"]?.jsonPrimitive?.content, reply.toString())
            assertEquals("world", String(drive.remote.getValue("/a.txt")), "the newer version in the cloud is untouched")
        }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
