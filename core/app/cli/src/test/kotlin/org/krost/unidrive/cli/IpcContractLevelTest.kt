package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.sync.ProfileMode
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `contract_level` (gkrost/unidrive-cli#445): the IPC surface a client can rely on at each level. The surface is every verb
 * the corpus pins (one `<verb>.ndjson` per verb), every capability the corpus `daemon.status` reply lists and every
 * capability of a profile mode. A change that adds to it appends one entry to [LEVEL_ADDS] and raises
 * [DaemonRuntime.IPC_CONTRACT_LEVEL] to the new count; an entry is never edited or removed, so level N always means the same
 * surface.
 */
class IpcContractLevelTest {
    private val levelAdds: List<Set<String>> = LEVEL_ADDS

    @Test
    fun the_contract_level_is_the_number_of_pinned_levels() {
        assertEquals(
            levelAdds.size,
            DaemonRuntime.IPC_CONTRACT_LEVEL,
            "IPC_CONTRACT_LEVEL must equal the number of entries in LEVEL_ADDS",
        )
        levelAdds.forEachIndexed { i, adds -> assertTrue(adds.isNotEmpty(), "level ${i + 1} adds nothing") }
        val seen = mutableSetOf<String>()
        levelAdds.forEachIndexed { i, adds ->
            val again = adds intersect seen
            assertTrue(again.isEmpty(), "level ${i + 1} adds $again, which an earlier level already has")
            seen += adds
        }
    }

    @Test
    fun the_ipc_surface_is_the_one_its_contract_level_pins() {
        val pinned = levelAdds.flatten().toSet()
        val current = currentSurface()
        assertEquals(
            pinned.sorted(),
            current.sorted(),
            "the IPC surface changed: append what was added as a new entry of LEVEL_ADDS and raise " +
                "DaemonRuntime.IPC_CONTRACT_LEVEL by one (an IPC change is additive, nothing is removed)",
        )
    }

    @Test
    fun daemon_status_corpus_names_the_contract_level() {
        val reply = Json.parseToJsonElement(statusCorpusReply()).jsonObject
        assertEquals(DaemonRuntime.IPC_CONTRACT_LEVEL, reply.getValue("contract_level").jsonPrimitive.int)
    }

    private fun currentSurface(): Set<String> {
        val dir = Paths.get(checkNotNull(javaClass.getResource("/ipc-contract")).toURI())
        val verbs =
            Files.list(dir).use { stream ->
                stream.map { it.fileName.toString() }.filter { it.endsWith(".ndjson") }.toList()
            }.map { "verb:" + it.removeSuffix(".ndjson") }
        // Every capability a corpus reply lists: the plain daemon.status fixture and the sessions that show the capabilities
        // a daemon lists only in some setups (views, share.create).
        val replied =
            Files.walk(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".ndjson") }.toList()
            }.flatMap { file ->
                Files.readAllLines(file).filter { it.isNotBlank() }.mapNotNull { line ->
                    (runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject)?.get("capabilities")
                }
            }.flatMap { capabilities -> capabilities.jsonArray.map { it.jsonPrimitive.content } }
        val capabilities = replied + ProfileMode.entries.flatMap { it.capabilities }
        return (verbs + capabilities.map { "capability:$it" }).toSet()
    }

    private fun statusCorpusReply(): String {
        val lines =
            checkNotNull(javaClass.getResourceAsStream("/ipc-contract/daemon.status.ndjson")).bufferedReader().readLines()
                .filter { it.isNotBlank() }
        return lines[1]
    }

    companion object {
        /** What each contract level added, level 1 first. Append only. */
        val LEVEL_ADDS: List<Set<String>> =
            listOf(
                // 1: the surface when contract_level was introduced.
                setOf(
                    "verb:auth.begin", "verb:auth.check", "verb:auth.complete", "verb:auth.login", "verb:auth.logout",
                    "verb:daemon.pause", "verb:daemon.resume", "verb:daemon.shutdown", "verb:daemon.status",
                    "verb:history.list", "verb:history.subscribe", "verb:hydration.cancel", "verb:hydration.close_handle",
                    "verb:hydration.create", "verb:hydration.dehydrate", "verb:hydration.handle_status", "verb:hydration.hydrate",
                    "verb:hydration.last_synced", "verb:hydration.list", "verb:hydration.locate", "verb:hydration.matches_local",
                    "verb:hydration.mkdir", "verb:hydration.open_read", "verb:hydration.open_write",
                    "verb:hydration.open_write_begin", "verb:hydration.refuse_name", "verb:hydration.rename",
                    "verb:hydration.rmdir", "verb:hydration.stat", "verb:hydration.subscribe", "verb:hydration.unlink",
                    "verb:ignore.explain", "verb:metrics.query", "verb:refresh.run", "verb:share.create",
                    "verb:sync.enumerate", "verb:sync.subscribe", "verb:view.page",
                    "capability:enumerate", "capability:hydration", "capability:hydration.cancel_read",
                    "capability:hydration.handle_status", "capability:hydration.list_content_id", "capability:hydration.list_live",
                    "capability:hydration.locate", "capability:hydration.matches_local", "capability:hydration.refuse_name",
                    "capability:hydration.stat", "capability:hydration.subscribe_replay", "capability:mount",
                    "capability:notifications.status", "capability:open_write_create_handle_ids", "capability:pause",
                    "capability:refresh", "capability:status", "capability:uploads.running", "capability:sync", "capability:apply",
                    "capability:sweep", "capability:views", "capability:view_page", "capability:share.create",
                ),
                // 2 (gkrost/unidrive-cli#445): hydration.list says whether a folder is listed whole, rmdir takes discard_local,
                // ignore.explain answers a batch of paths.
                setOf("capability:hydration.list_complete", "capability:hydration.rmdir_discard_local", "capability:ignore.explain_batch"),
            )
    }
}
