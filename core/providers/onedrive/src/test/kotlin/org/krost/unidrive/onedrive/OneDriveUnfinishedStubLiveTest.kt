package org.krost.unidrive.onedrive

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * #69 finding 1, LIVE on a OneDrive Personal test account: an upload is cut off right after its session reserved the
 * empty item under the real name, and its session record is lost (no cancel reached the server). The next create of
 * the same file meets that item as a name conflict:
 * - when the reservation is this profile's, the upload completes into the same item id, at full size;
 * - when it is not, the empty item is kept and the upload lands beside it.
 *
 * Gated: UNIDRIVE_INTEGRATION_TESTS=true, UNIDRIVE_LIVE_ONEDRIVE_TOKEN_DIR names a signed-in TEST profile's folder. Works in a
 * `/_bench-MMDD-HHMM-stub` folder that is removed afterwards.
 */
class OneDriveUnfinishedStubLiveTest {
    // The test JVM's HOME is the build's test home, so the profile folder is named by its full path (a copy of a test
    // profile's folder will do).
    private val tokenPath: Path? = System.getenv("UNIDRIVE_LIVE_ONEDRIVE_TOKEN_DIR")?.let { Path.of(it) }
    private val size = 6 * 1024 * 1024

    private fun enabled(): Boolean =
        System.getenv("UNIDRIVE_INTEGRATION_TESTS")?.toBoolean() == true && tokenPath != null && Files.exists(tokenPath.resolve("token.json"))

    // Starts an upload of [local] to [path] and cancels it once its session reserved the item (or has a stored session).
    private suspend fun cutOffAfterSessionCreate(
        provider: OneDriveProvider,
        local: Path,
        path: String,
    ) = kotlinx.coroutines.coroutineScope {
        val sessions = UploadSessionStore(tokenPath!!)
        val upload = async { provider.upload(local, path, existingRemoteId = null) }
        withTimeout(60_000) { while (sessions.getSession(path) == null) delay(20) }
        delay(1_500) // the reservation lookup follows the session create
        upload.cancelAndJoin()
        // The record of the session is lost; the server still holds it and its empty item.
        sessions.delete(path)
    }

    @Test
    fun `a cut-off upload's reserved empty item is completed into, an unreserved one is kept`() {
        if (!enabled()) return
        val provider = OneDriveProvider(OneDriveConfig(tokenPath = tokenPath!!))
        val folder = "/_bench-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmm")) + "-stub"
        val local = TestScratch.file("ud-stub-live", ".bin").also { Files.write(it, Random.nextBytes(size)) }
        runBlocking {
            provider.authenticate()
            try {
                provider.createFolder(folder)

                // Own reservation: completed into, same id.
                val own = "$folder/own.bin"
                cutOffAfterSessionCreate(provider, local, own)
                val stub = provider.getMetadata(own)
                println("LIVE own stub after cut-off: id=${stub.id} size=${stub.size}")
                assertEquals(0L, stub.size, "the cut-off upload left the empty item under the real name")
                assertTrue(UploadReservationStore(tokenPath).holds(own, stub.id), "the reservation was recorded by id")
                val completed = provider.upload(local, own, existingRemoteId = null)
                println("LIVE own re-upload: id=${completed.id} size=${completed.size} path=${completed.path}")
                assertEquals(stub.id, completed.id, "the upload completed into its own reserved item")
                assertEquals(size.toLong(), provider.getMetadata(own).size)

                // Foreign empty item (no reservation of this profile): kept, the upload lands beside it.
                val foreign = "$folder/foreign.bin"
                cutOffAfterSessionCreate(provider, local, foreign)
                UploadReservationStore(tokenPath).release(foreign)
                val foreignStub = provider.getMetadata(foreign)
                val beside = provider.upload(local, foreign, existingRemoteId = null)
                println("LIVE foreign: stub id=${foreignStub.id} kept size=${provider.getMetadata(foreign).size}; upload landed at ${beside.path} id=${beside.id}")
                assertNotEquals(foreignStub.id, beside.id)
                assertEquals(0L, provider.getMetadata(foreign).size, "the empty item that is not this profile's is untouched")

                println("LIVE listing: " + provider.listChildren(folder).joinToString { "${it.name}=${it.size}" })
            } finally {
                runCatching { provider.deletePermanently(folder) }.onFailure { println("cleanup of $folder failed: $it") }
                provider.close()
                Files.deleteIfExists(local)
            }
        }
    }
}
