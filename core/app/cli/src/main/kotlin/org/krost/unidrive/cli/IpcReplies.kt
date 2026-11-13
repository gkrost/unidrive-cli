package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The line as a JSON object, or null when it is not one. */
internal fun String.asJsonObjectOrNull(): JsonObject? = runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull()

/** True only for a top-level `"ok":true` boolean; text that looks like it, in a string or a nested object, does not count. */
internal fun JsonObject.isOkReply(): Boolean = (this["ok"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true

/** The reply line [reply] is one JSON object with `"ok":true` ([isOkReply]); a substring match would also take text inside a string. */
internal fun isOkReplyLine(reply: String): Boolean = reply.asJsonObjectOrNull()?.isOkReply() == true

/**
 * gkrost/unidrive-cli#442: the strict JSON-type rule of the request members, in one place: the value as a JSON boolean
 * literal, else null (a string "true", a number, an object or `null` are no boolean).
 */
internal fun JsonElement.strictBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

/** As [strictBooleanOrNull], for a whole JSON number: the string "5" and 5.5 are no count. */
internal fun JsonElement.strictLongOrNull(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
