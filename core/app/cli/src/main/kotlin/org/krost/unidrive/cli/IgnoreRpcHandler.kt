package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.sync.PathNormalizer
import org.krost.unidrive.sync.ignore.IgnoreLayer
import org.krost.unidrive.sync.ignore.UploadIgnore

/**
 * `ignore.explain` (archive#660; docs/dev/specs/ignore-layering-design.md): why the daemon does or does not upload a path.
 * A read verb (`IpcAuth.VERB_CLASSES`), answered from the daemon's own rules, so it says what the upload path
 * decides: the same rule files, read at the same moment.
 *
 * Request `{"path":"/a/b","is_dir":false}` (`is_dir` optional, default false). Reply:
 * `{"ok":true,"path","excluded","ignored","layer","file","line","pattern","decided_at","reason","exclude_pattern"}`.
 * - `excluded`: the upload path keeps the path local (an ignore rule or one of the profile's `exclude_patterns`);
 * - `ignored`, `layer` (`operational`, `unreadable`, `unidrive_ignore`, `gitignore`, `account`, `default`), `file` (the
 *   rule file, logical path; null for the built-in and account layers), `line`, `pattern`, `decided_at` (the path the
 *   rule matched: the path itself or an ignored folder above it): the deciding ignore rule, all null when none
 *   matched. A matching `!` rule decides too (`ignored` false);
 * - `reason`: why a held-back folder's rule file cannot be read (`layer` `unreadable`), else null;
 * - `exclude_pattern`: the first of the profile's `exclude_patterns` that matches, else null.
 *
 * Errors: `missing_path`, `invalid_path` (a `.` or `..` name, an empty name inside, a control character),
 * `invalid_is_dir`.
 *
 * Batch (gkrost/unidrive-cli#445): `{"paths":["/a",{"path":"/b","is_dir":true},...]}` answers each path as above, in
 * request order, from the same matcher: `{"ok":true,"results":[{...},...]}` without `ok` inside a result. A path the
 * single form would refuse answers `{"path":<as sent>,"error":"invalid_path"|"invalid_is_dir"|"missing_path"}` in its
 * place, and the rest are answered. With `paths` present the top-level `path` and `is_dir` are not read. At most
 * [MAX_BATCH] paths: more answers `{"ok":false,"error":"too_many_paths","max":MAX_BATCH}`; `paths` that is not an array
 * answers `invalid_paths`.
 */
class IgnoreRpcHandler(
    private val ignore: UploadIgnore,
    private val excludePattern: (path: String) -> String?,
) {
    fun explain(jsonRequest: String): String {
        val body = jsonRequest.asJsonObjectOrNull()
        body?.get("paths")?.let { return explainBatch(it) }
        return when (val one = verdictOf(body)) {
            is Verdict.Refused -> """{"ok":false,"error":"${one.error}"}"""
            is Verdict.Answered -> JsonObject(mapOf("ok" to JsonPrimitive(true)) + one.reply).toString()
        }
    }

    private fun explainBatch(paths: JsonElement): String {
        val items = paths as? JsonArray ?: return INVALID_PATHS
        if (items.size > MAX_BATCH) return """{"ok":false,"error":"too_many_paths","max":$MAX_BATCH}"""
        val results =
            JsonArray(
                items.map { item ->
                    // A bare string is a file path; an object carries path and is_dir as the single form does.
                    val one = if (item is JsonPrimitive && item.isString) JsonObject(mapOf("path" to item)) else item as? JsonObject
                    when (val v = verdictOf(one)) {
                        is Verdict.Answered -> v.reply
                        is Verdict.Refused ->
                            buildJsonObject {
                                put("path", (one?.get("path") as? JsonPrimitive)?.takeIf { it.isString }?.content)
                                put("error", v.error)
                            }
                    }
                },
            )
        return buildJsonObject {
            put("ok", true)
            put("results", results)
        }.toString()
    }

    private sealed class Verdict {
        class Refused(val error: String) : Verdict()

        class Answered(val reply: JsonObject) : Verdict()
    }

    private fun verdictOf(body: JsonObject?): Verdict {
        val raw = (body?.get("path") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return Verdict.Refused("missing_path")
        val isDir =
            when (val element = body["is_dir"]) {
                null, JsonNull -> false
                else ->
                    (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
                        ?: return Verdict.Refused("invalid_is_dir")
            }
        val path = logicalPath(raw) ?: return Verdict.Refused("invalid_path")
        val v = ignore.explain(path, isDir)
        val rule = v.rule
        val pattern = excludePattern(path)
        val ruleFile = rule != null && (v.layer == IgnoreLayer.UNIDRIVE_IGNORE || v.layer == IgnoreLayer.GITIGNORE || v.layer == IgnoreLayer.UNREADABLE)
        return Verdict.Answered(
            buildJsonObject {
                put("path", path)
                put("excluded", v.ignored || pattern != null)
                put("ignored", v.ignored)
                put("layer", v.layer?.name?.lowercase())
                put("file", if (ruleFile) "/" + rule!!.source else null)
                put("line", rule?.line?.takeIf { v.layer != IgnoreLayer.UNREADABLE })
                put("pattern", rule?.text?.takeIf { v.layer != IgnoreLayer.UNREADABLE })
                put("decided_at", if (rule != null) "/" + v.decidedAt else null)
                put("reason", rule?.text?.takeIf { v.layer == IgnoreLayer.UNREADABLE })
                put("exclude_pattern", pattern)
            },
        )
    }

    companion object {
        private const val INVALID_PATHS = """{"ok":false,"error":"invalid_paths"}"""

        /** The most paths one batched `ignore.explain` answers; a request of 64 KiB holds about this many short paths. */
        const val MAX_BATCH: Int = 1000
        private val NOT_A_NAME = setOf("", ".", "..")

        /** `/a/b` in NFC, or null for a path no entry can have. "" and "/" are the root. */
        internal fun logicalPath(raw: String): String? {
            val names = PathNormalizer.nfc(raw).trim('/').split('/')
            if (names == listOf("")) return "/"
            if (names.any { it in NOT_A_NAME || it.any { c -> c < ' ' } }) return null
            return "/" + names.joinToString("/")
        }
    }
}
