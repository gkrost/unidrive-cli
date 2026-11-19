package org.krost.unidrive.xtra

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.hydration.HydrationIpcHandler
import org.krost.unidrive.hydration.MountEngine
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Owner decision on #710 (review of #818): a file refused because its header names another algorithm comes back on its
 * own once the profile's `xtra_algorithm` names that algorithm again — no manual unquarantine. The provider raises
 * [XtraAlgorithmMismatchException], a retryable failure; these tests pin the engines' side: the mirror does not
 * quarantine the row (its `download_quarantined` flag survives an unchanged re-delivery, so a quarantine would be for
 * good), the mount remembers nothing of its own (a repeat read in the same run still asks and still fails, now without
 * the transfer — the provider's per-run record answers), and after the restart a switch takes (a new engine over the
 * same state database and the same stored object, now wired with the file's algorithm) the file arrives.
 *
 * If [XtraAlgorithmMismatchException] became a [org.krost.unidrive.PermanentDownloadFailureException] (or a content
 * refusal), the mirror test's second pass would find the row quarantined and skip it, and the file would never arrive.
 */
class XtraAlgorithmSwitchEngineTest {
    private val base: Path = TestScratch.directory("ud-xtra-switch")
    private val db = StateDatabase(base.resolve("state.db")).also { it.initialize() }
    private val inner = FakeCloudProvider()
    private val root: Path = Files.createDirectories(base.resolve("root"))

    private val ageStore = XtraAgeKeyStore(base.resolve("xtra-age.key")).also { it.generate("switch-pass".toCharArray()) }
    private val ageV1 = AgeV1Strategy(lazy { ageStore }, { "switch-pass".toCharArray() })

    // The profile switched to aes-gcm: its RSA pair must never be demanded for the age file.
    private val aesGcm = AesGcmStrategy(lazy { error("the RSA key manager was demanded for a file of another algorithm") })

    private fun chain(strategy: XtraEncryptionStrategy) = XtraEncryptedProvider(XtraPlaintextListings(inner, strategy), strategy)

    @AfterTest
    fun close() {
        db.close()
        base.toFile().deleteRecursively()
    }

    // An age-v1 file in the cloud, listed the way the cloud lists it: the stored object's own size.
    private fun seedAgeFile(
        path: String,
        content: String,
    ) {
        val file = base.resolve("seed.txt")
        Files.writeString(file, content)
        val uploaded = runBlocking { chain(ageV1).upload(file, path) }
        inner.deltaItems = listOf(uploaded.copy(size = inner.files.getValue(path).size.toLong()))
    }

    private fun mirror(strategy: XtraEncryptionStrategy) =
        SyncEngine(
            provider = chain(strategy),
            db = db,
            syncRoot = root,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
        )

    @Test
    fun `a mirror pass under another algorithm does not quarantine the row, and the file arrives after the switch back`() =
        runBlocking {
            seedAgeFile("/a.txt", "hello from age")

            mirror(aesGcm).syncOnce()
            val refused = assertNotNull(db.getEntry("/a.txt"), "the pass records the cloud file")
            assertFalse(refused.downloadQuarantined, "a configuration block must not be quarantined")
            assertFalse(refused.isHydrated)
            val local = root.resolve("a.txt")
            assertNotEquals("hello from age", if (Files.exists(local)) Files.readString(local) else null)

            // The restart with the file's algorithm: the same row, the same stored object, delivered unchanged.
            mirror(ageV1).syncOnce()
            assertEquals("hello from age", Files.readString(local))
            val arrived = assertNotNull(db.getEntry("/a.txt"))
            assertFalse(arrived.downloadQuarantined)
            assertEquals("hello from age".length.toLong(), arrived.remoteSize, "the listing sizes it with the right overhead again")
        }

    @Test
    fun `a mirror row sized under another algorithm downloads after the switch back with no listing re-delivering it`() =
        runBlocking {
            // The usual restart: the delta cursor resumes and the unchanged file is not delivered again, so the row keeps
            // the size the other algorithm's overhead gave it, and only the recovery loop (from the row) retries it.
            seedAgeFile("/a.txt", "hello from age")
            val stored = inner.deltaItems.single()
            mirror(aesGcm).syncOnce()
            val stale = assertNotNull(db.getEntry("/a.txt")).remoteSize
            assertNotEquals("hello from age".length.toLong(), stale, "precondition: the row's size went stale under aes-gcm")
            inner.deltaItems = emptyList()
            inner.metadataOverride["/a.txt"] = stored

            mirror(ageV1).syncOnce()
            assertEquals("hello from age", Files.readString(root.resolve("a.txt")))
            val arrived = assertNotNull(db.getEntry("/a.txt"))
            assertFalse(arrived.downloadQuarantined)
            assertEquals("hello from age".length.toLong(), arrived.remoteSize, "the row records the confirmed size, not the stale one")
        }

    private suspend fun openRead(
        engine: SyncEngine,
        path: String,
    ): JsonObject {
        val ipc = HydrationIpcHandler(HydrationImpl(engine, db))
        val request = """{"verb":"hydration.open_read","handle_id":"r-$path","path":"$path"}"""
        return Json.parseToJsonElement(ipc.handle("client", request)).jsonObject
    }

    private fun mount(strategy: XtraEncryptionStrategy) =
        SyncEngine(
            provider = chain(strategy),
            db = db,
            syncRoot = root,
            cacheRoot = base.resolve("cache"),
            cacheKey = "profile",
        )

    @Test
    fun `a mount read under another algorithm is not a content refusal, and the file reads after the switch back`() =
        runBlocking {
            seedAgeFile("/a.txt", "hello from age")

            val switched = mount(aesGcm)
            MountEngine.over(switched).enumerateRemoteIntoState(false)
            val refused = openRead(switched, "/a.txt")
            assertEquals("false", refused.getValue("ok").jsonPrimitive.content, refused.toString())
            val error = refused.getValue("error").jsonPrimitive.content
            assertFalse(error.startsWith("content_refused"), "a configuration block is not a refused content: $error")
            // The same daemon asks again and fails the same way, but the provider answers from its per-run record of
            // the refusal: the repeat read transfers nothing.
            val downloadsBefore = inner.downloadByPathCalls.size + inner.downloadByIdCalls.size
            val askedAgain = openRead(switched, "/a.txt")
            assertEquals("false", askedAgain.getValue("ok").jsonPrimitive.content, askedAgain.toString())
            assertFalse(
                askedAgain.getValue("error").jsonPrimitive.content.startsWith("content_refused"),
                "the remembered refusal is still a configuration block, not refused content",
            )
            assertEquals(downloadsBefore, inner.downloadByPathCalls.size + inner.downloadByIdCalls.size)

            val restored = mount(ageV1)
            MountEngine.over(restored).enumerateRemoteIntoState(false)
            val read = openRead(restored, "/a.txt")
            assertEquals("true", read.getValue("ok").jsonPrimitive.content, read.toString())
            assertEquals("hello from age", Files.readString(Path.of(read.getValue("cache_path").jsonPrimitive.content)))
        }
}
