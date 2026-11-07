package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.sync.EnumerateResult
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.StateDatabase
import java.nio.file.Files
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#442: a job's in-flight slot is freed after its terminal event is emitted, never before. Between the
 * two, a second request would be accepted and its client would read the first job's `*.done` as its own.
 */
class RefreshSlotOrderTest {
    @Test
    fun `the refresh slot is still taken while refresh_done is emitted and free after`(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val db = StateDatabase(Files.createTempDirectory("ud-slot-db").resolve("state.db")).also { it.initialize() }
        val server = IpcServer(Files.createTempDirectory("ud-slot-sock").resolve("d.sock"))
        try {
            val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
            lateinit var handler: RefreshRpcHandler
            val takenAtEmit = mutableListOf<Boolean>()
            handler = RefreshRpcHandler(server, engine, db, scope, mountClientConnected = { true }, emit = { takenAtEmit.add(handler.isInFlight()) })

            handler.handle("c", """{"verb":"refresh.run"}""")
            handler.awaitInFlight()

            assertEquals(listOf(true), takenAtEmit, "the slot is still held when the terminal event goes out")
            assertFalse(handler.isInFlight(), "and it is free afterwards")
        } finally {
            scope.cancel()
            db.close()
        }
    }

    @Test
    fun `the enumerate slot is still taken while enumerate_done is emitted and free after`(): Unit = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val engine = RecordingEngine(enumerateResult = EnumerateResult(ok = true))
            lateinit var handler: EnumerateRpcHandler
            val takenAtEmit = mutableListOf<Boolean>()
            handler = EnumerateRpcHandler(engine, scope, emit = { takenAtEmit.add(handler.isInFlight()) })

            handler.handle("c", """{"verb":"sync.enumerate","reset":false}""")
            handler.awaitInFlight()

            assertEquals(listOf(true), takenAtEmit)
            assertFalse(handler.isInFlight())
            assertTrue(handler.handle("c", """{"verb":"sync.enumerate","reset":false}""").contains("\"ok\":true"))
        } finally {
            scope.cancel()
        }
    }
}
