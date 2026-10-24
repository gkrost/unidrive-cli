package org.krost.unidrive.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/config-schema/config.example.toml` documents only keys the schema (and so the parser) has, and the
 * schema's poll_interval floor is the one the parser enforces.
 */
class ConfigExampleLockstepTest {
    private fun doc(name: String): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("docs/config-schema/$name") }
            .first { Files.exists(it) }

    private val schemaProps by lazy {
        Json.parseToJsonElement(Files.readString(doc("config.schema.json"))).jsonObject["properties"]!!.jsonObject
    }

    @Test
    fun `every key the example documents, commented out or not, is a schema property of its section`() {
        val general = schemaProps["general"]!!.jsonObject["properties"]!!.jsonObject.keys
        val provider =
            schemaProps["providers"]!!.jsonObject["additionalProperties"]!!.jsonObject["properties"]!!.jsonObject.keys
        val header = Regex("""^\[([^\]]+)]""")
        val assignment = Regex("""^#?\s*([a-z][a-z0-9_]*)\s*=\s*\S""")
        var section: String? = null
        var checked = 0
        Files.readAllLines(doc("config.example.toml")).forEachIndexed { index, line ->
            header.find(line)?.let {
                section = it.groupValues[1].substringBefore('.')
                return@forEachIndexed
            }
            val key = assignment.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            val known =
                when (section) {
                    "general" -> general
                    "providers" -> provider
                    else -> error("line ${index + 1}: '$key' sits outside [general] and [providers.*]")
                }
            assertTrue(key in known, "line ${index + 1}: '$key' in [$section] is not a schema property")
            checked++
        }
        assertTrue(checked > 10, "the scan must have found the documented keys, found $checked")
    }

    @Test
    fun `the example is valid TOML for the parser`() {
        SyncConfig.parseRaw(Files.readString(doc("config.example.toml")))
    }

    @Test
    fun `the schema floor of poll_interval is the one the parser enforces`() {
        val pollInterval = schemaProps["general"]!!.jsonObject["properties"]!!.jsonObject["poll_interval"]!!.jsonObject
        assertEquals(SyncConfig.MIN_POLL_INTERVAL_SECONDS, pollInterval["minimum"]!!.jsonPrimitive.int)
    }
}
