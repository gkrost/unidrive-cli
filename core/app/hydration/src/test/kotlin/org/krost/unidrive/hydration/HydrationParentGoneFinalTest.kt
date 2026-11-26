package org.krost.unidrive.hydration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#951 (what is left of #134): a pending upload refused `parent_gone` stays refused. The refusal is on the
 * row (state.db), so neither a backoff round, a resume, nor a daemon start offers the same bytes to the provider again; the
 * row stays failed and visible. The ways out are the user's own: a client's retry, `history.retry`, or new content.
 * (Test drive 2, E3 had these offers go through at every start; on a folder that never came back that looped for hours.)
 */
class HydrationParentGoneFinalTest {
    private class Env(
        val provider: MemProvider,
        val dbPath: Path,
        val syncRoot: Path,
        val cacheRoot: Path,
    ) {
        lateinit var db: StateDatabase
        lateinit var engine: SyncEngine
        lateinit var mount: MountEngine

        // A (re)start: the state.db is opened from its file again and a new engine is made over it.
        suspend fun start() {
            db = StateDatabase(dbPath).also { it.initialize() }
            engine = SyncEngine(provider = provider, db = db, syncRoot = syncRoot, cacheRoot = cacheRoot)
            mount = MountEngine.over(engine)
            mount.enumerateRemoteIntoState(reset = false)
        }

        fun stop() = db.close()
    }

    private suspend fun env(): Env {
        val provider = MemProvider()
        provider.seedFolder("/dir")
        provider.seedFolder("/other")
        val env =
            Env(
                provider,
                TestScratch.directory("ud-951-db").resolve("state.db"),
                TestScratch.directory("ud-951-root"),
                TestScratch.directory("ud-951-cache"),
            )
        env.start()
        return env
    }

    // A file written below /dir while /dir is gone in the cloud: its upload is refused parent_gone. Afterwards the folder is back.
    private suspend fun Env.refusedParentGone(
        scope: CoroutineScope,
        retryDelays: List<Long>,
    ): HydrationImpl {
        provider.items.remove("/dir")
        provider.requireParents = true
        val hydration = HydrationImpl(engine, db, recoveryUploadScope = scope, exhaustedRetryDelaysMs = retryDelays)
        val created = assertIs<CreateResult.Ok>(hydration.create("conn", "h1", "/dir/new.txt"))
        Files.writeString(created.cachePath, "only copy")
        hydration.openForWrite("conn", "h1", "/dir/new.txt", created.cachePath)
        // runCurrent, not advanceUntilIdle: the backoff's delay must not elapse before the check.
        if (scope is TestScope) scope.runCurrent()
        assertNull(provider.remote["/dir/new.txt"], "precondition: the upload was refused")
        assertTrue(db.uploadRefusal("/dir/new.txt")?.endsWith("|parent_gone: /dir") == true, "precondition: the refusal is on the row")
        return hydration
    }

    // The folder is back in the cloud. Nothing has listed it yet.
    private fun Env.folderBack() = provider.seedFolder("/dir")

    // A complete listing: the folder as the provider has it now.
    // [reset]: a full listing, which is what reaps a folder the provider no longer has.
    private suspend fun Env.listing(reset: Boolean = false) {
        assertTrue(mount.enumerateRemoteIntoState(reset = reset).ok, "the listing ran")
    }

    @Test
    fun `a parent_gone refusal is not offered again by a backoff round`() = runTest {
        val env = env()
        env.refusedParentGone(this, retryDelays = listOf(60_000L, 120_000L))
        env.folderBack()

        advanceTimeBy(24 * 60 * 60 * 1000L)
        advanceUntilIdle()

        assertNull(env.provider.remote["/dir/new.txt"], "the refused row was not sent again")
        assertNotNull(env.db.uploadRefusal("/dir/new.txt"), "the refusal is still on the row")
        env.stop()
    }

