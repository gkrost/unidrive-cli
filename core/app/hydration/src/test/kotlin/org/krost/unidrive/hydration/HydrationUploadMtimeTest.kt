package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A copied file's modification time survives through the mount. The cache copy's mtime is the only carrier the
 * engine has: the row's watermark ([org.krost.unidrive.sync.model.SyncEntry.localMtime]) and every provider's own
 * write field (S3 `x-amz-meta-mtime`, OneDrive `fileSystemInfo`, Dropbox `client_modified`, Internxt
 * `modificationTime`) are read from it at transfer time. A copy tool sets the time after closing the file, so the
 * hand-over races the queued upload; a set time handed over with the write (`open_write`'s `mtime_ms`) re-stamps
 * the copy at admission and keeps the chain deterministic.
 */
class HydrationUploadMtimeTest {
    private val golden = Instant.parse("2026-01-01T12:00:00Z")

    /** A copy into the mount: the create mints row and empty cache copy, the client's bytes and time land on it. */
    private suspend fun copyInto(
        env: HydrationTestEnv,
        path: String,
        content: String,
        mtimeMillis: Long = System.currentTimeMillis(),
    ): Path {
        val created = env.hydration.create("conn1", "h-c", path)
        assertTrue(created is CreateResult.Ok, "create queued: $created")
        val cachePath = (created as CreateResult.Ok).cachePath
        Files.write(cachePath, content.toByteArray())
        Files.setLastModifiedTime(cachePath, FileTime.fromMillis(mtimeMillis))
        return cachePath
    }

    /**
     * Waits until the upload has drained *into the row*: the provider call completes before the row's rebaseline
     * lands (`upload_done` precedes `upload_confirmed` by about a millisecond), so on a loaded machine a wait on the
     * provider counter alone can end inside that gap and read the pre-upload row (gkrost/unidrive-cli#1068). The
     * wait ends on the row carrying [expectedMtime], which is what the assertions then check.
     */
    private fun awaitUploadDrained(
        env: HydrationTestEnv,
        path: String,
        expectedMtime: Long,
    ) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && env.stateDb.localMtimeOf(path) != expectedMtime) {
            Thread.sleep(20)
        }
        assertEquals(expectedMtime, env.stateDb.localMtimeOf(path), "the copy's upload drained into the row")
    }

    private suspend fun listedMtimeOf(
        env: HydrationTestEnv,
        path: String,
    ): Long? {
        val listed = env.hydration.list("/")
        assertTrue(listed is ListResult.Ok, "listing: $listed")
        return (listed as ListResult.Ok).entries.single { it.path == path }.mtimeEpochMillis
    }

    private fun openWriteRequest(
        cachePath: Path,
        vararg members: Pair<String, JsonPrimitive>,
    ): String =
        buildJsonObject {
            put("verb", "hydration.open_write")
            put("handle_id", "h-w")
            put("path", "/copy.txt")
            put("cache_path", cachePath.toString())
            for ((key, value) in members) put(key, value)
        }.toString()

    @Test
    fun `a copy whose time is settled on the cache copy before hand-over survives the upload`() = runTest {
        val env = HydrationTestEnv()
        // The fake answers the copy's mtime as the cloud time, what a provider with a time field reports back.
        env.providerForTest.answerModifiedFromCopyMtime = true
        val cachePath = copyInto(env, "/copy.txt", "copied bytes", golden.toEpochMilli())

        val opened = env.hydration.openForWrite("conn1", "h-w", "/copy.txt", cachePath)
        assertTrue(opened is OpenResult.Ok, "open_write queued the upload: $opened")
        awaitUploadDrained(env, "/copy.txt", golden.toEpochMilli())

        assertEquals(golden.toEpochMilli(), env.stateDb.localMtimeOf("/copy.txt"), "the row keeps the copy's time")
        assertEquals(golden.toEpochMilli(), listedMtimeOf(env, "/copy.txt"), "the listing shows the copy's time")
        assertEquals(
            golden.toEpochMilli(),
            env.providerForTest.uploadMtimes["/copy.txt"],
            "the provider read the copy's time at transfer, so its own write field carries it",
        )
    }

    @Test
    fun `a set time handed over with the write survives the upload`() = runTest {
        val env = HydrationTestEnv()
        // The fake answers the copy's mtime as the cloud time, what a provider with a time field reports back.
        env.providerForTest.answerModifiedFromCopyMtime = true
        // The copy tool stamps the cache copy after the close, so the copy's own stamp races the queued upload:
        // the write carries the client's set time explicitly.
        val cachePath = copyInto(env, "/copy.txt", "copied bytes")
        val handler = HydrationIpcHandler(env.hydration)

        val reply =
            handler.handle(
                "c1",
                openWriteRequest(cachePath, "mtime_ms" to JsonPrimitive(golden.toEpochMilli())),
            )
        assertTrue(reply.contains("\"ok\":true"), "open_write accepted the set time: $reply")
        awaitUploadDrained(env, "/copy.txt", golden.toEpochMilli())

        assertEquals(golden.toEpochMilli(), env.stateDb.localMtimeOf("/copy.txt"), "the row keeps the client's time")
        assertEquals(golden.toEpochMilli(), listedMtimeOf(env, "/copy.txt"), "the listing shows the client's time")
        assertEquals(
            golden.toEpochMilli(),
            env.providerForTest.uploadMtimes["/copy.txt"],
            "the provider read the client's time at transfer, so its own write field carries it",
        )
    }

    @Test
    fun `a non-numeric set time is refused with a stable token and queues nothing`() = runTest {
        val env = HydrationTestEnv()
        val cachePath = copyInto(env, "/copy.txt", "copied bytes")
        val handler = HydrationIpcHandler(env.hydration)

        val reply =
            handler.handle(
                "c1",
                openWriteRequest(cachePath, "mtime_ms" to JsonPrimitive("soon")),
            )

        assertTrue(reply.contains("\"error\":\"invalid_mtime_ms\""), "the stable refusal: $reply")
        assertNull(env.providerForTest.uploadMtimes["/copy.txt"], "nothing was uploaded behind the refusal")
    }

    @Test
    fun `a fraction, an exponent, a boolean or a number past Long is refused and leaves the copy's time alone`() = runTest {
        val env = HydrationTestEnv()
        val copyTime = golden.toEpochMilli() + 60_000
        val cachePath = copyInto(env, "/copy.txt", "copied bytes", copyTime)
        val handler = HydrationIpcHandler(env.hydration)
        val prefix = """{"verb":"hydration.open_write","handle_id":"h-w","path":"/copy.txt","cache_path":${JsonPrimitive(cachePath.toString())},"mtime_ms":"""

        for (raw in listOf("1767268800000.5", "1.7672688E12", "true", "9223372036854775808", "-9223372036854775809")) {
            val reply = handler.handle("c1", "$prefix$raw}")
            assertEquals("""{"ok":false,"error":"invalid_mtime_ms"}""", reply, "mtime_ms $raw")
        }
        assertEquals(copyTime, Files.getLastModifiedTime(cachePath).toMillis(), "no refused value reached the copy")
        assertNull(env.providerForTest.uploadMtimes["/copy.txt"], "nothing was uploaded behind the refusals")
    }

    @Test
    fun `a set time outside the years 0001 to 9999 is refused and never reaches the copy`() = runTest {
        val env = HydrationTestEnv()
        val copyTime = golden.toEpochMilli() + 60_000
        val cachePath = copyInto(env, "/copy.txt", "copied bytes", copyTime)
        val handler = HydrationIpcHandler(env.hydration)

        for (millis in listOf(CLIENT_SET_TIME_RANGE.first - 1, CLIENT_SET_TIME_RANGE.last + 1, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val reply = handler.handle("c1", openWriteRequest(cachePath, "mtime_ms" to JsonPrimitive(millis)))
            assertEquals("""{"ok":false,"error":"invalid_mtime_ms"}""", reply, "mtime_ms $millis")
        }
        assertEquals(copyTime, Files.getLastModifiedTime(cachePath).toMillis(), "no out-of-range time reached the copy")
        assertNull(env.providerForTest.uploadMtimes["/copy.txt"], "nothing was uploaded behind the refusals")
    }

    @Test
    fun `a set time is never stamped on a cache path outside the cache folder`() = runTest {
        val env = HydrationTestEnv()
        copyInto(env, "/copy.txt", "copied bytes")
        val outside = org.krost.unidrive.testing.TestScratch.directory("unidrive-mtime-outside").resolve("victim.txt")
        Files.createDirectories(outside.parent)
        Files.write(outside, "not the engine's".toByteArray())
        val outsideTime = golden.toEpochMilli() + 120_000
        Files.setLastModifiedTime(outside, FileTime.fromMillis(outsideTime))

        val opened = env.hydration.openForWrite("conn1", "h-w", "/copy.txt", outside, mtimeMillis = golden.toEpochMilli())

        assertEquals(OpenResult.Failed(HydrationError.InvalidPath), opened, "the containment refusal")
        assertEquals(outsideTime, Files.getLastModifiedTime(outside).toMillis(), "the file outside the cache keeps its time")
    }
}
