package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.engine.ScopeSwap
import org.krost.unidrive.hydration.HydrationEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** gkrost/unidrive-cli#860: the wire side of `scope.set` over a scripted engine swap. */
class ScopeRpcHandlerTest {
    private fun effect(unsynced: List<String>, count: Int) =
        ScopeSwap.Effect(
            leaving = listOf("/B"), leavingMore = 0, entering = emptyList(), folders = 1, placeholders = 3, offlineCopies = 0,
            unsynced = unsynced, unsyncedCount = count, reEnumerate = false,
        )

    private class Scripted(
        val outcome: ScopeSwap.Outcome,
        val effect: ScopeSwap.Effect,
    ) : ScopeControl {
        val persisted = mutableListOf<List<String>>()

        override fun current(): List<String> = emptyList()

        override fun plan(scope: List<String>): ScopeSwap.Effect = effect

        override suspend fun apply(
            scope: List<String>,
            persist: (List<String>) -> Unit,
        ): ScopeSwap.Outcome = outcome
    }

    @Test
    fun scope_set_refusal_names_the_count_and_the_first_unsynced_paths() =
        runBlocking {
            val first = (1..ScopeSwap.UNSYNCED_PATHS_CAP).map { "/B/draft-$it.txt" }
            val refused = effect(first, count = 12)
            val events = mutableListOf<HydrationEvent>()
            val handler = ScopeRpcHandler(Scripted(ScopeSwap.Outcome.Refused(refused), refused), persist = {}, listRoot = { emptyList() }, publish = { events += it })
            val version = ScopeSwap.versionOf(emptyList())

            for (dryRun in listOf(true, false)) {
                val reply = Json.parseToJsonElement(handler.set("""{"verb":"scope.set","included":["/A"],"version":"$version","dry_run":$dryRun}""")).jsonObject
                assertEquals("unsynced_in_removed_scope", reply.getValue("error").jsonPrimitive.content, "dry_run=$dryRun: $reply")
                val unsynced = reply.getValue("effect").jsonObject.getValue("unsynced").jsonObject
                assertEquals(12, unsynced.getValue("count").jsonPrimitive.int, "every local-only file is counted")
                assertEquals(first, unsynced.getValue("paths").jsonArray.map { it.jsonPrimitive.content }, "the first ones are named")
            }
            assertTrue(events.isEmpty(), "a refused change sends no scope.changed")
        }
}