    @Test
    fun `a parent_gone refusal is not offered again by a resume`() = runTest {
        val env = env()
        env.refusedParentGone(this, retryDelays = emptyList())
        env.folderBack()

        env.mount.pause.pause("test", auto = false)
        env.mount.pause.resume()
        advanceUntilIdle()

        assertNull(env.provider.remote["/dir/new.txt"], "the refused row was not sent again")
        env.stop()
    }

    @Test
    fun `a parent_gone refusal survives a daemon restart and is not replayed`() = runTest {
        val env = env()
        env.refusedParentGone(this, retryDelays = emptyList())
        env.folderBack()
        env.stop()

        env.start() // the state.db is read from its file by a new engine
        val restarted = HydrationImpl(env.engine, env.db, recoveryUploadScope = this, exhaustedRetryDelaysMs = emptyList())
        restarted.replayPendingUploads()
        advanceUntilIdle()

        assertNull(env.provider.remote["/dir/new.txt"], "the start did not offer the refused row again")
        assertTrue(env.db.uploadRefusal("/dir/new.txt")?.endsWith("|parent_gone: /dir") == true, "the refusal is persisted")
        env.stop()
    }

    @Test
    fun `a client retry still sends a parent_gone row once the parent is back`() = runTest {
        val env = env()
        val hydration = env.refusedParentGone(this, retryDelays = emptyList())
        env.folderBack()

        hydration.openForWrite("conn", "h2", "/dir/new.txt", env.mount.resolveCachePath("/dir/new.txt"))
        advanceUntilIdle()

        assertEquals("only copy", env.provider.remote["/dir/new.txt"]?.let { String(it) })
        env.stop()
    }

    @Test
    fun `a parent seen alive again in a listing offers its refused rows once and they upload`() = runTest {
        val env = env()
        env.refusedParentGone(this, retryDelays = emptyList())
        env.listing(reset = true) // the folder is still gone in this listing: nothing to offer
        advanceUntilIdle()
        assertNull(env.provider.remote["/dir/new.txt"], "a listing without the folder offers nothing")

        env.folderBack()
        env.listing() // the listing sees the folder alive again
        advanceUntilIdle()

        assertEquals("only copy", env.provider.remote["/dir/new.txt"]?.let { String(it) })
        assertEquals(2, env.provider.uploadAttempts.get(), "the refusal and one offer")
        env.listing()
        advanceUntilIdle()
        assertEquals(2, env.provider.uploadAttempts.get(), "the same folder listed again offers nothing more")
        env.stop()
    }

    @Test
    fun `without a listing nothing is offered however long it takes, and a listing is what offers it`() = runTest {
        val env = env()
        env.refusedParentGone(this, retryDelays = listOf(60_000L, 120_000L))
        env.stop()
        env.start() // a restart
        env.listing(reset = true) // its listing sees the folder gone
        val restarted = HydrationImpl(env.engine, env.db, recoveryUploadScope = this, exhaustedRetryDelaysMs = listOf(60_000L))
        restarted.replayPendingUploads()
        env.folderBack()

        advanceTimeBy(24 * 60 * 60 * 1000L)
        env.mount.pause.pause("test", auto = false)
        env.mount.pause.resume()
        advanceUntilIdle()
        assertNull(env.provider.remote["/dir/new.txt"], "no timer, resume or start offers it")
        assertEquals(1, env.provider.uploadAttempts.get(), "only the first refusal reached the provider")

        env.listing()
        advanceUntilIdle()
        assertEquals("only copy", env.provider.remote["/dir/new.txt"]?.let { String(it) }, "the listing is the trigger")
        env.stop()
    }

