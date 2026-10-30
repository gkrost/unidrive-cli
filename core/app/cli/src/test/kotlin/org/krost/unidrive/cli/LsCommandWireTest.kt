package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#217: `ls` reads the daemon's `hydration.list` reply as JSON and writes its request as one JSON
 * line. Against a real authenticated server, so the framing and the reply bytes are the daemon's.
 */
class LsCommandWireTest {
    // The escaping of HydrationIpcHandler.jsonEsc: \\ \" \n \r \t, any other control character as \u00xx.
    private fun daemonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun entry(
        path: String,
        folder: Boolean = false,
        excluded: Boolean = false,
        remoteId: String = "id",
    ): String =
        """{"path":${daemonString(path)},"size":7,"mtime_ms":1000,"hydrated":false,"folder":$folder,""" +
            """"remote_modified_ms":null,"remote_id":${daemonString(remoteId)},"etag":null,"pending_upload":false,"error":false""" +
            (if (excluded) ""","excluded":true""" else "") + "}"

    private fun reply(vararg entries: String) = """{"ok":true,"entries":[${entries.joinToString(",")}]}"""

    @Test
    fun `a reply over 4 KiB with multi-byte names comes out byte-exact`() {
        // Three-byte characters only, so the 4096-byte read boundaries fall inside a character.
        val names = (0 until 120).map { "/" + "€".repeat(50) + "-%03d-ü😀".format(it) }
        val body = reply(*names.map { entry(it) }.toTypedArray())
        assertTrue(body.toByteArray().size > 16 * 1024, "the reply must span several read chunks")
        FakeIpcDaemon().on("hydration.list") { _, _ -> body }.start().use { daemon ->
            val parsed = LsCommand().queryDaemonView(daemon.endpoint, "/")

            assertNotNull(parsed)
            assertEquals(names, parsed.map { it.path })
            assertFalse(parsed.any { '�' in it.path }, "no name is mangled")
        }
    }

    @Test
    fun `a newline, a tab, a control character and a brace in a name round-trip through ls`() {
        val names = listOf("/line\nbreak", "/tab\tbed", "/ctrl\u0001char", "/brace}name", "/quote\"d", "/back\\slash", "/cr\rname")
        val body = reply(*names.map { entry(it) }.toTypedArray())
        FakeIpcDaemon().on("hydration.list") { _, _ -> body }.start().use { daemon ->
            val parsed = LsCommand().queryDaemonView(daemon.endpoint, "/")

            assertEquals(names, parsed?.map { it.path })
        }
    }

    @Test
    fun `parseListEntries decodes the escapes the daemon writes`() {
        val names = listOf("/line\nbreak", "/tab\tbed", "/ctrl\u0001char", "/brace}name", "/uniüc€de")

        val parsed = LsCommand.parseListEntries(reply(*names.map { entry(it) }.toTypedArray()))

        assertEquals(names, parsed.map { it.path })
    }

    @Test
    fun `a brace inside a string field does not hide the excluded flag`() {
        val parsed =
            LsCommand.parseListEntries(
                reply(entry("/odd}name", excluded = true, remoteId = "id}1"), entry("/plain", excluded = false, remoteId = "}")),
            )

        assertEquals(listOf(true, false), parsed.map { it.excluded })
    }

    @Test
    fun `a newline in the listed path stays inside one request line`() {
        val requests = CopyOnWriteArrayList<String>()
        FakeIpcDaemon().on("hydration.list") { _, line ->
            requests.add(line)
            reply()
        }.start().use { daemon ->
            LsCommand().queryDaemonView(daemon.endpoint, "/dir\nwith break")
        }

        val request = Json.parseToJsonElement(requests.single()).jsonObject
        assertEquals("hydration.list", request.getValue("verb").jsonPrimitive.content)
        assertEquals("/dir\nwith break", request.getValue("prefix").jsonPrimitive.content)
    }

    @Test
    fun `a listed path with a quote and a backslash reaches the daemon unchanged`() {
        val requests = CopyOnWriteArrayList<String>()
        FakeIpcDaemon().on("hydration.list") { _, line ->
            requests.add(line)
            reply()
        }.start().use { daemon ->
            LsCommand().queryDaemonView(daemon.endpoint, "/q\"uo\\te\u0001")
        }

        val request = Json.parseToJsonElement(requests.single()).jsonObject
        assertEquals("/q\"uo\\te\u0001", request.getValue("prefix").jsonPrimitive.content)
    }

    @Test
    fun `an error reply is not a listing`() {
        FakeIpcDaemon().on("hydration.list") { _, _ -> """{"ok":false,"error":"boom","detail":{"ok":true}}""" }.start().use { daemon ->
            assertEquals(null, LsCommand().queryDaemonView(daemon.endpoint, "/"))
        }
    }
}
