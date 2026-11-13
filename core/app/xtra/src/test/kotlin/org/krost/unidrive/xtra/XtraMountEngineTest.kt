package org.krost.unidrive.xtra

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.HydrationIpcHandler
import org.krost.unidrive.hydration.MountEngine
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The X-tra wrapper against the engine it must satisfy (gkrost/unidrive-cli#710 review): the engines' size
 * contracts hold what a listing said against the bytes a download writes — the mount's #80 size confirmation
 * ([MountEngine.confirmedDownloadSize]), the mirror's completeness check, the warm-cache size match and the
 * incomplete-hydration guard. A wrapper that answers ciphertext sizes on listings but plaintext bytes on
 * downloads fails every one of them: the first cold read dies in the size confirmation, and even past it the
 * row's remote_size would flip between the listing's and the download's count, so no cache copy ever matched
 * its row again and every read re-downloaded. The wrapper answers the plaintext size on both sides; this test
 * pins that the real mount machinery runs over it — a cold read lands, the listing and the copy agree, and a
 * re-scan plus a re-read serves warm without touching the provider again.
 */
class XtraMountEngineTest {
    private val base: Path = Files.createTempDirectory("ud-xtra-mount")
    private val db = StateDatabase(base.resolve("state.db")).also { it.initialize() }
    private val inner = FakeCloudProvider()
    private val keys =
        lazy {
            XtraKeyManager(base.resolve("xtra.key")).also { it.generate("xtra-mount-pass".toCharArray()) }
        }
    private val provider = XtraEncryptedProvider(inner, keys)
    private val engine =
        SyncEngine(
            provider = provider,
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
        call("""{"verb":"hydration.open_read","handle_id":"r-$path","path":"$path"}""")

    private suspend fun listedSize(path: String): Long? {
        val reply = call("""{"verb":"hydration.list","prefix":"/"}""")
        val entry = reply.getValue("entries").jsonArray.map { it.jsonObject }.single { it.getValue("path").jsonPrimitive.content == path }
        return entry["size"]?.jsonPrimitive?.content?.toLong()
    }

    // Seeds the content through the wrapper (encrypted and stored), then lists it the way the cloud would:
    // the stored object's own size and the hash the inner provider answered for those bytes.
    private fun seed(
        path: String,
        content: String,
    ) {
        val file = base.resolve("seed-" + path.trim('/').replace('/', '-'))
        Files.writeString(file, content)
        val uploaded = runBlocking { provider.upload(file, path) }
        inner.deltaItems =
            listOf(
                uploaded.copy(size = inner.files.getValue(path).size.toLong()),
            )
    }

    @Test
    fun `a mount over the wrapper hydrates, agrees with its listing and re-scans warm`() =
        runBlocking {
            seed("/a.txt", "hello")
            scan()

            // The cold read: the #80 confirmation compares the listed size against the bytes the decryption
            // wrote — it only passes when the listing and the receipt answer the same (plaintext) size.
            val cold = openRead("/a.txt")
            assertEquals("true", cold.getValue("ok").jsonPrimitive.content, cold.toString())
            assertEquals(
                "hello",
                Files.readString(Path.of(cold.getValue("cache_path").jsonPrimitive.content)),
            )
            assertEquals(5L, listedSize("/a.txt"), "the row holds the plaintext size, the cache copy's")
            val downloadsAfterCold = inner.downloadByPathCalls.size + inner.downloadByIdCalls.size
            assertEquals(1, downloadsAfterCold)

            // The re-scan sees the same ciphertext hash and the same rewritten size — no churn, no re-download —
            // and the read after it serves the warm copy, whose size matches the row again.
            scan()
            val warm = openRead("/a.txt")
            assertEquals("true", warm.getValue("ok").jsonPrimitive.content, warm.toString())
            assertEquals(
                "hello",
                Files.readString(Path.of(warm.getValue("cache_path").jsonPrimitive.content)),
            )
            assertEquals(1, inner.downloadByPathCalls.size + inner.downloadByIdCalls.size, "the re-scan must not re-download")
            assertEquals(5L, listedSize("/a.txt"))
        }

    @Test
    fun `a second version of the file comes down and replaces the copy`() =
        runBlocking {
            seed("/a.txt", "hello")
            scan()
            assertEquals("hello", Files.readString(Path.of(openRead("/a.txt").getValue("cache_path").jsonPrimitive.content)))

            // The cloud moves on: the stored object is replaced by another X-tra one (what a second machine's
            // wrapper uploaded), listed under the new hash.
            val next = base.resolve("seed-a-v2")
            Files.writeString(next, "world, longer")
            val uploaded = runBlocking { provider.upload(next, "/a.txt") }
            inner.deltaItems = listOf(uploaded.copy(size = inner.files.getValue("/a.txt").size.toLong()))
            scan()

            val fresh = openRead("/a.txt")
            assertEquals("true", fresh.getValue("ok").jsonPrimitive.content, fresh.toString())
            assertEquals(
                "world, longer",
                Files.readString(Path.of(fresh.getValue("cache_path").jsonPrimitive.content)),
            )
            assertEquals("world, longer".length.toLong(), listedSize("/a.txt"))
        }
}