    @Test
    fun `a parent seen alive but gone again by upload time is refused again with no second offer`() = runTest {
        val env = env()
        env.refusedParentGone(this, retryDelays = emptyList())
        env.listing(reset = true)
        env.folderBack()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        env.provider.uploadGate = gate
        env.listing() // seen alive: the offer is queued and waits at the provider
        runCurrent()
        env.provider.items.remove("/dir") // gone again before the bytes are sent
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, env.provider.uploadAttempts.get(), "the refusal and the one offer")
        assertNull(env.provider.remote["/dir/new.txt"])
        assertTrue(env.db.uploadRefusal("/dir/new.txt")?.endsWith("|parent_gone: /dir") == true, "refused again, and final")
        env.folderBack() // not listed: no offer
        env.mount.pause.pause("test", auto = false)
        env.mount.pause.resume()
        advanceTimeBy(24 * 60 * 60 * 1000L)
        advanceUntilIdle()
        assertEquals(2, env.provider.uploadAttempts.get(), "no second offer")
        env.stop()
    }

    // A file written below [folder] whose upload runs to its end.
    private suspend fun HydrationImpl.writeFile(
        folder: String,
        name: String,
    ) {
        val created = assertIs<CreateResult.Ok>(create("conn", "h-$name", "$folder/$name"))
        Files.writeString(created.cachePath, name)
        openForWrite("conn", "h-$name", "$folder/$name", created.cachePath)
    }

    // The folder was deleted and made again between two full listings: the second shows the same row, so it changes nothing.
    private suspend fun Env.recreatedUnseen(scope: CoroutineScope) {
        folderBack()
        listing(reset = true)
        if (scope is TestScope) scope.advanceUntilIdle()
        assertNull(provider.remote["/dir/new.txt"], "precondition: a listing of an unchanged row is no evidence")
        assertEquals(1, provider.uploadAttempts.get(), "precondition: only the first refusal reached the provider")
    }

    @Test
    fun `a sibling upload into the recreated folder offers its refused rows once and they upload`() = runTest {
        val env = env()
        val hydration = env.refusedParentGone(this, retryDelays = emptyList())
        env.recreatedUnseen(this)

        hydration.writeFile("/dir", "sibling.txt")
        advanceUntilIdle()

        assertEquals("sibling.txt", env.provider.remote["/dir/sibling.txt"]?.let { String(it) })
        assertEquals("only copy", env.provider.remote["/dir/new.txt"]?.let { String(it) })
        assertEquals(3, env.provider.uploadAttempts.get(), "the refusal, the sibling and one offer")
        env.stop()
    }

    @Test
    fun `a listing of the refused folder itself offers its refused rows once and they upload`() = runTest {
        val env = env()
        val hydration = env.refusedParentGone(this, retryDelays = emptyList())
        env.recreatedUnseen(this)

        org.krost.unidrive.engine.CursorState(env.db).setDeltaCursor("") // the folder is browsed while the first scan has not completed
        env.mount.listFolderLive("/dir") // the provider lists the folder (it holds nothing else, so the answer is null)
        advanceUntilIdle()

        assertEquals("only copy", env.provider.remote["/dir/new.txt"]?.let { String(it) })
        assertEquals(2, env.provider.uploadAttempts.get(), "the refusal and one offer")
        env.stop()
    }

    @Test
    fun `a successful upload elsewhere offers nothing`() = runTest {
        val env = env()
        val hydration = env.refusedParentGone(this, retryDelays = emptyList())
        env.recreatedUnseen(this)

        hydration.writeFile("/other", "elsewhere.txt")
        advanceUntilIdle()

        assertEquals("elsewhere.txt", env.provider.remote["/other/elsewhere.txt"]?.let { String(it) })
        assertNull(env.provider.remote["/dir/new.txt"], "the refused row was not offered")
        assertEquals(2, env.provider.uploadAttempts.get(), "the refusal and the upload elsewhere")
        env.stop()
    }

    @Test
    fun `with no evidence nothing is offered after 24 hours`() = runTest {
        val env = env()
        val hydration = env.refusedParentGone(this, retryDelays = listOf(60_000L))
        env.recreatedUnseen(this)

        advanceTimeBy(24 * 60 * 60 * 1000L)
        env.mount.pause.pause("test", auto = false)
        env.mount.pause.resume()
        advanceUntilIdle()

        assertNull(env.provider.remote["/dir/new.txt"])
        assertEquals(1, env.provider.uploadAttempts.get(), "nothing reached the provider after the refusal")
        env.stop()
    }
}
